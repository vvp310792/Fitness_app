package com.fitnessapp.summary.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.fitnessapp.summary.analytics.LifestyleAnalytics
import com.fitnessapp.summary.analytics.TrendPoint

/** Presentation only. No persisted values, workout reconciliation or aggregates change. */
enum class TrendSmoothing(val label:String,val description:String) {
    MEDIAN("Медиана","скользящая медиана"),
    MEAN("Среднее","скользящее среднее")
}
val LocalTrendSmoothing=staticCompositionLocalOf{TrendSmoothing.MEDIAN}

/** Weekly distance is an additive volume, not a typical individual reading.
 * Zero-volume weeks are retained in its mean. A global median preference must
 * not make a seasonal sport disappear from the volume chart. */
internal fun effectiveTrendSmoothing(preference:TrendSmoothing,weeklyVolume:Boolean):TrendSmoothing =
    if(weeklyVolume)TrendSmoothing.MEAN else preference

/** Centred calendar-day window; no zero-fill, no synthetic dates. At least
 * minPoints actual observations are needed, and even samples use the midpoint
 * of the two middle values. Maintain a sorted sliding window for long histories. */
internal fun rollingMedian(points:List<TrendPoint>,windowDays:Int,minPoints:Int=3):List<TrendPoint> {
    if(windowDays<2 || minPoints<1 || points.size<minPoints)return emptyList()
    val sorted=points.filter{it.value.isFinite()}.sortedBy{it.epochDay}
    val half=(windowDays/2).toLong()
    val window=ArrayList<Float>()
    val result=ArrayList<TrendPoint>(sorted.size)
    var lo=0
    var hi=0
    for(p in sorted) {
        while(hi<sorted.size && sorted[hi].epochDay<=p.epochDay+half) {
            val v=sorted[hi++].value
            val index=window.binarySearch(v)
            window.add(if(index<0)-index-1 else index,v)
        }
        while(lo<hi && sorted[lo].epochDay<p.epochDay-half) {
            val index=window.binarySearch(sorted[lo++].value)
            check(index>=0)
            window.removeAt(index)
        }
        val n=window.size
        if(n>=minPoints) {
            val median=if(n%2==1)window[n/2] else ((window[n/2-1].toDouble()+window[n/2].toDouble())/2.0).toFloat()
            result.add(TrendPoint(p.epochDay,median))
        }
    }
    return result
}

internal fun trendSmoothing(points:List<TrendPoint>,windowDays:Int,mode:TrendSmoothing):List<TrendPoint> =
    when(mode) {
        TrendSmoothing.MEDIAN->rollingMedian(points,windowDays)
        TrendSmoothing.MEAN->LifestyleAnalytics.smoothTrend(points,windowDays)
    }

internal enum class TrendEdge { SOLID, BREAK, BRIDGE }

/** Raw observations break at missing dates. A calculated trend remains legible:
 * intervals exceeding its own calendar window are dotted bridges, not readings. */
internal fun trendEdge(previous:Long,next:Long,maxGapDays:Long,bridge:Boolean):TrendEdge =
    if(!trendHasGap(previous,next,maxGapDays))TrendEdge.SOLID
    else if(bridge)TrendEdge.BRIDGE else TrendEdge.BREAK

internal fun smoothingGapDays(rawGapDays:Long,windowDays:Int):Long =
    maxOf(rawGapDays.coerceAtLeast(1L),windowDays.toLong())

/** Dense points must not obscure the trend, even when one date is missing. */
internal data class TrendDotStyle(val radiusDp:Float,val opacity:Float)
internal fun trendDotStyle(count:Int,hasSmooth:Boolean):TrendDotStyle=when {
    !hasSmooth && count<=100->TrendDotStyle(2.5f,0.85f)
    count>1000->TrendDotStyle(0.65f,0.16f)
    count>100->TrendDotStyle(1.0f,0.24f)
    else->TrendDotStyle(1.8f,0.38f)
}
