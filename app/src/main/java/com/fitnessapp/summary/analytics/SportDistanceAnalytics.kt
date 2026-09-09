package com.fitnessapp.summary.analytics

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.fitnessapp.summary.data.GarminActivity
import com.fitnessapp.summary.data.Workout
import com.fitnessapp.summary.util.weekStart
import java.time.LocalDate
import kotlin.math.abs

/**
 * The three sports whose weekly kilometres actually mean something to this user.
 *
 * Distance is only a training metric where it maps onto effort. Running, cycling and
 * swimming kilometres do; the strength work, yoga and the other sports in the log either
 * carry no distance at all or one nobody reads that way, and lumping them into a single
 * "distance" line would produce a number that means nothing. Walking is deliberately not
 * running: it inflates a running week with commuting.
 *
 * Matching is by KEYWORD, not by exact key. Garmin spells one sport many ways
 * (`running` / `trail_running` / `treadmill_running` / `track_running`, `cycling` /
 * `road_biking` / `gravel_cycling` / `virtual_ride`) and adds new ones without warning -
 * an exact list would silently drop a trail run the first time Garmin renames the sport,
 * and a dropped run looks exactly like a week that wasn't trained.
 */
enum class DistanceSport(val key: String, val title: String, val emoji: String) {
    // Trail is not split out: the user tracks the weekly volume, and a trail run and a
    // road run are the same week's running load - the split would halve both lines.
    RUN("run", "Бег и трейл", "🏃"),
    BIKE("bike", "Велосипед", "🚴"),
    SWIM("swim", "Плавание", "🏊");

    companion object {
        /** From a Garmin `activityType.typeKey`, or null when the sport isn't one of the three. */
        fun ofGarmin(typeKey: String): DistanceSport? {
            val key = typeKey.lowercase()
            return when {
                key.contains("swim") -> SWIM
                // "ride" catches virtual_ride; "bike"/"biking" the road/mountain/gravel keys.
                key.contains("cycl") || key.contains("bike") || key.contains("biking") || key.contains("ride") -> BIKE
                // Checked last: nothing above contains "run", and this must not swallow
                // walking or hiking, which are their own thing.
                key.contains("run") -> RUN
                else -> null
            }
        }

        /** The same three sports as Health Connect exercise types, for installs with no Garmin login. */
        fun ofHealthConnect(exerciseType: Int): DistanceSport? = when (exerciseType) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> RUN
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> BIKE
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> SWIM
            else -> null
        }
    }
}

/** One session that carries distance, from whichever source knew about it. */
data class SportSession(
    val sport: DistanceSport,
    val dateEpochDay: Long,
    val startTimeMillis: Long,
    val meters: Int
)

/** One calendar week's worth of one sport. [meters] is 0 for a week that was genuinely skipped. */
data class SportWeek(
    val weekStartEpochDay: Long,
    val meters: Int,
    val sessions: Int
) {
    val km: Float get() = meters / 1000f
}

/**
 * Weekly distance per sport - how much running, cycling and swimming a week actually
 * held, over the same window as every other chart on "Тренды".
 *
 * **Weekly, not daily, because that is the unit the training is planned in.** A daily
 * distance line for someone who runs three times a week is mostly gaps, and the question
 * "am I riding more than I was in spring" is not answerable from it. The week (Monday to
 * Sunday, the same week the "Неделя" tab uses) is the smallest bucket where the answer
 * is visible.
 */
object SportDistanceAnalytics {

    /**
     * The sessions of all three sports, from both sources, without double-counting.
     *
     * Health Connect and the direct Garmin client describe the SAME session off the same
     * watch, so a session present in both must be counted once. Garmin's row wins - it is
     * the source with the sport key, and Health Connect flattens Garmin's sport list onto
     * a much shorter enum - and a Health Connect workout is only added when no Garmin
     * activity started within [WORKOUT_MATCH_WINDOW_MILLIS] of it. Same rule, same window,
     * as the pairing on the workout cards.
     *
     * Keeping both sources matters in two directions: an install that never logged into
     * Garmin still gets these charts, and a session recorded before the Garmin login was
     * set up still counts.
     */
    fun sessions(garmin: List<GarminActivity>, workouts: List<Workout>): List<SportSession> {
        val fromGarmin = garmin.mapNotNull { activity ->
            val sport = DistanceSport.ofGarmin(activity.typeKey) ?: return@mapNotNull null
            if (activity.distanceMeters <= 0) return@mapNotNull null
            SportSession(sport, activity.dateEpochDay, activity.startTimeMillis, activity.distanceMeters)
        }
        val fromHealth = workouts.mapNotNull { workout ->
            val sport = DistanceSport.ofHealthConnect(workout.exerciseType) ?: return@mapNotNull null
            if (workout.distanceMeters <= 0) return@mapNotNull null
            val alreadyKnown = garmin.any {
                abs(it.startTimeMillis - workout.startTimeMillis) <= WORKOUT_MATCH_WINDOW_MILLIS
            }
            if (alreadyKnown) return@mapNotNull null
            SportSession(sport, workout.dateEpochDay, workout.startTimeMillis, workout.distanceMeters)
        }
        return (fromGarmin + fromHealth).sortedBy { it.startTimeMillis }
    }

    /**
     * [sport] week by week across the window, oldest first.
     *
     * **A week with no session of this sport is a zero, not a gap** - and this is the one
     * chart in the app where that is true. Everywhere else a missing day means "the watch
     * wasn't measuring", so drawing it as zero would invent a fact. Here the fact IS the
     * absence: a week without a single ride is a week of 0 km, and hiding it as a gap
     * would turn "I stopped cycling in November" into a line that sails straight through
     * the gap as if nothing changed.
     *
     * The zeros are only filled in between the first and last week that hold ANY session
     * of ANY of the three sports ([allSessions]) - not across the whole window. Outside
     * that range we don't know whether the week was empty or simply never synced from
     * Garmin, and painting years of confident 0 km before the history was backfilled
     * would be exactly the kind of invented fact the rule above is meant to prevent.
     *
     * The week clipped by the start of the window is dropped: a Monday-to-Sunday total
     * built from three days is a dip that says nothing about training. The week in
     * progress at the right edge is kept - it is real, just not finished yet, and the UI
     * says so.
     */
    fun weeks(
        sport: DistanceSport,
        allSessions: List<SportSession>,
        from: LocalDate,
        to: LocalDate
    ): List<SportWeek> {
        val known = allSessions.filter { it.dateEpochDay in from.toEpochDay()..to.toEpochDay() }
        if (known.isEmpty()) return emptyList()

        // Only weeks that start inside the window: the leading one is clipped by the edge.
        val firstWeek = weekStart(from).let { if (it < from) it.plusWeeks(1) else it }
        val lastWeek = weekStart(to)
        // ...and only the stretch where the log is actually populated, so unsynced history
        // isn't reported as weeks of zero.
        val firstKnownWeek = weekStart(LocalDate.ofEpochDay(known.minOf { it.dateEpochDay }))
        val lastKnownWeek = weekStart(LocalDate.ofEpochDay(known.maxOf { it.dateEpochDay }))

        val start = maxOf(firstWeek, firstKnownWeek)
        val end = minOf(lastWeek, lastKnownWeek)
        if (start > end) return emptyList()

        val byWeek = known.filter { it.sport == sport }
            .groupBy { weekStart(LocalDate.ofEpochDay(it.dateEpochDay)).toEpochDay() }

        val out = mutableListOf<SportWeek>()
        var week = start
        while (week <= end) {
            val inWeek = byWeek[week.toEpochDay()].orEmpty()
            out += SportWeek(week.toEpochDay(), inWeek.sumOf { it.meters }, inWeek.size)
            week = week.plusWeeks(1)
        }
        return out
    }

    /**
     * The weeks as chart points, in KILOMETRES - the unit these are read and talked about
     * in. Anchored on the week's Monday, so a point sits at the start of the week it sums
     * rather than trailing it by six days.
     */
    fun trend(weeks: List<SportWeek>): List<TrendPoint> =
        weeks.map { TrendPoint(it.weekStartEpochDay, it.km) }

    /** Average kilometres per week over [weeks], counting the zero weeks - they are training too. */
    fun averageKmPerWeek(weeks: List<SportWeek>): Float =
        if (weeks.isEmpty()) 0f else weeks.sumOf { it.meters }.toFloat() / 1000f / weeks.size

    /**
     * How much a session of one sport can differ in start time between the two sources and
     * still be the same session. Health Connect re-stamps what Garmin wrote, so equality is
     * too strict; three minutes is far shorter than any gap between two real sessions.
     */
    const val WORKOUT_MATCH_WINDOW_MILLIS = 3 * 60 * 1000L
}
