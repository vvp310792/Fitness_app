package com.fitnessapp.summary.data

import com.fitnessapp.summary.util.HealthSourceLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that lets pre-watch years exist at all: on a day Garmin knows nothing about,
 * Health Connect is read without the Garmin filter, and the row says whose data it is.
 *
 * What these guard is the *label*, not the read - whether a number is Garmin's or the
 * phone's is a claim the day card makes out loud, and it must not survive into a day
 * where Garmin supplied the numbers.
 */
class HealthSourceFallbackTest {

    private val day = 20000L

    private fun hc(
        steps: Long = 0,
        sleep: Int = 0,
        sourceApps: String = ""
    ) = DailySummary(
        dateEpochDay = day,
        steps = steps,
        sleepTotalMinutes = sleep,
        sourceApps = sourceApps
    )

    @Test
    fun `a day only Health Connect has keeps the name of the app that wrote it`() {
        val merged = DayView.merge(
            health = hc(steps = 8000, sourceApps = "com.google.android.apps.fitness"),
            garmin = null,
            sleep = null
        )

        assertEquals(8000L, merged?.steps)
        assertEquals("com.google.android.apps.fitness", merged?.sourceApps)
    }

    @Test
    fun `the label is dropped the moment Garmin supplies the day`() {
        // The phone kept writing steps after the watch appeared, so both rows can exist
        // for one date. Garmin wins the numbers - and a caption reading "не от Garmin"
        // under Garmin's own step count would be a true sentence about the wrong data.
        val merged = DayView.merge(
            health = hc(steps = 19350, sourceApps = "com.google.android.apps.fitness"),
            garmin = GarminDailyExtra(dateEpochDay = day, totalSteps = 10787),
            sleep = null
        )

        assertEquals(10787L, merged?.steps)
        assertEquals("", merged?.sourceApps)
    }

    @Test
    fun `a Garmin night alone is enough to drop the label`() {
        val merged = DayView.merge(
            health = hc(steps = 8000, sourceApps = "com.google.android.apps.fitness"),
            garmin = null,
            sleep = GarminSleep(dateEpochDay = day, sleepSeconds = 7 * 3600)
        )

        assertEquals("", merged?.sourceApps)
    }

    @Test
    fun `an ordinary Garmin day carries no label at all`() {
        val merged = DayView.merge(health = hc(steps = 10787), garmin = null, sleep = null)
        assertEquals("", merged?.sourceApps)
    }

    @Test
    fun `package names become readable, unknown ones stay visible`() {
        assertEquals("Google Fit", HealthSourceLabels.label("com.google.android.apps.fitness"))
        assertEquals("Garmin Connect", HealthSourceLabels.label("com.garmin.android.apps.connectmobile"))
        assertEquals(
            "Google Fit, Samsung Health",
            HealthSourceLabels.labels("com.google.android.apps.fitness,com.samsung.android.app.shealth")
        )
        // An unrecognised writer is the case most worth seeing, so it is shown rather
        // than swallowed - the last segment, capitalised, not an empty string.
        assertEquals("Someapp", HealthSourceLabels.label("com.vendor.someapp"))
        assertEquals("", HealthSourceLabels.label(""))
        assertEquals("", HealthSourceLabels.labels(" , "))
    }

    @Test
    fun `duplicate writers are listed once`() {
        assertEquals(
            "Google Fit",
            HealthSourceLabels.labels("com.google.android.apps.fitness,com.google.android.apps.fitness")
        )
    }

    @Test
    fun `an empty day is still nothing, label or not`() {
        assertTrue(hc(sourceApps = "com.google.android.apps.fitness").isEmpty)
        assertEquals(null, DayView.merge(health = hc(sourceApps = "x"), garmin = null, sleep = null))
    }
}
