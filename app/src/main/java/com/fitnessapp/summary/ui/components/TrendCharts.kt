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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fitnessapp.summary.analytics.TrendPoint
import com.fitnessapp.summary.util.formatDayMonth
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/**
 * One measure over a run of days, as a line with a dot per day that had data.
 *
 * The same rules as [WeekBarChart]: a single series, no legend (the card title names the
 * measure), no second axis. Numbers live in text under the chart - the minimum, the
 * maximum and the latest value - rather than on every point, and in ink tokens rather
 * than the series colour. Days without data are gaps, not zeros: the line connects the
 * points that exist, and a missing morning simply isn't drawn.
 *
 * [band] shades a reference range (an HRV baseline), [goal] draws a dashed reference
 * line (a step or intensity target). Both are recessive: present enough to read the
 * points against, quiet enough not to compete with them.
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
    floorAtZero: Boolean = false
) {
    val sorted = points.filter { it.epochDay in fromEpochDay..toEpochDay }.sortedBy { it.epochDay }
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
    var lo = values.min()
    var hi = values.max()
    band?.let { lo = min(lo, it.start); hi = max(hi, it.endInclusive) }
    goal?.let { lo = min(lo, it); hi = max(hi, it) }
    if (floorAtZero) lo = min(lo, 0f)
    // A flat line still needs a visible range to sit inside.
    if (hi - lo < 1e-3f) { hi += 1f; lo -= 1f }
    val pad = (hi - lo) * 0.12f
    val yMin = if (floorAtZero) min(lo, 0f) else lo - pad
    val yMax = hi + pad

    val span = (toEpochDay - fromEpochDay).coerceAtLeast(1L).toFloat()
    val latest = sorted.last()

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height.dp)
        ) {
            val w = size.width
            val h = size.height
            fun x(day: Long) = (day - fromEpochDay) / span * w
            fun y(v: Float) = h - (v - yMin) / (yMax - yMin) * h

            band?.let {
                val top = y(it.endInclusive)
                val bottom = y(it.start)
                drawRect(
                    color = accent.copy(alpha = 0.12f),
                    topLeft = Offset(0f, top),
                    size = Size(w, bottom - top)
                )
            }
            goal?.let {
                drawLine(
                    color = baseline,
                    start = Offset(0f, y(it)),
                    end = Offset(w, y(it)),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                )
            }
            drawLine(color = baseline, start = Offset(0f, h), end = Offset(w, h), strokeWidth = 1.dp.toPx())

            if (sorted.size > 1) {
                val path = Path()
                sorted.forEachIndexed { index, p ->
                    if (index == 0) path.moveTo(x(p.epochDay), y(p.value)) else path.lineTo(x(p.epochDay), y(p.value))
                }
                drawPath(
                    path = path,
                    color = accent.copy(alpha = 0.7f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
            sorted.forEach { p ->
                drawCircle(color = accent, radius = 3.dp.toPx(), center = Offset(x(p.epochDay), y(p.value)))
            }
            drawCircle(color = accent, radius = 5.dp.toPx(), center = Offset(x(latest.epochDay), y(latest.value)))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatDayMonth(LocalDate.ofEpochDay(fromEpochDay)), style = MaterialTheme.typography.labelMedium, color = ink)
            Text(formatDayMonth(LocalDate.ofEpochDay(toEpochDay)), style = MaterialTheme.typography.labelMedium, color = ink)
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
