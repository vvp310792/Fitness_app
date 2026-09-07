package com.fitnessapp.summary.ui.day

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitnessapp.summary.FitnessSummaryApp
import com.fitnessapp.summary.data.DailySummary
import com.fitnessapp.summary.ui.components.EmptyState
import com.fitnessapp.summary.ui.components.InfoCard
import com.fitnessapp.summary.ui.components.MetricCard
import com.fitnessapp.summary.ui.components.SectionHeader
import com.fitnessapp.summary.ui.components.StageBars
import com.fitnessapp.summary.ui.components.StageDatum
import com.fitnessapp.summary.ui.components.StatRow
import com.fitnessapp.summary.ui.components.WorkoutRow
import com.fitnessapp.summary.ui.theme.metricPalette
import com.fitnessapp.summary.util.declineWorkouts
import com.fitnessapp.summary.util.formatCount
import com.fitnessapp.summary.util.formatDayHeader
import com.fitnessapp.summary.util.formatDistance
import com.fitnessapp.summary.util.formatDuration
import com.fitnessapp.summary.util.formatHeartRate
import com.fitnessapp.summary.util.formatSleepDuration
import java.time.LocalDate

/**
 * One day at a time, with arrows to step through history.
 *
 * Forward navigation stops at today: there is no such thing as tomorrow's activity,
 * and letting the user page into empty future days makes the app feel broken.
 */
@Composable
fun DayScreen(app: FitnessSummaryApp) {
    val today = remember { LocalDate.now() }
    var selectedDate by remember { mutableStateOf(today) }

    // Keyed on the date so switching days resubscribes to the right row instead of
    // holding the Flow built for the day the screen first opened with.
    val summary by remember(selectedDate) {
        app.summaryRepository.observeDay(selectedDate)
    }.collectAsState(initial = null)

    val workouts by remember(selectedDate) {
        app.workoutRepository.observeForDay(selectedDate)
    }.collectAsState(initial = emptyList())

    val palette = metricPalette()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedDate = selectedDate.minusDays(1) }) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "Предыдущий день")
                }
                Text(
                    text = formatDayHeader(selectedDate, today),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(
                    onClick = { selectedDate = selectedDate.plusDays(1) },
                    enabled = selectedDate.isBefore(today)
                ) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "Следующий день")
                }
            }
        }

        val day = summary
        if (day == null || day.isEmpty) {
            item {
                EmptyState(
                    emoji = "⌚",
                    title = "Нет данных за этот день",
                    message = if (app.healthSync.hasEverSynced) {
                        "Часы за этот день ничего не записали, либо синхронизация Garmin Connect ещё не дошла до Health Connect."
                    } else {
                        "Откройте вкладку «Я» и разрешите чтение Health Connect, чтобы начать."
                    }
                )
            }
        } else {
            item { DayMetrics(day, palette) }
            item { SleepCard(day, palette) }
            item { HeartCard(day) }
        }

        if (workouts.isNotEmpty()) {
            item {
                SectionHeader("${workouts.size} ${declineWorkouts(workouts.size)}")
            }
            items(workouts, key = { it.recordId }) { workout ->
                WorkoutRow(workout = workout)
            }
        }
    }
}

@Composable
private fun DayMetrics(day: DailySummary, palette: com.fitnessapp.summary.ui.theme.MetricPalette) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                emoji = "👟",
                label = "Шаги",
                value = if (day.steps > 0) formatCount(day.steps) else "-",
                accent = palette.steps,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                emoji = "📍",
                label = "Дистанция",
                value = formatDistance(day.distanceMeters),
                accent = palette.distance,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                emoji = "🔥",
                label = "Активные калории",
                value = if (day.activeCaloriesKcal > 0) formatCount(day.activeCaloriesKcal) else "-",
                accent = palette.calories,
                modifier = Modifier.weight(1f),
                caption = if (day.totalCaloriesKcal > 0) {
                    "всего ${formatCount(day.totalCaloriesKcal)} ккал"
                } else {
                    null
                }
            )
            MetricCard(
                emoji = "❤️",
                label = "Пульс покоя",
                value = formatHeartRate(day.restingHeartRate),
                accent = palette.heart,
                modifier = Modifier.weight(1f),
                caption = if (day.restingHeartRate > 0) "уд/мин" else null
            )
        }
    }
}

@Composable
private fun SleepCard(day: DailySummary, palette: com.fitnessapp.summary.ui.theme.MetricPalette) {
    InfoCard(title = "Сон") {
        if (!day.hasSleep) {
            Text(
                text = "Нет записи сна за эту ночь.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@InfoCard
        }

        Text(
            text = formatSleepDuration(day.sleepTotalMinutes),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Only render the stages a source actually provided. Garmin fills all of
        // them, but a session written without a breakdown would otherwise show four
        // convincing-looking zero rows.
        val stages = buildList {
            if (day.sleepDeepMinutes > 0) add(StageDatum("Глубокий", day.sleepDeepMinutes, palette.sleepDeep))
            if (day.sleepRemMinutes > 0) add(StageDatum("Быстрый", day.sleepRemMinutes, palette.sleepRem))
            if (day.sleepLightMinutes > 0) add(StageDatum("Лёгкий", day.sleepLightMinutes, palette.sleepLight))
            if (day.sleepAwakeMinutes > 0) add(StageDatum("Бодрствование", day.sleepAwakeMinutes, palette.sleepAwake))
        }
        if (stages.isNotEmpty()) {
            StageBars(
                stages = stages,
                formatValue = { formatDuration(it) },
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun HeartCard(day: DailySummary) {
    if (day.avgHeartRate <= 0 && day.maxHeartRate <= 0) return

    InfoCard(title = "Пульс за день") {
        StatRow("Средний", "${formatHeartRate(day.avgHeartRate)} уд/мин")
        if (day.minHeartRate > 0) {
            StatRow("Минимальный", "${formatHeartRate(day.minHeartRate)} уд/мин")
        }
        StatRow("Максимальный", "${formatHeartRate(day.maxHeartRate)} уд/мин")
    }
}
