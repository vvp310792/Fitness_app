package com.fitnessapp.summary.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** One column of a [WeekBarChart]. */
data class BarDatum(
    val label: String,
    val value: Long,
    val highlighted: Boolean = false
)

/**
 * Seven days of one measure.
 *
 * Single series on purpose - there is no legend because the card's own title names
 * the measure, and there is no second y-axis anywhere in this app. Only the largest
 * bar is labelled with its number: putting a value on all seven turns the chart back
 * into a table and buries the shape, which is the whole reason to draw it.
 *
 * Bars are separated by a real surface gap and their tops are rounded while their
 * bases stay square, so they read as anchored to the baseline rather than floating.
 */
@Composable
fun WeekBarChart(
    data: List<BarDatum>,
    accent: Color,
    formatValue: (Long) -> String,
    modifier: Modifier = Modifier,
    barAreaHeight: Int = 120,
    onBarClick: ((Int) -> Unit)? = null
) {
    val maxValue = data.maxOfOrNull { it.value } ?: 0L
    val maxIndex = data.indexOfFirst { it.value == maxValue && maxValue > 0 }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barAreaHeight.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            data.forEachIndexed { index, datum ->
                val fraction = if (maxValue <= 0L) 0f else (datum.value.toFloat() / maxValue)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(
                            if (onBarClick != null) Modifier.clickable { onBarClick(index) } else Modifier
                        ),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (index == maxIndex) {
                        Text(
                            text = formatValue(datum.value),
                            style = MaterialTheme.typography.labelMedium,
                            // Values wear an ink token, never the series colour - the
                            // bar beside the number already carries the identity.
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // A zero-height bar is invisible and reads as a rendering
                            // bug; a 3dp stub reads as "this day was empty".
                            .fillMaxHeight(fraction.coerceAtLeast(0.02f))
                            .background(
                                color = if (datum.highlighted) accent else accent.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                            )
                    )
                }
            }
        }

        // Recessive baseline - present enough to anchor the bars, quiet enough not to
        // compete with them.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            data.forEach { datum ->
                Text(
                    text = datum.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (datum.highlighted) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (datum.highlighted) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** One labelled row of a [StageBars] breakdown. */
data class StageDatum(
    val label: String,
    val minutes: Int,
    val color: Color
)

/**
 * A composition breakdown as labelled rows rather than one stacked bar.
 *
 * Stacked segments would need four colours to stay mutually distinguishable at small
 * sizes on both a light and a dark background, and a single-hue depth ramp cannot do
 * that while also clearing the contrast floor - the lightest step goes grey. Giving
 * each stage its own row with its name and duration in text makes colour purely
 * reinforcing, which is both more accessible and easier to read on a phone.
 *
 * Bars are scaled against the largest stage, not the total, so the small stages stay
 * legible instead of collapsing into slivers.
 */
@Composable
fun StageBars(
    stages: List<StageDatum>,
    formatValue: (Int) -> String,
    modifier: Modifier = Modifier
) {
    val maxMinutes = stages.maxOfOrNull { it.minutes } ?: 0

    Column(modifier = modifier.fillMaxWidth()) {
        stages.forEach { stage ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stage.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(76.dp)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(10.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f),
                            RoundedCornerShape(5.dp)
                        )
                ) {
                    val fraction = if (maxMinutes <= 0) 0f else stage.minutes.toFloat() / maxMinutes
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(stage.color, RoundedCornerShape(5.dp))
                    )
                }
                Text(
                    text = formatValue(stage.minutes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .width(84.dp)
                        .padding(start = 8.dp)
                )
            }
        }
    }
}
