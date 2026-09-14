package com.fitnessapp.summary.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DailySummaryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: DailySummary)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(summaries: List<DailySummary>)

    @Query("SELECT * FROM daily_summaries WHERE dateEpochDay = :dateEpochDay LIMIT 1")
    fun observeDay(dateEpochDay: Long): Flow<DailySummary?>

    @Query("SELECT * FROM daily_summaries WHERE dateEpochDay = :dateEpochDay LIMIT 1")
    suspend fun getDayOnce(dateEpochDay: Long): DailySummary?

    @Query(
        "SELECT * FROM daily_summaries WHERE dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay " +
            "ORDER BY dateEpochDay ASC"
    )
    fun observeRange(fromEpochDay: Long, toEpochDay: Long): Flow<List<DailySummary>>

    @Query("SELECT * FROM daily_summaries ORDER BY dateEpochDay DESC")
    fun observeAll(): Flow<List<DailySummary>>

    @Query("SELECT * FROM daily_summaries ORDER BY dateEpochDay ASC")
    suspend fun getAllOnce(): List<DailySummary>

    /** Newest day we hold anything for - the starting point for an incremental sync. */
    @Query("SELECT MAX(dateEpochDay) FROM daily_summaries")
    suspend fun getLatestDay(): Long?

    /**
     * Days holding nothing but the total-calories figure Health Connect derives from the
     * user's profile - see [DailySummary.isEmpty]. Spelled out here rather than reusing
     * that property because Room needs SQL, so **the two must be kept in step**; a test
     * pins them together.
     *
     * Ids first, delete second, deliberately: the same rows have to be removed from
     * Firestore too, and a row deleted locally with its id already forgotten would come
     * straight back on the next snapshot.
     */
    @Query(FABRICATED_WHERE_SELECT)
    suspend fun fabricatedDayIds(): List<Long>

    @Query(FABRICATED_WHERE_DELETE)
    suspend fun deleteFabricatedDays(): Int

    companion object {
        private const val FABRICATED_CONDITION =
            "steps = 0 AND activeCaloriesKcal = 0 AND distanceMeters = 0 AND " +
                "restingHeartRate = 0 AND avgHeartRate = 0 AND sleepTotalMinutes = 0 AND " +
                "workoutCount = 0"

        const val FABRICATED_WHERE_SELECT =
            "SELECT dateEpochDay FROM daily_summaries WHERE $FABRICATED_CONDITION"

        const val FABRICATED_WHERE_DELETE =
            "DELETE FROM daily_summaries WHERE $FABRICATED_CONDITION"
    }
}
