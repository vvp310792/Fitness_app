package com.fitnessapp.summary.strength

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.fitnessapp.summary.analytics.StrengthLift
import com.fitnessapp.summary.data.AppDatabase
import com.fitnessapp.summary.data.StrengthSet
import com.fitnessapp.summary.debug.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * Loads a gym log into `strength_sets`.
 *
 * Two file formats arrive here and **the format is read off the file, not its name**: a
 * database backup the gym app writes to Google Drive by itself after every workout
 * ([GymUpBackupReader]), and the older plain-text export the user produced by hand
 * ([WorkoutLogParser]). One button takes either, because a `content://` URI picked out of
 * Drive frequently carries no usable file name at all.
 *
 * One-shot and user-driven, unlike every other source in the app: there is no gym-log API to
 * poll. Re-importing a newer file is the update mechanism, and it is safe by construction -
 * see [importFrom] for why the two formats need *different* kinds of safety.
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
            val atMillis: Long,
            /** What the file was - named on screen, because the two say different things. */
            val fromBackup: Boolean = false,
            val firstEpochDay: Long = 0,
            val lastEpochDay: Long = 0,
            /** Exercises imported but not identified - see [GymUpBackupParser.Unidentified]. */
            val unidentified: List<GymUpBackupParser.Unidentified> = emptyList()
        ) : State()
        data class Failed(val reason: String) : State()
    }

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Reads [uri] and stores what it holds.
     *
     * **The two formats are written differently on purpose.**
     *
     * A text export is a partial view - it holds whatever the user chose to export - so it is
     * *upserted*: rows land on their own key and nothing else is touched.
     *
     * A database backup is the gym app's complete truth for the period it covers, so it
     * *replaces* that period: every existing row between its first and last day is deleted
     * and the file's rows are written in one transaction. That is the only way an edit made
     * in the gym app - a corrected weight, a deleted set, an exercise finally given a name -
     * can ever reach this app, because an upsert can add and overwrite but never remove.
     * It also means naming a previously unidentified exercise and re-importing actually
     * renames it instead of leaving both versions side by side.
     *
     * The replacement is bounded by the file's own range and happens only after the file has
     * parsed, so a broken or half-read file can never take the existing history with it -
     * the same rule as `WorkoutRepository.replaceDay()`.
     */
    suspend fun importFrom(uri: Uri): State {
        _state.value = State.Running("Читаю файл тренировок...")

        val isBackup = withContext(Dispatchers.IO) {
            try {
                appContext.contentResolver.openInputStream(uri).use { stream ->
                    stream != null && GymUpBackupReader.looksLikeSqlite(GymUpBackupReader.readMagic(stream))
                }
            } catch (e: Exception) {
                AppLog.w("StrengthImportManager", "Импорт тренировок: файл не открылся", e)
                false
            }
        }

        return if (isBackup) importBackup(uri) else importTextLog(uri)
    }

    private suspend fun importBackup(uri: Uri): State {
        _state.value = State.Running("Читаю бэкап дневника тренировок...")

        val parsed = withContext(Dispatchers.IO) {
            // SQLiteDatabase needs a path, and a content:// URI has none. The copy lives in
            // the cache and is deleted whatever happens - it is a full copy of the user's
            // training history and has no reason to outlive the import.
            val scratch = File.createTempFile("gym-backup", ".db", appContext.cacheDir)
            try {
                appContext.contentResolver.openInputStream(uri).use { stream ->
                    if (stream == null) return@withContext GymUpBackupParser.Result.Failed("файл не открылся")
                    scratch.outputStream().use { stream.copyTo(it) }
                }
                GymUpBackupReader.read(scratch)
            } catch (e: Exception) {
                AppLog.w("StrengthImportManager", "Импорт бэкапа не удался", e)
                GymUpBackupParser.Result.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                scratch.delete()
            }
        }

        val ok = when (parsed) {
            is GymUpBackupParser.Result.Failed ->
                return State.Failed("Импорт не удался: ${parsed.reason}").also { _state.value = it }
            is GymUpBackupParser.Result.Ok -> parsed
        }

        val first = ok.sets.minOf { it.dateEpochDay }
        val last = ok.sets.maxOf { it.dateEpochDay }

        _state.value = State.Running("Сохраняю ${ok.sets.size} подходов...")
        database.withTransaction {
            database.strengthSetDao().deleteRange(first, last)
            ok.sets.chunked(CHUNK).forEach { database.strengthSetDao().upsertAll(it) }
        }

        AppLog.i(
            "StrengthImportManager",
            "Импорт бэкапа: тренировок ${ok.sessions} (пустых ${ok.emptySessions}), " +
                "подходов ${ok.sets.size}, период ${LocalDate.ofEpochDay(first)}..${LocalDate.ofEpochDay(last)}; " +
                "неопознанных упражнений ${ok.unidentified.size}; ${summarise(ok.sets)}"
        )
        return State.Success(
            sessions = ok.sessions,
            sets = ok.sets.size,
            skippedLines = 0,
            liftSummary = summarise(ok.sets),
            atMillis = System.currentTimeMillis(),
            fromBackup = true,
            firstEpochDay = first,
            lastEpochDay = last,
            unidentified = ok.unidentified
        ).also { _state.value = it }
    }

    private suspend fun importTextLog(uri: Uri): State {
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

        val first = ok.sets.minOf { it.dateEpochDay }
        val last = ok.sets.maxOf { it.dateEpochDay }
        AppLog.i(
            "StrengthImportManager",
            "Импорт тренировок: сессий ${ok.sessions}, подходов ${ok.sets.size}, " +
                "период ${LocalDate.ofEpochDay(first)}..${LocalDate.ofEpochDay(last)}; ${summarise(ok.sets)}"
        )
        return State.Success(
            sessions = ok.sessions,
            sets = ok.sets.size,
            skippedLines = ok.skippedLines,
            liftSummary = summarise(ok.sets),
            atMillis = System.currentTimeMillis(),
            firstEpochDay = first,
            lastEpochDay = last
        ).also { _state.value = it }
    }

    /**
     * Sessions per tracked lift, resolved from the exercise **name** rather than the stored
     * `lift` column - that column is a denormalised hint and this summary is shown to the
     * user right after the import, so it has to agree with what the charts will draw.
     */
    private fun summarise(sets: List<StrengthSet>): String {
        val perLift = StrengthLift.entries.mapNotNull { lift ->
            val sessions = sets.filter { StrengthLift.match(it.exerciseName) == lift }
                .map { it.startMillis }.distinct().size
            if (sessions > 0) "${lift.title}: $sessions" else null
        }
        return if (perLift.isEmpty()) {
            "ни одного из отслеживаемых базовых упражнений не найдено"
        } else {
            perLift.joinToString(", ")
        }
    }

    private companion object {
        const val CHUNK = 500
    }
}
