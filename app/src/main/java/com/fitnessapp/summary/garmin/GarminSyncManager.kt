package com.fitnessapp.summary.garmin

import com.fitnessapp.summary.data.AppDatabase
import com.fitnessapp.summary.debug.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * Pulls everything the unofficial Garmin client can read into Room, day by day - the
 * counterpart to [com.fitnessapp.summary.health.HealthSyncManager] for the source that
 * actually has Garmin's own scores (sleep score, HRV status, readiness, training status,
 * Body Battery, stress...). Deliberately its own sync path rather than folded into that
 * one: it has a completely different failure mode (a login that can expire and need
 * re-auth, an unofficial endpoint that can change shape without notice) that a user who
 * never sets this up should never be affected by.
 *
 * Every section is fetched and stored independently, in the same spirit as
 * HealthConnectReader.runCatchingRead: a watch with no HRV sensor 204s on hrv-service, a
 * user without a scale has no weight, a day with no workout has no readiness recompute -
 * none of that should stop the sleep or stress for the same day from landing. Each
 * section's failure is logged with its name, so "part of the Garmin data is missing" is
 * diagnosable from Я -> Логи instead of a guess.
 *
 * Nothing here is pushed to Firestore - see the header comment in data/GarminEntities.kt.
 */
class GarminSyncManager(
    private val apiClient: GarminApiClient,
    private val database: AppDatabase
) {
    sealed class State {
        data object Idle : State()
        data class Running(val done: Int, val total: Int) : State()
        data class Success(val daysWritten: Int, val atMillis: Long) : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Routine refresh: today plus the preceding days - same window and reasoning as HealthSyncManager. */
    suspend fun syncRecent(days: Int = DEFAULT_RECENT_DAYS): State {
        val today = LocalDate.now()
        return syncRange(today.minusDays((days - 1).toLong()), today)
    }

    /** "Pull everything" - long enough for month-scale trends on the Тренды tab to mean something. */
    suspend fun backfill(days: Int = DEFAULT_BACKFILL_DAYS): State {
        val today = LocalDate.now()
        return syncRange(today.minusDays(days.toLong()), today)
    }

    suspend fun syncRange(from: LocalDate, to: LocalDate): State {
        AppLog.i("GarminSyncManager", "Синк Garmin $from..$to начат")
        val totalDays = (to.toEpochDay() - from.toEpochDay() + 1).toInt().coerceAtLeast(1)
        _state.value = State.Running(0, totalDays)

        // Range-shaped endpoints first, one call per window instead of one per day. Their
        // results are folded into the per-day summary row below.
        val stressZones = section("стресс по зонам") { apiClient.stressZones(from, to) } ?: emptyMap()
        val intensityGoals = section("цель интенсивных минут") { apiClient.intensityMinutesGoal(from, to) } ?: emptyMap()
        val hydration = section("гидратация") { apiClient.hydration(from, to) } ?: emptyMap()

        val counts = SectionCounts()
        var daysWritten = 0

        var date = from
        var index = 0
        while (!date.isAfter(to)) {
            _state.value = State.Running(index, totalDays)
            val epochDay = date.toEpochDay()
            var wroteSomething = false

            section("сводка дня $date") { apiClient.dailySummary(date) }?.let { summary ->
                val zones = stressZones[epochDay]
                val hydrationDay = hydration[epochDay]
                val merged = summary.copy(
                    restStressSeconds = zones?.restSeconds ?: 0,
                    lowStressSeconds = zones?.lowSeconds ?: 0,
                    mediumStressSeconds = zones?.mediumSeconds ?: 0,
                    highStressSeconds = zones?.highSeconds ?: 0,
                    intensityMinutesWeeklyGoal = intensityGoals[epochDay] ?: 0,
                    hydrationMl = hydrationDay?.first ?: 0,
                    hydrationGoalMl = hydrationDay?.second ?: 0
                )
                if (!merged.isEmpty) {
                    database.garminDailyExtraDao().upsert(merged)
                    counts.summary++
                    wroteSomething = true
                }
            }

            section("сон $date") { apiClient.sleep(date) }?.let { sleep ->
                if (!sleep.isEmpty) {
                    database.garminSleepDao().upsert(sleep)
                    counts.sleep++
                    wroteSomething = true
                }
            }

            section("ВСР $date") { apiClient.hrv(date) }?.let { hrv ->
                if (!hrv.isEmpty) {
                    database.garminHrvDao().upsert(hrv)
                    counts.hrv++
                    wroteSomething = true
                }
            }

            section("готовность $date") { apiClient.readiness(date) }?.let { readiness ->
                if (!readiness.isEmpty) {
                    database.garminReadinessDao().upsert(readiness)
                    counts.readiness++
                    wroteSomething = true
                }
            }

            section("статус тренировок $date") { apiClient.training(date) }?.let { training ->
                if (!training.isEmpty) {
                    database.garminTrainingDao().upsert(training)
                    counts.training++
                    wroteSomething = true
                }
            }

            if (wroteSomething) daysWritten++
            date = date.plusDays(1)
            index++
        }

        section("вес и состав тела") { apiClient.bodyComposition(from, to) }?.let { rows ->
            val real = rows.filter { !it.isEmpty }
            if (real.isNotEmpty()) {
                database.garminBodyCompositionDao().upsertAll(real)
                counts.weight = real.size
            }
        }

        section("тренировки Garmin") { apiClient.activities(from, to) }?.let { activities ->
            if (activities.isNotEmpty()) {
                database.garminActivityDao().upsertAll(activities)
                counts.activities = activities.size
            }
        }

        AppLog.i(
            "GarminSyncManager",
            "Синк Garmin завершён: дней с данными=$daysWritten из $totalDays; " +
                "сводка=${counts.summary} сон=${counts.sleep} ВСР=${counts.hrv} " +
                "готовность=${counts.readiness} статус=${counts.training} " +
                "взвешиваний=${counts.weight} тренировок=${counts.activities}"
        )

        // A whole run with nothing at all is worth surfacing as a failure rather than
        // "0 days written": with a valid login that means the token has died or Garmin
        // changed the protocol - both of which the user needs to know about.
        if (daysWritten == 0 && counts.activities == 0 && counts.weight == 0) {
            AppLog.w("GarminSyncManager", "Ни один раздел не вернул данных - проверьте вход в Garmin")
            return State.Failed("Garmin ничего не вернул. Возможно, нужно войти заново.").also { _state.value = it }
        }
        return State.Success(daysWritten, System.currentTimeMillis()).also { _state.value = it }
    }

    /**
     * Runs one fetch, turning any failure into a logged null. The client itself already
     * logs HTTP-level detail; this catches everything else (a JSON shape change, a parse
     * error) with the section's name so the log says WHICH part broke.
     */
    private suspend fun <T> section(name: String, block: suspend () -> T?): T? =
        try {
            block()
        } catch (e: Exception) {
            AppLog.w("GarminSyncManager", "Раздел не прочитался: $name", e)
            null
        }

    private class SectionCounts {
        var summary = 0
        var sleep = 0
        var hrv = 0
        var readiness = 0
        var training = 0
        var weight = 0
        var activities = 0
    }

    companion object {
        const val DEFAULT_RECENT_DAYS = 14
        const val DEFAULT_BACKFILL_DAYS = 90
    }
}
