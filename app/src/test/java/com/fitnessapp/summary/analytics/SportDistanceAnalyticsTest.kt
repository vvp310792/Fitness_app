package com.fitnessapp.summary.analytics

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.fitnessapp.summary.data.GarminActivity
import com.fitnessapp.summary.data.Workout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * The two places weekly volume can be silently wrong: which sport a session lands in, and
 * which week it lands in. Both produce a plausible-looking chart when broken, so both are
 * pinned here rather than eyeballed.
 */
class SportDistanceAnalyticsTest {

    // A Monday, so the week arithmetic is readable in the assertions below.
    private val monday = LocalDate.of(2026, 9, 7)

    private fun garmin(day: LocalDate, typeKey: String, meters: Int, hour: Int = 9) = GarminActivity(
        activityId = day.toEpochDay() * 100 + hour,
        dateEpochDay = day.toEpochDay(),
        startTimeMillis = day.toEpochDay() * 86_400_000L + hour * 3_600_000L,
        typeKey = typeKey,
        distanceMeters = meters
    )

    private fun workout(day: LocalDate, type: Int, meters: Int, hour: Int = 9) = Workout(
        recordId = "$day-$hour",
        dateEpochDay = day.toEpochDay(),
        startTimeMillis = day.toEpochDay() * 86_400_000L + hour * 3_600_000L,
        endTimeMillis = day.toEpochDay() * 86_400_000L + hour * 3_600_000L + 3_600_000L,
        exerciseType = type,
        distanceMeters = meters
    )

    /**
     * The seventeen sports this account's own Garmin export actually contains, each with
     * the bucket it must land in. Written from the export rather than from imagination:
     * the counts in the comments are the real ones, and they are why each line matters -
     * 185 treadmill runs and 228 pool swims are not a rounding error to get wrong.
     */
    @Test
    fun `the real sport mix of this account lands where it should`() {
        val expected = mapOf(
            // 151 outdoor, 185 treadmill, 42 trail, 1 indoor - all one sport.
            "running" to DistanceSport.RUN,
            "treadmill_running" to DistanceSport.RUN,
            "trail_running" to DistanceSport.RUN,
            "indoor_running" to DistanceSport.RUN,
            // 172 outdoor, 1 indoor.
            "cycling" to DistanceSport.BIKE,
            "indoor_cycling" to DistanceSport.BIKE,
            // Not in this export, but the keys Garmin uses elsewhere for the same sport.
            // road_biking contains "bik" and NOT "bike" - the whole reason the check is
            // written on the shorter stem.
            "road_biking" to DistanceSport.BIKE,
            "mountain_biking" to DistanceSport.BIKE,
            "gravel_cycling" to DistanceSport.BIKE,
            "virtual_ride" to DistanceSport.BIKE,
            // 228 pool, 10 open water - one sport, as the user asked.
            "lap_swimming" to DistanceSport.SWIM,
            "open_water_swimming" to DistanceSport.SWIM,
            // Everything else in the export carries no distance, or is not one of the three.
            "strength_training" to null,
            "walking" to null,
            "hiking" to null,
            "indoor_rowing" to null,
            "cross_country_classic_skiing" to null,
            "floor_climbing" to null,
            "multi_sport" to null,
            "cardio" to null,
            "other" to null
        )
        expected.forEach { (key, sport) -> assertEquals(key, sport, DistanceSport.ofGarmin(key)) }
    }

    /** "cycl" must not claim a motorbike - it is the one word that would wrongly match. */
    @Test
    fun `motorcycling is not cycling`() {
        assertNull(DistanceSport.ofGarmin("motorcycling"))
        assertNull(DistanceSport.ofGarmin("motor_cycling"))
    }

    /** The trace that stops an unknown sport key from failing silently. */
    @Test
    fun `sports with distance that match nothing are reported`() {
        val unclassified = SportDistanceAnalytics.unclassified(
            listOf(
                garmin(monday, "indoor_rowing", 5_000),
                garmin(monday, "indoor_rowing", 4_000, hour = 18),
                garmin(monday, "cross_country_classic_skiing", 12_000, hour = 11),
                // Matched sports and distance-less sessions never appear here.
                garmin(monday, "cycling", 30_000, hour = 14),
                garmin(monday, "strength_training", 0, hour = 20)
            )
        )
        assertEquals(mapOf("indoor_rowing" to 2, "cross_country_classic_skiing" to 1), unclassified)
    }

    @Test
    fun `every garmin spelling of the three sports is recognised`() {
        listOf("running", "trail_running", "treadmill_running", "track_running", "indoor_running")
            .forEach { assertEquals(it, DistanceSport.RUN, DistanceSport.ofGarmin(it)) }
        listOf("cycling", "road_biking", "mountain_biking", "gravel_cycling", "indoor_cycling", "virtual_ride")
            .forEach { assertEquals(it, DistanceSport.BIKE, DistanceSport.ofGarmin(it)) }
        listOf("lap_swimming", "open_water_swimming")
            .forEach { assertEquals(it, DistanceSport.SWIM, DistanceSport.ofGarmin(it)) }
    }

    /** Walking is not running: counting a commute as a run would inflate every running week. */
    @Test
    fun `walking hiking and strength are not distance sports`() {
        listOf("walking", "casual_walking", "speed_walking", "hiking", "strength_training", "yoga")
            .forEach { assertNull(it, DistanceSport.ofGarmin(it)) }
    }

    /** The same session from both sources must count once - Garmin's row, with its sport key, wins. */
    @Test
    fun `a session known to both sources is counted once`() {
        val day = monday
        val sessions = SportDistanceAnalytics.sessions(
            garmin = listOf(garmin(day, "trail_running", 12_000)),
            // Health Connect re-stamps the start by a minute or so - still the same run.
            workouts = listOf(
                Workout(
                    recordId = "hc-1",
                    dateEpochDay = day.toEpochDay(),
                    startTimeMillis = day.toEpochDay() * 86_400_000L + 9 * 3_600_000L + 60_000L,
                    endTimeMillis = day.toEpochDay() * 86_400_000L + 10 * 3_600_000L,
                    exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
                    distanceMeters = 12_010
                )
            )
        )
        assertEquals(1, sessions.size)
        assertEquals(12_000, sessions.first().meters)
    }

    /** A workout Garmin never saw (logged before the login existed) still has to count. */
    @Test
    fun `an unmatched health connect workout is kept`() {
        val sessions = SportDistanceAnalytics.sessions(
            garmin = listOf(garmin(monday, "cycling", 30_000, hour = 9)),
            workouts = listOf(workout(monday, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, 1_500, hour = 19))
        )
        assertEquals(2, sessions.size)
        assertEquals(setOf(DistanceSport.BIKE, DistanceSport.SWIM), sessions.map { it.sport }.toSet())
    }

    @Test
    fun `distances inside one week are summed onto its monday`() {
        val sessions = SportDistanceAnalytics.sessions(
            garmin = listOf(
                garmin(monday, "running", 10_000),
                garmin(monday.plusDays(3), "trail_running", 15_000),
                garmin(monday.plusDays(6), "treadmill_running", 5_000)
            ),
            workouts = emptyList()
        )
        val weeks = SportDistanceAnalytics.weeks(DistanceSport.RUN, sessions, monday, monday.plusDays(6))
        assertEquals(1, weeks.size)
        assertEquals(monday.toEpochDay(), weeks.first().weekStartEpochDay)
        assertEquals(30_000, weeks.first().meters)
        assertEquals(3, weeks.first().sessions)
    }

    /**
     * The rule this whole chart hangs on: a week without a ride is 0 km, drawn, not a gap
     * the line slides through.
     */
    @Test
    fun `a week with no session of the sport is a zero not a missing point`() {
        val sessions = SportDistanceAnalytics.sessions(
            garmin = listOf(
                garmin(monday, "cycling", 40_000),
                // Nothing in the middle week at all.
                garmin(monday.plusWeeks(2), "road_biking", 25_000)
            ),
            workouts = emptyList()
        )
        val weeks = SportDistanceAnalytics.weeks(DistanceSport.BIKE, sessions, monday, monday.plusWeeks(2).plusDays(6))
        assertEquals(3, weeks.size)
        assertEquals(listOf(40_000, 0, 25_000), weeks.map { it.meters })
        assertEquals(3, SportDistanceAnalytics.trend(weeks).size)
    }

    /**
     * Zeros stop at the edges of what was actually synced. Otherwise a window reaching
     * back further than the backfill would report months of confident 0 km for training
     * that simply hasn't been downloaded.
     */
    @Test
    fun `weeks outside the synced stretch are not invented as zeros`() {
        val sessions = SportDistanceAnalytics.sessions(
            garmin = listOf(garmin(monday.plusWeeks(4), "lap_swimming", 2_000)),
            workouts = emptyList()
        )
        val weeks = SportDistanceAnalytics.weeks(DistanceSport.SWIM, sessions, monday, monday.plusWeeks(8).plusDays(6))
        assertEquals(1, weeks.size)
        assertEquals(monday.plusWeeks(4).toEpochDay(), weeks.first().weekStartEpochDay)
    }

    /** The week clipped by the start of the window is dropped - a three-day total is a fake dip. */
    @Test
    fun `a week cut off by the window start is dropped`() {
        val sessions = SportDistanceAnalytics.sessions(
            garmin = listOf(
                garmin(monday.minusDays(2), "running", 8_000),
                garmin(monday.plusDays(1), "running", 9_000)
            ),
            workouts = emptyList()
        )
        // Window opens mid-week, on the Saturday before `monday`.
        val weeks = SportDistanceAnalytics.weeks(DistanceSport.RUN, sessions, monday.minusDays(2), monday.plusDays(6))
        assertEquals(1, weeks.size)
        assertEquals(monday.toEpochDay(), weeks.first().weekStartEpochDay)
        assertEquals(9_000, weeks.first().meters)
    }

    /** The average has to count the empty weeks, or a month off looks like no change at all. */
    @Test
    fun `average per week counts the zero weeks`() {
        val weeks = listOf(
            SportWeek(monday.toEpochDay(), 40_000, 2),
            SportWeek(monday.plusWeeks(1).toEpochDay(), 0, 0),
            SportWeek(monday.plusWeeks(2).toEpochDay(), 20_000, 1)
        )
        assertEquals(20f, SportDistanceAnalytics.averageKmPerWeek(weeks), 0.001f)
    }

    /** A session with no distance (a strength set, an indoor ride with no sensor) adds nothing. */
    @Test
    fun `sessions without distance are ignored`() {
        val sessions = SportDistanceAnalytics.sessions(
            garmin = listOf(garmin(monday, "running", 0), garmin(monday, "strength_training", 0, hour = 18)),
            workouts = emptyList()
        )
        assertEquals(0, sessions.size)
    }
}
