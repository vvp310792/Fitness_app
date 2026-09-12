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
    /**
     * True once the per-activity detail call (`activity-service/activity/{id}`) has been
     * made for this row. Stored rather than inferred from the detail fields being
     * non-zero, because a legitimately detail-less session (a strength set with no
     * Training Effect) would otherwise be re-fetched on every single sync forever.
     */
    val detailsLoaded: Boolean = false,

    /**
     * Seconds spent in each heart-rate zone, Z1..Z5, as **Garmin itself counted them**
     * from the per-second heart rate (`activity-service/{id}/hrTimeInZones`).
     *
     * This exists because the alternative was wrong in practice. Without it the app had
     * only one average per session, so a 45-minute run averaging 125 with a peak of 167
     * was filed whole into zone 1 - and a week that really did hold time in Z2 and Z3 came
     * out as "100 % Z1". The user caught exactly that. An average cannot answer "how long
     * was I above threshold", and no amount of care in presenting it makes it able to.
     *
     * It also fixes the per-sport problem for free: Garmin counts a run against the running
     * zone ladder and everything else against the default one, which is what the watch
     * shows. The app's own fallback can only ever use one ladder for everything.
     */
    val zone1Seconds: Int = 0,
    val zone2Seconds: Int = 0,
    val zone3Seconds: Int = 0,
    val zone4Seconds: Int = 0,
    val zone5Seconds: Int = 0,
    /**
     * True once the time-in-zones call has been made for this activity. Stored rather than
     * inferred from the seconds being non-zero, for the same reason [detailsLoaded] is: a
     * session that legitimately has none (a pool swim with no wrist heart rate) would
     * otherwise be re-fetched on every sync forever.
     */
    val zonesLoaded: Boolean = false,
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val durationMinutes: Int get() = durationSeconds / 60
    val hasTrainingEffect: Boolean get() = aerobicTrainingEffect > 0f || anaerobicTrainingEffect > 0f

    /** Z1..Z5 seconds, or null when Garmin has not given this activity a breakdown. */
    val zoneSeconds: List<Int>?
        get() = listOf(zone1Seconds, zone2Seconds, zone3Seconds, zone4Seconds, zone5Seconds)
            .takeIf { it.sum() > 0 }
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

    /**
     * Stored rows by id, so an activity that only needs one of its two enrichment calls
     * can be rebuilt from what is already known instead of from the list response - the
     * list carries no Training Effect, and upserting it over a detailed row would erase it.
     */
    @Query("SELECT * FROM garmin_activities WHERE activityId IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<GarminActivity>

    /** Ids already enriched with their detail call - see [GarminActivity.detailsLoaded]. */
    @Query("SELECT activityId FROM garmin_activities WHERE dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay AND detailsLoaded = 1")
    suspend fun detailedIdsInRange(fromEpochDay: Long, toEpochDay: Long): List<Long>

    /**
     * Ids whose time-in-zones question is already settled: either it was fetched, or the
     * session carries no heart rate at all and never will (a pool swim), so asking Garmin
     * about its zones would be one wasted request per sync, forever.
     */
    @Query(
        "SELECT activityId FROM garmin_activities WHERE dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay " +
            "AND (zonesLoaded = 1 OR avgHeartRate <= 0)"
    )
    suspend fun zonedIdsInRange(fromEpochDay: Long, toEpochDay: Long): List<Long>
}

/**
 * One "we already asked Garmin about this day and section" note, so a later sync doesn't
 * ask again.
 *
 * Written for both outcomes that settle a question - data stored, and Garmin answering
 * "nothing here" - but deliberately NOT for a failure: a day whose call failed has no
 * mark, so it is exactly what the next run retries. That is what makes an interrupted
 * 90-day backfill resumable instead of starting over, and what stops a watch that has no
 * HRV at all from being asked 90 times about it on every sync.
 *
 * [logicVersion] stamps the marks with the sync logic that wrote them. When a fix changes
 * what a call would return - as the User-Agent fix did, turning three permanently silent
 * endpoints into working ones - bumping the constant invalidates every old mark at once,
 * so nobody is left with "no data" cached from a version that was asking wrongly. That is
 * the alternative to a "re-read everything" button the user would have to know to press.
 *
 * Recent days are never skipped regardless of their marks (see
 * [GarminSyncManager.ALWAYS_REFRESH_DAYS]): Garmin revises the last few days as the watch
 * syncs late, the same reason the Health Connect window is wider than one day.
 */
@Entity(tableName = "garmin_sync_marks", primaryKeys = ["dateEpochDay", "section"])
data class GarminSyncMark(
    val dateEpochDay: Long,
    /** One of [GarminSyncMark.SECTION_SUMMARY] etc. */
    val section: String,
    /** True when the day produced a stored row; false when Garmin answered "nothing here". */
    val hasData: Boolean,
    val logicVersion: Int,
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    companion object {
        const val SECTION_SUMMARY = "summary"
        const val SECTION_SLEEP = "sleep"
        const val SECTION_HRV = "hrv"
        const val SECTION_READINESS = "readiness"
        const val SECTION_TRAINING = "training"

        /**
         * The two range-shaped sections. They are fetched once per WINDOW, not per day,
         * but they are marked per day like everything else - once the window's call
         * succeeds, every day it covered gets a mark.
         *
         * They need marks at all because of a bug that shipped without them: a window in
         * which all five per-day sections were settled was skipped whole, including its
         * activity list, so any window whose list call had failed in an earlier pass could
         * never be retried - months of workouts missing from the history with nothing in
         * the log to say so. A section that can fail independently needs a mark of its own,
         * or "settled" means "settled for the parts we happened to write down".
         */
        const val SECTION_ACTIVITIES = "activities"
        const val SECTION_WEIGHT = "weight"

        /** Every section a day can be settled for. */
        val ALL_SECTIONS = listOf(
            SECTION_SUMMARY, SECTION_SLEEP, SECTION_HRV, SECTION_READINESS, SECTION_TRAINING,
            SECTION_ACTIVITIES, SECTION_WEIGHT
        )

        /** The per-day ones - the sections the day loop actually asks about. */
        val DAY_SECTIONS = listOf(
            SECTION_SUMMARY, SECTION_SLEEP, SECTION_HRV, SECTION_READINESS, SECTION_TRAINING
        )
    }
}

@Dao
interface GarminSyncMarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(marks: List<GarminSyncMark>)

    @Query(
        "SELECT * FROM garmin_sync_marks WHERE dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay " +
            "AND logicVersion = :logicVersion"
    )
    suspend fun marksInRange(fromEpochDay: Long, toEpochDay: Long, logicVersion: Int): List<GarminSyncMark>

    /**
     * How many days are settled per section, and the oldest/newest of them - the compact
     * form of a table that can hold tens of thousands of rows. This is what the JSON
     * export carries instead of the rows themselves: it answers "what does the app think
     * it already has", which is the question every sync bug so far has turned on, without
     * making the export ten times bigger than the data it is about.
     */
    @Query(
        "SELECT section, COUNT(*) AS days, SUM(CASE WHEN hasData THEN 1 ELSE 0 END) AS daysWithData, " +
            "MIN(dateEpochDay) AS firstDay, MAX(dateEpochDay) AS lastDay " +
            "FROM garmin_sync_marks GROUP BY section ORDER BY section"
    )
    suspend fun sectionCoverage(): List<GarminSectionCoverage>

    @Query("DELETE FROM garmin_sync_marks")
    suspend fun clear()
}

/** One row of [GarminSyncMarkDao.sectionCoverage] - see its doc for why this shape. */
data class GarminSectionCoverage(
    val section: String,
    val days: Int,
    val daysWithData: Int,
    val firstDay: Long,
    val lastDay: Long
)

/**
 * The user's OWN heart-rate zones, as configured in Garmin Connect, for one sport profile.
 *
 * This table exists because the app got zones wrong by modelling them. The first version
 * computed five bands as fixed percentages of a maximum heart rate the app itself
 * estimated - and the app has never known the user's age, so that estimate was the 95th
 * percentile of their recorded session maxima, which reads 20-30 bpm low after an easy
 * season and drags all five boundaries down with it. Meanwhile Garmin has had the real
 * answer on its server the whole time: the max it uses, the five floors, and which method
 * they were derived by. Reading it is the same principle the day/week screens already
 * follow - Garmin is the first source, a local model of it is a copy at best.
 *
 * Keyed by [sport] because Garmin configures zones per sport profile: "DEFAULT" plus
 * whatever else the user has customised (running, cycling, swimming). One row each, so a
 * profile the user never touched simply isn't here.
 *
 * [rawJson] is the response object for this sport, verbatim. It is here on purpose and it
 * is not a debugging leftover: the field names in this payload are the only ones in the
 * whole Garmin client that could NOT be checked against garth or python-garminconnect -
 * neither carries a fixture for it - so the parser matches keys structurally and keeps the
 * original alongside. If a zone comes back 0, the raw row is what says whether Garmin sent
 * nothing or sent it under a name the parser didn't recognise. Those two look identical
 * everywhere else, and telling them apart is exactly what this project keeps paying for.
 */
@Entity(tableName = "garmin_heart_rate_zones")
data class GarminHeartRateZone(
    /** Garmin's sport profile key, uppercase: "DEFAULT", "RUNNING", "CYCLING", "SWIMMING". */
    @PrimaryKey val sport: String,
    /** Lowest bpm of each zone, Z1..Z5. 0 means Garmin didn't give this zone a floor. */
    val zone1Floor: Int = 0,
    val zone2Floor: Int = 0,
    val zone3Floor: Int = 0,
    val zone4Floor: Int = 0,
    val zone5Floor: Int = 0,
    /** The maximum heart rate Garmin used to derive the floors above. */
    val maxHeartRateUsed: Int = 0,
    val restingHeartRateUsed: Int = 0,
    val lactateThresholdHeartRateUsed: Int = 0,
    /** How Garmin derived them, in its own words - e.g. percent of max HR, of HRR, of LTHR. */
    val method: String = "",
    val rawJson: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    /** Floors Z1..Z5 in order. */
    val floors: List<Int> get() = listOf(zone1Floor, zone2Floor, zone3Floor, zone4Floor, zone5Floor)

    /**
     * Usable only when the floors actually ascend. A partially parsed row - three floors
     * found, two left at 0 - would otherwise put every session into whichever band the
     * zeros collapsed into, which looks like data rather than like a parse failure.
     */
    val isUsable: Boolean
        get() = floors.all { it > 0 } && floors.zipWithNext().all { (a, b) -> b > a }
}

@Dao
interface GarminHeartRateZoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(zones: List<GarminHeartRateZone>)

    @Query("SELECT * FROM garmin_heart_rate_zones ORDER BY sport ASC")
    fun observeAll(): Flow<List<GarminHeartRateZone>>

    @Query("SELECT * FROM garmin_heart_rate_zones ORDER BY sport ASC")
    suspend fun getAllOnce(): List<GarminHeartRateZone>

    @Query("DELETE FROM garmin_heart_rate_zones")
    suspend fun clear()
}
