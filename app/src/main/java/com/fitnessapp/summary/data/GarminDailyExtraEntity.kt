package com.fitnessapp.summary.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One day of Garmin Connect's own daily summary
 * (`usersummary-service/usersummary/daily/?calendarDate=`), read straight from Garmin by
 * the unofficial client (garmin/GarminApiClient.kt). Field set = garth's `DailySummary`
 * dataclass, verbatim (see CLAUDE.md, "Прямой доступ к Garmin") - plus the four stress
 * zone durations, which come from a second, range-based endpoint
 * (`usersummary-service/stats/stress/daily/{start}/{end}`, garth `DailyStress`) and are
 * folded into the same row because they describe the same day.
 *
 * Deliberately a SEPARATE table from [DailySummary] rather than new columns on it:
 * [DailySummary] is the Health-Connect-derived contract used everywhere in the app whether
 * or not Garmin unofficial access is ever set up, and keeping this table apart means a
 * user who never logs into Garmin directly sees a database with an always-empty table,
 * not a DailySummary schema whose meaning quietly depends on a feature most installs
 * won't use. Where the two overlap (steps, calories, resting HR) they come from the same
 * watch; the Day screen prefers Health Connect when it has the day and falls back to this
 * row when it doesn't - which is exactly the "sleep and resting HR never reach Health
 * Connect" case this whole path exists for.
 *
 * Same 0-means-no-data convention as the rest of the app (see DailySummary's doc
 * comment) - accepted here too even though a body battery or stress reading of exactly
 * 0 is a real, if uncommon, value; consistency with the rest of the codebase's
 * convention matters more than perfectly modelling this one rare edge case.
 */
@Entity(tableName = "garmin_daily_extra")
data class GarminDailyExtra(
    @PrimaryKey val dateEpochDay: Long,

    // Stress / Body Battery (the original reason this table exists)
    val averageStressLevel: Int = 0,
    val maxStressLevel: Int = 0,
    val bodyBatteryAtWake: Int = 0,
    val bodyBatteryHighest: Int = 0,
    val bodyBatteryLowest: Int = 0,
    /** Garmin's own word for the day: "calm" / "balanced" / "stressful" / "very_stressful". */
    val stressQualifier: String = "",
    // Seconds spent in each stress zone (rest < 25, low 26-50, medium 51-75, high 76+ -
    // Garmin's published zone boundaries). From the stats/stress/daily endpoint.
    val restStressSeconds: Int = 0,
    val lowStressSeconds: Int = 0,
    val mediumStressSeconds: Int = 0,
    val highStressSeconds: Int = 0,

    // Movement
    val totalSteps: Long = 0,
    val totalDistanceMeters: Int = 0,
    val totalKilocalories: Int = 0,
    val activeKilocalories: Int = 0,
    val floorsAscended: Int = 0,
    val floorsDescended: Int = 0,
    val moderateIntensityMinutes: Int = 0,
    val vigorousIntensityMinutes: Int = 0,
    /** Garmin's weekly Intensity Minutes goal (default 150 = WHO). From stats/im/daily. */
    val intensityMinutesWeeklyGoal: Int = 0,
    val activeSeconds: Int = 0,
    val highlyActiveSeconds: Int = 0,
    val sedentarySeconds: Int = 0,
    val sleepingSeconds: Int = 0,

    // Heart
    val restingHeartRate: Int = 0,
    val minHeartRate: Int = 0,
    val maxHeartRate: Int = 0,
    val lastSevenDaysAvgRestingHeartRate: Int = 0,

    // Pulse ox / respiration (only on watches that measure them; 0 otherwise)
    val averageSpo2: Int = 0,
    val lowestSpo2: Int = 0,
    val avgWakingRespiration: Int = 0,
    val highestRespiration: Int = 0,
    val lowestRespiration: Int = 0,

    // Hydration (manual logging in Garmin Connect; 0 for everyone who doesn't use it)
    val hydrationMl: Int = 0,
    val hydrationGoalMl: Int = 0,

    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val isEmpty: Boolean
        get() = averageStressLevel == 0 && maxStressLevel == 0 &&
            bodyBatteryAtWake == 0 && bodyBatteryHighest == 0 && bodyBatteryLowest == 0 &&
            totalSteps == 0L && totalKilocalories == 0 && restingHeartRate == 0 &&
            moderateIntensityMinutes == 0 && vigorousIntensityMinutes == 0

    val hasStress: Boolean get() = averageStressLevel > 0 || maxStressLevel > 0
    val hasBodyBattery: Boolean get() = bodyBatteryAtWake > 0 || bodyBatteryHighest > 0 || bodyBatteryLowest > 0
    val hasStressZones: Boolean
        get() = restStressSeconds + lowStressSeconds + mediumStressSeconds + highStressSeconds > 0

    /**
     * Garmin's own Intensity Minutes formula: a vigorous minute counts double toward the
     * weekly goal - this is the number the Garmin Connect ring shows, not the plain sum.
     */
    val intensityMinutesWeighted: Int get() = moderateIntensityMinutes + 2 * vigorousIntensityMinutes
}

@Dao
interface GarminDailyExtraDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(extra: GarminDailyExtra)

    @Query("SELECT * FROM garmin_daily_extra WHERE dateEpochDay = :dateEpochDay LIMIT 1")
    fun observeDay(dateEpochDay: Long): Flow<GarminDailyExtra?>

    @Query("SELECT * FROM garmin_daily_extra WHERE dateEpochDay = :dateEpochDay LIMIT 1")
    suspend fun getDayOnce(dateEpochDay: Long): GarminDailyExtra?

    @Query(
        "SELECT * FROM garmin_daily_extra WHERE dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay " +
            "ORDER BY dateEpochDay ASC"
    )
    fun observeRange(fromEpochDay: Long, toEpochDay: Long): Flow<List<GarminDailyExtra>>

    @Query("SELECT * FROM garmin_daily_extra ORDER BY dateEpochDay ASC")
    suspend fun getAllOnce(): List<GarminDailyExtra>
}
