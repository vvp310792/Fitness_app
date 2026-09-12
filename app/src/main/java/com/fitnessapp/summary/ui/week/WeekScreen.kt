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
import com.fitnessapp.summary.analytics.GarminWeekSummary
import com.fitnessapp.summary.analytics.LifestyleAnalytics
import com.fitnessapp.summary.data.DayView
import com.fitnessapp.summary.data.GarminDailyExtra
import com.fitnessapp.summary.data.GarminSleep
import com.fitnessapp.summary.data.SummaryRepository
import com.fitnessapp.summary.data.WeekSummary
import com.fitnessapp.summary.ui.components.BarDatum
import com.fitnessapp.summary.ui.components.Chip
import com.fitnessapp.summary.ui.components.EmptyState
import com.fitnessapp.summary.ui.components.GarminActivityRow
import com.fitnessapp.summary.ui.components.InfoCard
import com.fitnessapp.summary.ui.components.MetricCard
import com.fitnessapp.summary.ui.components.ProgressBar
import com.fitnessapp.summary.ui.components.SectionHeader
import com.fitnessapp.summary.ui.components.StatRow
import com.fitnessapp.summary.ui.components.WeekBarChart
import com.fitnessapp.summary.ui.components.WorkoutRow
import com.fitnessapp.summary.ui.components.matchGarminActivity
import com.fitnessapp.summary.ui.components.unmatchedGarminActivities
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
import com.fitnessapp.summary.util.hrvStatusLabel
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

    // Garmin's own numbers, over the same two weeks the Health Connect query covers, so
    // the previous week is merged from both sources too - a comparison chip built from a
    // Health-Connect-only baseline against a Garmin-backed current week is how the two
    // disagreed in the first place. All empty for an install without the Garmin login.
    val weekFromEpoch = selectedWeekStart.toEpochDay()
    val weekToEpoch = selectedWeekStart.plusDays(6).toEpochDay()
    val garminDaysRange by remember(selectedWeekStart) { app.database.garminDailyExtraDao().observeRange(weekFromEpoch - 7, weekToEpoch) }.collectAsState(initial = emptyList())
    val garminSleepsRange by remember(selectedWeekStart) { app.database.garminSleepDao().observeRange(weekFromEpoch - 7, weekToEpoch) }.collectAsState(initial = emptyList())
    val garminDays = remember(garminDaysRange, weekFromEpoch) { garminDaysRange.filter { it.dateEpochDay >= weekFromEpoch } }
    val garminSleeps = remember(garminSleepsRange, weekFromEpoch) { garminSleepsRange.filter { it.dateEpochDay >= weekFromEpoch } }

    // Every number below is computed from the MERGED day, not the Health Connect row:
    // Garmin is the first source, Health Connect fills its gaps (see [DayView]). Before
    // this, steps and calories came from a Health Connect copy that other apps also write
    // into, and sleep came from a copy Garmin fills only sporadically.
    val mergedDays = remember(days, garminDaysRange, garminSleepsRange) {
        DayView.mergeRange(days, garminDaysRange, garminSleepsRange)
    }

    val week = remember(mergedDays, workouts, selectedWeekStart) {
        SummaryRepository.computeWeekSummary(selectedWeekStart, mergedDays, workouts)
    }
    val previousWeek = remember(mergedDays, previousWorkouts, selectedWeekStart) {
        SummaryRepository.computeWeekSummary(
            selectedWeekStart.minusWeeks(1),
            mergedDays,
            previousWorkouts
        )
    }
    val garminHrvs by remember(selectedWeekStart) { app.database.garminHrvDao().observeRange(weekFromEpoch, weekToEpoch) }.collectAsState(initial = emptyList())
    val garminReadiness by remember(selectedWeekStart) { app.database.garminReadinessDao().observeRange(weekFromEpoch, weekToEpoch) }.collectAsState(initial = emptyList())
    val garminActivities by remember(selectedWeekStart) { app.database.garminActivityDao().observeRange(weekFromEpoch, weekToEpoch) }.collectAsState(initial = emptyList())
    val garminWeek = remember(garminDays, garminSleeps, garminHrvs, garminReadiness, garminActivities, selectedWeekStart) {
        LifestyleAnalytics.computeWeek(selectedWeekStart, garminDays, garminSleeps, garminHrvs, garminReadiness, garminActivities)
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

        if (week.isEmpty && garminWeek.isEmpty) {
            item {
                EmptyState(
                    emoji = "📊",
                    title = "За эту неделю данных нет",
                    message = "Синхронизируйте Health Connect на вкладке «Я» или выберите другую неделю."
                )
            }
            return@LazyColumn
        }

        if (!week.isEmpty) {
            item {
                WeekTotals(week = week, previous = previousWeek, palette = palette)
            }
        }
        if (!garminWeek.isEmpty) {
            item { GarminWeekSection(garminWeek, garminDays, garminSleeps, selectedWeekStart, today, palette) }
        }
        if (!week.isEmpty) {
            item { StepsChart(week, mergedDays, selectedWeekStart, today, palette) }
            item { SleepSection(week, mergedDays, selectedWeekStart, today, palette) }
            item { HeartSection(week) }
        }

        val unmatched = unmatchedGarminActivities(workouts, garminActivities)
        val total = workouts.size + unmatched.size
        if (total > 0) {
            item {
                SectionHeader("$total ${declineWorkouts(total)} за неделю")
            }
            items(workouts, key = { it.recordId }) { workout ->
                WorkoutRow(
                    workout = workout,
                    showDate = formatDayMonth(LocalDate.ofEpochDay(workout.dateEpochDay)),
                    garmin = matchGarminActivity(workout, garminActivities)
                )
            }
            items(unmatched, key = { "garmin_${it.activityId}" }) { activity ->
                GarminActivityRow(
                    activity = activity,
                    showDate = formatDayMonth(LocalDate.ofEpochDay(activity.dateEpochDay))
                )
            }
        }
    }
}

/**
 * The Garmin half of the week: the weekly Intensity Minutes goal (the one weekly target
 * Garmin itself sets), then the recovery-side averages. Every average names how many
 * days it stands on, same as the Health Connect section.
 */
@Composable
private fun GarminWeekSection(
    garmin: GarminWeekSummary,
    garminDays: List<GarminDailyExtra>,
    garminSleeps: List<GarminSleep>,
    weekStart: LocalDate,
    today: LocalDate,
    palette: MetricPalette
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (garmin.daysWithSummary > 0) {
            InfoCard(title = "Интенсивные минуты") {
                val goal = garmin.intensityMinutesGoal
                Text(
                    text = "${garmin.intensityMinutesWeighted} из $goal",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                ProgressBar(
                    fraction = garmin.intensityMinutesWeighted.toFloat() / goal,
                    accent = palette.workout,
                    modifier = Modifier.padding(top = 6.dp)
                )
                val byDay = garminDays.associateBy { it.dateEpochDay }
                val bars = (0..6).map { offset ->
                    val date = weekStart.plusDays(offset.toLong())
                    BarDatum(
                        label = WEEKDAY_LABELS[offset],
                        value = (byDay[date.toEpochDay()]?.intensityMinutesWeighted ?: 0).toLong(),
                        highlighted = date == today
                    )
                }
                WeekBarChart(
                    data = bars,
                    accent = palette.workout,
                    formatValue = { it.toString() },
                    modifier = Modifier.padding(top = 10.dp),
                    barAreaHeight = 70
                )
                Text(
                    text = "Умеренные минуты плюс интенсивные вдвое — формула Garmin. Недельная цель Garmin по умолчанию 150 (рекомендация ВОЗ).",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                emoji = "🌙",
                label = "Sleep Score",
                value = if (garmin.avgSleepScore > 0) garmin.avgSleepScore.toString() else "-",
                accent = palette.sleep,
                modifier = Modifier.weight(1f),
                caption = if (garmin.nightsWithScore > 0) "за ${garmin.nightsWithScore} ${declineDays(garmin.nightsWithScore)}" else null
            )
            MetricCard(
                emoji = "🟢",
                label = "Готовность",
                value = if (garmin.avgReadiness > 0) garmin.avgReadiness.toString() else "-",
                accent = palette.readiness,
                modifier = Modifier.weight(1f),
                caption = if (garmin.daysWithReadiness > 0) "за ${garmin.daysWithReadiness} ${declineDays(garmin.daysWithReadiness)}" else null
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                emoji = "⚡",
                label = "Стресс, средний",
                value = if (garmin.avgStress > 0) garmin.avgStress.toString() else "-",
                accent = palette.stress,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                emoji = "🔋",
                label = "Body Battery утром",
                value = if (garmin.avgBodyBatteryAtWake > 0) garmin.avgBodyBatteryAtWake.toString() else "-",
                accent = palette.bodyBattery,
                modifier = Modifier.weight(1f)
            )
        }

        if (garmin.nightsWithScore > 0) {
            InfoCard(title = "Sleep Score по дням") {
                val byDay = garminSleeps.associateBy { it.dateEpochDay }
                val bars = (0..6).map { offset ->
                    val date = weekStart.plusDays(offset.toLong())
                    BarDatum(
                        label = WEEKDAY_LABELS[offset],
                        value = (byDay[date.toEpochDay()]?.score ?: 0).toLong(),
                        highlighted = date == today
                    )
                }
                WeekBarChart(
                    data = bars,
                    accent = palette.sleep,
                    formatValue = { it.toString() },
                    modifier = Modifier.padding(top = 4.dp),
                    barAreaHeight = 80
                )
                if (garmin.avgSleepNeedDeficitMinutes > 15) {
                    Text(
                        text = "В среднем не хватало ${formatDuration(garmin.avgSleepNeedDeficitMinutes)} до потребности во сне (Garmin Sleep Need).",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        val hasRecovery = garmin.avgHrvLastNight > 0 || garmin.avgRestingHeartRate > 0 || garmin.totalTrainingLoad > 0 || garmin.floorsAscended > 0
        if (hasRecovery) {
            InfoCard(title = "Восстановление и нагрузка") {
                if (garmin.avgHrvLastNight > 0) {
                    StatRow("ВСР за ночь, средняя", "${garmin.avgHrvLastNight} мс")
                }
                if (garmin.latestHrvStatus.isNotBlank()) {
                    StatRow("Статус ВСР", hrvStatusLabel(garmin.latestHrvStatus))
                }
                if (garmin.avgRestingHeartRate > 0) {
                    StatRow(
                        "Пульс покоя, средний",
                        "${formatHeartRate(garmin.avgRestingHeartRate)} уд/мин · " +
                            "${garmin.daysWithRestingHeartRate} ${declineDays(garmin.daysWithRestingHeartRate)} с ночью"
                    )
                }
                if (garmin.totalTrainingLoad > 0) {
                    StatRow("Нагрузка тренировок за неделю", garmin.totalTrainingLoad.toString())
                }
                if (garmin.floorsAscended > 0) {
                    StatRow("Этажей вверх", garmin.floorsAscended.toString())
                }
            }
        }
    }
}

@Composable
private fun WeekTotals(
    week: WeekSummary,
    previous: WeekSummary,
    palette: MetricPalette
) {
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
                label = "Калории за неделю",
                // The total, with the active part underneath: "how much did this week
                // cost" is the number people mean by calories, and the active share is
                // what training changed. Showing only the active half made the card
                // answer a question nobody had asked.
                value = if (week.totalCaloriesKcal > 0) {
                    formatCount(week.totalCaloriesKcal)
                } else if (week.totalActiveCaloriesKcal > 0) {
                    formatCount(week.totalActiveCaloriesKcal)
                } else "-",
                accent = palette.calories,
                modifier = Modifier.weight(1f),
                caption = buildList {
                    if (week.totalActiveCaloriesKcal > 0) add("активные ${formatCount(week.totalActiveCaloriesKcal)}")
                    if (week.avgTotalCaloriesKcal > 0) add("${formatCount(week.avgTotalCaloriesKcal)} в день")
                }.joinToString(" · ").ifBlank { null }
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
/**
 * The week's sleep, from **both** sources with Garmin's own night winning.
 *
 * Health Connect alone was wrong here, and visibly so: Garmin Connect writes sleep into
 * Health Connect only sporadically on this account - eight nights out of fifty-six in one
 * real log - so the bars were mostly empty and the average was built from whichever few
 * nights happened to get through. The Day screen already preferred the Garmin night; this
 * card did not, and the two screens disagreed about the same week.
 *
 * Precedence is Garmin first because it is the watch's own scoring of the night, and
 * Health Connect's copy is at best the same number arriving by a longer road. A night
 * missing from Garmin still falls back to Health Connect rather than showing zero.
 */
private fun SleepSection(
    week: WeekSummary,
    days: List<com.fitnessapp.summary.data.DailySummary>,
    weekStart: LocalDate,
    today: LocalDate,
    palette: MetricPalette
) {
    // `days` is already merged (Garmin first, Health Connect filling gaps - see [DayView]),
    // so the whole night, stages included, comes from one source and this screen no longer
    // has its own precedence rule to keep in step with the Day screen's.
    val byDay = days.associateBy { it.dateEpochDay }
    val nights = (0..6).map { weekStart.plusDays(it.toLong()) }
    val minutes = nights.map { byDay[it.toEpochDay()]?.sleepTotalMinutes ?: 0 }
    val recorded = minutes.filter { it > 0 }
    val deep = nights.mapNotNull { byDay[it.toEpochDay()]?.sleepDeepMinutes }.filter { it > 0 }
    val rem = nights.mapNotNull { byDay[it.toEpochDay()]?.sleepRemMinutes }.filter { it > 0 }

    InfoCard(title = "Сон") {
        if (recorded.isEmpty()) {
            Text(
                text = "За эту неделю записей сна нет.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@InfoCard
        }

        Text(
            text = formatSleepDuration(recorded.average().toInt()),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            // Says what the average is actually over. A "7 ч 10 мин" built from three
            // nights is a different claim than one built from seven, and hiding that
            // makes the number look more solid than it is.
            text = "в среднем за ${recorded.size} ${declineDays(recorded.size)} с записью",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val bars = nights.mapIndexed { offset, date ->
            BarDatum(
                label = WEEKDAY_LABELS[offset],
                value = minutes[offset].toLong(),
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

        if (deep.isNotEmpty() || rem.isNotEmpty()) {
            Column(modifier = Modifier.padding(top = 10.dp)) {
                if (deep.isNotEmpty()) StatRow("Глубокий в среднем", formatDuration(deep.average().toInt()))
                if (rem.isNotEmpty()) StatRow("Быстрый в среднем", formatDuration(rem.average().toInt()))
            }
        }
    }
}

/**
 * One night's sleep in minutes, Garmin's own scoring first and Health Connect as the
 * fallback, or 0 when neither source has that night.
 *
 * The precedence exists because Garmin Connect writes sleep into Health Connect only
 * sporadically on some accounts - eight nights out of fifty-six in one real log - so
 * reading Health Connect alone made most nights look like zero.
 */



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
