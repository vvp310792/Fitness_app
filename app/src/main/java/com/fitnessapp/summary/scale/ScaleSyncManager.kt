package com.fitnessapp.summary.scale

import android.content.Context
import com.fitnessapp.summary.data.AppDatabase
import com.fitnessapp.summary.data.ScaleMeasurement
import com.fitnessapp.summary.debug.AppLog
import com.fitnessapp.summary.garmin.GarminApiClient
import com.fitnessapp.summary.garmin.GarminAuthClient
import com.fitnessapp.summary.garmin.GarminFetch
import com.fitnessapp.summary.garmin.GarminWeightUploader
import com.fitnessapp.summary.garmin.valueOrNull
import com.fitnessapp.summary.health.HealthConnectManager
import com.fitnessapp.summary.health.HealthConnectScaleReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs

/**
 * The scale pipeline: weigh-ins -> `scale_measurements` -> (optionally) Garmin.
 *
 * Two sources feed the table, either or both may be active:
 *
 * - **Health Connect** ([HealthConnectScaleReader]) - the primary one. The user's chain is
 *   Mi scale -> Zepp Life -> Google Fit -> Health Connect, and from there this app reads
 *   weight the same way it reads steps, with nothing but a Health Connect permission.
 *   Re-read with an overlap of [HC_RESYNC_OVERLAP] behind the latest stored row, because
 *   Google Fit forwards Zepp Life's data with a delay and can drop a weigh-in into the
 *   past; the first read goes [HC_FIRST_WINDOW] back and Health Connect returns whatever
 *   it has (30 days without READ_HEALTH_DATA_HISTORY, all of it with).
 * - **Zepp Life cloud** ([ZeppApiClient]) - optional, needs a Xiaomi login, richer rows
 *   (body score, protein, impedance). Kept for installs where it works; not required.
 *
 * With both active the same step onto the scale arrives twice at slightly different
 * timestamps (Google Fit re-stamps to the second). A row within [DUPLICATE_WINDOW] of one
 * already stored from the *other* source is dropped, first come first served, so the
 * table - and Garmin - see each weigh-in once. Re-storing a row preserves its upload
 * stamp: a REPLACE upsert would otherwise reset it and re-push the history every time.
 *
 * **Push** - only if "Отправлять в Garmin" is on and Garmin is logged in. Before
 * uploading, Garmin is asked which weigh-in instants it already holds, so a reading that
 * got there earlier (through this app or another tool) is marked as uploaded rather than
 * sent again. Then the rest goes up in FIT files of at most
 * [GarminWeightUploader.MAX_PER_FILE], and Garmin's own copy of the range is re-read into
 * garmin_body_composition so the Day screen shows the upload landed.
 *
 * Deliberately not part of [com.fitnessapp.summary.garmin.GarminSyncManager]: that one is
 * a read-only mirror of Garmin, this one WRITES to Garmin, and the two failure modes
 * should never be reported as one.
 */
class ScaleSyncManager(
    context: Context,
    private val healthConnect: HealthConnectManager,
    private val healthReader: HealthConnectScaleReader,
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
            val atMillis: Long,
            /** A source that misbehaved while the other delivered - shown, not fatal. */
            val note: String? = null
        ) : State()
        data class Failed(val reason: String) : State()
    }

    private val prefs = context.getSharedPreferences("scale_sync", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * The switch for the one thing this app writes to Garmin. Defaults to the value the
     * earlier Zepp-only build stored, so an existing install keeps its choice.
     */
    var uploadToGarmin: Boolean
        get() = prefs.getBoolean(KEY_UPLOAD_TO_GARMIN, zeppTokens.uploadToGarmin)
        set(value) = prefs.edit().putBoolean(KEY_UPLOAD_TO_GARMIN, value).apply()

    suspend fun healthConnectReadable(): Boolean = healthConnect.isAvailable && healthConnect.canReadWeight()

    /** Anything to sync from at all - decides whether the start-up sync runs. */
    suspend fun hasAnySource(): Boolean = healthConnectReadable() || zeppTokens.isLoggedIn

    suspend fun sync(): State {
        val fromHealthConnect = healthConnectReadable()
        val fromZepp = zeppTokens.isLoggedIn
        if (!fromHealthConnect && !fromZepp) {
            return State.Failed(
                "Нет источника взвешиваний: выдайте разрешение на чтение веса в Health Connect " +
                    "или войдите в Zepp Life"
            ).also { _state.value = it }
        }
        AppLog.i("ScaleSyncManager", "Синк весов начат (Health Connect=$fromHealthConnect, Zepp=$fromZepp)")
        val dao = database.scaleMeasurementDao()
        val problems = ArrayList<String>()
        var newRows = 0

        // ---- 1. pull: Health Connect ------------------------------------------------
        if (fromHealthConnect) {
            _state.value = State.Running("Читаю вес из Health Connect...")
            val latest = dao.latestTimestampForSource(ScaleMeasurement.SOURCE_HEALTH_CONNECT)
            val from = if (latest == null) Instant.now().minus(HC_FIRST_WINDOW)
            else Instant.ofEpochMilli(latest).minus(HC_RESYNC_OVERLAP)
            val rows = healthReader.readWeighIns(from, Instant.now().plus(Duration.ofHours(1)))
            if (rows == null) {
                problems += "Health Connect не отдал вес"
            } else {
                newRows += store(rows)
                AppLog.i("ScaleSyncManager", "Health Connect: взвешиваний в окне ${rows.size}")
            }
        }

        // ---- 1b. pull: Zepp Life cloud -----------------------------------------------
        if (fromZepp) {
            _state.value = State.Running("Читаю взвешивания из Zepp Life...")
            val since = dao.latestTimestampForSource(ScaleMeasurement.SOURCE_ZEPP) ?: 0L
            when (val fetch = zeppApi.weighIns(since)) {
                is GarminFetch.Ok -> {
                    newRows += store(fetch.value)
                    AppLog.i("ScaleSyncManager", "Zepp Life: новых взвешиваний ${fetch.value.size}")
                }
                GarminFetch.NoData -> Unit
                is GarminFetch.Failed -> {
                    AppLog.w("ScaleSyncManager", "Zepp Life не отдал взвешивания: ${fetch.reason}")
                    problems += "Zepp Life: ${fetch.reason}"
                }
            }
        }

        if (problems.size == listOf(fromHealthConnect, fromZepp).count { it }) {
            // Every active source failed - nothing was read, say so plainly.
            return State.Failed(problems.joinToString("; ")).also { _state.value = it }
        }

        // ---- 2. push -------------------------------------------------------------
        var uploaded = 0
        val pending = dao.pendingGarminUpload()
        val pushWanted = uploadToGarmin && garminAuth.isLoggedIn
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
                    return State.Success(newRows, 0, pending.size, System.currentTimeMillis(), "Garmin: ${fetch.reason}")
                        .also { _state.value = it }
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
            "Синк весов завершён: новых=$newRows, отправлено в Garmin=$uploaded, ожидают=$stillPending" +
                (if (!pushWanted) " (отправка в Garmin выключена или нет входа в Garmin)" else "") +
                (if (problems.isNotEmpty()) "; проблемы: ${problems.joinToString("; ")}" else "")
        )
        return State.Success(
            newRows, uploaded, stillPending, System.currentTimeMillis(),
            problems.takeIf { it.isNotEmpty() }?.joinToString("; ")
        ).also { _state.value = it }
    }

    /**
     * Upserts [rows], dropping cross-source duplicates and preserving upload stamps.
     * Returns how many were not in the table before.
     */
    private suspend fun store(rows: List<ScaleMeasurement>): Int {
        if (rows.isEmpty()) return 0
        val dao = database.scaleMeasurementDao()
        val window = DUPLICATE_WINDOW.toMillis()
        val existing = dao.stampsBetween(
            rows.minOf { it.timestampMillis } - window,
            rows.maxOf { it.timestampMillis } + window
        )
        val exactKeys = existing.map { it.timestampMillis }.toHashSet()
        val uploaded = dao.uploadedTimestamps().associate { it.timestampMillis to it.garminUploadedAtMillis }

        val kept = rows.filter { row ->
            row.timestampMillis in exactKeys || existing.none { other ->
                other.source != row.source && abs(other.timestampMillis - row.timestampMillis) <= window
            }
        }
        val dropped = rows.size - kept.size
        if (dropped > 0) AppLog.d("ScaleSyncManager", "Пропущено как дубликаты другого источника: $dropped")

        dao.upsertAll(kept.map { it.copy(garminUploadedAtMillis = uploaded[it.timestampMillis] ?: 0L) })
        return kept.count { it.timestampMillis !in exactKeys }
    }

    private companion object {
        const val KEY_UPLOAD_TO_GARMIN = "upload_to_garmin"

        /** First read: as far back as Health Connect will go. */
        val HC_FIRST_WINDOW: Duration = Duration.ofDays(3 * 365)

        /** Later reads: behind the latest stored row, to catch Google Fit's late forwarding. */
        val HC_RESYNC_OVERLAP: Duration = Duration.ofDays(14)

        /** Two records this close from different sources are one step onto the scale. */
        val DUPLICATE_WINDOW: Duration = Duration.ofMinutes(3)
    }
}
