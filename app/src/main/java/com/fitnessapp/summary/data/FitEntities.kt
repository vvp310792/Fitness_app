package com.fitnessapp.summary.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One calendar day as Google Fit recorded it, out of a Google Takeout archive.
 *
 * The fourth source, and the second one that reaches back before the watch: Strava brought
 * the workouts from 2015, this brings what happened between them - steps, calories, heart
 * rate, resting heart rate and weight from the phone and the Mi Band, for years when there
 * was no Garmin at all. Health Connect cannot answer for those years: it keeps a sliding
 * window, and the walk through its history found 38 days of real measurements, not 4000.
 *
 * **Its own table, never rows in `daily_summaries`.** Same reasoning as `strava_activities`
 * and `scale_measurements`: `daily_summaries` is the Health Connect table and must stay
 * re-readable from Health Connect. These are a different fact - "what Google Fit held on
 * the day the archive was cut" - and a frozen one: the archive never changes, so re-importing
 * the same file is a no-op and importing a newer one replaces the day.
 *
 * Priority is the same as everywhere: Garmin is the first source, Health Connect is its
 * copy, and this is below both. Which day is a duplicate is decided when the day is READ
 * ([DayView.merge]), not at import - the rows would otherwise bake in a verdict that goes
 * stale the moment Garmin backfills another month, which is exactly the `strength_sets.lift`
 * scar.
 *
 * 0 means "no data", as it does in every other table here.
 */
@Entity(tableName = "fit_daily")
data class FitDay(
    @PrimaryKey val dateEpochDay: Long,
    val steps: Long = 0,
    /** Total expenditure, the way Google Fit counts it: BMR included. */
    val caloriesKcal: Int = 0,
    /**
     * Google Fit's own basal figure, from its `calories.bmr` stream.
     *
     * Stored rather than subtracted at import: the active part is [activeCaloriesKcal], a
     * rule over these two numbers, and rules get revised. It also answers a question the
     * total alone cannot - whether a day's calories are anything but arithmetic.
     */
    val caloriesBmrKcal: Int = 0,
    val distanceMeters: Int = 0,
    val avgHeartRate: Int = 0,
    val minHeartRate: Int = 0,
    val maxHeartRate: Int = 0,
    /** Google Fit's own resting value, its own stream - not the minimum of the day. */
    val restingHeartRate: Int = 0,
    val weightGrams: Int = 0,
    val sleepTotalMinutes: Int = 0,
    val sleepDeepMinutes: Int = 0,
    val sleepLightMinutes: Int = 0,
    val sleepRemMinutes: Int = 0,
    val sleepAwakeMinutes: Int = 0,
    val updatedAtMillis: Long = 0
) {
    val weightKg: Float get() = weightGrams / 1000f

    /**
     * What was burned beyond lying still, or 0 when that cannot be said.
     *
     * Computed on read, never stored: the same rule as `strength_sets.lift`. Guarded against
     * a negative, which would mean the two streams disagree rather than that the day was
     * restful.
     */
    val activeCaloriesKcal: Int
        get() = if (caloriesKcal > 0 && caloriesBmrKcal in 1 until caloriesKcal) {
            caloriesKcal - caloriesBmrKcal
        } else {
            0
        }

    /**
     * Nothing measured. **Calories do not count**, and that is the whole point of this rule.
     *
     * Google Fit publishes an expenditure figure for days nobody recorded anything on - it
     * derives one from the profile, and the archive even carries the basal stream it uses
     * (`calories.bmr`). The first version of this import counted calories as data and stored
     * **469 such days out of 2744** on this account, including a fake "history starts in
     * 2008": ten days of 2008, one of 2009, one of 2012, and 144 days of 2026 whose only
     * content was arithmetic.
     *
     * This is the identical trap to Health Connect's 1564 kcal, which once put 4345 invented
     * days into the database and into the cloud (see `DailySummary.isEmpty`). The rule had
     * already been written there and was not carried over here - so it is stated twice now,
     * and the SQL copy in [FitDayDao] is pinned to this property by a test.
     */
    val isEmpty: Boolean
        get() = steps == 0L && distanceMeters == 0 &&
            avgHeartRate == 0 && restingHeartRate == 0 && weightGrams == 0 &&
            sleepTotalMinutes == 0
}

@Dao
interface FitDayDao {

    @Upsert
    suspend fun upsertAll(rows: List<FitDay>)

    @Query("SELECT * FROM fit_daily WHERE dateEpochDay IN (:days)")
    suspend fun byDays(days: List<Long>): List<FitDay>

    /**
     * Drops rows whose only content is a derived calorie figure.
     *
     * The same condition as [FitDay.isEmpty], written again because Room cannot call a Kotlin
     * property - and pinned to it by `FitFabricatedDayTest`, because two copies of a rule that
     * DELETES rows drift apart at the first edit. `caloriesKcal` must not appear here at all.
     */
    @Query(FitDayDao.FABRICATED_WHERE_DELETE)
    suspend fun deleteWithoutMeasurements(): Int

    @Query("SELECT * FROM fit_daily WHERE dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay ORDER BY dateEpochDay")
    fun observeRange(fromEpochDay: Long, toEpochDay: Long): Flow<List<FitDay>>

    @Query("SELECT * FROM fit_daily WHERE dateEpochDay = :dateEpochDay")
    fun observeDay(dateEpochDay: Long): Flow<FitDay?>

    @Query("SELECT * FROM fit_daily ORDER BY dateEpochDay")
    suspend fun getAllOnce(): List<FitDay>

    @Query("SELECT COUNT(*) FROM fit_daily")
    fun observeCount(): Flow<Int>

    @Query("SELECT MIN(dateEpochDay) FROM fit_daily")
    suspend fun firstDay(): Long?

    @Query("SELECT MAX(dateEpochDay) FROM fit_daily")
    suspend fun lastDay(): Long?

    /** For "forget this import" - the archive is the only source, so it is fully reversible. */
    @Query("DELETE FROM fit_daily")
    suspend fun deleteAll(): Int

    companion object {
        const val FABRICATED_WHERE_DELETE =
            "DELETE FROM fit_daily WHERE steps = 0 AND distanceMeters = 0 AND " +
                "avgHeartRate = 0 AND restingHeartRate = 0 AND weightGrams = 0 AND " +
                "sleepTotalMinutes = 0"
    }
}
