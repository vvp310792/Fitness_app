package com.fitnessapp.summary.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.fitnessapp.summary.data.SummaryRepository
import com.fitnessapp.summary.data.WorkoutRepository
import com.fitnessapp.summary.util.exerciseName
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
 * - the exercise type gets its resolved name next to the raw int, for the same reason.
 *
 * Room is the source, not Health Connect - so an export contains the full stored
 * history even for days Health Connect itself has since pruned.
 */
object DataExporter {

    private val ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE

    suspend fun export(
        context: Context,
        summaryRepository: SummaryRepository,
        workoutRepository: WorkoutRepository
    ): File {
        val days = summaryRepository.getAllOnce()
        val workouts = workoutRepository.getAllOnce()

        val root = JSONObject().apply {
            put("app", "fitness-summary")
            put("schemaVersion", 1)
            put("exportedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
            put("source", "Health Connect")
            put("dayCount", days.size)
            put("workoutCount", workouts.size)
        }

        val daysArray = JSONArray()
        for (day in days) {
            daysArray.put(
                JSONObject().apply {
                    put("date", ISO_DATE.format(LocalDate.ofEpochDay(day.dateEpochDay)))
                    put("dateEpochDay", day.dateEpochDay)
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
        root.put("days", daysArray)

        val workoutsArray = JSONArray()
        for (workout in workouts) {
            workoutsArray.put(
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
        root.put("workouts", workoutsArray)

        val exportDir = File(context.cacheDir, "export").apply { mkdirs() }
        // Only ever keep the latest export around - nothing reads old ones, and
        // leaving them piles up copies of the user's health data in the cache.
        exportDir.listFiles()?.forEach { it.delete() }

        val stamp = LocalDate.now().format(ISO_DATE)
        val file = File(exportDir, "fitness-summary-export_$stamp.json")
        file.writeText(root.toString(2))
        return file
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
