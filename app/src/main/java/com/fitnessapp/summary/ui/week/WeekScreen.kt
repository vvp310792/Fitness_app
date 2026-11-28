package com.fitnessapp.summary.ui.week

import com.fitnessapp.summary.ui.components.readScreen
import com.fitnessapp.summary.ui.components.ReadStatus
import com.fitnessapp.summary.ui.components.rememberCurrentDate
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.TextButton
import com.fitnessapp.summary.ui.components.LocalDateSaver
import com.fitnessapp.summary.ui.components.DateNavigator
import com.fitnessapp.summary.ui.components.barState
import com.fitnessapp.summary.analytics.ArchiveResult
import com.fitnessapp.summary.analytics.WorkoutPeriod
import com.fitnessapp.summary.ui.workouts.WorkoutGroupCard
import com.fitnessapp.summary.ui.workouts.WorkoutTotals

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
import com.fitnessapp.summary.analytics.WeekComparison
import com.fitnessapp.summary.analytics.WeekComparisonResult
import com.fitnessapp.summary.analytics.ComparedWeekMetric
import com.fitnessapp.summary.data.DayView
import com.fitnessapp.summary.data.GarminDailyExtra
import com.fitnessapp.summary.data.GarminSleep
import com.fitnessapp.summary.data.SummaryRepository
import com.fitnessapp.summary.data.WeekSummary
import com.fitnessapp.summary.ui.components.BarDatum
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
import com.fitnessapp.summary.util.weekStart
import java.time.LocalDate

@Composable
fun WeekScreen(app:FitnessSummaryApp,onOpenDay:(LocalDate)->Unit={},onSettings:()->Unit={}) {
    val today=rememberCurrentDate()
    val thisWeekStart=weekStart(today)
    var selectedWeekStart by rememberSaveable(stateSaver=LocalDateSaver){mutableStateOf(thisWeekStart)}
    var retry by remember { mutableStateOf(0) }

    // Two weeks are loaded in one query - the selected one and the one before it -
    // because the week-over-week comparison needs both, and a second subscription
    // would just re-read overlapping rows.
    val weekFromEpoch = selectedWeekStart.toEpochDay()
    val weekToEpoch = selectedWeekStart.plusDays(6).toEpochDay()
    val healthRowsRead=readScreen(remember(selectedWeekStart) { app.unifiedRead.observeHealthDays(weekFromEpoch-7,weekToEpoch) },retry)
    val healthRows=healthRowsRead.value.orEmpty()
    val days=remember(healthRows) { healthRows.map { it.summary } }
    val archiveRead=readScreen(remember(app) { app.unifiedRead.observeArchive() },retry)
    val archive=archiveRead.value
    val period=remember(archive,weekFromEpoch,weekToEpoch) { WorkoutPeriod.between(archive,weekFromEpoch,weekToEpoch) }
    val garminDaysRangeRead=readScreen(remember(selectedWeekStart) { app.unifiedRead.observeGarminDays(weekFromEpoch - 7, weekToEpoch) },retry)
    val garminDaysRange=garminDaysRangeRead.value.orEmpty()
    val garminSleepsRangeRead=readScreen(remember(selectedWeekStart) { app.unifiedRead.observeGarminSleeps(weekFromEpoch - 7, weekToEpoch) },retry)
    val garminSleepsRange=garminSleepsRangeRead.value.orEmpty()
    val garminDays = remember(garminDaysRange, weekFromEpoch) { garminDaysRange.filter { it.dateEpochDay >= weekFromEpoch } }
    val garminSleeps = remember(garminSleepsRange, weekFromEpoch) { garminSleepsRange.filter { it.dateEpochDay >= weekFromEpoch } }
    val fitDaysRangeRead=readScreen(remember(selectedWeekStart) { app.database.fitDayDao().observeRange(weekFromEpoch - 7, weekToEpoch) },retry)
    val fitDaysRange=fitDaysRangeRead.value.orEmpty()

    // Every number below is computed from the MERGED day, not the Health Connect row:
    // Garmin is the first source, Health Connect fills its gaps (see [DayView]). Before
    // this, steps and calories came from a Health Connect copy that other apps also write
    // into, and sleep came from a copy Garmin fills only sporadically.
    val mergedDays = remember(days, garminDaysRange, garminSleepsRange, fitDaysRange) {
        DayView.mergeRange(days, garminDaysRange, garminSleepsRange, fitDaysRange)
    }

    val week = remember(mergedDays, period, selectedWeekStart) {
        SummaryRepository.computeWeekSummary(selectedWeekStart, mergedDays, emptyList()).copy(
            workoutCount=period.confirmed.size,workoutMinutes=(period.durationSeconds/60).toInt(),
            workoutDistanceMeters=period.distanceMeters.coerceAtMost(Int.MAX_VALUE.toDouble()).toInt())
    }
    val comparison = remember(mergedDays, selectedWeekStart, today) {
        WeekComparison.compare(mergedDays, selectedWeekStart, today)
    }
    val garminHrvsRead=readScreen(remember(selectedWeekStart) { app.database.garminHrvDao().observeRange(weekFromEpoch, weekToEpoch) },retry)
    val garminHrvs=garminHrvsRead.value.orEmpty()
    val garminReadinessRead=readScreen(remember(selectedWeekStart) { app.database.garminReadinessDao().observeRange(weekFromEpoch, weekToEpoch) },retry)
    val garminReadiness=garminReadinessRead.value.orEmpty()
    val garminActivities=period.garminActivities
    val garminWeek = remember(garminDays, garminSleeps, garminHrvs, garminReadiness, garminActivities, selectedWeekStart) {
        LifestyleAnalytics.computeWeek(selectedWeekStart, garminDays, garminSleeps, garminHrvs, garminReadiness, garminActivities)
    }

    val palette = metricPalette()
    val reads=listOf(healthRowsRead,archiveRead,garminDaysRangeRead,garminSleepsRangeRead,fitDaysRangeRead,garminHrvsRead,garminReadinessRead)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            DateNavigator(formatWeekHeader(selectedWeekStart,today),
                subtitle=formatWeekRange(selectedWeekStart,selectedWeekStart.plusDays(6)),
                canGoForward=selectedWeekStart.isBefore(thisWeekStart),previousLabel="Предыдущая неделя",
                nextLabel="Следующая неделя",currentLabel="Эта неделя",selected=selectedWeekStart,today=today,
                onPrevious={selectedWeekStart=selectedWeekStart.minusWeeks(1)},onNext={selectedWeekStart=selectedWeekStart.plusWeeks(1)},
                onPick={selectedWeekStart=weekStart(it)},onCurrent={selectedWeekStart=thisWeekStart},
                atCurrent=selectedWeekStart==thisWeekStart)
        }
        if(reads.any{!it.loaded || it.failed})item { ReadStatus(reads,{retry++},onSettings) }

        if (week.isEmpty && garminWeek.isEmpty && period.episodes.isEmpty() && healthRows.none { it.floors!=null && it.summary.dateEpochDay in weekFromEpoch..weekToEpoch }) {
            if(reads.all{it.loaded && !it.failed})item {
                EmptyState(emoji="📊",title="За эту неделю данных нет",
                    message="Подключите источники на вкладке «Я» или выберите другую неделю.")
                TextButton(onClick=onSettings){Text("Источники данных")}
            }
            return@LazyColumn
        }

        if (!week.isEmpty) {
            item {
                WeekTotals(week = week, comparison = comparison, palette = palette)
            }
        }
        if (!garminWeek.isEmpty) {
            item { GarminWeekSection(garminWeek,garminDays,garminSleeps,selectedWeekStart,today,palette,onOpenDay) }
        }
        if (!week.isEmpty) {
            item { StepsChart(week,mergedDays,selectedWeekStart,today,palette,onOpenDay) }
            item { SleepSection(week,mergedDays,selectedWeekStart,today,palette,onOpenDay) }
            item { HeartSection(week) }
        }

        val ghFloors=healthRows.filter { it.summary.dateEpochDay in weekFromEpoch..weekToEpoch && it.floors!=null }
        val floorDays=garminDays.filter { it.floorsAscended>0 }.map { it.dateEpochDay }.toSet()
        val additionalFloors=ghFloors.filter { it.summary.dateEpochDay !in floorDays }
        if(additionalFloors.isNotEmpty()) item { InfoCard("Этажи · Google Health") {
            StatRow("По дням без Garmin",formatCount(additionalFloors.sumOf { it.floors ?: 0.0 }.toLong()))
            Text("Дополнительные дни: ${additionalFloors.size}. Не суммируются с копиями Garmin.",style=MaterialTheme.typography.bodySmall)
        } }
        if (archive!=null) {
            if(period.episodes.isNotEmpty())item { InfoCard("Тренировки за неделю") { WorkoutTotals(period,detailed=false) } }
            items(period.displayGroups,key={it.key}) { group -> WorkoutGroupCard(group,showDate=true) }
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
    palette: MetricPalette,
    onOpenDay:(LocalDate)->Unit
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
                        highlighted = date == today,
                        state = barState(date.isAfter(today), ((byDay[date.toEpochDay()]?.intensityMinutesWeighted ?: 0).toLong()) > 0L)
                    )
                }
                WeekBarChart(
                    data = bars,
                    accent = palette.workout,
                    formatValue = { it.toString() },
                    modifier = Modifier.padding(top = 10.dp),
                    barAreaHeight = 70,
                    onBarClick={onOpenDay(weekStart.plusDays(it.toLong()))}
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
                label = "Оценка сна",
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
                label = "Энергия утром",
                value = if (garmin.avgBodyBatteryAtWake > 0) garmin.avgBodyBatteryAtWake.toString() else "-",
                accent = palette.bodyBattery,
                modifier = Modifier.weight(1f)
            )
        }

        if (garmin.nightsWithScore > 0) {
            InfoCard(title = "Оценка сна по дням") {
                val byDay = garminSleeps.associateBy { it.dateEpochDay }
                val bars = (0..6).map { offset ->
                    val date = weekStart.plusDays(offset.toLong())
                    BarDatum(
                        label = WEEKDAY_LABELS[offset],
                        value = (byDay[date.toEpochDay()]?.score ?: 0).toLong(),
                        highlighted = date == today,
                        state = barState(date.isAfter(today), ((byDay[date.toEpochDay()]?.score ?: 0).toLong()) > 0L)
                    )
                }
                WeekBarChart(
                    data = bars,
                    accent = palette.sleep,
                    formatValue = { it.toString() },
                    modifier = Modifier.padding(top = 4.dp),
                    barAreaHeight = 80,
                    onBarClick={onOpenDay(weekStart.plusDays(it.toLong()))}
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
    comparison: WeekComparisonResult,
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
                label = "Однозначные занятия",
                value = week.workoutCount.toString(),
                accent = palette.workout,
                modifier = Modifier.weight(1f),
                caption = if (week.workoutMinutes > 0) formatDuration(week.workoutMinutes) else null
            )
        }

        InfoCard(title = "К прошлой неделе") {
            val n = comparison.completedDays
            if (n == 0) {
                Text("Сравнение появится после завершения первого дня недели.",
                    style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("Первые $n завершённых дней каждой недели. Сегодняшний неполный день исключён.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp))
                comparison.metrics.forEach { metric ->
                    val label = when (metric.metric) {
                        ComparedWeekMetric.STEPS -> "Шаги"
                        ComparedWeekMetric.DISTANCE -> "Дистанция"
                        ComparedWeekMetric.SLEEP -> "Сон"
                    }
                    if (metric.percent != null) {
                        StatRow(label, formatSignedPercent(metric.percent))
                    } else {
                        Text("$label: недостаточно данных (${metric.currentDays}/$n и ${metric.previousDays}/$n дн.)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 5.dp))
                    }
                }
                // The present WorkoutRepository does not yet reconcile every source.
                // Do not compare its partial training totals as the complete archive.
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
    palette: MetricPalette,
    onOpenDay:(LocalDate)->Unit
) {
    InfoCard(title = "Шаги по дням") {
        val byDay = days.associateBy { it.dateEpochDay }
        val bars = (0..6).map { offset ->
            val date = weekStart.plusDays(offset.toLong())
            BarDatum(
                label = WEEKDAY_LABELS[offset],
                value = byDay[date.toEpochDay()]?.steps ?: 0L,
                        highlighted = date == today,
                        state = barState(date.isAfter(today), (byDay[date.toEpochDay()]?.steps ?: 0L) > 0L)
            )
        }
        WeekBarChart(
            data = bars,
            accent = palette.steps,
            formatValue = { formatCount(it) },
            modifier = Modifier.padding(top = 4.dp),
            onBarClick={onOpenDay(weekStart.plusDays(it.toLong()))}
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
    palette: MetricPalette,
    onOpenDay:(LocalDate)->Unit
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
                        highlighted = date == today,
                        state = barState(date.isAfter(today), (minutes[offset].toLong()) > 0L)
            )
        }
        WeekBarChart(
            data = bars,
            accent = palette.sleep,
            formatValue = { formatSleepDuration(it.toInt()) },
            modifier = Modifier.padding(top = 12.dp),
            barAreaHeight = 90,
            onBarClick={onOpenDay(weekStart.plusDays(it.toLong()))}
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
