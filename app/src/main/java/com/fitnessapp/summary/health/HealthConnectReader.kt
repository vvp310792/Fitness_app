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
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.fitnessapp.summary.data.DailySummary
import com.fitnessapp.summary.data.Workout
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
 * Each section is independently guarded: with a partially granted permission set,
 * reading what we *can* read beats failing the whole day. A denied read leaves its
 * fields at 0, which the rest of the app already treats as "no data".
 */
class HealthConnectReader(private val manager: HealthConnectManager) {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    suspend fun readDay(date: LocalDate): DayReadResult? {
        val client = manager.clientOrNull() ?: return null

        val dayStart = date.atStartOfDay(zone).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()

        val movement = readMovement(client, dayStart, dayEnd)
        val heart = readHeart(client, dayStart, dayEnd)
        val sleep = readSleepEndingOn(client, date)
        val workouts = readWorkouts(client, date, dayStart, dayEnd)

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
            workoutMinutes = workouts.sumOf { it.durationMinutes }
        )
        return DayReadResult(summary, workouts)
    }

    // ---- movement -----------------------------------------------------------

    private data class Movement(
        val steps: Long = 0,
        val activeKcal: Int = 0,
        val totalKcal: Int = 0,
        val distanceMeters: Int = 0
    )

    private suspend fun readMovement(
        client: HealthConnectClient,
        start: Instant,
        end: Instant
    ): Movement = runCatchingRead {
        val result = client.aggregate(
            AggregateRequest(
                metrics = setOf(
                    StepsRecord.COUNT_TOTAL,
                    ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                    TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                    DistanceRecord.DISTANCE_TOTAL
                ),
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        )
        Movement(
            steps = result[StepsRecord.COUNT_TOTAL] ?: 0L,
            activeKcal = result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]
                ?.inKilocalories?.toInt() ?: 0,
            totalKcal = result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]
                ?.inKilocalories?.toInt() ?: 0,
            distanceMeters = result[DistanceRecord.DISTANCE_TOTAL]?.inMeters?.toInt() ?: 0
        )
    } ?: Movement()

    // ---- heart --------------------------------------------------------------

    private data class Heart(
        val resting: Int = 0,
        val avg: Int = 0,
        val min: Int = 0,
        val max: Int = 0
    )

    private suspend fun readHeart(
        client: HealthConnectClient,
        start: Instant,
        end: Instant
    ): Heart {
        val range = TimeRangeFilter.between(start, end)

        val beats = runCatchingRead {
            val result = client.aggregate(
                AggregateRequest(
                    metrics = setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_MAX),
                    timeRangeFilter = range
                )
            )
            Triple(
                result[HeartRateRecord.BPM_AVG]?.toInt() ?: 0,
                result[HeartRateRecord.BPM_MIN]?.toInt() ?: 0,
                result[HeartRateRecord.BPM_MAX]?.toInt() ?: 0
            )
        } ?: Triple(0, 0, 0)

        // Resting HR is its own record type, aggregated separately: Garmin writes one
        // value per day, and averaging it in with continuous HR would destroy it.
        val resting = runCatchingRead {
            val result = client.aggregate(
                AggregateRequest(
                    metrics = setOf(RestingHeartRateRecord.BPM_AVG),
                    timeRangeFilter = range
                )
            )
            result[RestingHeartRateRecord.BPM_AVG]?.toInt() ?: 0
        } ?: 0

        return Heart(resting = resting, avg = beats.first, min = beats.second, max = beats.third)
    }

    // ---- sleep --------------------------------------------------------------

    private data class Sleep(
        val totalMinutes: Int = 0,
        val deepMinutes: Int = 0,
        val lightMinutes: Int = 0,
        val remMinutes: Int = 0,
        val awakeMinutes: Int = 0
    )

    /**
     * Reads a window wide enough to catch a night that started the previous evening,
     * then keeps only the sessions that *ended* on [date] - see the class comment.
     */
    private suspend fun readSleepEndingOn(client: HealthConnectClient, date: LocalDate): Sleep =
        runCatchingRead {
            val windowStart = date.minusDays(1).atStartOfDay(zone).toInstant()
            val windowEnd = date.plusDays(1).atStartOfDay(zone).toInstant()

            val sessions = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(windowStart, windowEnd)
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
                awakeMinutes = awake.toInt()
            )
        } ?: Sleep()

    // ---- workouts -----------------------------------------------------------

    private suspend fun readWorkouts(
        client: HealthConnectClient,
        date: LocalDate,
        dayStart: Instant,
        dayEnd: Instant
    ): List<Workout> = runCatchingRead {
        val sessions = client.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(dayStart, dayEnd)
            )
        ).records

        sessions.map { session ->
            val sessionRange = TimeRangeFilter.between(session.startTime, session.endTime)

            // Distance/calories/HR aren't fields on the session - they're separate
            // record streams that happen to overlap it, so each session needs its own
            // aggregate over its own time span. One extra call per workout, and there
            // are only ever a handful of workouts in a day.
            val metrics = runCatchingRead {
                client.aggregate(
                    AggregateRequest(
                        metrics = setOf(
                            DistanceRecord.DISTANCE_TOTAL,
                            ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                            HeartRateRecord.BPM_AVG,
                            HeartRateRecord.BPM_MAX
                        ),
                        timeRangeFilter = sessionRange
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
     */
    private inline fun <T> runCatchingRead(block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        null
    }
}
