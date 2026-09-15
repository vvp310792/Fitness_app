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
        sleep: GarminSleep?,
        fit: FitDay? = null
    ): DailySummary? {
        val hc = health?.takeUnless { it.isEmpty }
        val g = garmin?.takeUnless { it.isEmpty }
        val s = sleep?.takeUnless { it.isEmpty }
        val f = fit?.takeUnless { it.isEmpty }
        if (hc == null && g == null && s == null && f == null) return null

        val base = hc ?: DailySummary(
            dateEpochDay = g?.dateEpochDay ?: s?.dateEpochDay ?: f?.dateEpochDay ?: return null
        )

        val withMovement = base.copy(
            // The provenance label describes the Health Connect row, and survives only
            // while Health Connect is the day's ONLY source. The moment Garmin supplies
            // anything, most of the numbers on screen are Garmin's, and a line reading
            // "источник: Google Fit" under them would be a true fact about the wrong
            // thing - see DailySummary.sourceApps.
            sourceApps = when {
                g != null || s != null -> ""
                hc != null -> base.sourceApps
                // Nothing but the Takeout archive knows about this day, and saying so is the
                // whole point of the label: these are years when the source was a phone or a
                // Mi Band, not a watch.
                f != null -> GOOGLE_FIT_PACKAGE
                else -> base.sourceApps
            },
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

        // Google Fit fills only what is still missing. It is the weakest source here by
        // construction: a Takeout archive is frozen at the moment it was cut, and its numbers
        // come from a phone pedometer or a Mi Band. On any day the watch covered it must
        // contribute nothing - which "0 means no data" already guarantees, field by field.
        val withFit = if (f == null) withMovement else withMovement.copy(
            steps = withMovement.steps.orKeep(f.steps),
            totalCaloriesKcal = withMovement.totalCaloriesKcal.orKeep(f.caloriesKcal),
            distanceMeters = withMovement.distanceMeters.orKeep(f.distanceMeters),
            restingHeartRate = withMovement.restingHeartRate.orKeep(f.restingHeartRate),
            avgHeartRate = withMovement.avgHeartRate.orKeep(f.avgHeartRate),
            minHeartRate = withMovement.minHeartRate.orKeep(f.minHeartRate),
            maxHeartRate = withMovement.maxHeartRate.orKeep(f.maxHeartRate),
            // The active half comes from Google's own basal stream, not from a split this
            // app invents: total minus `calories.bmr`. It stays 0 on days the archive has
            // no basal figure for, and 0 still means "no data".
            activeCaloriesKcal = withMovement.activeCaloriesKcal.orKeep(f.activeCaloriesKcal)
        )

        // Whole-night swap, stages included - see the class comment.
        return when {
            s != null && s.sleepMinutes > 0 -> withFit.copy(
                sleepTotalMinutes = s.sleepMinutes,
                sleepDeepMinutes = s.deepMinutes,
                sleepLightMinutes = s.lightMinutes,
                sleepRemMinutes = s.remMinutes,
                sleepAwakeMinutes = s.awakeMinutes
            )
            // Same all-or-nothing rule one source down: a Google Fit night replaces the whole
            // night or none of it, never just the total.
            withFit.sleepTotalMinutes == 0 && f != null && f.sleepTotalMinutes > 0 -> withFit.copy(
                sleepTotalMinutes = f.sleepTotalMinutes,
                sleepDeepMinutes = f.sleepDeepMinutes,
                sleepLightMinutes = f.sleepLightMinutes,
                sleepRemMinutes = f.sleepRemMinutes,
                sleepAwakeMinutes = f.sleepAwakeMinutes
            )
            else -> withFit
        }
    }

    /** Merges a whole range, keyed by date, for the Week screen and the trend series. */
    fun mergeRange(
        health: List<DailySummary>,
        garmin: List<GarminDailyExtra>,
        sleep: List<GarminSleep>,
        fit: List<FitDay> = emptyList()
    ): List<DailySummary> {
        val hcByDay = health.associateBy { it.dateEpochDay }
        val gByDay = garmin.associateBy { it.dateEpochDay }
        val sByDay = sleep.associateBy { it.dateEpochDay }
        val fByDay = fit.associateBy { it.dateEpochDay }
        // Union of all of them, not just the Health Connect days: a day that only Garmin
        // knows about must still appear in the week, or the week's averages are computed
        // over a set of days chosen by the weaker source.
        return (hcByDay.keys + gByDay.keys + sByDay.keys + fByDay.keys)
            .sorted()
            .mapNotNull { merge(hcByDay[it], gByDay[it], sByDay[it], fByDay[it]) }
    }

    /** What `sourceApps` says on a day the Takeout archive is the only source for. */
    const val GOOGLE_FIT_PACKAGE = "com.google.android.apps.fitness"
}

/** 0 means "no data" everywhere in this app, so a zero from Garmin defers to Health Connect. */
private fun Long?.orFirst(fallback: Long): Long = if (this != null && this > 0L) this else fallback

private fun Int?.orFirst(fallback: Int): Int = if (this != null && this > 0) this else fallback

/** Keeps what the stronger sources produced; a zero there means they had nothing to say. */
private fun Long.orKeep(weaker: Long): Long = if (this > 0L) this else weaker

private fun Int.orKeep(weaker: Int): Int = if (this > 0) this else weaker
