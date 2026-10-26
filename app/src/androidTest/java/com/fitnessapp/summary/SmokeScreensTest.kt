package com.fitnessapp.summary

import android.graphics.Bitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fitnessapp.summary.data.DailySummary
import com.fitnessapp.summary.data.GarminDailyExtra
import com.fitnessapp.summary.data.Workout
import com.fitnessapp.summary.debug.AppLog
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Walks every bottom tab on a clean CI emulator and leaves a PNG per screen.
 *
 * Not an assertion suite: what it guards is "the build renders at all" (a crash on any
 * tab fails the run) and it hands Claude screenshots to look at, because the owner's PC
 * cannot build or run the app and the phone is the only other place a regression shows.
 *
 * The emulator has no Health Connect data, no Garmin login and no Firebase session, so
 * every sync in MainActivity is skipped; Room is seeded directly instead, otherwise each
 * screen would only ever show its empty state.
 */
@RunWith(AndroidJUnit4::class)
class SmokeScreensTest {

    // Empty rule: the activity must start only after seeding, or the first frame of
    // "День" would be drawn from an empty database.
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // External files dir of the app under test: adb can pull it without run-as.
    private val shotsDir: File by lazy {
        File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply {
            deleteRecursively(); mkdirs()
        }
    }

    @Test
    fun walkAllTabs() {
        seedDatabase()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            shot("01_day")
            for ((i, label) in listOf("Неделя", "Тренды", "Тренировки", "Я").withIndex()) {
                tab(label).performClick()
                compose.waitForIdle()
                // Trends computes its series off the main thread; give it a beat so the
                // shot shows charts rather than a half-loaded frame.
                Thread.sleep(1500)
                compose.waitForIdle()
                val name = "%02d_%s".format(i + 2, label)
                shot(name)
                if (label == "Тренды") cardShot("Стресс, средний за день", "${name}_stress")
                if (label == "Тренды" || label == "Я") scrollShots(name)
            }
        }
        dumpLogs()
    }

    // Bottom-bar items are Role.Tab; screen bodies may repeat the same word (a "Неделя"
    // header, say), so text alone would be ambiguous.
    private fun tab(label: String) = compose.onNode(
        hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
        useUnmergedTree = false
    )

    // A swipe on onRoot() starts at the very bottom edge, which is the NavigationBar: the
    // gesture lands there and nothing scrolls (every "_N" shot came out byte-identical).
    // So the swipe goes to the tallest scrollable node - the screen body, not a chip row -
    // and stays inside it.
    private fun scrollShots(prefix: String) {
        val scrollables = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes()
        if (scrollables.isEmpty()) return
        val body = scrollables.indices.maxBy { scrollables[it].size.height }
        repeat(4) { n ->
            compose.onAllNodes(hasScrollAction())[body].performTouchInput {
                swipeUp(startY = height * 0.85f, endY = height * 0.15f, durationMillis = 400)
            }
            compose.waitForIdle()
            Thread.sleep(500)
            shot("${prefix}_${n + 1}")
        }
    }

    // Fixed swipes drift as cards are added above; a chart under suspicion is reached by
    // its title instead, then nudged up so the whole card, not just the header, is in frame.
    // Scrolls the list back to the top afterwards so scrollShots still starts from the top.
    private fun cardShot(title: String, name: String) {
        val list = compose.onAllNodes(hasScrollAction())
        val nodes = list.fetchSemanticsNodes()
        if (nodes.isEmpty()) return
        val body = nodes.indices.maxBy { nodes[it].size.height }
        runCatching {
            list[body].performScrollToNode(hasText(title))
            compose.waitForIdle()
            list[body].performTouchInput {
                swipeUp(startY = height * 0.6f, endY = height * 0.35f, durationMillis = 400)
            }
            compose.waitForIdle()
            Thread.sleep(500)
            shot(name)
            list[body].performScrollToIndex(0)
            compose.waitForIdle()
        }
    }

    private fun shot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(shotsDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    // The cloud outbox holds every WARN/ERROR of the run; written beside the shots so a
    // swallowed failure is visible even when every screen looks fine.
    // A failure here must not fail the walk: the screenshots are the point.
    private fun dumpLogs() {
        runCatching {
            File(shotsDir, "outbox.jsonl").writeText(AppLog.readOutbox().joinToString("\n"))
        }
        runCatching {
            AppLog.logFileOrNull()?.copyTo(File(shotsDir, "app.log"), overwrite = true)
        }
    }

    private fun seedDatabase() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<FitnessSummaryApp>()
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val days = (0L until 90L).map { back ->
            val d = today.minusDays(back)
            // Deterministic but uneven, so charts have shape and weekly averages differ.
            val k = (back * 37 % 11).toInt()
            DailySummary(
                dateEpochDay = d.toEpochDay(),
                steps = 6000L + k * 700,
                activeCaloriesKcal = 350 + k * 25,
                totalCaloriesKcal = 2200 + k * 40,
                distanceMeters = 4500 + k * 500,
                restingHeartRate = 54 + k % 5,
                avgHeartRate = 72 + k,
                minHeartRate = 50 + k % 4,
                maxHeartRate = 140 + k * 3,
                sleepTotalMinutes = 390 + k * 9,
                sleepDeepMinutes = 70 + k * 2,
                sleepLightMinutes = 220 + k * 4,
                sleepRemMinutes = 100 + k * 3,
                sleepAwakeMinutes = 15 + k,
                workoutCount = if (back % 2 == 0L) 1 else 0,
                workoutMinutes = if (back % 2 == 0L) 45 + k else 0
            )
        }
        val types = listOf(
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
        )
        val workouts = (0L until 90L step 2).map { back ->
            val d = today.minusDays(back)
            val start = d.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
            val minutes = 45 + (back % 7).toInt() * 5
            val type = types[(back / 2 % types.size).toInt()]
            Workout(
                recordId = "smoke-$back",
                dateEpochDay = d.toEpochDay(),
                startTimeMillis = start,
                endTimeMillis = start + minutes * 60_000L,
                exerciseType = type,
                durationMinutes = minutes,
                distanceMeters = if (type == ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING) 0 else 8000,
                activeCaloriesKcal = 400,
                avgHeartRate = 135,
                maxHeartRate = 168
            )
        }
        app.database.dailySummaryDao().upsertAll(days)
        app.database.workoutDao().upsertAll(workouts)
        // Garmin's daily summary is what lifts "Тренды" out of its empty state; values sit
        // in Garmin's real ranges, with a few high-stress days so the axis has to stretch.
        val extraDao = app.database.garminDailyExtraDao()
        for (back in 0L until 90L) {
            val k = (back * 37 % 11).toInt()
            extraDao.upsert(
                GarminDailyExtra(
                    dateEpochDay = today.minusDays(back).toEpochDay(),
                    averageStressLevel = if (back % 13 == 5L) 68 else 22 + k * 2,
                    maxStressLevel = 85 + k,
                    bodyBatteryAtWake = 45 + k * 4,
                    bodyBatteryHighest = 60 + k * 3,
                    bodyBatteryLowest = 10 + k,
                    stressQualifier = if (k > 6) "stressful" else "balanced",
                    totalSteps = 6000L + k * 700,
                    restingHeartRate = 54 + k % 5,
                    moderateIntensityMinutes = 20 + k,
                    vigorousIntensityMinutes = k * 2
                )
            )
        }
    }
}
