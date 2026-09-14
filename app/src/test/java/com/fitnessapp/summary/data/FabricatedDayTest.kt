package com.fitnessapp.summary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether a stored day is data or Health Connect's own arithmetic.
 *
 * Worth its own tests because the rule exists in two places at once - [DailySummary.isEmpty]
 * in Kotlin and the same condition as SQL in [DailySummaryDao], because Room cannot call a
 * Kotlin property. Two copies of one physical rule drift apart at the first edit, and this
 * one deletes rows, so they are pinned against each other here.
 */
class FabricatedDayTest {

    private fun day(
        steps: Long = 0,
        active: Int = 0,
        total: Int = 0,
        distance: Int = 0,
        resting: Int = 0,
        avg: Int = 0,
        sleep: Int = 0,
        workouts: Int = 0
    ) = DailySummary(
        dateEpochDay = 20000,
        steps = steps,
        activeCaloriesKcal = active,
        totalCaloriesKcal = total,
        distanceMeters = distance,
        restingHeartRate = resting,
        avgHeartRate = avg,
        sleepTotalMinutes = sleep,
        workoutCount = workouts
    )

    @Test
    fun `a day holding only derived total calories is not data`() {
        // The real shape: 1564 kcal and nothing else, repeated for 3029 consecutive days
        // because Health Connect computes it from the profile for any date it is asked.
        assertTrue(day(total = 1564).isEmpty)
    }

    @Test
    fun `any single real measurement makes the day real`() {
        assertFalse(day(steps = 1).isEmpty)
        assertFalse(day(active = 1).isEmpty)
        assertFalse(day(distance = 1).isEmpty)
        assertFalse(day(resting = 1).isEmpty)
        assertFalse(day(avg = 1).isEmpty)
        assertFalse(day(sleep = 1).isEmpty)
        assertFalse(day(workouts = 1).isEmpty)
    }

    @Test
    fun `a real day keeps its total calories`() {
        // Total calories are not distrusted in general - only *alone*. A day with steps
        // and 3354 kcal is an ordinary day and must survive the purge untouched.
        val real = day(steps = 23898, total = 3354, resting = 59)
        assertFalse(real.isEmpty)
        assertEquals(3354, real.totalCaloriesKcal)
    }

    @Test
    fun `an entirely blank day is empty too`() {
        assertTrue(day().isEmpty)
    }

    @Test
    fun `the SQL used to delete matches the Kotlin rule field for field`() {
        // Every column the property tests must appear in the DELETE, and the DELETE must
        // not mention a column the property ignores - totalCaloriesKcal above all, since
        // naming it there would spare exactly the rows this is meant to remove.
        val sql = DailySummaryDao.FABRICATED_WHERE_DELETE
        for (column in listOf(
            "steps", "activeCaloriesKcal", "distanceMeters",
            "restingHeartRate", "avgHeartRate", "sleepTotalMinutes", "workoutCount"
        )) {
            assertTrue("В SQL нет условия по $column", sql.contains("$column = 0"))
        }
        assertFalse("SQL не должен смотреть на totalCaloriesKcal", sql.contains("totalCaloriesKcal"))
        assertTrue(sql.startsWith("DELETE FROM daily_summaries WHERE "))
        assertTrue(DailySummaryDao.FABRICATED_WHERE_SELECT.startsWith("SELECT dateEpochDay FROM daily_summaries WHERE "))
        // Same condition in both, so the ids collected are exactly the rows deleted.
        assertEquals(
            DailySummaryDao.FABRICATED_WHERE_DELETE.substringAfter("WHERE "),
            DailySummaryDao.FABRICATED_WHERE_SELECT.substringAfter("WHERE ")
        )
    }
}
