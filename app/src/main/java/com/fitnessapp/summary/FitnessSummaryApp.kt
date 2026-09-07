package com.fitnessapp.summary

import android.app.Application
import com.fitnessapp.summary.data.AppDatabase
import com.fitnessapp.summary.data.SummaryRepository
import com.fitnessapp.summary.data.WorkoutRepository
import com.fitnessapp.summary.health.HealthConnectManager
import com.fitnessapp.summary.health.HealthConnectReader
import com.fitnessapp.summary.health.HealthSyncManager
import com.fitnessapp.summary.sync.AuthManager
import com.fitnessapp.summary.sync.FirebaseSetup
import com.fitnessapp.summary.sync.FirestoreSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The app's whole object graph, wired by hand.
 *
 * No DI framework and no ViewModels - screens read Flows straight off these
 * repositories with `collectAsState`. Same deliberate simplification as the sibling
 * habits app: at one-developer scale the indirection costs more than it saves.
 *
 * Data flows one way: Health Connect -> Room -> UI, with Firestore mirroring Room
 * sideways to other devices. Nothing in the UI ever talks to Health Connect or
 * Firestore directly.
 */
class FitnessSummaryApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    val authManager: AuthManager by lazy { AuthManager(BuildConfig.GOOGLE_WEB_CLIENT_ID) }

    val syncManager: FirestoreSyncManager by lazy { FirestoreSyncManager(database) }

    val healthConnect: HealthConnectManager by lazy { HealthConnectManager(this) }

    private val healthReader: HealthConnectReader by lazy { HealthConnectReader(healthConnect) }

    val summaryRepository: SummaryRepository by lazy {
        SummaryRepository(
            database.dailySummaryDao(),
            database.workoutDao(),
            syncManager = syncManager,
            currentUid = { authManager.currentUser?.uid }
        )
    }

    val workoutRepository: WorkoutRepository by lazy {
        WorkoutRepository(
            database.workoutDao(),
            syncManager = syncManager,
            currentUid = { authManager.currentUser?.uid }
        )
    }

    val healthSync: HealthSyncManager by lazy {
        HealthSyncManager(
            context = this,
            healthConnect = healthConnect,
            reader = healthReader,
            summaryRepository = summaryRepository,
            workoutRepository = workoutRepository
        )
    }

    private val appScope = CoroutineScope(Dispatchers.IO)

    /**
     * Launches work that MUST finish on an application-scoped coroutine rather than a
     * screen's `rememberCoroutineScope()`. A screen scope is cancelled the moment the
     * user navigates away, which would abort a sync halfway through a date range and
     * leave a partially written week behind.
     */
    fun launchPersistent(block: suspend () -> Unit) {
        appScope.launch { block() }
    }

    /** True once we've done the one-time "push everything local" for the current sign-in. */
    private var didInitialPush = false

    override fun onCreate() {
        super.onCreate()

        // Without a real Firebase project there's no cloud to listen to - skip the
        // auth wiring entirely rather than letting it fail repeatedly in the background.
        if (!FirebaseSetup.isConfigured) return

        appScope.launch {
            authManager.authStateFlow().collect { user ->
                if (user != null) {
                    syncManager.start(user.uid)
                    if (!didInitialPush) {
                        didInitialPush = true
                        // Uploads whatever was synced locally before this sign-in.
                        // Idempotent - every document id is a natural key.
                        syncManager.pushAll(user.uid)
                    }
                } else {
                    syncManager.stop()
                    didInitialPush = false
                }
            }
        }
    }
}
