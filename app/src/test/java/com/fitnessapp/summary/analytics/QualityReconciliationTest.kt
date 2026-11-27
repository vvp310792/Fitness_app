package com.fitnessapp.summary.analytics

import com.fitnessapp.summary.sync.ServerWorkoutProjection
import org.junit.Assert.*
import org.junit.Test

class QualityReconciliationTest {
    private val t=1700000000000L
    private val day=19675L
    private fun watch(id:String="watch",offset:Long=0,seconds:Long=2059)=
        FitnessSession(FitnessSource.GARMIN,id,FitnessSport.STRENGTH,t+offset,seconds,recordedSeconds=seconds)
    private fun journal(offset:Long=120564,seconds:Long=34639)=
        FitnessSession(FitnessSource.GYMUP,"journal",FitnessSport.STRENGTH,t+offset,seconds,recordedSeconds=seconds)
    private fun fit(offset:Long=120564,seconds:Long=3600)=
        FitnessSession(FitnessSource.GOOGLE_HEALTH,"fit",FitnessSport.STRENGTH,t+offset,seconds,
            transportOrigin="com.google.android.apps.fitness",recordedSeconds=seconds)

    @Test fun longerFitAndUnclosedJournalAttachToSameWatch() {
        val w=watch()
        val j=journal()
        val f=fit()
        val gh=w.copy(source=FitnessSource.GOOGLE_HEALTH,nativeId="watch-copy",
            transportOrigin="com.garmin.android.apps.connectmobile")
        val hc=w.copy(source=FitnessSource.HEALTH_CONNECT,nativeId="hc")
        val e=CanonicalFitness.episodes(listOf(j,f,hc,w,gh)).single()
        assertEquals(w.key,e.primary.key)
        assertEquals(5,e.observations.size)
        assertEquals(setOf(j.key,f.key),e.fragmentKeys.toSet())
        assertFalse(e.ambiguous)
        assertEquals(listOf(e),CanonicalFitness.episodes(listOf(gh,w,hc,f,j)))
    }

    @Test fun exactJournalFitStandaloneWithLongOpenLogHasNoTrustedDuration() {
        val j=journal(seconds=87589)
        val f=fit()
        val input=listOf(
            ArchiveObservation(j,day,"Log",87589,null,null),
            ArchiveObservation(f,day,"Fit",3600,null,null))
        val result=LocalWorkoutArchive.build(emptyList(),emptyList(),emptyList(),emptyList(),
            ServerWorkoutProjection(input,2))
        val e=result.episodes.single()
        assertEquals(f.key,e.primary.session.key)
        assertEquals(listOf(j.key),e.episode.fragmentKeys)
        assertEquals(2,e.observations.size)
        assertNull(e.durationSeconds)
    }

    @Test fun twoPlausibleWatchesKeepExportPairTogetherButRequireWatchReview() {
        val a=watch("a")
        val b=watch("b",offset=300000)
        val j=journal()
        val f=fit()
        val groups=CanonicalFitness.episodes(listOf(a,b,j,f))
        assertEquals(3,groups.size)
        assertTrue(groups.all { it.ambiguous })
        val exported=groups.single { it.observations.size==2 }
        assertEquals(setOf(j.key,f.key),exported.observations.map { it.key }.toSet())
        assertEquals(setOf(a.key,b.key),exported.possibleDuplicateKeys.toSet())
    }

    @Test fun longOpenJournalAloneDoesNotContributeWallClockToTotals() {
        val j=journal(seconds=90000)
        val result=LocalWorkoutArchive.build(emptyList(),emptyList(),emptyList(),emptyList(),
            ServerWorkoutProjection(listOf(ArchiveObservation(j,day,"Log",90000,null,null)),1))
        assertNull(result.episodes.single().durationSeconds)
        assertEquals(1,result.episodes.size)
    }

    @Test fun differentStartsDoNotProveLongFitIsAnExportOfJournal() {
        val a=watch()
        val j=journal()
        val f=fit(offset=420000)
        val groups=CanonicalFitness.episodes(listOf(a,j,f))
        assertEquals(2,groups.size)
        assertTrue(groups.any { it.primary.key==f.key })
    }
    @Test fun exactExportStartCorroboratesWatchOpenedSeveralMinutesEarlier() {
        // Synthetic fixture: about 73% of a shorter, measured watch interval.
        val a=watch(seconds=1700)
        val j=journal(offset=450000,seconds=87500)
        val f=fit(offset=450000)
        val e=CanonicalFitness.episodes(listOf(j,f,a)).single()
        assertEquals(a.key,e.primary.key)
        assertEquals(setOf(j.key,f.key),e.fragmentKeys.toSet())
        assertEquals(3,e.observations.size)
        assertFalse(e.ambiguous)
    }

    @Test fun staleJournalTailCannotLinkToNextDayWorkout() {
        val j=journal(offset=0,seconds=175000)
        val a=watch(offset=86400000,seconds=2000)
        val e=CanonicalFitness.episodes(listOf(a,j))
        assertEquals(2,e.size)
        assertTrue(e.none { it.ambiguous })
    }

    @Test fun allSourceKeysAreAccountedForOnceIncludingUnresolvedRecords() {
        val a=watch("watch-a",seconds=1800)
        val j=journal(seconds=90000)
        val f=fit()
        val b=watch("watch-b",offset=300000,seconds=1800)
        val c=watch("next-day",offset=86400000,seconds=1700)
        val input=listOf(a,j,f,b,c)
        val result=CanonicalFitness.episodes(input)
        val accounted=result.flatMap { it.observations }.map { it.key }
        assertEquals(input.size,accounted.size)
        assertEquals(input.map { it.key }.toSet(),accounted.toSet())
        assertEquals(result,CanonicalFitness.episodes(input.reversed()))
        assertTrue(result.all { it.primary.key !in it.fragmentKeys })
    }

}
