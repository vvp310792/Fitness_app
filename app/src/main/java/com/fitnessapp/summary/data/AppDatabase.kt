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
    entities = [DailySummary::class, Workout::class, GarminDailyExtra::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun dailySummaryDao(): DailySummaryDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun garminDailyExtraDao(): GarminDailyExtraDao

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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "fitness_summary.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
