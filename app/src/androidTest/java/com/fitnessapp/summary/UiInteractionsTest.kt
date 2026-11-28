package com.fitnessapp.summary

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.fitnessapp.summary.analytics.TrendPoint
import java.io.File
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fitnessapp.summary.ui.components.*
import com.fitnessapp.summary.ui.theme.FitnessSummaryTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class UiInteractionsTest {
    @get:Rule val compose=createComposeRule()

    @Test fun denseTrendMedianAndMeanStayAvailable() {
        val points=(0L..4380L).filter{it%4L!=0L && it !in 1000L..1450L}
            .map{TrendPoint(16000L+it,5f+(it%20L).toFloat()+(if(it%137L==0L)75f else 0f))}
        var mode by mutableStateOf(TrendSmoothing.MEDIAN)
        compose.setContent {
            FitnessSummaryTheme(darkTheme=true) {
                androidx.compose.material3.Surface(color=MaterialTheme.colorScheme.surface) {
                    CompositionLocalProvider(LocalTrendSmoothing provides mode) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            TextButton(onClick={mode=TrendSmoothing.MEAN}){Text("Среднее")}
                            TrendChart(points,16000L,20380L,Color(0xFF2DD4BF),{v->v.toInt().toString()},
                                smoothWindowDays=180,floorAtZero=true)
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("скользящая медиана",substring=true).assertIsDisplayed()
        // Use a distinct directory, so the existing smoke test cannot erase it.
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(context.getExternalFilesDir(null),"trend-regression").apply{mkdirs()}
        fun shot(name:String) {
            val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
            File(dir,name).outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        }
        shot("median_12y_dark.png")
        compose.onNodeWithText("Среднее",substring=false).performClick()
        compose.onNodeWithText("скользящее среднее",substring=true).assertIsDisplayed()
        shot("mean_12y_dark.png")
    }

    @Test fun weeklyCyclingVolumeShowsMeanEvenInMedianMode() {
        val weeks=(0 until 599).map { i ->
            TrendPoint(16000L+7L*i,if(i%4==0 || i in 200..202) 50f+(i%7)*10f else 0f)
        }
        compose.setContent {
            FitnessSummaryTheme(darkTheme=true) {
                androidx.compose.material3.Surface(color=MaterialTheme.colorScheme.surface) {
                    CompositionLocalProvider(LocalTrendSmoothing provides TrendSmoothing.MEDIAN) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("Велосипед")
                            TrendChart(weeks,16000L,20186L,Color(0xFF34D399),{v->v.toInt().toString()+" км"},
                                smoothWindowDays=180,floorAtZero=true,maxGapDays=7,weeklyVolume=true)
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Столбцы — километры за неделю.",substring=true).assertIsDisplayed()
        compose.onNodeWithText("скользящее среднее",substring=true).assertIsDisplayed()
        compose.onNodeWithText("скользящая медиана",substring=true).assertDoesNotExist()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(context.getExternalFilesDir(null),"trend-regression").apply{mkdirs()}
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir,"weekly_cycling_12y_dark.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }

    @Test fun destructiveActionNeedsExplicitConfirmation() {
        val actions=AtomicInteger()
        compose.setContent {
            FitnessSummaryTheme {
                ConfirmAction("Удалить импорт","Удалить историю?","Проверка подтверждения") { actions.incrementAndGet() }
            }
        }
        compose.onNodeWithText("Удалить импорт").performClick()
        compose.onNodeWithText("Удалить историю?").assertIsDisplayed()
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { assertEquals(0,actions.get()) }
        compose.onNodeWithText("Удалить импорт").performClick()
        compose.onNodeWithText("Удалить",substring=false).performClick()
        compose.runOnIdle { assertEquals(1,actions.get()) }
    }

    @Test fun settingsDisclosureKeepsDetailsReachable() {
        compose.setContent {
            FitnessSummaryTheme {
                ExpandableSection("Импорт истории","Источники") { Text("Импорт Strava") }
            }
        }
        compose.onNodeWithText("Импорт Strava").assertDoesNotExist()
        compose.onNodeWithText("Импорт истории").performClick()
        compose.onNodeWithText("Импорт Strava").assertIsDisplayed()
        compose.onNodeWithText("Импорт истории").performClick()
        compose.onNodeWithText("Импорт Strava").assertDoesNotExist()
    }

    @Test fun emptyListIsOnlyReportedAfterFirstEmission() {
        val gate=CompletableDeferred<Unit>()
        val source=flow { gate.await();emit(emptyList<String>()) }
        compose.setContent {
            FitnessSummaryTheme {
                val data=readScreen(source)
                Column {
                    ReadStatus(listOf(data),{})
                    if(data.loaded && data.value.orEmpty().isEmpty())Text("Нет измерений")
                }
            }
        }
        compose.onNodeWithText("Нет измерений").assertDoesNotExist()
        compose.onNodeWithText("Загружаем измерения…",substring=true).assertIsDisplayed()
        gate.complete(Unit)
        compose.waitUntil(5000) { compose.onAllNodesWithText("Нет измерений").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Нет измерений").assertIsDisplayed()
    }
}
