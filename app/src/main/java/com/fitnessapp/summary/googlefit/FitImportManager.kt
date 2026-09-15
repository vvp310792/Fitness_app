package com.fitnessapp.summary.googlefit

import android.content.Context
import android.net.Uri
import com.fitnessapp.summary.data.AppDatabase
import com.fitnessapp.summary.data.FitDay
import com.fitnessapp.summary.debug.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * Loads a Google Takeout archive into `fit_daily`.
 *
 * The years before the watch, for everything that is not a workout: Strava's export brought
 * the sessions back to 2015, and this brings the days around them - steps, calories, heart
 * rate, resting heart rate and weight from the phone and the Mi Band. Health Connect cannot
 * supply those years (it keeps a sliding window; the full walk through it found 38 days with
 * a real measurement), and Garmin has no day before December 2021.
 *
 * User-driven and one-shot, like the gym log and the Strava export. There is no API to poll:
 * the Google Fit REST API stopped accepting new apps in May 2024 and shuts down in 2026, so
 * the archive is the only channel, and re-importing a newer one is the update mechanism.
 * Idempotent - rows land on the calendar day.
 *
 * **Overlap is stored, not dropped at import**, for the same reason as Strava: which days
 * Garmin also covers changes every time Garmin backfills, so that verdict belongs to the
 * read side ([com.fitnessapp.summary.data.DayView]), not to the rows. The number of
 * overlapping days is reported instead - a number the user can check beats a promise.
 */
class FitImportManager(
    context: Context,
    private val database: AppDatabase
) {
    sealed class State {
        data object Idle : State()
        data class Running(val note: String) : State()
        data class Success(val result: Result, val atMillis: Long) : State()
        data class Failed(val reason: String) : State()
    }

    data class Result(
        val days: Int,
        /** Days that Garmin or Health Connect already knows something about. */
        val overlapping: Int,
        val firstEpochDay: Long,
        val lastEpochDay: Long,
        val daysWithSteps: Int,
        val daysWithCalories: Int,
        val daysWithHeartRate: Int,
        val daysWithRestingHeartRate: Int,
        val daysWithWeight: Int,
        val daysWithSleep: Int,
        val pointsByStream: Map<FitTakeoutParser.Stream, Int>,
        val unknownStreams: List<String>,
        val filesRead: Int,
        val filesSkipped: Int
    ) {
        val fresh: Int get() = days - overlapping
    }

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun importFrom(uri: Uri): State = withContext(Dispatchers.IO) {
        _state.value = State.Running("Открываю архив…")
        try {
            val read = FitTakeoutReader.read(
                context = appContext,
                uri = uri,
                zone = ZoneId.systemDefault()
            ) { files, points ->
                _state.value = State.Running("Прочитано потоков: $files, точек: $points")
            }

            when (read) {
                is FitTakeoutReader.Result.Failed -> fail(read.reason)
                is FitTakeoutReader.Result.Ok -> {
                    val days = read.outcome.days
                    if (days.isEmpty()) {
                        return@withContext fail("В архиве не нашлось ни одного дня с измерениями")
                    }
                    _state.value = State.Running("Сохраняю ${days.size} дней…")
                    database.fitDayDao().upsertAll(days)

                    val first = days.minOf { it.dateEpochDay }
                    val last = days.maxOf { it.dateEpochDay }
                    val known = knownDays(first, last)

                    val result = Result(
                        days = days.size,
                        overlapping = days.count { it.dateEpochDay in known },
                        firstEpochDay = first,
                        lastEpochDay = last,
                        daysWithSteps = days.count { it.steps > 0 },
                        daysWithCalories = days.count { it.caloriesKcal > 0 },
                        daysWithHeartRate = days.count { it.avgHeartRate > 0 },
                        daysWithRestingHeartRate = days.count { it.restingHeartRate > 0 },
                        daysWithWeight = days.count { it.weightGrams > 0 },
                        daysWithSleep = days.count { it.sleepTotalMinutes > 0 },
                        pointsByStream = read.outcome.pointsByStream,
                        unknownStreams = read.outcome.unknownStreams,
                        filesRead = read.outcome.filesRead,
                        filesSkipped = read.outcome.filesSkipped
                    )
                    AppLog.i(
                        "FitImportManager",
                        "Импорт Google Fit: дней=${result.days}, из них уже известны=${result.overlapping}, " +
                            "${LocalDate.ofEpochDay(first)}..${LocalDate.ofEpochDay(last)}, " +
                            "потоков прочитано=${result.filesRead}, пропущено файлов=${result.filesSkipped}, " +
                            "точек=" + result.pointsByStream.entries.joinToString(" ") { "${it.key}=${it.value}" }
                    )
                    if (result.unknownStreams.isNotEmpty()) {
                        AppLog.w(
                            "FitImportManager",
                            "Неузнанные объединённые потоки: ${result.unknownStreams.joinToString(", ")}"
                        )
                    }
                    State.Success(result, System.currentTimeMillis()).also { _state.value = it }
                }
            }
        } catch (e: Exception) {
            AppLog.e("FitImportManager", "Импорт Google Fit упал", e)
            fail(e.message ?: "Не удалось прочитать архив")
        }
    }

    /** Drops the import. The archive is the only source, so this is fully reversible. */
    suspend fun forget(): Int = withContext(Dispatchers.IO) {
        val removed = database.fitDayDao().deleteAll()
        AppLog.i("FitImportManager", "Импорт Google Fit удалён: дней=$removed")
        _state.value = State.Idle
        removed
    }

    /** Days over the range that Garmin or Health Connect already has a measurement for. */
    private suspend fun knownDays(fromEpochDay: Long, toEpochDay: Long): Set<Long> {
        val garmin = database.garminDailyExtraDao().getAllOnce()
            .filter { it.dateEpochDay in fromEpochDay..toEpochDay && !it.isEmpty }
            .map { it.dateEpochDay }
        val health = database.dailySummaryDao().getAllOnce()
            .filter { it.dateEpochDay in fromEpochDay..toEpochDay && !it.isEmpty }
            .map { it.dateEpochDay }
        return (garmin + health).toSet()
    }

    private fun fail(reason: String): State =
        State.Failed(reason).also { _state.value = it }

    companion object {
        /** What the import brought, as one line for the settings screen. */
        fun summary(result: Result): String = buildList {
            if (result.daysWithSteps > 0) add("шаги ${result.daysWithSteps}")
            if (result.daysWithCalories > 0) add("калории ${result.daysWithCalories}")
            if (result.daysWithHeartRate > 0) add("пульс ${result.daysWithHeartRate}")
            if (result.daysWithRestingHeartRate > 0) add("пульс покоя ${result.daysWithRestingHeartRate}")
            if (result.daysWithWeight > 0) add("вес ${result.daysWithWeight}")
            if (result.daysWithSleep > 0) add("сон ${result.daysWithSleep}")
        }.joinToString(", ")

        /** Days for which this import is the only source the app has. */
        fun freshLine(result: Result): String =
            "новых дней: ${result.fresh}, уже были из Garmin или Health Connect: ${result.overlapping}"
    }
}
