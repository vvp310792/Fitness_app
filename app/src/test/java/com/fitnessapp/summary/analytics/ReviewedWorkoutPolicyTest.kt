package com.fitnessapp.summary.analytics

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ReviewedWorkoutPolicyTest {
    private val d=LocalDate.of(2025,1,1)
    private val t=1735689600000L
    private fun observation(src:FitnessSource,id:String,start:Long=t,elapsed:Long?=1800,timer:Long?=1800,
        distance:Double?=5000.0):ArchiveObservation {
        val s=FitnessSession(src,id,FitnessSport.RUN,start,elapsed,distance,recordedSeconds=timer)
        return ArchiveObservation(s,d.toEpochDay(),id,timer,null,null)
    }
    private fun guard(o:ArchiveObservation)=o.session.let{s->JSONObject()
        .put("key",s.key).put("start",s.startMillis).put("sport",s.sport.name)
        .put("elapsed",s.elapsedSeconds?:JSONObject.NULL).put("timer",s.recordedSeconds?:JSONObject.NULL)
        .put("distance",s.distanceMeters?:JSONObject.NULL).put("rawSport","").put("origin","")
        .put("setMarks",JSONArray()).put("cal",JSONObject.NULL).put("avg",JSONObject.NULL).put("max",JSONObject.NULL)}
    private fun entry(primary:ArchiveObservation,all:List<ArchiveObservation>,countable:Boolean=true,
        confidence:String="probable_same_event",composite:JSONObject?=null):JSONObject =
        JSONObject().put("primary",primary.session.key).put("members",JSONArray(all.map{it.session.key}))
            .put("fragments",JSONArray(all.filter{it!=primary}.map{it.session.key}))
            .put("days",JSONArray(listOf(d.toString()))).put("countable",countable)
            .put("reason","synthetic_review").put("confidence",confidence).also{if(composite!=null)it.put("composite",composite)}
    private fun payload(obs:List<ArchiveObservation>,entries:List<JSONObject>):String=JSONObject()
        .put("schemaVersion",1).put("days",JSONObject().put(d.toString(),JSONArray(obs.map(::guard))))
        .put("entries",JSONArray(entries)).toString()
    private fun plan(obs:List<ArchiveObservation>,entries:List<JSONObject>):ReviewedWorkoutPlan {
        val p=payload(obs,entries)
        return requireNotNull(ReviewedWorkoutPlan.decode(p,ReviewedWorkoutPlan.sha256(p)))
    }
    private fun baseline(obs:List<ArchiveObservation>)=obs.map{SessionEpisode(it.session,listOf(it.session),true)}
    private fun apply(p:ReviewedWorkoutPlan,obs:List<ArchiveObservation>,conflicts:Set<String> = emptySet())=
        p.apply(baseline(obs),obs.associateBy{it.session.key},conflicts)
    @Test fun guardedMergeRetainsBothObservationsAndConfidence() {
        val g=observation(FitnessSource.GARMIN,"g")
        val s=observation(FitnessSource.STRAVA,"s",elapsed=null)
        val obs=listOf(g,s)
        val p=plan(obs,listOf(entry(g,obs)))
        val result=apply(p,obs)
        assertEquals(1,result.applied);assertEquals(1,result.episodes.size)
        assertEquals(2,result.episodes.single().observations.size)
        assertTrue(result.resolutions.getValue(g.session.key).probable)
        assertFalse(result.episodes.single().ambiguous)
    }
    @Test fun changedMetricsNewNeighborMissingMemberAndConflictFailClosed() {
        val g=observation(FitnessSource.GARMIN,"g")
        val s=observation(FitnessSource.STRAVA,"s",elapsed=null)
        val obs=listOf(g,s);val p=plan(obs,listOf(entry(g,obs)))
        val variants=listOf(listOf(g),listOf(g,s.copy(session=s.session.copy(distanceMeters=8000.0))),
            obs+observation(FitnessSource.STRAVA,"new",start=t+10000))
        for(v in variants)assertEquals(0,apply(p,v).applied)
        assertEquals(0,apply(p,obs,setOf(s.session.key)).applied)
        val str=payload(obs,listOf(entry(g,obs)))
        assertNull(ReviewedWorkoutPlan.decode(str+" ",ReviewedWorkoutPlan.sha256(str)))
    }
    @Test fun shortRecordRemainsInArchiveButNotInStats() {
        val g=observation(FitnessSource.GARMIN,"short",elapsed=8,timer=8,distance=null)
        val p=plan(listOf(g),listOf(entry(g,listOf(g),false,"insufficient_training_evidence")))
        val r=apply(p,listOf(g));assertEquals(1,r.applied)
        val a=ArchiveEpisode(r.episodes.single(),g,listOf(g),r.resolutions[g.session.key])
        val period=WorkoutPeriod(listOf(a))
        assertEquals(1,period.shortRecordCount);assertEquals(0,period.confirmed.size)
        assertEquals(1,period.accountedObservationCount);assertEquals(0L,period.durationSeconds)
        assertEquals(1,period.displayGroups.size);assertTrue(period.sportSessions().isEmpty())
    }
    @Test fun shortCopiesMayBeGroupedButNeverCounted() {
        val g=observation(FitnessSource.GARMIN,"short-g",elapsed=8,timer=8,distance=null)
        val h=observation(FitnessSource.HEALTH_CONNECT,"short-h",elapsed=7,timer=7,distance=null)
        val obs=listOf(g,h)
        val p=plan(obs,listOf(entry(g,obs,false,"insufficient_training_evidence")))
        val r=apply(p,obs)
        assertEquals(1,r.applied)
        val e=ArchiveEpisode(r.episodes.single(),g,obs,r.resolutions[g.session.key])
        assertEquals(0,WorkoutPeriod(listOf(e)).confirmed.size)
        assertEquals(2,e.observations.size)
    }
    @Test fun compositeTimerUsesMeasuredNonOverlappingSegments() {
        val a=observation(FitnessSource.GARMIN,"first",elapsed=178,timer=178,distance=null)
        val b=observation(FitnessSource.GARMIN,"second",start=t+186000,elapsed=1966,timer=1967,distance=null)
        val obs=listOf(a,b)
        val metrics=JSONObject().put("start",t).put("timer",2144L).put("elapsed",2152L)
            .put("segmentKeys",JSONArray(obs.map{it.session.key}))
        val p=plan(obs,listOf(entry(b,obs,true,"measured_consecutive_fragments",metrics)))
        val r=apply(p,obs);assertEquals(1,r.applied)
        val e=ArchiveEpisode(r.episodes.single(),b,obs,r.resolutions[b.session.key])
        assertEquals(2144L,e.durationSeconds);assertEquals(t,e.startMillis);assertEquals(2152L,e.elapsedSeconds)
        metrics.put("timer",9999L)
        assertEquals(0,apply(plan(obs,listOf(entry(b,obs,true,"measured_consecutive_fragments",metrics))),obs).applied)
    }
}
