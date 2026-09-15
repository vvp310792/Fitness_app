package com.fitnessapp.summary

import com.fitnessapp.summary.data.DailySummary
import com.fitnessapp.summary.data.DayView
import com.fitnessapp.summary.data.FitDay
import com.fitnessapp.summary.data.GarminDailyExtra
import com.fitnessapp.summary.analytics.LifestyleAnalytics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Google Fit import is the weakest source in the app, and these tests are what keeps it
 * that way. The precedence it sits under - Garmin first, Health Connect second - is the same
 * rule that steps 19350-vs-10787 taught, one source further down.
 */
class FitDayViewTest {

    private val day = 17_000L

    @Test
    fun `a day only the archive knows about still shows up`() {
        val merged = DayView.merge(null, null, null, FitDay(dateEpochDay = day, steps = 8200))
        assertNotNull(merged)
        assertEquals(8200L, merged!!.steps)
        assertEquals(DayView.GOOGLE_FIT_PACKAGE, merged.sourceApps)
    }

    @Test
    fun `Garmin wins over the archive on every field it has`() {
        val merged = DayView.merge(
            health = null,
            garmin = GarminDailyExtra(dateEpochDay = day, totalSteps = 10787, restingHeartRate = 57),
            sleep = null,
            fit = FitDay(dateEpochDay = day, steps = 19350, restingHeartRate = 68, caloriesKcal = 2400)
        )!!
        assertEquals(10787L, merged.steps)
        assertEquals(57, merged.restingHeartRate)
        // A field Garmin was silent about is still filled - the sources fail independently.
        assertEquals(2400, merged.totalCaloriesKcal)
        // ...but the day is no longer "not from Garmin", so the provenance label goes.
        assertEquals("", merged.sourceApps)
    }

    @Test
    fun `Health Connect wins over the archive too`() {
        val merged = DayView.merge(
            health = DailySummary(dateEpochDay = day, steps = 9000),
            garmin = null,
            sleep = null,
            fit = FitDay(dateEpochDay = day, steps = 19350, caloriesKcal = 2400)
        )!!
        assertEquals(9000L, merged.steps)
        assertEquals(2400, merged.totalCaloriesKcal)
    }

    /** All-or-nothing, one source further down: parts must not be measured against another whole. */
    @Test
    fun `a Google Fit night is taken whole or not at all`() {
        val fit = FitDay(
            dateEpochDay = day,
            sleepTotalMinutes = 430,
            sleepDeepMinutes = 70,
            sleepLightMinutes = 300,
            sleepRemMinutes = 60
        )
        val onlyFit = DayView.merge(null, null, null, fit)!!
        assertEquals(430, onlyFit.sleepTotalMinutes)
        assertEquals(70, onlyFit.sleepDeepMinutes)

        val healthNight = DailySummary(dateEpochDay = day, sleepTotalMinutes = 400, sleepDeepMinutes = 55)
        val merged = DayView.merge(healthNight, null, null, fit)!!
        assertEquals(400, merged.sleepTotalMinutes)
        assertEquals(55, merged.sleepDeepMinutes)
    }

    @Test
    fun `an empty archive row is not a day`() {
        assertNull(DayView.merge(null, null, null, FitDay(dateEpochDay = day)))
    }

    /** The charts fill their day maps weakest-first, so the imported rows must come first. */
    @Test
    fun `withFitFallback puts the import beneath Health Connect`() {
        val real = DailySummary(dateEpochDay = day, steps = 9000)
        val list = LifestyleAnalytics.withFitFallback(
            healthDays = listOf(real),
            fit = listOf(
                FitDay(dateEpochDay = day, steps = 19350),
                FitDay(dateEpochDay = day - 1000, steps = 5000)
            )
        )
        assertEquals(3, list.size)
        assertTrue(list.last() === real)
        assertEquals(LifestyleAnalytics.stepsTrend(emptyList(), list).first { it.epochDay == day }.value, 9000f, 0.1f)
        assertEquals(LifestyleAnalytics.stepsTrend(emptyList(), list).first { it.epochDay == day - 1000 }.value, 5000f, 0.1f)
    }

    /**
     * Google Fit publishes one expenditure figure. Splitting it into active and resting here
     * would be a number this app invented, so the dashed "active" line simply has no points
     * over those years.
     */
    @Test
    fun `the import never produces an active-calorie figure`() {
        val list = LifestyleAnalytics.withFitFallback(
            healthDays = emptyList(),
            fit = listOf(FitDay(dateEpochDay = day, caloriesKcal = 2400))
        )
        assertEquals(1, LifestyleAnalytics.caloriesTrend(emptyList(), list, active = false).size)
        assertTrue(LifestyleAnalytics.caloriesTrend(emptyList(), list, active = true).isEmpty())
    }
}
