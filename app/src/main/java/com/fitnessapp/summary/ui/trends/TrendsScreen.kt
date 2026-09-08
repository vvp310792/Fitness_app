package com.fitnessapp.summary.ui.trends

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.fitnessapp.summary.analytics.LiftSession
import com.fitnessapp.summary.analytics.StrengthAnalytics
import com.fitnessapp.summary.analytics.StrengthLift
import com.fitnessapp.summary.analytics.TrendPoint
import com.fitnessapp.summary.ui.components.EmptyState
import com.fitnessapp.summary.ui.components.InfoCard
import com.fitnessapp.summary.ui.components.SectionHeader
import com.fitnessapp.summary.ui.components.StatRow
import com.fitnessapp.summary.ui.components.TrendChart
import com.fitnessapp.summary.ui.theme.metricPalette
import com.fitnessapp.summary.util.formatCount
import com.fitnessapp.summary.util.formatDays
import com.fitnessapp.summary.util.formatDecimal
import com.fitnessapp.summary.util.formatSleepDuration
import java.time.LocalDate

// Now that the history walk pulls everything Garmin has rather than a fixed 90 days,
// the windows go past a quarter and then past a year: weight, VO2max or resting heart
// rate across three years show a shape - a slow drift, last winter against this one -
// that no twelve-week view contains. Depth costs nothing here: the rows are already in
// Room, and a window with no data behind it just draws "нет данных за этот период".
private val WINDOWS = listOf(
    28 to "4 недели",
    84 to "12 недель",
    182 to "полгода",
    365 to "год",
    730 to "2 года",
    1095 to "3 года"
)

/**
 * Trends longer than a week, plus the plain-language reading of them (analytics/).
 *
 * The insights come first because they're the answer; the charts under them are the
 * evidence, in the same order, so every verdict can be checked against the line it was
 * drawn from. Everything here is Garmin-derived and therefore gated on the Garmin login -
 * an install without it gets a pointer to the Я tab, not an empty grid of charts.
 */
// FlowRow is still marked experimental in Compose Foundation 1.7 (BOM 2024.12.01), but
// it is the layout for the window chips: they must wrap, not scroll off the edge.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrendsScreen(app: FitnessSummaryApp) {
    val today = remember { LocalDate.now() }
    var windowIndex by remember { mutableIntStateOf(0) }
    val windowDays = WINDOWS[windowIndex].first
    val from = today.minusDays((windowDays - 1).toLong())
    val fromEpoch = from.toEpochDay()
    val toEpoch = today.toEpochDay()

    val smoothWindow = LifestyleAnalytics.smoothWindowDaysFor(windowDays)

    val summaries by remember(windowDays) { app.database.garminDailyExtraDao().observeRange(fromEpoch, toEpoch) }.collectAsState(initial = emptyList())
    val sleeps by remember(windowDays) { app.database.garminSleepDao().observeRange(fromEpoch, toEpoch) }.collectAsState(initial = emptyList())
    val hrvs by remember(windowDays) { app.database.garminHrvDao().observeRange(fromEpoch, toEpoch) }.collectAsState(initial = emptyList())
    val readiness by remember(windowDays) { app.database.garminReadinessDao().observeRange(fromEpoch, toEpoch) }.collectAsState(initial = emptyList())
    val training by remember(windowDays) { app.database.garminTrainingDao().observeRange(fromEpoch, toEpoch) }.collectAsState(initial = emptyList())
    val weights by remember(windowDays) { app.database.garminBodyCompositionDao().observeRange(fromEpoch, toEpoch) }.collectAsState(initial = emptyList())
    val activities by remember(windowDays) { app.database.garminActivityDao().observeRange(fromEpoch, toEpoch) }.collectAsState(initial = emptyList())
    val healthDays by remember(windowDays) { app.summaryRepository.observeRange(from, today) }.collectAsState(initial = emptyList())
    val scaleWeights by remember(windowDays) { app.database.scaleMeasurementDao().observeRange(fromEpoch, toEpoch) }.collectAsState(initial = emptyList())
    val strengthSets by remember(windowDays) { app.database.strengthSetDao().observeTrackedRange(fromEpoch, toEpoch) }.collectAsState(initial = emptyList())

    // Sessions per lift, recomputed only when the imported sets or the window change.
    val liftSessions = remember(strengthSets) {
        StrengthLift.entries
            .map { it to StrengthAnalytics.sessionsOf(strengthSets, it) }
            .filter { (_, sessions) -> sessions.isNotEmpty() }
    }

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
            // Six labels no longer fit one line on a phone - they wrap rather than
            // scroll sideways, so the longest window is never hidden off the edge.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                    // With an imported gym log on screen above, "нет трендов" would be a lie -
                    // it is the Garmin half specifically that is missing.
                    title = if (liftSessions.isEmpty()) "Трендов пока нет" else "Данных Garmin пока нет",
                    message = if (app.garminAuth.isLoggedIn) {
                        "Данные Garmin ещё не загружены. Нажмите «Вся история» во вкладке «Я» — для трендов нужно хотя бы две недели."
                    } else {
                        "Аналитика строится на данных Garmin напрямую: Sleep Score, ВСР, готовность, стресс. Войдите в Garmin во вкладке «Я»."
                    }
                )
            }
            // The gym log is a separate source: no Garmin data is no reason to hide it.
            strengthSection(liftSessions, fromEpoch, toEpoch, smoothWindow, palette.workout)
            return@LazyColumn
        }

        if (insights.isNotEmpty()) {
            item { SectionHeader("Что видно за последнюю неделю") }
            insights.forEach { insight ->
                item(key = insight.title) { InsightCard(insight, palette.readiness, palette.distance, palette.calories) }
            }
        }

        item { SectionHeader("Тренды за ${WINDOWS[windowIndex].second}") }
        item {
            Text(
                text = "Плавная линия на графиках — скользящее среднее за ${formatDays(smoothWindow)}: " +
                    "точки показывают, что было в конкретный день, линия — куда идёт дело. " +
                    "Окно среднего растёт вместе с периодом.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        trendCard("Готовность к тренировке", LifestyleAnalytics.readinessTrend(readiness), fromEpoch, toEpoch, palette.readiness, smoothWindow, floorAtZero = true) { it.toInt().toString() }
        trendCard("Sleep Score", LifestyleAnalytics.sleepScoreTrend(sleeps), fromEpoch, toEpoch, palette.sleep, smoothWindow, floorAtZero = true) { it.toInt().toString() }
        trendCard("Длительность сна", LifestyleAnalytics.sleepDurationTrend(sleeps), fromEpoch, toEpoch, palette.sleep, smoothWindow, goal = 480f) { formatSleepDuration(it.toInt()) }

        val latestHrv = hrvs.lastOrNull { it.hasBaseline }
        trendCard(
            "ВСР за ночь, мс",
            LifestyleAnalytics.hrvTrend(hrvs), fromEpoch, toEpoch, palette.hrv, smoothWindow,
            band = latestHrv?.let { it.baselineBalancedLow.toFloat()..it.baselineBalancedUpper.toFloat() },
            footnote = latestHrv?.let { "Закрашено: ваша базовая линия ${it.baselineBalancedLow}–${it.baselineBalancedUpper} мс" }
        ) { it.toInt().toString() }

        trendCard("Пульс покоя", LifestyleAnalytics.restingHeartRateTrend(summaries, healthDays), fromEpoch, toEpoch, palette.heart, smoothWindow) { it.toInt().toString() }
        trendCard("Стресс, средний за день", LifestyleAnalytics.stressTrend(summaries), fromEpoch, toEpoch, palette.stress, smoothWindow, floorAtZero = true, goal = 50f, footnote = "Пунктир: 50 — граница зоны низкого стресса по Garmin") { it.toInt().toString() }
        trendCard("Body Battery при пробуждении", LifestyleAnalytics.bodyBatteryWakeTrend(summaries), fromEpoch, toEpoch, palette.bodyBattery, smoothWindow, floorAtZero = true, goal = 50f) { it.toInt().toString() }
        trendCard("Шаги", LifestyleAnalytics.stepsTrend(summaries, healthDays), fromEpoch, toEpoch, palette.steps, smoothWindow, floorAtZero = true, goal = 10000f) { formatCount(it.toLong()) }
        trendCard("Интенсивные минуты за день", LifestyleAnalytics.intensityMinutesTrend(summaries), fromEpoch, toEpoch, palette.workout, smoothWindow, floorAtZero = true, footnote = "Интенсивные минуты считаются вдвое, как в Garmin. Недельная цель — на вкладке «Неделя».") { it.toInt().toString() }

        val vo2 = LifestyleAnalytics.vo2MaxTrend(training)
        if (vo2.isNotEmpty()) trendCard("VO2max", vo2, fromEpoch, toEpoch, palette.training, smoothWindow) { formatDecimal(it) }

        val acute = LifestyleAnalytics.acuteLoadTrend(training)
        if (acute.isNotEmpty()) {
            val latest = training.lastOrNull { it.hasLoad }
            trendCard(
                "Острая нагрузка (7 дней)", acute, fromEpoch, toEpoch, palette.training, smoothWindow, floorAtZero = true,
                goal = latest?.dailyTrainingLoadChronic?.takeIf { it > 0 }?.toFloat(),
                footnote = latest?.let { "Пунктир: хроническая нагрузка ${it.dailyTrainingLoadChronic}. Оптимум по Garmin — острая в пределах 0,8–1,3 от хронической." }
            ) { it.toInt().toString() }
        }

        val weightPoints = LifestyleAnalytics.weightTrend(weights, scaleWeights)
        if (weightPoints.isNotEmpty()) trendCard("Вес, кг", weightPoints, fromEpoch, toEpoch, palette.weight, smoothWindow) { formatDecimal(it) }

        strengthSection(liftSessions, fromEpoch, toEpoch, smoothWindow, palette.workout)

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
    smoothWindowDays: Int,
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
                smoothWindowDays = smoothWindowDays,
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

/**
 * The base lifts the user actually tracks: what could be lifted once (estimated) against
 * what the work is being done at, per lift, over the same window as everything else.
 *
 * One card per lift and one chart in it: the solid line is the estimated 1RM, the dashed
 * one the working weight. Both in kilograms, both the same colour - it is one measure of
 * one lift seen two ways, and the distance between them is the story (a 1RM that climbs
 * while the working weight sits still is a rep PR, not a load PR).
 *
 * Shown only for lifts that actually have sessions in the window, and independent of the
 * Garmin login: this data comes from an imported gym log, not from a watch.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.strengthSection(
    liftSessions: List<Pair<StrengthLift, List<LiftSession>>>,
    fromEpoch: Long,
    toEpoch: Long,
    smoothWindowDays: Int,
    accent: Color
) {
    if (liftSessions.isEmpty()) return

    item { SectionHeader("Силовые показатели") }
    item {
        Text(
            text = "1ПМ — расчётный разовый максимум по формуле Эпли (вес × (1 + повторы/30)) " +
                "по лучшему подходу тренировки; подходы длиннее ${StrengthAnalytics.MAX_REPS_FOR_ONE_RM} повторов " +
                "в расчёт не идут. Рабочий вес — самый тяжёлый вес, сделанный минимум в двух подходах. " +
                "Сплошная линия на графике — 1ПМ, пунктир — рабочий вес.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    liftSessions.forEach { (lift, sessions) ->
        item(key = "lift-${lift.key}") {
            val inWindow = sessions.filter { it.dateEpochDay in fromEpoch..toEpoch }
            InfoCard(title = lift.title) {
                if (inWindow.isEmpty()) {
                    Text(
                        text = "Нет тренировок за этот период.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    return@InfoCard
                }
                val last = inWindow.last()
                StatRow("1ПМ (оценка)", "${formatDecimal(last.oneRmKg)} кг")
                StatRow("Рабочий вес", "${formatDecimal(last.workingWeightKg)} кг × ${last.workingReps}")
                StatRow("Лучший подход", "${formatDecimal(last.topWeightKg)} кг × ${last.topReps}")
                StrengthAnalytics.changeKg(inWindow)?.let { change ->
                    val sign = if (change > 0) "+" else ""
                    StatRow("Изменение 1ПМ за период", "$sign${formatDecimal(change)} кг")
                }
                StatRow("Тренировок за период", inWindow.size.toString())
                // A programme can swap the variant (сумо вместо классики) without it being a
                // different lift - name it rather than let the numbers jump unexplained.
                val variants = inWindow.map { it.exerciseName }.distinct()
                if (variants.size > 1) {
                    Text(
                        text = "В этот период: ${variants.joinToString(", ")}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                TrendChart(
                    points = StrengthAnalytics.oneRmTrend(inWindow),
                    fromEpochDay = fromEpoch,
                    toEpochDay = toEpoch,
                    accent = accent,
                    formatValue = { "${formatDecimal(it)} кг" },
                    smoothWindowDays = smoothWindowDays,
                    secondary = StrengthAnalytics.workingWeightTrend(inWindow),
                    modifier = Modifier.padding(top = 8.dp)
                )
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
