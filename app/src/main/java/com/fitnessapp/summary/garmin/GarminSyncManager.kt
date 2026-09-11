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
    private val database: AppDatabase,
    private val historyStore: GarminHistoryStore
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
            val sections: List<SectionOutcome>,
            /** Where the history walk stands, when this run was one - see [historyProgress]. */
            val historyNote: String? = null
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
        // The walk's depth is derived from those marks being trustworthy; leaving it
        // behind would mean "re-read everything" quietly still refusing to look at
        // anything older than where the last walk stopped.
        historyStore.reset()
        AppLog.i("GarminSyncManager", "Отметки о загруженных днях сброшены - следующий синк перечитает всё")
    }

    /** How deep "Вся история" has already walked, for the Я tab. Null before it has ever run. */
    fun historyProgress(): GarminHistoryStore.Frontier = historyStore.read(LOGIC_VERSION)

    suspend fun syncRange(from: LocalDate, to: LocalDate): State {
        AppLog.i("GarminSyncManager", "Синк Garmin $from..$to начат")
        val totalDays = (to.toEpochDay() - from.toEpochDay() + 1).toInt().coerceAtLeast(1)
        val run = RunState()
        syncHeartRateZones(run)
        runWindow(from, to, run, totalDays, 0)
        return finish(run, totalDays)
    }

    /**
     * Re-reads the user's own zone configuration from Garmin. One request, every sync.
     *
     * Not marked in `garmin_sync_marks` like the per-day sections, and deliberately not
     * skipped once it has succeeded: zones are a setting, so they change exactly when the
     * user changes them - re-reading them costs a single request and means a max heart
     * rate edited in Garmin Connect shows up here on the next sync instead of never.
     *
     * A failure here must not fail the sync: everything else on every screen works without
     * zones, and before this existed the whole app did. The counter records it, and the Я
     * tab shows the row like any other section.
     */
    private suspend fun syncHeartRateZones(run: RunState) {
        val zones = run.take(run.zones) { apiClient.heartRateZones() } ?: return
        if (zones.isEmpty()) return
        database.garminHeartRateZoneDao().upsertAll(zones)
        run.zones.stored += zones.size
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
     *
     * **Resumable.** How far back the walk got is remembered ([GarminHistoryStore]), and the
     * next press goes deeper from there before topping up the days since. The per-day marks
     * alone were not enough: they made re-walking settled ground cheaper, but the walk still
     * had to walk it, the on-screen counter still restarted at zero, and - the real damage -
     * a connection that dropped part way aborted the run before it reached new ground, so on
     * flaky mobile data the history could never get any deeper no matter how often it was
     * pressed. One real log shows exactly that: a run that had reached 2025-07-15, and the
     * next two presses dying at 2025-11-15 and 2025-05-16 without ever passing it.
     */
    suspend fun syncAllHistory(): State {
        val today = LocalDate.now()
        val saved = historyStore.read(LOGIC_VERSION)
        val resumeFrom = saved.oldestCovered

        val run = RunState()
        // The history walk has its own RunState, so it needs its own zone read: pressing
        // «Вся история» on a fresh install would otherwise backfill years of days and still
        // leave the zones card empty.
        syncHeartRateZones(run)
        var emptyChunks = saved.emptyChunks
        var daysWalked = 0
        // One day older than the oldest window already finished. A window aborted half-way
        // was never recorded, so it gets walked again - the day marks inside it make that
        // nearly free.
        var chunkEnd = resumeFrom?.minusDays(1) ?: today
        val alreadyCovered = (today.toEpochDay() - chunkEnd.toEpochDay()).toInt().coerceAtLeast(0)

        AppLog.i(
            "GarminSyncManager",
            when {
                resumeFrom == null -> "Полная история Garmin: иду назад от $today"
                saved.complete -> "Полная история Garmin: до конца уже дошли ($resumeFrom), доберу только свежие дни"
                else -> "Полная история Garmin: продолжаю с $chunkEnd (уже пройдено $alreadyCovered дней назад от $today)"
            }
        )

        // Deeper into the past first, before the recent catch-up below: going deeper is
        // what the button is for, and doing it first means a connection that dies half way
        // still leaves the walk further along than it was.
        while (
            !saved.complete &&
            alreadyCovered + daysWalked < MAX_HISTORY_DAYS &&
            emptyChunks < EMPTY_CHUNKS_TO_STOP &&
            !run.networkLost
        ) {
            val chunkStart = chunkEnd.minusDays((HISTORY_CHUNK_DAYS - 1).toLong())
            val withData = runWindow(chunkStart, chunkEnd, run, totalDays = 0, doneOffset = alreadyCovered + daysWalked)

            // Bailing out BEFORE recording the frontier: the window is only half-asked, and
            // claiming it as covered would skip whatever the drop-out swallowed forever.
            if (run.networkLost) {
                AppLog.w(
                    "GarminSyncManager",
                    "Сеть пропала на окне $chunkStart..$chunkEnd - история остановлена, " +
                        "следующее нажатие продолжит с этого же окна"
                )
                break
            }

            if (withData == 0) emptyChunks++ else emptyChunks = 0
            daysWalked += HISTORY_CHUNK_DAYS
            chunkEnd = chunkStart.minusDays(1)
            historyStore.advance(
                oldestCoveredEpochDay = chunkStart.toEpochDay(),
                emptyChunks = emptyChunks,
                complete = emptyChunks >= EMPTY_CHUNKS_TO_STOP,
                logicVersion = LOGIC_VERSION
            )
        }

        // Then the other end. Resuming from the frontier means the walk no longer passes
        // over the days between it and today, and those grow: an account left alone for
        // three months has a gap wider than the routine 14-day sync ever reaches. A window
        // in there that is already settled costs no requests at all, so this is only
        // expensive when it has something to do.
        if (resumeFrom != null && !run.networkLost) {
            var gapEnd = today
            while (gapEnd >= resumeFrom && !run.networkLost) {
                val gapStart = maxOf(gapEnd.minusDays((HISTORY_CHUNK_DAYS - 1).toLong()), resumeFrom)
                runWindow(gapStart, gapEnd, run, totalDays = 0, doneOffset = alreadyCovered + daysWalked)
                gapEnd = gapStart.minusDays(1)
            }
            if (run.networkLost) {
                AppLog.w("GarminSyncManager", "Сеть пропала на доборе свежих дней - глубина истории при этом сохранена")
            }
        }

        // The note is built from what was actually recorded, not from the loop variable:
        // on an abort the current window was never claimed, and saying otherwise is exactly
        // the optimistic bookkeeping that made the old walk look finished when it wasn't.
        val frontier = historyStore.read(LOGIC_VERSION)
        val oldest = frontier.oldestCovered
        val note = when {
            oldest == null -> "Ни одного окна не пройдено целиком — попробуйте ещё раз."
            frontier.complete -> {
                val startsAround = oldest.plusDays((EMPTY_CHUNKS_TO_STOP * HISTORY_CHUNK_DAYS).toLong())
                AppLog.i(
                    "GarminSyncManager",
                    "Полная история: ${EMPTY_CHUNKS_TO_STOP * HISTORY_CHUNK_DAYS} дней подряд без данных - " +
                        "начало истории около $startsAround"
                )
                "История пройдена до конца: данные Garmin начинаются около $startsAround."
            }
            else -> {
                AppLog.i("GarminSyncManager", "Полная история: остановились на $oldest, отметка сохранена")
                "История загружена до $oldest — следующее нажатие продолжит с этого места."
            }
        }
        return finish(run, totalDays = daysWalked, historyNote = note)
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

        val windowDays = (to.toEpochDay() - from.toEpochDay() + 1).toInt()
        val daysWithDataFromMarks = daysKnownToHaveData.count { it in from.toEpochDay()..to.toEpochDay() }

        // Three independent questions, not one. Skipping a whole window when the per-day
        // sections were settled ALSO skipped its activity list and its weight - so a window
        // whose activity list had failed in an earlier pass could never be retried, and
        // months of workouts stayed missing with nothing in the log to say so. Each
        // range-shaped section carries its own mark now (see GarminSyncMark.SECTION_ACTIVITIES).
        val daysSettled = allSettled(from, to, settled, refreshFromEpochDay, GarminSyncMark.DAY_SECTIONS)
        val activitiesSettled =
            allSettled(from, to, settled, refreshFromEpochDay, listOf(GarminSyncMark.SECTION_ACTIVITIES))
        val weightSettled =
            allSettled(from, to, settled, refreshFromEpochDay, listOf(GarminSyncMark.SECTION_WEIGHT))

        // Nothing left to ask about at all: the window costs zero requests. That is what
        // keeps a re-walk over synced history from burning the NETWORK_FAILURE_LIMIT budget
        // before it ever reaches ground it hasn't seen.
        if (daysSettled && activitiesSettled && weightSettled) {
            listOf(run.summary, run.sleep, run.hrv, run.readiness, run.training).forEach { it.skipped += windowDays }
            run.weight.skipped += windowDays
            run.daysSkipped += windowDays
            return daysWithDataFromMarks
        }

        var stressZones: Map<Long, StressZones> = emptyMap()
        var intensityGoals: Map<Long, Int> = emptyMap()
        var hydration: Map<Long, Pair<Int, Int>> = emptyMap()

        if (!daysSettled) {
            // Range-shaped endpoints first, one call per window instead of one per day.
            // Their results are folded into the per-day summary row below - so they are
            // only worth fetching when there is a day left to fold them into.
            stressZones = run.take(run.ranges) { apiClient.stressZones(from, to) } ?: emptyMap()
            intensityGoals = run.take(run.ranges) { apiClient.intensityMinutesGoal(from, to) } ?: emptyMap()
            hydration = run.take(run.ranges) { apiClient.hydration(from, to) } ?: emptyMap()

            // The window calls above go through take() like everything else, so the
            // connection can already be gone before a single day is asked about. Checking
            // here as well as in the loop is what stops that case from being reported as a
            // clean finish.
            if (run.networkLost) {
                AppLog.w("GarminSyncManager", "Сеть пропала на диапазонных запросах $from..$to - окно прервано")
                return daysWithData
            }
        } else {
            listOf(run.summary, run.sleep, run.hrv, run.readiness, run.training).forEach { it.skipped += windowDays }
            run.daysSkipped += windowDays
            daysWithData = daysWithDataFromMarks
        }

        var date = if (daysSettled) to.plusDays(1) else from
        var index = 0
        while (!date.isAfter(to)) {
            // A backfill that outlives the phone's connectivity used to grind through
            // hundreds of doomed requests - 64 identical DNS failures per endpoint in one
            // real log - and still report success. One day's worth of consecutive network
            // failures is enough to conclude the network is gone, not the data.
            if (run.networkLost) {
                AppLog.w("GarminSyncManager", "Сеть пропала на $date - синк прерван, загружено дней=${run.daysWritten}")
                break
            }
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

            date = date.plusDays(1)
            index++
        }

        // Both of these are asked once per window, and both now get a per-day mark for the
        // whole window when the call actually answered. A failure leaves no mark, so the
        // next pass over this window asks again - the same rule the day sections follow,
        // and the thing whose absence hid the missing workouts.
        if (!run.networkLost && !weightSettled) {
            val daysWithWeight = mutableSetOf<Long>()
            run.take(run.weight) { apiClient.bodyComposition(from, to) }?.let { rows ->
                val real = rows.filter { !it.isEmpty }
                if (real.isNotEmpty()) {
                    database.garminBodyCompositionDao().upsertAll(real)
                    // += , not = : the history walk runs this once per window, and an
                    // assignment reported only the last window's rows in the summary line.
                    run.weight.stored += real.size
                    daysWithWeight += real.map { it.dateEpochDay }
                }
            }
            markWindow(from, to, GarminSyncMark.SECTION_WEIGHT, daysWithWeight, run.weight, refreshFromEpochDay)
        } else if (weightSettled) {
            run.weight.skipped += windowDays
        }

        if (!run.networkLost && !activitiesSettled) {
            val daysWithActivity = syncActivities(from, to, run)
            markWindow(from, to, GarminSyncMark.SECTION_ACTIVITIES, daysWithActivity, run.activities, refreshFromEpochDay)
        }

        return daysWithData
    }

    /**
     * Whether every day in the window carries a mark for every one of [sections] and is old
     * enough to be skippable at all - i.e. there is nothing left to ask Garmin about for
     * those sections here.
     */
    private fun allSettled(
        from: LocalDate,
        to: LocalDate,
        settled: Set<Pair<Long, String>>,
        refreshFromEpochDay: Long,
        sections: List<String>
    ): Boolean = (from.toEpochDay()..to.toEpochDay()).all { day ->
        day < refreshFromEpochDay && sections.all { (day to it) in settled }
    }

    /**
     * Marks a whole window as settled for one range-shaped section, but only when its call
     * actually answered ([counter] not having failed last) - a failed call must stay
     * unmarked so the next pass retries it. [daysWithData] are the days the answer put a
     * row on; the rest are marked as "Garmin has nothing here", which is just as settled.
     *
     * Days inside the always-refresh window are left unmarked deliberately: marking them
     * would be pointless (they are re-read regardless) and would make the mark table churn.
     */
    private suspend fun markWindow(
        from: LocalDate,
        to: LocalDate,
        section: String,
        daysWithData: Set<Long>,
        counter: Counter,
        refreshFromEpochDay: Long
    ) {
        if (counter.lastCallFailed) return
        val marks = (from.toEpochDay()..to.toEpochDay())
            .filter { it < refreshFromEpochDay }
            .map { GarminSyncMark(it, section, it in daysWithData, LOGIC_VERSION) }
        if (marks.isNotEmpty()) database.garminSyncMarkDao().upsertAll(marks)
    }

    /** Turns the accumulated tallies into the one State a whole sync reports. */
    private fun finish(run: RunState, totalDays: Int, historyNote: String? = null): State {
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
                buildString {
                    append("Соединение пропало во время синхронизации. Загружено дней: ${run.daysWritten}$outOf — ")
                    append("нажмите ещё раз, синк продолжится с того же места.")
                    if (historyNote != null) append(" ").append(historyNote)
                }
            )
            run.daysWritten == 0 && run.daysSkipped == 0 && sections.any { it.hasProblem } -> State.Failed(
                "Garmin не отдал данные (${sections.filter { it.hasProblem }.joinToString { it.label }}). Возможно, нужно войти заново."
            )
            else -> State.Success(run.daysWritten, run.daysSkipped, System.currentTimeMillis(), sections, historyNote)
        }.also { _state.value = it }
    }

    /**
     * Activities are range-shaped, not per-day, so they aren't marked like the sections
     * above - instead the detail call is skipped for every activity already carrying it
     * (`detailsLoaded`). On a repeated 90-day backfill that is the single biggest saving:
     * one request per activity, every time, for activities that never change.
     */
    private suspend fun syncActivities(from: LocalDate, to: LocalDate, run: RunState): Set<Long> {
        val listed = run.take(run.activities) { apiClient.activities(from, to) } ?: return emptySet()
        val days = listed.map { it.dateEpochDay }.toSet()
        val alreadyDetailed = database.garminActivityDao()
            .detailedIdsInRange(from.toEpochDay(), to.toEpochDay())
            .toSet()

        val fresh = listed.filter { it.activityId !in alreadyDetailed }
        run.activities.skipped += listed.size - fresh.size
        if (fresh.isEmpty()) return days

        val detailed = fresh.map { apiClient.activityDetail(it) }
        database.garminActivityDao().upsertAll(detailed)
        run.activities.stored += detailed.size
        return days
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
         * The user's configured heart-rate zones - one request per sync, not per day: it
         * is account configuration, not a daily reading. Its own counter because it fails
         * on its own terms, and because "Garmin has no zones for this account" has to stay
         * distinguishable from "the call didn't go through" (GarminFetch, as everywhere).
         */
        val zones = Counter("Пульсовые зоны")

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
            listOf(summary, sleep, hrv, readiness, training, weight, activities, zones).map {
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
