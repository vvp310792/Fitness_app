package com.fitnessapp.summary.sync

import com.fitnessapp.summary.analytics.FitnessSource
import com.fitnessapp.summary.analytics.FitnessSport
import com.fitnessapp.summary.analytics.LocalWorkoutArchive
import com.fitnessapp.summary.data.GarminActivity
import com.fitnessapp.summary.data.GarminDailyExtra
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ServerFitnessProjectionTest {
    private val date="2026-11-25"
    private val day=LocalDate.parse(date).toEpochDay()
    private val start=Instant.parse("2026-11-25T10:00:00Z").toEpochMilli()

    private fun row(source:String,kind:String,id:String,json:String):VerifiedServerRow =
        VerifiedServerRow(source,kind,id,date,JSONObject().put("id",id).put("day",date).put("data",JSONObject(json)).toString())

    private fun activity(id:String="42",gmt:String="2026-11-25 10:00:00") =
        row("garmin","activity",id,"""{"activityId":42,"startTimeGMT":"$gmt","activityType":{"typeKey":"running"},
            "activityName":"Run","duration":1700,"elapsedDuration":1800,"distance":5000,"averageHR":145,"maxHR":170,
            "hrTimeInZone_2":900}""")

    @Test fun mapsNestedGarminDailyWithoutSumming() {
        val d=ServerFitnessProjection.daily(row("garmin","daily",date,
            """{"movement":{"steps":{"value":10000,"distanceInMeters":7000}},"calories":{"burnedActive":420,"burnedTotal":1800},
            "heartRate":{"restingValue":52},"stress":{"avgLevel":25,"restDurationInMillis":3600000},
            "intensityMinutes":{"moderate":22,"vigorous":10}}"""))!!
        assertEquals(day,d.dateEpochDay)
        assertEquals(10000L,d.totalSteps)
        assertEquals(420,d.activeKilocalories)
        assertEquals(52,d.restingHeartRate)
        assertEquals(3600,d.restStressSeconds)
        assertEquals(42,d.intensityMinutesWeighted)
        val l=GarminDailyExtra(dateEpochDay=day,totalSteps=12000,bodyBatteryAtWake=75)
        val merged=UnifiedFitnessRead.fillDay(l,d)
        assertEquals(12000L,merged.totalSteps)
        assertEquals(75,merged.bodyBatteryAtWake)
        assertEquals(52,merged.restingHeartRate)
    }

    @Test fun sleepDetailAndSummaryUseSecondsAndStaySeparate() {
        val detail=ServerFitnessProjection.sleep(row("garmin","sleep_detail",date,
            """{"dailySleepDTO":{"sleepTimeSeconds":25200,"deepSleepSeconds":3600,"remSleepSeconds":5000,
                "lightSleepSeconds":16600,"awakeSleepSeconds":1200}}"""))!!
        val summary=ServerFitnessProjection.sleep(row("garmin","sleep_summary",date,
            """{"values":{"totalSleepTimeInSeconds":24000,"deepTime":3000,"remTime":4000,"lightTime":17000}}"""))!!
        assertEquals(420,detail.sleepMinutes)
        assertEquals(400,summary.sleepMinutes)
        assertEquals(3600,detail.deepSeconds)
        assertEquals(0,summary.score)
    }

    @Test fun mapsGarminElapsedAndDeduplicatesExactLocalId() {
        val p=ServerFitnessProjection.workouts(listOf(activity()))
        assertEquals(0,p.rejected)
        val observation=p.observations.single()
        assertEquals(start,observation.session.startMillis)
        assertEquals(1800L,observation.session.elapsedSeconds)
        assertEquals(1700L,observation.trainingSeconds)
        assertEquals(FitnessSport.RUN,observation.session.sport)
        val local=GarminActivity(activityId=42,dateEpochDay=day,startTimeMillis=start,
            name="Local details",typeKey="running",durationSeconds=1700,distanceMeters=5000,
            zone2Seconds=1000,zonesLoaded=true)
        val archive=LocalWorkoutArchive.build(emptyList(),listOf(local),emptyList(),emptyList(),p)
        assertEquals(1,archive.episodes.size)
        assertEquals(1800L,archive.episodes.single().primary.session.elapsedSeconds)
        assertEquals(1000,archive.episodes.single().garmin!!.zone2Seconds)
        assertEquals(1,archive.serverMappedCount)
    }

    @Test fun keepsPossibleGoogleGarminDuplicateOutOfConfirmedTotals() {
        val p=ServerFitnessProjection.workouts(listOf(activity(),row("google_health","exercise","google-1",
            """{"exercise":{"displayName":"Run","exerciseType":"RUNNING","interval":{
            "startTime":"2026-11-25T10:00:30Z","endTime":"2026-11-25T10:30:30Z"},
            "metricsSummary":{"averageHeartRateBeatsPerMinute":"146"}}}""")))
        assertEquals(2,p.observations.size)
        val a=LocalWorkoutArchive.build(emptyList(),emptyList(),emptyList(),emptyList(),p)
        assertEquals(2,a.episodes.size)
        assertTrue(a.episodes.all { it.episode.ambiguous })
        assertEquals(0,a.episodes.count { !it.episode.ambiguous })
    }

    @Test fun mapsGoogleDistanceAndActiveDurationAndConfirmsMatchingExercise() {
        val p=ServerFitnessProjection.workouts(listOf(activity(),row("google_health","exercise","google-2",
            """{"exercise":{"displayName":"Run","exerciseType":"RUNNING","activeDuration":"1600s","interval":{
            "startTime":"2026-11-25T10:00:30Z","endTime":"2026-11-25T10:30:30Z"},
            "metricsSummary":{"distanceMillimeters":5000000}}}""")))
        val g=p.observations.single { it.session.source==FitnessSource.GOOGLE_HEALTH }
        assertEquals(5000.0,g.session.distanceMeters!!,0.001)
        assertEquals(1600L,g.trainingSeconds)
        val a=LocalWorkoutArchive.build(emptyList(),emptyList(),emptyList(),emptyList(),p)
        assertEquals(1,a.episodes.size)
        assertFalse(a.episodes.single().episode.ambiguous)
        assertEquals(2,a.episodes.single().observations.size)
    }

    @Test fun gymUpUsesPerformedSetsAndContinuousNumbering() {
        val p=ServerFitnessProjection.workouts(listOf(row("gymup","training",start.toString(),
            """{"training":{"_id":1,"startDateTime":$start,"finishDateTime":${start+1800000}},
            "workouts":[
             {"_id":10,"th_exercise_id":987654,"order_num":1,"exerciseName":"Custom press",
              "sets":[{"_id":100,"weight":30,"reps":8},{"_id":101,"weight":0,"reps":0}]},
             {"_id":11,"th_exercise_id":987654,"order_num":2,"exerciseName":"Custom press",
              "sets":[{"_id":102,"weight":32.5,"reps":6}]}]}""")))
        assertEquals(0,p.rejected)
        val a=p.observations.single()
        assertEquals(FitnessSource.GYMUP,a.session.source)
        assertEquals(listOf(1,2),a.sets.map { it.setIndex })
        assertEquals(listOf(8,6),a.sets.map { it.reps })
        assertEquals(32.5f,a.sets.last().weightKg,0.001f)
        assertEquals(1800L,a.session.elapsedSeconds)
        assertEquals(day,a.sets.first().dateEpochDay)
    }

    @Test fun emptyPlansAndMalformedIdentitiesDoNotBecomeSessions() {
        val p=ServerFitnessProjection.workouts(listOf(
            row("gymup","training",start.toString(),"""{"training":{"_id":1,"startDateTime":$start},"workouts":[]}"""),
            activity(id="43"),
            row("google_health","exercise","bad","""{"exercise":{"interval":{"startTime":"invalid"}}}""")
        ))
        assertTrue(p.observations.isEmpty())
        assertEquals(3,p.rawCount)
        assertEquals(1,p.emptyGymSessions)
        assertEquals(2,p.rejected)
    }

    @Test fun contradictoryNativeIdentityIsFlaggedAndNotOverwritten() {
        val p=ServerFitnessProjection.workouts(listOf(activity()))
        val local=GarminActivity(activityId=42,dateEpochDay=day,startTimeMillis=start-3600000,
            typeKey="running",durationSeconds=1700)
        val a=LocalWorkoutArchive.build(emptyList(),listOf(local),emptyList(),emptyList(),p)
        assertEquals(1,a.episodes.size)
        assertEquals(1,a.serverIdentityConflicts)
        assertEquals(start-3600000,a.episodes.single().primary.session.startMillis)
    }
}
