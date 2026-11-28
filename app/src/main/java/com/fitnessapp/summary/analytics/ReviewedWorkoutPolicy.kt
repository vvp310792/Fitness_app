package com.fitnessapp.summary.analytics

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import kotlin.math.abs

/** Owner-scoped reviewed decisions, never hard-coded personal workout IDs.
 * All affected observations and adjacent-day candidates must still match. */
data class ReviewComposite(val startMillis:Long,val timerSeconds:Long,val elapsedSeconds:Long,val segmentKeys:List<String>)

data class ReviewedResolution(
    val reason:String,val confidence:String,val countable:Boolean,
    val composite:ReviewComposite?=null,val distanceOverride:Double?=null
) {
    val probable get()=confidence.startsWith("probable")
    fun persistedJson():String=JSONObject().put("reason",reason).put("confidence",confidence)
        .put("countable",countable).put("distanceOverride",distanceOverride ?: JSONObject.NULL)
        .put("composite",composite?.let { JSONObject().put("start",it.startMillis)
            .put("timer",it.timerSeconds).put("elapsed",it.elapsedSeconds)
            .put("segmentKeys",JSONArray(it.segmentKeys)) } ?: JSONObject.NULL).toString()
}
data class ReviewedEntry(val primary:String,val members:Set<String>,val fragments:Set<String>,
    val days:Set<Long>,val resolution:ReviewedResolution)
private data class SetGuard(val name:String,val reps:Int,val weight:Double)
private data class ObservationGuard(
    val key:String,val start:Long,val sport:String,val elapsed:Long?,val timer:Long?,val distance:Double?,
    val rawSport:String,val origin:String,val setMarks:List<Long>,val cal:Double?,val avg:Double?,val max:Double?,
    val sets:List<SetGuard>?
) {
    private fun close(a:Double?,b:Double?,tolerance:Double):Boolean =
        if(a==null || b==null) a==b else a.isFinite() && b.isFinite() && abs(a-b)<=tolerance
    fun matches(o:ArchiveObservation):Boolean {
        val s=o.session
        if(s.key!=key || abs(s.startMillis-start)>1000 || s.sport.name!=sport ||
            !close(s.elapsedSeconds?.toDouble(),elapsed?.toDouble(),1.0) ||
            !close(s.recordedSeconds?.toDouble(),timer?.toDouble(),1.0) ||
            !close(s.distanceMeters?.takeIf{it>0},distance?.takeIf{it>0},1.0) ||
            s.rawSport.orEmpty()!=rawSport || s.transportOrigin.orEmpty()!=origin ||
            s.setMarksMillis.distinct().sorted()!=setMarks ||
            !close(s.copyCalories,cal,1.0) || !close(s.copyAvgHr,avg,1.0) ||
            !close(s.copyMaxHr,max,1.0)) return false
        if(sets!=null) {
            val named=sets.all { it.name.isNotBlank() }
            fun normalized(x:SetGuard)=x.copy(name=if(named)x.name else "")
            val cmp=compareBy<SetGuard>{it.name}.thenBy{it.reps}.thenBy{it.weight}
            val expected=sets.map(::normalized).sortedWith(cmp)
            val actual=o.sets.map { normalized(SetGuard(it.exerciseName,it.reps,it.weightKg.toDouble())) }.sortedWith(cmp)
            if(expected.size!=actual.size || expected.zip(actual).any { (a,b) ->
                    a.name!=b.name || a.reps!=b.reps || abs(a.weight-b.weight)>0.002 })return false
        }
        return true
    }
}

class ReviewedWorkoutPlan private constructor(private val days:Map<Long,List<ObservationGuard>>,val entries:List<ReviewedEntry>) {
    companion object {
        private const val MAX_BYTES=750000
        private fun JSONObject.nullLong(k:String)=if(isNull(k))null else getLong(k)
        private fun JSONObject.nullDouble(k:String)=if(isNull(k))null else getDouble(k).also{require(it.isFinite())}
        private fun JSONArray.strings()=(0 until length()).map{getString(it).also{v->require(v.isNotBlank())}}
        private fun <T> List<T>.uniqueSet():Set<T> = toSet().also{require(it.size==size)}
        private fun JSONObject.guard():ObservationGuard {
            val marks=getJSONArray("setMarks").let{a->(0 until a.length()).map{a.getLong(it)}}
            require(marks==marks.distinct().sorted() && marks.size<=10000)
            val sets=optJSONArray("sets")?.let{a->(0 until a.length()).map{
                val x=a.getJSONArray(it);require(x.length()==3)
                SetGuard(x.getString(0),x.getInt(1),x.getDouble(2).also{w->require(w.isFinite()&&w>=0)})
            }}
            return ObservationGuard(getString("key"),getLong("start"),getString("sport"),nullLong("elapsed"),
                nullLong("timer"),nullDouble("distance"),getString("rawSport"),getString("origin"),
                marks,nullDouble("cal"),nullDouble("avg"),nullDouble("max"),sets)
        }
        fun sha256(payload:String):String=MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8)).joinToString(""){ "%02x".format(it.toInt() and 255) }
        fun decode(payload:String,sha:String):ReviewedWorkoutPlan?=runCatching {
            require(payload.toByteArray(Charsets.UTF_8).size in 1..MAX_BYTES && sha256(payload)==sha)
            val obj=JSONObject(payload);require(obj.getInt("schemaVersion")==1)
            val ds=obj.getJSONObject("days")
            require(ds.length()<=2000)
            val days=ds.keys().asSequence().associate{ k ->
                val day=LocalDate.parse(k).toEpochDay()
                val a=ds.getJSONArray(k)
                val guards=(0 until a.length()).map{a.getJSONObject(it).guard()}
                require(guards.map{it.key}.toSet().size==guards.size)
                day to guards
            }
            val a=obj.getJSONArray("entries");require(a.length()<=2000)
            val entries=(0 until a.length()).map {
                val e=a.getJSONObject(it)
                val members=e.getJSONArray("members").strings().uniqueSet()
                val fragments=e.getJSONArray("fragments").strings().uniqueSet()
                val primary=e.getString("primary")
                val ds2=e.getJSONArray("days").strings().map{v->LocalDate.parse(v).toEpochDay()}.uniqueSet()
                require(members.isNotEmpty() && primary in members && primary !in fragments && members.containsAll(fragments))
                require(ds2.isNotEmpty() && days.keys.containsAll(ds2))
                require(members.all{m->ds2.any{d->days.getValue(d).any{g->g.key==m}}})
                val composite=e.optJSONObject("composite")?.let { c ->
                    val keys=c.getJSONArray("segmentKeys").strings()
                    require(keys.size==2 && keys.distinct().size==2 && members.containsAll(keys))
                    ReviewComposite(c.getLong("start"),c.getLong("timer"),c.getLong("elapsed"),keys)
                }
                val distance=e.nullDouble("distanceOverride")
                require(distance==null || distance>0)
                val confidence=e.getString("confidence");val reason=e.getString("reason")
                require(confidence.isNotBlank() && reason.isNotBlank())
                ReviewedEntry(primary,members,fragments,ds2,ReviewedResolution(reason,confidence,e.getBoolean("countable"),composite,distance))
            }
            require(entries.flatMap{it.members}.distinct().size==entries.sumOf{it.members.size})
            ReviewedWorkoutPlan(days,entries)
        }.getOrNull()
    }

    data class Application(val episodes:List<SessionEpisode>,val resolutions:Map<String,ReviewedResolution>,val applied:Int,val skipped:Int)

    fun apply(baseline:List<SessionEpisode>,observations:Map<String,ArchiveObservation>,conflicted:Set<String>):Application {
        val actualDays=observations.values.groupBy{it.day}
        val matchingDays=days.filter { (day,guards) ->
            val current=actualDays[day].orEmpty().associateBy{it.session.key}
            current.size==guards.size && guards.all{g->current[g.key]?.let(g::matches)==true}
        }.keys
        val owners=baseline.flatMap{e->e.observations.map{it.key to e}}.toMap()
        require(owners.size==observations.size)
        val used=mutableSetOf<String>();val replacements=mutableListOf<SessionEpisode>();val resolutions=mutableMapOf<String,ReviewedResolution>()
        for(e in entries) {
            if(!matchingDays.containsAll(e.days) || e.members.any{it in conflicted || it in used || it !in observations})continue
            val parts=e.members.mapNotNull{owners[it]}.distinct()
            if(parts.flatMap{it.observations}.map{it.key}.toSet()!=e.members)continue
            val current=e.members.map{observations.getValue(it).session}
            val resolution=e.resolution
            if(!resolution.countable && (parts.size!=1 || current.any { s ->
                    s.source==FitnessSource.GYMUP || (s.elapsedSeconds?:0)>20 || (s.recordedSeconds?:0)>20 ||
                    (s.distanceMeters?:0.0)>5.0 || (s.copyCalories?:0.0)>1.0 }))continue
            val composite=resolution.composite
            if(composite!=null) {
                val seg=composite.segmentKeys.map{observations.getValue(it).session}.sortedBy{it.startMillis}
                val x=seg[0];val y=seg[1]
                val xe=x.elapsedSeconds ?: continue
                val ye=y.elapsedSeconds ?: continue
                val end=x.startMillis+xe*1000
                if(x.source!=FitnessSource.GARMIN || y.source!=FitnessSource.GARMIN ||
                    y.startMillis !in end..(end+15000) || abs(composite.startMillis-x.startMillis)>1000 ||
                    abs(composite.elapsedSeconds-(y.startMillis+ye*1000-x.startMillis+500)/1000)>2 ||
                    abs(composite.timerSeconds-(minOf(xe,x.recordedSeconds?:xe)+minOf(ye,y.recordedSeconds?:ye)))>2 ||
                    composite.timerSeconds !in 1..composite.elapsedSeconds)continue
            }
            if(resolution.distanceOverride!=null) {
                val measured=observations.getValue(e.primary).session.distanceMeters ?: continue
                if(abs(measured-resolution.distanceOverride)>1.0)continue
            }
            val primary=observations.getValue(e.primary).session
            // The PC review resolves the membership, not the certainty of the
            // underlying physical event. Probable outcomes retain that label.
            val external=parts.flatMap{it.possibleDuplicateKeys}.filterNot{it in e.members}.distinct().sorted()
            replacements.add(SessionEpisode(primary,current.sortedBy{it.key},!resolution.countable,external,e.fragments.sorted()))
            resolutions[e.primary]=resolution
            used.addAll(e.members)
        }
        val untouched=baseline.filter{e->e.observations.none{it.key in used}}
        val all=untouched+replacements
        require(all.flatMap{it.observations}.map{it.key}.toSet()==observations.keys &&
            all.sumOf{it.observations.size}==observations.size)
        return Application(all,resolutions,resolutions.size,entries.size-resolutions.size)
    }
}
