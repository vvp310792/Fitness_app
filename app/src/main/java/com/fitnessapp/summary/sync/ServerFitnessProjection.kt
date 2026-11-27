package com.fitnessapp.summary.sync

import com.fitnessapp.summary.analytics.ArchiveObservation
import com.fitnessapp.summary.analytics.FitnessSession
import com.fitnessapp.summary.analytics.FitnessSource
import com.fitnessapp.summary.analytics.FitnessSport
import com.fitnessapp.summary.analytics.LocalWorkoutArchive
import com.fitnessapp.summary.data.GarminActivity
import com.fitnessapp.summary.data.GarminDailyExtra
import com.fitnessapp.summary.data.GarminSleep
import com.fitnessapp.summary.strength.GymUpBackupParser
import com.fitnessapp.summary.util.garminSportName
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.roundToInt

/** Pure, read-only projection of verified server rows. No writes to legacy Room or cloud.
 * Unknown values are not inferred. Invalid/unsupported workout rows are counted. */
data class ServerWorkoutProjection(
    val observations: List<ArchiveObservation> = emptyList(),
    val rawCount: Int = 0,
    val rejected: Int = 0,
    val emptyGymSessions: Int = 0
)

object ServerFitnessProjection {
    private fun JSONObject.number(key: String): Double? =
        optDouble(key, Double.NaN).takeIf { it.isFinite() && it >= 0 }
    private fun JSONObject.positive(key: String): Double? = number(key)?.takeIf { it > 0 }
    private fun JSONObject.int(key: String): Int =
        number(key)?.takeIf { it <= Int.MAX_VALUE }?.roundToInt() ?: 0
    private fun JSONObject.long(key: String): Long =
        number(key)?.takeIf { it < Long.MAX_VALUE.toDouble() }?.toLong() ?: 0L
    private fun JSONObject.float(key: String): Float =
        number(key)?.toFloat()?.takeIf { it.isFinite() } ?: 0f
    private fun JSONObject.text(key: String): String =
        if (isNull(key)) "" else optString(key, "").trim()
    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun seconds(v: Double?): Long? = v?.toLong()?.takeIf { it in 1..604800 }
    private fun interval(start: Long, finish: Long): Long? =
        if (finish > start) ((finish-start)/1000).takeIf { it in 1..604800 } else null
    private fun instant(value: String): Long? = runCatching {
        Instant.parse(value).toEpochMilli()
    }.recoverCatching {
        LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli()
    }.getOrNull()?.takeIf { it > 0 }
    private fun utcInstant(value: String): Long? =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()?.takeIf { it > 0 }

    private fun data(row: VerifiedServerRow): JSONObject? = runCatching {
        JSONObject(row.rowJson).getJSONObject("data")
    }.getOrNull()
    private fun day(row: VerifiedServerRow): Long? =
        runCatching { LocalDate.parse(row.day).toEpochDay() }.getOrNull()

    fun daily(row: VerifiedServerRow): GarminDailyExtra? {
        if (row.source != "garmin" || row.kind != "daily") return null
        val d = data(row) ?: return null
        val date = day(row) ?: return null
        val movement = d.optJSONObject("movement") ?: JSONObject()
        val steps = movement.optJSONObject("steps") ?: JSONObject()
        val c = d.optJSONObject("calories") ?: JSONObject()
        val hr = d.optJSONObject("heartRate") ?: JSONObject()
        val stress = d.optJSONObject("stress") ?: JSONObject()
        val im = d.optJSONObject("intensityMinutes") ?: JSONObject()
        val result = GarminDailyExtra(
            dateEpochDay = date,
            totalSteps = steps.long("value"),
            totalDistanceMeters = steps.int("distanceInMeters"),
            totalKilocalories = c.int("burnedTotal"),
            activeKilocalories = c.int("burnedActive"),
            restingHeartRate = hr.int("restingValue"),
            minHeartRate = hr.int("minValue"),
            maxHeartRate = hr.int("maxValue"),
            averageStressLevel = stress.int("avgLevel").takeIf { it <= 100 } ?: 0,
            maxStressLevel = stress.int("maxLevel").takeIf { it <= 100 } ?: 0,
            stressQualifier = stress.text("qualifier"),
            restStressSeconds = (stress.long("restDurationInMillis")/1000).toInt(),
            lowStressSeconds = (stress.long("lowStressDurationInMillis")/1000).toInt(),
            mediumStressSeconds = (stress.long("mediumStressDurationInMillis")/1000).toInt(),
            highStressSeconds = (stress.long("highStressDurationInMillis")/1000).toInt(),
            floorsAscended = movement.optJSONObject("floorsAscended")?.int("value") ?: 0,
            floorsDescended = movement.optJSONObject("floorsDescended")?.int("value") ?: 0,
            moderateIntensityMinutes = im.int("moderate"),
            vigorousIntensityMinutes = im.int("vigorous"),
            updatedAtMillis = 0
        )
        return result.takeUnless { it.isEmpty }
    }

    fun sleep(row: VerifiedServerRow): GarminSleep? {
        if (row.source != "garmin" || row.kind !in setOf("sleep_detail", "sleep_summary")) return null
        val d = data(row) ?: return null
        val date = day(row) ?: return null
        val detail = row.kind == "sleep_detail"
        val s = d.optJSONObject(if (detail) "dailySleepDTO" else "values") ?: return null
        val total = s.int(if (detail) "sleepTimeSeconds" else "totalSleepTimeInSeconds")
        if (total !in 1..86400) return null
        return GarminSleep(
            dateEpochDay = date,
            sleepSeconds = total,
            deepSeconds = s.int(if (detail) "deepSleepSeconds" else "deepTime"),
            lightSeconds = s.int(if (detail) "lightSleepSeconds" else "lightTime"),
            remSeconds = s.int(if (detail) "remSleepSeconds" else "remTime"),
            awakeSeconds = s.int(if (detail) "awakeSleepSeconds" else "awakeTime"),
            napSeconds = if (detail) s.int("napTimeSeconds") else 0,
            unmeasurableSeconds = if (detail) s.int("unmeasurableSleepSeconds") else 0,
            sleepStartLocalMillis = s.long(if (detail) "sleepStartTimestampLocal" else "localSleepStartTimeInMillis"),
            sleepEndLocalMillis = s.long(if (detail) "sleepEndTimestampLocal" else "localSleepEndTimeInMillis"),
            updatedAtMillis = 0
        )
    }

    private fun garmin(row: VerifiedServerRow, d: JSONObject, day: Long): ArchiveObservation? {
        val id = row.nativeId.toLongOrNull()?.takeIf { it > 0 } ?: return null
        if (d.has("activityId") && d.long("activityId") != id) return null
        val start = instant(d.text("startTimeGMT")) ?: d.long("beginTimestamp").takeIf { it >= 100000000000L } ?: return null
        val type = d.optJSONObject("activityType")?.text("typeKey").orEmpty()
        val distance = d.positive("distance")
        val elapsed = seconds(d.positive("elapsedDuration"))
        val duration = seconds(d.positive("duration"))
        val g = GarminActivity(
            activityId = id, dateEpochDay = day, startTimeMillis = start,
            name = d.text("activityName"), typeKey = type,
            durationSeconds = duration?.toInt() ?: 0,
            distanceMeters = d.int("distance"), calories = d.int("calories"),
            avgHeartRate = d.int("averageHR"), maxHeartRate = d.int("maxHR"),
            steps = d.int("steps"), elevationGainMeters = d.int("elevationGain"),
            avgSpeedMetersPerSecond = d.float("averageSpeed"),
            aerobicTrainingEffect = d.float("aerobicTrainingEffect"),
            anaerobicTrainingEffect = d.float("anaerobicTrainingEffect"),
            activityTrainingLoad = d.float("activityTrainingLoad"),
            trainingEffectLabel = d.text("trainingEffectLabel"),
            zone1Seconds = d.int("hrTimeInZone_1"), zone2Seconds = d.int("hrTimeInZone_2"),
            zone3Seconds = d.int("hrTimeInZone_3"), zone4Seconds = d.int("hrTimeInZone_4"),
            zone5Seconds = d.int("hrTimeInZone_5"), updatedAtMillis = 0
        )
        return ArchiveObservation(
            FitnessSession(FitnessSource.GARMIN, id.toString(), LocalWorkoutArchive.garminSport(type), start, elapsed, distance),
            day, g.name.ifBlank { garminSportName(type) }, duration,
            g.avgHeartRate.takeIf { it > 0 }, g.maxHeartRate.takeIf { it > 0 }, garmin = g
        )
    }

    private fun gym(row: VerifiedServerRow, d: JSONObject, day: Long): ArchiveObservation? {
        val t = d.optJSONObject("training") ?: return null
        val start = t.long("startDateTime").takeIf { it >= 100000000000L } ?: return null
        if (row.nativeId != start.toString()) return null
        val id = t.long("_id")
        val blocks = d.optJSONArray("workouts").objects()
        val names = mutableMapOf<Long, String?>()
        val workouts = mutableListOf<GymUpBackupParser.WorkoutRow>()
        val sets = mutableListOf<GymUpBackupParser.SetRow>()
        val setMarks = mutableListOf<Long>()
        for (w in blocks) {
            val wid = w.long("_id")
            val eid = w.long("th_exercise_id")
            val name = w.text("exerciseName")
            if (name.isNotBlank()) names[eid] = name
            workouts.add(GymUpBackupParser.WorkoutRow(wid,id,eid,w.long("order_num")))
            for (set in w.optJSONArray("sets").objects()) {
                val reps = set.int("reps")
                val weight = set.number("weight")?.toFloat()?.takeIf { it.isFinite() }
                if (reps > 0 && weight != null && weight >= 0f) {
                    sets.add(GymUpBackupParser.SetRow(set.long("_id"),wid,weight,reps))
                    setMarks.add(set.long("finishDateTime"))
                }
            }
        }
        if (sets.isEmpty()) return null
        val parsed = GymUpBackupParser.build(
            listOf(GymUpBackupParser.TrainingRow(id,start)),workouts,sets,names,ZoneOffset.UTC
        ) as? GymUpBackupParser.Result.Ok ?: return null
        val duration = interval(start,t.long("finishDateTime"))
        return ArchiveObservation(
            FitnessSession(FitnessSource.GYMUP,start.toString(),FitnessSport.STRENGTH,start,duration,
                setMarksMillis=if(setMarks.all { it>=100000000000L }) setMarks.distinct().sorted() else emptyList()),
            day,"Силовая тренировка",duration,null,null,sets=parsed.sets.map { it.copy(dateEpochDay=day, updatedAtMillis=0) }
        )
    }

    private fun google(row: VerifiedServerRow, d: JSONObject, day: Long): ArchiveObservation? {
        val e = d.optJSONObject("exercise") ?: return null
        val i = e.optJSONObject("interval") ?: return null
        val start = utcInstant(i.text("startTime")) ?: return null
        val finish = utcInstant(i.text("endTime")) ?: return null
        val elapsed = interval(start, finish) ?: return null
        val type = e.text("exerciseType")
        val sport = when(type) {
            "RUNNING" -> FitnessSport.RUN
            "WALKING" -> FitnessSport.WALK
            "SWIMMING", "POOL_SWIMMING", "OPEN_WATER_SWIMMING" -> FitnessSport.SWIM
            "BIKING", "CYCLING" -> FitnessSport.CYCLE
            "STRENGTH_TRAINING", "WEIGHTLIFTING" -> FitnessSport.STRENGTH
            "" -> FitnessSport.UNKNOWN
            else -> FitnessSport.OTHER
        }
        val summary = e.optJSONObject("metricsSummary") ?: JSONObject()
        // Explicit exercise distance, not a daily total. Google Health stores mm.
        val distance=summary.positive("distanceMillimeters")?.div(1000.0)
        val active=e.text("activeDuration").takeIf { it.endsWith("s") }
            ?.removeSuffix("s")?.toDoubleOrNull()?.takeIf { it.isFinite() && it>0 }
            ?.toLong()?.takeIf { it in 1..elapsed }
        return ArchiveObservation(
            FitnessSession(FitnessSource.GOOGLE_HEALTH,row.nativeId,sport,start,elapsed,distance,
                transportOrigin=d.optJSONObject("dataSource")?.optJSONObject("application")
                    ?.text("packageName")?.takeIf { it.isNotBlank() }),
            day,e.text("displayName").ifBlank { type.ifBlank { "Тренировка" } },
            active, summary.int("averageHeartRateBeatsPerMinute").takeIf { it > 0 },
            summary.int("maxHeartRateBeatsPerMinute").takeIf { it > 0 }
        )
    }

    fun workouts(rows: List<VerifiedServerRow>): ServerWorkoutProjection {
        val result = mutableListOf<ArchiveObservation>()
        var rejected = 0
        var empty = 0
        for (row in rows) {
            val d = data(row)
            val date = day(row)
            if (d == null || date == null) { rejected++; continue }
            val observation = runCatching {
                when (row.source to row.kind) {
                    "garmin" to "activity" -> garmin(row,d,date)
                    "gymup" to "training" -> gym(row,d,date)
                    "google_health" to "exercise" -> google(row,d,date)
                    else -> null
                }
            }.getOrNull()
            if (observation != null) result.add(observation)
            else if (row.source == "gymup" && row.kind == "training" &&
                d.optJSONObject("training")?.long("startDateTime")?.toString() == row.nativeId &&
                d.optJSONArray("workouts").objects().all { w -> w.optJSONArray("sets").objects().none { it.int("reps") > 0 } }) empty++
            else rejected++
        }
        return ServerWorkoutProjection(result,rows.size,rejected,empty)
    }
}
