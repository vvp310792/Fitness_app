package com.fitnessapp.summary.data

import com.fitnessapp.summary.sync.FirestoreSyncManager
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * A whole training week, rolled up from its [DailySummary] rows.
 *
 * The averages here are the reason this is a computed type rather than a SQL SUM:
 * they're averaged over the days that actually *have* the metric, not over a flat 7.
 * A watch left on the charger on Saturday should not drag the week's resting heart
 * rate toward zero - a missing day is missing, not a zero. [daysWithData],
 * [nightsWithSleep] and [daysWithMovement] are exposed alongside the averages so the
 * UI can be honest about how many days each number is actually based on.
 */
data class WeekSummary(
    val startEpochDay: Long,
    val endEpochDay: Long,
    val daysWithData: Int,
    val daysWithMovement: Int,
    val nightsWithSleep: Int,

    val totalSteps: Long,
    val avgSteps: Int,
    val totalActiveCaloriesKcal: Int,
    /**
     * Every kilocalorie the day burned, resting metabolism included - Garmin's own
     * "total", not the active part. Kept beside [totalActiveCaloriesKcal] rather than
     * instead of it: they answer different questions ("how hard was the week" vs "what did
     * the week cost"), and one derived from the other by subtraction is the resting burn,
     * which is worth showing too.
     */
    val totalCaloriesKcal: Int,
    val avgTotalCaloriesKcal: Int,
    val avgActiveCaloriesKcal: Int,
    val totalDistanceMeters: Int,

    val avgRestingHeartRate: Int,
    val minRestingHeartRate: Int,
    val maxHeartRate: Int,

    val avgSleepMinutes: Int,
    val totalSleepMinutes: Int,
    val avgDeepSleepMinutes: Int,
    val avgRemSleepMinutes: Int,

    val workoutCount: Int,
    val workoutMinutes: Int,
    val workoutDistanceMeters: Int,
    val workoutCaloriesKcal: Int
) {
    val isEmpty: Boolean get() = daysWithData == 0
}

class SummaryRepository(
    private val dao: DailySummaryDao,
    private val workoutDao: WorkoutDao,
    private val syncManager: FirestoreSyncManager? = null,
    private val currentUid: () -> String? = { null }
) {

    fun observeDay(date: LocalDate): Flow<DailySummary?> = dao.observeDay(date.toEpochDay())

    fun observeRange(from: LocalDate, to: LocalDate): Flow<List<DailySummary>> =
        dao.observeRange(from.toEpochDay(), to.toEpochDay())

    fun observeAll(): Flow<List<DailySummary>> = dao.observeAll()

    suspend fun getDayOnce(date: LocalDate): DailySummary? = dao.getDayOnce(date.toEpochDay())

    suspend fun getAllOnce(): List<DailySummary> = dao.getAllOnce()

    suspend fun latestSyncedDay(): LocalDate? = dao.getLatestDay()?.let(LocalDate::ofEpochDay)

    /**
     * Stores a day read out of Health Connect.
     *
     * A completely empty day is deliberately *not* written. Health Connect returning
     * nothing means "this device has no data for that date", which is not the same
     * as "nothing happened" - the watch may simply not have synced yet, or the day's
     * data may live on another phone. Writing a row of zeros would turn that silence
     * into a fact, overwrite a real day that Firestore had already synced in from
     * elsewhere, and there'd be no way to tell the two apart afterwards.
     */
    suspend fun upsertDay(summary: DailySummary) {
        if (summary.isEmpty) return
        dao.upsert(summary)
        val uid = currentUid()
        if (uid != null && syncManager != null) {
            syncManager.pushDailySummary(uid, summary)
        }
    }

    /** Merges a day that arrived from Firestore. Skips the push-back, to avoid a loop. */
    suspend fun upsertFromSync(summary: DailySummary) {
        dao.upsert(summary)
    }

    companion object {

        /**
         * Rolls [days] and [workouts] up into one [WeekSummary]. Pure function, no I/O -
         * the callers hand it whatever Room gave them.
         *
         * [workouts] is passed in rather than derived from [DailySummary.workoutCount]
         * because the day rows only carry a count and a duration; distance and calories
         * per session live on the workout rows themselves.
         */
        fun computeWeekSummary(
            weekStart: LocalDate,
            days: List<DailySummary>,
            workouts: List<Workout>
        ): WeekSummary {
            val startEpochDay = weekStart.toEpochDay()
            val endEpochDay = weekStart.plusDays(6).toEpochDay()
            val inWeek = days.filter { it.dateEpochDay in startEpochDay..endEpochDay }

            val withMovement = inWeek.filter { it.hasMovement }
            val withSleep = inWeek.filter { it.hasSleep }
            val withResting = inWeek.filter { it.restingHeartRate > 0 }

            val totalSteps = inWeek.sumOf { it.steps }

            return WeekSummary(
                startEpochDay = startEpochDay,
                endEpochDay = endEpochDay,
                daysWithData = inWeek.size,
                daysWithMovement = withMovement.size,
                nightsWithSleep = withSleep.size,

                totalSteps = totalSteps,
                avgSteps = withMovement.averageIntOf { it.steps.toInt() },
                totalActiveCaloriesKcal = inWeek.sumOf { it.activeCaloriesKcal },
                totalCaloriesKcal = inWeek.sumOf { it.totalCaloriesKcal },
                // Averaged over the days that actually recorded calories, like every
                // other average here - a day on the charger must not halve the mean.
                avgTotalCaloriesKcal = inWeek.filter { it.totalCaloriesKcal > 0 }.averageIntOf { it.totalCaloriesKcal },
                avgActiveCaloriesKcal = inWeek.filter { it.activeCaloriesKcal > 0 }.averageIntOf { it.activeCaloriesKcal },
                totalDistanceMeters = inWeek.sumOf { it.distanceMeters },

                avgRestingHeartRate = withResting.averageIntOf { it.restingHeartRate },
                minRestingHeartRate = withResting.minOfOrNull { it.restingHeartRate } ?: 0,
                maxHeartRate = inWeek.maxOfOrNull { it.maxHeartRate } ?: 0,

                avgSleepMinutes = withSleep.averageIntOf { it.sleepTotalMinutes },
                totalSleepMinutes = withSleep.sumOf { it.sleepTotalMinutes },
                avgDeepSleepMinutes = withSleep.averageIntOf { it.sleepDeepMinutes },
                avgRemSleepMinutes = withSleep.averageIntOf { it.sleepRemMinutes },

                workoutCount = workouts.size,
                workoutMinutes = workouts.sumOf { it.durationMinutes },
                workoutDistanceMeters = workouts.sumOf { it.distanceMeters },
                workoutCaloriesKcal = workouts.sumOf { it.activeCaloriesKcal }
            )
        }

        /** Mean over the list, rounded, or 0 for an empty list - never a divide-by-zero NaN. */
        private inline fun <T> List<T>.averageIntOf(selector: (T) -> Int): Int =
            if (isEmpty()) 0 else Math.round(sumOf { selector(it) }.toDouble() / size).toInt()
    }
}
