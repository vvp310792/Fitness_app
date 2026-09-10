package com.fitnessapp.summary.analytics

import com.fitnessapp.summary.data.GarminActivity
import com.fitnessapp.summary.data.Workout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every way this card can be quietly wrong while still looking right.
 *
 * A zone distribution is unusually good at hiding its own bugs: an off-by-one boundary, a
 * sensor artefact in the HRmax estimate or a swim block silently dropped all produce a
 * chart that reads perfectly plausibly. So each of those is pinned here rather than
 * eyeballed, with the boundary numbers written out from this account's own HRmax of 190.
 */
class IntensityAnalyticsTest {

    private val hrMax = 190

    private fun garmin(
        id: Long,
        minutes: Int,
        avgHr: Int,
        maxHr: Int = 0,
        typeKey: String = "running",
        startMillis: Long = id * 86_400_000L
    ) = GarminActivity(
        activityId = id,
        dateEpochDay = id,
        startTimeMillis = startMillis,
        typeKey = typeKey,
        durationSeconds = minutes * 60,
        avgHeartRate = avgHr,
        maxHeartRate = maxHr
    )

    private fun workout(id: Long, minutes: Int, avgHr: Int, maxHr: Int = 0, startMillis: Long = id * 86_400_000L) =
        Workout(
            recordId = "hc-$id",
            dateEpochDay = id,
            startTimeMillis = startMillis,
            endTimeMillis = startMillis + minutes * 60_000L,
            exerciseType = 0,
            durationMinutes = minutes,
            avgHeartRate = avgHr,
            maxHeartRate = maxHr
        )

    /**
     * The bands at HRmax 190, straight off the watch: Z1 < 114, Z2 114-132, Z3 133-151,
     * Z4 152-170, Z5 171+. The boundary belongs to the HIGHER zone - 114 is Z2, not Z1 -
     * which is the single most likely place for an off-by-one nobody would ever notice.
     */
    @Test
    fun `zone boundaries at HRmax 190 match the watch`() {
        val expected = listOf(
            40 to HeartRateZone.Z1,
            113 to HeartRateZone.Z1,
            114 to HeartRateZone.Z2,
            132 to HeartRateZone.Z2,
            133 to HeartRateZone.Z3,
            151 to HeartRateZone.Z3,
            152 to HeartRateZone.Z4,
            170 to HeartRateZone.Z4,
            171 to HeartRateZone.Z5,
            195 to HeartRateZone.Z5
        )
        expected.forEach { (bpm, zone) ->
            assertEquals("$bpm bpm at HRmax $hrMax", zone, HeartRateZone.of(bpm, hrMax))
        }
    }

    /**
     * The printed band and the band used for counting must be the same number. Deriving
     * one from a percentage and the other from a rounded bpm would let a session sit
     * visibly inside a printed range while being counted in the one below.
     */
    @Test
    fun `printed lower bound is the bound that classifies`() {
        listOf(175, 186, 190, 201, 207).forEach { max ->
            HeartRateZone.entries.forEach { zone ->
                val lower = zone.lowerBpm(max)
                assertEquals(
                    "HRmax $max, ${zone.name} starts at $lower",
                    zone,
                    HeartRateZone.of(lower, max)
                )
                if (zone != HeartRateZone.Z1) {
                    assertTrue(
                        "HRmax $max: ${lower - 1} must fall below ${zone.name}",
                        HeartRateZone.of(lower - 1, max).ordinal < zone.ordinal
                    )
                }
            }
        }
    }

    /** Zone 5 is open-ended; every other zone hands its upper bound to the next one's lower. */
    @Test
    fun `the five bands tile the range without gap or overlap`() {
        HeartRateZone.entries.forEach { zone ->
            val upper = zone.upperBpmExclusive(hrMax)
            if (zone == HeartRateZone.Z5) {
                assertNull(upper)
            } else {
                assertEquals(HeartRateZone.entries[zone.ordinal + 1].lowerBpm(hrMax), upper)
            }
        }
    }

    /** All five zones come back, including the empty ones - a zero row is the finding. */
    @Test
    fun `empty zones are reported, not omitted`() {
        val sessions = IntensityAnalytics.sessions(
            listOf(garmin(1, minutes = 60, avgHr = 120)),
            emptyList()
        )
        val breakdown = IntensityAnalytics.breakdown(sessions, hrMax)
        assertEquals(5, breakdown.zones.size)
        assertEquals(60, breakdown.zones.first { it.zone == HeartRateZone.Z2 }.minutes)
        assertEquals(0, breakdown.zones.first { it.zone == HeartRateZone.Z5 }.minutes)
        assertEquals(0, breakdown.zones.first { it.zone == HeartRateZone.Z1 }.sessions)
    }

    /**
     * This account's actual 2026 shape: a lot of easy time, nothing above the aerobic
     * band. The point of the assertion is that "0 % above Z2" survives to the screen.
     */
    @Test
    fun `a period with no hard sessions reports zero above the aerobic band`() {
        val sessions = IntensityAnalytics.sessions(
            listOf(
                garmin(1, minutes = 30, avgHr = 100),
                garmin(2, minutes = 60, avgHr = 118),
                garmin(3, minutes = 45, avgHr = 125),
                garmin(4, minutes = 65, avgHr = 112)
            ),
            emptyList()
        )
        val breakdown = IntensityAnalytics.breakdown(sessions, hrMax)
        assertEquals(200, breakdown.totalMinutes)
        assertEquals(0, breakdown.zones.first { it.zone == HeartRateZone.Z3 }.minutes)
        assertEquals(0, breakdown.zones.first { it.zone == HeartRateZone.Z4 }.minutes)
        assertEquals(0, breakdown.zones.first { it.zone == HeartRateZone.Z5 }.minutes)
        // 30 + 65 easy against 60 + 45 in Z2.
        assertEquals(48, breakdown.sharePercent(breakdown.zones.first { it.zone == HeartRateZone.Z1 }))
        assertEquals(53, breakdown.sharePercent(breakdown.zones.first { it.zone == HeartRateZone.Z2 }))
    }

    /**
     * A session with no heart rate must never land in zone 1. Swimming usually reads
     * nothing on the wrist, and counting an hour in the pool as an hour of recovery would
     * be a fabricated fact, not a conservative one.
     */
    @Test
    fun `sessions without a heart rate are counted apart, never as zone 1`() {
        val sessions = IntensityAnalytics.sessions(
            listOf(
                garmin(1, minutes = 55, avgHr = 0, typeKey = "lap_swimming"),
                garmin(2, minutes = 45, avgHr = 0, typeKey = "lap_swimming"),
                garmin(3, minutes = 30, avgHr = 140, typeKey = "running")
            ),
            emptyList()
        )
        val breakdown = IntensityAnalytics.breakdown(sessions, hrMax)
        assertEquals(0, breakdown.zones.first { it.zone == HeartRateZone.Z1 }.minutes)
        assertEquals(30, breakdown.totalMinutes)
        assertEquals(1, breakdown.totalSessions)
        assertEquals(100, breakdown.minutesWithoutHeartRate)
        assertEquals(2, breakdown.sessionsWithoutHeartRate)
        assertEquals(mapOf("lap_swimming" to 2), breakdown.sportsWithoutHeartRate)
    }

    /**
     * An implausible reading is missing data, not a zone-5 session. This account's export
     * carries 244 and 214 bpm peaks in years whose 95th percentile is 177.
     */
    @Test
    fun `sensor artefacts are read as no data`() {
        val sessions = IntensityAnalytics.sessions(
            listOf(garmin(1, minutes = 40, avgHr = 244, maxHr = 250)),
            emptyList()
        )
        assertEquals(0, sessions.single().avgHeartRate)
        assertEquals(0, sessions.single().maxHeartRate)
        val breakdown = IntensityAnalytics.breakdown(sessions, hrMax)
        assertEquals(0, breakdown.zones.first { it.zone == HeartRateZone.Z5 }.minutes)
        assertEquals(40, breakdown.minutesWithoutHeartRate)
    }

    /**
     * The same session off the same watch, seen by both sources, is one session. Garmin's
     * row wins; the Health Connect copy inside the ±3-minute window is dropped.
     */
    @Test
    fun `a session known to both sources is counted once`() {
        val start = 5 * 86_400_000L
        val sessions = IntensityAnalytics.sessions(
            listOf(garmin(5, minutes = 60, avgHr = 140, startMillis = start)),
            listOf(workout(5, minutes = 60, avgHr = 138, startMillis = start + 60_000L))
        )
        assertEquals(1, sessions.size)
        assertEquals(140, sessions.single().avgHeartRate)
    }

    /** Outside the window they are two different sessions and both must count. */
    @Test
    fun `a Health Connect session Garmin never saw still counts`() {
        val start = 5 * 86_400_000L
        val sessions = IntensityAnalytics.sessions(
            listOf(garmin(5, minutes = 60, avgHr = 140, startMillis = start)),
            listOf(workout(5, minutes = 30, avgHr = 120, startMillis = start + 4 * 60_000L))
        )
        assertEquals(2, sessions.size)
        val breakdown = IntensityAnalytics.breakdown(sessions, hrMax)
        assertEquals(90, breakdown.totalMinutes)
    }

    /** Zero-length rows carry no time and must not inflate the session count. */
    @Test
    fun `sessions with no duration are not sessions`() {
        val sessions = IntensityAnalytics.sessions(
            listOf(garmin(1, minutes = 0, avgHr = 150)),
            listOf(workout(2, minutes = 0, avgHr = 150, startMillis = 99 * 86_400_000L))
        )
        assertTrue(sessions.isEmpty())
        assertTrue(IntensityAnalytics.breakdown(sessions, hrMax).isEmpty)
    }

    /**
     * The suggestion is the 95th percentile by nearest rank - a number that actually
     * repeated - not the single highest reading the sensor ever produced.
     */
    @Test
    fun `HRmax suggestion is the 95th percentile, not the peak`() {
        // 19 sessions at 170-178 plus one artefact-free but exceptional 200.
        val maxima = (0 until 19).map { 170 + it % 9 } + listOf(200)
        val sessions = maxima.mapIndexed { i, max ->
            garmin(i.toLong(), minutes = 40, avgHr = 140, maxHr = max)
        }
        val suggestion = IntensityAnalytics.suggestHrMax(IntensityAnalytics.sessions(sessions, emptyList()))
        // ceil(20 * 0.95) = 19 -> the 19th of 20 sorted values, i.e. one below the peak.
        assertEquals(178, suggestion)
    }

    /** An artefact must not be allowed to drag the whole zone ladder up with it. */
    @Test
    fun `an artefact peak cannot become the suggested HRmax`() {
        val sessions = (0 until 20).map { garmin(it.toLong(), minutes = 40, avgHr = 140, maxHr = 175) } +
            listOf(garmin(99, minutes = 40, avgHr = 140, maxHr = 244))
        val suggestion = IntensityAnalytics.suggestHrMax(IntensityAnalytics.sessions(sessions, emptyList()))
        assertEquals(175, suggestion)
    }

    /** A percentile over a handful of sessions is not an estimate - say nothing instead. */
    @Test
    fun `too few sessions produce no suggestion`() {
        val sessions = (0 until IntensityAnalytics.MIN_SESSIONS_FOR_SUGGESTION - 1).map {
            garmin(it.toLong(), minutes = 40, avgHr = 140, maxHr = 180)
        }
        assertNull(IntensityAnalytics.suggestHrMax(IntensityAnalytics.sessions(sessions, emptyList())))
    }

    /** Sessions with no recorded maximum don't count towards the minimum sample either. */
    @Test
    fun `sessions without a maximum do not fill the sample`() {
        val sessions = (0 until 30).map { garmin(it.toLong(), minutes = 40, avgHr = 140, maxHr = 0) }
        assertNull(IntensityAnalytics.suggestHrMax(IntensityAnalytics.sessions(sessions, emptyList())))
    }

    /** Nothing at all is an empty breakdown, not a division by zero. */
    @Test
    fun `an empty period is empty rather than broken`() {
        val breakdown = IntensityAnalytics.breakdown(emptyList(), hrMax)
        assertTrue(breakdown.isEmpty)
        assertEquals(0, breakdown.sharePercent(breakdown.zones.first()))
    }
}
