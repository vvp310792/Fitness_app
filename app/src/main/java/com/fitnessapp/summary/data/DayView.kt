package com.fitnessapp.summary.data

/**
 * The day as it should be *shown*: Garmin first, Health Connect only where Garmin is silent.
 *
 * Garmin Connect is the first source. Health Connect is a copy Garmin writes into - and
 * not only Garmin: any app on the phone may write the same record types, which is exactly
 * how steps once read 19350 here against 10787 in Garmin Connect itself (see CLAUDE.md,
 * "Подключение Google Fit к Health Connect ломает подсчёт активности"). Reading the copy
 * in preference to the original was backwards, and for sleep it was worse than backwards:
 * Garmin Connect writes sleep to Health Connect irregularly - 8 nights out of 56 on this
 * user's real log - so the Week screen was averaging the few nights that leaked through.
 *
 * Merged FIELD BY FIELD, not whole record. The two sources fail independently: Garmin can
 * have the day's steps with no sleep row (the sleep endpoint answers 200 with an empty
 * body for this account), and Health Connect can hold a night Garmin's own API never
 * returned. Picking one record wholesale would throw away whatever the other one had.
 *
 * Sleep is the one exception to field-by-field: the stages come from the SAME source as
 * that night's total, all or nothing. A total from Garmin with deep/REM from Health
 * Connect would be parts measured against someone else's whole.
 *
 * The result is a VIEW, never a row: it is never written back through
 * [SummaryRepository.upsertDay]. `daily_summary` stays the Health Connect table it has
 * always been, so a future re-read of Health Connect can't be confused by Garmin numbers
 * that were merged in for display.
 */
object DayView {

    /** Null when neither source has anything for the date - the caller shows its empty state. */
    fun merge(
        health: DailySummary?,
        garmin: GarminDailyExtra?,
        sleep: GarminSleep?
    ): DailySummary? {
        val hc = health?.takeUnless { it.isEmpty }
        val g = garmin?.takeUnless { it.isEmpty }
        val s = sleep?.takeUnless { it.isEmpty }
        if (hc == null && g == null && s == null) return null

        val base = hc ?: DailySummary(
            dateEpochDay = g?.dateEpochDay ?: s?.dateEpochDay ?: return null
        )

        val withMovement = base.copy(
            steps = g?.totalSteps.orFirst(base.steps),
            activeCaloriesKcal = g?.activeKilocalories.orFirst(base.activeCaloriesKcal),
            totalCaloriesKcal = g?.totalKilocalories.orFirst(base.totalCaloriesKcal),
            distanceMeters = g?.totalDistanceMeters.orFirst(base.distanceMeters),
            restingHeartRate = g?.restingHeartRate.orFirst(base.restingHeartRate),
            minHeartRate = g?.minHeartRate.orFirst(base.minHeartRate),
            maxHeartRate = g?.maxHeartRate.orFirst(base.maxHeartRate)
            // avgHeartRate stays Health Connect's: Garmin's daily summary has no average,
            // only min/max/resting, and inventing one from those would be a new number
            // rather than a first-hand one.
        )

        // Whole-night swap, stages included - see the class comment.
        return if (s != null && s.sleepMinutes > 0) {
            withMovement.copy(
                sleepTotalMinutes = s.sleepMinutes,
                sleepDeepMinutes = s.deepMinutes,
                sleepLightMinutes = s.lightMinutes,
                sleepRemMinutes = s.remMinutes,
                sleepAwakeMinutes = s.awakeMinutes
            )
        } else {
            withMovement
        }
    }

    /** Merges a whole range, keyed by date, for the Week screen and the trend series. */
    fun mergeRange(
        health: List<DailySummary>,
        garmin: List<GarminDailyExtra>,
        sleep: List<GarminSleep>
    ): List<DailySummary> {
        val hcByDay = health.associateBy { it.dateEpochDay }
        val gByDay = garmin.associateBy { it.dateEpochDay }
        val sByDay = sleep.associateBy { it.dateEpochDay }
        // Union of the three, not just the Health Connect days: a day that only Garmin
        // knows about must still appear in the week, or the week's averages are computed
        // over a set of days chosen by the weaker source.
        return (hcByDay.keys + gByDay.keys + sByDay.keys)
            .sorted()
            .mapNotNull { merge(hcByDay[it], gByDay[it], sByDay[it]) }
    }
}

/** 0 means "no data" everywhere in this app, so a zero from Garmin defers to Health Connect. */
private fun Long?.orFirst(fallback: Long): Long = if (this != null && this > 0L) this else fallback

private fun Int?.orFirst(fallback: Int): Int = if (this != null && this > 0) this else fallback
