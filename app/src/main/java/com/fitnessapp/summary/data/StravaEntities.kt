package com.fitnessapp.summary.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One activity out of a Strava bulk export.
 *
 * A third source of sessions, and the only one that reaches back before the watch: this
 * account's Strava history starts in 2015, Garmin's in late 2021. It is deliberately its
 * own table rather than rows in `garmin_activities` - same reasoning as every other
 * source here. The two are not the same fact: Garmin's row carries Training Effect, load
 * and a per-second zone breakdown, while Strava's carries what a CSV can hold, and mixing
 * them would make "no Training Effect" mean two different things.
 *
 * **Overlapping rows are kept, not dropped at import.** Everything from late 2021 on
 * exists in both (Strava gets it from Garmin), and it would be cheaper to throw those
 * away while reading the file. It would also be wrong: which rows are duplicates changes
 * as Garmin backfills, so that judgement has to be made when the data is READ, exactly
 * like `strength_sets.lift` had to be. See `SportDistanceAnalytics.sessions`.
 */
@Entity(tableName = "strava_activities")
data class StravaActivity(
    /** Strava's own activity id, so re-importing a newer export overwrites rather than doubles. */
    @PrimaryKey val activityId: Long,
    /** Local calendar day the activity started on. */
    val dateEpochDay: Long,
    /** Epoch millis of the start instant. The export's date column is UTC - verified, see the parser. */
    val startTimeMillis: Long,
    val name: String = "",
    /** Strava's sport name **as written in the export**, e.g. "Бег" - the file is localised. */
    val typeRaw: String = "",
    /** Moving time, not elapsed: the number the training is measured in. */
    val durationSeconds: Int = 0,
    val distanceMeters: Int = 0,
    val calories: Int = 0,
    val avgHeartRate: Int = 0,
    val maxHeartRate: Int = 0,
    val elevationGainMeters: Int = 0
) {
    val durationMinutes: Int get() = durationSeconds / 60
}

@Dao
interface StravaActivityDao {

    @Upsert
    suspend fun upsertAll(rows: List<StravaActivity>)

    @Query("SELECT * FROM strava_activities WHERE dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay ORDER BY startTimeMillis")
    fun observeRange(fromEpochDay: Long, toEpochDay: Long): Flow<List<StravaActivity>>

    @Query("SELECT * FROM strava_activities ORDER BY startTimeMillis")
    suspend fun getAllOnce(): List<StravaActivity>

    @Query("SELECT COUNT(*) FROM strava_activities")
    fun observeCount(): Flow<Int>

    @Query("SELECT MIN(dateEpochDay) FROM strava_activities")
    suspend fun firstDay(): Long?

    @Query("SELECT MAX(dateEpochDay) FROM strava_activities")
    suspend fun lastDay(): Long?

    /** For the "forget this import" button - the file is the only source, so this is reversible. */
    @Query("DELETE FROM strava_activities")
    suspend fun deleteAll(): Int
}
