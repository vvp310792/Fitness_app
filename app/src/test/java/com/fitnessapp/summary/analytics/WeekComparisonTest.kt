package com.fitnessapp.summary.analytics

import com.fitnessapp.summary.data.DailySummary
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class WeekComparisonTest {
    private val monday=LocalDate.of(2026,11,23)
    private fun day(offset:Long,steps:Long=1000,distance:Int=800,sleep:Int=480)=
        DailySummary(dateEpochDay=monday.plusDays(offset).toEpochDay(),steps=steps,distanceMeters=distance,sleepTotalMinutes=sleep)
    private fun bothWeeks()=(-7L..6L).map { day(it) }

    @Test fun excludesTodayAndRemainingDaysFromBothWeeks() {
        val rows=bothWeeks().map { if(it.dateEpochDay>=monday.plusDays(3).toEpochDay() ||
            it.dateEpochDay in monday.minusDays(4).toEpochDay()..monday.minusDays(1).toEpochDay()) it.copy(steps=99999) else it }
        val cmp=WeekComparison.compare(rows,monday,monday.plusDays(3))
        assertEquals(3,cmp.completedDays)
        assertEquals(0,cmp.metrics.first().percent)
        assertTrue(cmp.metrics.all { it.currentDays==3 && it.previousDays==3 })
    }
    @Test fun metricCoverageIsIndependent() {
        val rows=bothWeeks().map { if(it.dateEpochDay==monday.toEpochDay()) it.copy(steps=0) else it }
        val cmp=WeekComparison.compare(rows,monday,monday.plusDays(3))
        assertNull(cmp.metrics.first().percent)
        assertEquals(2,cmp.metrics.first().currentDays)
        assertEquals(0,cmp.metrics[1].percent)
        assertEquals(0,cmp.metrics[2].percent)
    }
    @Test fun noComparisonOnMonday() {
        val cmp=WeekComparison.compare(bothWeeks(),monday,monday)
        assertEquals(0,cmp.completedDays)
        assertTrue(cmp.metrics.all { it.percent==null })
    }
    @Test fun historicalWeekUsesSevenDays() {
        val cmp=WeekComparison.compare(bothWeeks(),monday,monday.plusWeeks(1))
        assertEquals(7,cmp.completedDays)
        assertTrue(cmp.metrics.all { it.percent==0 })
    }
    @Test fun duplicateDayCannotSilentlyInflateTotal() {
        val cmp=WeekComparison.compare(bothWeeks()+day(0),monday,monday.plusDays(3))
        assertNull(cmp.metrics.first().percent)
        assertEquals(2,cmp.metrics.first().currentDays)
    }
}
