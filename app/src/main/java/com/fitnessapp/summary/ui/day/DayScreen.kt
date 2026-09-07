package com.fitnessapp.summary.ui.day

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
import com.fitnessapp.summary.data.DailySummary
import com.fitnessapp.summary.data.GarminBodyComposition
import com.fitnessapp.summary.data.GarminDailyExtra
import com.fitnessapp.summary.data.GarminHrv
import com.fitnessapp.summary.data.GarminReadiness
import com.fitnessapp.summary.data.GarminSleep
import com.fitnessapp.summary.data.GarminTraining
import com.fitnessapp.summary.ui.components.EmptyState
import com.fitnessapp.summary.ui.components.GarminActivityRow
import com.fitnessapp.summary.ui.components.InfoCard
import com.fitnessapp.summary.ui.components.MetricCard
import com.fitnessapp.summary.ui.components.ProgressBar
import com.fitnessapp.summary.ui.components.SectionHeader
import com.fitnessapp.summary.ui.components.StageBars
import com.fitnessapp.summary.ui.components.StageDatum
import com.fitnessapp.summary.ui.components.StatRow
import com.fitnessapp.summary.ui.components.WorkoutRow
import com.fitnessapp.summary.ui.components.matchGarminActivity
import com.fitnessapp.summary.ui.components.unmatchedGarminActivities
import com.fitnessapp.summary.ui.theme.MetricPalette
import com.fitnessapp.summary.ui.theme.metricPalette
import com.fitnessapp.summary.util.acwrStatusLabel
import com.fitnessapp.summary.util.bodyBatteryLabel
import com.fitnessapp.summary.util.declineWorkouts
import com.fitnessapp.summary.util.formatCount
import com.fitnessapp.summary.util.formatDayHeader
import com.fitnessapp.summary.util.formatDayMonth
import com.fitnessapp.summary.util.formatDecimal
import com.fitnessapp.summary.util.formatDistance
import com.fitnessapp.summary.util.formatDuration
import com.fitnessapp.summary.util.formatHeartRate
import com.fitnessapp.summary.util.formatHours
import com.fitnessapp.summary.util.formatKg
import com.fitnessapp.summary.util.formatSleepDuration
import com.fitnessapp.summary.util.formatWallClockUtc
import com.fitnessapp.summary.util.hrvStatusLabel
import com.fitnessapp.summary.util.qualifierLabel
import com.fitnessapp.summary.util.readinessLevelLabel
import com.fitnessapp.summary.util.stressLevelLabel
import com.fitnessapp.summary.util.stressQualifierLabel
import com.fitnessapp.summary.util.trainingStatusLabel
import java.time.LocalDate

/**
 * One day at a time, with arrows to step through history.
 *
 * Forward navigation stops at today: there is no such thing as tomorrow's activity,
 * and letting the user page into empty future days makes the app feel broken.
 *
 * Two sources feed this screen. Health Connect ([DailySummary], workouts) is what every
 * install has. The Garmin tables (garmin/) exist only for a user who logged into Garmin
 * directly, and carry everything Health Connect never will - Garmin's own scores. Where
 * they overlap, Health Connect is shown when it has the day and the Garmin row fills in
 * when it doesn't (sleep and resting HR in particular never reached Health Connect on
 * the device this was built against). Every Garmin card is gated on its row existing,
 * so an install without the login sees exactly the screen it always did.
 */
@Composable
fun DayScreen(app: FitnessSummaryApp) {
    val today = remember { LocalDate.now() }
    var selectedDate by remember { mutableStateOf(today) }
    val epochDay = selectedDate.toEpochDay()

    // Keyed on the date so switching days resubscribes to the right row instead of
    // holding the Flow built for the day the screen first opened with.
    val summary by remember(selectedDate) { app.summaryRepository.observeDay(selectedDate) }.collectAsState(initial = null)
    val workouts by remember(selectedDate) { app.workoutRepository.observeForDay(selectedDate) }.collectAsState(initial = emptyList())

    val garmin by remember(selectedDate) { app.database.garminDailyExtraDao().observeDay(epochDay) }.collectAsState(initial = null)
    val garminSleep by remember(selectedDate) { app.database.garminSleepDao().observeDay(epochDay) }.collectAsState(initial = null)
    val hrv by remember(selectedDate) { app.database.garminHrvDao().observeDay(epochDay) }.collectAsState(initial = null)
    val readiness by remember(selectedDate) { app.database.garminReadinessDao().observeDay(epochDay) }.collectAsState(initial = null)
    val training by remember(selectedDate) { app.database.garminTrainingDao().observeDay(epochDay) }.collectAsState(initial = null)
    val bodyComp by remember(selectedDate) { app.database.garminBodyCompositionDao().observeLatestUpTo(epochDay) }.collectAsState(initial = null)
    val garminActivities by remember(selectedDate) { app.database.garminActivityDao().observeForDay(epochDay) }.collectAsState(initial = emptyList())

    val palette = metricPalette()

    // The day as shown: Health Connect when it has anything, otherwise the same numbers
    // from Garmin's own summary - so a day that never made it to Health Connect still has
    // its steps and calories on screen instead of an empty state.
    val healthDay = summary?.takeUnless { it.isEmpty }
    val displayDay = healthDay ?: garmin?.takeUnless { it.isEmpty }?.let { dailyFromGarmin(it, garminSleep) }
    val extra = garmin?.takeUnless { it.isEmpty }
    val sleep = garminSleep?.takeUnless { it.isEmpty }

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

        if (displayDay == null && sleep == null && readiness == null) {
            item {
                EmptyState(
                    emoji = "⌚",
                    title = "Нет данных за этот день",
                    message = when {
                        app.garminAuth.isLoggedIn -> "Часы за этот день ничего не записали, либо Garmin Connect ещё не синхронизировался."
                        app.healthSync.hasEverSynced -> "Часы за этот день ничего не записали, либо синхронизация Garmin Connect ещё не дошла до Health Connect."
                        else -> "Откройте вкладку «Я» и разрешите чтение Health Connect, чтобы начать."
                    }
                )
            }
        } else {
            // Garmin's morning report comes first when there is one: it's the summary of
            // the summaries, the four numbers Garmin itself leads with.
            if (readiness != null || sleep?.score ?: 0 > 0 || extra?.hasBodyBattery == true || hrv != null) {
                item { MorningReport(readiness, sleep, extra, hrv, palette) }
            }
            displayDay?.let { day ->
                item { DayMetrics(day, extra, palette) }
            }
            item { SleepCard(displayDay, sleep, palette) }
            extra?.let { if (it.hasStress || it.hasBodyBattery) item { StressBatteryCard(it, palette) } }
            hrv?.takeUnless { it.isEmpty }?.let { item { HrvCard(it) } }
            readiness?.takeUnless { it.isEmpty }?.let { item { ReadinessCard(it, palette) } }
            training?.takeUnless { it.isEmpty }?.let { item { TrainingCard(it) } }
            displayDay?.let { day -> item { HeartCard(day, extra) } }
            extra?.let { item { ActivityDetailsCard(it, palette) } }
            bodyComp?.takeUnless { it.isEmpty }?.let { item { BodyCompositionCard(it, selectedDate) } }
        }

        val unmatched = unmatchedGarminActivities(workouts, garminActivities)
        val total = workouts.size + unmatched.size
        if (total > 0) {
            item { SectionHeader("$total ${declineWorkouts(total)}") }
            items(workouts, key = { it.recordId }) { workout ->
                WorkoutRow(workout = workout, garmin = matchGarminActivity(workout, garminActivities))
            }
            items(unmatched, key = { "garmin_${it.activityId}" }) { activity ->
                GarminActivityRow(activity = activity)
            }
        }
    }
}

/** Health-Connect-shaped view of a Garmin day, for the tiles when Health Connect has nothing. */
private fun dailyFromGarmin(g: GarminDailyExtra, sleep: GarminSleep?): DailySummary = DailySummary(
    dateEpochDay = g.dateEpochDay,
    steps = g.totalSteps,
    activeCaloriesKcal = g.activeKilocalories,
    totalCaloriesKcal = g.totalKilocalories,
    distanceMeters = g.totalDistanceMeters,
    restingHeartRate = g.restingHeartRate,
    minHeartRate = g.minHeartRate,
    maxHeartRate = g.maxHeartRate,
    sleepTotalMinutes = sleep?.sleepMinutes ?: 0,
    sleepDeepMinutes = sleep?.deepMinutes ?: 0,
    sleepLightMinutes = sleep?.lightMinutes ?: 0,
    sleepRemMinutes = sleep?.remMinutes ?: 0,
    sleepAwakeMinutes = sleep?.awakeMinutes ?: 0
)

@Composable
private fun MorningReport(
    readiness: GarminReadiness?,
    sleep: GarminSleep?,
    extra: GarminDailyExtra?,
    hrv: GarminHrv?,
    palette: MetricPalette
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                emoji = "🟢",
                label = "Готовность",
                value = readiness?.score?.takeIf { it > 0 }?.toString() ?: "-",
                accent = palette.readiness,
                modifier = Modifier.weight(1f),
                caption = readiness?.let { readinessLevelLabel(it.level).ifBlank { null } }
            )
            MetricCard(
                emoji = "🌙",
                label = "Sleep Score",
                value = sleep?.score?.takeIf { it > 0 }?.toString() ?: "-",
                accent = palette.sleep,
                modifier = Modifier.weight(1f),
                caption = sleep?.let { qualifierLabel(it.scoreQualifier).ifBlank { null } }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                emoji = "🔋",
                label = "Body Battery утром",
                value = extra?.bodyBatteryAtWake?.takeIf { it > 0 }?.toString() ?: "-",
                accent = palette.bodyBattery,
                modifier = Modifier.weight(1f),
                caption = extra?.let { bodyBatteryLabel(it.bodyBatteryAtWake).ifBlank { null } }
            )
            MetricCard(
                emoji = "💓",
                label = "ВСР за ночь",
                value = hrv?.lastNightAvg?.takeIf { it > 0 }?.let { "$it мс" } ?: "-",
                accent = palette.hrv,
                modifier = Modifier.weight(1f),
                caption = hrv?.let { hrvStatusLabel(it.status) }
            )
        }
    }
}

@Composable
private fun DayMetrics(day: DailySummary, extra: GarminDailyExtra?, palette: MetricPalette) {
    val resting = if (day.restingHeartRate > 0) day.restingHeartRate else extra?.restingHeartRate ?: 0
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                emoji = "👟",
                label = "Шаги",
                value = if (day.steps > 0) formatCount(day.steps) else "-",
                accent = palette.steps,
                modifier = Modifier.weight(1f),
                caption = extra?.floorsAscended?.takeIf { it > 0 }?.let { "этажей вверх: $it" }
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
                caption = if (day.totalCaloriesKcal > 0) "всего ${formatCount(day.totalCaloriesKcal)} ккал" else null
            )
            MetricCard(
                emoji = "❤️",
                label = "Пульс покоя",
                value = formatHeartRate(resting),
                accent = palette.heart,
                modifier = Modifier.weight(1f),
                caption = when {
                    extra != null && extra.lastSevenDaysAvgRestingHeartRate > 0 -> "7 дней: ${extra.lastSevenDaysAvgRestingHeartRate}"
                    resting > 0 -> "уд/мин"
                    else -> null
                }
            )
        }
    }
}

/**
 * Garmin's night when there is one (score, need, overnight physiology), the Health
 * Connect session otherwise. The stage rows are the same either way.
 */
@Composable
private fun SleepCard(day: DailySummary?, sleep: GarminSleep?, palette: MetricPalette) {
    val totalMinutes = sleep?.sleepMinutes ?: day?.sleepTotalMinutes ?: 0
    InfoCard(title = "Сон") {
        if (totalMinutes <= 0) {
            Text(
                text = "Нет записи сна за эту ночь.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@InfoCard
        }

        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = formatSleepDuration(totalMinutes),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (sleep != null && sleep.score > 0) {
                Text(
                    text = "Sleep Score ${sleep.score} · ${qualifierLabel(sleep.scoreQualifier)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
        if (sleep != null && sleep.sleepStartLocalMillis > 0 && sleep.sleepEndLocalMillis > 0) {
            Text(
                text = "${formatWallClockUtc(sleep.sleepStartLocalMillis)} - ${formatWallClockUtc(sleep.sleepEndLocalMillis)}" +
                    (if (sleep.napMinutes > 0) " · дрёма ${formatDuration(sleep.napMinutes)}" else ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Only render the stages a source actually provided. Garmin fills all of
        // them, but a session written without a breakdown would otherwise show four
        // convincing-looking zero rows.
        val deep = sleep?.deepMinutes ?: day?.sleepDeepMinutes ?: 0
        val rem = sleep?.remMinutes ?: day?.sleepRemMinutes ?: 0
        val light = sleep?.lightMinutes ?: day?.sleepLightMinutes ?: 0
        val awake = sleep?.awakeMinutes ?: day?.sleepAwakeMinutes ?: 0
        val stages = buildList {
            if (deep > 0) add(StageDatum("Глубокий", deep, palette.sleepDeep))
            if (rem > 0) add(StageDatum("Быстрый", rem, palette.sleepRem))
            if (light > 0) add(StageDatum("Лёгкий", light, palette.sleepLight))
            if (awake > 0) add(StageDatum("Бодрствование", awake, palette.sleepAwake))
        }
        if (stages.isNotEmpty()) {
            StageBars(
                stages = stages,
                formatValue = { formatDuration(it) },
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (sleep != null) {
            Column(modifier = Modifier.padding(top = 6.dp)) {
                if (sleep.needActualMinutes > 0) {
                    val deficit = sleep.needDeficitMinutes
                    StatRow(
                        "Потребность во сне",
                        formatSleepDuration(sleep.needActualMinutes) + when {
                            deficit > 15 -> " (не хватило ${formatDuration(deficit)})"
                            deficit < -15 -> " (с запасом ${formatDuration(-deficit)})"
                            else -> " (в норме)"
                        }
                    )
                }
                if (sleep.awakeCount > 0) StatRow("Пробуждений", "${sleep.awakeCount} · ${qualifierLabel(sleep.awakeCountQualifier).lowercase()}".trimEnd(' ', '·'))
                if (sleep.restlessnessQualifier.isNotBlank()) StatRow("Беспокойность", qualifierLabel(sleep.restlessnessQualifier))
                if (sleep.restingHeartRate > 0) StatRow("Пульс покоя за ночь", "${sleep.restingHeartRate} уд/мин")
                if (sleep.avgSleepStress > 0f) StatRow("Стресс во сне", "${Math.round(sleep.avgSleepStress)} · ${qualifierLabel(sleep.stressQualifier).lowercase()}".trimEnd(' ', '·'))
                if (sleep.bodyBatteryChange != 0) {
                    StatRow("Body Battery за ночь", (if (sleep.bodyBatteryChange > 0) "+" else "") + sleep.bodyBatteryChange.toString())
                }
                if (sleep.avgSpo2 > 0f) {
                    StatRow("SpO2 средний", "${formatDecimal(sleep.avgSpo2)}%" + if (sleep.lowestSpo2 > 0) " (мин ${sleep.lowestSpo2}%)" else "")
                }
                if (sleep.avgRespiration > 0f) StatRow("Дыхание", "${formatDecimal(sleep.avgRespiration)} вд/мин")
                if (sleep.hasSkinTemp) {
                    val sign = if (sleep.skinTempDeviationC > 0f) "+" else ""
                    StatRow("Температура кожи", "$sign${formatDecimal(sleep.skinTempDeviationC)} °C к норме")
                }
                val grades = buildList {
                    if (sleep.durationQualifier.isNotBlank()) add("длительность — ${qualifierLabel(sleep.durationQualifier).lowercase()}")
                    if (sleep.deepQualifier.isNotBlank()) add("глубокий — ${qualifierLabel(sleep.deepQualifier).lowercase()}")
                    if (sleep.remQualifier.isNotBlank()) add("быстрый — ${qualifierLabel(sleep.remQualifier).lowercase()}")
                    if (sleep.lightQualifier.isNotBlank()) add("лёгкий — ${qualifierLabel(sleep.lightQualifier).lowercase()}")
                }
                if (grades.isNotEmpty()) {
                    Text(
                        text = "Оценки Garmin: ${grades.joinToString(", ")}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StressBatteryCard(extra: GarminDailyExtra, palette: MetricPalette) {
    InfoCard(title = "Body Battery и стресс") {
        if (extra.hasBodyBattery) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                BatteryValue("При пробуждении", extra.bodyBatteryAtWake, Modifier.weight(1f))
                BatteryValue("Максимум", extra.bodyBatteryHighest, Modifier.weight(1f))
                BatteryValue("Минимум", extra.bodyBatteryLowest, Modifier.weight(1f))
            }
        }
        if (extra.hasStress) {
            StatRow(
                "Стресс, средний",
                "${extra.averageStressLevel} · ${stressLevelLabel(extra.averageStressLevel)}" +
                    (stressQualifierLabel(extra.stressQualifier).takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            )
            if (extra.maxStressLevel > 0) StatRow("Стресс, максимум", extra.maxStressLevel.toString())
        }
        if (extra.hasStressZones) {
            val zones = buildList {
                if (extra.restStressSeconds > 0) add(StageDatum("Покой", extra.restStressSeconds / 60, palette.stressRest))
                if (extra.lowStressSeconds > 0) add(StageDatum("Низкий", extra.lowStressSeconds / 60, palette.stressLow))
                if (extra.mediumStressSeconds > 0) add(StageDatum("Средний", extra.mediumStressSeconds / 60, palette.stressMedium))
                if (extra.highStressSeconds > 0) add(StageDatum("Высокий", extra.highStressSeconds / 60, palette.stressHigh))
            }
            StageBars(stages = zones, formatValue = { formatDuration(it) }, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun BatteryValue(label: String, value: Int, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = if (value > 0) value.toString() else "-",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        val words = bodyBatteryLabel(value)
        if (words.isNotBlank()) Text(words, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HrvCard(hrv: GarminHrv) {
    InfoCard(title = "Вариабельность пульса (ВСР)") {
        if (hrv.lastNightAvg > 0) StatRow("За ночь, среднее", "${hrv.lastNightAvg} мс")
        if (hrv.lastNight5MinHigh > 0) StatRow("Лучшие 5 минут", "${hrv.lastNight5MinHigh} мс")
        if (hrv.weeklyAvg > 0) StatRow("Среднее за 7 ночей", "${hrv.weeklyAvg} мс")
        if (hrv.hasBaseline) StatRow("Ваша норма", "${hrv.baselineBalancedLow}–${hrv.baselineBalancedUpper} мс")
        StatRow("Статус", hrvStatusLabel(hrv.status))
        Text(
            text = "Garmin сравнивает среднее за 7 ночей с вашей базовой линией, которая формируется около трёх недель. " +
                "Одна низкая ночь — не повод для выводов; несколько подряд — сигнал недовосстановления.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun ReadinessCard(readiness: GarminReadiness, palette: MetricPalette) {
    InfoCard(title = "Готовность к тренировке") {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = readiness.score.toString(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = readinessLevelLabel(readiness.level),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        if (readiness.recoveryTimeHours > 0f) {
            StatRow("До восстановления", formatHours(readiness.recoveryTimeHours))
        }

        // The six factors Garmin builds the score from, each as a 0-100 contribution.
        // Drawn as labelled rows (StageBars) so the words carry the meaning and the bar
        // just shows relative weight.
        val factors = buildList {
            if (readiness.sleepScoreFactorPercent > 0) add(StageDatum("Сон", readiness.sleepScoreFactorPercent, palette.sleep))
            if (readiness.recoveryTimeFactorPercent > 0) add(StageDatum("Восстановл.", readiness.recoveryTimeFactorPercent, palette.readiness))
            if (readiness.hrvFactorPercent > 0) add(StageDatum("ВСР", readiness.hrvFactorPercent, palette.hrv))
            if (readiness.acwrFactorPercent > 0) add(StageDatum("Нагрузка", readiness.acwrFactorPercent, palette.training))
            if (readiness.sleepHistoryFactorPercent > 0) add(StageDatum("История сна", readiness.sleepHistoryFactorPercent, palette.sleepLight))
            if (readiness.stressHistoryFactorPercent > 0) add(StageDatum("Стресс", readiness.stressHistoryFactorPercent, palette.stress))
        }
        if (factors.isNotEmpty()) {
            Text(
                text = "Из чего складывается",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            StageBars(stages = factors, formatValue = { "$it%" })
        }
        val notes = buildList {
            qualifierLabel(readiness.sleepScoreFactorFeedback).takeIf { it.isNotBlank() }?.let { add("сон — ${it.lowercase()}") }
            qualifierLabel(readiness.hrvFactorFeedback).takeIf { it.isNotBlank() }?.let { add("ВСР — ${it.lowercase()}") }
            qualifierLabel(readiness.recoveryTimeFactorFeedback).takeIf { it.isNotBlank() }?.let { add("восстановление — ${it.lowercase()}") }
            qualifierLabel(readiness.acwrFactorFeedback).takeIf { it.isNotBlank() }?.let { add("нагрузка — ${it.lowercase()}") }
        }
        if (notes.isNotEmpty()) {
            Text(
                text = "Оценки Garmin: ${notes.joinToString(", ")}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun TrainingCard(training: GarminTraining) {
    InfoCard(title = "Статус тренировок") {
        if (training.hasStatus) {
            StatRow("Статус", trainingStatusLabel(training.statusFeedbackPhrase).ifBlank { "Статус ${training.trainingStatus}" } +
                if (training.trainingPaused) " (пауза)" else "")
        }
        if (training.vo2Max > 0f) StatRow("VO2max", formatDecimal(if (training.vo2MaxPrecise > 0f) training.vo2MaxPrecise else training.vo2Max))
        if (training.weeklyTrainingLoad > 0) {
            StatRow(
                "Нагрузка за 7 дней",
                training.weeklyTrainingLoad.toString() +
                    if (training.loadTunnelMin > 0 && training.loadTunnelMax > 0) " (оптимум ${training.loadTunnelMin}–${training.loadTunnelMax})" else ""
            )
        }
        if (training.dailyTrainingLoadAcute > 0 || training.dailyTrainingLoadChronic > 0) {
            StatRow("Острая / хроническая", "${training.dailyTrainingLoadAcute} / ${training.dailyTrainingLoadChronic}")
        }
        if (training.acwrStatus.isNotBlank()) {
            StatRow(
                "Баланс нагрузки",
                acwrStatusLabel(training.acwrStatus).replaceFirstChar { it.uppercaseChar() } +
                    if (training.acuteChronicRatio > 0f) " (${"%.2f".format(training.acuteChronicRatio)})" else ""
            )
        }
        if (training.enduranceScore > 0) StatRow("Endurance Score", training.enduranceScore.toString())
        if (training.hillScore > 0) {
            StatRow(
                "Hill Score",
                training.hillScore.toString() +
                    if (training.hillEnduranceScore > 0 || training.hillStrengthScore > 0) " (выносливость ${training.hillEnduranceScore}, сила ${training.hillStrengthScore})" else ""
            )
        }
    }
}

@Composable
private fun HeartCard(day: DailySummary, extra: GarminDailyExtra?) {
    val avg = day.avgHeartRate
    val min = if (day.minHeartRate > 0) day.minHeartRate else extra?.minHeartRate ?: 0
    val max = if (day.maxHeartRate > 0) day.maxHeartRate else extra?.maxHeartRate ?: 0
    if (avg <= 0 && max <= 0) return

    InfoCard(title = "Пульс за день") {
        if (avg > 0) StatRow("Средний", "${formatHeartRate(avg)} уд/мин")
        if (min > 0) StatRow("Минимальный", "${formatHeartRate(min)} уд/мин")
        if (max > 0) StatRow("Максимальный", "${formatHeartRate(max)} уд/мин")
    }
}

/** The Garmin daily-summary fields that have no tile of their own: intensity minutes, time budget, SpO2, respiration, hydration. */
@Composable
private fun ActivityDetailsCard(extra: GarminDailyExtra, palette: MetricPalette) {
    val hasIntensity = extra.moderateIntensityMinutes > 0 || extra.vigorousIntensityMinutes > 0
    val hasTime = extra.activeSeconds > 0 || extra.sedentarySeconds > 0
    val hasOx = extra.averageSpo2 > 0 || extra.avgWakingRespiration > 0
    val hasHydration = extra.hydrationMl > 0 || extra.hydrationGoalMl > 0
    if (!hasIntensity && !hasTime && !hasOx && !hasHydration && extra.floorsAscended == 0) return

    InfoCard(title = "Активность за день") {
        if (hasIntensity) {
            StatRow(
                "Интенсивные минуты",
                "${extra.intensityMinutesWeighted}" +
                    " (умеренных ${extra.moderateIntensityMinutes}, интенсивных ${extra.vigorousIntensityMinutes})"
            )
        }
        if (extra.floorsAscended > 0 || extra.floorsDescended > 0) {
            StatRow("Этажи", "↑${extra.floorsAscended} ↓${extra.floorsDescended}")
        }
        if (hasTime) {
            val zones = buildList {
                if (extra.highlyActiveSeconds > 0) add(StageDatum("Активно", extra.highlyActiveSeconds / 60, palette.calories))
                if (extra.activeSeconds > 0) add(StageDatum("Движение", extra.activeSeconds / 60, palette.steps))
                if (extra.sedentarySeconds > 0) add(StageDatum("Сидя", extra.sedentarySeconds / 60, palette.stressRest))
                if (extra.sleepingSeconds > 0) add(StageDatum("Сон", extra.sleepingSeconds / 60, palette.sleep))
            }
            Text(
                text = "Бюджет времени",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            StageBars(stages = zones, formatValue = { formatDuration(it) })
        }
        if (extra.averageSpo2 > 0) {
            StatRow("SpO2 средний", "${extra.averageSpo2}%" + if (extra.lowestSpo2 > 0) " (мин ${extra.lowestSpo2}%)" else "")
        }
        if (extra.avgWakingRespiration > 0) {
            StatRow(
                "Дыхание днём",
                "${extra.avgWakingRespiration} вд/мин" +
                    if (extra.lowestRespiration > 0 && extra.highestRespiration > 0) " (${extra.lowestRespiration}–${extra.highestRespiration})" else ""
            )
        }
        if (hasHydration) {
            StatRow("Вода", "${extra.hydrationMl} мл" + if (extra.hydrationGoalMl > 0) " из ${extra.hydrationGoalMl}" else "")
            if (extra.hydrationGoalMl > 0) {
                ProgressBar(
                    fraction = extra.hydrationMl.toFloat() / extra.hydrationGoalMl,
                    accent = palette.stressRest,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun BodyCompositionCard(body: GarminBodyComposition, selectedDate: LocalDate) {
    val sameDay = body.dateEpochDay == selectedDate.toEpochDay()
    InfoCard(title = if (sameDay) "Вес и состав тела" else "Вес и состав тела · ${formatDayMonth(LocalDate.ofEpochDay(body.dateEpochDay))}") {
        StatRow("Вес", formatKg(body.weightGrams))
        if (body.bmi > 0f) StatRow("ИМТ", formatDecimal(body.bmi))
        if (body.bodyFatPercent > 0f) StatRow("Жир", "${formatDecimal(body.bodyFatPercent)}%")
        if (body.muscleMassGrams > 0) StatRow("Мышечная масса", formatKg(body.muscleMassGrams))
        if (body.bodyWaterPercent > 0f) StatRow("Вода", "${formatDecimal(body.bodyWaterPercent)}%")
        if (body.boneMassGrams > 0) StatRow("Костная масса", formatKg(body.boneMassGrams))
        if (body.visceralFat > 0f) StatRow("Висцеральный жир", formatDecimal(body.visceralFat))
        if (body.metabolicAge > 0) StatRow("Метаболический возраст", body.metabolicAge.toString())
    }
}
