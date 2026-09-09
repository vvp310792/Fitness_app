package com.fitnessapp.summary.analytics

import com.fitnessapp.summary.data.DailySummary
import com.fitnessapp.summary.data.GarminActivity
import com.fitnessapp.summary.data.GarminBodyComposition
import com.fitnessapp.summary.data.GarminDailyExtra
import com.fitnessapp.summary.data.GarminHrv
import com.fitnessapp.summary.data.GarminReadiness
import com.fitnessapp.summary.data.GarminSleep
import com.fitnessapp.summary.data.GarminTraining
import com.fitnessapp.summary.data.ScaleMeasurement
import com.fitnessapp.summary.util.acwrStatusLabel
import com.fitnessapp.summary.util.declineDays
import com.fitnessapp.summary.util.formatDuration
import com.fitnessapp.summary.util.hrvStatusLabel
import com.fitnessapp.summary.util.weekStart
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class InsightTone { ATTENTION, GOOD, NEUTRAL }

/** One plain-language observation for the Тренды tab. [detail] says what the number is built on. */
data class Insight(
    val emoji: String,
    val title: String,
    val detail: String,
    val tone: InsightTone
)

/** One day's value for a trend chart. Days without data are simply absent. */
data class TrendPoint(val epochDay: Long, val value: Float)

/** Everything the rules below can look at - the last N days of every Garmin table plus the Health Connect days. */
data class LifestyleInputs(
    val today: LocalDate,
    val summaries: List<GarminDailyExtra>,
    val sleeps: List<GarminSleep>,
    val hrvs: List<GarminHrv>,
    val readiness: List<GarminReadiness>,
    val training: List<GarminTraining>,
    val weights: List<GarminBodyComposition>,
    val activities: List<GarminActivity>,
    val healthDays: List<DailySummary>,
    /** Weigh-ins from a non-Garmin scale (scale/); merged with [weights] by day, scale winning. */
    val scaleWeights: List<ScaleMeasurement> = emptyList()
)

/**
 * The Garmin-derived half of a training week, for the Неделя screen. Averages are over
 * the days that actually have the metric - same rule as [com.fitnessapp.summary.data.WeekSummary].
 */
data class GarminWeekSummary(
    val daysWithSummary: Int,
    val nightsWithScore: Int,
    val avgSleepScore: Int,
    val avgSleepNeedDeficitMinutes: Int,
    val avgReadiness: Int,
    val daysWithReadiness: Int,
    val avgStress: Int,
    val avgBodyBatteryAtWake: Int,
    val avgRestingHeartRate: Int,
    val avgHrvLastNight: Int,
    val latestHrvStatus: String,
    val intensityMinutesWeighted: Int,
    val intensityMinutesGoal: Int,
    val totalTrainingLoad: Int,
    val floorsAscended: Int
) {
    val isEmpty: Boolean get() = daysWithSummary == 0 && nightsWithScore == 0 && daysWithReadiness == 0
}

/**
 * Rule-based lifestyle analytics on top of Garmin's own metrics - the layer Garmin
 * Connect, Whoop and Oura all sell as "insights", rebuilt here transparently: every rule
 * is a plain threshold on a number the user can see on the same screen, and every insight
 * says how many days it's based on. No model, no black box - deliberately, so a surprising
 * verdict can always be checked against the chart under it.
 *
 * Thresholds come from Garmin's own published guidance where one exists (stress zones,
 * Body Battery bands, the 0.8-1.3 ACWR window, the 150-minute WHO intensity goal Garmin
 * defaults to) and from conservative, widely used clinical rules of thumb otherwise
 * (resting HR drifting +5 bpm above baseline, SpO2 below 94%, >10 sedentary hours).
 */
object LifestyleAnalytics {

    /** Default weekly Intensity Minutes goal when Garmin hasn't told us the user's own. */
    private const val DEFAULT_INTENSITY_GOAL = 150

    /** Fallback Sleep Need when Garmin hasn't computed one for the night (8 h, Garmin's own default baseline). */
    private const val DEFAULT_SLEEP_NEED_MINUTES = 480

    fun insights(inputs: LifestyleInputs): List<Insight> {
        val out = mutableListOf<Insight>()
        val today = inputs.today
        val weekFrom = today.minusDays(6).toEpochDay()
        val prevWeekFrom = today.minusDays(13).toEpochDay()

        val sleeps7 = inputs.sleeps.filter { it.dateEpochDay >= weekFrom && !it.isEmpty }
        val summaries7 = inputs.summaries.filter { it.dateEpochDay >= weekFrom }
        val summariesPrev = inputs.summaries.filter { it.dateEpochDay in prevWeekFrom until weekFrom }
        val readiness7 = inputs.readiness.filter { it.dateEpochDay >= weekFrom && !it.isEmpty }
        val readinessPrev = inputs.readiness.filter { it.dateEpochDay in prevWeekFrom until weekFrom && !it.isEmpty }

        // --- Sleep debt against Garmin's Sleep Need ------------------------------------
        if (sleeps7.isNotEmpty()) {
            val debt = sleeps7.sumOf { night ->
                val need = when {
                    night.needActualMinutes > 0 -> night.needActualMinutes
                    night.needBaselineMinutes > 0 -> night.needBaselineMinutes
                    else -> DEFAULT_SLEEP_NEED_MINUTES
                }
                need - night.sleepMinutes
            }
            val shortNights = sleeps7.count { it.needDeficitMinutes > 30 || (it.needActualMinutes == 0 && it.sleepMinutes < DEFAULT_SLEEP_NEED_MINUTES - 30) }
            when {
                debt >= 120 -> out += Insight(
                    "😴", "Недосып за неделю: ${formatDuration(debt)}",
                    "Сумма разницы между потребностью во сне (Garmin Sleep Need) и фактическим сном за ${sleeps7.size} ${declineDays(sleeps7.size)}. Короче нормы — $shortNights ${nightsWord(shortNights)}.",
                    InsightTone.ATTENTION
                )
                debt <= 0 -> out += Insight(
                    "😴", "Сон покрывает потребность",
                    "За ${sleeps7.size} ${nightsWord(sleeps7.size)} вы спали в сумме на ${formatDuration(-debt)} больше, чем требовал Garmin Sleep Need.",
                    InsightTone.GOOD
                )
                else -> out += Insight(
                    "😴", "Небольшой недосып: ${formatDuration(debt)}",
                    "Разница между потребностью во сне и фактом за ${sleeps7.size} ${nightsWord(sleeps7.size)} — в пределах одной ранней ночи.",
                    InsightTone.NEUTRAL
                )
            }
        }

        // --- Bedtime consistency ----------------------------------------------------------
        val bedtimes = sleeps7.filter { it.sleepStartLocalMillis > 0 }.map { bedtimeMinutes(it.sleepStartLocalMillis) }
        if (bedtimes.size >= 4) {
            val sd = stdDev(bedtimes.map { it.toDouble() })
            when {
                sd > 60 -> out += Insight(
                    "🕰", "Режим сна плавает: разброс отбоя ±${sd.roundToInt()} мин",
                    "Стандартное отклонение времени засыпания за ${bedtimes.size} ${nightsWord(bedtimes.size)}. Стабильный отбой (±30 мин) — самый дешёвый способ поднять Sleep Score.",
                    InsightTone.ATTENTION
                )
                sd <= 30 -> out += Insight(
                    "🕰", "Стабильный режим сна",
                    "Время засыпания за ${bedtimes.size} ${nightsWord(bedtimes.size)} колебалось всего на ±${sd.roundToInt()} мин.",
                    InsightTone.GOOD
                )
            }
        }

        // --- Sleep score trend ---------------------------------------------------------------
        val scored7 = sleeps7.filter { it.score > 0 }
        val scoredPrev = inputs.sleeps.filter { it.dateEpochDay in prevWeekFrom until weekFrom && it.score > 0 }
        if (scored7.isNotEmpty() && scoredPrev.isNotEmpty()) {
            val now = scored7.map { it.score }.average()
            val before = scoredPrev.map { it.score }.average()
            val delta = (now - before).roundToInt()
            if (abs(delta) >= 5) {
                out += Insight(
                    "🌙", "Sleep Score ${if (delta > 0) "вырос" else "упал"} на $delta",
                    "Средний балл сна ${now.roundToInt()} за ${scored7.size} ${nightsWord(scored7.size)} против ${before.roundToInt()} неделей раньше.",
                    if (delta > 0) InsightTone.GOOD else InsightTone.ATTENTION
                )
            }
        }

        // --- HRV vs personal baseline ---------------------------------------------------------
        val hrv7 = inputs.hrvs.filter { it.dateEpochDay >= weekFrom && !it.isEmpty }
        val latestHrv = inputs.hrvs.lastOrNull { !it.isEmpty }
        if (latestHrv != null) {
            val belowBaseline = hrv7.count { it.hasBaseline && it.lastNightAvg > 0 && it.lastNightAvg < it.baselineBalancedLow }
            when {
                belowBaseline >= 3 -> out += Insight(
                    "💓", "ВСР ниже вашей нормы $belowBaseline ${nightsWord(belowBaseline)} из ${hrv7.size}",
                    "Ночная ВСР опускалась ниже нижней границы вашей базовой линии (${latestHrv.baselineBalancedLow}–${latestHrv.baselineBalancedUpper} мс). Обычно это недовосстановление, стресс или начало болезни.",
                    InsightTone.ATTENTION
                )
                latestHrv.status.uppercase() == "BALANCED" -> out += Insight(
                    "💓", "ВСР сбалансирована",
                    "Средняя за 7 ночей ${latestHrv.weeklyAvg} мс — внутри вашей базовой линии ${latestHrv.baselineBalancedLow}–${latestHrv.baselineBalancedUpper} мс.",
                    InsightTone.GOOD
                )
                latestHrv.status.uppercase() in setOf("UNBALANCED", "LOW", "POOR") -> out += Insight(
                    "💓", "ВСР: ${hrvStatusLabel(latestHrv.status).lowercase()}",
                    "Garmin оценивает статус ВСР по среднему за 7 ночей (${latestHrv.weeklyAvg} мс) относительно вашей базовой линии.",
                    InsightTone.ATTENTION
                )
            }
        }

        // --- Resting heart rate drift -----------------------------------------------------------
        val rhr7 = restingRates(summaries7, inputs.healthDays.filter { it.dateEpochDay >= weekFrom })
        val rhrBaseline = restingRates(
            inputs.summaries.filter { it.dateEpochDay in (weekFrom - 21) until weekFrom },
            inputs.healthDays.filter { it.dateEpochDay in (weekFrom - 21) until weekFrom }
        )
        if (rhr7.size >= 3 && rhrBaseline.size >= 7) {
            val delta = (rhr7.average() - rhrBaseline.average()).roundToInt()
            when {
                delta >= 4 -> out += Insight(
                    "❤️", "Пульс покоя выше обычного на $delta уд/мин",
                    "Среднее ${rhr7.average().roundToInt()} за ${rhr7.size} ${declineDays(rhr7.size)} против ${rhrBaseline.average().roundToInt()} за предыдущие три недели. Так выглядит усталость, недосып или болезнь.",
                    InsightTone.ATTENTION
                )
                delta <= -3 -> out += Insight(
                    "❤️", "Пульс покоя ниже обычного на ${-delta} уд/мин",
                    "Среднее ${rhr7.average().roundToInt()} против ${rhrBaseline.average().roundToInt()} за предыдущие три недели — признак роста формы или хорошего восстановления.",
                    InsightTone.GOOD
                )
            }
        }

        // --- Stress ------------------------------------------------------------------------------
        val stressed7 = summaries7.filter { it.averageStressLevel > 0 }
        if (stressed7.isNotEmpty()) {
            val avg = stressed7.map { it.averageStressLevel }.average().roundToInt()
            val zoneDays = stressed7.filter { it.hasStressZones }
            val tenseHoursPerDay = if (zoneDays.isEmpty()) 0.0 else
                zoneDays.map { (it.mediumStressSeconds + it.highStressSeconds) / 3600.0 }.average()
            val tense = if (tenseHoursPerDay > 0) " В зонах среднего и высокого стресса — ${formatHours(tenseHoursPerDay)} в день." else ""
            when {
                avg > 50 -> out += Insight(
                    "⚡", "Высокий средний стресс: $avg",
                    "Средний дневной стресс Garmin за ${stressed7.size} ${declineDays(stressed7.size)} выше 50 — граница между «низким» и «средним».$tense",
                    InsightTone.ATTENTION
                )
                avg <= 25 -> out += Insight(
                    "⚡", "Низкий стресс: $avg",
                    "Средний дневной стресс за ${stressed7.size} ${declineDays(stressed7.size)} — в зоне покоя (до 25).$tense",
                    InsightTone.GOOD
                )
                else -> out += Insight(
                    "⚡", "Стресс в норме: $avg",
                    "Среднее за ${stressed7.size} ${declineDays(stressed7.size)}. Зона низкого стресса по Garmin — 26–50.$tense",
                    InsightTone.NEUTRAL
                )
            }
        }

        // --- Body Battery at wake -------------------------------------------------------------------
        val wake7 = summaries7.filter { it.bodyBatteryAtWake > 0 }
        if (wake7.size >= 3) {
            val avg = wake7.map { it.bodyBatteryAtWake }.average().roundToInt()
            val lowMornings = wake7.count { it.bodyBatteryAtWake < 50 }
            when {
                lowMornings >= 3 -> out += Insight(
                    "🔋", "Утренняя Body Battery ниже 50 — $lowMornings ${morningsWord(lowMornings)} из ${wake7.size}",
                    "Ночь не восстанавливает заряд полностью (среднее при пробуждении $avg). Garmin связывает это с поздним отбоем, алкоголем, болезнью или высоким вечерним стрессом.",
                    InsightTone.ATTENTION
                )
                avg >= 75 -> out += Insight(
                    "🔋", "Утренняя Body Battery в среднем $avg",
                    "Высокий заряд по утрам за ${wake7.size} ${declineDays(wake7.size)} — сон восстанавливает.",
                    InsightTone.GOOD
                )
            }
        }

        // --- Intensity minutes vs the weekly goal -------------------------------------------------------
        val thisWeekStart = weekStart(today).toEpochDay()
        val thisWeek = inputs.summaries.filter { it.dateEpochDay >= thisWeekStart }
        if (thisWeek.isNotEmpty()) {
            val weighted = thisWeek.sumOf { it.intensityMinutesWeighted }
            val goal = inputs.summaries.lastOrNull { it.intensityMinutesWeeklyGoal > 0 }?.intensityMinutesWeeklyGoal ?: DEFAULT_INTENSITY_GOAL
            val dayOfWeek = (today.toEpochDay() - thisWeekStart + 1).toInt().coerceIn(1, 7)
            val projected = weighted * 7 / dayOfWeek
            val percent = (weighted * 100 / goal).coerceAtMost(999)
            when {
                weighted >= goal -> out += Insight(
                    "🎯", "Интенсивные минуты: $weighted из $goal — цель выполнена",
                    "Умеренные минуты + удвоенные интенсивные, с понедельника. Garmin (и ВОЗ) считают 150 в неделю минимумом для здоровья.",
                    InsightTone.GOOD
                )
                projected >= goal -> out += Insight(
                    "🎯", "Интенсивные минуты: $weighted из $goal ($percent%)",
                    "На текущем темпе неделя закроется примерно на $projected — цель достижима.",
                    InsightTone.NEUTRAL
                )
                else -> out += Insight(
                    "🎯", "Интенсивные минуты: $weighted из $goal ($percent%)",
                    "Текущий темп даёт около $projected к воскресенью. Не хватает ${goal - weighted}: это, например, ${(goal - weighted + 1) / 2} мин быстрой ходьбы в день до конца недели.",
                    if (dayOfWeek >= 5) InsightTone.ATTENTION else InsightTone.NEUTRAL
                )
            }
        }

        // --- Training load balance ------------------------------------------------------------------------
        val latestTraining = inputs.training.lastOrNull { it.hasLoad || it.hasStatus }
        if (latestTraining != null && latestTraining.acwrStatus.isNotBlank()) {
            val ratio = if (latestTraining.acuteChronicRatio > 0f) " (соотношение ${"%.2f".format(latestTraining.acuteChronicRatio)})" else ""
            when (latestTraining.acwrStatus.uppercase()) {
                "OPTIMAL" -> out += Insight(
                    "🏋", "Нагрузка сбалансирована$ratio",
                    "Острая нагрузка (7 дней) к хронической (4 недели) — в оптимальном окне Garmin 0,8–1,3. Так форма растёт без перегруза.",
                    InsightTone.GOOD
                )
                "HIGH", "VERY_HIGH" -> out += Insight(
                    "🏋", "Нагрузка ${acwrStatusLabel(latestTraining.acwrStatus)}$ratio",
                    "Последние 7 дней тяжелее вашей 4-недельной нормы. Garmin: это зона повышенного риска травм и перетренированности — стоит запланировать лёгкие дни.",
                    InsightTone.ATTENTION
                )
                "LOW", "VERY_LOW" -> out += Insight(
                    "🏋", "Нагрузка ${acwrStatusLabel(latestTraining.acwrStatus)}$ratio",
                    "Последние 7 дней заметно легче вашей обычной нагрузки. Если это не запланированный отдых — форма начнёт уходить.",
                    InsightTone.NEUTRAL
                )
            }
        }

        // --- Recovery time -----------------------------------------------------------------------------
        val latestReadiness = inputs.readiness.lastOrNull { !it.isEmpty }
        if (latestReadiness != null && latestReadiness.dateEpochDay >= today.minusDays(1).toEpochDay() && latestReadiness.recoveryTimeHours >= 24f) {
            out += Insight(
                "⏳", "До полного восстановления ${formatHours(latestReadiness.recoveryTimeHours.toDouble())}",
                "Оценка Garmin после последней тренировки. Тяжёлую сессию лучше отложить, лёгкая — нормально.",
                InsightTone.NEUTRAL
            )
        }

        // --- Readiness trend ----------------------------------------------------------------------------
        if (readiness7.size >= 3 && readinessPrev.size >= 3) {
            val now = readiness7.map { it.score }.average()
            val before = readinessPrev.map { it.score }.average()
            val delta = (now - before).roundToInt()
            if (abs(delta) >= 8) {
                out += Insight(
                    "🟢", "Готовность к тренировкам ${if (delta > 0) "выросла" else "снизилась"} на $delta",
                    "Средний Training Readiness ${now.roundToInt()} за ${readiness7.size} ${declineDays(readiness7.size)} против ${before.roundToInt()} неделей раньше.",
                    if (delta > 0) InsightTone.GOOD else InsightTone.ATTENTION
                )
            }
        }

        // --- Sedentary time -------------------------------------------------------------------------------
        val sedentary7 = summaries7.filter { it.sedentarySeconds > 0 }
        if (sedentary7.size >= 3) {
            val hours = sedentary7.map { it.sedentarySeconds / 3600.0 }.average()
            if (hours > 10) {
                out += Insight(
                    "🪑", "Сидя ${formatHours(hours)} в день",
                    "Среднее время без движения за ${sedentary7.size} ${declineDays(sedentary7.size)} (без учёта сна). Больше 10 часов связано с рисками независимо от тренировок — помогают короткие перерывы каждый час.",
                    InsightTone.ATTENTION
                )
            }
        }

        // --- Steps ----------------------------------------------------------------------------------------
        val steps7 = stepsPerDay(summaries7, inputs.healthDays.filter { it.dateEpochDay >= weekFrom })
        val stepsPrev = stepsPerDay(summariesPrev, inputs.healthDays.filter { it.dateEpochDay in prevWeekFrom until weekFrom })
        if (steps7.size >= 3) {
            val avg = steps7.average().roundToInt()
            val change = if (stepsPrev.size >= 3) ((steps7.average() - stepsPrev.average()) / stepsPrev.average() * 100).roundToInt() else null
            val changeText = change?.let { " К прошлой неделе: ${if (it >= 0) "+" else ""}$it%." } ?: ""
            when {
                avg >= 10000 -> out += Insight(
                    "👟", "В среднем ${formatThousands(avg)} шагов в день",
                    "За ${steps7.size} ${declineDays(steps7.size)} с записью.$changeText",
                    InsightTone.GOOD
                )
                avg < 5000 -> out += Insight(
                    "👟", "Мало движения: ${formatThousands(avg)} шагов в день",
                    "Ниже 5 000 в день за ${steps7.size} ${declineDays(steps7.size)} — Garmin относит это к малоподвижному образу жизни.$changeText",
                    InsightTone.ATTENTION
                )
                change != null && abs(change) >= 20 -> out += Insight(
                    "👟", "Шаги: ${if (change > 0) "+" else ""}$change% к прошлой неделе",
                    "В среднем ${formatThousands(avg)} в день за ${steps7.size} ${declineDays(steps7.size)}.",
                    if (change > 0) InsightTone.GOOD else InsightTone.NEUTRAL
                )
            }
        }

        // --- Overnight SpO2 -------------------------------------------------------------------------------------
        val spo2Nights = sleeps7.filter { it.avgSpo2 > 0f }
        if (spo2Nights.size >= 3) {
            val avg = spo2Nights.map { it.avgSpo2.toDouble() }.average()
            if (avg < 94.0) {
                out += Insight(
                    "🫁", "Ночной SpO2 в среднем ${"%.1f".format(avg)}%",
                    "Ниже 94% за ${spo2Nights.size} ${nightsWord(spo2Nights.size)}. Разовые провалы нормальны; устойчиво низкие значения — повод обсудить с врачом (высота, простуда, апноэ).",
                    InsightTone.ATTENTION
                )
            }
        }

        // --- VO2max -------------------------------------------------------------------------------------------
        val vo2 = inputs.training.filter { it.vo2Max > 0f }
        if (vo2.size >= 2) {
            val first = vo2.first().vo2Max
            val last = vo2.last().vo2Max
            val delta = last - first
            if (abs(delta) >= 0.5f) {
                out += Insight(
                    "🫀", "VO2max ${if (delta > 0) "вырос" else "снизился"}: ${"%.1f".format(first)} → ${"%.1f".format(last)}",
                    "За период с ${LocalDate.ofEpochDay(vo2.first().dateEpochDay)} по ${LocalDate.ofEpochDay(vo2.last().dateEpochDay)}. VO2max меняется медленно — сдвиг на единицу за месяц уже заметный.",
                    if (delta > 0) InsightTone.GOOD else InsightTone.NEUTRAL
                )
            }
        }

        // --- Weight ---------------------------------------------------------------------------------------------
        val weights = mergedWeightByDay(inputs.weights, inputs.scaleWeights)
        if (weights.size >= 2) {
            val delta = weights.last().value - weights.first().value
            if (abs(delta) >= 0.5f) {
                out += Insight(
                    "⚖️", "Вес: ${if (delta > 0) "+" else ""}${"%.1f".format(delta)} кг",
                    "С ${LocalDate.ofEpochDay(weights.first().epochDay)} по ${LocalDate.ofEpochDay(weights.last().epochDay)}, ${weights.size} ${declineDays(weights.size)} с взвешиванием.",
                    InsightTone.NEUTRAL
                )
            }
        }

        return out.sortedBy { it.tone.ordinal }
    }

    /** The Garmin half of a week, see [GarminWeekSummary]. */
    fun computeWeek(
        weekStart: LocalDate,
        summaries: List<GarminDailyExtra>,
        sleeps: List<GarminSleep>,
        hrvs: List<GarminHrv>,
        readiness: List<GarminReadiness>,
        activities: List<GarminActivity>
    ): GarminWeekSummary {
        val start = weekStart.toEpochDay()
        val end = weekStart.plusDays(6).toEpochDay()
        val s = summaries.filter { it.dateEpochDay in start..end && !it.isEmpty }
        val n = sleeps.filter { it.dateEpochDay in start..end && it.score > 0 }
        val h = hrvs.filter { it.dateEpochDay in start..end && it.lastNightAvg > 0 }
        val r = readiness.filter { it.dateEpochDay in start..end && !it.isEmpty }
        val a = activities.filter { it.dateEpochDay in start..end }
        val stress = s.filter { it.averageStressLevel > 0 }
        val wake = s.filter { it.bodyBatteryAtWake > 0 }
        val rhr = s.filter { it.restingHeartRate > 0 }
        val needNights = n.filter { it.needActualMinutes > 0 && it.sleepSeconds > 0 }

        return GarminWeekSummary(
            daysWithSummary = s.size,
            nightsWithScore = n.size,
            avgSleepScore = n.averageIntOf { it.score },
            avgSleepNeedDeficitMinutes = needNights.averageIntOf { it.needDeficitMinutes },
            avgReadiness = r.averageIntOf { it.score },
            daysWithReadiness = r.size,
            avgStress = stress.averageIntOf { it.averageStressLevel },
            avgBodyBatteryAtWake = wake.averageIntOf { it.bodyBatteryAtWake },
            avgRestingHeartRate = rhr.averageIntOf { it.restingHeartRate },
            avgHrvLastNight = h.averageIntOf { it.lastNightAvg },
            latestHrvStatus = hrvs.lastOrNull { it.dateEpochDay in start..end && it.status.isNotBlank() }?.status.orEmpty(),
            intensityMinutesWeighted = s.sumOf { it.intensityMinutesWeighted },
            intensityMinutesGoal = s.lastOrNull { it.intensityMinutesWeeklyGoal > 0 }?.intensityMinutesWeeklyGoal
                ?: summaries.lastOrNull { it.intensityMinutesWeeklyGoal > 0 }?.intensityMinutesWeeklyGoal
                ?: DEFAULT_INTENSITY_GOAL,
            totalTrainingLoad = a.sumOf { it.activityTrainingLoad.roundToInt() },
            floorsAscended = s.sumOf { it.floorsAscended }
        )
    }

    // ---- Trend series for the Тренды tab --------------------------------------------------

    fun sleepScoreTrend(sleeps: List<GarminSleep>) = sleeps.filter { it.score > 0 }.map { TrendPoint(it.dateEpochDay, it.score.toFloat()) }
    fun sleepDurationTrend(sleeps: List<GarminSleep>) = sleeps.filter { it.sleepSeconds > 0 }.map { TrendPoint(it.dateEpochDay, it.sleepMinutes.toFloat()) }
    fun readinessTrend(list: List<GarminReadiness>) = list.filter { it.score > 0 }.map { TrendPoint(it.dateEpochDay, it.score.toFloat()) }
    fun hrvTrend(list: List<GarminHrv>) = list.filter { it.lastNightAvg > 0 }.map { TrendPoint(it.dateEpochDay, it.lastNightAvg.toFloat()) }
    fun stressTrend(list: List<GarminDailyExtra>) = list.filter { it.averageStressLevel > 0 }.map { TrendPoint(it.dateEpochDay, it.averageStressLevel.toFloat()) }
    fun bodyBatteryWakeTrend(list: List<GarminDailyExtra>) = list.filter { it.bodyBatteryAtWake > 0 }.map { TrendPoint(it.dateEpochDay, it.bodyBatteryAtWake.toFloat()) }
    fun restingHeartRateTrend(summaries: List<GarminDailyExtra>, healthDays: List<DailySummary>): List<TrendPoint> {
        val byDay = mutableMapOf<Long, Float>()
        healthDays.filter { it.restingHeartRate > 0 }.forEach { byDay[it.dateEpochDay] = it.restingHeartRate.toFloat() }
        summaries.filter { it.restingHeartRate > 0 }.forEach { byDay[it.dateEpochDay] = it.restingHeartRate.toFloat() }
        return byDay.entries.sortedBy { it.key }.map { TrendPoint(it.key, it.value) }
    }
    fun stepsTrend(summaries: List<GarminDailyExtra>, healthDays: List<DailySummary>): List<TrendPoint> {
        val byDay = mutableMapOf<Long, Float>()
        healthDays.filter { it.steps > 0 }.forEach { byDay[it.dateEpochDay] = it.steps.toFloat() }
        summaries.filter { it.totalSteps > 0 }.forEach { byDay[it.dateEpochDay] = it.totalSteps.toFloat() }
        return byDay.entries.sortedBy { it.key }.map { TrendPoint(it.key, it.value) }
    }
    fun vo2MaxTrend(list: List<GarminTraining>) = list.filter { it.vo2Max > 0f }.map { TrendPoint(it.dateEpochDay, it.vo2Max) }
    fun acuteLoadTrend(list: List<GarminTraining>) = list.filter { it.dailyTrainingLoadAcute > 0 }.map { TrendPoint(it.dateEpochDay, it.dailyTrainingLoadAcute.toFloat()) }
    fun chronicLoadTrend(list: List<GarminTraining>) = list.filter { it.dailyTrainingLoadChronic > 0 }.map { TrendPoint(it.dateEpochDay, it.dailyTrainingLoadChronic.toFloat()) }
    fun weightTrend(garmin: List<GarminBodyComposition>, scale: List<ScaleMeasurement> = emptyList()) = mergedWeightByDay(garmin, scale)

    /**
     * One weight per day from both sources. The scale's own reading wins over Garmin's
     * copy of it when both exist for a day - it's the primary record; Garmin's is the
     * echo - and the latest weigh-in of a day wins within the scale (lists arrive
     * ascending, so a later entry simply overwrites).
     */
    fun mergedWeightByDay(garmin: List<GarminBodyComposition>, scale: List<ScaleMeasurement>): List<TrendPoint> {
        val byDay = mutableMapOf<Long, Float>()
        garmin.filter { it.weightGrams > 0 }.forEach { byDay[it.dateEpochDay] = it.weightKg }
        scale.filter { it.weightGrams > 0 }.forEach { byDay[it.dateEpochDay] = it.weightKg }
        return byDay.entries.sortedBy { it.key }.map { TrendPoint(it.key, it.value) }
    }
    fun intensityMinutesTrend(list: List<GarminDailyExtra>) = list.map { TrendPoint(it.dateEpochDay, it.intensityMinutesWeighted.toFloat()) }

    // ---- Smoothing ----------------------------------------------------------------------------

    /**
     * A centred moving average of [points], for drawing the trend through the noise.
     *
     * The window is a span of **calendar days**, not a count of points, and that is the
     * whole design: these series have holes in them (a night without the watch, a week
     * on the charger), and an N-point window silently stretches across such a hole,
     * averaging together days that are a month apart while claiming to be a week's mean.
     * A day window can't - it averages what actually happened near that date, and where
     * there is nothing near, it declines to answer.
     *
     * "Declines" is [minPoints]: fewer than this inside the window and no smoothed value
     * is produced for that day. Without it, a single stray reading in an empty stretch
     * would draw a confident line through a period the data says nothing about.
     *
     * At the very ends of the range the window is necessarily half-full (there is no data
     * after today), so the last stretch of the curve is an average of fewer days than the
     * middle. That's inherent to a centred average and the reason the raw points stay
     * drawn underneath: the curve is a reading of the data, never a replacement for it.
     */
    fun smoothTrend(points: List<TrendPoint>, windowDays: Int, minPoints: Int = 3): List<TrendPoint> {
        if (windowDays < 2 || points.size < minPoints) return emptyList()
        val sorted = points.sortedBy { it.epochDay }
        val half = (windowDays / 2).toLong()
        val out = ArrayList<TrendPoint>(sorted.size)
        var lo = 0
        var hi = 0
        var sum = 0.0
        for (point in sorted) {
            while (hi < sorted.size && sorted[hi].epochDay <= point.epochDay + half) {
                sum += sorted[hi].value
                hi++
            }
            while (lo < hi && sorted[lo].epochDay < point.epochDay - half) {
                sum -= sorted[lo].value
                lo++
            }
            val count = hi - lo
            if (count >= minPoints) out += TrendPoint(point.epochDay, (sum / count).toFloat())
        }
        return out
    }

    /**
     * How many days to average over for a chart covering [spanDays].
     *
     * Scaled to the window rather than fixed, because the question changes with the span:
     * over four weeks "is this week worse than last" needs a week's smoothing, and over
     * three years a week's smoothing is still just noise - there the question is seasons,
     * so the window grows to two months. Roughly a quarter of the span at the short end,
     * flattening out long before it could swallow a year.
     */
    fun smoothWindowDaysFor(spanDays: Int): Int = when {
        spanDays <= 35 -> 7
        spanDays <= 120 -> 14
        spanDays <= 250 -> 21
        spanDays <= 400 -> 30
        spanDays <= 1200 -> 60
        // Five years is ~1800 points on a phone-width chart: at 60 days the curve still
        // carries week-to-week wobble that no longer means anything at that scale, and a
        // quarter is the unit a multi-year shape is actually read in.
        else -> 90
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun restingRates(summaries: List<GarminDailyExtra>, healthDays: List<DailySummary>): List<Int> {
        val byDay = mutableMapOf<Long, Int>()
        healthDays.filter { it.restingHeartRate > 0 }.forEach { byDay[it.dateEpochDay] = it.restingHeartRate }
        summaries.filter { it.restingHeartRate > 0 }.forEach { byDay[it.dateEpochDay] = it.restingHeartRate }
        return byDay.values.toList()
    }

    private fun stepsPerDay(summaries: List<GarminDailyExtra>, healthDays: List<DailySummary>): List<Long> {
        val byDay = mutableMapOf<Long, Long>()
        healthDays.filter { it.steps > 0 }.forEach { byDay[it.dateEpochDay] = it.steps }
        summaries.filter { it.totalSteps > 0 }.forEach { byDay[it.dateEpochDay] = it.totalSteps }
        return byDay.values.toList()
    }

    /**
     * Minutes since 18:00 of the bedtime's wall clock, so a 23:30 and a 00:30 bedtime are
     * 60 minutes apart, not 23 hours. Garmin's local timestamps are already wall-clock
     * shifted, hence the UTC read.
     */
    private fun bedtimeMinutes(localMillis: Long): Int {
        val time = Instant.ofEpochMilli(localMillis).atZone(ZoneOffset.UTC).toLocalTime()
        val minutes = time.hour * 60 + time.minute
        return (minutes - 18 * 60 + 24 * 60) % (24 * 60)
    }

    private fun stdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    }

    private inline fun <T> List<T>.averageIntOf(selector: (T) -> Int): Int =
        if (isEmpty()) 0 else (sumOf { selector(it) }.toDouble() / size).roundToInt()

    private fun formatHours(hours: Double): String {
        val total = (hours * 60).roundToInt()
        return formatDuration(total)
    }

    private fun formatThousands(value: Int): String = com.fitnessapp.summary.util.formatCount(value)

    private fun nightsWord(n: Int): String = when {
        n % 100 in 11..14 -> "ночей"
        n % 10 == 1 -> "ночь"
        n % 10 in 2..4 -> "ночи"
        else -> "ночей"
    }

    private fun morningsWord(n: Int): String = when {
        n % 100 in 11..14 -> "утр"
        n % 10 == 1 -> "утро"
        n % 10 in 2..4 -> "утра"
        else -> "утр"
    }
}
