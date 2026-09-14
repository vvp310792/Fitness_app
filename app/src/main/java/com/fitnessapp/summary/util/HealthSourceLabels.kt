package com.fitnessapp.summary.util

/**
 * Human names for the apps that write into Health Connect.
 *
 * Only needed where a day did **not** come from Garmin: `DailySummary.sourceApps` holds
 * raw package names, and "com.google.android.apps.fitness" on a day card is a diagnostic,
 * not a sentence. An unknown package keeps its last segment rather than being hidden -
 * the point of showing the source at all is that an unrecognised one is exactly the case
 * worth seeing.
 */
object HealthSourceLabels {

    private val NAMES: Map<String, String> = mapOf(
        "com.garmin.android.apps.connectmobile" to "Garmin Connect",
        "com.google.android.apps.fitness" to "Google Fit",
        "com.google.android.apps.healthdata" to "Health Connect",
        "com.google.android.apps.health" to "Google Health",
        "com.fitbit.FitbitMobile" to "Fitbit",
        "com.samsung.android.app.shealth" to "Samsung Health",
        "com.xiaomi.hm.health" to "Zepp Life",
        "com.huami.midong" to "Zepp",
        "com.strava" to "Strava",
        "com.google.android.gms" to "Google Play Services"
    )

    fun label(packageName: String): String {
        val clean = packageName.trim()
        if (clean.isEmpty()) return ""
        return NAMES[clean] ?: clean.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    /** Comma-separated packages, as stored, turned into a readable list. */
    fun labels(sourceApps: String): String =
        sourceApps.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { label(it) }
            .distinct()
            .joinToString(", ")
}
