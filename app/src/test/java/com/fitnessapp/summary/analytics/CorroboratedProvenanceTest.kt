package com.fitnessapp.summary.analytics

import org.junit.Assert.*
import org.junit.Test

class CorroboratedProvenanceTest {
    private val cutoff=1635267600000L
    private val t=cutoff+100000
    private fun row(src: FitnessSource,id: String,sport: FitnessSport=FitnessSport.STRENGTH,
        start: Long=t,elapsed: Long?=2400,timer: Long?=1000,distance: Double?=null,raw: String?=null,
        cal: Double?=200.0,avg: Double?=100.0,mx: Double?=150.0) =
        FitnessSession(src,id,sport,start,elapsed,distance,recordedSeconds=timer,rawSport=raw,
            copyCalories=cal,copyAvgHr=avg,copyMaxHr=mx)
    private fun g()=row(FitnessSource.GARMIN,"g")
    private fun s()=row(FitnessSource.STRAVA,"s",elapsed=null,timer=2401)
    private fun episodes(vararg rows: FitnessSession)=CanonicalFitness.episodes(rows.toList(),cutoff)
    @Test fun elapsedExportPreservesMeasuredGarminAndRawStrava() {
        val g=g()
        val s=s()
        val e=episodes(g,s).single()
        assertEquals(g,e.primary)
        assertEquals(setOf(g,s),e.observations.toSet())
        assertNull(e.observations.single { it.source==FitnessSource.STRAVA }.elapsedSeconds)
    }
    @Test fun poolSwimmingWithClockOffsetNeedsCalorieDistanceTimerEvidence() {
        val g=row(FitnessSource.GARMIN,"g",FitnessSport.SWIM,timer=2300,distance=800.0,mx=null)
        val s=row(FitnessSource.STRAVA,"s",FitnessSport.SWIM,start=t+9000,elapsed=null,timer=2291,distance=800.0,mx=null)
        assertEquals(1,episodes(g,s).size)
        assertEquals(2,episodes(g,s.copy(copyCalories=201.0)).size)
    }
    @Test fun explicitGenericTrailExportNeedsRouteAndHrEvidence() {
        val g=row(FitnessSource.GARMIN,"g",FitnessSport.RUN,timer=2300,distance=11000.0,raw="trail_running")
        val s=row(FitnessSource.STRAVA,"s",FitnessSport.OTHER,elapsed=null,timer=2100,distance=10998.0,raw="workout")
        assertEquals(1,episodes(g,s).size)
        assertEquals(2,episodes(g,s.copy(copyAvgHr=110.0)).size)
    }
    @Test fun conflictingOrMissingMeasurementsAndCompetingIdentityStaySeparate() {
        val g=g()
        val s=s()
        for(bad in listOf(s.copy(copyMaxHr=160.0),s.copy(copyCalories=null),s.copy(recordedSeconds=3600),
            s.copy(elapsedSeconds=600),s.copy(sport=FitnessSport.RUN))) {
            assertEquals(2,episodes(g,bad).size)
        }
        assertEquals(2,episodes(g.copy(elapsedSeconds=null),s).size)
        val competitor=s.copy(nativeId="s2",startMillis=t+5000,sport=FitnessSport.RUN)
        assertEquals(3,episodes(g,s,competitor).size)
        assertEquals(3,episodes(g,s,g.copy(nativeId="g2",startMillis=t+5000,sport=FitnessSport.RUN)).size)
    }
    @Test fun ruleDoesNotInferElapsedBeforeCutoffOrOnShortNoise() {
        assertEquals(2,episodes(g().copy(startMillis=cutoff-1),s().copy(startMillis=cutoff-1)).size)
        assertEquals(2,episodes(g().copy(elapsedSeconds=20,recordedSeconds=1),s().copy(recordedSeconds=20,startMillis=t+9000)).size)
        assertEquals(2,CanonicalFitness.episodes(listOf(g(),s())).size)
    }
}
