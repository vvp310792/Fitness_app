package com.fitnessapp.summary.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Stress and Body Battery for one day - the two metrics Garmin never exposes through
 * Health Connect (see garmin/GarminApiClient.kt and CLAUDE.md). Deliberately a
 * SEPARATE table from [DailySummary] rather than new columns on it: [DailySummary] is
 * the Health-Connect-derived contract used everywhere in the app whether or not Garmin
 * unofficial access is ever set up, and keeping this table apart means a user who never
 * logs into Garmin directly sees a database with an always-empty table, not a
 * DailySummary schema whose meaning quietly depends on a feature most installs won't use.
 *
 * Same 0-means-no-data convention as the rest of the app (see DailySummary's doc
 * comment) - accepted here too even though a body battery or stress reading of exactly
 * 0 is a real, if uncommon, value; consistency with the rest of the codebase's
 * convention matters more than perfectly modelling this one rare edge case.
 */
@Entity(tableName = "garmin_daily_extra")
data class GarminDailyExtra(
    @PrimaryKey val dateEpochDay: Long,
    val averageStressLevel: Int = 0,
    val maxStressLevel: Int = 0,
    val bodyBatteryAtWake: Int = 0,
    val bodyBatteryHighest: Int = 0,
    val bodyBatteryLowest: Int = 0,
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val isEmpty: Boolean
        get() = averageStressLevel == 0 && maxStressLevel == 0 &&
            bodyBatteryAtWake == 0 && bodyBatteryHighest == 0 && bodyBatteryLowest == 0
}

@Dao
interface GarminDailyExtraDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(extra: GarminDailyExtra)

    @Query("SELECT * FROM garmin_daily_extra WHERE dateEpochDay = :dateEpochDay LIMIT 1")
    fun observeDay(dateEpochDay: Long): Flow<GarminDailyExtra?>

    @Query("SELECT * FROM garmin_daily_extra WHERE dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay")
    fun observeRange(fromEpochDay: Long, toEpochDay: Long): Flow<List<GarminDailyExtra>>
}
