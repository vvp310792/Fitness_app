package com.fitnessapp.summary.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single exercise session (an ExerciseSessionRecord in Health Connect terms).
 *
 * [recordId] is Health Connect's own record UUID, used verbatim as the primary key.
 * It's stable for the lifetime of the record, so re-reading the same day over and
 * over just overwrites the same rows instead of piling up duplicates - the same
 * reason the daily summary keys on the date. It doubles as the Firestore document id.
 *
 * [exerciseType] is the raw Health Connect `EXERCISE_TYPE_*` int. It's stored raw
 * rather than as a resolved label so a newer Health Connect version adding a sport
 * doesn't require a database migration - see util/ExerciseTypes.kt, which maps it to
 * a Russian name and an emoji at display time and falls back gracefully on unknowns.
 */
@Entity(
    tableName = "workouts",
    indices = [Index(value = ["dateEpochDay"]), Index(value = ["startTimeMillis"])]
)
data class Workout(
    @PrimaryKey val recordId: String,
    /** Day the session STARTED on, local time - what groups it onto a day/week screen. */
    val dateEpochDay: Long,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val exerciseType: Int,
    val title: String = "",
    val durationMinutes: Int = 0,
    val distanceMeters: Int = 0,
    val activeCaloriesKcal: Int = 0,
    val avgHeartRate: Int = 0,
    val maxHeartRate: Int = 0,
    val updatedAtMillis: Long = System.currentTimeMillis()
)
