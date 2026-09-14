package com.fitnessapp.summary.strength

import com.fitnessapp.summary.analytics.StrengthLift
import com.fitnessapp.summary.data.StrengthSet
import java.time.Instant
import java.time.ZoneId

/**
 * Turns the four tables of a gym-app database backup into [StrengthSet] rows.
 *
 * **Why the backup and not the text export.** The gym app writes a full copy of its SQLite
 * database to Google Drive after every workout, automatically; the text export is a manual,
 * paid-tier action. Same sessions, but one of them arrives by itself. The backup is also the
 * only way to reach history the app no longer holds: this user's log restarts on 2021-10-02
 * after a reinstall, and the 2018-2021 sessions exist *only* inside an older backup file.
 *
 * Checked against the user's own text export of the overlapping period: of 3180 exported
 * sets, **3179 come out identical** - same session instant, same exercise name, same set
 * number, same weight and reps - and the single difference is a set the *text* export lost,
 * see [setIndex numbering][build] below.
 *
 * This object is deliberately pure: it takes rows, not a database. The SQL lives in
 * [GymUpBackupReader], which is Android-only; everything that can be wrong about the
 * *meaning* of the rows is here, where a JVM test can reach it.
 */
object GymUpBackupParser {

    /** A session. `startMillis` is a true UTC instant, as the backup stores it. */
    data class TrainingRow(val id: Long, val startMillis: Long)

    /**
     * One exercise within a session. [orderNum] is the gym app's own display order - a
     * creation timestamp, not a 1..n counter - so it sorts but says nothing by itself.
     */
    data class WorkoutRow(val id: Long, val trainingId: Long, val exerciseId: Long, val orderNum: Long)

    /** One set. [id] carries the insertion order, which is the order the sets were done in. */
    data class SetRow(val id: Long, val workoutId: Long, val weightKg: Float, val reps: Int)

    /**
     * An exercise whose sets were imported but whose name is not known - a stock exercise
     * missing from [GymUpExerciseCatalog]. Surfaced rather than swallowed: the sets are real
     * and belong in the totals, but calling them something would be inventing a fact.
     */
    data class Unidentified(
        val exerciseId: Long,
        val sets: Int,
        val sessions: Int,
        val firstEpochDay: Long,
        val lastEpochDay: Long,
        val maxWeightKg: Float
    )

    sealed class Result {
        data class Ok(
            val sets: List<StrengthSet>,
            val sessions: Int,
            /** Sessions present in the backup that hold no performed set at all. */
            val emptySessions: Int,
            val unidentified: List<Unidentified>
        ) : Result()

        data class Failed(val reason: String) : Result()
    }

    /**
     * Builds the rows.
     *
     * Four rules decide whether this matches the text export or quietly diverges from it:
     *
     * - **A session with no performed set is not a session.** The gym app writes a session
     *   row when a programme day is opened, so a backup legitimately holds sessions whose
     *   every exercise is empty - and, in the 2019 history, a second session sharing another
     *   one's exact start instant, which would collide on the primary key. Requiring at
     *   least one set removes both without a special case for either.
     * - **A set with no reps is not a set.** `0 / 0` in the text export is the same fact:
     *   the exercise was planned and skipped.
     * - **Set numbers run continuously per exercise within a session, across blocks.** An
     *   exercise can legitimately appear twice in one session (sets added after the
     *   programme's block was finished). Numbering each block from 1 makes the second block
     *   overwrite the first on the `(session, exercise, set number)` key - which is exactly
     *   what the text export does, and it costs it a set in 2025-07-31. Counting straight
     *   through loses nothing and is identical to the export everywhere an exercise appears
     *   once, which is 523 sessions out of 524.
     * - **Order is [WorkoutRow.orderNum] then id for exercises, id for sets** - the gym app's
     *   own order, so the set numbers here mean what they mean on its screen.
     *
     * [zone] only decides which calendar day a session falls on; the instant itself is
     * already absolute.
     */
    fun build(
        trainings: List<TrainingRow>,
        workouts: List<WorkoutRow>,
        sets: List<SetRow>,
        exerciseNames: Map<Long, String?>,
        zone: ZoneId = ZoneId.systemDefault()
    ): Result {
        if (trainings.isEmpty()) return Result.Failed("в файле нет ни одной тренировки")

        val setsByWorkout = sets.groupBy { it.workoutId }
        val workoutsByTraining = workouts.groupBy { it.trainingId }

        val out = ArrayList<StrengthSet>()
        var sessions = 0
        var emptySessions = 0
        val unidentifiedSets = HashMap<Long, MutableList<SetRow>>()
        val unidentifiedSessions = HashMap<Long, MutableSet<Long>>()
        val unidentifiedDays = HashMap<Long, MutableList<Long>>()

        for (training in trainings.sortedBy { it.startMillis }) {
            if (training.startMillis <= 0L) continue
            val dateEpochDay = Instant.ofEpochMilli(training.startMillis).atZone(zone).toLocalDate().toEpochDay()
            val blocks = workoutsByTraining[training.id]
                ?.sortedWith(compareBy({ it.orderNum }, { it.id }))
                .orEmpty()

            val indexByExercise = HashMap<String, Int>()
            var sessionSets = 0

            for (block in blocks) {
                val performed = setsByWorkout[block.id]?.sortedBy { it.id }?.filter { it.reps > 0 }.orEmpty()
                if (performed.isEmpty()) continue

                val name = GymUpExerciseCatalog.nameFor(block.exerciseId, exerciseNames[block.exerciseId])
                if (!GymUpExerciseCatalog.isKnown(block.exerciseId) && exerciseNames[block.exerciseId].isNullOrBlank()) {
                    unidentifiedSets.getOrPut(block.exerciseId) { mutableListOf() }.addAll(performed)
                    unidentifiedSessions.getOrPut(block.exerciseId) { mutableSetOf() }.add(training.id)
                    unidentifiedDays.getOrPut(block.exerciseId) { mutableListOf() }.add(dateEpochDay)
                }

                for (set in performed) {
                    val index = (indexByExercise[name] ?: 0) + 1
                    indexByExercise[name] = index
                    out += StrengthSet(
                        startMillis = training.startMillis,
                        dateEpochDay = dateEpochDay,
                        exerciseName = name,
                        lift = StrengthLift.match(name)?.key.orEmpty(),
                        setIndex = index,
                        weightKg = set.weightKg,
                        reps = set.reps
                    )
                    sessionSets++
                }
            }

            if (sessionSets > 0) sessions++ else emptySessions++
        }

        if (out.isEmpty()) return Result.Failed("в файле нет ни одного выполненного подхода")

        val unidentified = unidentifiedSets.map { (id, rows) ->
            val days = unidentifiedDays.getValue(id)
            Unidentified(
                exerciseId = id,
                sets = rows.size,
                sessions = unidentifiedSessions.getValue(id).size,
                firstEpochDay = days.min(),
                lastEpochDay = days.max(),
                maxWeightKg = rows.maxOf { it.weightKg }
            )
        }.sortedByDescending { it.sets }

        return Result.Ok(out, sessions, emptySessions, unidentified)
    }
}
