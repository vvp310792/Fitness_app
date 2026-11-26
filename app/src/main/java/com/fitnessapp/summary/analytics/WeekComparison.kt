package com.fitnessapp.summary.analytics

import com.fitnessapp.summary.data.DailySummary
import com.fitnessapp.summary.util.percentChange
import java.time.LocalDate

enum class ComparedWeekMetric { STEPS, DISTANCE, SLEEP }
data class WeekMetricChange(val metric: ComparedWeekMetric, val percent: Int?, val currentDays: Int, val previousDays: Int)
data class WeekComparisonResult(val completedDays: Int, val metrics: List<WeekMetricChange>)

/** Legacy Room stores zero as missing. Do not infer an observed zero or an absent
 * training session from it. Use full coverage for each compared metric separately. */
object WeekComparison {
    fun compare(days: List<DailySummary>, weekStart: LocalDate, today: LocalDate): WeekComparisonResult {
        val count=CanonicalFitness.completedWeekDays(weekStart,today)
        val byDate=days.groupBy { it.dateEpochDay }
        fun values(start: LocalDate, pick: (DailySummary)->Long): List<Long> =
            (0 until count).mapNotNull { i ->
                byDate[start.plusDays(i.toLong()).toEpochDay()]?.singleOrNull()?.let(pick)?.takeIf { it>0 }
            }
        fun metric(kind: ComparedWeekMetric,pick: (DailySummary)->Long): WeekMetricChange {
            val now=values(weekStart,pick)
            val previous=values(weekStart.minusWeeks(1),pick)
            val change=if(count>0 && now.size==count && previous.size==count)
                percentChange(now.sum(),previous.sum()) else null
            return WeekMetricChange(kind,change,now.size,previous.size)
        }
        return WeekComparisonResult(count,listOf(
            metric(ComparedWeekMetric.STEPS) { it.steps },
            metric(ComparedWeekMetric.DISTANCE) { it.distanceMeters.toLong() },
            metric(ComparedWeekMetric.SLEEP) { it.sleepTotalMinutes.toLong() }
        ))
    }
}
