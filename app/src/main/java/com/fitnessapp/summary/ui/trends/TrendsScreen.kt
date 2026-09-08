package com.fitnessapp.summary.ui.trends

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fitnessapp.summary.FitnessSummaryApp
import com.fitnessapp.summary.analytics.Insight
import com.fitnessapp.summary.analytics.InsightTone
import com.fitnessapp.summary.analytics.LifestyleAnalytics
import com.fitnessapp.summary.analytics.LifestyleInputs
import com.fitnessapp.summary.analytics.TrendPoint
import com.fitnessapp.summary.ui.components.EmptyState
import com.fitnessapp.summary.ui.components.InfoCard
import com.fitnessapp.summary.ui.components.SectionHeader
import com.fitnessapp.summary.ui.components.StatRow
import com.fitnessapp.summary.ui.components.TrendChart
import com.fitnessapp.summary.ui.theme.metricPalette
import com.fitnessapp.summary.util.formatCount
import com.fitnessapp.summary.util.formatDecimal
import com.fitnessapp.summary.util.formatSleepDuration
import java.time.LocalDate

// Now that the history walk pulls everything Garmin has rather than a fixed 90 days,
// the windows go past a quarter: a year of weight or VO2max is a different picture from
// twelve weeks of it. A window with no data just draws "нет данных за этот период".
private val WINDOWS = listOf(
    28 to "4 недели",
    84 to "12 недель",
    182 to "полгода",
    365 to "год"
)

/**
 * Trends longer than a week, plus the plain-language reading of them (analytics/).
 *
 * The insights come first because they're the answer; the charts under them are the
 * evidence, in the same order, so every verdict can be checked against the line it was
 * drawn from. Everything here is Garmin-derived and therefore gated on the Garmin login -
 * an install without it gets a pointer to the Я tab, not an empty grid of charts.
 */
@Composable
fun TrendsScreen(app: FitnessSummaryApp) {
    val today = remember { LocalDate.now() }
    var windowIndex by remember { mutableIntStateOf(0) }
    val windowDays = WINDOWS[windowIndex].first
    val from = today.minusDays((windowDays - 1).toLong())
    val fromEpoch = from.toEpochDay()
    val toEpoch = today.toEpochDay()

    val summaries by remember(windowDays) { app.database.garminDailyExtraDao().observeRange(fromEpoch, toEpoch) }.collectAsState(initial = emptyList())
    val sleeps by remember(windowDays) { app.database.garminSleepDao().observeRange(fromEpoch, toEpoch) }.collectAsState(initial = emptyList())
    val hrvs by remember(windowDays) { app.database.garminHrvDao().observeRange(fromEpoch, toEpoch) }.collectAsState(initial = emptyList())
    val readiness by remember(windowDays) { app.database.garminReadinessDao().observeRange(fromEpoch, toEpoch) }.collectAsState(initial = emptyList())
    val training by remember(windowDays) { app.database.garminTrainingDao().observeRange(fromEpoch, toEpoch) }.collectAsState(initial = emptyList())
    val weights by remember(windowDays) { app.database.garminBodyCompositionDao().observeRange(fromEpoch, toEpoch) }.collectAsState(initial = emptyList())
    val activities by remember(windowDays) { app.database.garminActivityDao().observeRange(fromEpoch, toEpoch) }.collectAsState(initial = emptyList())
    val healthDays by remember(windowDays) { app.summaryRepository.observeRange(from, today) }.collectAsState(initial = emptyList())
    val scaleWeights by remember(windowDays) { app.database.scaleMeasurementDao().observeRange(fromEpoch, toEpoch) }.collectAsState(initial = emptyList())

    val inputs = remember(summaries, sleeps, hrvs, readiness, training, weights, activities, healthDays, scaleWeights) {
        LifestyleInputs(today, summaries, sleeps, hrvs, readiness, training, weights, activities, healthDays, scaleWeights)
    }
    val insights = remember(inputs) { LifestyleAnalytics.insights(inputs) }
    val palette = metricPalette()

    val nothingFromGarmin = summaries.isEmpty() && sleeps.isEmpty() && readiness.isEmpty() && hrvs.isEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WINDOWS.forEachIndexed { index, (_, label) ->
                    val selected = index == windowIndex
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(16.dp)
                            )
                            .clickable { windowIndex = index }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        if (nothingFromGarmin) {
            item {
                EmptyState(
                    emoji = "📈",
                    title = "Трендов пока нет",
                    message = if (app.garminAuth.isLoggedIn) {
                        "Данные Garmin ещё не загружены. Нажмите «Вся история» во вкладке «Я» — для трендов нужно хотя бы две недели."
                    } else {
                        "Аналитика строится на данных Garmin напрямую: Sleep Score, ВСР, готовность, стресс. Войдите в Garmin во вкладке «Я»."
                    }
                )
            }
            return@LazyColumn
        }

        if (insights.isNotEmpty()) {
            item { SectionHeader("Что видно за последнюю неделю") }
            insights.forEach { insight ->
                item(key = insight.title) { InsightCard(insight, palette.readiness, palette.distance, palette.calories) }
            }
        }

        item { SectionHeader("Тренды за ${WINDOWS[windowIndex].second}") }

        trendCard("Готовность к тренировке", LifestyleAnalytics.readinessTrend(readiness), fromEpoch, toEpoch, palette.readiness, floorAtZero = true) { it.toInt().toString() }
        trendCard("Sleep Score", LifestyleAnalytics.sleepScoreTrend(sleeps), fromEpoch, toEpoch, palette.sleep, floorAtZero = true) { it.toInt().toString() }
        trendCard("Длительность сна", LifestyleAnalytics.sleepDurationTrend(sleeps), fromEpoch, toEpoch, palette.sleep, goal = 480f) { formatSleepDuration(it.toInt()) }

        val latestHrv = hrvs.lastOrNull { it.hasBaseline }
        trendCard(
            "ВСР за ночь, мс",
            LifestyleAnalytics.hrvTrend(hrvs), fromEpoch, toEpoch, palette.hrv,
            band = latestHrv?.let { it.baselineBalancedLow.toFloat()..it.baselineBalancedUpper.toFloat() },
            footnote = latestHrv?.let { "Закрашено: ваша базовая линия ${it.baselineBalancedLow}–${it.baselineBalancedUpper} мс" }
        ) { it.toInt().toString() }

        trendCard("Пульс покоя", LifestyleAnalytics.restingHeartRateTrend(summaries, healthDays), fromEpoch, toEpoch, palette.heart) { it.toInt().toString() }
        trendCard("Стресс, средний за день", LifestyleAnalytics.stressTrend(summaries), fromEpoch, toEpoch, palette.stress, floorAtZero = true, goal = 50f, footnote = "Пунктир: 50 — граница зоны низкого стресса по Garmin") { it.toInt().toString() }
        trendCard("Body Battery при пробуждении", LifestyleAnalytics.bodyBatteryWakeTrend(summaries), fromEpoch, toEpoch, palette.bodyBattery, floorAtZero = true, goal = 50f) { it.toInt().toString() }
        trendCard("Шаги", LifestyleAnalytics.stepsTrend(summaries, healthDays), fromEpoch, toEpoch, palette.steps, floorAtZero = true, goal = 10000f) { formatCount(it.toLong()) }
        trendCard("Интенсивные минуты за день", LifestyleAnalytics.intensityMinutesTrend(summaries), fromEpoch, toEpoch, palette.workout, floorAtZero = true, footnote = "Интенсивные минуты считаются вдвое, как в Garmin. Недельная цель — на вкладке «Неделя».") { it.toInt().toString() }

        val vo2 = LifestyleAnalytics.vo2MaxTrend(training)
        if (vo2.isNotEmpty()) trendCard("VO2max", vo2, fromEpoch, toEpoch, palette.training) { formatDecimal(it) }

        val acute = LifestyleAnalytics.acuteLoadTrend(training)
        if (acute.isNotEmpty()) {
            val latest = training.lastOrNull { it.hasLoad }
            trendCard(
                "Острая нагрузка (7 дней)", acute, fromEpoch, toEpoch, palette.training, floorAtZero = true,
                goal = latest?.dailyTrainingLoadChronic?.takeIf { it > 0 }?.toFloat(),
                footnote = latest?.let { "Пунктир: хроническая нагрузка ${it.dailyTrainingLoadChronic}. Оптимум по Garmin — острая в пределах 0,8–1,3 от хронической." }
            ) { it.toInt().toString() }
        }

        val weightPoints = LifestyleAnalytics.weightTrend(weights, scaleWeights)
        if (weightPoints.isNotEmpty()) trendCard("Вес, кг", weightPoints, fromEpoch, toEpoch, palette.weight) { formatDecimal(it) }

        item {
            Text(
                text = "Все выводы — простые правила поверх метрик Garmin, без модели: порог и число дней, на которых он построен, всегда названы в самом выводе.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.trendCard(
    title: String,
    points: List<TrendPoint>,
    fromEpoch: Long,
    toEpoch: Long,
    accent: Color,
    floorAtZero: Boolean = false,
    goal: Float? = null,
    band: ClosedFloatingPointRange<Float>? = null,
    footnote: String? = null,
    formatValue: (Float) -> String
) {
    item(key = title) {
        InfoCard(title = title) {
            TrendChart(
                points = points,
                fromEpochDay = fromEpoch,
                toEpochDay = toEpoch,
                accent = accent,
                formatValue = formatValue,
                floorAtZero = floorAtZero,
                goal = goal,
                band = band,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (footnote != null && points.isNotEmpty()) {
                Text(
                    text = footnote,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (points.isNotEmpty()) {
                StatRow("Дней с данными", points.count { it.epochDay in fromEpoch..toEpoch }.toString())
            }
        }
    }
}

@Composable
private fun InsightCard(insight: Insight, neutral: Color, good: Color, attention: Color) {
    val accent = when (insight.tone) {
        InsightTone.GOOD -> good
        InsightTone.ATTENTION -> attention
        InsightTone.NEUTRAL -> neutral
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            // A thin tone stripe rather than a coloured card: the words carry the verdict,
            // the colour only says whether it's a nudge or a pat on the back.
            Column(
                modifier = Modifier
                    .width(4.dp)
                    .padding(vertical = 2.dp)
                    .background(accent, RoundedCornerShape(2.dp))
            ) { Text(" ", style = MaterialTheme.typography.titleMedium) }
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = "${insight.emoji} ${insight.title}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = insight.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
