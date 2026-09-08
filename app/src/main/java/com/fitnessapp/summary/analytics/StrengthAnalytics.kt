package com.fitnessapp.summary.analytics

import com.fitnessapp.summary.data.StrengthSet
import kotlin.math.roundToInt

/**
 * The base lifts whose numbers the user actually tracks. Everything else in the imported
 * log (curls, dips, machines, abs) is stored but never charted - six lines that mean
 * something beat thirty that don't.
 *
 * Matching is by keyword against the log's own exercise names, not by exact string,
 * because the source app spells them out in full ("Жим штанги лёжа средним хватом") and
 * a program change renames them ("Становая тяга сумо" replaced "Становая тяга со
 * штангой" in 2024 without being a different lift). Order matters: "становая" is checked
 * before the generic row rule, and bench before overhead press, or "Жим штанги лёжа"
 * would land in the press bucket.
 */
enum class StrengthLift(val key: String, val title: String) {
    SQUAT("squat", "Приседания"),
    DEADLIFT("deadlift", "Становая тяга"),
    BENCH("bench", "Жим лёжа"),
    OVERHEAD("overhead", "Армейский жим"),
    ROW("row", "Тяга в наклоне"),
    PULL_UP("pullup", "Подтягивания");

    companion object {
        fun byKey(key: String): StrengthLift? = entries.firstOrNull { it.key == key }

        /**
         * Which tracked lift [exerciseName] is, or null. Deliberately conservative:
         * an unrecognised name is stored with an empty lift rather than guessed into a
         * bucket, because a wrong match quietly corrupts a five-year trend line.
         */
        fun match(exerciseName: String): StrengthLift? {
            val name = exerciseName.lowercase().replace('ё', 'е')
            return when {
                name.contains("становая") -> DEADLIFT
                name.contains("присед") -> SQUAT
                name.contains("подтягивания") || name.contains("подтягивание") -> PULL_UP
                name.contains("жим") && name.contains("леж") -> BENCH
                name.contains("армейский") || (name.contains("жим") && name.contains("стоя")) -> OVERHEAD
                name.contains("тяга") && name.contains("наклон") -> ROW
                else -> null
            }
        }
    }
}

/** What one training session says about one lift. */
data class LiftSession(
    val dateEpochDay: Long,
    val startMillis: Long,
    /** Estimated one-rep max, kg - see [StrengthAnalytics.estimateOneRm]. */
    val oneRmKg: Float,
    /** The weight the session's work was actually done at - see [StrengthAnalytics.sessionOf]. */
    val workingWeightKg: Float,
    /** Best reps achieved at [workingWeightKg]. */
    val workingReps: Int,
    val topWeightKg: Float,
    val topReps: Int,
    val setCount: Int,
    val volumeKg: Float,
    /** The log's own name for the exercise, so a variant switch is visible, not hidden. */
    val exerciseName: String
)

/**
 * Turns imported gym sets into the two numbers a lifter tracks: what could be lifted once
 * (estimated), and what the work is actually being done at.
 *
 * Both are rules, both are arguable, and both were checked against the user's real log
 * (519 sessions of the six lifts, 2021-2026) rather than picked from a textbook and hoped
 * for - see the notes on each below.
 */
object StrengthAnalytics {

    /**
     * Epley: `w × (1 + reps/30)`, with a single taken at face value. Chosen over Brzycki
     * because it stays sane deeper into the rep range, and it is slightly *conservative*
     * where this log lives: a real 12RM is about 67% of a 1RM (×1.49), Epley says ×1.40.
     *
     * Sets past [MAX_REPS_FOR_ONE_RM] reps are ignored - past a dozen reps the estimate
     * is measuring endurance, not maximal strength. The cap is 12 rather than the more
     * common 10 for a concrete reason: at 10, ninety of this log's 519 lift-sessions
     * (17%) contain no eligible set at all and would silently vanish from the chart; at
     * 12, none do.
     */
    fun estimateOneRm(weightKg: Float, reps: Int): Float = when {
        reps <= 0 || reps > MAX_REPS_FOR_ONE_RM -> 0f
        // A single IS the one-rep max; the formula alone would call 100 × 1 a 103 kg max,
        // and that inflation is exactly where a top single is most likely to be logged.
        reps == 1 -> weightKg
        else -> weightKg * (1f + reps / 30f)
    }

    /**
     * One session's worth of a lift, or null if there is nothing usable in it.
     *
     * **Working weight = the heaviest weight carried for at least two sets**, falling
     * back to the heaviest single set when every weight appears once (a pure ramp).
     * The obvious alternative - the most frequent weight among the non-warm-up sets -
     * agrees with this on 512 of 519 real sessions, and on the seven where they differ
     * it is the worse answer every time: `60×4, 60×3, 55×5, 55×6, 55×5` is a session
     * whose working weight is 60 with back-off sets at 55, not a session at 55.
     *
     * Warm-ups are excluded by weight, not by position: anything under
     * [WARMUP_FRACTION] of the session's heaviest set. Position would misread the
     * back-off sets that end most of these sessions.
     */
    fun sessionOf(sets: List<StrengthSet>): LiftSession? {
        val usable = sets.filter { it.weightKg > 0f && it.reps > 0 }
        if (usable.isEmpty()) return null

        val heaviest = usable.maxOf { it.weightKg }
        val working = usable.filter { it.weightKg >= heaviest * WARMUP_FRACTION }
        val repeated = working.groupBy { it.weightKg }.filterValues { it.size >= 2 }.keys
        val workingWeight = repeated.maxOrNull() ?: heaviest
        val workingReps = working.filter { it.weightKg == workingWeight }.maxOf { it.reps }
        val topSet = usable.filter { it.weightKg == heaviest }.maxByOrNull { it.reps }!!

        return LiftSession(
            dateEpochDay = usable.first().dateEpochDay,
            startMillis = usable.first().startMillis,
            oneRmKg = usable.maxOf { estimateOneRm(it.weightKg, it.reps) },
            workingWeightKg = workingWeight,
            workingReps = workingReps,
            topWeightKg = topSet.weightKg,
            topReps = topSet.reps,
            setCount = usable.size,
            volumeKg = usable.sumOf { it.volumeKg.toDouble() }.toFloat(),
            exerciseName = usable.first().exerciseName
        )
    }

    /**
     * Every session of [lift] in [sets], oldest first. Sets are grouped by session start:
     * a lift done twice in one session (a variant switch, a second exercise mapping to
     * the same lift) becomes one session, which is what the trend line wants - one day,
     * one point.
     */
    fun sessionsOf(sets: List<StrengthSet>, lift: StrengthLift): List<LiftSession> =
        sets.filter { it.lift == lift.key }
            .groupBy { it.startMillis }
            .toSortedMap()
            .values
            .mapNotNull { sessionOf(it) }

    fun oneRmTrend(sessions: List<LiftSession>): List<TrendPoint> =
        sessions.filter { it.oneRmKg > 0f }.map { TrendPoint(it.dateEpochDay, it.oneRmKg) }

    fun workingWeightTrend(sessions: List<LiftSession>): List<TrendPoint> =
        sessions.filter { it.workingWeightKg > 0f }.map { TrendPoint(it.dateEpochDay, it.workingWeightKg) }

    /**
     * Change in estimated 1RM across [sessions], in kg, comparing the first and last
     * [EDGE_SESSIONS] sessions rather than the single first and last. One bad day at
     * either end would otherwise decide the verdict for a whole year.
     */
    fun changeKg(sessions: List<LiftSession>): Float? {
        val values = sessions.filter { it.oneRmKg > 0f }
        if (values.size < 2 * EDGE_SESSIONS) return null
        val first = values.take(EDGE_SESSIONS).map { it.oneRmKg }.average()
        val last = values.takeLast(EDGE_SESSIONS).map { it.oneRmKg }.average()
        return ((last - first) * 10).roundToInt() / 10f
    }

    const val MAX_REPS_FOR_ONE_RM = 12

    /** Below this share of the session's top weight a set is a warm-up, not work. */
    private const val WARMUP_FRACTION = 0.8f

    /** How many sessions at each end average into [changeKg]. */
    private const val EDGE_SESSIONS = 3
}
