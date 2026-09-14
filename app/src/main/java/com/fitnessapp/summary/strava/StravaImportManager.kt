package com.fitnessapp.summary.strava

import android.content.Context
import android.net.Uri
import com.fitnessapp.summary.analytics.DistanceSport
import com.fitnessapp.summary.analytics.SportDistanceAnalytics
import com.fitnessapp.summary.data.AppDatabase
import com.fitnessapp.summary.debug.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * Loads `activities.csv` from a Strava bulk export into `strava_activities`.
 *
 * The only source in this app that reaches back before the watch: this account's Strava
 * history starts in 2015, Garmin's in late 2021. Nothing else can fill those years -
 * Health Connect keeps about a month, and the watch did not exist.
 *
 * One-shot and user-driven, like the gym log: Strava's API is not usable here (its 2026
 * terms forbid caching beyond seven days), so the export file is the channel. Re-importing
 * a newer export is the update mechanism and is idempotent - rows land on Strava's own
 * activity id.
 *
 * **Overlap is stored, not discarded.** See [StravaImportManager.Result.duplicates] for
 * what that means on screen and [com.fitnessapp.summary.data.StravaActivity] for why.
 */
class StravaImportManager(
    context: Context,
    private val database: AppDatabase
) {
    sealed class State {
        data object Idle : State()
        data object Running : State()
        data class Success(val result: Result, val atMillis: Long) : State()
        data class Failed(val reason: String) : State()
    }

    data class Result(
        val imported: Int,
        /**
         * How many of [imported] the app already knows from Garmin or Health Connect.
         *
         * Reported rather than silently dropped: it is the honest answer to "are my rides
         * now counted twice" - they are stored twice and shown once, and a number the user
         * can check beats a promise. Computed against today's data; it shrinks on its own
         * as Garmin backfills, which is exactly why it is not baked into the rows.
         */
        val duplicates: Int,
        val skippedRows: Int,
        val firstEpochDay: Long,
        val lastEpochDay: Long,
        val sports: Map<String, Int>
    ) {
        val fresh: Int get() = imported - duplicates
    }

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun importFrom(uri: Uri): State = withContext(Dispatchers.IO) {
        _state.value = State.Running
        try {
            val text = readText(uri)
                ?: return@withContext fail("Не удалось открыть файл")

            when (val parsed = StravaExportParser.parse(text, ZoneId.systemDefault())) {
                is StravaExportParser.Result.Failed -> fail(parsed.reason)
                is StravaExportParser.Result.Ok -> {
                    val activities = parsed.activities
                    database.stravaActivityDao().upsertAll(activities)

                    val first = activities.minOf { it.dateEpochDay }
                    val last = activities.maxOf { it.dateEpochDay }

                    // Counted after storing and over the imported range only - this is a
                    // report, not a filter, and it must describe what is actually there.
                    val known = knownStartTimes(first, last)
                    val duplicates = activities.count { activity ->
                        known.any { abs(it - activity.startTimeMillis) <= SportDistanceAnalytics.WORKOUT_MATCH_WINDOW_MILLIS }
                    }

                    val result = Result(
                        imported = activities.size,
                        duplicates = duplicates,
                        skippedRows = parsed.skippedRows,
                        firstEpochDay = first,
                        lastEpochDay = last,
                        sports = parsed.sports
                    )
                    AppLog.i(
                        "StravaImportManager",
                        "Импорт Strava: тренировок=${result.imported}, из них уже известны=${result.duplicates}, " +
                            "пропущено строк=${result.skippedRows}, " +
                            "${LocalDate.ofEpochDay(first)}..${LocalDate.ofEpochDay(last)}"
                    )
                    State.Success(result, System.currentTimeMillis()).also { _state.value = it }
                }
            }
        } catch (e: Exception) {
            AppLog.e("StravaImportManager", "Импорт Strava упал", e)
            fail(e.message ?: "Не удалось прочитать файл")
        }
    }

    /** Drops the import. The file is the only source, so this is fully reversible. */
    suspend fun forget(): Int = withContext(Dispatchers.IO) {
        val removed = database.stravaActivityDao().deleteAll()
        AppLog.i("StravaImportManager", "Импорт Strava удалён: строк=$removed")
        _state.value = State.Idle
        removed
    }

    /** Start instants of everything the app already has over the range, from both other sources. */
    private suspend fun knownStartTimes(fromEpochDay: Long, toEpochDay: Long): LongArray {
        val garmin = database.garminActivityDao().getAllOnce()
            .filter { it.dateEpochDay in fromEpochDay..toEpochDay }
            .map { it.startTimeMillis }
        val health = database.workoutDao().getAllOnce()
            .filter { it.dateEpochDay in fromEpochDay..toEpochDay }
            .map { it.startTimeMillis }
        return (garmin + health).toLongArray()
    }

    /**
     * Decodes the file. UTF-8 first and **strictly**, so a mis-decode is a failure rather
     * than a page of replacement characters - the same rule the gym-log text export needed,
     * where the answer turned out to be windows-1251. Strava writes UTF-8 with a BOM; the
     * fallback is here because a file that has been through a spreadsheet may not.
     */
    private fun readText(uri: Uri): String? {
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }.getOrElse {
            AppLog.w("StravaImportManager", "Файл не UTF-8, читаю как windows-1251")
            String(bytes, Charset.forName("windows-1251"))
        }
    }

    private fun fail(reason: String): State =
        State.Failed(reason).also { _state.value = it }

    /** The three sports of «Объём по неделям», counted the way the charts will count them. */
    fun distanceSummary(sports: Map<String, Int>): String =
        DistanceSport.entries.mapNotNull { sport ->
            val n = sports.entries
                .filter { DistanceSport.ofStrava(it.key) == sport }
                .sumOf { it.value }
            if (n > 0) "${sport.title.lowercase()} $n" else null
        }.joinToString(", ")
}
