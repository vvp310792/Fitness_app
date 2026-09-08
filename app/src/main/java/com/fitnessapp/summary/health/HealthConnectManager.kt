package com.fitnessapp.summary.health

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord

/**
 * Owns the connection to Health Connect: whether it's usable at all, whether the
 * user has granted us the reads we need, and how to ask for them.
 *
 * This app is a pure *reader*. It never requests a write permission and never
 * writes a record - Garmin Connect (or whatever else the user has installed) fills
 * Health Connect, and everything here only summarises what's already there.
 *
 * Actually pulling the data lives in [HealthConnectReader], kept separate so that
 * availability/permission handling (which the UI needs constantly) isn't tangled up
 * with the I/O-heavy read path.
 */
class HealthConnectManager(private val context: Context) {

    /**
     * Why the app might not be able to read anything. Worth distinguishing rather
     * than collapsing into one "no data" state, because each case has a different
     * fix and the settings screen tells the user which one applies to them.
     */
    enum class Availability {
        /** Health Connect is present and usable. */
        AVAILABLE,

        /** Installed but too old - the user needs to update it from the Play Store. */
        UPDATE_REQUIRED,

        /**
         * Not present at all. On Android 14+ this basically shouldn't happen (it's
         * part of the system); on 13 and below it means the standalone Health
         * Connect app isn't installed yet.
         */
        NOT_INSTALLED
    }

    fun availability(): Availability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> Availability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.UPDATE_REQUIRED
        else -> Availability.NOT_INSTALLED
    }

    val isAvailable: Boolean get() = availability() == Availability.AVAILABLE

    /**
     * Null whenever Health Connect isn't usable, so callers are forced to handle
     * the unavailable case instead of getting a throwing client.
     */
    fun clientOrNull(): HealthConnectClient? =
        if (isAvailable) HealthConnectClient.getOrCreate(context) else null

    /**
     * Daily activity and recovery: what Garmin Connect writes into Health Connect.
     */
    val activityPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    )

    /**
     * Weight and body composition: what a smart scale's app (Zepp Life via Google Fit,
     * or anything else) writes into Health Connect. Read by [HealthConnectScaleReader]
     * and pushed on to Garmin by scale/ScaleSyncManager. Kept as its own set so the
     * scale section of the settings screen can ask for exactly these and tell the user
     * whether weight specifically is readable, independent of the activity grants.
     *
     * Deliberately no LeanBodyMassRecord: lean mass (everything that isn't fat) is not
     * the "muscle mass" the scale card shows, and storing it under that label would be
     * a lie by ~20 kg.
     */
    val scalePermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(BodyWaterMassRecord::class),
        HealthPermission.getReadPermission(BoneMassRecord::class),
        HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
        HealthPermission.getReadPermission(HeightRecord::class)
    )

    /** The one permission without which the scale path cannot do anything at all. */
    val weightPermission: String = HealthPermission.getReadPermission(WeightRecord::class)

    /**
     * Every permission the app asks for. Must stay in sync with the
     * `android.permission.health.*` entries in AndroidManifest.xml - a permission
     * requested here but not declared there is simply never granted, with no error.
     */
    val permissions: Set<String> = activityPermissions + scalePermissions

    /** Which of [permissions] the user has actually granted. Empty when unavailable. */
    suspend fun grantedPermissions(): Set<String> {
        val client = clientOrNull() ?: return emptySet()
        return client.permissionController.getGrantedPermissions().intersect(permissions)
    }

    suspend fun hasAllPermissions(): Boolean = grantedPermissions().containsAll(permissions)

    /** True when at least weight can be read - the minimum for the scale pipeline to run. */
    suspend fun canReadWeight(): Boolean = weightPermission in grantedPermissions()

    /**
     * Contract for the system permission sheet. Health Connect deliberately does NOT
     * use the normal runtime-permission flow, so `ActivityResultContracts.RequestMultiplePermissions`
     * would silently do nothing here - this is the only way to ask.
     */
    fun requestPermissionsContract() = PermissionController.createRequestPermissionResultContract()

    /**
     * Opens Health Connect's permission screen for this app, where the user can grant
     * permissions they previously denied. Needed because after two denials the
     * permission sheet stops appearing and this is the only route left.
     *
     * Tries an explicit package launch FIRST, deliberately ahead of the "correct"
     * platform action. Confirmed against a real Xiaomi HyperOS device: some OEM ROMs
     * silently reroute an implicit intent with no registered handler to a browser web
     * search instead of throwing `ActivityNotFoundException` - which
     * `android.health.connect.action.MANAGE_HEALTH_PERMISSIONS` (Android 14+) and
     * `androidx.health.ACTION_HEALTH_CONNECT_SETTINGS` (below it) are both implicit
     * actions vulnerable to, on those builds, even though they're the platform-correct
     * ones and work fine on stock/Samsung devices. `getLaunchIntentForPackage` is
     * explicit - it names the installed package directly - so there is no "no match"
     * step for that OEM behaviour to hijack: either Health Connect's own launcher
     * activity exists and this opens it, or it returns null and the action-based
     * intent is used as before. The cost when it succeeds is one extra tap (landing on
     * Health Connect's own home screen rather than straight at this app's permission
     * row); that beats ending up in a browser.
     */
    fun settingsIntent(context: Context): Intent {
        val explicit = context.packageManager.getLaunchIntentForPackage(HEALTH_CONNECT_PACKAGE)
        val intent = explicit ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(ACTION_MANAGE_HEALTH_PERMISSIONS)
                .putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
        } else {
            Intent(ACTION_HEALTH_CONNECT_SETTINGS)
        }
        return intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    /** Play Store page for the standalone Health Connect app (Android 13 and below). */
    fun installIntent(): Intent = Intent(Intent.ACTION_VIEW).apply {
        data = android.net.Uri.parse(
            "market://details?id=$HEALTH_CONNECT_PACKAGE&url=healthconnect%3A%2F%2Fonboarding"
        )
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        setPackage("com.android.vending")
    }

    companion object {
        const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"

        /** Android 13 and below: broadcast-handled by the standalone Health Connect app. */
        const val ACTION_HEALTH_CONNECT_SETTINGS = "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"

        /**
         * Android 14+: android.health.connect.HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS,
         * a platform (not Jetpack) constant. Hardcoded as a string rather than referencing
         * that class directly so this file doesn't need @RequiresApi(34) plumbing for what
         * is, at the call site, already a Build.VERSION.SDK_INT-guarded branch - verified
         * against the actual compileSdk 36 platform android.jar, not guessed.
         */
        const val ACTION_MANAGE_HEALTH_PERMISSIONS = "android.health.connect.action.MANAGE_HEALTH_PERMISSIONS"
    }
}
