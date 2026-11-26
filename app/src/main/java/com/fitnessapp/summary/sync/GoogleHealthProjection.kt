package com.fitnessapp.summary.sync

import com.fitnessapp.summary.data.DailySummary
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.TreeMap
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Read-only daily projection of verified Google Health records.
 * Copies from different apps are alternatives, not additive streams. */
data class GoogleHealthDay(
    val summary: DailySummary,
    val floors: Double? = null,
    val metricSources: Map<String,String> = emptyMap()
)
data class GoogleHeight(val day: Long, val instant: Long, val centimeters: Double)

internal object HealthJson {
    fun data(row: VerifiedServerRow): JSONObject? =
        runCatching { JSONObject(row.rowJson).optJSONObject("data") }.getOrNull()
    fun number(o: JSONObject?, k: String): Double? =
        o?.optDouble(k,Double.NaN)?.takeIf { it.isFinite() && it >= 0 }
    fun text(o: JSONObject?, k: String): String =
        o?.takeUnless { it.isNull(k) }?.optString(k,"").orEmpty()
    fun epoch(text: String): Long? =
        runCatching { Instant.parse(text).toEpochMilli() }.recoverCatching {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        }.getOrNull()?.takeIf { it > 0 }
    fun civilDate(o: JSONObject?): Long? = runCatching {
        requireNotNull(o)
        LocalDate.of(o.getInt("year"),o.getInt("month"),o.getInt("day")).toEpochDay()
    }.getOrNull()
    fun rowDay(row: VerifiedServerRow): Long? =
        runCatching { LocalDate.parse(row.day).toEpochDay() }.getOrNull()
    fun dayAt(ms: Long, offset: String): Long? = runCatching {
        val seconds=offset.removeSuffix("s").toInt()
        Instant.ofEpochMilli(ms).atOffset(ZoneOffset.ofTotalSeconds(seconds)).toLocalDate().toEpochDay()
    }.getOrNull()
    fun sampleDay(o: JSONObject?, ms: Long, row: VerifiedServerRow): Long? =
        civilDate(o?.optJSONObject("civilTime")?.optJSONObject("date"))
            ?: dayAt(ms,text(o,"utcOffset")) ?: rowDay(row)
    fun stream(d: JSONObject): String =
        text(d.optJSONObject("dataSource")?.optJSONObject("application"),"packageName").ifBlank { "Google Health" }
    fun rank(s: String): Int = when(s) {
        "com.garmin.android.apps.connectmobile" -> 0
        "com.android.healthconnect.phone" -> 1
        "com.google.android.apps.fitness" -> 2
        else -> 3
    }
}

object GoogleHealthProjection {
    private data class Segment(val day:Long,val metric:String,val source:String,val start:Long,val end:Long,val value:Double)
    private data class Choice(val value:Double,val coverage:Long,val source:String)
    private data class Sample(val day:Long,val source:String,val at:Long,val value:Double)
    private data class Night(val day:Long,val source:String,val at:Long,val main:Boolean,
        val total:Int,val deep:Int,val light:Int,val rem:Int,val awake:Int)
    private data class DailyValue(val day:Long,val source:String,val metric:String,val value:Double)
    private fun positiveInt(v: Double?):Int = v?.takeIf { it <= Int.MAX_VALUE }?.roundToInt() ?: 0

    private fun segment(row:VerifiedServerRow,d:JSONObject,key:String,field:String,scale:Double):Segment? {
        val b=d.optJSONObject(key) ?: return null
        val i=b.optJSONObject("interval") ?: return null
        val start=HealthJson.epoch(HealthJson.text(i,"startTime")) ?: return null
        val end=HealthJson.epoch(HealthJson.text(i,"endTime")) ?: return null
        if(end<=start || end-start>90_000_000L) return null
        val offset=HealthJson.text(i,"startUtcOffset")
        val day=HealthJson.civilDate(i.optJSONObject("civilStartTime")?.optJSONObject("date"))
            ?: HealthJson.dayAt(start,offset) ?: HealthJson.rowDay(row) ?: return null
        // Attribute only whole intervals. A cross-midnight interval is not apportioned
        // using an invented constant activity rate. Ending exactly at midnight is OK.
        val last=HealthJson.dayAt(end-1,HealthJson.text(i,"endUtcOffset").ifBlank { offset })
        if(last!=null && last!=day) return null
        val value=HealthJson.number(b,field)?.times(scale) ?: return null
        return Segment(day,row.kind,HealthJson.stream(d),start,end,value)
    }

    private fun chooseIntervals(xs:List<Segment>):Choice? {
        val choices=xs.groupBy { it.source }.mapNotNull { (source,rows) ->
            // Exact interval copies collapse. Conflicting copies are omitted.
            val unique=rows.groupBy { it.start to it.end }.values.mapNotNull { copies ->
                copies.first().takeIf { copies.all { c -> c.value==it.value } }
            }
            val kept=TreeMap<Long,Segment>()
            // Longest spans first; never add overlapping totals within a stream.
            unique.sortedWith(compareByDescending<Segment> { it.end-it.start }.thenBy { it.start }).forEach { s ->
                if ((kept.floorEntry(s.start)?.value?.end ?: Long.MIN_VALUE)<=s.start &&
                    (kept.ceilingKey(s.start) ?: Long.MAX_VALUE)>=s.end) kept[s.start]=s
            }
            if(kept.isEmpty()) null else Choice(kept.values.sumOf { it.value },kept.values.sumOf { it.end-it.start },source)
        }
        // Prefer the best recorded coverage, then the known origin priority. Never sum apps.
        return choices.sortedWith(compareByDescending<Choice> { it.coverage }
            .thenBy { HealthJson.rank(it.source) }.thenBy { it.source }).firstOrNull()
    }

    private fun night(row:VerifiedServerRow,d:JSONObject):Night? {
        val sleep=d.optJSONObject("sleep") ?: return null
        val i=sleep.optJSONObject("interval") ?: return null
        val start=HealthJson.epoch(HealthJson.text(i,"startTime")) ?: return null
        val end=HealthJson.epoch(HealthJson.text(i,"endTime")) ?: return null
        if(end<=start || end-start>90_000_000L) return null
        val day=HealthJson.dayAt(end,HealthJson.text(i,"endUtcOffset")) ?: HealthJson.rowDay(row) ?: return null
        val sum=sleep.optJSONObject("summary") ?: return null
        val total=positiveInt(HealthJson.number(sum,"minutesAsleep")).takeIf { it in 1..1440 } ?: return null
        val stages=sum.optJSONArray("stagesSummary")
        val m=mutableMapOf<String,Int>()
        for(n in 0 until (stages?.length() ?: 0)){
            val s=stages?.optJSONObject(n) ?: continue
            val type=HealthJson.text(s,"type")
            val minutes=positiveInt(HealthJson.number(s,"minutes"))
            if(type in setOf("DEEP","LIGHT","REM","AWAKE") && minutes in 0..1440 && type !in m) m[type]=minutes
        }
        // Keep one night's stages together; inconsistent breakdown is unknown.
        val stagesValid=listOf("DEEP","LIGHT","REM").sumOf { m[it] ?: 0 }<=total+3
        return Night(day,HealthJson.stream(d),end,sleep.optJSONObject("metadata")?.optBoolean("mainSleep",false)==true,
            total,if(stagesValid) m["DEEP"] ?: 0 else 0,if(stagesValid) m["LIGHT"] ?: 0 else 0,
            if(stagesValid) m["REM"] ?: 0 else 0,positiveInt(HealthJson.number(sum,"minutesAwake")).takeIf { it<=1440 } ?: 0)
    }

    fun days(rows:List<VerifiedServerRow>):List<GoogleHealthDay> {
        val segments=mutableListOf<Segment>()
        val heart=mutableListOf<Sample>()
        val nights=mutableListOf<Night>()
        val daily=mutableListOf<DailyValue>()
        for(row in rows) {
            if(row.source!="google_health") continue
            val d=HealthJson.data(row) ?: continue
            when(row.kind) {
                "steps" -> segment(row,d,"steps","count",1.0)?.let(segments::add)
                "distance" -> segment(row,d,"distance","millimeters",0.001)?.let(segments::add)
                "active-energy-burned" -> segment(row,d,"activeEnergyBurned","kcal",1.0)?.let(segments::add)
                "heart-rate" -> {
                    val b=d.optJSONObject("heartRate") ?: continue
                    val t=b.optJSONObject("sampleTime") ?: continue
                    val ms=HealthJson.epoch(HealthJson.text(t,"physicalTime")) ?: continue
                    val day=HealthJson.sampleDay(t,ms,row) ?: continue
                    val v=HealthJson.number(b,"beatsPerMinute")?.takeIf { it in 20.0..250.0 } ?: continue
                    heart.add(Sample(day,HealthJson.stream(d),ms,v))
                }
                "sleep" -> night(row,d)?.let(nights::add)
                "daily-resting-heart-rate" -> {
                    val b=d.optJSONObject("dailyRestingHeartRate") ?: continue
                    val day=HealthJson.civilDate(b.optJSONObject("date")) ?: HealthJson.rowDay(row) ?: continue
                    val v=HealthJson.number(b,"beatsPerMinute")?.takeIf { it in 20.0..250.0 } ?: continue
                    daily.add(DailyValue(day,HealthJson.stream(d),row.kind,v))
                }
                "floors","total-calories" -> {
                    val day=HealthJson.civilDate(d.optJSONObject("civilStartTime")?.optJSONObject("date"))
                        ?: HealthJson.rowDay(row) ?: continue
                    val key=if(row.kind=="floors") "floors" else "totalCalories"
                    val field=if(row.kind=="floors") "countSum" else "kcalSum"
                    val v=HealthJson.number(d.optJSONObject(key),field) ?: continue
                    daily.add(DailyValue(day,HealthJson.stream(d),row.kind,v))
                }
            }
        }
        val s=segments.groupBy { it.day }
        val h=heart.groupBy { it.day }
        val n=nights.groupBy { it.day }
        val d=daily.groupBy { it.day }
        return (s.keys+h.keys+n.keys+d.keys).sorted().map { day ->
            val sources=mutableMapOf<String,String>()
            fun interval(kind:String):Double? {
                val c=chooseIntervals(s[day].orEmpty().filter { it.metric==kind }) ?: return null
                sources[kind]=c.source
                return c.value
            }
            fun aggregate(kind:String):Double? {
                val candidates=d[day].orEmpty().filter { it.metric==kind }.groupBy { it.source }.mapNotNull { (_,rr) ->
                    rr.first().takeIf { r -> rr.all { it.value==r.value } }
                }
                val c=candidates.sortedWith(compareBy<DailyValue> { HealthJson.rank(it.source) }.thenBy { it.source }).firstOrNull() ?: return null
                sources[kind]=c.source
                return c.value
            }
            val hs=h[day].orEmpty().groupBy { it.source }.values.map { samples ->
                samples.groupBy { it.at }.values.mapNotNull { copies -> copies.first().takeIf { r -> copies.all { it.value==r.value } } }
            }.filter { it.isNotEmpty() }.sortedWith(compareByDescending<List<Sample>> { it.size }
                .thenBy { HealthJson.rank(it.first().source) }.thenBy { it.first().source }).firstOrNull().orEmpty()
            if(hs.isNotEmpty()) sources["heart-rate"]=hs.first().source
            val sleep=n[day].orEmpty().sortedWith(compareByDescending<Night> { it.main }
                .thenBy { HealthJson.rank(it.source) }.thenByDescending { it.total }.thenByDescending { it.at }).firstOrNull()
            if(sleep!=null) sources["sleep"]=sleep.source
            val steps=interval("steps")?.takeIf { it <= Long.MAX_VALUE.toDouble() }?.roundToLong() ?: 0L
            val distance=positiveInt(interval("distance"))
            val active=positiveInt(interval("active-energy-burned"))
            val total=positiveInt(aggregate("total-calories"))
            val resting=positiveInt(aggregate("daily-resting-heart-rate"))
            val floors=aggregate("floors")
            GoogleHealthDay(DailySummary(dateEpochDay=day,steps=steps,distanceMeters=distance,activeCaloriesKcal=active,
                totalCaloriesKcal=total,restingHeartRate=resting,
                avgHeartRate=if(hs.isEmpty()) 0 else hs.map { it.value }.average().roundToInt(),
                minHeartRate=positiveInt(hs.minOfOrNull { it.value }),maxHeartRate=positiveInt(hs.maxOfOrNull { it.value }),
                sleepTotalMinutes=sleep?.total ?: 0,sleepDeepMinutes=sleep?.deep ?: 0,sleepLightMinutes=sleep?.light ?: 0,
                sleepRemMinutes=sleep?.rem ?: 0,sleepAwakeMinutes=sleep?.awake ?: 0,
                sourceApps="google_health",updatedAtMillis=0),floors,sources)
        }
    }

    /** Local measured values take precedence, with a whole-night fallback. */
    fun merge(local:DailySummary?, remote:DailySummary?):DailySummary? {
        if(local==null) return remote
        if(remote==null) return local
        require(local.dateEpochDay==remote.dateEpochDay)
        fun take(a:Int,b:Int)=if(a>0) a else b
        val sl=if(local.sleepTotalMinutes>0) local else remote
        val heart=if(local.avgHeartRate>0 || local.minHeartRate>0 || local.maxHeartRate>0) local else remote
        val merged=local.copy(steps=if(local.steps>0) local.steps else remote.steps,
            distanceMeters=take(local.distanceMeters,remote.distanceMeters),
            activeCaloriesKcal=take(local.activeCaloriesKcal,remote.activeCaloriesKcal),
            totalCaloriesKcal=take(local.totalCaloriesKcal,remote.totalCaloriesKcal),
            restingHeartRate=take(local.restingHeartRate,remote.restingHeartRate),
            avgHeartRate=heart.avgHeartRate,minHeartRate=heart.minHeartRate,maxHeartRate=heart.maxHeartRate,
            sleepTotalMinutes=sl.sleepTotalMinutes,sleepDeepMinutes=sl.sleepDeepMinutes,
            sleepLightMinutes=sl.sleepLightMinutes,sleepRemMinutes=sl.sleepRemMinutes,sleepAwakeMinutes=sl.sleepAwakeMinutes,
            sourceApps=local.sourceApps)
        return if(merged==local) local else merged.copy(sourceApps=(local.sourceApps.split(",").filter { it.isNotBlank() }+"google_health").distinct().joinToString(","))
    }

    fun heights(rows:List<VerifiedServerRow>):List<GoogleHeight> = rows.mapNotNull { row ->
        if(row.source!="google_health" || row.kind!="height") return@mapNotNull null
        val b=HealthJson.data(row)?.optJSONObject("height") ?: return@mapNotNull null
        val t=b.optJSONObject("sampleTime") ?: return@mapNotNull null
        val at=HealthJson.epoch(HealthJson.text(t,"physicalTime")) ?: return@mapNotNull null
        val day=HealthJson.sampleDay(t,at,row) ?: return@mapNotNull null
        val cm=HealthJson.number(b,"heightMillimeters")?.div(10)?.takeIf { it in 20.0..300.0 } ?: return@mapNotNull null
        GoogleHeight(day,at,cm)
    }.distinctBy { it.instant }.sortedBy { it.instant }
}
