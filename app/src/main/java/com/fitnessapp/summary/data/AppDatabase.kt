package com.fitnessapp.summary.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room is the single source of truth for everything the UI renders. Health Connect
 * is upstream of it (a read-only feed we pull from) and Firestore sits on top of it
 * (a sync layer, not a replacement) - neither is ever read directly by a screen.
 *
 * Schema version history lives here. Every future migration must be ADDITIVE:
 * add columns/tables, never drop or rewrite them, and never
 * `fallbackToDestructiveMigration()` - on this app a lost row is lost history that
 * Health Connect may no longer be able to hand back (it only guarantees 30 days
 * without READ_HEALTH_DATA_HISTORY, and providers prune independently).
 */
@Database(
    entities = [
        DailySummary::class,
        Workout::class,
        GarminDailyExtra::class,
        GarminSleep::class,
        GarminHrv::class,
        GarminReadiness::class,
        GarminTraining::class,
        GarminBodyComposition::class,
        GarminActivity::class,
        GarminSyncMark::class,
        ScaleMeasurement::class,
        StrengthSet::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun dailySummaryDao(): DailySummaryDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun garminDailyExtraDao(): GarminDailyExtraDao
    abstract fun garminSleepDao(): GarminSleepDao
    abstract fun garminHrvDao(): GarminHrvDao
    abstract fun garminReadinessDao(): GarminReadinessDao
    abstract fun garminTrainingDao(): GarminTrainingDao
    abstract fun garminBodyCompositionDao(): GarminBodyCompositionDao
    abstract fun garminActivityDao(): GarminActivityDao
    abstract fun garminSyncMarkDao(): GarminSyncMarkDao
    abstract fun scaleMeasurementDao(): ScaleMeasurementDao
    abstract fun strengthSetDao(): StrengthSetDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 -> v2: adds garmin_daily_extra (Stress + Body Battery from the unofficial
         * Garmin Connect client - see garmin/GarminApiClient.kt). A separate table, not
         * new columns on daily_summaries - see GarminDailyExtra's own doc comment.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS garmin_daily_extra (
                        dateEpochDay INTEGER PRIMARY KEY NOT NULL,
                        averageStressLevel INTEGER NOT NULL DEFAULT 0,
                        maxStressLevel INTEGER NOT NULL DEFAULT 0,
                        bodyBatteryAtWake INTEGER NOT NULL DEFAULT 0,
                        bodyBatteryHighest INTEGER NOT NULL DEFAULT 0,
                        bodyBatteryLowest INTEGER NOT NULL DEFAULT 0,
                        updatedAtMillis INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v2 -> v3: the unofficial Garmin client grows from "Stress + Body Battery" to the
         * whole set of Garmin's own daily metrics (see data/GarminEntities.kt for what each
         * table is and which endpoint fills it). garmin_daily_extra gains the rest of the
         * daily summary's fields as new columns; six new tables cover sleep, HRV, training
         * readiness, training status/load, body composition and Garmin's own activity
         * records. Every ADD COLUMN carries a DEFAULT so existing rows stay valid and read
         * as "no data" (0 / "") - the same convention as everywhere else in the schema.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val intColumns = listOf(
                    "restStressSeconds", "lowStressSeconds", "mediumStressSeconds", "highStressSeconds",
                    "totalSteps", "totalDistanceMeters", "totalKilocalories", "activeKilocalories",
                    "floorsAscended", "floorsDescended", "moderateIntensityMinutes", "vigorousIntensityMinutes",
                    "intensityMinutesWeeklyGoal", "activeSeconds", "highlyActiveSeconds", "sedentarySeconds",
                    "sleepingSeconds", "restingHeartRate", "minHeartRate", "maxHeartRate",
                    "lastSevenDaysAvgRestingHeartRate", "averageSpo2", "lowestSpo2", "avgWakingRespiration",
                    "highestRespiration", "lowestRespiration", "hydrationMl", "hydrationGoalMl"
                )
                for (column in intColumns) {
                    db.execSQL("ALTER TABLE garmin_daily_extra ADD COLUMN $column INTEGER NOT NULL DEFAULT 0")
                }
                db.execSQL("ALTER TABLE garmin_daily_extra ADD COLUMN stressQualifier TEXT NOT NULL DEFAULT ''")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS garmin_sleep (
                        dateEpochDay INTEGER PRIMARY KEY NOT NULL,
                        sleepSeconds INTEGER NOT NULL DEFAULT 0,
                        napSeconds INTEGER NOT NULL DEFAULT 0,
                        deepSeconds INTEGER NOT NULL DEFAULT 0,
                        lightSeconds INTEGER NOT NULL DEFAULT 0,
                        remSeconds INTEGER NOT NULL DEFAULT 0,
                        awakeSeconds INTEGER NOT NULL DEFAULT 0,
                        unmeasurableSeconds INTEGER NOT NULL DEFAULT 0,
                        awakeCount INTEGER NOT NULL DEFAULT 0,
                        sleepStartLocalMillis INTEGER NOT NULL DEFAULT 0,
                        sleepEndLocalMillis INTEGER NOT NULL DEFAULT 0,
                        score INTEGER NOT NULL DEFAULT 0,
                        scoreQualifier TEXT NOT NULL DEFAULT '',
                        durationQualifier TEXT NOT NULL DEFAULT '',
                        stressQualifier TEXT NOT NULL DEFAULT '',
                        awakeCountQualifier TEXT NOT NULL DEFAULT '',
                        remQualifier TEXT NOT NULL DEFAULT '',
                        restlessnessQualifier TEXT NOT NULL DEFAULT '',
                        lightQualifier TEXT NOT NULL DEFAULT '',
                        deepQualifier TEXT NOT NULL DEFAULT '',
                        feedback TEXT NOT NULL DEFAULT '',
                        insight TEXT NOT NULL DEFAULT '',
                        needBaselineMinutes INTEGER NOT NULL DEFAULT 0,
                        needActualMinutes INTEGER NOT NULL DEFAULT 0,
                        needFeedback TEXT NOT NULL DEFAULT '',
                        avgSpo2 REAL NOT NULL DEFAULT 0,
                        lowestSpo2 INTEGER NOT NULL DEFAULT 0,
                        avgRespiration REAL NOT NULL DEFAULT 0,
                        avgSleepStress REAL NOT NULL DEFAULT 0,
                        restingHeartRate INTEGER NOT NULL DEFAULT 0,
                        bodyBatteryChange INTEGER NOT NULL DEFAULT 0,
                        skinTempDeviationC REAL NOT NULL DEFAULT 0,
                        hasSkinTemp INTEGER NOT NULL DEFAULT 0,
                        updatedAtMillis INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS garmin_hrv (
                        dateEpochDay INTEGER PRIMARY KEY NOT NULL,
                        weeklyAvg INTEGER NOT NULL DEFAULT 0,
                        lastNightAvg INTEGER NOT NULL DEFAULT 0,
                        lastNight5MinHigh INTEGER NOT NULL DEFAULT 0,
                        status TEXT NOT NULL DEFAULT '',
                        feedbackPhrase TEXT NOT NULL DEFAULT '',
                        baselineLowUpper INTEGER NOT NULL DEFAULT 0,
                        baselineBalancedLow INTEGER NOT NULL DEFAULT 0,
                        baselineBalancedUpper INTEGER NOT NULL DEFAULT 0,
                        baselineMarkerValue REAL NOT NULL DEFAULT 0,
                        updatedAtMillis INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS garmin_readiness (
                        dateEpochDay INTEGER PRIMARY KEY NOT NULL,
                        score INTEGER NOT NULL DEFAULT 0,
                        level TEXT NOT NULL DEFAULT '',
                        feedbackShort TEXT NOT NULL DEFAULT '',
                        feedbackLong TEXT NOT NULL DEFAULT '',
                        timestampLocal TEXT NOT NULL DEFAULT '',
                        sleepScore INTEGER NOT NULL DEFAULT 0,
                        sleepScoreFactorPercent INTEGER NOT NULL DEFAULT 0,
                        sleepScoreFactorFeedback TEXT NOT NULL DEFAULT '',
                        recoveryTimeHours REAL NOT NULL DEFAULT 0,
                        recoveryTimeFactorPercent INTEGER NOT NULL DEFAULT 0,
                        recoveryTimeFactorFeedback TEXT NOT NULL DEFAULT '',
                        acwrFactorPercent INTEGER NOT NULL DEFAULT 0,
                        acwrFactorFeedback TEXT NOT NULL DEFAULT '',
                        acuteLoad INTEGER NOT NULL DEFAULT 0,
                        stressHistoryFactorPercent INTEGER NOT NULL DEFAULT 0,
                        stressHistoryFactorFeedback TEXT NOT NULL DEFAULT '',
                        hrvFactorPercent INTEGER NOT NULL DEFAULT 0,
                        hrvFactorFeedback TEXT NOT NULL DEFAULT '',
                        hrvWeeklyAverage INTEGER NOT NULL DEFAULT 0,
                        sleepHistoryFactorPercent INTEGER NOT NULL DEFAULT 0,
                        sleepHistoryFactorFeedback TEXT NOT NULL DEFAULT '',
                        updatedAtMillis INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS garmin_training (
                        dateEpochDay INTEGER PRIMARY KEY NOT NULL,
                        trainingStatus INTEGER NOT NULL DEFAULT 0,
                        statusFeedbackPhrase TEXT NOT NULL DEFAULT '',
                        weeklyTrainingLoad INTEGER NOT NULL DEFAULT 0,
                        loadTunnelMin INTEGER NOT NULL DEFAULT 0,
                        loadTunnelMax INTEGER NOT NULL DEFAULT 0,
                        fitnessTrend INTEGER NOT NULL DEFAULT 0,
                        trainingPaused INTEGER NOT NULL DEFAULT 0,
                        acwrPercent INTEGER NOT NULL DEFAULT 0,
                        acwrStatus TEXT NOT NULL DEFAULT '',
                        acwrStatusFeedback TEXT NOT NULL DEFAULT '',
                        dailyTrainingLoadAcute INTEGER NOT NULL DEFAULT 0,
                        dailyTrainingLoadChronic INTEGER NOT NULL DEFAULT 0,
                        acuteChronicRatio REAL NOT NULL DEFAULT 0,
                        vo2Max REAL NOT NULL DEFAULT 0,
                        vo2MaxPrecise REAL NOT NULL DEFAULT 0,
                        enduranceScore INTEGER NOT NULL DEFAULT 0,
                        enduranceClassification INTEGER NOT NULL DEFAULT 0,
                        hillScore INTEGER NOT NULL DEFAULT 0,
                        hillEnduranceScore INTEGER NOT NULL DEFAULT 0,
                        hillStrengthScore INTEGER NOT NULL DEFAULT 0,
                        updatedAtMillis INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS garmin_body_composition (
                        dateEpochDay INTEGER PRIMARY KEY NOT NULL,
                        weightGrams INTEGER NOT NULL DEFAULT 0,
                        bmi REAL NOT NULL DEFAULT 0,
                        bodyFatPercent REAL NOT NULL DEFAULT 0,
                        bodyWaterPercent REAL NOT NULL DEFAULT 0,
                        boneMassGrams INTEGER NOT NULL DEFAULT 0,
                        muscleMassGrams INTEGER NOT NULL DEFAULT 0,
                        visceralFat REAL NOT NULL DEFAULT 0,
                        metabolicAge INTEGER NOT NULL DEFAULT 0,
                        sourceType TEXT NOT NULL DEFAULT '',
                        updatedAtMillis INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS garmin_activities (
                        activityId INTEGER PRIMARY KEY NOT NULL,
                        dateEpochDay INTEGER NOT NULL,
                        startTimeMillis INTEGER NOT NULL,
                        name TEXT NOT NULL DEFAULT '',
                        typeKey TEXT NOT NULL DEFAULT '',
                        locationName TEXT NOT NULL DEFAULT '',
                        durationSeconds INTEGER NOT NULL DEFAULT 0,
                        distanceMeters INTEGER NOT NULL DEFAULT 0,
                        calories INTEGER NOT NULL DEFAULT 0,
                        avgHeartRate INTEGER NOT NULL DEFAULT 0,
                        maxHeartRate INTEGER NOT NULL DEFAULT 0,
                        steps INTEGER NOT NULL DEFAULT 0,
                        elevationGainMeters INTEGER NOT NULL DEFAULT 0,
                        avgSpeedMetersPerSecond REAL NOT NULL DEFAULT 0,
                        aerobicTrainingEffect REAL NOT NULL DEFAULT 0,
                        anaerobicTrainingEffect REAL NOT NULL DEFAULT 0,
                        trainingEffectLabel TEXT NOT NULL DEFAULT '',
                        activityTrainingLoad REAL NOT NULL DEFAULT 0,
                        avgRunCadence INTEGER NOT NULL DEFAULT 0,
                        avgPower INTEGER NOT NULL DEFAULT 0,
                        normalizedPower INTEGER NOT NULL DEFAULT 0,
                        moderateIntensityMinutes INTEGER NOT NULL DEFAULT 0,
                        vigorousIntensityMinutes INTEGER NOT NULL DEFAULT 0,
                        bodyBatteryDiff INTEGER NOT NULL DEFAULT 0,
                        updatedAtMillis INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                // Index names follow Room's own `index_<table>_<column>` convention - Room
                // validates the schema on open and a differently named index is a mismatch.
                db.execSQL("CREATE INDEX IF NOT EXISTS index_garmin_activities_dateEpochDay ON garmin_activities(dateEpochDay)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_garmin_activities_startTimeMillis ON garmin_activities(startTimeMillis)")
            }
        }

        /**
         * v3 -> v4: makes the Garmin sync incremental. `garmin_sync_marks` remembers which
         * day/section pairs are already settled (data stored, or Garmin saying "nothing
         * here") so a later sync skips them instead of asking again - see
         * [GarminSyncMark]. `detailsLoaded` on garmin_activities does the same for the
         * per-activity detail call. Both default to "not known yet", so the first sync
         * after the update behaves exactly as before and fills the marks in as it goes.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS garmin_sync_marks (
                        dateEpochDay INTEGER NOT NULL,
                        section TEXT NOT NULL,
                        hasData INTEGER NOT NULL DEFAULT 0,
                        logicVersion INTEGER NOT NULL DEFAULT 0,
                        updatedAtMillis INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(dateEpochDay, section)
                    )
                    """.trimIndent()
                )
                db.execSQL("ALTER TABLE garmin_activities ADD COLUMN detailsLoaded INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v4 -> v5: `scale_measurements` - weigh-ins from a non-Garmin smart scale (Mi Body
         * Composition Scale via the Zepp Life cloud, scale/). Its own table, not rows in
         * garmin_body_composition: see ScaleMeasurement's doc comment for why the scale's
         * reading and Garmin's copy of it are kept as two facts.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS scale_measurements (
                        timestampMillis INTEGER PRIMARY KEY NOT NULL,
                        dateEpochDay INTEGER NOT NULL,
                        weightGrams INTEGER NOT NULL,
                        heightCm REAL NOT NULL DEFAULT 0,
                        bmi REAL NOT NULL DEFAULT 0,
                        bodyFatPercent REAL NOT NULL DEFAULT 0,
                        bodyWaterPercent REAL NOT NULL DEFAULT 0,
                        boneMassGrams INTEGER NOT NULL DEFAULT 0,
                        muscleMassGrams INTEGER NOT NULL DEFAULT 0,
                        metabolicAge INTEGER NOT NULL DEFAULT 0,
                        visceralFat INTEGER NOT NULL DEFAULT 0,
                        basalMetabolismKcal INTEGER NOT NULL DEFAULT 0,
                        proteinPercent REAL NOT NULL DEFAULT 0,
                        bodyScore INTEGER NOT NULL DEFAULT 0,
                        physiqueRating INTEGER NOT NULL DEFAULT 0,
                        impedance INTEGER NOT NULL DEFAULT 0,
                        deviceId TEXT NOT NULL DEFAULT '',
                        source TEXT NOT NULL DEFAULT 'zepp',
                        garminUploadedAtMillis INTEGER NOT NULL DEFAULT 0,
                        updatedAtMillis INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scale_measurements_dateEpochDay ON scale_measurements(dateEpochDay)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scale_measurements_garminUploadedAtMillis ON scale_measurements(garminUploadedAtMillis)")
            }
        }

        /** Imported gym log - see data/StrengthEntities.kt. Additive, like every migration here. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS strength_sets (
                        startMillis INTEGER NOT NULL,
                        dateEpochDay INTEGER NOT NULL,
                        exerciseName TEXT NOT NULL,
                        lift TEXT NOT NULL DEFAULT '',
                        setIndex INTEGER NOT NULL,
                        weightKg REAL NOT NULL DEFAULT 0,
                        reps INTEGER NOT NULL DEFAULT 0,
                        updatedAtMillis INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(startMillis, exerciseName, setIndex)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_strength_sets_dateEpochDay ON strength_sets(dateEpochDay)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_strength_sets_lift ON strength_sets(lift)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "fitness_summary.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
