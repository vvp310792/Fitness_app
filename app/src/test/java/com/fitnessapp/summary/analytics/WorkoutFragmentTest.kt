package com.fitnessapp.summary.analytics

import com.fitnessapp.summary.sync.ServerWorkoutProjection
import org.junit.Assert.*
import org.junit.Test

class WorkoutFragmentTest {
    private val start=1700000000000L
    private val day=19675L
    private fun anchor(id:String="watch",offset:Long=0,elapsed:Long=1800,active:Long=elapsed,
        sport:FitnessSport=FitnessSport.STRENGTH,distance:Double?=null) =
        FitnessSession(FitnessSource.GARMIN,id,sport,start+offset,elapsed,distance,recordedSeconds=active)

    private fun fit(id:String="fit",offset:Long=480000,elapsed:Long=900,
        sport:FitnessSport=FitnessSport.STRENGTH,distance:Double?=null) =
        FitnessSession(FitnessSource.GOOGLE_HEALTH,id,sport,start+offset,elapsed,distance,
            transportOrigin="com.google.android.apps.fitness",recordedSeconds=elapsed)

    @Test fun nestedFitIntervalJoinsAlreadyReconciledWatchAndTransportCopies() {
        val a=anchor()
        val gh=a.copy(source=FitnessSource.GOOGLE_HEALTH,nativeId="watch-copy",
            transportOrigin="com.garmin.android.apps.connectmobile")
        val hc=a.copy(source=FitnessSource.HEALTH_CONNECT,nativeId="hc")
        val f=fit()
        val result=CanonicalFitness.episodes(listOf(f,hc,gh,a))
        val e=result.single()
        assertEquals(a.key,e.primary.key)
        assertFalse(e.ambiguous)
        assertEquals(4,e.observations.size)
        assertEquals(listOf(f.key),e.fragmentKeys)
    }

    @Test fun nestedSameSportFragmentsDoNotFillOrInflateFullSessionMetrics() {
        val a=anchor(sport=FitnessSport.RUN)
        val f=fit(sport=FitnessSport.RUN,distance=1300.0)
        val observations=listOf(
            ArchiveObservation(a,day,"Run",1800,null,null),
            ArchiveObservation(f,day,"Partial run",900,160,180))
        val archive=LocalWorkoutArchive.build(emptyList(),emptyList(),emptyList(),emptyList(),
            ServerWorkoutProjection(observations,2))
        val result=archive.episodes.single()
        assertFalse(result.episode.ambiguous)
        assertEquals(1800L,result.durationSeconds)
        assertNull(result.distanceMeters)
        assertNull(result.averageHr)
        assertNull(result.maxHr)
        assertEquals(1,WorkoutPeriod.between(archive,day,day).confirmed.size)
        assertEquals(1,archive.confirmedDuplicateObservations)
    }

    @Test fun multiplePartialRecordsAttachWithoutBecomingAnchors() {
        val a=anchor(elapsed=2400)
        val f=fit("first",offset=300000,elapsed=600)
        val f2=fit("second",offset=1200000,elapsed=600)
        val e=CanonicalFitness.episodes(listOf(f2,f,a)).single()
        assertEquals(a.key,e.primary.key)
        assertFalse(e.ambiguous)
        assertEquals(listOf(f.key,f2.key).sorted(),e.fragmentKeys)
        assertEquals(3,e.observations.size)
    }

    @Test fun differentSportOrSmallOverlapDoesNotMerge() {
        val a=anchor()
        assertEquals(2,CanonicalFitness.episodes(listOf(a,fit(sport=FitnessSport.WALK))).size)
        assertEquals(2,CanonicalFitness.episodes(listOf(a,fit(offset=1700000))).size)
    }

    @Test fun competingAnchorsAreNotArbitrarilyMerged() {
        val a=anchor("a")
        val b=anchor("b",offset=120000)
        val f=fit()
        val episodes=CanonicalFitness.episodes(listOf(a,b,f))
        assertEquals(3,episodes.size)
        assertTrue(episodes.all { it.ambiguous })
        assertTrue(episodes.all { it.fragmentKeys.isEmpty() })
    }

    @Test fun longPauseAndConflictingDistanceRequireReview() {
        val a=anchor(elapsed=3000,active=1200)
        val f=fit()
        val paused=CanonicalFitness.episodes(listOf(a,f))
        assertEquals(2,paused.size)
        assertTrue(paused.all { it.ambiguous })
        val run=anchor(sport=FitnessSport.RUN,distance=2000.0)
        val longer=fit(sport=FitnessSport.RUN,distance=4000.0)
        val conflicting=CanonicalFitness.episodes(listOf(run,longer))
        assertEquals(2,conflicting.size)
        assertTrue(conflicting.all { it.ambiguous })
    }
}
