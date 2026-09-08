package com.fitnessapp.summary.scale

import com.fitnessapp.summary.data.AppDatabase
import com.fitnessapp.summary.debug.AppLog
import com.fitnessapp.summary.garmin.GarminApiClient
import com.fitnessapp.summary.garmin.GarminAuthClient
import com.fitnessapp.summary.garmin.GarminFetch
import com.fitnessapp.summary.garmin.GarminWeightUploader
import com.fitnessapp.summary.garmin.valueOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * The scale pipeline: Zepp Life cloud -> `scale_measurements` -> (optionally) Garmin.
 *
 * Runs on app start when a Zepp Life session exists, and on demand. Two halves, each
 * independently skippable:
 *
 * 1. **Pull** - everything newer than the latest stored weigh-in (or the whole history
 *    the first time). Zepp records never change after the fact, so "newer than what we
 *    have" is a safe incremental rule; there is no Garmin-style late revision to chase.
 *    Re-syncing a record preserves its upload stamp - a REPLACE upsert would otherwise
 *    reset it and re-push the whole history every time.
 * 2. **Push** - only if the user left "Отправлять в Garmin" on and Garmin is logged in.
 *    Before uploading, Garmin is asked which weigh-in instants it already holds, so a
 *    reading that got there earlier (through this app, or another tool) is marked as
 *    uploaded rather than sent again. Then the rest goes up in FIT files of at most
 *    [GarminWeightUploader.MAX_PER_FILE], and Garmin's own copy of the range is re-read
 *    into garmin_body_composition so the Day screen shows the upload landed.
 *
 * Deliberately not part of [com.fitnessapp.summary.garmin.GarminSyncManager]: that one is
 * a read-only mirror of Garmin, this one WRITES to Garmin, and the two failure modes
 * (an expired Zepp token; Garmin rejecting a file) should never be reported as one.
 */
class ScaleSyncManager(
    private val zeppApi: ZeppApiClient,
    private val zeppTokens: ZeppTokenStore,
    private val garminAuth: GarminAuthClient,
    private val garminApi: GarminApiClient,
    private val uploader: GarminWeightUploader,
    private val database: AppDatabase
) {
    sealed class State {
        data object Idle : State()
        data class Running(val step: String) : State()
        data class Success(
            val newMeasurements: Int,
            val uploadedToGarmin: Int,
            val pendingUpload: Int,
            val atMillis: Long
        ) : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun sync(): State {
        if (!zeppTokens.isLoggedIn) {
            return State.Failed("Нет входа в Zepp Life").also { _state.value = it }
        }
        AppLog.i("ScaleSyncManager", "Синк весов начат")
        val dao = database.scaleMeasurementDao()

        // ---- 1. pull -------------------------------------------------------------
        _state.value = State.Running("Читаю взвешивания из Zepp Life...")
        val since = dao.latestTimestamp() ?: 0L
        val pulled = when (val fetch = zeppApi.weighIns(since)) {
            is GarminFetch.Ok -> fetch.value
            GarminFetch.NoData -> emptyList()
            is GarminFetch.Failed -> {
                AppLog.w("ScaleSyncManager", "Zepp Life не отдал взвешивания: ${fetch.reason}")
                return State.Failed("Zepp Life: ${fetch.reason}").also { _state.value = it }
            }
        }
        if (pulled.isNotEmpty()) {
            val stamps = dao.uploadedTimestamps().associate { it.timestampMillis to it.garminUploadedAtMillis }
            dao.upsertAll(pulled.map { it.copy(garminUploadedAtMillis = stamps[it.timestampMillis] ?: 0L) })
        }
        AppLog.i("ScaleSyncManager", "Из Zepp Life получено новых взвешиваний: ${pulled.size}")

        // ---- 2. push -------------------------------------------------------------
        var uploaded = 0
        val pending = dao.pendingGarminUpload()
        val pushWanted = zeppTokens.uploadToGarmin && garminAuth.isLoggedIn
        if (pushWanted && pending.isNotEmpty()) {
            _state.value = State.Running("Отправляю в Garmin: ${pending.size}...")
            val from = LocalDate.ofEpochDay(pending.first().dateEpochDay)
            val to = maxOf(LocalDate.ofEpochDay(pending.last().dateEpochDay), LocalDate.now())

            // What Garmin already has, so nothing is sent twice. A failure here means we
            // can't tell, so the push waits for next time rather than risking duplicates.
            val known = when (val fetch = garminApi.weightTimestampsSeconds(from, to)) {
                is GarminFetch.Ok -> fetch.value
                GarminFetch.NoData -> emptySet()
                is GarminFetch.Failed -> {
                    AppLog.w("ScaleSyncManager", "Не удалось узнать, что уже есть в Garmin - отправка отложена: ${fetch.reason}")
                    return State.Success(pulled.size, 0, pending.size, System.currentTimeMillis()).also { _state.value = it }
                }
            }
            val now = System.currentTimeMillis()
            val alreadyThere = pending.filter { it.timestampMillis / 1000 in known }
            if (alreadyThere.isNotEmpty()) {
                dao.markUploaded(alreadyThere.map { it.timestampMillis }, now)
                AppLog.i("ScaleSyncManager", "Уже были в Garmin, отмечены без отправки: ${alreadyThere.size}")
            }

            val toUpload = pending.filter { it.timestampMillis / 1000 !in known }
            for (chunk in toUpload.chunked(GarminWeightUploader.MAX_PER_FILE)) {
                when (val result = uploader.upload(chunk)) {
                    is GarminFetch.Ok -> {
                        dao.markUploaded(chunk.map { it.timestampMillis }, System.currentTimeMillis())
                        uploaded += result.value
                    }
                    GarminFetch.NoData -> Unit
                    is GarminFetch.Failed -> {
                        AppLog.w("ScaleSyncManager", "Отправка в Garmin прервана: ${result.reason}")
                        return State.Failed("Garmin не принял вес: ${result.reason}. Загружено до сбоя: $uploaded")
                            .also { _state.value = it }
                    }
                }
            }

            if (uploaded > 0 || alreadyThere.isNotEmpty()) {
                // Make Garmin's own copy visible right away instead of waiting for the next
                // daily Garmin sync to notice it.
                _state.value = State.Running("Перечитываю вес из Garmin...")
                garminApi.bodyComposition(from, to).valueOrNull()?.filter { !it.isEmpty }?.let { rows ->
                    if (rows.isNotEmpty()) database.garminBodyCompositionDao().upsertAll(rows)
                }
            }
        }

        val stillPending = dao.pendingGarminUpload().size
        AppLog.i(
            "ScaleSyncManager",
            "Синк весов завершён: новых=${pulled.size}, отправлено в Garmin=$uploaded, ожидают=$stillPending" +
                if (!pushWanted) " (отправка в Garmin выключена или нет входа в Garmin)" else ""
        )
        return State.Success(pulled.size, uploaded, stillPending, System.currentTimeMillis()).also { _state.value = it }
    }
}
