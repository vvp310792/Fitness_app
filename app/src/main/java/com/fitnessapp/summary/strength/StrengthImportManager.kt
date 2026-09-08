package com.fitnessapp.summary.strength

import android.content.Context
import android.net.Uri
import com.fitnessapp.summary.analytics.StrengthLift
import com.fitnessapp.summary.data.AppDatabase
import com.fitnessapp.summary.debug.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Loads a gym-log export into `strength_sets` ([WorkoutLogParser] does the reading).
 *
 * One-shot and user-driven, unlike every other source in the app: there is no gym-log API
 * to poll, the file is exported by hand, and re-importing a newer export is the update
 * mechanism. That is safe by construction - rows are keyed by session, exercise and set
 * number, so the same session lands on itself instead of doubling, and only sets added
 * since the last export are actually new.
 *
 * Nothing here goes to Garmin. Garmin already has these sessions as activities from the
 * watch; what it does not have is the set-by-set detail, and pushing invented strength
 * activities into it would be writing over the watch's own record.
 */
class StrengthImportManager(
    context: Context,
    private val database: AppDatabase
) {
    sealed class State {
        data object Idle : State()
        data class Running(val step: String) : State()
        data class Success(
            val sessions: Int,
            val sets: Int,
            val skippedLines: Int,
            val liftSummary: String,
            val atMillis: Long
        ) : State()
        data class Failed(val reason: String) : State()
    }

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun importFrom(uri: Uri): State {
        _state.value = State.Running("Читаю файл тренировок...")
        val parsed = withContext(Dispatchers.IO) {
            try {
                appContext.contentResolver.openInputStream(uri).use { stream ->
                    if (stream == null) WorkoutLogParser.Result.Failed("файл не открылся")
                    else WorkoutLogParser.parse(stream)
                }
            } catch (e: Exception) {
                AppLog.w("StrengthImportManager", "Импорт тренировок: файл не прочитался", e)
                WorkoutLogParser.Result.Failed(e.message ?: e.javaClass.simpleName)
            }
        }

        val ok = when (parsed) {
            is WorkoutLogParser.Result.Failed ->
                return State.Failed("Импорт не удался: ${parsed.reason}").also { _state.value = it }
            is WorkoutLogParser.Result.Ok -> parsed
        }

        _state.value = State.Running("Сохраняю ${ok.sets.size} подходов...")
        // Chunked because SQLite binds a limited number of arguments per statement and a
        // five-year log is thousands of rows in one call.
        ok.sets.chunked(CHUNK).forEach { database.strengthSetDao().upsertAll(it) }

        val perLift = StrengthLift.entries.mapNotNull { lift ->
            val sessions = ok.sets.filter { it.lift == lift.key }.map { it.startMillis }.distinct().size
            if (sessions > 0) "${lift.title}: $sessions" else null
        }
        val summary = if (perLift.isEmpty()) {
            "ни одного из отслеживаемых базовых упражнений не найдено"
        } else {
            perLift.joinToString(", ")
        }
        val first = ok.sets.minOf { it.dateEpochDay }
        val last = ok.sets.maxOf { it.dateEpochDay }
        AppLog.i(
            "StrengthImportManager",
            "Импорт тренировок: сессий ${ok.sessions}, подходов ${ok.sets.size}, " +
                "период ${LocalDate.ofEpochDay(first)}..${LocalDate.ofEpochDay(last)}; $summary"
        )
        return State.Success(ok.sessions, ok.sets.size, ok.skippedLines, summary, System.currentTimeMillis())
            .also { _state.value = it }
    }

    private companion object {
        const val CHUNK = 500
    }
}
