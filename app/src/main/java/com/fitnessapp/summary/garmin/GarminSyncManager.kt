package com.fitnessapp.summary.garmin

import com.fitnessapp.summary.data.GarminDailyExtra
import com.fitnessapp.summary.data.GarminDailyExtraDao
import com.fitnessapp.summary.debug.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * Pulls Stress + Body Battery from Garmin Connect directly into Room, day by day - the
 * unofficial counterpart to [com.fitnessapp.summary.health.HealthSyncManager], for the
 * two metrics that source can never provide. Deliberately its own sync path rather than
 * folded into that one: it has a completely different failure mode (a login that can
 * expire and need re-auth, an unofficial endpoint that can change shape without notice)
 * that a user who never sets this up should never be affected by.
 */
class GarminSyncManager(
    private val apiClient: GarminApiClient,
    private val dao: GarminDailyExtraDao
) {
    sealed class State {
        data object Idle : State()
        data class Running(val done: Int, val total: Int) : State()
        data class Success(val daysWritten: Int) : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun syncRange(from: LocalDate, to: LocalDate): State {
        AppLog.i("GarminSyncManager", "Синк Garmin $from..$to начат")
        val totalDays = (to.toEpochDay() - from.toEpochDay() + 1).toInt().coerceAtLeast(1)
        var written = 0

        var date = from
        var index = 0
        while (!date.isAfter(to)) {
            _state.value = State.Running(index, totalDays)
            val extra = apiClient.dailyExtra(date)
            if (extra != null && !extra.isEmpty) {
                dao.upsert(
                    GarminDailyExtra(
                        dateEpochDay = date.toEpochDay(),
                        averageStressLevel = extra.averageStressLevel,
                        maxStressLevel = extra.maxStressLevel,
                        bodyBatteryAtWake = extra.bodyBatteryAtWake,
                        bodyBatteryHighest = extra.bodyBatteryHighest,
                        bodyBatteryLowest = extra.bodyBatteryLowest
                    )
                )
                written++
            }
            date = date.plusDays(1)
            index++
        }

        AppLog.i("GarminSyncManager", "Синк Garmin завершён: записано дней=$written из $totalDays")
        return State.Success(written).also { _state.value = it }
    }
}
