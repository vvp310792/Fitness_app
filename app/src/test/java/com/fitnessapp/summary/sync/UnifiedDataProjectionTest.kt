package com.fitnessapp.summary.sync

import com.fitnessapp.summary.analytics.LocalWorkoutArchive
import com.fitnessapp.summary.analytics.WorkoutPeriod
import com.fitnessapp.summary.data.DailySummary
import com.fitnessapp.summary.data.GarminBodyComposition
import com.fitnessapp.summary.data.ScaleMeasurement
import com.fitnessapp.summary.data.GarminActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class UnifiedDataProjectionTest {
    private val date="2026-11-26"
    private val day=LocalDate.parse(date).toEpochDay()
    private fun row(kind:String,id:String,payload:String,source:String="google_health",date:String=this.date) =
        VerifiedServerRow(source,kind,id,date,JSONObject().put("id",id).put("day",date).put("data",JSONObject(payload)).toString())
    private fun step(id:String,app:String,start:String,end:String,count:Int) = row("steps",id,
        """{"dataSource":{"application":{"packageName":"$app"}},"steps":{"count":"$count","interval":{"startTime":"$start","endTime":"$end","startUtcOffset":"25200s","endUtcOffset":"25200s"}}}""")

    @Test fun overlappingAndTransportedStepsNeverSumTwice() {
        val garmin="com.garmin.android.apps.connectmobile"
        val a=step("a",garmin,"2026-11-26T00:00:00Z","2026-11-26T01:00:00Z",1000)
        val rows=listOf(a,a.copy(nativeId="copy"),
            step("b",garmin,"2026-11-26T00:15:00Z","2026-11-26T00:45:00Z",500),
            step("c",garmin,"2026-11-26T01:00:00Z","2026-11-26T02:00:00Z",800),
            step("d","com.google.android.apps.fitness","2026-11-26T00:00:00Z","2026-11-26T02:00:00Z",1800))
        val result=GoogleHealthProjection.days(rows).single()
        assertEquals(1800L,result.summary.steps)
        assertEquals(garmin,result.metricSources["steps"])
    }
    @Test fun realUnitsAndWakeDayArePreserved() {
        val rows=listOf(
            row("distance","d","""{"distance":{"millimeters":"1234500","interval":{"startTime":"2026-11-26T00:00:00Z","endTime":"2026-11-26T01:00:00Z","startUtcOffset":"25200s","endUtcOffset":"25200s"}}}"""),
            row("floors","f","""{"civilStartTime":{"date":{"year":2026,"month":11,"day":26}},"floors":{"countSum":"12.5"}}"""),
            row("total-calories","c","""{"civilStartTime":{"date":{"year":2026,"month":11,"day":26}},"totalCalories":{"kcalSum":2100}}"""),
            row("heart-rate","h","""{"heartRate":{"beatsPerMinute":"60","sampleTime":{"physicalTime":"2026-11-26T00:00:00Z","utcOffset":"25200s"}}}"""),
            row("sleep","s","""{"sleep":{"interval":{"startTime":"2026-11-25T16:00:00Z","endTime":"2026-11-26T00:00:00Z","startUtcOffset":"25200s","endUtcOffset":"25200s"},"metadata":{"mainSleep":true},"summary":{"minutesAsleep":"440","minutesAwake":"40","stagesSummary":[{"type":"DEEP","minutes":"80"},{"type":"LIGHT","minutes":"240"},{"type":"REM","minutes":"120"}]}}}""",date="2026-11-25"))
        val r=GoogleHealthProjection.days(rows).single()
        assertEquals(day,r.summary.dateEpochDay)
        assertEquals(1235,r.summary.distanceMeters)
        assertEquals(12.5,r.floors!!,0.0001)
        assertEquals(2100,r.summary.totalCaloriesKcal)
        assertEquals(60,r.summary.avgHeartRate)
        assertEquals(440,r.summary.sleepTotalMinutes)
        assertEquals(80,r.summary.sleepDeepMinutes)
    }
    @Test fun localAndRemoteDailyValuesDoNotAddAndSleepStaysCoherent() {
        val local=DailySummary(day,steps=1500,sleepTotalMinutes=400,sleepDeepMinutes=75)
        val remote=DailySummary(day,steps=2000,distanceMeters=1100,sleepTotalMinutes=460,sleepRemMinutes=100)
        val r=GoogleHealthProjection.merge(local,remote)!!
        assertEquals(1500L,r.steps)
        assertEquals(1100,r.distanceMeters)
        assertEquals(400,r.sleepTotalMinutes)
        assertEquals(75,r.sleepDeepMinutes)
        assertEquals(0,r.sleepRemMinutes)
    }
    @Test fun bodyUsesPrimaryCoherentReadingRatherThanCombiningCopies() {
        val a=ScaleMeasurement(1700000000000L,day,70000,bodyFatPercent=20f,muscleMassGrams=50000)
        val g=GarminBodyComposition(day,weightGrams=70000,bodyWaterPercent=55f,muscleMassGrams=30000)
        val rows=listOf(BodyProjection.scale(a)!!,BodyProjection.scale(a)!!,BodyProjection.garmin(g)!!)
        val r=BodyProjection.choose(rows).single()
        assertEquals(BodyProjection.Z,r.source)
        assertEquals(70000,r.weightGrams)
        assertEquals(50000,r.muscleGrams)
        assertEquals(0f,r.waterPercent,0f)
    }
    @Test fun googleWeightAndHeightUseExplicitUnits() {
        val time="""{"physicalTime":"2026-11-26T00:00:00Z","utcOffset":"25200s"}"""
        val w=row("weight","w","""{"weight":{"weightGrams":70000,"sampleTime":$time}}""")
        val h=row("height","h","""{"height":{"heightMillimeters":"1750","sampleTime":$time}}""")
        assertEquals(70000,BodyProjection.readings(listOf(w)).single().weightGrams)
        assertEquals(175.0,GoogleHealthProjection.heights(listOf(h)).single().centimeters,0.0001)
    }
    @Test fun periodUsesTheSameConfirmedArchiveAndFiltersByDay() {
        val g=GarminActivity(1,day,1700000000000L,typeKey="running",durationSeconds=1800,distanceMeters=5000)
        val older=g.copy(activityId=2,dateEpochDay=day-1,startTimeMillis=g.startTimeMillis-86400000L)
        val archive=LocalWorkoutArchive.build(emptyList(),listOf(g,older),emptyList(),emptyList())
        val p=WorkoutPeriod.between(archive,day,day)
        assertEquals(1,p.confirmed.size)
        assertEquals(1800L,p.durationSeconds)
        assertEquals(5000.0,p.distanceMeters,0.0001)
        assertEquals(5000,p.sportSessions().single().meters)
        assertEquals(30,p.intensitySessions().single().minutes)
    }
}
