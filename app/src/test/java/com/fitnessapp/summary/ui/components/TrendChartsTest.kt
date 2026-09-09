package com.fitnessapp.summary.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Y-axis tick values. Pure arithmetic, and the kind that is easy to get subtly wrong -
 * a tick one step past the top draws a gridline outside the plot, a step of 0 loops
 * forever - while a 96dp chart shows none of it.
 */
class TrendChartsTest {

    @Test
    fun `ticks are round numbers, not raw fractions of the range`() {
        niceTicks(48f, 66f).forEach { assertEquals(0f, it.mod(5f), 0.001f) }
        niceTicks(2000f, 21000f).forEach { assertEquals(0f, it.mod(1000f), 0.001f) }
    }

    @Test
    fun `every tick stays inside the range it labels`() {
        listOf(
            0f to 1f, 48f to 66f, 2000f to 21000f, -5f to 5f, 0.1f to 0.9f, 60f to 620f
        ).forEach { (lo, hi) ->
            niceTicks(lo, hi).forEach { tick ->
                assertTrue("$tick outside $lo..$hi", tick >= lo - 0.001f && tick <= hi + 0.001f)
            }
        }
    }

    /** A chart with no labelled line at all is what this function exists to prevent. */
    @Test
    fun `there is always at least one tick`() {
        listOf(0f to 0f, 5f to 5f, 1f to 1.0001f, -3f to -3f).forEach { (lo, hi) ->
            assertTrue("no ticks for $lo..$hi", niceTicks(lo, hi).isNotEmpty())
        }
    }

    /** Four gridlines is the most a 96dp chart can carry before they become texture. */
    @Test
    fun `never more ticks than asked for`() {
        listOf(0f to 1f, 0f to 100f, 0f to 999999f, 0.001f to 0.002f).forEach { (lo, hi) ->
            assertTrue(niceTicks(lo, hi).size <= 4)
            assertTrue(niceTicks(lo, hi, maxTicks = 3).size <= 3)
        }
    }

    /** A degenerate or non-finite range must return, not spin. */
    @Test
    fun `an inverted or infinite range degrades instead of looping`() {
        assertEquals(listOf(10f), niceTicks(10f, 5f))
        assertEquals(listOf(0f), niceTicks(0f, Float.POSITIVE_INFINITY))
        assertEquals(listOf(0f), niceTicks(0f, Float.NaN))
    }
}
