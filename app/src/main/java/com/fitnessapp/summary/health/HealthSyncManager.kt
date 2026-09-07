package com.fitnessapp.summary.health

import android.content.Context
import com.fitnessapp.summary.data.SummaryRepository
import com.fitnessapp.summary.data.WorkoutRepository
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

    suspend fun syncRange(from: LocalDate, to: LocalDate): State {
        if (!healthConnect.isAvailable) {
            return State.Failed("Health Connect недоступен на этом устройстве").also { _state.value = it }
        }
        if (!healthConnect.hasAllPermissions()) {
            // Not fatal - a partial grant still reads what it's allowed to - but the
            // user should know why some cards stay empty.
            if (healthConnect.grantedPermissions().isEmpty()) {
                return State.Failed("Нет разрешений на чтение данных").also { _state.value = it }
            }
        }

        val totalDays = (to.toEpochDay() - from.toEpochDay() + 1).toInt().coerceAtLeast(1)
        var written = 0

        return try {
            var date = from
            var index = 0
            while (!date.isAfter(to)) {
                _state.value = State.Running(index, totalDays)
                val result = reader.readDay(date)

                if (result != null && !(result.summary.isEmpty && result.workouts.isEmpty())) {
                    summaryRepository.upsertDay(result.summary)
                    // Only touches the workouts table on a day we know we read
                    // successfully - see the guard above. On a day that came back
                    // wholly empty we leave whatever is already stored alone rather
                    // than treating silence as a deletion.
                    workoutRepository.replaceDay(date, result.workouts)
                    written++
                }

                date = date.plusDays(1)
                index++
            }
            lastSyncMillis = System.currentTimeMillis()
            State.Success(written, lastSyncMillis).also { _state.value = it }
        } catch (e: Exception) {
            State.Failed(e.message ?: "Не удалось прочитать данные").also { _state.value = it }
        }
    }

    companion object {
        private const val KEY_LAST_SYNC = "last_sync_millis"

        /** Two weeks back on every open - comfortably covers a late-syncing watch. */
        const val DEFAULT_RECENT_DAYS = 14

        /** Roughly three months, enough for week-over-week trends to mean something. */
        const val DEFAULT_BACKFILL_DAYS = 90
    }
}
