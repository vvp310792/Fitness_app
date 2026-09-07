package com.fitnessapp.summary.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    entities = [DailySummary::class, Workout::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun dailySummaryDao(): DailySummaryDao
    abstract fun workoutDao(): WorkoutDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "fitness_summary.db"
                )
                    // .addMigrations(...) goes here as the schema grows - see the
                    // additive-only rule in the class comment above.
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
