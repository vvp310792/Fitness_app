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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Box
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
import com.fitnessapp.summary.analytics.DistanceSport
import com.fitnessapp.summary.analytics.IntensityAnalytics
import com.fitnessapp.summary.analytics.IntensityBreakdown
import com.fitnessapp.summary.analytics.LiftSession
import com.fitnessapp.summary.analytics.StrengthAnalytics
import com.fitnessapp.summary.analytics.SportDistanceAnalytics
import com.fitnessapp.summary.analytics.SportWeek
import com.fitnessapp.summary.analytics.StrengthLift
import com.fitnessapp.summary.analytics.TrendPoint
import com.fitnessapp.summary.ui.components.EmptyState
import com.fitnessapp.summary.ui.components.InfoCard
import com.fitnessapp.summary.ui.components.SectionHeader
import com.fitnessapp.summary.ui.components.StatRow
import com.fitnessapp.summary.ui.components.TrendChart
import com.fitnessapp.summary.analytics.ZoneShare
import com.fitnessapp.summary.ui.theme.MetricPalette
import com.fitnessapp.summary.ui.theme.metricPalette
import com.fitnessapp.summary.util.formatCount
import com.fitnessapp.summary.util.formatDayMonth
import com.fitnessapp.summary.util.formatDays
import com.fitnessapp.summary.util.formatDecimal
import com.fitnessapp.summary.util.formatDuration
import com.fitnessapp.summary.util.formatDistance
import com.fitnessapp.summary.util.formatSleepDuration
import com.fitnessapp.summary.util.garminSportName
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
    1095 to "3 года",
    // Five years reaches past the start of this account's Garmin history (Dec 2021), so it
    // is the window that shows all of it - and the one where a year-on-year shape, rather
    // than a season, is what the chart is for.
    1825 to "5 лет"
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
    val strengthSets by remember(windowDays) { app.database.strengthSetDao().observeRange(fromEpoch, toEpoch) }.collectAsState(initial = emptyList())
    val workouts by remember(windowDays) { app.workoutRepository.observeRange(from, today) }.collectAsState(initial = emptyList())

    // Weekly kilometres per sport, from both sources with the duplicates dropped. Computed
    // once for all three sports: the de-duplication has to see every session, not one
    // sport's worth, or a ride would be matched against a run.
    val sportWeeks = remember(activities, workouts, windowDays) {
        val merged = SportDistanceAnalytics.sessions(activities, workouts)
        DistanceSport.entries
            .map { it to SportDistanceAnalytics.weeks(it, merged, from, today) }
            .filter { (_, weeks) -> weeks.any { it.meters > 0 } }
    }
    // Sports with real distance that matched no bucket. Named on screen rather than
    // silently dropped: an unrecognised Garmin sport key looks exactly like a sport that
    // was never synced, and that ambiguity is what cost months of missing rides once.
    val unclassifiedSports = remember(activities) { SportDistanceAnalytics.unclassified(activities) }

    // Every recorded session of the window, from both sources, de-duplicated once - the
    // same merge the distance charts do, but keeping all sports rather than three.
    val intensitySessions = remember(activities, workouts) {
        IntensityAnalytics.sessions(activities, workouts)
    }
    // The user's set maximum wins; otherwise the 95th percentile of their own recorded
    // maxima, which is a floor rather than an estimate - hence the wording on the card.
    val hrMaxOverride by app.heartRateZones.hrMax.collectAsState()
    val suggestedHrMax = remember(intensitySessions) { IntensityAnalytics.suggestHrMax(intensitySessions) }
    val hrMax = hrMaxOverride.takeIf { it > 0 } ?: suggestedHrMax
    val intensity = remember(intensitySessions, hrMax) {
        hrMax?.let { IntensityAnalytics.breakdown(intensitySessions, it) }
    }

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
            // Seven labels no longer fit one line on a phone - they wrap rather than
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
            // All three of these have their own sources - Health Connect workouts and an
            // imported gym log - so no Garmin data is no reason to hide any of them.
            intensitySection(intensity, hrMaxOverride > 0, suggestedHrMax, intensitySessions.size, palette)
            sportDistanceSection(sportWeeks, unclassifiedSports, fromEpoch, toEpoch, smoothWindow, palette.distance)
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

        // Total and active on one chart: same unit, same metric seen two ways, and the
        // gap between the lines is the resting burn. Solid is the total because that is
        // what "калории за день" means; dashed is the part training moved.
        val totalCalories = LifestyleAnalytics.caloriesTrend(summaries, healthDays, active = false)
        val activeCalories = LifestyleAnalytics.caloriesTrend(summaries, healthDays, active = true)
        if (totalCalories.isNotEmpty() || activeCalories.isNotEmpty()) {
            trendCard(
                "Калории за день",
                totalCalories.ifEmpty { activeCalories },
                fromEpoch, toEpoch, palette.calories, smoothWindow,
                floorAtZero = true,
                secondary = if (totalCalories.isEmpty()) emptyList() else activeCalories,
                footnote = if (totalCalories.isNotEmpty() && activeCalories.isNotEmpty()) {
                    "Сплошная линия — всего за день, пунктир — активные. Расстояние между ними — расход в покое."
                } else null
            ) { formatCount(it.toLong()) }
        }
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

        intensitySection(intensity, hrMaxOverride > 0, suggestedHrMax, intensitySessions.size, palette)
        sportDistanceSection(sportWeeks, unclassifiedSports, fromEpoch, toEpoch, smoothWindow, palette.distance)
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
    secondary: List<TrendPoint> = emptyList(),
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
                secondary = secondary,
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
 * Where the training time of the period actually sat, by heart-rate zone.
 *
 * The one question the rest of this screen could not answer. Volume is visible on every
 * chart above; intensity was visible nowhere, so a period whose hours were cut on purpose
 * and whose intensity was cut by accident looked exactly like a well-managed one.
 *
 * Three things the card has to say out loud, because each of them would otherwise be read
 * as more than it is:
 *
 * 1. **Which HRmax the bands came from** - the user's own number, or a floor estimated
 *    from what the watch happened to record. Every boundary moves with it.
 * 2. **That a session is charged whole to the zone of its average.** The app has no
 *    per-minute heart rate (see [IntensityAnalytics]), so this narrows the distribution
 *    towards the middle. Printing percentages without that is printing a measurement.
 * 3. **What carried no heart rate at all.** Swimming usually does not, and a swim block
 *    quietly missing from "where my training time went" is the same failure mode as an
 *    unrecognised sport key - which cost this project months of missing rides once.
 *
 * Independent of the Garmin login, like the two sections below it: Health Connect
 * workouts carry an average heart rate too.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.intensitySection(
    breakdown: IntensityBreakdown?,
    hrMaxIsManual: Boolean,
    suggestedHrMax: Int?,
    sessionCount: Int,
    palette: MetricPalette
) {
    if (sessionCount == 0) return

    item { SectionHeader("Пульсовые зоны") }

    // No HRmax at all: too few sessions carry a usable maximum to estimate one, and
    // guessing from age isn't possible - the app never asks for a birth year. Say what is
    // missing and where to fix it rather than drawing bands off an invented number.
    if (breakdown == null) {
        item(key = "intensity-no-hrmax") {
            InfoCard(title = "Не задан максимальный пульс") {
                Text(
                    text = "Чтобы разложить тренировки по зонам, нужен ваш максимальный пульс — " +
                        "все пять границ считаются от него. Оценить его по вашим записям пока не " +
                        "получается: для этого нужно хотя бы " +
                        "${IntensityAnalytics.MIN_SESSIONS_FOR_SUGGESTION} тренировок с пульсом " +
                        "(за этот период их меньше). Задайте его вручную во вкладке «Я» → " +
                        "«Пульсовые зоны».",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    item {
        Text(
            text = "Время тренировок по зонам от максимального пульса ${breakdown.hrMax} уд/мин " +
                (if (hrMaxIsManual) "(задан вами)." else "(оценка по вашим тренировкам — см. ниже).") +
                " Зона тренировки определяется по её СРЕДНЕМУ пульсу, и вся её длительность " +
                "идёт в эту одну зону: поминутный пульс приложение не хранит. Поэтому " +
                "распределение уже реального — края занижены, середина завышена. " +
                "Вопрос, на который оно отвечает честно: в какой зоне проходит типичная тренировка.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    item(key = "intensity-zones") {
        InfoCard(title = "Распределение времени") {
            breakdown.zones.forEach { share ->
                ZoneRow(
                    share = share,
                    percent = breakdown.sharePercent(share),
                    accent = palette.zoneColor(share.zone.number)
                )
            }
            StatRow(
                "Всего с пульсом",
                "${formatDuration(breakdown.totalMinutes)} · ${breakdown.totalSessions} трен."
            )

            // The sessions that could not be placed, named rather than dropped: a wrist
            // that reads nothing underwater would otherwise silently shrink the swimming
            // half of the training week and leave the percentages looking complete.
            if (breakdown.sessionsWithoutHeartRate > 0) {
                Text(
                    text = "Без пульса: ${breakdown.sessionsWithoutHeartRate} трен., " +
                        "${formatDuration(breakdown.minutesWithoutHeartRate)} — в распределение " +
                        "не вошли (" +
                        breakdown.sportsWithoutHeartRate.entries.take(5)
                            .joinToString(", ") { "${garminSportName(it.key)} — ${it.value}" } +
                        ").",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            if (!hrMaxIsManual && suggestedHrMax != null) {
                Text(
                    text = "Максимальный пульс не задан, поэтому взят 95-й процентиль ваших " +
                        "собственных максимумов за период — $suggestedHrMax уд/мин. Это нижняя " +
                        "граница, а не оценка: видно только те усилия, которые действительно " +
                        "были, и после лёгкого сезона число выходит заниженным, а все зоны " +
                        "вместе с ним — завышенными. Свой настоящий максимум можно задать во " +
                        "вкладке «Я» → «Пульсовые зоны».",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

/**
 * One zone as a labelled row: name, its own bpm band, how long and what share of the
 * period sat there, and a bar whose LENGTH carries the value.
 *
 * Deliberately not a stacked bar. Five colours cannot hold both contrast against the
 * surface and separation from each other (see MetricPalette) - the same wall the sleep
 * stages hit - so the colour only reinforces an ordering the text already states.
 */
@Composable
private fun ZoneRow(share: ZoneShare, percent: Int, accent: Color) {
    val range = share.upperBpmExclusive
        ?.let { "${share.lowerBpm}\u2013${it - 1}" }
        ?: "${share.lowerBpm} и выше"
    Column(modifier = Modifier.padding(vertical = 5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Z${share.zone.number} · ${share.zone.title}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (share.minutes > 0) "${formatDuration(share.minutes)} · $percent%" else "-",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "$range уд/мин",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (share.sessions > 0) {
                Text(
                    text = "${share.sessions} трен.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(6.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    RoundedCornerShape(3.dp)
                )
        ) {
            // A zone nothing landed in keeps its empty track: the missing bar IS the
            // finding on a period with nothing above the aerobic band.
            if (percent > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(percent / 100f)
                        .fillMaxHeight()
                        .background(accent, RoundedCornerShape(3.dp))
                )
            }
        }
    }
}

/** The five zone colours by number, so the ramp is indexed in one place only. */
private fun MetricPalette.zoneColor(number: Int): Color = when (number) {
    1 -> zone1
    2 -> zone2
    3 -> zone3
    4 -> zone4
    else -> zone5
}

/**
 * Weekly kilometres for running, cycling and swimming - the volume question the daily
 * charts above cannot answer.
 *
 * Weekly rather than daily on purpose: someone who runs three times a week has a daily
 * distance line that is mostly gaps, and "am I riding more than I was in spring" is not
 * readable from it. One point per week, anchored on its Monday - the same week the
 * "Неделя" tab uses.
 *
 * **A week with no session shows as 0, not as a break in the line** - the only chart here
 * where that is right. Elsewhere a missing day means the watch wasn't measuring; here the
 * absence is the measurement, and a gap would let a line sail through a month off the
 * bike as though nothing had changed. Zeros are only drawn across the stretch that
 * actually holds sessions, so history that was never synced doesn't masquerade as weeks
 * of nothing (see [SportDistanceAnalytics.weeks]).
 *
 * Independent of the Garmin login: Health Connect workouts carry distance too, and a
 * session known to both sources is counted once.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.sportDistanceSection(
    sportWeeks: List<Pair<DistanceSport, List<SportWeek>>>,
    unclassified: Map<String, Int>,
    fromEpoch: Long,
    toEpoch: Long,
    smoothWindowDays: Int,
    accent: Color
) {
    if (sportWeeks.isEmpty()) return

    item { SectionHeader("Объём по неделям") }
    item {
        Text(
            text = "Сумма расстояния за календарную неделю (пн–вс), точка — на понедельник этой недели. " +
                "Бассейн и открытая вода — одно плавание; дорожка, трейл и улица — один бег. " +
                "Неделя без тренировки — это ноль, а не пропуск: именно так видно, когда вид спорта " +
                "выпадал. Последняя неделя ещё идёт, поэтому она обычно ниже остальных.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // Everything with distance that isn't one of the three, named outright. Without this a
    // sport key the matcher doesn't know is indistinguishable from a sport that never synced.
    if (unclassified.isNotEmpty()) {
        item(key = "sport-unclassified") {
            Text(
                text = "Не вошли в эти три вида (есть расстояние, но другой спорт): " +
                    unclassified.entries.joinToString(", ") { "${garminSportName(it.key)} — ${it.value}" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    sportWeeks.forEach { (sport, weeks) ->
        item(key = "sport-${sport.key}") {
            InfoCard(title = "${sport.emoji} ${sport.title}") {
                val active = weeks.filter { it.meters > 0 }
                StatRow("Всего за период", formatDistance(weeks.sumOf { it.meters }))
                StatRow("В среднем за неделю", formatDistance((SportDistanceAnalytics.averageKmPerWeek(weeks) * 1000).toInt()))
                weeks.maxByOrNull { it.meters }?.takeIf { it.meters > 0 }?.let {
                    StatRow("Лучшая неделя", "${formatDistance(it.meters)} · ${formatDayMonth(LocalDate.ofEpochDay(it.weekStartEpochDay))}")
                }
                StatRow("Тренировок", weeks.sumOf { it.sessions }.toString())
                // Weeks that held a session against weeks in the window: "8 из 12" says
                // more about consistency than any average over the same period does.
                StatRow("Недель с тренировкой", "${active.size} из ${weeks.size}")
                TrendChart(
                    points = SportDistanceAnalytics.trend(weeks),
                    fromEpochDay = fromEpoch,
                    toEpochDay = toEpoch,
                    accent = accent,
                    formatValue = { "${formatDecimal(it)} км" },
                    floorAtZero = true,
                    smoothWindowDays = smoothWindowDays,
                    modifier = Modifier.padding(top = 8.dp)
                )
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
