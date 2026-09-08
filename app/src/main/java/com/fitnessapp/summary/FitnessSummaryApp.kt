package com.fitnessapp.summary

import android.app.Application
import com.fitnessapp.summary.data.AppDatabase
import com.fitnessapp.summary.debug.AppLog
import com.fitnessapp.summary.data.SummaryRepository
import com.fitnessapp.summary.data.WorkoutRepository
import com.fitnessapp.summary.garmin.GarminApiClient
import com.fitnessapp.summary.garmin.GarminAuthClient
import com.fitnessapp.summary.garmin.GarminSyncManager
import com.fitnessapp.summary.garmin.GarminTokenStore
import com.fitnessapp.summary.garmin.GarminWeightUploader
import com.fitnessapp.summary.scale.ScaleSyncManager
import com.fitnessapp.summary.scale.ZeppApiClient
import com.fitnessapp.summary.scale.ZeppAuthClient
import com.fitnessapp.summary.scale.ZeppTokenStore
import com.fitnessapp.summary.health.HealthConnectManager
import com.fitnessapp.summary.health.HealthConnectScaleReader
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

    // Unofficial Garmin Connect access (Garmin's own scores: sleep score, HRV, readiness,
    // training status, Body Battery, stress... - see garmin/GarminAuthClient.kt for why
    // this exists alongside Health Connect rather than instead of it).
    val garminTokenStore: GarminTokenStore by lazy { GarminTokenStore(this) }
    val garminAuth: GarminAuthClient by lazy { GarminAuthClient(garminTokenStore) }
    private val garminApi: GarminApiClient by lazy { GarminApiClient(garminAuth) }
    val garminSync: GarminSyncManager by lazy { GarminSyncManager(garminApi, database) }

    // Smart-scale weigh-ins (scale/): primarily out of Health Connect, where Zepp Life
    // lands via Google Fit; optionally straight from the Zepp Life cloud. And the one
    // write this app makes to Garmin - pushing those weigh-ins in. See scale/ScaleSyncManager.kt.
    val zeppTokenStore: ZeppTokenStore by lazy { ZeppTokenStore(this) }
    val zeppAuth: ZeppAuthClient by lazy { ZeppAuthClient(zeppTokenStore) }
    private val zeppApi: ZeppApiClient by lazy { ZeppApiClient(zeppAuth) }
    val healthScaleReader: HealthConnectScaleReader by lazy { HealthConnectScaleReader(healthConnect) }
    private val garminWeightUploader: GarminWeightUploader by lazy { GarminWeightUploader(garminAuth) }
    val scaleSync: ScaleSyncManager by lazy {
        ScaleSyncManager(
            context = this,
            healthConnect = healthConnect,
            healthReader = healthScaleReader,
            zeppApi = zeppApi,
            zeppTokens = zeppTokenStore,
            garminAuth = garminAuth,
            garminApi = garminApi,
            uploader = garminWeightUploader,
            database = database
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
        AppLog.init(this)

        // Without a real Firebase project there's no cloud to listen to - skip the
        // auth wiring entirely rather than letting it fail repeatedly in the background.
        if (!FirebaseSetup.isConfigured) {
            AppLog.i("FitnessSummaryApp", "Firebase не настроен (заглушка google-services.json) - облако выключено")
            return
        }

        appScope.launch {
            authManager.authStateFlow().collect { user ->
                if (user != null) {
                    AppLog.i("FitnessSummaryApp", "Вход выполнен (uid=${user.uid.take(6)}...), запускаю Firestore-синк")
                    syncManager.start(user.uid)
                    if (!didInitialPush) {
                        didInitialPush = true
                        // Uploads whatever was synced locally before this sign-in.
                        // Idempotent - every document id is a natural key.
                        syncManager.pushAll(user.uid)
                    }
                } else {
                    AppLog.i("FitnessSummaryApp", "Вышли из аккаунта, останавливаю Firestore-синк")
                    syncManager.stop()
                    didInitialPush = false
                }
            }
        }
    }
}
