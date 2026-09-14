package com.fitnessapp.summary.analytics

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.fitnessapp.summary.data.GarminActivity
import com.fitnessapp.summary.data.StravaActivity
import com.fitnessapp.summary.data.Workout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * "Импортируем, только без дублей" - this is the test of that promise.
 *
 * Strava receives these sessions from Garmin, so on this account 1236 of 3028 exported
 * rows are activities the app already holds. They are stored anyway and dropped HERE, on
 * read, which is the part worth pinning: the alternative - deciding it while importing -
 * bakes today's answer into the rows, and the answer changes every time Garmin backfills
 * another month. That exact mistake is already a scar in this codebase
 * (`strength_sets.lift`), so the rule is tested rather than trusted.
 */
class StravaMergeTest {

    private val monday = LocalDate.of(2026, 9, 7)

    private fun startOf(day: LocalDate, hour: Int, minute: Int = 0) =
        day.toEpochDay() * 86_400_000L + hour * 3_600_000L + minute * 60_000L

    private fun garmin(day: LocalDate, typeKey: String, meters: Int, hour: Int = 9) = GarminActivity(
        activityId = day.toEpochDay() * 100 + hour,
        dateEpochDay = day.toEpochDay(),
        startTimeMillis = startOf(day, hour),
        typeKey = typeKey,
        distanceMeters = meters,
        durationSeconds = 3600,
        avgHeartRate = 150
    )

    private fun workout(day: LocalDate, type: Int, meters: Int, hour: Int = 9) = Workout(
        recordId = "$day-$hour",
        dateEpochDay = day.toEpochDay(),
        startTimeMillis = startOf(day, hour),
        endTimeMillis = startOf(day, hour) + 3_600_000L,
        exerciseType = type,
        distanceMeters = meters
    )

    private fun strava(
        day: LocalDate,
        type: String,
        meters: Int,
        hour: Int = 9,
        minute: Int = 0,
        avgHr: Int = 0
    ) = StravaActivity(
        activityId = day.toEpochDay() * 1000 + hour * 60 + minute,
        dateEpochDay = day.toEpochDay(),
        startTimeMillis = startOf(day, hour, minute),
        typeRaw = type,
        distanceMeters = meters,
        durationSeconds = 3600,
        avgHeartRate = avgHr
    )

    @Test
    fun `a ride Garmin already has is not counted twice`() {
        val sessions = SportDistanceAnalytics.sessions(
            garmin = listOf(garmin(monday, "cycling", 30_000)),
            workouts = emptyList(),
            strava = listOf(strava(monday, "Велосипед", 30_000))
        )
        assertEquals(1, sessions.size)
        assertEquals(30_000, sessions.single().meters)
    }

    @Test
    fun `the match is by start time within three minutes, not by exact equality`() {
        // Strava and Garmin stamp the same ride a minute or two apart. Requiring equality
        // would let every one of them through as a second session and double the year.
        val nearly = SportDistanceAnalytics.sessions(
            garmin = listOf(garmin(monday, "running", 10_000, hour = 7)),
            workouts = emptyList(),
            strava = listOf(strava(monday, "Бег", 10_000, hour = 7, minute = 2))
        )
        assertEquals(1, nearly.size)

        // Four minutes apart is a different session - and on a brick day it genuinely is.
        val separate = SportDistanceAnalytics.sessions(
            garmin = listOf(garmin(monday, "running", 10_000, hour = 7)),
            workouts = emptyList(),
            strava = listOf(strava(monday, "Велосипед", 30_000, hour = 7, minute = 4))
        )
        assertEquals(2, separate.size)
    }

    @Test
    fun `a session Health Connect already has is not counted twice either`() {
        val sessions = SportDistanceAnalytics.sessions(
            garmin = emptyList(),
            workouts = listOf(workout(monday, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, 8_000)),
            strava = listOf(strava(monday, "Бег", 8_000))
        )
        assertEquals(1, sessions.size)
    }

    @Test
    fun `the years before the watch come through - that is the whole point`() {
        val old = LocalDate.of(2016, 6, 6)
        val sessions = SportDistanceAnalytics.sessions(
            garmin = emptyList(),
            workouts = emptyList(),
            strava = listOf(
                strava(old, "Бег", 12_000),
                strava(old, "Велосипед", 40_000, hour = 17),
                strava(old, "Плавание", 1_500, hour = 20)
            )
        )
        assertEquals(3, sessions.size)
        assertEquals(
            setOf(DistanceSport.RUN, DistanceSport.BIKE, DistanceSport.SWIM),
            sessions.map { it.sport }.toSet()
        )
    }

    @Test
    fun `a walk imported from Strava never inflates a running week`() {
        val sessions = SportDistanceAnalytics.sessions(
            garmin = emptyList(),
            workouts = emptyList(),
            strava = listOf(strava(monday, "Ходьба", 6_000), strava(monday, "Хайкинг", 9_000, hour = 14))
        )
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `imported kilometres reach the weekly chart`() {
        val old = LocalDate.of(2016, 6, 6) // also a Monday
        val merged = SportDistanceAnalytics.sessions(
            garmin = emptyList(),
            workouts = emptyList(),
            strava = listOf(strava(old, "Бег", 12_000), strava(old.plusDays(3), "Бег", 8_000, hour = 18))
        )
        val weeks = SportDistanceAnalytics.weeks(DistanceSport.RUN, merged, old, old.plusDays(6))
        assertEquals(1, weeks.size)
        assertEquals(20_000, weeks.single().meters)
        assertEquals(2, weeks.single().sessions)
    }

    @Test
    fun `the same de-duplication holds for the intensity sessions`() {
        val sessions = IntensityAnalytics.sessions(
            garmin = listOf(garmin(monday, "running", 10_000, hour = 7)),
            workouts = emptyList(),
            strava = listOf(
                strava(monday, "Бег", 10_000, hour = 7, minute = 1, avgHr = 150),
                strava(LocalDate.of(2017, 3, 3), "Бег", 9_000, avgHr = 140)
            )
        )
        assertEquals(2, sessions.size)
        // The 2017 one is the one that could only have come from Strava.
        assertTrue(sessions.any { it.dateEpochDay == LocalDate.of(2017, 3, 3).toEpochDay() })
    }

    @Test
    fun `an imported session without a heart rate is counted apart, not charged to Z1`() {
        // Strava carried no heart rate for 0 of 131 sessions in 2015 and 9 of 223 in 2020,
        // so this is the normal case for the imported years, not an edge one.
        val sessions = IntensityAnalytics.sessions(
            garmin = emptyList(),
            workouts = emptyList(),
            strava = listOf(strava(LocalDate.of(2015, 5, 5), "Бег", 9_000, avgHr = 0))
        )
        assertEquals(1, sessions.size)
        assertEquals(false, sessions.single().hasHeartRate)
        assertEquals("Бег", sessions.single().typeKey)
    }
}
