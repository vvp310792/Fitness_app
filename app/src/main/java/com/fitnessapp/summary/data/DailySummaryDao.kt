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
}
