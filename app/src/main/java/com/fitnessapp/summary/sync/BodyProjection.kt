package com.fitnessapp.summary.sync

import com.fitnessapp.summary.data.FitDay
import com.fitnessapp.summary.data.GarminBodyComposition
import com.fitnessapp.summary.data.ScaleMeasurement
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.roundToInt

/** One coherent observation per day, rather than blending BIA fields from different weigh-ins. */
data class BodyDay(
    val day: Long,
    val source: String,
    val measuredAt: Long? = null,
    val scale: ScaleMeasurement? = null,
    val garmin: GarminBodyComposition? = null
) {
    val weightGrams: Int get() = scale?.weightGrams ?: garmin?.weightGrams ?: 0
    val fatPercent: Float get() = scale?.bodyFatPercent ?: garmin?.bodyFatPercent ?: 0f
    val waterPercent: Float get() = scale?.bodyWaterPercent ?: garmin?.bodyWaterPercent ?: 0f
    val bmi: Float get() = scale?.bmi ?: garmin?.bmi ?: 0f
    val muscleGrams: Int get() = scale?.muscleMassGrams ?: garmin?.muscleMassGrams ?: 0
    val richness: Int get() = listOf(fatPercent,waterPercent,bmi,muscleGrams.toFloat()).count { it>0 }
}

object BodyProjection {
    const val Z = "Zepp Life"
    const val G = "Garmin"
    const val H = "Health Connect"
    const val GH = "Google Health"
    const val F = "Google Fit"
    private fun rank(source:String)=when(source){Z->0;G->1;H->2;GH->3;F->4;else->5}
    private fun n(o:JSONObject?,k:String) = HealthJson.number(o,k)
    private fun int(o:JSONObject?,k:String,scale:Double=1.0):Int =
        n(o,k)?.times(scale)?.takeIf { it<=Int.MAX_VALUE }?.roundToInt() ?: 0
    private fun fl(o:JSONObject?,k:String,max:Double=1000.0):Float =
        n(o,k)?.takeIf { it<=max }?.toFloat() ?: 0f
    private fun weight(v:Int):Boolean = v in 10000..500000
    private fun stamp(v:Long):Long? = when {
        v in 946684800..4102444800L -> v*1000L
        v in 946684800000L..4102444800000L -> v
        else -> null
    }
    private fun scaleSource(s:String):String = when(s) {
        ScaleMeasurement.SOURCE_ZEPP,ScaleMeasurement.SOURCE_ZEPP_FILE->Z
        ScaleMeasurement.SOURCE_HEALTH_CONNECT->H
        "google_health"->GH
        else->"Архив весов"
    }
    fun scale(s:ScaleMeasurement):BodyDay? = s.takeIf { weight(it.weightGrams) }?.let {
        BodyDay(it.dateEpochDay,scaleSource(it.source),it.timestampMillis,scale=it)
    }
    fun garmin(g:GarminBodyComposition):BodyDay? = g.takeIf { weight(it.weightGrams) }?.let {
        BodyDay(it.dateEpochDay,G,garmin=it)
    }
    fun fit(f:FitDay):BodyDay? = f.takeIf { weight(it.weightGrams) }?.let {
        BodyDay(it.dateEpochDay,F,garmin=GarminBodyComposition(it.dateEpochDay,weightGrams=it.weightGrams,sourceType="Google Fit",updatedAtMillis=0))
    }
    private fun zepp(row:VerifiedServerRow,d:JSONObject):BodyDay? {
        if(d.optInt("weightType",0)!=0)return null
        val t=stamp(d.optLong("generatedTime",0)) ?: return null
        val day=HealthJson.rowDay(row) ?: return null
        val s=d.optJSONObject("summary") ?: return null
        val w=int(s,"weight",1000.0)
        if(!weight(w))return null
        return BodyDay(day,Z,t,scale=ScaleMeasurement(timestampMillis=t,dateEpochDay=day,weightGrams=w,
            heightCm=fl(s,"height",300.0),bmi=fl(s,"bmi",100.0),bodyFatPercent=fl(s,"fatRate",100.0),
            bodyWaterPercent=fl(s,"bodyWaterRate",100.0),boneMassGrams=int(s,"boneMass",1000.0),
            muscleMassGrams=int(s,"muscleRate",1000.0),metabolicAge=int(s,"muscleAge"),
            visceralFat=int(s,"visceralFat"),basalMetabolismKcal=int(s,"metabolism"),proteinPercent=fl(s,"proteinRatio",100.0),
            bodyScore=int(s,"bodyScore"),physiqueRating=int(s,"bodyStyle"),impedance=int(s,"impedance"),
            source=ScaleMeasurement.SOURCE_ZEPP,updatedAtMillis=0))
    }
    private fun garminReading(row:VerifiedServerRow,d:JSONObject):BodyDay? {
        val day=runCatching { LocalDate.parse(HealthJson.text(d,"calendarDate")).toEpochDay() }.getOrNull()
            ?: HealthJson.rowDay(row) ?: return null
        val w=int(d,"weight")
        if(!weight(w))return null
        val t=stamp(d.optLong("timestampGMT",0))
        return BodyDay(day,G,t,garmin=GarminBodyComposition(dateEpochDay=day,weightGrams=w,
            bmi=fl(d,"bmi",100.0),bodyFatPercent=fl(d,"bodyFat",100.0),bodyWaterPercent=fl(d,"bodyWater",100.0),
            boneMassGrams=int(d,"boneMass"),muscleMassGrams=int(d,"muscleMass"),
            visceralFat=fl(d,"visceralFat",100.0),metabolicAge=int(d,"metabolicAge"),
            sourceType=HealthJson.text(d,"sourceType"),updatedAtMillis=0))
    }
    private fun google(row:VerifiedServerRow,d:JSONObject):BodyDay? {
        val s=d.optJSONObject("weight") ?: return null
        val time=s.optJSONObject("sampleTime") ?: return null
        val t=HealthJson.epoch(HealthJson.text(time,"physicalTime")) ?: return null
        val day=HealthJson.sampleDay(time,t,row) ?: return null
        val w=int(s,"weightGrams")
        if(!weight(w))return null
        return BodyDay(day,GH,t,scale=ScaleMeasurement(t,day,w,source="google_health",updatedAtMillis=0))
    }
    private fun legacy(row:VerifiedServerRow,d:JSONObject):BodyDay? {
        val t=stamp(d.optLong("timestampMillis",0)) ?: return null
        val day=HealthJson.rowDay(row) ?: return null
        val w=int(d,"weightGrams")
        if(!weight(w))return null
        val source=HealthJson.text(d,"source")
        return BodyDay(day,scaleSource(source),t,scale=ScaleMeasurement(timestampMillis=t,dateEpochDay=day,weightGrams=w,
            heightCm=fl(d,"heightCm",300.0),bmi=fl(d,"bmi",100.0),bodyFatPercent=fl(d,"bodyFatPercent",100.0),
            bodyWaterPercent=fl(d,"bodyWaterPercent",100.0),boneMassGrams=int(d,"boneMassGrams"),
            muscleMassGrams=int(d,"muscleMassGrams"),metabolicAge=int(d,"metabolicAge"),visceralFat=int(d,"visceralFat"),
            basalMetabolismKcal=int(d,"basalMetabolismKcal"),proteinPercent=fl(d,"proteinPercent",100.0),
            bodyScore=int(d,"bodyScore"),physiqueRating=int(d,"physiqueRating"),impedance=int(d,"impedance"),
            source=source,updatedAtMillis=0))
    }

    fun readings(rows:List<VerifiedServerRow>):List<BodyDay> = buildList {
        for(row in rows){
            val d=HealthJson.data(row) ?: continue
            when {
                row.source in setOf("zepp","zepp_archive") && row.kind=="weight" -> zepp(row,d)?.let { add(it) }
                row.source=="google_health" && row.kind=="weight" -> google(row,d)?.let { add(it) }
                row.source=="legacy_app" && row.kind=="scale_measurements" -> legacy(row,d)?.let { add(it) }
                row.source=="garmin" && row.kind in setOf("weight","body_composition","body_composition_range","weigh_ins") -> {
                    // Flatten actual weigh-ins, never a period's average into an invented day.
                    val a=d.optJSONArray("allWeightMetrics") ?: d.optJSONArray("dateWeightList")
                    if(a!=null) for(i in 0 until a.length()) a.optJSONObject(i)?.let { garminReading(row,it) }?.let { add(it) }
                    d.optJSONObject("latestWeight")?.let { garminReading(row,it) }?.let { add(it) }
                    if(d.has("weight"))garminReading(row,d)?.let { add(it) }
                }
            }
        }
    }

    /** Primary scale wins a day; within a source latest measured reading wins.
     * A local daily Garmin record with no timestamp is only preferred for its
     * coherent extra fields when the remote measurement has the same weight. */
    fun choose(readings:List<BodyDay>):List<BodyDay> =
        readings.filter { weight(it.weightGrams) }.groupBy { it.day }.values.map { day ->
            val bestRank=day.minOf { rank(it.source) }
            val candidates=day.filter { rank(it.source)==bestRank }
            val latest=candidates.sortedWith(compareByDescending<BodyDay> { it.measuredAt ?: Long.MIN_VALUE }
                .thenByDescending { it.richness }).first()
            candidates.filter { it.weightGrams==latest.weightGrams &&
                (it.measuredAt==latest.measuredAt || it.measuredAt==null) }
                .maxByOrNull { it.richness }?.takeIf { it.richness>latest.richness } ?: latest
        }.sortedBy { it.day }
}
