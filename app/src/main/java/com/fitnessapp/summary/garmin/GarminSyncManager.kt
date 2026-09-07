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
 * Every section is fetched independently, in the same spirit as
 * HealthConnectReader.runCatchingRead: a watch with no HRV sensor gets nothing from
 * hrv-service, a user without a scale has no weight, a day with no workout has no
 * readiness recompute - none of that should stop the sleep or stress for the same day
 * from landing.
 *
 * What each section produced is COUNTED, not just logged as a total, split three ways
 * (stored / Garmin said no data / the call failed) - see [GarminFetch]. That distinction
 * is the whole reason this exists in this shape: a release once shipped with sleep, HRV
 * and readiness silently returning nothing for 14 days straight, and a summary line
 * saying only "14 days written" could not tell that from working correctly. The counts
 * reach both the log and the "Я" tab, so the next "not everything syncs" is answerable
 * without asking for a log file at all.
 *
 * Nothing here is pushed to Firestore - see the header comment in data/GarminEntities.kt.
 */
class GarminSyncManager(
    private val apiClient: GarminApiClient,
    private val database: AppDatabase
) {
    /** How one section of the sync went. [label] is what the UI shows. */
    data class SectionOutcome(
        val label: String,
        val stored: Int,
        val noData: Int,
        val failed: Int
    ) {
        /** Garmin answered every time and had nothing - the watch or account doesn't produce this. */
        val isEmptyFromGarmin: Boolean get() = stored == 0 && noData > 0 && failed == 0
        val hasProblem: Boolean get() = failed > 0
    }

    sealed class State {
        data object Idle : State()
        data class Running(val done: Int, val total: Int) : State()
        data class Success(
            val daysWritten: Int,
            val atMillis: Long,
            val sections: List<SectionOutcome>
        ) : State()
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
        val run = RunState()

        // Range-shaped endpoints first, one call per window instead of one per day. Their
        // results are folded into the per-day summary row below.
        val stressZones = run.take(run.ranges) { apiClient.stressZones(from, to) } ?: emptyMap()
        val intensityGoals = run.take(run.ranges) { apiClient.intensityMinutesGoal(from, to) } ?: emptyMap()
        val hydration = run.take(run.ranges) { apiClient.hydration(from, to) } ?: emptyMap()

        var date = from
        var index = 0
        while (!date.isAfter(to)) {
            _state.value = State.Running(index, totalDays)
            val epochDay = date.toEpochDay()
            var wroteSomething = false

            run.take(run.summary) { apiClient.dailySummary(date) }?.let { summary ->
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
                    run.summary.stored++
                    wroteSomething = true
                }
            }

            run.take(run.sleep) { apiClient.sleep(date) }?.let { sleep ->
                if (!sleep.isEmpty) {
                    database.garminSleepDao().upsert(sleep)
                    run.sleep.stored++
                    wroteSomething = true
                }
            }

            run.take(run.hrv) { apiClient.hrv(date) }?.let { hrv ->
                if (!hrv.isEmpty) {
                    database.garminHrvDao().upsert(hrv)
                    run.hrv.stored++
                    wroteSomething = true
                }
            }

            run.take(run.readiness) { apiClient.readiness(date) }?.let { readiness ->
                if (!readiness.isEmpty) {
                    database.garminReadinessDao().upsert(readiness)
                    run.readiness.stored++
                    wroteSomething = true
                }
            }

            run.take(run.training) { apiClient.training(date) }?.let { training ->
                if (!training.isEmpty) {
                    database.garminTrainingDao().upsert(training)
                    run.training.stored++
                    wroteSomething = true
                }
            }

            if (wroteSomething) run.daysWritten++

            // A backfill that outlives the phone's connectivity used to grind through
            // hundreds of doomed requests - 64 identical DNS failures per endpoint in one
            // real log - and still report success. One day's worth of consecutive network
            // failures is enough to conclude the network is gone, not the data.
            if (run.networkLost) {
                AppLog.w("GarminSyncManager", "Сеть пропала на $date - синк прерван, загружено дней=${run.daysWritten}")
                break
            }

            date = date.plusDays(1)
            index++
        }

        if (!run.networkLost) {
            run.take(run.weight) { apiClient.bodyComposition(from, to) }?.let { rows ->
                val real = rows.filter { !it.isEmpty }
                if (real.isNotEmpty()) {
                    database.garminBodyCompositionDao().upsertAll(real)
                    run.weight.stored = real.size
                }
            }

            run.take(run.activities) { apiClient.activities(from, to) }?.let { activities ->
                if (activities.isNotEmpty()) {
                    database.garminActivityDao().upsertAll(activities)
                    run.activities.stored = activities.size
                }
            }
        }

        val sections = run.sections()
        AppLog.i(
            "GarminSyncManager",
            "Синк Garmin завершён: дней с данными=${run.daysWritten} из $totalDays; " +
                sections.joinToString(" ") { "${it.label}=${it.stored}/нд${it.noData}/ош${it.failed}" }
        )

        return when {
            run.networkLost -> State.Failed(
                "Соединение пропало во время синхронизации. Загружено дней: ${run.daysWritten} из $totalDays — повторите позже."
            )
            run.daysWritten == 0 && sections.any { it.hasProblem } -> State.Failed(
                "Garmin не отдал данные (${sections.filter { it.hasProblem }.joinToString { it.label }}). Возможно, нужно войти заново."
            )
            else -> State.Success(run.daysWritten, System.currentTimeMillis(), sections)
        }.also { _state.value = it }
    }

    /** Per-run tallies. Mutable and single-threaded - one sync at a time, by construction. */
    private class RunState {
        val summary = Counter("Сводка")
        val sleep = Counter("Сон")
        val hrv = Counter("ВСР")
        val readiness = Counter("Готовность")
        val training = Counter("Статус тренировок")
        val weight = Counter("Вес")
        val activities = Counter("Тренировки")

        /**
         * The three range endpoints (stress zones, intensity goal, hydration) are folded
         * into the summary row rather than being sections of their own - most people never
         * log water, so counting their silence against "Сводка" would permanently show a
         * problem that isn't one. They still run through [take] so a network drop during
         * them aborts the sync like any other call.
         */
        val ranges = Counter("Диапазоны")

        var daysWritten = 0
        private var consecutiveNetworkFailures = 0

        val networkLost: Boolean get() = consecutiveNetworkFailures >= NETWORK_FAILURE_LIMIT

        /**
         * Runs one fetch and files its outcome under [counter]. Returns the value, or null
         * for both "no data" and "failed" - the caller doesn't need to care which, the
         * counter already recorded the difference.
         */
        suspend fun <T> take(counter: Counter, block: suspend () -> GarminFetch<T>): T? =
            when (val fetch = block()) {
                is GarminFetch.Ok -> {
                    consecutiveNetworkFailures = 0
                    fetch.value
                }
                GarminFetch.NoData -> {
                    consecutiveNetworkFailures = 0
                    counter.noData++
                    null
                }
                is GarminFetch.Failed -> {
                    counter.failed++
                    if (fetch.isNetwork) consecutiveNetworkFailures++ else consecutiveNetworkFailures = 0
                    null
                }
            }

        fun sections(): List<SectionOutcome> =
            listOf(summary, sleep, hrv, readiness, training, weight, activities).map {
                SectionOutcome(it.label, it.stored, it.noData, it.failed)
            }
    }

    private class Counter(val label: String) {
        var stored = 0
        var noData = 0
        var failed = 0
    }

    companion object {
        const val DEFAULT_RECENT_DAYS = 14
        const val DEFAULT_BACKFILL_DAYS = 90

        /**
         * Consecutive network-level failures that mean "the phone is offline", not "this
         * endpoint is unhappy" - roughly one day's worth of section calls.
         */
        private const val NETWORK_FAILURE_LIMIT = 6
    }
}
