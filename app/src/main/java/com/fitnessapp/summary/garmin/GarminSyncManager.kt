package com.fitnessapp.summary.garmin

import com.fitnessapp.summary.data.AppDatabase
import com.fitnessapp.summary.data.GarminSyncMark
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
 * **Incremental.** A day/section pair that has already been settled - stored, or Garmin
 * having answered "nothing here" - is remembered in `garmin_sync_marks` and not asked
 * about again (see [GarminSyncMark] for the marking rules and why a FAILED call is
 * deliberately left unmarked). That makes an interrupted 90-day backfill resume where it
 * stopped instead of redoing 65 days of requests, and stops a watch with no HRV from
 * being asked about HRV 90 times on every sync. The last [ALWAYS_REFRESH_DAYS] days are
 * re-read regardless, because Garmin revises them as the watch syncs late - the same
 * reason the Health Connect window is wider than one day.
 *
 * Every section is fetched independently, in the same spirit as
 * HealthConnectReader.runCatchingRead: a watch with no HRV sensor gets nothing from
 * hrv-service, a user without a scale has no weight, a day with no workout has no
 * readiness recompute - none of that should stop the sleep or stress for the same day
 * from landing.
 *
 * What each section produced is COUNTED, not just logged as a total, four ways (stored /
 * Garmin said no data / the call failed / skipped as already known) - see [GarminFetch].
 * That distinction is the whole reason this exists in this shape: a release once shipped
 * with sleep, HRV and readiness silently returning nothing for 14 days straight, and a
 * summary line saying only "14 days written" could not tell that from working correctly.
 * The counts reach both the log and the "Я" tab.
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
        val failed: Int,
        val skipped: Int
    ) {
        /** Garmin answered every time and had nothing - the watch or account doesn't produce this. */
        val isEmptyFromGarmin: Boolean get() = stored == 0 && noData > 0 && failed == 0
        val hasProblem: Boolean get() = failed > 0
        /** Nothing was asked and nothing came back - everything was already known. */
        val isFullySkipped: Boolean get() = stored == 0 && noData == 0 && failed == 0 && skipped > 0
    }

    sealed class State {
        data object Idle : State()
        data class Running(val done: Int, val total: Int) : State()
        data class Success(
            val daysWritten: Int,
            val daysSkipped: Int,
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

    /**
     * Forgets every "already synced" mark, so the next run asks Garmin about every day
     * again. For when Garmin itself back-fills an old day long after the fact - the marks
     * are otherwise permanent for days outside the refresh window.
     */
    suspend fun forgetSyncMarks() {
        database.garminSyncMarkDao().clear()
        AppLog.i("GarminSyncManager", "Отметки о загруженных днях сброшены - следующий синк перечитает всё")
    }

    suspend fun syncRange(from: LocalDate, to: LocalDate): State {
        AppLog.i("GarminSyncManager", "Синк Garmin $from..$to начат")
        val totalDays = (to.toEpochDay() - from.toEpochDay() + 1).toInt().coerceAtLeast(1)
        val run = RunState()
        runWindow(from, to, run, totalDays, 0)
        return finish(run, totalDays)
    }

    /**
     * Everything Garmin still has, without being told how far back that is.
     *
     * Walks backwards in [HISTORY_CHUNK_DAYS]-day windows and stops when Garmin has run
     * out: [EMPTY_CHUNKS_TO_STOP] consecutive windows in which no day holds data, freshly
     * read or already stored. That is what "all days" has to mean here - Garmin exposes no
     * "account starts on" date, and guessing a fixed number of years would either cut real
     * history off or spend thousands of requests on years that never existed. The cap is
     * only a safety net for an account whose history somehow never goes quiet.
     *
     * Walking backwards (rather than forward from a guessed start) is what makes the stop
     * rule possible at all: the interesting end is today, and the far end is exactly what
     * we're looking for.
     */
    suspend fun syncAllHistory(): State {
        val today = LocalDate.now()
        AppLog.i("GarminSyncManager", "Полная история Garmin: иду назад от $today")
        val run = RunState()
        var chunkEnd = today
        var emptyChunks = 0
        var daysWalked = 0

        while (daysWalked < MAX_HISTORY_DAYS && emptyChunks < EMPTY_CHUNKS_TO_STOP && !run.networkLost) {
            val chunkStart = chunkEnd.minusDays((HISTORY_CHUNK_DAYS - 1).toLong())
            val withData = runWindow(chunkStart, chunkEnd, run, totalDays = 0, doneOffset = daysWalked)
            if (withData == 0) emptyChunks++ else emptyChunks = 0
            daysWalked += HISTORY_CHUNK_DAYS
            chunkEnd = chunkStart.minusDays(1)
        }

        if (emptyChunks >= EMPTY_CHUNKS_TO_STOP) {
            AppLog.i(
                "GarminSyncManager",
                "Полная история: ${EMPTY_CHUNKS_TO_STOP * HISTORY_CHUNK_DAYS} дней подряд без данных - " +
                    "дошли до начала истории на ${chunkEnd.plusDays((EMPTY_CHUNKS_TO_STOP * HISTORY_CHUNK_DAYS).toLong())}"
            )
        }
        return finish(run, totalDays = daysWalked)
    }

    /**
     * One window of days. Accumulates into [run] rather than reporting, so the history walk
     * above can run many windows and still produce a single report at the end.
     *
     * Returns how many days in the window hold data - counting both what was just stored
     * and what a mark says is already stored. That distinction matters for the walk's stop
     * rule: a window that is entirely skipped because it was synced last week is NOT an
     * empty window, and must not count towards "Garmin has nothing this far back".
     */
    private suspend fun runWindow(
        from: LocalDate,
        to: LocalDate,
        run: RunState,
        totalDays: Int,
        doneOffset: Int
    ): Int {
        _state.value = State.Running(doneOffset, totalDays)

        val markDao = database.garminSyncMarkDao()
        val marksHere = markDao.marksInRange(from.toEpochDay(), to.toEpochDay(), LOGIC_VERSION)
        val settled = marksHere.map { it.dateEpochDay to it.section }.toSet()
        val daysKnownToHaveData = marksHere.filter { it.hasData }.map { it.dateEpochDay }.toSet()
        // Days Garmin may still revise are re-read no matter what the marks say.
        val refreshFromEpochDay = LocalDate.now().minusDays((ALWAYS_REFRESH_DAYS - 1).toLong()).toEpochDay()
        var daysWithData = 0

        // Range-shaped endpoints first, one call per window instead of one per day. Their
        // results are folded into the per-day summary row below.
        val stressZones = run.take(run.ranges) { apiClient.stressZones(from, to) } ?: emptyMap()
        val intensityGoals = run.take(run.ranges) { apiClient.intensityMinutesGoal(from, to) } ?: emptyMap()
        val hydration = run.take(run.ranges) { apiClient.hydration(from, to) } ?: emptyMap()

        var date = from
        var index = 0
        while (!date.isAfter(to)) {
            _state.value = State.Running(doneOffset + index, totalDays)
            val epochDay = date.toEpochDay()
            val skippable = epochDay < refreshFromEpochDay
            val marks = mutableListOf<GarminSyncMark>()
            var wroteSomething = false
            var askedSomething = false

            fun isSettled(section: String): Boolean = skippable && (epochDay to section) in settled

            if (isSettled(GarminSyncMark.SECTION_SUMMARY)) {
                run.summary.skipped++
            } else {
                askedSomething = true
                var stored = false
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
                        stored = true
                        wroteSomething = true
                    } else {
                        // Garmin answered, the day was blank. Counting this as "no data"
                        // rather than leaving all four counters at zero is what stops the
                        // log from reading "0/нд0/ош0", which is indistinguishable from a
                        // section that was never called at all - the exact hole that hid
                        // an empty sleep response for a whole release.
                        run.summary.noData++
                    }
                }
                run.markFor(epochDay, GarminSyncMark.SECTION_SUMMARY, stored, run.summary)?.let { marks += it }
            }

            if (isSettled(GarminSyncMark.SECTION_SLEEP)) {
                run.sleep.skipped++
            } else {
                askedSomething = true
                var stored = false
                run.take(run.sleep) { apiClient.sleep(date) }?.let { sleep ->
                    if (!sleep.isEmpty) {
                        database.garminSleepDao().upsert(sleep)
                        run.sleep.stored++
                        stored = true
                        wroteSomething = true
                    } else {
                        run.sleep.noData++
                    }
                }
                run.markFor(epochDay, GarminSyncMark.SECTION_SLEEP, stored, run.sleep)?.let { marks += it }
            }

            if (isSettled(GarminSyncMark.SECTION_HRV)) {
                run.hrv.skipped++
            } else {
                askedSomething = true
                var stored = false
                run.take(run.hrv) { apiClient.hrv(date) }?.let { hrv ->
                    if (!hrv.isEmpty) {
                        database.garminHrvDao().upsert(hrv)
                        run.hrv.stored++
                        stored = true
                        wroteSomething = true
                    } else {
                        run.hrv.noData++
                    }
                }
                run.markFor(epochDay, GarminSyncMark.SECTION_HRV, stored, run.hrv)?.let { marks += it }
            }

            if (isSettled(GarminSyncMark.SECTION_READINESS)) {
                run.readiness.skipped++
            } else {
                askedSomething = true
                var stored = false
                run.take(run.readiness) { apiClient.readiness(date) }?.let { readiness ->
                    if (!readiness.isEmpty) {
                        database.garminReadinessDao().upsert(readiness)
                        run.readiness.stored++
                        stored = true
                        wroteSomething = true
                    } else {
                        run.readiness.noData++
                    }
                }
                run.markFor(epochDay, GarminSyncMark.SECTION_READINESS, stored, run.readiness)?.let { marks += it }
            }

            if (isSettled(GarminSyncMark.SECTION_TRAINING)) {
                run.training.skipped++
            } else {
                askedSomething = true
                var stored = false
                run.take(run.training) { apiClient.training(date) }?.let { training ->
                    if (!training.isEmpty) {
                        database.garminTrainingDao().upsert(training)
                        run.training.stored++
                        stored = true
                        wroteSomething = true
                    } else {
                        run.training.noData++
                    }
                }
                run.markFor(epochDay, GarminSyncMark.SECTION_TRAINING, stored, run.training)?.let { marks += it }
            }

            // Written per day rather than at the end, so an abort - or the process being
            // killed mid-backfill - still leaves everything done so far marked as done.
            if (marks.isNotEmpty()) markDao.upsertAll(marks)

            if (wroteSomething) run.daysWritten++
            if (!askedSomething) run.daysSkipped++
            if (wroteSomething || epochDay in daysKnownToHaveData) daysWithData++

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
            syncActivities(from, to, run)
        }

        return daysWithData
    }

    /** Turns the accumulated tallies into the one State a whole sync reports. */
    private fun finish(run: RunState, totalDays: Int): State {
        val sections = run.sections()
        val outOf = if (totalDays > 0) " из $totalDays" else ""
        AppLog.i(
            "GarminSyncManager",
            "Синк Garmin завершён: дней с данными=${run.daysWritten}$outOf " +
                "(пропущено уже загруженных: ${run.daysSkipped}); " +
                sections.joinToString(" ") { "${it.label}=${it.stored}/нд${it.noData}/ош${it.failed}/проп${it.skipped}" }
        )

        return when {
            run.networkLost -> State.Failed(
                "Соединение пропало во время синхронизации. Загружено дней: ${run.daysWritten}$outOf — " +
                    "нажмите ещё раз, синк продолжится с того же места."
            )
            run.daysWritten == 0 && run.daysSkipped == 0 && sections.any { it.hasProblem } -> State.Failed(
                "Garmin не отдал данные (${sections.filter { it.hasProblem }.joinToString { it.label }}). Возможно, нужно войти заново."
            )
            else -> State.Success(run.daysWritten, run.daysSkipped, System.currentTimeMillis(), sections)
        }.also { _state.value = it }
    }

    /**
     * Activities are range-shaped, not per-day, so they aren't marked like the sections
     * above - instead the detail call is skipped for every activity already carrying it
     * (`detailsLoaded`). On a repeated 90-day backfill that is the single biggest saving:
     * one request per activity, every time, for activities that never change.
     */
    private suspend fun syncActivities(from: LocalDate, to: LocalDate, run: RunState) {
        val listed = run.take(run.activities) { apiClient.activities(from, to) } ?: return
        val alreadyDetailed = database.garminActivityDao()
            .detailedIdsInRange(from.toEpochDay(), to.toEpochDay())
            .toSet()

        val fresh = listed.filter { it.activityId !in alreadyDetailed }
        run.activities.skipped = listed.size - fresh.size
        if (fresh.isEmpty()) return

        val detailed = fresh.map { apiClient.activityDetail(it) }
        database.garminActivityDao().upsertAll(detailed)
        run.activities.stored = detailed.size
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
        var daysSkipped = 0
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
                    counter.lastCallFailed = false
                    fetch.value
                }
                GarminFetch.NoData -> {
                    consecutiveNetworkFailures = 0
                    counter.lastCallFailed = false
                    counter.noData++
                    null
                }
                is GarminFetch.Failed -> {
                    counter.failed++
                    counter.lastCallFailed = true
                    if (fetch.isNetwork) consecutiveNetworkFailures++ else consecutiveNetworkFailures = 0
                    null
                }
            }

        /**
         * A mark for a settled question, or null when the last call for this section
         * FAILED - an unmarked day is what the next run retries, which is the whole
         * mechanism behind a resumable backfill.
         */
        fun markFor(epochDay: Long, section: String, stored: Boolean, counter: Counter): GarminSyncMark? {
            if (counter.lastCallFailed) return null
            return GarminSyncMark(
                dateEpochDay = epochDay,
                section = section,
                hasData = stored,
                logicVersion = LOGIC_VERSION
            )
        }

        fun sections(): List<SectionOutcome> =
            listOf(summary, sleep, hrv, readiness, training, weight, activities).map {
                SectionOutcome(it.label, it.stored, it.noData, it.failed, it.skipped)
            }
    }

    private class Counter(val label: String) {
        var stored = 0
        var noData = 0
        var failed = 0
        var skipped = 0

        /** Whether the most recent [RunState.take] for this section failed - see [RunState.markFor]. */
        var lastCallFailed = false
    }

    companion object {
        const val DEFAULT_RECENT_DAYS = 14

        /** One window of the history walk - also the granularity of its stop rule. */
        private const val HISTORY_CHUNK_DAYS = 30

        /**
         * Consecutive empty windows that mean "Garmin's history ends here". Three months of
         * complete silence is far longer than any plausible gap between wearing the watch,
         * and short enough that the walk doesn't spend hundreds of requests proving it.
         */
        private const val EMPTY_CHUNKS_TO_STOP = 3

        /**
         * Hard stop for the history walk - only reached by an account whose data never goes
         * quiet for three months. Garmin Connect launched well inside this window.
         */
        private const val MAX_HISTORY_DAYS = 366 * 15

        /**
         * Consecutive network-level failures that mean "the phone is offline", not "this
         * endpoint is unhappy" - roughly one day's worth of section calls.
         */
        private const val NETWORK_FAILURE_LIMIT = 6

        /**
         * Days always re-read regardless of their sync marks. Garmin keeps revising the
         * last few days as the watch syncs late and as scores are recomputed through the
         * day (training readiness in particular is recomputed repeatedly).
         */
        private const val ALWAYS_REFRESH_DAYS = 3

        /**
         * Bumped whenever a change alters what a call would return, which invalidates every
         * mark written by an older version at once. Without this, a user whose marks say
         * "Garmin has no sleep" from before the User-Agent fix would keep skipping sleep
         * forever, and the fix would look like it did nothing.
         */
        private const val LOGIC_VERSION = 1
    }
}
