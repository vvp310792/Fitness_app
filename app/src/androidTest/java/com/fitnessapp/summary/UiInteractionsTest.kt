package com.fitnessapp.summary

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
