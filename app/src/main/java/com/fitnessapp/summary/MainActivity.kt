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
            // After Garmin, so a weigh-in pushed to Garmin here is re-read in the same run.
            // Runs whenever there is any scale source: weight readable from Health Connect
            // (Zepp Life -> Google Fit -> Health Connect) or a Zepp Life login.
            if (app.scaleSync.hasAnySource()) {
                app.scaleSync.sync()
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
