package com.fitnessapp.summary.health

import android.content.Context
import com.fitnessapp.summary.data.SummaryRepository
import com.fitnessapp.summary.data.WorkoutRepository
import com.fitnessapp.summary.debug.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * Pulls Health Connect into Room, day by day.
 *
 * Health Connect has no change feed we can subscribe to, so there is no such thing
 * as a live listener here - the app re-reads a window of recent days whenever it
 * opens, plus on demand. That's cheap (a handful of aggregate queries per day) and
 * it's also *necessary*: Garmin backfills and revises past days when the watch syncs
 * late, so yesterday's numbers can legitimately change after the fact. Only re-reading
 * new days would leave those revisions permanently stale.
 */
class HealthSyncManager(
    context: Context,
    private val healthConnect: HealthConnectManager,
    private val reader: HealthConnectReader,
    private val summaryRepository: SummaryRepository,
    private val workoutRepository: WorkoutRepository
) {

    sealed class State {
        data object Idle : State()

        /**
         * @param note what the whole run is doing, when the window alone would misrepresent
         *   it. The history walk reads a month at a time, so "день 17 из 30" is true of the
         *   window and says nothing about the walk - and repeated verbatim on every window it
         *   reads as the same thirty days over and over, which is exactly how the walk that
         *   never advanced looked from the outside.
         */
        data class Running(val done: Int, val total: Int, val note: String = "") : State()
        /**
         * @param daysUnreadable days whose sections threw instead of answering. Separate
         *   from [daysWritten] because "nothing there" and "could not look" must never
         *   collapse into one number - see [syncAllHistory].
         */
        data class Success(
            val daysWritten: Int,
            val atMillis: Long,
            val daysUnreadable: Int = 0
        ) : State()
        data class Failed(val reason: String) : State()
    }

    private val prefs = context.getSharedPreferences("health_sync", Context.MODE_PRIVATE)

    private val historyStore = HealthHistoryStore(context)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    var lastSyncMillis: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        private set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    /** True once any sync has completed - used to tell "no data yet" from "never synced". */
    val hasEverSynced: Boolean get() = lastSyncMillis > 0L

    /**
     * The routine refresh: today plus the preceding [days] - 1 days.
     *
     * The window is deliberately wider than "just today" because of the backfill
     * behaviour described in the class comment - a watch synced on Wednesday can fill
     * in Monday, and a one-day window would never notice.
     */
    suspend fun syncRecent(days: Int = DEFAULT_RECENT_DAYS): State {
        val today = LocalDate.now()
        return syncRange(today.minusDays((days - 1).toLong()), today)
    }

    /**
     * First-run / "pull everything" sync. Reaches back [days] days, which needs the
     * READ_HEALTH_DATA_HISTORY permission to see past 30 - without it Health Connect
     * simply returns nothing for the older dates rather than failing, so this stays
     * safe either way, it just fills in less.
     */
    suspend fun backfill(days: Int = DEFAULT_BACKFILL_DAYS): State {
        val today = LocalDate.now()
        return syncRange(today.minusDays(days.toLong()), today)
    }

    /** Where the frontier of the backwards walk currently stands, for the «Я» tab. */
    fun historyProgress(): HealthHistoryStore.Frontier = historyStore.read(HISTORY_LOGIC_VERSION)

    /** Forgets the frontier, so the next «Вся история» starts over from today. */
    fun forgetHistoryDepth() = historyStore.reset()

    /**
     * Walks backwards until Health Connect runs out of history, however far back that is.
     *
     * Needed because the fixed windows above cannot reach the years this app was built to
     * surface: a phone that counted steps since 2014 is 4000+ days back, and 90 would never
     * see it. A fixed number of years instead would either cut real history short or spend
     * thousands of queries on years that never existed - the same reasoning as
     * `GarminSyncManager.syncAllHistory`, and the same shape of answer: go backwards and
     * stop when the data does.
     *
     * **The walk resumes from the stored frontier, not from today.** The first version did
     * start at today every time, and it had no way of advancing: every press re-read the
     * same first windows, hit the same run of empty months and stopped in the same place.
     * The user's report of it was exact - "несколько раз просто считается 30 дней" - and
     * that is what a walk with no memory looks like from the outside. The frontier moves
     * only per *finished* window: a window read half-way was never claimed, so the next
     * press walks it again rather than skipping whatever the interruption swallowed.
     *
     * "Runs out" is [EMPTY_CHUNKS_TO_STOP] windows in a row with nothing from any source,
     * counted across runs for the same reason - long enough that a changed phone or a
     * half-year of Google Fit not syncing doesn't end the walk at the near edge of a real
     * gap. [MAX_HISTORY_DAYS] is only a backstop against a provider that answers forever.
     *
     * Note this is the expensive path by construction: every empty day costs two reads, the
     * Garmin-scoped one and the unscoped fallback. That is the price of the fallback being
     * narrow, and it is paid on a button press, never on startup.
     */
    suspend fun syncAllHistory(): State {
        val today = LocalDate.now()
        val saved = historyStore.read(HISTORY_LOGIC_VERSION)
        val resumeFrom = saved.oldestCovered

        var emptyChunks = saved.emptyChunks
        var totalWritten = 0
        var daysWalked = 0
        var unreadable = false
        // One day older than the oldest window already finished.
        var windowEnd = resumeFrom?.minusDays(1) ?: today
        val alreadyCovered = (today.toEpochDay() - windowEnd.toEpochDay()).toInt().coerceAtLeast(0)

        AppLog.i(
            "HealthSyncManager",
            when {
                resumeFrom == null -> "Вся история Health Connect: иду назад от $today"
                saved.complete -> "Вся история Health Connect: до конца уже дошли ($resumeFrom), перечитаю только свежие дни"
                else -> "Вся история Health Connect: продолжаю с $windowEnd (пройдено $alreadyCovered дней назад от $today)"
            }
        )

        while (
            !saved.complete &&
            alreadyCovered + daysWalked < MAX_HISTORY_DAYS &&
            emptyChunks < EMPTY_CHUNKS_TO_STOP
        ) {
            val windowStart = windowEnd.minusDays((HISTORY_WINDOW_DAYS - 1).toLong())

            when (val result = syncRange(windowStart, windowEnd, Walk(alreadyCovered + daysWalked))) {
                // A failure here is a permission or availability problem, not "no data" -
                // continuing would spend fifteen years of queries on the same error. The
                // frontier is left where it was, so the next press retries this window.
                is State.Failed -> return result
                is State.Success -> {
                    totalWritten += result.daysWritten
                    // A window we could not READ says nothing about whether data exists in
                    // it, so the walk stops here and records nothing: not the frontier, not
                    // the empty-window count, not "finished". This is the exact hole that
                    // ended the previous walk - Health Connect refuses to aggregate from the
                    // background, 180 days threw SecurityException, every one of them looked
                    // like an empty day, and the walk stamped itself complete at 2024-08-26.
                    if (result.daysUnreadable > 0) {
                        AppLog.w(
                            "HealthSyncManager",
                            "Окно $windowStart..$windowEnd не прочиталось (${result.daysUnreadable} дн.) - " +
                                "останавливаюсь, не делая выводов о глубине истории"
                        )
                        unreadable = true
                        break
                    }
                    if (result.daysWritten > 0) emptyChunks = 0 else emptyChunks++
                }
                else -> Unit
            }

            daysWalked += HISTORY_WINDOW_DAYS
            windowEnd = windowStart.minusDays(1)
            historyStore.advance(
                oldestCoveredEpochDay = windowStart.toEpochDay(),
                emptyChunks = emptyChunks,
                complete = emptyChunks >= EMPTY_CHUNKS_TO_STOP,
                logicVersion = HISTORY_LOGIC_VERSION
            )
        }

        // The recent end, which the walk no longer passes over once it resumes from the
        // frontier. Cheap by comparison (two weeks), and without it pressing «Вся история»
        // on an already-deep frontier would skip precisely the days the watch is still
        // revising.
        if (resumeFrom != null && !unreadable) {
            when (val recent = syncRange(today.minusDays((DEFAULT_RECENT_DAYS - 1).toLong()), today)) {
                is State.Failed -> return recent
                is State.Success -> totalWritten += recent.daysWritten
                else -> Unit
            }
        }

        // Read back rather than taken from the loop: on an early return the current window
        // was never claimed, and reporting it as covered is the optimistic bookkeeping that
        // made the old walk look finished when it wasn't.
        val frontier = historyStore.read(HISTORY_LOGIC_VERSION)
        AppLog.i(
            "HealthSyncManager",
            "Вся история Health Connect: записано дней=$totalWritten, " +
                "пройдено до ${frontier.oldestCovered ?: "нет отметки"}, " +
                "пустых окон подряд=${frontier.emptyChunks}, до конца=${frontier.complete}" +
                if (unreadable) " (прервано: Health Connect не дал прочитать)" else ""
        )
        lastSyncMillis = System.currentTimeMillis()
        if (unreadable) {
            return State.Failed(
                "Health Connect отказал в чтении — скорее всего приложение ушло в фон " +
                    "или не выдан доступ к прошлым данным. Загружено дней: $totalWritten."
            ).also { _state.value = it }
        }
        return State.Success(totalWritten, lastSyncMillis).also { _state.value = it }
    }

    /**
     * Position of a window inside a longer walk. Progress has to describe the walk: a
     * window counter alone repeats itself every thirty days and hides whether anything is
     * moving at all.
     */
    data class Walk(val scannedBefore: Int)

    suspend fun syncRange(from: LocalDate, to: LocalDate, walk: Walk? = null): State {
        AppLog.i("HealthSyncManager", "Синк $from..$to начат")

        if (!healthConnect.isAvailable) {
            AppLog.w("HealthSyncManager", "Health Connect недоступен (${healthConnect.availability()})")
            return State.Failed("Health Connect недоступен на этом устройстве").also { _state.value = it }
        }

        val granted = healthConnect.grantedPermissions()
        val missing = healthConnect.permissions - granted
        if (missing.isNotEmpty()) {
            // Not fatal when partial - a partial grant still reads what it's allowed
            // to - but this is the single most likely explanation for "some data
            // doesn't come through": one or two permission types silently denied
            // while the rest were granted. Logging exactly which ones turns that from
            // a guess into a fact.
            AppLog.w(
                "HealthSyncManager",
                "Не выданы разрешения: ${missing.joinToString { it.substringAfterLast('.') }}"
            )
        }
        if (granted.isEmpty()) {
            return State.Failed("Нет разрешений на чтение данных").also { _state.value = it }
        }

        val totalDays = (to.toEpochDay() - from.toEpochDay() + 1).toInt().coerceAtLeast(1)
        var written = 0
        var emptyDays = 0
        var otherSourceDays = 0
        var unreadableDays = 0

        return try {
            var date = from
            var index = 0
            while (!date.isAfter(to)) {
                _state.value = State.Running(
                    done = index,
                    total = totalDays,
                    note = walk?.let { "Читаю $date - ${it.scannedBefore + index + 1}-й день назад от сегодня" }.orEmpty()
                )

                // Garmin's own records first - that filter is what keeps the phone's
                // pedometer from being added on top of the watch's steps.
                val fromGarmin = reader.readDay(date)

                // ...and only when Garmin knows nothing at all about this day, ask again
                // without the filter. Everything before the watch existed is such a day:
                // for 2014-2021 there is no Garmin record to be doubled, so whatever Google
                // Fit or the phone wrote is the only account of that day there will ever be,
                // and dropping it left those years blank. The narrowness matters - falling
                // back per FIELD instead of per DAY would let phone steps top up a day the
                // watch already covered, which is exactly the 19350-against-10787 bug.
                val result = if (fromGarmin.isBlank()) {
                    reader.readDay(date, HealthConnectReader.ANY_ORIGIN)
                        ?.also { if (!it.isBlank()) otherSourceDays++ }
                } else {
                    fromGarmin
                }

                // A day that threw is not a day that was empty. Counted apart so the
                // history walk can refuse to draw any conclusion from it.
                if (result == null || result.readFailed) unreadableDays++

                if (result != null && !result.isBlank()) {
                    summaryRepository.upsertDay(result.summary)
                    // Only touches the workouts table on a day we know we read
                    // successfully - see the guard above. On a day that came back
                    // wholly empty we leave whatever is already stored alone rather
                    // than treating silence as a deletion.
                    workoutRepository.replaceDay(date, result.workouts)
                    written++
                } else {
                    emptyDays++
                }

                date = date.plusDays(1)
                index++
            }
            lastSyncMillis = System.currentTimeMillis()
            AppLog.i(
                "HealthSyncManager",
                "Синк завершён: записано дней=$written, пустых=$emptyDays, " +
                    "не прочиталось=$unreadableDays, не от Garmin=$otherSourceDays, всего=$totalDays"
            )
            State.Success(written, lastSyncMillis, unreadableDays).also { _state.value = it }
        } catch (e: Exception) {
            AppLog.e("HealthSyncManager", "Синк $from..$to упал", e)
            State.Failed(e.message ?: "Не удалось прочитать данные").also { _state.value = it }
        }
    }

    /** Nothing at all for the day - neither a metric nor a workout. */
    private fun DayReadResult?.isBlank(): Boolean =
        this == null || (summary.isEmpty && workouts.isEmpty())

    companion object {
        private const val KEY_LAST_SYNC = "last_sync_millis"

        /** Two weeks back on every open - comfortably covers a late-syncing watch. */
        const val DEFAULT_RECENT_DAYS = 14

        /** Roughly three months, enough for week-over-week trends to mean something. */
        const val DEFAULT_BACKFILL_DAYS = 90

        /** One month per pass of the backwards walk - small enough to show progress. */
        private const val HISTORY_WINDOW_DAYS = 30

        /**
         * Six empty months in a row means the history has ended, not that the user went on
         * holiday. Deliberately longer than the four this started at: a changed phone, or
         * Google Fit not syncing for a season, is a real gap with real years behind it, and
         * stopping at its near edge looks identical to having reached the beginning.
         * Health Connect reads are local, so the extra windows cost time, not battery or
         * network.
         */
        private const val EMPTY_CHUNKS_TO_STOP = 6

        /**
         * Bumping this invalidates every stored frontier: "Health Connect has nothing older"
         * is a conclusion of the walk's own logic, and a change to that logic can make it
         * wrong. Same role as `GarminSyncManager.LOGIC_VERSION`.
         */
        private const val HISTORY_LOGIC_VERSION = 2

        /** Backstop only - roughly 15 years. */
        private const val MAX_HISTORY_DAYS = 15 * 365
    }
}
