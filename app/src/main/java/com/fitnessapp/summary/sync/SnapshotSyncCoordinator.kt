package com.fitnessapp.summary.sync

import android.content.Context
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
    val error: String? = null
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

    fun ensurePeriodic() {
        if (!FirebaseSetup.isConfigured || app.authManager.currentUser == null) return
        val request = PeriodicWorkRequestBuilder<SnapshotSyncWorker>(1, TimeUnit.HOURS, 15, TimeUnit.MINUTES)
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Safe to call on every activity start or sign-in. KEEP coalesces requests. */
    fun enqueueNow() {
        if (!FirebaseSetup.isConfigured || app.authManager.currentUser == null) return
        val request = OneTimeWorkRequestBuilder<SnapshotSyncWorker>()
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        work.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    fun cancelForSignOut() {
        work.cancelUniqueWork(NOW)
        work.cancelUniqueWork(PERIODIC)
        mutableState.value = null
    }

    suspend fun refresh(uid: String): ServerCatalogStatus = mutex.withLock {
        val gateway = FirebaseServerSnapshotGateway(uid)
        gateway.requireOwner()
        val prior = mutableState.value?.takeIf { it.ownerUid == uid }?.status
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
                mutableState.value = SnapshotRefreshState(uid,status=prior,error="Обновление прервано; предыдущая копия сохранена.")
            }
            throw e
        } catch (e: Exception) {
            val message = safeError(e)
            AppLog.w("ServerSnapshot",message)
            if (app.authManager.currentUser?.uid == uid) {
                mutableState.value = SnapshotRefreshState(uid,status=prior,error=message)
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
            "TimeoutCancellationException"
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
