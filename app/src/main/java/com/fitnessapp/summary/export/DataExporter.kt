package com.fitnessapp.summary.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.fitnessapp.summary.analytics.StrengthLift
import com.fitnessapp.summary.data.AppDatabase
import com.fitnessapp.summary.data.SummaryRepository
import com.fitnessapp.summary.data.WorkoutRepository
import com.fitnessapp.summary.util.exerciseName
import com.fitnessapp.summary.util.garminSportName
import com.fitnessapp.summary.util.hrvStatusLabel
import com.fitnessapp.summary.util.qualifierLabel
import com.fitnessapp.summary.util.readinessLevelLabel
import com.fitnessapp.summary.util.trainingStatusLabel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Dumps everything the app holds to a single JSON file the user can share out.
 *
 * Two things it deliberately does that a naive dump wouldn't:
 * - dates are written as ISO strings *alongside* the raw epoch day, so the file is
 *   readable by a human (or an LLM being asked to analyse it) without needing to
 *   know that 20338 means 2025-09-07;
 * - enum-ish keys (exercise type, Garmin qualifiers/statuses) get their resolved
 *   Russian label next to the raw value, for the same reason.
 *
 * Room is the source, not Health Connect or Garmin - so an export contains the full
 * stored history even for days the upstream has since pruned. The Garmin sections are
 * present (possibly empty) for every install; a reader can tell "no Garmin login" from
 * "Garmin had nothing" by whether `garmin.days` has any rows at all.
 */
object DataExporter {

    private val ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE

    suspend fun export(
        context: Context,
        summaryRepository: SummaryRepository,
        workoutRepository: WorkoutRepository,
        database: AppDatabase
    ): File {
        val days = summaryRepository.getAllOnce()
        val workouts = workoutRepository.getAllOnce()

        val root = JSONObject().apply {
            put("app", "fitness-summary")
            put("schemaVersion", 2)
            put("exportedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
            put("sources", JSONArray(listOf("Health Connect", "Garmin Connect (unofficial)", "Zepp Life (unofficial, optional)", "журнал силовых тренировок (импорт)")))
            put("dayCount", days.size)
            put("workoutCount", workouts.size)
        }

        root.put("days", JSONArray().also { array ->
            for (day in days) {
                array.put(
                    JSONObject().apply {
                        putDate(day.dateEpochDay)
                        put("steps", day.steps)
                        put("activeCaloriesKcal", day.activeCaloriesKcal)
                        put("totalCaloriesKcal", day.totalCaloriesKcal)
                        put("distanceMeters", day.distanceMeters)
                        put("restingHeartRate", day.restingHeartRate)
                        put("avgHeartRate", day.avgHeartRate)
                        put("minHeartRate", day.minHeartRate)
                        put("maxHeartRate", day.maxHeartRate)
                        put("sleepTotalMinutes", day.sleepTotalMinutes)
                        put("sleepDeepMinutes", day.sleepDeepMinutes)
                        put("sleepLightMinutes", day.sleepLightMinutes)
                        put("sleepRemMinutes", day.sleepRemMinutes)
                        put("sleepAwakeMinutes", day.sleepAwakeMinutes)
                        put("workoutCount", day.workoutCount)
                        put("workoutMinutes", day.workoutMinutes)
                    }
                )
            }
        })

        root.put("workouts", JSONArray().also { array ->
            for (workout in workouts) {
                array.put(
                    JSONObject().apply {
                        put("date", ISO_DATE.format(LocalDate.ofEpochDay(workout.dateEpochDay)))
                        put("startTime", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(workout.startTimeMillis)))
                        put("endTime", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(workout.endTimeMillis)))
                        put("exerciseType", workout.exerciseType)
                        put("exerciseName", exerciseName(workout.exerciseType))
                        put("title", workout.title)
                        put("durationMinutes", workout.durationMinutes)
                        put("distanceMeters", workout.distanceMeters)
                        put("activeCaloriesKcal", workout.activeCaloriesKcal)
                        put("avgHeartRate", workout.avgHeartRate)
                        put("maxHeartRate", workout.maxHeartRate)
                    }
                )
            }
        })

        root.put("garmin", JSONObject().apply {
            put("days", JSONArray().also { array ->
                for (g in database.garminDailyExtraDao().getAllOnce()) {
                    array.put(JSONObject().apply {
                        putDate(g.dateEpochDay)
                        put("totalSteps", g.totalSteps)
                        put("totalDistanceMeters", g.totalDistanceMeters)
                        put("totalKilocalories", g.totalKilocalories)
                        put("activeKilocalories", g.activeKilocalories)
                        put("floorsAscended", g.floorsAscended)
                        put("floorsDescended", g.floorsDescended)
                        put("moderateIntensityMinutes", g.moderateIntensityMinutes)
                        put("vigorousIntensityMinutes", g.vigorousIntensityMinutes)
                        put("intensityMinutesWeighted", g.intensityMinutesWeighted)
                        put("intensityMinutesWeeklyGoal", g.intensityMinutesWeeklyGoal)
                        put("activeSeconds", g.activeSeconds)
                        put("highlyActiveSeconds", g.highlyActiveSeconds)
                        put("sedentarySeconds", g.sedentarySeconds)
                        put("sleepingSeconds", g.sleepingSeconds)
                        put("restingHeartRate", g.restingHeartRate)
                        put("minHeartRate", g.minHeartRate)
                        put("maxHeartRate", g.maxHeartRate)
                        put("lastSevenDaysAvgRestingHeartRate", g.lastSevenDaysAvgRestingHeartRate)
                        put("averageStressLevel", g.averageStressLevel)
                        put("maxStressLevel", g.maxStressLevel)
                        put("stressQualifier", g.stressQualifier)
                        put("restStressSeconds", g.restStressSeconds)
                        put("lowStressSeconds", g.lowStressSeconds)
                        put("mediumStressSeconds", g.mediumStressSeconds)
                        put("highStressSeconds", g.highStressSeconds)
                        put("bodyBatteryAtWake", g.bodyBatteryAtWake)
                        put("bodyBatteryHighest", g.bodyBatteryHighest)
                        put("bodyBatteryLowest", g.bodyBatteryLowest)
                        put("averageSpo2", g.averageSpo2)
                        put("lowestSpo2", g.lowestSpo2)
                        put("avgWakingRespiration", g.avgWakingRespiration)
                        put("highestRespiration", g.highestRespiration)
                        put("lowestRespiration", g.lowestRespiration)
                        put("hydrationMl", g.hydrationMl)
                        put("hydrationGoalMl", g.hydrationGoalMl)
                    })
                }
            })

            put("sleep", JSONArray().also { array ->
                for (s in database.garminSleepDao().getAllOnce()) {
                    array.put(JSONObject().apply {
                        putDate(s.dateEpochDay)
                        put("sleepSeconds", s.sleepSeconds)
                        put("napSeconds", s.napSeconds)
                        put("deepSeconds", s.deepSeconds)
                        put("lightSeconds", s.lightSeconds)
                        put("remSeconds", s.remSeconds)
                        put("awakeSeconds", s.awakeSeconds)
                        put("unmeasurableSeconds", s.unmeasurableSeconds)
                        put("awakeCount", s.awakeCount)
                        put("sleepStartLocalMillis", s.sleepStartLocalMillis)
                        put("sleepEndLocalMillis", s.sleepEndLocalMillis)
                        put("score", s.score)
                        put("scoreQualifier", s.scoreQualifier)
                        put("scoreQualifierLabel", qualifierLabel(s.scoreQualifier))
                        put("durationQualifier", s.durationQualifier)
                        put("stressQualifier", s.stressQualifier)
                        put("awakeCountQualifier", s.awakeCountQualifier)
                        put("remQualifier", s.remQualifier)
                        put("restlessnessQualifier", s.restlessnessQualifier)
                        put("lightQualifier", s.lightQualifier)
                        put("deepQualifier", s.deepQualifier)
                        put("feedback", s.feedback)
                        put("insight", s.insight)
                        put("needBaselineMinutes", s.needBaselineMinutes)
                        put("needActualMinutes", s.needActualMinutes)
                        put("needFeedback", s.needFeedback)
                        put("avgSpo2", s.avgSpo2.toDouble())
                        put("lowestSpo2", s.lowestSpo2)
                        put("avgRespiration", s.avgRespiration.toDouble())
                        put("avgSleepStress", s.avgSleepStress.toDouble())
                        put("restingHeartRate", s.restingHeartRate)
                        put("bodyBatteryChange", s.bodyBatteryChange)
                        if (s.hasSkinTemp) put("skinTempDeviationC", s.skinTempDeviationC.toDouble())
                    })
                }
            })

            put("hrv", JSONArray().also { array ->
                for (h in database.garminHrvDao().getAllOnce()) {
                    array.put(JSONObject().apply {
                        putDate(h.dateEpochDay)
                        put("weeklyAvg", h.weeklyAvg)
                        put("lastNightAvg", h.lastNightAvg)
                        put("lastNight5MinHigh", h.lastNight5MinHigh)
                        put("status", h.status)
                        put("statusLabel", hrvStatusLabel(h.status))
                        put("feedbackPhrase", h.feedbackPhrase)
                        put("baselineLowUpper", h.baselineLowUpper)
                        put("baselineBalancedLow", h.baselineBalancedLow)
                        put("baselineBalancedUpper", h.baselineBalancedUpper)
                        put("baselineMarkerValue", h.baselineMarkerValue.toDouble())
                    })
                }
            })

            put("readiness", JSONArray().also { array ->
                for (r in database.garminReadinessDao().getAllOnce()) {
                    array.put(JSONObject().apply {
                        putDate(r.dateEpochDay)
                        put("score", r.score)
                        put("level", r.level)
                        put("levelLabel", readinessLevelLabel(r.level))
                        put("feedbackShort", r.feedbackShort)
                        put("feedbackLong", r.feedbackLong)
                        put("timestampLocal", r.timestampLocal)
                        put("sleepScore", r.sleepScore)
                        put("sleepScoreFactorPercent", r.sleepScoreFactorPercent)
                        put("sleepScoreFactorFeedback", r.sleepScoreFactorFeedback)
                        put("recoveryTimeHours", r.recoveryTimeHours.toDouble())
                        put("recoveryTimeFactorPercent", r.recoveryTimeFactorPercent)
                        put("recoveryTimeFactorFeedback", r.recoveryTimeFactorFeedback)
                        put("acwrFactorPercent", r.acwrFactorPercent)
                        put("acwrFactorFeedback", r.acwrFactorFeedback)
                        put("acuteLoad", r.acuteLoad)
                        put("stressHistoryFactorPercent", r.stressHistoryFactorPercent)
                        put("stressHistoryFactorFeedback", r.stressHistoryFactorFeedback)
                        put("hrvFactorPercent", r.hrvFactorPercent)
                        put("hrvFactorFeedback", r.hrvFactorFeedback)
                        put("hrvWeeklyAverage", r.hrvWeeklyAverage)
                        put("sleepHistoryFactorPercent", r.sleepHistoryFactorPercent)
                        put("sleepHistoryFactorFeedback", r.sleepHistoryFactorFeedback)
                    })
                }
            })

            put("training", JSONArray().also { array ->
                for (t in database.garminTrainingDao().getAllOnce()) {
                    array.put(JSONObject().apply {
                        putDate(t.dateEpochDay)
                        put("trainingStatus", t.trainingStatus)
                        put("statusFeedbackPhrase", t.statusFeedbackPhrase)
                        put("statusLabel", trainingStatusLabel(t.statusFeedbackPhrase))
                        put("weeklyTrainingLoad", t.weeklyTrainingLoad)
                        put("loadTunnelMin", t.loadTunnelMin)
                        put("loadTunnelMax", t.loadTunnelMax)
                        put("fitnessTrend", t.fitnessTrend)
                        put("trainingPaused", t.trainingPaused)
                        put("acwrPercent", t.acwrPercent)
                        put("acwrStatus", t.acwrStatus)
                        put("acwrStatusFeedback", t.acwrStatusFeedback)
                        put("dailyTrainingLoadAcute", t.dailyTrainingLoadAcute)
                        put("dailyTrainingLoadChronic", t.dailyTrainingLoadChronic)
                        put("acuteChronicRatio", t.acuteChronicRatio.toDouble())
                        put("vo2Max", t.vo2Max.toDouble())
                        put("vo2MaxPrecise", t.vo2MaxPrecise.toDouble())
                        put("enduranceScore", t.enduranceScore)
                        put("enduranceClassification", t.enduranceClassification)
                        put("hillScore", t.hillScore)
                        put("hillEnduranceScore", t.hillEnduranceScore)
                        put("hillStrengthScore", t.hillStrengthScore)
                    })
                }
            })

            put("bodyComposition", JSONArray().also { array ->
                for (b in database.garminBodyCompositionDao().getAllOnce()) {
                    array.put(JSONObject().apply {
                        putDate(b.dateEpochDay)
                        put("weightGrams", b.weightGrams)
                        put("bmi", b.bmi.toDouble())
                        put("bodyFatPercent", b.bodyFatPercent.toDouble())
                        put("bodyWaterPercent", b.bodyWaterPercent.toDouble())
                        put("boneMassGrams", b.boneMassGrams)
                        put("muscleMassGrams", b.muscleMassGrams)
                        put("visceralFat", b.visceralFat.toDouble())
                        put("metabolicAge", b.metabolicAge)
                        put("sourceType", b.sourceType)
                    })
                }
            })

            put("activities", JSONArray().also { array ->
                for (a in database.garminActivityDao().getAllOnce()) {
                    array.put(JSONObject().apply {
                        put("activityId", a.activityId)
                        putDate(a.dateEpochDay)
                        put("startTime", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(a.startTimeMillis)))
                        put("name", a.name)
                        put("typeKey", a.typeKey)
                        put("typeName", garminSportName(a.typeKey))
                        put("locationName", a.locationName)
                        put("durationSeconds", a.durationSeconds)
                        put("distanceMeters", a.distanceMeters)
                        put("calories", a.calories)
                        put("avgHeartRate", a.avgHeartRate)
                        put("maxHeartRate", a.maxHeartRate)
                        put("steps", a.steps)
                        put("elevationGainMeters", a.elevationGainMeters)
                        put("avgSpeedMetersPerSecond", a.avgSpeedMetersPerSecond.toDouble())
                        put("aerobicTrainingEffect", a.aerobicTrainingEffect.toDouble())
                        put("anaerobicTrainingEffect", a.anaerobicTrainingEffect.toDouble())
                        put("trainingEffectLabel", a.trainingEffectLabel)
                        put("activityTrainingLoad", a.activityTrainingLoad.toDouble())
                        put("avgRunCadence", a.avgRunCadence)
                        put("avgPower", a.avgPower)
                        put("normalizedPower", a.normalizedPower)
                        put("moderateIntensityMinutes", a.moderateIntensityMinutes)
                        put("vigorousIntensityMinutes", a.vigorousIntensityMinutes)
                        put("bodyBatteryDiff", a.bodyBatteryDiff)
                    })
                }
            })
        })

        root.put("scale", JSONObject().apply {
            put("sources", JSONArray(listOf("health_connect (Zepp Life -> Google Fit -> Health Connect)", "zepp (Zepp Life cloud, optional)")))
            put("measurements", JSONArray().also { array ->
                for (m in database.scaleMeasurementDao().getAllOnce()) {
                    array.put(JSONObject().apply {
                        putDate(m.dateEpochDay)
                        put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(m.timestampMillis)))
                        put("weightGrams", m.weightGrams)
                        put("heightCm", m.heightCm.toDouble())
                        put("bmi", m.bmi.toDouble())
                        put("bodyFatPercent", m.bodyFatPercent.toDouble())
                        put("bodyWaterPercent", m.bodyWaterPercent.toDouble())
                        put("boneMassGrams", m.boneMassGrams)
                        put("muscleMassGrams", m.muscleMassGrams)
                        put("metabolicAge", m.metabolicAge)
                        put("visceralFat", m.visceralFat)
                        put("basalMetabolismKcal", m.basalMetabolismKcal)
                        put("proteinPercent", m.proteinPercent.toDouble())
                        put("bodyScore", m.bodyScore)
                        put("physiqueRating", m.physiqueRating)
                        put("impedance", m.impedance)
                        put("deviceId", m.deviceId)
                        put("source", m.source)
                        put("uploadedToGarmin", m.isUploadedToGarmin)
                    })
                }
            })
        })

        root.put("strength", JSONObject().apply {
            put("source", "журнал тренировок (импорт из файла)")
            put("sets", JSONArray().also { array ->
                for (set in database.strengthSetDao().getAllOnce()) {
                    array.put(JSONObject().apply {
                        putDate(set.dateEpochDay)
                        put("start", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(set.startMillis)))
                        put("exercise", set.exerciseName)
                        // Live mapping, not the copy frozen into the row at import time.
                        put("lift", StrengthLift.match(set.exerciseName)?.key.orEmpty())
                        put("setIndex", set.setIndex)
                        put("weightKg", set.weightKg.toDouble())
                        put("reps", set.reps)
                    })
                }
            })
        })

        val exportDir = File(context.cacheDir, "export").apply { mkdirs() }
        // Only ever keep the latest export around - nothing reads old ones, and
        // leaving them piles up copies of the user's health data in the cache.
        exportDir.listFiles()?.forEach { it.delete() }

        val stamp = LocalDate.now().format(ISO_DATE)
        val file = File(exportDir, "fitness-summary-export_$stamp.json")
        file.writeText(root.toString(2))
        return file
    }

    private fun JSONObject.putDate(epochDay: Long) {
        put("date", ISO_DATE.format(LocalDate.ofEpochDay(epochDay)))
        put("dateEpochDay", epochDay)
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Экспорт фитнес-данных")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Поделиться экспортом")
    }
}
