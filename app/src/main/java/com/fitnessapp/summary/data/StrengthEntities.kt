package com.fitnessapp.summary.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One set of one exercise, as recorded in the gym log the user keeps in a separate
 * workout app and imports here (`strength/WorkoutLogParser.kt`).
 *
 * **Raw sets, not per-session summaries.** Every number the strength screen shows -
 * estimated 1RM, working weight, top set - is a *rule* over these rows, and rules get
 * revised: the working-weight definition alone went through three candidates before one
 * matched what a lifter would call it. Storing the summary would have baked the first
 * guess into the database and made every later fix require re-importing the file.
 * 13 000 sets over five years is nothing for Room.
 *
 * Keyed by (session instant + exercise + set number) so re-importing the same log is
 * idempotent - the same set overwrites itself instead of doubling. That the key needs
 * the exercise name is not incidental: one session legitimately repeats an exercise
 * (a superset), and one exercise legitimately repeats a set number across exercises.
 *
 * [weightKg] is what the log holds, unmodified. For bodyweight movements that app
 * records the **total** load - pull-ups at 78.5 kg on a 78 kg person is bodyweight
 * alone, 90 kg is bodyweight plus 12 - so the numbers here are directly comparable with
 * barbell lifts, and the app must not "helpfully" subtract anything it doesn't know.
 */
@Entity(
    tableName = "strength_sets",
    primaryKeys = ["startMillis", "exerciseName", "setIndex"],
    indices = [Index(value = ["dateEpochDay"]), Index(value = ["lift"])]
)
data class StrengthSet(
    /** Session start (date + time from the log header), epoch millis in the local zone. */
    val startMillis: Long,
    val dateEpochDay: Long,
    /** Exercise name exactly as the log spells it - the audit trail for [lift]. */
    val exerciseName: String,
    /**
     * Which of the tracked base lifts this is, or "" for everything else. Resolved at
     * import by [com.fitnessapp.summary.analytics.StrengthLift.match]; the raw name stays
     * alongside so a mapping fix is a re-import, not a lost row.
     */
    val lift: String,
    /** 1-based position within the exercise, as printed in the log. */
    val setIndex: Int,
    val weightKg: Float,
    val reps: Int,
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val volumeKg: Float get() = weightKg * reps
}

@Dao
interface StrengthSetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<StrengthSet>)

    /** Only the tracked lifts, which is all the strength screen ever draws. */
    @Query(
        "SELECT * FROM strength_sets WHERE lift != '' AND dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay " +
            "ORDER BY startMillis ASC, setIndex ASC"
    )
    fun observeTrackedRange(fromEpochDay: Long, toEpochDay: Long): Flow<List<StrengthSet>>

    @Query("SELECT * FROM strength_sets ORDER BY startMillis ASC, setIndex ASC")
    suspend fun getAllOnce(): List<StrengthSet>

    @Query("SELECT COUNT(DISTINCT startMillis) FROM strength_sets")
    fun observeSessionCount(): Flow<Int>

    @Query("SELECT MIN(dateEpochDay) FROM strength_sets")
    suspend fun firstDay(): Long?

    @Query("SELECT MAX(dateEpochDay) FROM strength_sets")
    suspend fun lastDay(): Long?

    @Query("DELETE FROM strength_sets")
    suspend fun deleteAll()
}
