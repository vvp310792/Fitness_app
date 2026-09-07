package com.fitnessapp.summary.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(workout: Workout)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(workouts: List<Workout>)

    @Query("SELECT * FROM workouts WHERE dateEpochDay = :dateEpochDay ORDER BY startTimeMillis ASC")
    fun observeForDay(dateEpochDay: Long): Flow<List<Workout>>

    @Query(
        "SELECT * FROM workouts WHERE dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay " +
            "ORDER BY startTimeMillis ASC"
    )
    fun observeRange(fromEpochDay: Long, toEpochDay: Long): Flow<List<Workout>>

    @Query(
        "SELECT * FROM workouts WHERE dateEpochDay = :dateEpochDay ORDER BY startTimeMillis ASC"
    )
    suspend fun getForDayOnce(dateEpochDay: Long): List<Workout>

    @Query("SELECT * FROM workouts ORDER BY startTimeMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<Workout>>

    @Query("SELECT * FROM workouts ORDER BY startTimeMillis ASC")
    suspend fun getAllOnce(): List<Workout>

    /**
     * Clears a day's sessions before re-inserting what Health Connect currently has,
     * so a workout deleted upstream (or one whose record id changed) doesn't linger
     * forever as a ghost. Scoped to a single day so it can never touch history the
     * current sync isn't responsible for.
     */
    @Query("DELETE FROM workouts WHERE dateEpochDay = :dateEpochDay")
    suspend fun deleteForDay(dateEpochDay: Long)
}
