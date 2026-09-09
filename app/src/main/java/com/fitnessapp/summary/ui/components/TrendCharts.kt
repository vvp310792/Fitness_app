package com.fitnessapp.summary.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.fitnessapp.summary.analytics.LifestyleAnalytics
import com.fitnessapp.summary.analytics.TrendPoint
import com.fitnessapp.summary.util.formatDayMonth
import com.fitnessapp.summary.util.formatMonthYear
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * One measure over a run of days: the daily values, and the moving average through them.
 *
 * The same rules as [WeekBarChart]: a single series, no legend (the card title names the
 * measure), no second axis. Numbers live in text under the chart - the minimum, the
 * maximum and the latest value - rather than on every point, and in ink tokens rather
 * than the series colour.
 *
 * **The smoothed curve is the message, the daily values are the evidence.** Day-to-day
 * readings of readiness or resting heart rate swing far enough that a raw line answers
 * "what happened on Tuesday" while hiding "which way is this going" - which is the only
 * question a months-long view is asked. So with [smoothWindowDays] set, the raw line and
 * its dots drop back to a faint texture and the average is drawn over them at full
 * weight. Same colour for both, because it is the same measure (one colour = one metric,
 * everywhere); the difference in weight, not hue, says which is the reading and which is
 * the data. With no smoothing possible - too few points to average honestly - the raw
 * line steps back up to full weight rather than leaving a nearly invisible chart.
 *
 * **The line is continuous.** It used to break wherever two readings sat more than a week
 * (or a smoothing window) apart, on the theory that a long straight segment across a month
 * without the watch looks like a measurement that never happened. In practice that read as
 * a broken chart rather than as an honest one - on the longer windows the smoothed curve
 * fragmented into disconnected pieces and the shape of the trend, which is the whole point
 * of the screen, stopped being visible. So gaps are simply spanned, and the dots carry the
 * honesty instead: they mark where the actual readings are, so a long segment with nothing
 * on it is visibly a bridge, not data. Dots are drawn only while they can still be told
 * apart; past that the daily values are the texture of the line itself.
 *
 * **The Y axis is labelled.** Three or four gridlines at round values, drawn in the ink
 * token with the card's own [formatValue], so the numbers on them are in the same units
 * and spelling as everywhere else ("7 ч 10 мин", not "430"). Without them the shape of a
 * line said which way things were going but never in what range - and a chart whose
 * vertical scale is invisible cannot be read against anything, only against itself. The
 * min/max/latest row below the chart stays: it answers "exactly what", the axis answers
 * "roughly where".
 *
 * [band] shades a reference range (an HRV baseline), [goal] draws a dashed reference
 * line (a step or intensity target). Both are recessive: present enough to read the
 * points against, quiet enough not to compete with them.
 *
 * [secondary] is a second series of the SAME measure in the SAME units, drawn dashed and
 * without dots - estimated 1RM against the weight the work is actually done at. Same
 * colour again, for the same reason: it is the same metric, and the gap between the two
 * lines is the thing worth seeing. It is not a licence for a second axis; anything that
 * would need one does not belong on this chart.
 */
@Composable
fun TrendChart(
    points: List<TrendPoint>,
    fromEpochDay: Long,
    toEpochDay: Long,
    accent: Color,
    formatValue: (Float) -> String,
    modifier: Modifier = Modifier,
    height: Int = 96,
    band: ClosedFloatingPointRange<Float>? = null,
    goal: Float? = null,
    floorAtZero: Boolean = false,
    smoothWindowDays: Int = 0,
    secondary: List<TrendPoint> = emptyList()
) {
    val sorted = points.filter { it.epochDay in fromEpochDay..toEpochDay }.sortedBy { it.epochDay }
    val sortedSecondary = secondary.filter { it.epochDay in fromEpochDay..toEpochDay }.sortedBy { it.epochDay }
    val smoothed = remember(sorted, smoothWindowDays) {
        if (smoothWindowDays >= 2) LifestyleAnalytics.smoothTrend(sorted, smoothWindowDays) else emptyList()
    }
    val hasSmooth = smoothed.size >= 2
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val baseline = ink.copy(alpha = 0.25f)

    if (sorted.isEmpty()) {
        Text(
            text = "Нет данных за этот период.",
            style = MaterialTheme.typography.bodyMedium,
            color = ink,
            modifier = modifier.padding(vertical = 8.dp)
        )
        return
    }

    val values = sorted.map { it.value }
    var lo = minOf(values.min(), sortedSecondary.minOfOrNull { it.value } ?: values.min())
    var hi = maxOf(values.max(), sortedSecondary.maxOfOrNull { it.value } ?: values.max())
    band?.let { lo = min(lo, it.start); hi = max(hi, it.endInclusive) }
    goal?.let { lo = min(lo, it); hi = max(hi, it) }
    if (floorAtZero) lo = min(lo, 0f)
    // A flat line still needs a visible range to sit inside.
    if (hi - lo < 1e-3f) { hi += 1f; lo -= 1f }
    val pad = (hi - lo) * 0.12f
    val yMin = if (floorAtZero) min(lo, 0f) else lo - pad
    val yMax = hi + pad

    val spanDays = (toEpochDay - fromEpochDay).coerceAtLeast(1L)
    val span = spanDays.toFloat()
    val latest = sorted.last()

    // Round values inside the visible range, not the padded edges themselves: an axis
    // labelled "62,4 / 68,1 / 73,8" is arithmetically right and useless to read against.
    val ticks = remember(yMin, yMax) { niceTicks(yMin, yMax) }
    val measurer = rememberTextMeasurer()
    val tickStyle = MaterialTheme.typography.labelSmall.copy(color = ink)
    // The gutter is as wide as the widest label - measured, not guessed, because the
    // labels are formatted by the caller and can be "9" or "7 ч 10 мин".
    val gutterPx = ticks.maxOfOrNull { measurer.measure(formatValue(it), tickStyle).size.width }?.plus(10) ?: 0

    // 3dp dots need ~6dp of room each; past that they merge into a bar and stop being dots.
    val showDots = !hasSmooth || sorted.size <= 100
    // Only worth marking year boundaries once a span actually crosses more than one.
    val yearMarks = if (spanDays > 400) {
        val first = LocalDate.ofEpochDay(fromEpochDay).year + 1
        val last = LocalDate.ofEpochDay(toEpochDay).year
        (first..last).map { LocalDate.of(it, 1, 1).toEpochDay() }
    } else {
        emptyList()
    }
    val labelStart = if (spanDays > 180) formatMonthYear(LocalDate.ofEpochDay(fromEpochDay)) else formatDayMonth(LocalDate.ofEpochDay(fromEpochDay))
    val labelEnd = if (spanDays > 180) formatMonthYear(LocalDate.ofEpochDay(toEpochDay)) else formatDayMonth(LocalDate.ofEpochDay(toEpochDay))

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height.dp)
        ) {
            val gutter = gutterPx.toFloat()
            val w = size.width
            val plotWidth = (w - gutter).coerceAtLeast(1f)
            val h = size.height
            fun x(day: Long) = gutter + (day - fromEpochDay) / span * plotWidth
            fun y(v: Float) = h - (v - yMin) / (yMax - yMin) * h

            // Axis first, so every series is drawn over it rather than under.
            ticks.forEach { value ->
                val ty = y(value)
                drawLine(
                    color = baseline.copy(alpha = 0.35f),
                    start = Offset(gutter, ty),
                    end = Offset(w, ty),
                    strokeWidth = 1.dp.toPx()
                )
                val label = measurer.measure(formatValue(value), tickStyle)
                drawText(
                    textLayoutResult = label,
                    topLeft = Offset(
                        x = (gutter - 10 - label.size.width).coerceAtLeast(0f),
                        // Nudged so the text sits centred on its line, and never clipped
                        // off the top or bottom edge of the canvas.
                        y = (ty - label.size.height / 2f).coerceIn(0f, h - label.size.height)
                    )
                )
            }

            band?.let {
                val top = y(it.endInclusive)
                val bottom = y(it.start)
                drawRect(
                    color = accent.copy(alpha = 0.12f),
                    topLeft = Offset(gutter, top),
                    size = Size(plotWidth, bottom - top)
                )
            }
            goal?.let {
                drawLine(
                    color = baseline,
                    start = Offset(gutter, y(it)),
                    end = Offset(w, y(it)),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                )
            }
            yearMarks.forEach { day ->
                drawLine(
                    color = baseline,
                    start = Offset(x(day), 0f),
                    end = Offset(x(day), h),
                    strokeWidth = 1.dp.toPx()
                )
            }
            drawLine(color = baseline, start = Offset(gutter, h), end = Offset(w, h), strokeWidth = 1.dp.toPx())

            /** One series, drawn as one unbroken line - see the note on continuity above. */
            fun drawSeries(series: List<TrendPoint>, color: Color, widthDp: Float) {
                if (series.size < 2) return
                val path = Path()
                series.forEachIndexed { index, p ->
                    val px = x(p.epochDay)
                    val py = y(p.value)
                    if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width = widthDp.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            drawSeries(sorted, accent.copy(alpha = if (hasSmooth) 0.3f else 0.7f), if (hasSmooth) 1.5f else 2f)
            if (showDots) {
                val dotColor = if (hasSmooth) accent.copy(alpha = 0.55f) else accent
                val dotRadius = if (hasSmooth) 2.5f else 3f
                sorted.forEach { p ->
                    drawCircle(color = dotColor, radius = dotRadius.dp.toPx(), center = Offset(x(p.epochDay), y(p.value)))
                }
            }
            if (sortedSecondary.size >= 2) {
                val path = Path()
                sortedSecondary.forEachIndexed { index, p ->
                    val px = x(p.epochDay)
                    val py = y(p.value)
                    if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(
                    path = path,
                    color = accent.copy(alpha = 0.75f),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                    )
                )
            }
            drawSeries(smoothed, accent, 2.5f)
            drawCircle(color = accent, radius = 5.dp.toPx(), center = Offset(x(latest.epochDay), y(latest.value)))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Indented by the axis gutter so the first date sits under the first
                // point rather than under the axis labels.
                .padding(start = with(LocalDensity.current) { gutterPx.toDp() }, top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(labelStart, style = MaterialTheme.typography.labelMedium, color = ink)
            Text(labelEnd, style = MaterialTheme.typography.labelMedium, color = ink)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("мин ${formatValue(values.min())}", style = MaterialTheme.typography.labelMedium, color = ink)
            Text("макс ${formatValue(values.max())}", style = MaterialTheme.typography.labelMedium, color = ink)
            Text(
                text = "сейчас ${formatValue(latest.value)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Round values to label the Y axis with, inside [lo]..[hi].
 *
 * "Round" in the 1/2/2.5/5/10 sense, the same ladder every plotting library settles on:
 * those are the steps a reader adds up in their head without effort. The step comes from
 * the range, so a resting-heart-rate chart gets 5s and a step-count chart gets 5000s
 * without either being told what it is measuring.
 *
 * At most [maxTicks] of them, and never fewer than the two ends: on a 96dp-tall chart a
 * fourth gridline is clutter, and a chart with no labelled line at all is what this
 * function exists to prevent. A flat series (lo == hi) falls back to its single value.
 */
internal fun niceTicks(lo: Float, hi: Float, maxTicks: Int = 4): List<Float> {
    val range = hi - lo
    if (range <= 0f || !range.isFinite()) return listOf(lo)
    val raw = range / (maxTicks - 1)
    val magnitude = 10f.pow(floor(log10(raw.toDouble())).toFloat())
    val normalised = raw / magnitude
    val step = magnitude * when {
        normalised <= 1f -> 1f
        normalised <= 2f -> 2f
        normalised <= 2.5f -> 2.5f
        normalised <= 5f -> 5f
        else -> 10f
    }
    if (step <= 0f || !step.isFinite()) return listOf(lo)

    val first = ceil(lo / step) * step
    val out = mutableListOf<Float>()
    var value = first
    while (value <= hi + step * 0.001f && out.size < maxTicks) {
        out += value
        value += step
    }
    return out.ifEmpty { listOf(lo) }
}

/** A single horizontal progress bar, for "X of goal Y" readouts. Fraction is clamped to 0..1. */
@Composable
fun ProgressBar(
    fraction: Float,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f), RoundedCornerShape(5.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(accent, RoundedCornerShape(5.dp))
        )
    }
}
