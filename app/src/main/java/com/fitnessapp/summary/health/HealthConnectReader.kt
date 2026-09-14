package com.fitnessapp.summary.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.fitnessapp.summary.data.DailySummary
import com.fitnessapp.summary.data.Workout
import com.fitnessapp.summary.debug.AppLog
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One day's worth of everything we pull out of Health Connect in a single pass. */
data class DayReadResult(
    val summary: DailySummary,
    val workouts: List<Workout>
)

/**
 * Turns Health Connect records into the app's own [DailySummary] / [Workout] rows.
 *
 * Two decisions worth knowing about, because they're what make the numbers here
 * match what Garmin Connect shows rather than being subtly off:
 *
 * 1. **Days are local-calendar days**, not 24h UTC windows. A day runs from local
 *    midnight to local midnight, so a late-evening run stays on the day it was run
 *    even for a user several timezones from UTC.
 *
 * 2. **A night's sleep belongs to the morning it ends on.** A session from 23:40
 *    Monday to 07:10 Tuesday is Tuesday's sleep, which is how Garmin (and every
 *    other tracker) reports it - attributing it to Monday would make "how did I
 *    sleep last night" answer with the wrong night.
 *
 * 3. **Every read here is scoped to Garmin's own records** ([garminOrigin]) - real
 *    bug, not a hypothetical: a user who connected Google Fit to Health Connect
 *    (for the scale pipeline - [HealthConnectScaleReader]) got a step total of
 *    19350 in the app against 10787 in Garmin Connect itself, because Google Fit
 *    also writes StepsRecord from the phone's own pedometer, and an unscoped
 *    `StepsRecord.COUNT_TOTAL` aggregate sums every app's records in the window,
 *    not just Garmin's. Weight/body composition is the one deliberate exception -
 *    that section wants every source and merges them explicitly, because Garmin
 *    doesn't write most of it at all.
 *
 * Each section is independently guarded: with a partially granted permission set,
 * reading what we *can* read beats failing the whole day. A denied read leaves its
 * fields at 0, which the rest of the app already treats as "no data".
 */
class HealthConnectReader(private val manager: HealthConnectManager) {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /**
     * Daily activity is scoped to Garmin's own records - see [HealthConnectManager.GARMIN_PACKAGE].
     * Weight/body composition ([HealthConnectScaleReader]) deliberately does NOT use this:
     * that section wants every source, Garmin included, and merges them explicitly.
     */
    private val garminOrigin = setOf(DataOrigin(HealthConnectManager.GARMIN_PACKAGE))

    companion object {
        /**
         * "Every app that wrote to Health Connect" - an empty filter is how the API
         * itself spells no filtering. Named rather than inlined because an empty set
         * at a call site reads like an oversight, and this one is a decision.
         */
        val ANY_ORIGIN: Set<DataOrigin> = emptySet()
    }

    /**
     * Reads one day, by default only Garmin's own records.
     *
     * [origins] is the escape hatch for history: pass [ANY_ORIGIN] and Health Connect
     * stops filtering, returning whatever every app on the phone wrote. That is the
     * right thing to do **only for a day Garmin knows nothing about** - see
     * `HealthSyncManager.syncRange`, which is the one caller that does it and only
     * after the Garmin-scoped read came back empty. Calling it on a day Garmin covers
     * would bring back the 19350-against-10787 step bug this filter exists to prevent.
     */
    suspend fun readDay(
        date: LocalDate,
        origins: Set<DataOrigin> = garminOrigin
    ): DayReadResult? {
        val client = manager.clientOrNull() ?: run {
            AppLog.w("HealthConnectReader", "Health Connect недоступен, день $date не читаю")
            return null
        }

        val dayStart = date.atStartOfDay(zone).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()

        val movement = readMovement(client, dayStart, dayEnd, origins)
        val heart = readHeart(client, dayStart, dayEnd, origins)
        val sleep = readSleepEndingOn(client, date, origins)
        val workouts = readWorkouts(client, date, dayStart, dayEnd, origins)

        // Which apps actually produced this day. Only recorded when the read was NOT
        // scoped to Garmin: on a scoped read the answer is Garmin by construction, and
        // storing it would just be noise. On an unscoped read it is the difference
        // between "10 000 steps" and "10 000 steps, and they came off the phone".
        val sourceApps = if (origins.isEmpty()) {
            (movement.origins + heart.origins + sleep.origins).sorted().joinToString(",")
        } else {
            ""
        }

        val summary = DailySummary(
            dateEpochDay = date.toEpochDay(),
            steps = movement.steps,
            activeCaloriesKcal = movement.activeKcal,
            totalCaloriesKcal = movement.totalKcal,
            distanceMeters = movement.distanceMeters,
            restingHeartRate = heart.resting,
            avgHeartRate = heart.avg,
            minHeartRate = heart.min,
            maxHeartRate = heart.max,
            sleepTotalMinutes = sleep.totalMinutes,
            sleepDeepMinutes = sleep.deepMinutes,
            sleepLightMinutes = sleep.lightMinutes,
            sleepRemMinutes = sleep.remMinutes,
            sleepAwakeMinutes = sleep.awakeMinutes,
            workoutCount = workouts.size,
            workoutMinutes = workouts.sumOf { it.durationMinutes },
            sourceApps = sourceApps
        )

        // Presence/absence only, never the actual values - this is what turns "many
        // things don't come through" from a guess into something diagnosable: it says
        // exactly which categories were empty for this day, correlated by timestamp
        // with any "Не удалось прочитать" warning just above it in the log.
        AppLog.d(
            "HealthConnectReader",
            "$date: шаги=${movement.steps > 0} дистанция=${movement.distanceMeters > 0} " +
                "калории=${movement.activeKcal > 0} пульс=${heart.avg > 0} " +
                "пульс_покоя=${heart.resting > 0} сон=${sleep.totalMinutes > 0} " +
                "тренировок=${workouts.size}"
        )
        return DayReadResult(summary, workouts)
    }

    // ---- movement -----------------------------------------------------------

    private data class Movement(
        val steps: Long = 0,
        val activeKcal: Int = 0,
        val totalKcal: Int = 0,
        val distanceMeters: Int = 0,
        /** Packages that contributed, straight from the aggregate - never guessed. */
        val origins: Set<String> = emptySet()
    )

    private suspend fun readMovement(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
        origins: Set<DataOrigin>
    ): Movement = runCatchingRead("шаги/дистанция/калории") {
        val result = client.aggregate(
            AggregateRequest(
                metrics = setOf(
                    StepsRecord.COUNT_TOTAL,
                    ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                    TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                    DistanceRecord.DISTANCE_TOTAL
                ),
                timeRangeFilter = TimeRangeFilter.between(start, end),
                dataOriginFilter = origins
            )
        )
        Movement(
            steps = result[StepsRecord.COUNT_TOTAL] ?: 0L,
            activeKcal = result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]
                ?.inKilocalories?.toInt() ?: 0,
            totalKcal = result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]
                ?.inKilocalories?.toInt() ?: 0,
            distanceMeters = result[DistanceRecord.DISTANCE_TOTAL]?.inMeters?.toInt() ?: 0,
            origins = result.dataOrigins.map { it.packageName }.toSet()
        )
    } ?: Movement()

    // ---- heart --------------------------------------------------------------

    private data class Heart(
        val resting: Int = 0,
        val avg: Int = 0,
        val min: Int = 0,
        val max: Int = 0,
        val origins: Set<String> = emptySet()
    )

    private suspend fun readHeart(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
        origins: Set<DataOrigin>
    ): Heart {
        val range = TimeRangeFilter.between(start, end)

        val beats = runCatchingRead("пульс (avg/min/max)") {
            val result = client.aggregate(
                AggregateRequest(
                    metrics = setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_MAX),
                    timeRangeFilter = range,
                    dataOriginFilter = origins
                )
            )
            BeatsRead(
                avg = result[HeartRateRecord.BPM_AVG]?.toInt() ?: 0,
                min = result[HeartRateRecord.BPM_MIN]?.toInt() ?: 0,
                max = result[HeartRateRecord.BPM_MAX]?.toInt() ?: 0,
                origins = result.dataOrigins.map { it.packageName }.toSet()
            )
        } ?: BeatsRead()

        // Resting HR is its own record type, aggregated separately: Garmin writes one
        // value per day, and averaging it in with continuous HR would destroy it.
        val resting = runCatchingRead("пульс покоя") {
            val result = client.aggregate(
                AggregateRequest(
                    metrics = setOf(RestingHeartRateRecord.BPM_AVG),
                    timeRangeFilter = range,
                    dataOriginFilter = origins
                )
            )
            result[RestingHeartRateRecord.BPM_AVG]?.toInt() ?: 0
        } ?: 0

        return Heart(
            resting = resting,
            avg = beats.avg,
            min = beats.min,
            max = beats.max,
            origins = beats.origins
        )
    }

    private data class BeatsRead(
        val avg: Int = 0,
        val min: Int = 0,
        val max: Int = 0,
        val origins: Set<String> = emptySet()
    )

    // ---- sleep --------------------------------------------------------------

    private data class Sleep(
        val totalMinutes: Int = 0,
        val deepMinutes: Int = 0,
        val lightMinutes: Int = 0,
        val remMinutes: Int = 0,
        val awakeMinutes: Int = 0,
        val origins: Set<String> = emptySet()
    )

    /**
     * Reads a window wide enough to catch a night that started the previous evening,
     * then keeps only the sessions that *ended* on [date] - see the class comment.
     */
    private suspend fun readSleepEndingOn(
        client: HealthConnectClient,
        date: LocalDate,
        origins: Set<DataOrigin>
    ): Sleep =
        runCatchingRead("сон за $date") {
            val windowStart = date.minusDays(1).atStartOfDay(zone).toInstant()
            val windowEnd = date.plusDays(1).atStartOfDay(zone).toInstant()

            val sessions = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(windowStart, windowEnd),
                    dataOriginFilter = origins
                )
            ).records.filter { it.endTime.atZone(zone).toLocalDate() == date }

            var deep = 0L
            var light = 0L
            var rem = 0L
            var awake = 0L
            var unstaged = 0L

            for (session in sessions) {
                if (session.stages.isEmpty()) {
                    // Some sources write only the session envelope with no stage
                    // breakdown. Counting the whole span as sleep is better than
                    // reporting a night of zero.
                    unstaged += Duration.between(session.startTime, session.endTime).toMinutes()
                    continue
                }
                for (stage in session.stages) {
                    val minutes = Duration.between(stage.startTime, stage.endTime).toMinutes()
                    when (stage.stage) {
                        SleepSessionRecord.STAGE_TYPE_DEEP -> deep += minutes
                        SleepSessionRecord.STAGE_TYPE_LIGHT -> light += minutes
                        SleepSessionRecord.STAGE_TYPE_REM -> rem += minutes
                        SleepSessionRecord.STAGE_TYPE_AWAKE,
                        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> awake += minutes
                        // SLEEPING = "asleep, stage unknown". Real sleep time, just
                        // not attributable to a stage, so it lands in the total only.
                        SleepSessionRecord.STAGE_TYPE_SLEEPING -> unstaged += minutes
                        // OUT_OF_BED / UNKNOWN are deliberately not counted as either
                        // sleep or awake-in-bed time.
                        else -> Unit
                    }
                }
            }

            Sleep(
                totalMinutes = (deep + light + rem + unstaged).toInt(),
                deepMinutes = deep.toInt(),
                lightMinutes = light.toInt(),
                remMinutes = rem.toInt(),
                awakeMinutes = awake.toInt(),
                origins = sessions.map { it.metadata.dataOrigin.packageName }.toSet()
            )
        } ?: Sleep()

    // ---- workouts -----------------------------------------------------------

    private suspend fun readWorkouts(
        client: HealthConnectClient,
        date: LocalDate,
        dayStart: Instant,
        dayEnd: Instant,
        origins: Set<DataOrigin>
    ): List<Workout> = runCatchingRead("тренировки за $date") {
        val sessions = client.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(dayStart, dayEnd),
                dataOriginFilter = origins
            )
        ).records

        sessions.map { session ->
            val sessionRange = TimeRangeFilter.between(session.startTime, session.endTime)

            // Distance/calories/HR aren't fields on the session - they're separate
            // record streams that happen to overlap it, so each session needs its own
            // aggregate over its own time span. One extra call per workout, and there
            // are only ever a handful of workouts in a day.
            val metrics = runCatchingRead("метрики тренировки ${session.metadata.id}") {
                client.aggregate(
                    AggregateRequest(
                        metrics = setOf(
                            DistanceRecord.DISTANCE_TOTAL,
                            ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                            HeartRateRecord.BPM_AVG,
                            HeartRateRecord.BPM_MAX
                        ),
                        timeRangeFilter = sessionRange,
                        dataOriginFilter = origins
                    )
                )
            }

            Workout(
                recordId = session.metadata.id,
                dateEpochDay = date.toEpochDay(),
                startTimeMillis = session.startTime.toEpochMilli(),
                endTimeMillis = session.endTime.toEpochMilli(),
                exerciseType = session.exerciseType,
                title = session.title.orEmpty(),
                durationMinutes = Duration.between(session.startTime, session.endTime)
                    .toMinutes().toInt(),
                distanceMeters = metrics?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters?.toInt() ?: 0,
                activeCaloriesKcal = metrics?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
                    ?.inKilocalories?.toInt() ?: 0,
                avgHeartRate = metrics?.get(HeartRateRecord.BPM_AVG)?.toInt() ?: 0,
                maxHeartRate = metrics?.get(HeartRateRecord.BPM_MAX)?.toInt() ?: 0
            )
        }
    } ?: emptyList()

    /**
     * Swallows a failed read and returns null instead. Health Connect throws for a
     * whole family of ordinary, non-exceptional situations - a permission the user
     * revoked, a record type the provider has never written, the provider being
     * mid-update - and none of them should abort the other sections of the day.
     *
     * The swallow is still deliberate and still correct (see class comment), but it
     * used to leave zero trace anywhere - the exact bug pattern behind "Garmin data
     * doesn't come through, no idea why". Every catch here now logs [section] and the
     * exception's own class+message (never any data), so a genuine failure - a
     * specific record type not permitted, a provider error - shows up in the log the
     * user can share instead of just quietly becoming a 0 on screen.
     */
    private inline fun <T> runCatchingRead(section: String, block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        AppLog.w("HealthConnectReader", "Не удалось прочитать: $section", e)
        null
    }
}
