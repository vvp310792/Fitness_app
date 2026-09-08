package com.fitnessapp.summary.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One weigh-in from a smart scale that is NOT Garmin's - today a Mi Body Composition
 * Scale read out of the Zepp Life cloud (scale/ZeppApiClient.kt).
 *
 * Kept apart from [GarminBodyComposition] on purpose. That table is "what Garmin holds";
 * this one is "what the scale measured". After a measurement is pushed to Garmin (see
 * [garminUploadedAtMillis]) the same weigh-in exists in both, and that is fine - the two
 * tables answer different questions ("did the upload land?" is exactly the diff between
 * them), and the Garmin row carries only the subset of fields Garmin's FIT format
 * accepts, while this one keeps the scale's full output (body score, protein, basal
 * metabolism, impedance).
 *
 * Keyed by the measurement instant, not the day: a scale is stepped on more than once a
 * day, and the Zepp API itself identifies records by `generatedTime`. Weight is grams,
 * matching [GarminBodyComposition]; percentages and BMI are as the scale reports them.
 *
 * Xiaomi's field naming is misleading in one place and this is where it's normalised:
 * `muscleRate` in the Zepp payload is muscle MASS in kilograms (~50 kg for a 65 kg
 * person in Zepp's own sample data), not a rate - see SmartScaleConnect's zepp client,
 * which makes the same call. It is stored as [muscleMassGrams] and deliberately NOT
 * pushed to Garmin, whose "muscle mass" is skeletal muscle only and would read ~40% too
 * high - again matching SmartScaleConnect, which leaves that field out of the FIT file.
 */
@Entity(
    tableName = "scale_measurements",
    indices = [Index(value = ["dateEpochDay"]), Index(value = ["garminUploadedAtMillis"])]
)
data class ScaleMeasurement(
    /** Instant of the weigh-in, epoch millis (Zepp `generatedTime` is seconds; multiplied on read). */
    @PrimaryKey val timestampMillis: Long,
    /** Local calendar day of the weigh-in, for the Day screen and daily trends. */
    val dateEpochDay: Long,
    val weightGrams: Int,
    val heightCm: Float = 0f,
    val bmi: Float = 0f,
    val bodyFatPercent: Float = 0f,
    val bodyWaterPercent: Float = 0f,
    val boneMassGrams: Int = 0,
    val muscleMassGrams: Int = 0,
    val metabolicAge: Int = 0,
    val visceralFat: Int = 0,
    val basalMetabolismKcal: Int = 0,
    val proteinPercent: Float = 0f,
    /** Xiaomi's overall 0-100 body score. */
    val bodyScore: Int = 0,
    /** Xiaomi `bodyStyle`, a 1-9 physique classification - same scale Garmin calls physique rating. */
    val physiqueRating: Int = 0,
    val impedance: Int = 0,
    /** Scale MAC without colons, as Zepp reports it. */
    val deviceId: String = "",
    val source: String = SOURCE_ZEPP,
    /** When this row was pushed to Garmin; 0 = not (yet) uploaded. */
    val garminUploadedAtMillis: Long = 0,
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val weightKg: Float get() = weightGrams / 1000f
    val isUploadedToGarmin: Boolean get() = garminUploadedAtMillis > 0

    companion object {
        const val SOURCE_ZEPP = "zepp"
    }
}

@Dao
interface ScaleMeasurementDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<ScaleMeasurement>)

    /** Latest weigh-in of a day - what the Day screen shows when the scale was used that day. */
    @Query("SELECT * FROM scale_measurements WHERE dateEpochDay = :dateEpochDay ORDER BY timestampMillis DESC LIMIT 1")
    fun observeLatestForDay(dateEpochDay: Long): Flow<ScaleMeasurement?>

    @Query("SELECT * FROM scale_measurements WHERE dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay ORDER BY timestampMillis ASC")
    fun observeRange(fromEpochDay: Long, toEpochDay: Long): Flow<List<ScaleMeasurement>>

    @Query("SELECT * FROM scale_measurements ORDER BY timestampMillis ASC")
    suspend fun getAllOnce(): List<ScaleMeasurement>

    @Query("SELECT COUNT(*) FROM scale_measurements")
    fun observeCount(): Flow<Int>

    @Query("SELECT MAX(timestampMillis) FROM scale_measurements")
    suspend fun latestTimestamp(): Long?

    /** Rows still to be pushed to Garmin, oldest first so a partial upload leaves a contiguous history. */
    @Query("SELECT * FROM scale_measurements WHERE garminUploadedAtMillis = 0 ORDER BY timestampMillis ASC")
    suspend fun pendingGarminUpload(): List<ScaleMeasurement>

    /**
     * Existing rows to preserve their upload state on re-sync: a REPLACE upsert of a
     * freshly downloaded record would otherwise reset garminUploadedAtMillis to 0 and
     * re-upload the whole history on every sync.
     */
    @Query("SELECT timestampMillis, garminUploadedAtMillis FROM scale_measurements WHERE garminUploadedAtMillis > 0")
    suspend fun uploadedTimestamps(): List<UploadedStamp>

    @Query("UPDATE scale_measurements SET garminUploadedAtMillis = :atMillis WHERE timestampMillis IN (:timestamps)")
    suspend fun markUploaded(timestamps: List<Long>, atMillis: Long)
}

/** Projection for [ScaleMeasurementDao.uploadedTimestamps]. */
data class UploadedStamp(
    val timestampMillis: Long,
    val garminUploadedAtMillis: Long
)
