package com.fitnessapp.summary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule under test: Garmin is the first source, Health Connect only fills its gaps.
 * Every case here is one that actually happened on the real account - a Garmin day with
 * no sleep row, a night Health Connect had and Garmin didn't, steps inflated in Health
 * Connect by a second writer.
 */
class DayViewTest {

    private val day = 20_000L

    private fun hc(
        steps: Long = 0, total: Int = 0, active: Int = 0, distance: Int = 0,
        rhr: Int = 0, sleep: Int = 0, deep: Int = 0, rem: Int = 0
    ) = DailySummary(
        dateEpochDay = day, steps = steps, totalCaloriesKcal = total,
        activeCaloriesKcal = active, distanceMeters = distance, restingHeartRate = rhr,
        sleepTotalMinutes = sleep, sleepDeepMinutes = deep, sleepRemMinutes = rem
    )

    private fun garmin(steps: Long = 0, total: Int = 0, rhr: Int = 0) = GarminDailyExtra(
        dateEpochDay = day, totalSteps = steps, totalKilocalories = total, restingHeartRate = rhr
    )

    private fun night(minutes: Int, deep: Int = 0, rem: Int = 0) = GarminSleep(
        dateEpochDay = day, sleepSeconds = minutes * 60,
        deepSeconds = deep * 60, remSeconds = rem * 60
    )

    @Test
    fun `garmin steps win over the health connect copy`() {
        // The real case: Google Fit wrote phone-pedometer steps on top of the watch's,
        // and Health Connect showed 19350 against Garmin Connect's own 10787.
        val merged = DayView.merge(hc(steps = 19_350), garmin(steps = 10_787), null)!!
        assertEquals(10_787L, merged.steps)
    }

    @Test
    fun `health connect fills fields garmin does not have`() {
        val merged = DayView.merge(hc(steps = 5_000, distance = 4_200), garmin(steps = 6_000), null)!!
        assertEquals(6_000L, merged.steps)
        assertEquals(4_200, merged.distanceMeters)
    }

    @Test
    fun `a zero from garmin is no data, not a measurement`() {
        val merged = DayView.merge(hc(rhr = 54, total = 2_900), garmin(steps = 6_000), null)!!
        assertEquals(54, merged.restingHeartRate)
        assertEquals(2_900, merged.totalCaloriesKcal)
    }

    @Test
    fun `garmin night replaces the health connect night whole, stages included`() {
        val merged = DayView.merge(
            hc(sleep = 300, deep = 60, rem = 40),
            garmin(steps = 6_000),
            night(minutes = 438, deep = 71, rem = 96)
        )!!
        assertEquals(438, merged.sleepTotalMinutes)
        assertEquals(71, merged.sleepDeepMinutes)
        assertEquals(96, merged.sleepRemMinutes)
    }

    @Test
    fun `stages are never mixed across sources`() {
        // Garmin returned the night but no stage breakdown. Borrowing Health Connect's
        // deep and REM would quote parts against a whole they don't belong to.
        val merged = DayView.merge(hc(sleep = 300, deep = 60, rem = 40), null, night(minutes = 438))!!
        assertEquals(438, merged.sleepTotalMinutes)
        assertEquals(0, merged.sleepDeepMinutes)
        assertEquals(0, merged.sleepRemMinutes)
    }

    @Test
    fun `a night only health connect has is kept`() {
        // Garmin's sleep endpoint answers 200 with an empty body for this account, so the
        // Garmin row exists for the day but carries no night.
        val merged = DayView.merge(hc(sleep = 421, deep = 55), garmin(steps = 6_000), null)!!
        assertEquals(421, merged.sleepTotalMinutes)
        assertEquals(55, merged.sleepDeepMinutes)
    }

    @Test
    fun `a day only garmin has still renders`() {
        val merged = DayView.merge(null, garmin(steps = 12_000, total = 3_100), night(minutes = 400))!!
        assertEquals(12_000L, merged.steps)
        assertEquals(3_100, merged.totalCaloriesKcal)
        assertEquals(400, merged.sleepTotalMinutes)
    }

    @Test
    fun `nothing anywhere is null, not an empty row`() {
        assertNull(DayView.merge(null, null, null))
        assertNull(DayView.merge(DailySummary(dateEpochDay = day), GarminDailyExtra(dateEpochDay = day), null))
    }

    @Test
    fun `range keeps days only one source knows about`() {
        val merged = DayView.mergeRange(
            health = listOf(hc(steps = 100).copy(dateEpochDay = day)),
            garmin = listOf(GarminDailyExtra(dateEpochDay = day + 1, totalSteps = 9_000)),
            sleep = listOf(GarminSleep(dateEpochDay = day + 2, sleepSeconds = 400 * 60))
        )
        assertEquals(listOf(day, day + 1, day + 2), merged.map { it.dateEpochDay })
        assertEquals(9_000L, merged[1].steps)
        assertEquals(400, merged[2].sleepTotalMinutes)
    }
}
