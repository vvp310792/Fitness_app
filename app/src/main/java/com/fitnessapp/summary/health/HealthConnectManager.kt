package com.fitnessapp.summary.health

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord

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
     * Every permission the app asks for. Must stay in sync with the
     * `android.permission.health.*` entries in AndroidManifest.xml - a permission
     * requested here but not declared there is simply never granted, with no error.
     */
    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    )

    /** Which of [permissions] the user has actually granted. Empty when unavailable. */
    suspend fun grantedPermissions(): Set<String> {
        val client = clientOrNull() ?: return emptySet()
        return client.permissionController.getGrantedPermissions().intersect(permissions)
    }

    suspend fun hasAllPermissions(): Boolean = grantedPermissions().containsAll(permissions)

    /**
     * Contract for the system permission sheet. Health Connect deliberately does NOT
     * use the normal runtime-permission flow, so `ActivityResultContracts.RequestMultiplePermissions`
     * would silently do nothing here - this is the only way to ask.
     */
    fun requestPermissionsContract() = PermissionController.createRequestPermissionResultContract()

    /**
     * Opens Health Connect's own settings screen, where the user can grant
     * permissions they previously denied. Needed because after two denials the
     * permission sheet stops appearing and the settings screen is the only route left.
     */
    fun settingsIntent(): Intent = Intent(ACTION_HEALTH_CONNECT_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
        const val ACTION_HEALTH_CONNECT_SETTINGS = "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"
    }
}
