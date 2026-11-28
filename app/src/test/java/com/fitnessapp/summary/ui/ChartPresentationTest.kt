package com.fitnessapp.summary.ui

import com.fitnessapp.summary.ui.components.TrendSmoothing
import com.fitnessapp.summary.ui.components.TrendEdge
import com.fitnessapp.summary.ui.components.rollingMedian
import com.fitnessapp.summary.ui.components.trendSmoothing
import com.fitnessapp.summary.ui.components.trendEdge
import com.fitnessapp.summary.ui.components.smoothingGapDays
import com.fitnessapp.summary.ui.components.trendDotStyle
import com.fitnessapp.summary.analytics.TrendPoint
import com.fitnessapp.summary.analytics.LifestyleAnalytics
import com.fitnessapp.summary.ui.components.trendHasGap
import com.fitnessapp.summary.ui.components.BarState
import com.fitnessapp.summary.ui.components.barState
import com.fitnessapp.summary.ui.components.BarDatum
import org.junit.Assert.*
import org.junit.Test

class ChartPresentationTest {
    @Test fun medianResistsOutlierAndMeanRemainsAvailable() {
        val input=listOf(2f,3f,100f,4f,5f).mapIndexed{i,v->TrendPoint(100L+i,v)}
        val median=trendSmoothing(input,8,TrendSmoothing.MEDIAN)
        assertEquals(4f,median.first{it.epochDay==102L}.value,0.0001f)
        val mean=trendSmoothing(input,8,TrendSmoothing.MEAN)
        assertEquals(LifestyleAnalytics.smoothTrend(input,8),mean)
        assertEquals(22.8f,mean.first{it.epochDay==102L}.value,0.0001f)
        assertEquals(listOf(2f,3f,100f,4f,5f),input.map{it.value})
    }
    @Test fun medianUsesCalendarDaysAndDoesNotInventObservations() {
        val points=listOf(TrendPoint(1,1f),TrendPoint(2,3f),TrendPoint(3,5f),TrendPoint(4,7f),TrendPoint(100,99f))
        val trend=rollingMedian(points,10)
        assertEquals(listOf(1L,2L,3L,4L),trend.map{it.epochDay})
        assertTrue(trend.all{it.value==4f})
        assertTrue(rollingMedian(listOf(TrendPoint(1,4f),TrendPoint(100,5f),TrendPoint(200,6f)),10).isEmpty())
    }
    @Test fun denseTwelveYearHistoryKeepsTrendConnectedWithoutFillingGaps() {
        val points=(0L..4380L).filter{it !in 1000L..1450L && it%4L!=0L}
            .map{TrendPoint(it,5f+(it%20L).toFloat())}
        val trend=rollingMedian(points,180)
        assertTrue(trend.size>2500)
        assertTrue(trend.all{p->points.any{it.epochDay==p.epochDay}})
        assertEquals(TrendEdge.BREAK,trendEdge(1,3,1,false))
        assertEquals(TrendEdge.SOLID,trendEdge(1,3,smoothingGapDays(1,180),true))
        assertEquals(TrendEdge.BRIDGE,trendEdge(999,1451,smoothingGapDays(1,180),true))
        assertFalse(trend.zipWithNext().any{(a,b)->trendEdge(a.epochDay,b.epochDay,180,true)==TrendEdge.BREAK})
        assertTrue(trendDotStyle(points.size,true).radiusDp<1f)
        assertTrue(trendDotStyle(points.size,true).opacity<0.2f)
    }
    @Test fun noFalseLineAcrossMissingDays() {
        assertFalse(trendHasGap(100,101,1))
        assertTrue(trendHasGap(100,102,1))
        assertFalse(trendHasGap(100,107,7))
        assertTrue(trendHasGap(100,108,7))
        assertTrue(trendHasGap(100,130,21))
    }
    @Test fun measurementMissingAndFutureHaveSeparateStates() {
        assertEquals(BarState.MEASURED,barState(false,true))
        assertEquals(BarState.MISSING,barState(false,false))
        assertEquals(BarState.FUTURE,barState(true,false))
        assertEquals(BarState.FUTURE,barState(true,true))
        assertEquals(BarState.MEASURED,BarDatum("Пн",0).state)
    }
}
