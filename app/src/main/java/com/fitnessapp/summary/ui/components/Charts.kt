package com.fitnessapp.summary.ui.components

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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

/** Missing and future values are not measurements of zero. */
enum class BarState { MEASURED,MISSING,FUTURE }
fun barState(future:Boolean,hasMeasurement:Boolean):BarState=when {
    future->BarState.FUTURE
    hasMeasurement->BarState.MEASURED
    else->BarState.MISSING
}
data class BarDatum(val label:String,val value:Long,val highlighted:Boolean=false,val state:BarState=BarState.MEASURED)

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
    val maxValue=data.filter{it.state==BarState.MEASURED}.maxOfOrNull{it.value} ?: 0L
    val maxIndex=data.indexOfFirst{it.state==BarState.MEASURED && it.value==maxValue && maxValue>0}

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barAreaHeight.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            data.forEachIndexed { index, datum ->
                val fraction=if(maxValue<=0L)0f else (datum.value.toFloat()/maxValue).coerceIn(0f,1f)
                val spoken=datum.label+", "+when(datum.state) {
                    BarState.MEASURED->formatValue(datum.value)
                    BarState.MISSING->"нет измерения"
                    BarState.FUTURE->"день ещё не наступил"
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight().semantics { contentDescription=spoken }
                        .then(if(onBarClick!=null && datum.state!=BarState.FUTURE)Modifier.clickable{onBarClick(index)} else Modifier),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if(datum.state!=BarState.MEASURED || datum.value==0L) {
                        Text(when(datum.state){BarState.MISSING->"—";BarState.FUTURE->"·";BarState.MEASURED->"0"},
                            style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if(index==maxIndex) {
                        Text(
                            text = formatValue(datum.value),
                            style = MaterialTheme.typography.labelMedium,
                            // Values wear an ink token, never the series colour - the
                            // bar beside the number already carries the identity.
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    if(datum.state==BarState.MEASURED && datum.value>0)Box(
                        Modifier.fillMaxWidth().fillMaxHeight(fraction.coerceAtLeast(0.02f))
                            .background(if(datum.highlighted)accent else accent.copy(alpha=0.75f),
                                RoundedCornerShape(topStart=4.dp,topEnd=4.dp)))
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
        if(data.any{it.state!=BarState.MEASURED})Text("— нет измерения   · будущий день",
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=6.dp))
        if(onBarClick!=null)Text("Нажмите на день, чтобы открыть его.",style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
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
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .weight(1.1f)
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
                        .weight(1f)
                        .padding(start = 8.dp)
                )
            }
        }
    }
}
