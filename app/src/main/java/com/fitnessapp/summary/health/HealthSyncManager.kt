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
        data class Running(val done: Int, val total: Int) : State()
        data class Success(val daysWritten: Int, val atMillis: Long) : State()
        data class Failed(val reason: String) : State()
    }

    private val prefs = context.getSharedPreferences("health_sync", Context.MODE_PRIVATE)

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
     * "Runs out" is [STOP_AFTER_EMPTY_DAYS] consecutive days with nothing from any source -
     * long enough that a holiday with the phone left at home, or a dead battery week,
     * doesn't end the walk. [MAX_HISTORY_DAYS] is only a backstop against a provider that
     * answers forever.
     *
     * Note this is the expensive path by construction: every empty day costs two reads, the
     * Garmin-scoped one and the unscoped fallback. That is the price of the fallback being
     * narrow, and it is paid on a button press, never on startup.
     */
    suspend fun syncAllHistory(): State {
        val today = LocalDate.now()
        var cursor = today
        var consecutiveEmpty = 0
        var totalWritten = 0
        var oldestWithData: LocalDate? = null

        while (consecutiveEmpty < STOP_AFTER_EMPTY_DAYS &&
            today.toEpochDay() - cursor.toEpochDay() < MAX_HISTORY_DAYS
        ) {
            val windowEnd = cursor
            val windowStart = cursor.minusDays((HISTORY_WINDOW_DAYS - 1).toLong())

            when (val result = syncRange(windowStart, windowEnd)) {
                // A failure here is a permission or availability problem, not "no data" -
                // continuing would spend fifteen years of queries on the same error.
                is State.Failed -> return result
                is State.Success -> {
                    totalWritten += result.daysWritten
                    if (result.daysWritten > 0) {
                        consecutiveEmpty = 0
                        oldestWithData = windowStart
                    } else {
                        consecutiveEmpty += HISTORY_WINDOW_DAYS
                    }
                }
                else -> Unit
            }
            cursor = windowStart.minusDays(1)
        }

        AppLog.i(
            "HealthSyncManager",
            "Вся история Health Connect: записано дней=$totalWritten, " +
                "самый ранний с данными=${oldestWithData ?: "нет"}, остановились на $cursor"
        )
        lastSyncMillis = System.currentTimeMillis()
        return State.Success(totalWritten, lastSyncMillis).also { _state.value = it }
    }

    suspend fun syncRange(from: LocalDate, to: LocalDate): State {
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

        return try {
            var date = from
            var index = 0
            while (!date.isAfter(to)) {
                _state.value = State.Running(index, totalDays)

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
                    "не от Garmin=$otherSourceDays, всего=$totalDays"
            )
            State.Success(written, lastSyncMillis).also { _state.value = it }
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
         * Four months of nothing at all means the history has ended, not that the user
         * went on holiday. Shorter would stop inside a real gap; much longer just spends
         * queries on years that were never recorded.
         */
        private const val STOP_AFTER_EMPTY_DAYS = 120

        /** Backstop only - roughly 15 years. */
        private const val MAX_HISTORY_DAYS = 15 * 365
    }
}
