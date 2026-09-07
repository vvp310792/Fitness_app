package com.fitnessapp.summary.ui.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitnessapp.summary.FitnessSummaryApp
import com.fitnessapp.summary.ui.components.EmptyState
import com.fitnessapp.summary.ui.components.MetricCard
import com.fitnessapp.summary.ui.components.SectionHeader
import com.fitnessapp.summary.ui.components.WorkoutRow
import com.fitnessapp.summary.ui.theme.metricPalette
import com.fitnessapp.summary.util.exerciseName
import com.fitnessapp.summary.util.formatDayHeader
import com.fitnessapp.summary.util.formatDistance
import com.fitnessapp.summary.util.formatDuration
import java.time.LocalDate

/**
 * Every stored session, newest first, grouped under a date heading.
 *
 * The three tiles on top are lifetime-of-the-database totals rather than a rolling
 * window: this screen is the archive, and the day/week screens already answer
 * "recently".
 */
@Composable
fun WorkoutsScreen(app: FitnessSummaryApp) {
    val today = remember { LocalDate.now() }
    val workouts by remember { app.workoutRepository.observeRecent(200) }
        .collectAsState(initial = emptyList())

    val palette = metricPalette()

    if (workouts.isEmpty()) {
        EmptyState(
            emoji = "🏃",
            title = "Тренировок пока нет",
            message = "Здесь появятся занятия, которые Garmin Connect передал в Health Connect.",
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    val totalMinutes = workouts.sumOf { it.durationMinutes }
    val totalDistance = workouts.sumOf { it.distanceMeters }
    val favourite = workouts
        .groupingBy { it.exerciseType }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard(
                    emoji = "🏋",
                    label = "Всего",
                    value = workouts.size.toString(),
                    accent = palette.workout,
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    emoji = "⏱",
                    label = "Время",
                    value = formatDuration(totalMinutes),
                    accent = palette.calories,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard(
                    emoji = "📍",
                    label = "Дистанция",
                    value = formatDistance(totalDistance),
                    accent = palette.distance,
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    emoji = "⭐",
                    label = "Чаще всего",
                    value = favourite?.let { exerciseName(it) } ?: "-",
                    accent = palette.steps,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Grouped by day so a two-a-day shows up as two rows under one heading,
        // instead of repeating the date on every card.
        val byDate = workouts.groupBy { it.dateEpochDay }
        byDate.forEach { (epochDay, dayWorkouts) ->
            item(key = "header_$epochDay") {
                SectionHeader(formatDayHeader(LocalDate.ofEpochDay(epochDay), today))
            }
            items(dayWorkouts, key = { it.recordId }) { workout ->
                WorkoutRow(workout = workout)
            }
        }

        item {
            Text(
                text = "Показаны последние ${workouts.size} тренировок.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
