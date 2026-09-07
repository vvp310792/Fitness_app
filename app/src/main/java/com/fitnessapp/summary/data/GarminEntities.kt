package com.fitnessapp.summary.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/*
 * One table per Garmin Connect endpoint, all keyed by the calendar day, all filled by
 * garmin/GarminSyncManager.kt from the unofficial client. Kept as separate tables rather
 * than one very wide row on purpose: each endpoint fails independently (a watch without
 * an HRV sensor 204s on hrv-service, a user who never weighs in has no weight rows), and
 * a per-table upsert means one missing feature never blocks or blanks the others - the
 * same "each section wrapped separately" rule HealthConnectReader follows.
 *
 * None of these are pushed to Firestore. Unlike the Health Connect tables (whose source
 * may only be readable on ONE phone), everything here can be re-fetched from Garmin on any
 * device that's logged in, so cross-device sync would just multiply Firestore writes for
 * data that has a cloud master copy already.
 *
 * Field names track garth's dataclasses (CLAUDE.md, "Прямой доступ к Garmin") - where a
 * JSON key is quoted in a comment below, that's the verbatim key read by GarminApiClient.
 */

/**
 * A night of sleep from `sleep-service/sleep/dailySleepData?date=` (garth
 * `DailySleepData`), attributed to the morning it ended - Garmin's `calendarDate`, the
 * same convention as [DailySummary]. This is the richer sibling of the Health Connect
 * sleep session: it carries Garmin's Sleep Score and its component grades, Sleep Need,
 * the overnight SpO2/respiration/stress/HRV context, and the plain-language feedback
 * Garmin shows in its own morning report.
 */
@Entity(tableName = "garmin_sleep")
data class GarminSleep(
    @PrimaryKey val dateEpochDay: Long,

    val sleepSeconds: Int = 0,
    val napSeconds: Int = 0,
    val deepSeconds: Int = 0,
    val lightSeconds: Int = 0,
    val remSeconds: Int = 0,
    val awakeSeconds: Int = 0,
    val unmeasurableSeconds: Int = 0,
    val awakeCount: Int = 0,
    /** Epoch millis, LOCAL wall clock (Garmin's `sleepStartTimestampLocal` is already shifted). */
    val sleepStartLocalMillis: Long = 0,
    val sleepEndLocalMillis: Long = 0,

    // Sleep Score (0-100) and Garmin's grade for each component: "EXCELLENT" / "GOOD" /
    // "FAIR" / "POOR" (`qualifierKey`).
    val score: Int = 0,
    val scoreQualifier: String = "",
    val durationQualifier: String = "",
    val stressQualifier: String = "",
    val awakeCountQualifier: String = "",
    val remQualifier: String = "",
    val restlessnessQualifier: String = "",
    val lightQualifier: String = "",
    val deepQualifier: String = "",
    val feedback: String = "",
    val insight: String = "",

    // Sleep Need - Garmin's per-night target (baseline + adjustments for training,
    // recent sleep debt, HRV, naps). `sleepNeed.baseline` / `sleepNeed.actual`, minutes.
    val needBaselineMinutes: Int = 0,
    val needActualMinutes: Int = 0,
    val needFeedback: String = "",

    // Overnight physiology. Floats stored as-is; 0 means "not measured".
    val avgSpo2: Float = 0f,
    val lowestSpo2: Int = 0,
    val avgRespiration: Float = 0f,
    val avgSleepStress: Float = 0f,
    val restingHeartRate: Int = 0,
    /** Body Battery gained (or lost, negative) over the night. */
    val bodyBatteryChange: Int = 0,
    /** Skin temperature deviation from the user's baseline, °C. 0 is a real value, so [hasSkinTemp] gates it. */
    val skinTempDeviationC: Float = 0f,
    val hasSkinTemp: Boolean = false,

    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val isEmpty: Boolean get() = sleepSeconds == 0 && score == 0
    val sleepMinutes: Int get() = sleepSeconds / 60
    val deepMinutes: Int get() = deepSeconds / 60
    val lightMinutes: Int get() = lightSeconds / 60
    val remMinutes: Int get() = remSeconds / 60
    val awakeMinutes: Int get() = awakeSeconds / 60
    val napMinutes: Int get() = napSeconds / 60
    /** Minutes short of (positive) or over (negative) tonight's Sleep Need. */
    val needDeficitMinutes: Int get() = if (needActualMinutes > 0 && sleepSeconds > 0) needActualMinutes - sleepMinutes else 0
}

/**
 * Overnight HRV from `hrv-service/hrv/{date}` (garth `HRVData.hrv_summary`). Garmin's HRV
 * Status compares the 7-night average against a personal baseline band learnt over ~3
 * weeks - [status] is Garmin's own verdict ("BALANCED" / "UNBALANCED" / "LOW" / "POOR",
 * or "NONE" while the baseline is still forming), the band is stored so the app can draw
 * where last night landed relative to it.
 */
@Entity(tableName = "garmin_hrv")
data class GarminHrv(
    @PrimaryKey val dateEpochDay: Long,
    val weeklyAvg: Int = 0,
    val lastNightAvg: Int = 0,
    val lastNight5MinHigh: Int = 0,
    val status: String = "",
    val feedbackPhrase: String = "",
    // Baseline band, ms: below lowUpper = low; balancedLow..balancedUpper = balanced.
    val baselineLowUpper: Int = 0,
    val baselineBalancedLow: Int = 0,
    val baselineBalancedUpper: Int = 0,
    val baselineMarkerValue: Float = 0f,
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val isEmpty: Boolean get() = lastNightAvg == 0 && weeklyAvg == 0
    val hasBaseline: Boolean get() = baselineBalancedLow > 0 && baselineBalancedUpper > 0
}

/**
 * Training Readiness from `metrics-service/metrics/trainingreadiness/{date}` (garth
 * `TrainingReadinessData`). Garmin's composite 1-100 "how ready is your body to train"
 * score, plus the six factors it's built from - each a 0-100 percent contribution with
 * Garmin's short phrase for it. The endpoint returns a list (the score is recomputed
 * through the day as data arrives); the sync keeps the latest entry.
 */
@Entity(tableName = "garmin_readiness")
data class GarminReadiness(
    @PrimaryKey val dateEpochDay: Long,
    val score: Int = 0,
    /** "HIGH" / "MODERATE" / "LOW" / "PRIME" etc. - Garmin's `level`. */
    val level: String = "",
    val feedbackShort: String = "",
    val feedbackLong: String = "",
    /** Local time of the latest recompute, ISO string as Garmin sends it (`timestampLocal`). */
    val timestampLocal: String = "",

    val sleepScore: Int = 0,
    val sleepScoreFactorPercent: Int = 0,
    val sleepScoreFactorFeedback: String = "",
    /** Hours of recovery Garmin still recommends before the next hard session. */
    val recoveryTimeHours: Float = 0f,
    val recoveryTimeFactorPercent: Int = 0,
    val recoveryTimeFactorFeedback: String = "",
    val acwrFactorPercent: Int = 0,
    val acwrFactorFeedback: String = "",
    val acuteLoad: Int = 0,
    val stressHistoryFactorPercent: Int = 0,
    val stressHistoryFactorFeedback: String = "",
    val hrvFactorPercent: Int = 0,
    val hrvFactorFeedback: String = "",
    val hrvWeeklyAverage: Int = 0,
    val sleepHistoryFactorPercent: Int = 0,
    val sleepHistoryFactorFeedback: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val isEmpty: Boolean get() = score == 0
}

/**
 * Training Status + load + fitness scores for one day, merged from three endpoints that
 * all describe "where is my training": `mobile-gateway/usersummary/trainingstatus/latest/{date}`
 * (garth `DailyTrainingStatus` - status, weekly load, acute/chronic load, ACWR),
 * `metrics-service/metrics/endurancescore` and `.../hillscore` (garth `GarminScoresData` -
 * Endurance Score, Hill Score and, alongside them, VO2max).
 */
@Entity(tableName = "garmin_training")
data class GarminTraining(
    @PrimaryKey val dateEpochDay: Long,

    /** Garmin's numeric status code, kept raw; the readable status is derived from [statusFeedbackPhrase]. */
    val trainingStatus: Int = 0,
    /** e.g. "PRODUCTIVE_1", "MAINTAINING_2" - the prefix is the status name. */
    val statusFeedbackPhrase: String = "",
    val weeklyTrainingLoad: Int = 0,
    val loadTunnelMin: Int = 0,
    val loadTunnelMax: Int = 0,
    val fitnessTrend: Int = 0,
    val trainingPaused: Boolean = false,

    // Acute:chronic workload ratio - Garmin's overtraining guard (0.8-1.3 is the
    // "optimal" window Garmin itself shows).
    val acwrPercent: Int = 0,
    /** "OPTIMAL" / "HIGH" / "LOW" / "VERY_HIGH" ... - Garmin's `acwrStatus`. */
    val acwrStatus: String = "",
    val acwrStatusFeedback: String = "",
    val dailyTrainingLoadAcute: Int = 0,
    val dailyTrainingLoadChronic: Int = 0,
    val acuteChronicRatio: Float = 0f,

    val vo2Max: Float = 0f,
    val vo2MaxPrecise: Float = 0f,
    val enduranceScore: Int = 0,
    val enduranceClassification: Int = 0,
    val hillScore: Int = 0,
    val hillEnduranceScore: Int = 0,
    val hillStrengthScore: Int = 0,
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val isEmpty: Boolean
        get() = statusFeedbackPhrase.isBlank() && weeklyTrainingLoad == 0 &&
            dailyTrainingLoadAcute == 0 && vo2Max == 0f && enduranceScore == 0 && hillScore == 0

    val hasStatus: Boolean get() = statusFeedbackPhrase.isNotBlank() || trainingStatus > 0
    val hasLoad: Boolean get() = dailyTrainingLoadAcute > 0 || dailyTrainingLoadChronic > 0 || weeklyTrainingLoad > 0
}

/**
 * A weigh-in from `weight-service/weight/range/{start}/{end}?includeAll=true` (garth
 * `WeightData`). Only ever populated for someone with a connected scale or manual weight
 * logging; the body-composition fields need a Garmin Index scale specifically. Weight is
 * grams, as Garmin stores it.
 */
@Entity(tableName = "garmin_body_composition")
data class GarminBodyComposition(
    @PrimaryKey val dateEpochDay: Long,
    val weightGrams: Int = 0,
    val bmi: Float = 0f,
    val bodyFatPercent: Float = 0f,
    val bodyWaterPercent: Float = 0f,
    val boneMassGrams: Int = 0,
    val muscleMassGrams: Int = 0,
    val visceralFat: Float = 0f,
    val metabolicAge: Int = 0,
    val sourceType: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val isEmpty: Boolean get() = weightGrams == 0
    val weightKg: Float get() = weightGrams / 1000f
}

/**
 * A Garmin activity, from `activitylist-service/activities/search/activities` (garth
 * `Activity`) enriched with its `activity-service/activity/{id}` detail (`summaryDTO`,
 * garth `Summary`). This is the same session that arrives in the workouts table via
 * Health Connect, but Garmin's own record carries what Health Connect never exports:
 * Training Effect (aerobic/anaerobic 0-5 and Garmin's label), Training Load, power,
 * cadence, elevation and the session's Body Battery cost.
 *
 * Kept separate from [Workout] (keyed by Garmin's own `activityId`, not Health
 * Connect's record UUID) and matched to it at display time by start time - see
 * [GarminActivityDao.observeRange] callers - because the two sources can each carry a
 * session the other doesn't (a manually logged Garmin activity, or a Health Connect
 * session from before the Garmin login).
 */
@Entity(
    tableName = "garmin_activities",
    indices = [Index(value = ["dateEpochDay"]), Index(value = ["startTimeMillis"])]
)
data class GarminActivity(
    @PrimaryKey val activityId: Long,
    /** Local calendar day the activity started on. */
    val dateEpochDay: Long,
    /** Epoch millis of the start instant (from `startTimeGMT`). */
    val startTimeMillis: Long,
    val name: String = "",
    /** Garmin's sport key, e.g. "running", "strength_training", "lap_swimming". */
    val typeKey: String = "",
    val locationName: String = "",
    val durationSeconds: Int = 0,
    val distanceMeters: Int = 0,
    val calories: Int = 0,
    val avgHeartRate: Int = 0,
    val maxHeartRate: Int = 0,
    val steps: Int = 0,
    val elevationGainMeters: Int = 0,
    val avgSpeedMetersPerSecond: Float = 0f,

    // From the detail summaryDTO
    val aerobicTrainingEffect: Float = 0f,
    val anaerobicTrainingEffect: Float = 0f,
    /** e.g. "TEMPO", "AEROBIC_BASE", "VO2MAX", "RECOVERY", "ANAEROBIC_CAPACITY" ... */
    val trainingEffectLabel: String = "",
    val activityTrainingLoad: Float = 0f,
    val avgRunCadence: Int = 0,
    val avgPower: Int = 0,
    val normalizedPower: Int = 0,
    val moderateIntensityMinutes: Int = 0,
    val vigorousIntensityMinutes: Int = 0,
    /** Body Battery change over the session (negative = drained). */
    val bodyBatteryDiff: Int = 0,
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val durationMinutes: Int get() = durationSeconds / 60
    val hasTrainingEffect: Boolean get() = aerobicTrainingEffect > 0f || anaerobicTrainingEffect > 0f
}

@Dao
interface GarminSleepDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sleep: GarminSleep)

    @Query("SELECT * FROM garmin_sleep WHERE dateEpochDay = :dateEpochDay LIMIT 1")
    fun observeDay(dateEpochDay: Long): Flow<GarminSleep?>

    @Query("SELECT * FROM garmin_sleep WHERE dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay ORDER BY dateEpochDay ASC")
    fun observeRange(fromEpochDay: Long, toEpochDay: Long): Flow<List<GarminSleep>>

    @Query("SELECT * FROM garmin_sleep ORDER BY dateEpochDay ASC")
    suspend fun getAllOnce(): List<GarminSleep>
}

@Dao
interface GarminHrvDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(hrv: GarminHrv)

    @Query("SELECT * FROM garmin_hrv WHERE dateEpochDay = :dateEpochDay LIMIT 1")
    fun observeDay(dateEpochDay: Long): Flow<GarminHrv?>

    @Query("SELECT * FROM garmin_hrv WHERE dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay ORDER BY dateEpochDay ASC")
    fun observeRange(fromEpochDay: Long, toEpochDay: Long): Flow<List<GarminHrv>>

    @Query("SELECT * FROM garmin_hrv ORDER BY dateEpochDay ASC")
    suspend fun getAllOnce(): List<GarminHrv>
}

@Dao
interface GarminReadinessDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(readiness: GarminReadiness)

    @Query("SELECT * FROM garmin_readiness WHERE dateEpochDay = :dateEpochDay LIMIT 1")
    fun observeDay(dateEpochDay: Long): Flow<GarminReadiness?>

    @Query("SELECT * FROM garmin_readiness WHERE dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay ORDER BY dateEpochDay ASC")
    fun observeRange(fromEpochDay: Long, toEpochDay: Long): Flow<List<GarminReadiness>>

    @Query("SELECT * FROM garmin_readiness ORDER BY dateEpochDay ASC")
    suspend fun getAllOnce(): List<GarminReadiness>
}

@Dao
interface GarminTrainingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(training: GarminTraining)

    @Query("SELECT * FROM garmin_training WHERE dateEpochDay = :dateEpochDay LIMIT 1")
    fun observeDay(dateEpochDay: Long): Flow<GarminTraining?>

    @Query("SELECT * FROM garmin_training WHERE dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay ORDER BY dateEpochDay ASC")
    fun observeRange(fromEpochDay: Long, toEpochDay: Long): Flow<List<GarminTraining>>

    @Query("SELECT * FROM garmin_training ORDER BY dateEpochDay ASC")
    suspend fun getAllOnce(): List<GarminTraining>
}

@Dao
interface GarminBodyCompositionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<GarminBodyComposition>)

    @Query("SELECT * FROM garmin_body_composition WHERE dateEpochDay = :dateEpochDay LIMIT 1")
    fun observeDay(dateEpochDay: Long): Flow<GarminBodyComposition?>

    @Query("SELECT * FROM garmin_body_composition WHERE dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay ORDER BY dateEpochDay ASC")
    fun observeRange(fromEpochDay: Long, toEpochDay: Long): Flow<List<GarminBodyComposition>>

    /** The most recent weigh-in on or before a day - what the Day screen shows when that day itself had none. */
    @Query("SELECT * FROM garmin_body_composition WHERE dateEpochDay <= :dateEpochDay ORDER BY dateEpochDay DESC LIMIT 1")
    fun observeLatestUpTo(dateEpochDay: Long): Flow<GarminBodyComposition?>

    @Query("SELECT * FROM garmin_body_composition ORDER BY dateEpochDay ASC")
    suspend fun getAllOnce(): List<GarminBodyComposition>
}

@Dao
interface GarminActivityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(activities: List<GarminActivity>)

    @Query("SELECT * FROM garmin_activities WHERE dateEpochDay = :dateEpochDay ORDER BY startTimeMillis ASC")
    fun observeForDay(dateEpochDay: Long): Flow<List<GarminActivity>>

    @Query("SELECT * FROM garmin_activities WHERE dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay ORDER BY startTimeMillis ASC")
    fun observeRange(fromEpochDay: Long, toEpochDay: Long): Flow<List<GarminActivity>>

    @Query("SELECT * FROM garmin_activities ORDER BY startTimeMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<GarminActivity>>

    @Query("SELECT * FROM garmin_activities ORDER BY startTimeMillis ASC")
    suspend fun getAllOnce(): List<GarminActivity>
}
