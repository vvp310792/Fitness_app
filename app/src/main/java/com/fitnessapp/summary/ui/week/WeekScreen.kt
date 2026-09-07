package com.fitnessapp.summary.ui.week

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import com.fitnessapp.summary.data.SummaryRepository
import com.fitnessapp.summary.data.WeekSummary
import com.fitnessapp.summary.ui.components.BarDatum
import com.fitnessapp.summary.ui.components.Chip
import com.fitnessapp.summary.ui.components.EmptyState
import com.fitnessapp.summary.ui.components.InfoCard
import com.fitnessapp.summary.ui.components.MetricCard
import com.fitnessapp.summary.ui.components.SectionHeader
import com.fitnessapp.summary.ui.components.StatRow
import com.fitnessapp.summary.ui.components.WeekBarChart
import com.fitnessapp.summary.ui.components.WorkoutRow
import com.fitnessapp.summary.ui.theme.MetricPalette
import com.fitnessapp.summary.ui.theme.metricPalette
import com.fitnessapp.summary.util.WEEKDAY_LABELS
import com.fitnessapp.summary.util.declineDays
import com.fitnessapp.summary.util.declineWorkouts
import com.fitnessapp.summary.util.formatCount
import com.fitnessapp.summary.util.formatDayMonth
import com.fitnessapp.summary.util.formatDistance
import com.fitnessapp.summary.util.formatDuration
import com.fitnessapp.summary.util.formatHeartRate
import com.fitnessapp.summary.util.formatSignedPercent
import com.fitnessapp.summary.util.formatSleepDuration
import com.fitnessapp.summary.util.formatWeekHeader
import com.fitnessapp.summary.util.formatWeekRange
import com.fitnessapp.summary.util.percentChange
import com.fitnessapp.summary.util.weekStart
import java.time.LocalDate

@Composable
fun WeekScreen(app: FitnessSummaryApp) {
    val today = remember { LocalDate.now() }
    val thisWeekStart = remember { weekStart(today) }
    var selectedWeekStart by remember { mutableStateOf(thisWeekStart) }

    // Two weeks are loaded in one query - the selected one and the one before it -
    // because the week-over-week comparison needs both, and a second subscription
    // would just re-read overlapping rows.
    val days by remember(selectedWeekStart) {
        app.summaryRepository.observeRange(
            selectedWeekStart.minusWeeks(1),
            selectedWeekStart.plusDays(6)
        )
    }.collectAsState(initial = emptyList())

    val workouts by remember(selectedWeekStart) {
        app.workoutRepository.observeRange(selectedWeekStart, selectedWeekStart.plusDays(6))
    }.collectAsState(initial = emptyList())

    val previousWorkouts by remember(selectedWeekStart) {
        app.workoutRepository.observeRange(
            selectedWeekStart.minusWeeks(1),
            selectedWeekStart.minusDays(1)
        )
    }.collectAsState(initial = emptyList())

    val week = remember(days, workouts, selectedWeekStart) {
        SummaryRepository.computeWeekSummary(selectedWeekStart, days, workouts)
    }
    val previousWeek = remember(days, previousWorkouts, selectedWeekStart) {
        SummaryRepository.computeWeekSummary(
            selectedWeekStart.minusWeeks(1),
            days,
            previousWorkouts
        )
    }

    val palette = metricPalette()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedWeekStart = selectedWeekStart.minusWeeks(1) }) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "Предыдущая неделя")
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatWeekHeader(selectedWeekStart, today),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = formatWeekRange(selectedWeekStart, selectedWeekStart.plusDays(6)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { selectedWeekStart = selectedWeekStart.plusWeeks(1) },
                    enabled = selectedWeekStart.isBefore(thisWeekStart)
                ) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "Следующая неделя")
                }
            }
        }

        if (week.isEmpty) {
            item {
                EmptyState(
                    emoji = "📊",
                    title = "За эту неделю данных нет",
                    message = "Синхронизируйте Health Connect на вкладке «Я» или выберите другую неделю."
                )
            }
            return@LazyColumn
        }

        item { WeekTotals(week, previousWeek, palette) }
        item { StepsChart(week, days, selectedWeekStart, today, palette) }
        item { SleepSection(week, days, selectedWeekStart, today, palette) }
        item { HeartSection(week) }

        if (workouts.isNotEmpty()) {
            item {
                SectionHeader("${workouts.size} ${declineWorkouts(workouts.size)} за неделю")
            }
            items(workouts, key = { it.recordId }) { workout ->
                WorkoutRow(
                    workout = workout,
                    showDate = formatDayMonth(LocalDate.ofEpochDay(workout.dateEpochDay))
                )
            }
        }
    }
}

@Composable
private fun WeekTotals(week: WeekSummary, previous: WeekSummary, palette: MetricPalette) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                emoji = "👟",
                label = "Шагов за неделю",
                value = formatCount(week.totalSteps),
                accent = palette.steps,
                modifier = Modifier.weight(1f),
                caption = "в среднем ${formatCount(week.avgSteps)} в день"
            )
            MetricCard(
                emoji = "📍",
                label = "Дистанция",
                value = formatDistance(week.totalDistanceMeters),
                accent = palette.distance,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                emoji = "🔥",
                label = "Активные калории",
                value = if (week.totalActiveCaloriesKcal > 0) formatCount(week.totalActiveCaloriesKcal) else "-",
                accent = palette.calories,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                emoji = "🏋",
                label = "Тренировки",
                value = week.workoutCount.toString(),
                accent = palette.workout,
                modifier = Modifier.weight(1f),
                caption = if (week.workoutMinutes > 0) formatDuration(week.workoutMinutes) else null
            )
        }

        // Comparison chips are rendered only for metrics that have a real baseline -
        // percentChange returns null against a zero previous week, and "+∞%" after a
        // week off is worse than saying nothing.
        val comparisons = buildList {
            percentChange(week.totalSteps, previous.totalSteps)?.let { add("Шаги" to it) }
            percentChange(week.totalDistanceMeters, previous.totalDistanceMeters)?.let { add("Дистанция" to it) }
            percentChange(week.workoutMinutes, previous.workoutMinutes)?.let { add("Тренировки" to it) }
            percentChange(week.avgSleepMinutes, previous.avgSleepMinutes)?.let { add("Сон" to it) }
        }
        if (comparisons.isNotEmpty()) {
            InfoCard(title = "К прошлой неделе") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    comparisons.forEach { (label, change) ->
                        Chip(
                            text = "$label ${formatSignedPercent(change)}",
                            // Growth is not automatically good - more sleep and more
                            // steps both are, but this app has no opinion about pace,
                            // so the chip carries the direction and no verdict colour.
                            color = if (change >= 0) palette.distance else palette.calories
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepsChart(
    week: WeekSummary,
    days: List<com.fitnessapp.summary.data.DailySummary>,
    weekStart: LocalDate,
    today: LocalDate,
    palette: MetricPalette
) {
    InfoCard(title = "Шаги по дням") {
        val byDay = days.associateBy { it.dateEpochDay }
        val bars = (0..6).map { offset ->
            val date = weekStart.plusDays(offset.toLong())
            BarDatum(
                label = WEEKDAY_LABELS[offset],
                value = byDay[date.toEpochDay()]?.steps ?: 0L,
                highlighted = date == today
            )
        }
        WeekBarChart(
            data = bars,
            accent = palette.steps,
            formatValue = { formatCount(it) },
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = "Данные за ${week.daysWithMovement} ${declineDays(week.daysWithMovement)} из 7",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun SleepSection(
    week: WeekSummary,
    days: List<com.fitnessapp.summary.data.DailySummary>,
    weekStart: LocalDate,
    today: LocalDate,
    palette: MetricPalette
) {
    InfoCard(title = "Сон") {
        if (week.nightsWithSleep == 0) {
            Text(
                text = "За эту неделю записей сна нет.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@InfoCard
        }

        Text(
            text = formatSleepDuration(week.avgSleepMinutes),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            // Says what the average is actually over. A "7 ч 10 мин" built from three
            // nights is a different claim than one built from seven, and hiding that
            // makes the number look more solid than it is.
            text = "в среднем за ${week.nightsWithSleep} ${declineDays(week.nightsWithSleep)} с записью",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val byDay = days.associateBy { it.dateEpochDay }
        val bars = (0..6).map { offset ->
            val date = weekStart.plusDays(offset.toLong())
            BarDatum(
                label = WEEKDAY_LABELS[offset],
                value = (byDay[date.toEpochDay()]?.sleepTotalMinutes ?: 0).toLong(),
                highlighted = date == today
            )
        }
        WeekBarChart(
            data = bars,
            accent = palette.sleep,
            formatValue = { formatSleepDuration(it.toInt()) },
            modifier = Modifier.padding(top = 12.dp),
            barAreaHeight = 90
        )

        if (week.avgDeepSleepMinutes > 0 || week.avgRemSleepMinutes > 0) {
            Column(modifier = Modifier.padding(top = 10.dp)) {
                StatRow("Глубокий в среднем", formatDuration(week.avgDeepSleepMinutes))
                StatRow("Быстрый в среднем", formatDuration(week.avgRemSleepMinutes))
            }
        }
    }
}

@Composable
private fun HeartSection(week: WeekSummary) {
    if (week.avgRestingHeartRate <= 0 && week.maxHeartRate <= 0) return

    InfoCard(title = "Пульс") {
        if (week.avgRestingHeartRate > 0) {
            StatRow("Пульс покоя, средний", "${formatHeartRate(week.avgRestingHeartRate)} уд/мин")
            StatRow("Пульс покоя, лучший", "${formatHeartRate(week.minRestingHeartRate)} уд/мин")
        }
        if (week.maxHeartRate > 0) {
            StatRow("Максимальный за неделю", "${formatHeartRate(week.maxHeartRate)} уд/мин")
        }
    }
}
