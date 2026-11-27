package com.fitnessapp.summary.analytics

import com.fitnessapp.summary.data.StrengthSet
import com.fitnessapp.summary.sync.ServerWorkoutProjection
import org.junit.Assert.*
import org.junit.Test

class GeneralWorkoutReconciliationTest {
    private val t=1700000000000L
    private val day=19675L
    private fun watch(id:String="watch",offset:Long=0,seconds:Long=1800,sport:FitnessSport=FitnessSport.STRENGTH)=
        FitnessSession(FitnessSource.GARMIN,id,sport,t+offset,seconds,recordedSeconds=seconds)
    private fun log(id:String="journal",offset:Long=-600000,seconds:Long=3000)=
        FitnessSession(FitnessSource.GYMUP,id,FitnessSport.STRENGTH,t+offset,seconds,recordedSeconds=seconds)

    @Test fun longerJournalAndFullTransportCopiesBecomeOneWorkout() {
        val a=watch()
        val gh=a.copy(source=FitnessSource.GOOGLE_HEALTH,nativeId="gh",
            transportOrigin="com.garmin.android.apps.connectmobile")
        val hc=a.copy(source=FitnessSource.HEALTH_CONNECT,nativeId="hc")
        val j=log()
        val result=CanonicalFitness.episodes(listOf(j,hc,gh,a))
        val e=result.single()
        assertFalse(e.ambiguous)
        assertEquals(a.key,e.primary.key)
        assertEquals(4,e.observations.size)
        assertEquals(listOf(j.key),e.fragmentKeys)
        assertEquals(result,CanonicalFitness.episodes(listOf(a,gh,hc,j)))
    }

    @Test fun journalKeepsSetsButNotWallClockDuration() {
        val a=watch()
        val j=log()
        val set=StrengthSet(j.startMillis,day,"Bench press","bench",1,60f,8)
        val observations=listOf(
            ArchiveObservation(a,day,"Strength",1800,100,140),
            ArchiveObservation(j,day,"Journal",3000,null,null,sets=listOf(set)))
        val result=LocalWorkoutArchive.build(emptyList(),emptyList(),emptyList(),emptyList(),
            ServerWorkoutProjection(observations,2)).episodes.single()
        assertEquals(1800L,result.durationSeconds)
        assertEquals(100,result.averageHr)
        assertEquals(listOf(set),result.sets)
        assertEquals(listOf(j.key),result.episode.fragmentKeys)
    }

    @Test fun twoCompetingJournalsRemainReviewable() {
        val a=watch()
        val j=log("one",0,1700)
        val k=log("two",60000,1600)
        val result=CanonicalFitness.episodes(listOf(j,k,a))
        assertEquals(3,result.size)
        assertTrue(result.all { it.ambiguous })
        assertTrue(result.all { it.fragmentKeys.isEmpty() })
    }

    @Test fun healthConnectPartialCanBeNestedToo() {
        val a=watch(sport=FitnessSport.RUN)
        val h=FitnessSession(FitnessSource.HEALTH_CONNECT,"partial",FitnessSport.RUN,
            t+480000,900,1000.0,recordedSeconds=900)
        val e=CanonicalFitness.episodes(listOf(a,h)).single()
        assertFalse(e.ambiguous)
        assertEquals(listOf(h.key),e.fragmentKeys)
    }

    @Test fun nestedJournalCannotExtendDeviceEvidence() {
        val a=watch()
        val j=log(offset=-1200000,seconds=3600)
        val f=FitnessSession(FitnessSource.GOOGLE_HEALTH,"earlier",FitnessSport.STRENGTH,
            t-900000,300,transportOrigin="com.google.android.apps.fitness",recordedSeconds=300)
        val result=CanonicalFitness.episodes(listOf(a,j,f))
        assertEquals(2,result.size)
        assertTrue(result.any { it.primary.key==f.key })
        assertTrue(result.any { it.observations.map { x->x.key }.toSet()==setOf(a.key,j.key) })
    }

    @Test fun fullHealthConnectCopyOfStandaloneJournalKeepsMeasuredHeartRate() {
        val j=log(offset=0,seconds=1800)
        val h=FitnessSession(FitnessSource.HEALTH_CONNECT,"hc",FitnessSport.STRENGTH,t,1800,recordedSeconds=1800)
        val e=CanonicalFitness.episodes(listOf(j,h)).single()
        assertFalse(e.ambiguous)
        assertTrue(e.fragmentKeys.isEmpty())
        assertEquals(2,e.observations.size)
    }
}
