package com.fitnessapp.summary.analytics

import com.fitnessapp.summary.data.DailySummary
import com.fitnessapp.summary.data.GarminDailyExtra
import com.fitnessapp.summary.data.GarminSleep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resting heart rate is only a resting heart rate on a night the watch actually measured.
 *
 * On every other day Garmin still publishes a number, derived from the quietest waking
 * stretch, and it runs several beats high. On this account's own export the split is
 * median 60 against 68 - so a year in which the watch was worn overnight less often
 * produces a confident "resting heart rate is climbing" trend out of nothing but a change
 * in habit. That is what these tests pin.
 */
class RestingHeartRateNightTest {

    private fun garmin(day: Long, rhr: Int) = GarminDailyExtra(dateEpochDay = day, restingHeartRate = rhr)
    private fun night(day: Long, seconds: Int = 7 * 3600) = GarminSleep(dateEpochDay = day, sleepSeconds = seconds)
    private fun hcDay(day: Long, rhr: Int = 0, sleepMinutes: Int = 0) =
        DailySummary(dateEpochDay = day, restingHeartRate = rhr, sleepTotalMinutes = sleepMinutes)

    /** A night from either source counts - Health Connect knows nights Garmin sometimes doesn't. */
    @Test
    fun `a night from either source marks the day`() {
        val nights = LifestyleAnalytics.nightDays(
            sleeps = listOf(night(10), night(11, seconds = 0)),
            healthDays = listOf(hcDay(12, sleepMinutes = 430), hcDay(13))
        )
        assertEquals(setOf(10L, 12L), nights)
    }

    /** The trend keeps only the measured nights; the rest become the dashed series. */
    @Test
    fun `the trend splits into measured nights and the rest`() {
        val summaries = listOf(garmin(1, 58), garmin(2, 68), garmin(3, 60), garmin(4, 70))
        val sleeps = listOf(night(1), night(3))
        val nights = LifestyleAnalytics.nightDays(sleeps, emptyList())

        val measured = LifestyleAnalytics.restingHeartRateTrend(summaries, emptyList(), nights)
        val rest = LifestyleAnalytics.restingHeartRateNoNightTrend(summaries, emptyList(), nights)

        assertEquals(listOf(1L, 3L), measured.map { it.epochDay })
        assertEquals(listOf(58f, 60f), measured.map { it.value })
        assertEquals(listOf(2L, 4L), rest.map { it.epochDay })
        assertEquals(listOf(68f, 70f), rest.map { it.value })
    }

    /** Null boundaries mean "give me everything" - only for a window holding no night at all. */
    @Test
    fun `passing no night set returns every day`() {
        val summaries = listOf(garmin(1, 58), garmin(2, 68))
        assertEquals(2, LifestyleAnalytics.restingHeartRateTrend(summaries, emptyList(), null).size)
    }

    /**
     * The offset is measured from the user's own data, not assumed. Reproduces the shape of
     * this account's export: nights around 60, non-nights around 68.
     */
    @Test
    fun `the gap between the two groups is measured`() {
        val summaries = (1L..10L).map { garmin(it, 60) } + (11L..20L).map { garmin(it, 68) }
        val nights = LifestyleAnalytics.nightDays((1L..10L).map { night(it) }, emptyList())
        assertEquals(8, LifestyleAnalytics.restingHeartRateNightGap(summaries, emptyList(), nights))
    }

    /** Too few days in either group is not a measurement, and must not be stated as one. */
    @Test
    fun `too small a group produces no gap`() {
        val summaries = (1L..10L).map { garmin(it, 68) } + listOf(garmin(11, 60))
        val nights = LifestyleAnalytics.nightDays(listOf(night(11)), emptyList())
        assertNull(LifestyleAnalytics.restingHeartRateNightGap(summaries, emptyList(), nights))
        assertNull(
            LifestyleAnalytics.restingHeartRateNightGap(summaries, emptyList(), emptySet())
        )
    }

    /**
     * The failure this whole change exists to stop: a stretch where the watch stopped being
     * worn overnight must NOT read as a rising resting heart rate. Unfiltered, these numbers
     * climb 60 -> 68; filtered, they are flat, because the climb is the habit, not the heart.
     */
    @Test
    fun `wearing the watch less at night does not become a rising trend`() {
        val early = (1L..14L).map { garmin(it, 60) }
        val late = (15L..28L).map { garmin(it, if (it % 7 == 0L) 60 else 68) }
        val sleeps = (1L..14L).map { night(it) } + (15L..28L).filter { it % 7 == 0L }.map { night(it) }
        val nights = LifestyleAnalytics.nightDays(sleeps, emptyList())

        val unfiltered = LifestyleAnalytics.restingHeartRateTrend(early + late, emptyList(), null)
        val filtered = LifestyleAnalytics.restingHeartRateTrend(early + late, emptyList(), nights)

        // Compared as halves, not endpoints: day 28 happens to be one of the nights, so the
        // last raw point is a low one even though the stretch as a whole has drifted up.
        val rawEarly = unfiltered.filter { it.epochDay <= 14 }.map { it.value }.average()
        val rawLate = unfiltered.filter { it.epochDay > 14 }.map { it.value }.average()
        assertTrue("сырая серия растёт: $rawEarly -> $rawLate", rawLate > rawEarly + 5)
        assertTrue("отфильтрованная — плоская", filtered.all { it.value == 60f })
        assertEquals(16, filtered.size)
    }

    /** Health Connect's own resting rate is still used - but under the same night rule. */
    @Test
    fun `Health Connect days obey the same rule`() {
        val hc = listOf(hcDay(1, rhr = 57, sleepMinutes = 430), hcDay(2, rhr = 66))
        val nights = LifestyleAnalytics.nightDays(emptyList(), hc)
        val measured = LifestyleAnalytics.restingHeartRateTrend(emptyList(), hc, nights)
        assertEquals(listOf(1L), measured.map { it.epochDay })
    }

    /** Garmin wins over Health Connect on a day both know, as everywhere else in the app. */
    @Test
    fun `Garmin overrides Health Connect on the same day`() {
        val nights = setOf(1L)
        val points = LifestyleAnalytics.restingHeartRateTrend(
            listOf(garmin(1, 47)),
            listOf(hcDay(1, rhr = 57, sleepMinutes = 430)),
            nights
        )
        assertEquals(listOf(47f), points.map { it.value })
    }
}
