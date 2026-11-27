package com.fitnessapp.summary.sync

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.fitnessapp.summary.analytics.CanonicalFitness
import com.fitnessapp.summary.analytics.FitnessSession
import com.fitnessapp.summary.analytics.FitnessSource
import com.fitnessapp.summary.analytics.FitnessSport
import com.fitnessapp.summary.analytics.LocalWorkoutArchive
import com.fitnessapp.summary.analytics.WorkoutPeriod
import com.fitnessapp.summary.data.Workout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class CanonicalWorkoutMergeTest {
    private val date="2026-01-10"
    private val day=LocalDate.parse(date).toEpochDay()
    private val start=Instant.parse("2026-01-10T08:00:00Z").toEpochMilli()
    private fun row(source:String,kind:String,id:String,body:String)=
        VerifiedServerRow(source,kind,id,date,JSONObject().put("id",id).put("day",date)
            .put("data",JSONObject(body)).toString())

    @Test fun timerAndElapsedMismatchDoesNotSplitThreeSwimmingCopies() {
        // Synthetic regression: transport omits distance and reports timer time,
        // whereas the original watch has a longer elapsed time including pauses.
        val g=row("garmin","activity","9001",
            """{"activityId":9001,"startTimeGMT":"2026-01-10 08:00:00","activityType":{"typeKey":"lap_swimming"},"duration":2485.75,"elapsedDuration":4900.5,"distance":200}""")
        val gh=row("google_health","exercise","transport-copy",
            """{"dataSource":{"application":{"packageName":"com.garmin.android.apps.connectmobile"}},"exercise":{"exerciseType":"SWIMMING","activeDuration":"2485.75s","interval":{"startTime":"2026-01-10T08:00:00Z","endTime":"2026-01-10T08:41:25.75Z"},"metricsSummary":{"averageHeartRateBeatsPerMinute":90}}}""")
        val hc=Workout("health-copy",day,start,start+2485750,
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,durationMinutes=41,
            distanceMeters=200,avgHeartRate=90)
        val projection=ServerFitnessProjection.workouts(listOf(g,gh))
        assertEquals(2,projection.observations.size)
        val result=LocalWorkoutArchive.build(listOf(hc),emptyList(),emptyList(),emptyList(),projection)
        val episode=result.episodes.single()
        assertFalse(episode.episode.ambiguous)
        assertEquals(3,episode.observations.size)
        assertEquals(2,result.confirmedDuplicateObservations)
        assertEquals(FitnessSource.GARMIN,episode.primary.session.source)
        assertEquals(2485L,episode.durationSeconds)
        assertEquals(200.0,episode.distanceMeters!!,0.001)
        assertEquals(90,episode.averageHr)
        assertEquals(1,WorkoutPeriod.between(result,day,day).confirmed.size)
    }

    @Test fun weakExternalLinkDoesNotExplodeConfirmedCopyGroup() {
        val a=FitnessSession(FitnessSource.GARMIN,"original",FitnessSport.SWIM,start,4900,200.0,recordedSeconds=2485)
        val b=FitnessSession(FitnessSource.GOOGLE_HEALTH,"copy",FitnessSport.SWIM,start,2485,
            transportOrigin="com.garmin.android.apps.connectmobile",recordedSeconds=2485)
        val c=FitnessSession(FitnessSource.HEALTH_CONNECT,"nearby",FitnessSport.SWIM,start+60000,400,null,recordedSeconds=400)
        val groups=CanonicalFitness.episodes(listOf(a,b,c))
        assertEquals(2,groups.size)
        val group=groups.single { it.observations.size==2 }
        assertTrue(group.ambiguous)
        assertEquals(listOf(c.key),group.possibleDuplicateKeys)
        assertEquals(3,groups.sumOf { it.observations.size })
    }

    @Test fun exactStartDoesNotOverrideConflictingDistanceOrSport() {
        val a=FitnessSession(FitnessSource.GARMIN,"a",FitnessSport.RUN,start,4900,4000.0,recordedSeconds=2485)
        val b=FitnessSession(FitnessSource.GOOGLE_HEALTH,"b",FitnessSport.RUN,start,2485,1200.0,recordedSeconds=2485)
        assertEquals(2,CanonicalFitness.episodes(listOf(a,b)).size)
        val c=b.copy(sport=FitnessSport.CYCLE)
        assertEquals(2,CanonicalFitness.episodes(listOf(a,c)).size)
    }
}
