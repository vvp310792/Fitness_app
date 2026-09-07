package com.fitnessapp.summary

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.fitnessapp.summary.ui.navigation.AppNavigation
import com.fitnessapp.summary.ui.theme.FitnessSummaryTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as FitnessSummaryApp

        // Refresh on every launch. Health Connect offers no change notifications, so
        // opening the app is the trigger - and the window is wide enough to pick up
        // days the watch backfilled since last time (see HealthSyncManager).
        app.launchPersistent {
            if (app.healthConnect.isAvailable && app.healthConnect.grantedPermissions().isNotEmpty()) {
                app.healthSync.syncRecent()
            }
        }

        // Same trigger, same window, for the unofficial Garmin source - only if the
        // user has actually logged in (most installs never will, and that's fine).
        app.launchPersistent {
            if (app.garminAuth.isLoggedIn) {
                app.garminSync.syncRecent()
            }
        }

        setContent {
            FitnessSummaryTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation(app = app)
                }
            }
        }
    }
}
