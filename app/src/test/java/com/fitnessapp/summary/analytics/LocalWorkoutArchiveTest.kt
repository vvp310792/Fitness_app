package com.fitnessapp.summary.analytics

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.fitnessapp.summary.data.GarminActivity
import com.fitnessapp.summary.data.StravaActivity
import com.fitnessapp.summary.data.StrengthSet
import com.fitnessapp.summary.data.Workout
import org.junit.Assert.*
import org.junit.Test

class LocalWorkoutArchiveTest {
    private val t=1_700_000_000_000L
    private val d=19675L
    @Test fun archiveDoesNotTruncateAtTwoHundred() {
        val rows=(0..250).map { i -> GarminActivity(i.toLong(),d,t+i*3600000L,typeKey="running",durationSeconds=1800,distanceMeters=5000) }
        val result=LocalWorkoutArchive.build(emptyList(),rows,emptyList(),emptyList())
        assertEquals(251,result.episodes.size)
        assertTrue(result.episodes.none { it.episode.ambiguous })
    }
    @Test fun movingTimeIsNotElapsedTime() {
        val g=GarminActivity(1,d,t,typeKey="running",durationSeconds=1800,distanceMeters=5000)
        val s=StravaActivity(1,d,t,typeRaw="Бег",durationSeconds=1800,distanceMeters=5000)
        val result=LocalWorkoutArchive.build(emptyList(),listOf(g),listOf(s),emptyList())
        assertEquals(2,result.episodes.size)
        assertTrue(result.episodes.all { it.episode.ambiguous })
        assertTrue(result.episodes.all { it.primary.session.elapsedSeconds==null })
    }
    @Test fun differentSportsRemainDistinct() {
        val g=GarminActivity(1,d,t,typeKey="running",durationSeconds=1800,distanceMeters=5000)
        val s=StravaActivity(1,d,t,typeRaw="Велосипед",durationSeconds=1800,distanceMeters=5000)
        val result=LocalWorkoutArchive.build(emptyList(),listOf(g),listOf(s),emptyList())
        assertEquals(2,result.episodes.size)
        assertTrue(result.episodes.none { it.episode.ambiguous })
    }
    @Test fun strengthSetsAreOneSessionWithoutInventedDuration() {
        val a=StrengthSet(t,d,"Жим","bench",1,60f,8)
        val result=LocalWorkoutArchive.build(emptyList(),emptyList(),emptyList(),listOf(a,a.copy(setIndex=2)))
        val e=result.episodes.single()
        assertEquals(2,e.sets.size)
        assertNull(e.durationSeconds)
        assertEquals(FitnessSport.STRENGTH,e.primary.session.sport)
    }
    @Test fun missingDistanceRemainsUnknown() {
        val row=Workout("h",d,t,t+1800000,ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,durationMinutes=30)
        val e=LocalWorkoutArchive.build(listOf(row),emptyList(),emptyList(),emptyList()).episodes.single()
        assertNull(e.distanceMeters)
        assertEquals(1800L,e.durationSeconds)
    }
}
