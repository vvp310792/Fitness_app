package com.fitnessapp.summary.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fitnessapp.summary.FitnessSummaryApp
import com.fitnessapp.summary.debug.AppLog
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class SnapshotRefreshState(
    val ownerUid: String,
    val running: Boolean = false,
    val progress: SnapshotProgress? = null,
    val status: ServerCatalogStatus? = null,
    val error: String? = null,
    val notice: String? = null
)

/** Application-wide coordinator. Manual, launch and periodic requests use the
 * same importer and mutex, never stage competing generations. */
class SnapshotSyncCoordinator(private val app: FitnessSummaryApp) {
    private val work get() = WorkManager.getInstance(app)
    private val importer by lazy {
        ServerSnapshotImporter(RoomServerSnapshotSink(ServerSnapshotDatabase.getInstance(app)))
    }
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<SnapshotRefreshState?>(null)
    val state = mutableState.asStateFlow()

    // An explicit check must not be stopped merely because WorkManager loses a
    // scheduling constraint. The application owns this scope, not a screen.
    private val immediateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var immediateJob: Job? = null
    private var immediateOwner: String? = null

    fun ensurePeriodic() {
        if (!FirebaseSetup.isConfigured || app.authManager.currentUser == null) return
        val request = PeriodicWorkRequestBuilder<SnapshotSyncWorker>(1, TimeUnit.HOURS, 15, TimeUnit.MINUTES)
            .setInitialDelay(1, TimeUnit.HOURS)
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** On start, sign-in or manual request: attempt immediately, without WorkManager
     * constraints. Retain durable scheduled work for offline/transient failures. */
    @Synchronized
    fun enqueueNow() {
        if (!FirebaseSetup.isConfigured) return
        val uid = app.authManager.currentUser?.uid ?: return
        if (immediateJob?.isActive == true && immediateOwner == uid) return
        immediateJob?.cancel()
        if (!hasInternetNetwork()) {
            val previous = mutableState.value?.takeIf { it.ownerUid == uid }
            if (previous?.running != true) {
                mutableState.value = (previous ?: SnapshotRefreshState(uid)).copy(
                    error = null, notice = "Нет сети. Проверка запланирована при подключении."
                )
            }
            enqueueDeferred(uid)
            return
        }
        immediateOwner = uid
        immediateJob = immediateScope.launch {
            try {
                refresh(uid, "immediate")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (retryable(e)) enqueueDeferred(uid, 30)
            }
        }
    }

    private fun hasInternetNetwork(): Boolean = try {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        network != null && manager?.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: SecurityException) {
        true // Let the real request diagnose connectivity.
    }

    @Synchronized
    private fun enqueueDeferred(uid: String, delaySeconds: Long = 0) {
        if (app.authManager.currentUser?.uid != uid) return
        val request = OneTimeWorkRequestBuilder<SnapshotSyncWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        work.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    @Synchronized
    fun cancelForSignOut() {
        immediateJob?.cancel()
        immediateJob = null
        immediateOwner = null
        work.cancelUniqueWork(NOW)
        work.cancelUniqueWork(PERIODIC)
        mutableState.value = null
    }

    suspend fun refresh(uid: String, trigger: String = "scheduled"): ServerCatalogStatus = mutex.withLock {
        val gateway = FirebaseServerSnapshotGateway(uid)
        gateway.requireOwner()
        val previous = mutableState.value?.takeIf { it.ownerUid == uid }
        val prior = previous?.status
        mutableState.value = SnapshotRefreshState(uid,running=true,status=prior)
        try {
            val status = withContext(Dispatchers.IO) {
                importer.refresh(uid,gateway) { p ->
                    mutableState.value = SnapshotRefreshState(uid,true,p,prior)
                }
            }
            gateway.requireOwner()
            val progress = mutableState.value?.takeIf { it.ownerUid == uid }?.progress
            mutableState.value = SnapshotRefreshState(uid,false,progress,status)
            AppLog.i("ServerSnapshot","Refresh completed; reused=${progress?.reusedParts ?: 0}; downloaded=${progress?.downloadedParts ?: 0}")
            status
        } catch (e: CancellationException) {
            if (app.authManager.currentUser?.uid == uid) {
                val phase = mutableState.value?.progress?.phase ?: "START"
                AppLog.w("ServerSnapshot","Refresh deferred; trigger=$trigger; phase=$phase")
                mutableState.value = SnapshotRefreshState(uid,progress=previous?.progress,status=prior,
                    notice="Проверка отложена. Проверенная локальная копия не удаляется; повторная проверка при наличии сети или открытии приложения.")
            }
            throw e
        } catch (e: Exception) {
            val message = safeError(e)
            AppLog.w("ServerSnapshot",message)
            if (app.authManager.currentUser?.uid == uid) {
                mutableState.value = SnapshotRefreshState(uid,status=prior,progress=previous?.progress,
                    error=if (retryable(e)) null else message,
                    notice=if (retryable(e)) "Временный сбой (${reasonCode(e)}). Проверка будет повторена." else null)
            }
            throw e
        }
    }

    companion object {
        internal const val NOW = "fitness-verified-snapshot-now"
        internal const val PERIODIC = "fitness-verified-snapshot-periodic"
        private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        internal fun reasonCode(e: Exception): String {
            val firestore = (e as? FirebaseFirestoreException) ?: (e.cause as? FirebaseFirestoreException)
            return firestore?.code?.name ?: (e as? SnapshotImportFailure)?.reasonCode ?: e.javaClass.simpleName
        }

        internal fun retryable(e: Exception): Boolean = reasonCode(e) in setOf(
            "TIMEOUT","PUBLICATION_CHANGED","UNAVAILABLE","DEADLINE_EXCEEDED","ABORTED","INTERNAL","UNKNOWN",
            "TimeoutCancellationException","SnapshotTaskInterrupted"
        )

        private fun safeError(e: Exception): String {
            val f = e as? SnapshotImportFailure
            return if (f == null) "Ошибка обновления: ${reasonCode(e)}. Предыдущая копия сохранена."
            else "Ошибка ${f.phase}: ${reasonCode(e)} (${f.completedParts}/${f.totalParts} блоков). Предыдущая копия сохранена."
        }
    }
}

/** WorkManager may defer execution in Doze or when Android restricts background
 * work. Each run re-checks the current owner; there are no stored cloud credentials. */
class SnapshotSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? FitnessSummaryApp ?: return Result.failure()
        if (!FirebaseSetup.isConfigured) return Result.success()
        val uid = app.authManager.currentUser?.uid ?: return Result.success()
        return try {
            app.snapshotSync.refresh(uid)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (app.authManager.currentUser?.uid != uid) Result.success()
            else if (SnapshotSyncCoordinator.retryable(e) && runAttemptCount < 3) Result.retry()
            else Result.failure()
        }
    }
}
