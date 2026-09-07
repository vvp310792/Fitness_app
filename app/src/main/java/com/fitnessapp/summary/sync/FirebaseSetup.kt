package com.fitnessapp.summary.sync

import com.google.firebase.FirebaseApp

/**
 * Tells whether `app/google-services.json` is still the committed placeholder.
 *
 * The repo ships a structurally valid but fake google-services.json on purpose: the
 * Google Services Gradle plugin fails the whole build without one, so a placeholder
 * is what lets the project compile and the APK run before anyone has created a
 * Firebase project. The cost is that Auth and Firestore would then fail at runtime
 * with opaque errors, so the settings screen checks this and says plainly that cloud
 * sync isn't set up yet - rather than showing a sign-in button that can only fail.
 *
 * Nothing else in the app is gated on it: Room works entirely offline, so an
 * unconfigured Firebase costs the user only cross-device sync.
 *
 * See docs/SETUP_FIREBASE.md for replacing it.
 */
object FirebaseSetup {

    private const val PLACEHOLDER_PROJECT_ID = "fitness-summary-placeholder"

    val isConfigured: Boolean
        get() = try {
            FirebaseApp.getInstance().options.projectId != PLACEHOLDER_PROJECT_ID
        } catch (e: IllegalStateException) {
            // No FirebaseApp initialised at all - same practical outcome.
            false
        }
}
