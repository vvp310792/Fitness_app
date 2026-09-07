package com.fitnessapp.summary.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitnessapp.summary.data.Workout
import com.fitnessapp.summary.ui.theme.metricPalette
import com.fitnessapp.summary.util.exerciseEmoji
import com.fitnessapp.summary.util.exerciseName
import com.fitnessapp.summary.util.formatCalories
import com.fitnessapp.summary.util.formatDistance
import com.fitnessapp.summary.util.formatDuration
import com.fitnessapp.summary.util.formatPace
import com.fitnessapp.summary.util.formatTimeRange
import com.fitnessapp.summary.util.usesPace

/**
 * One workout, summarised in a row.
 *
 * Which facts appear is driven by the session itself rather than being a fixed
 * template: a strength session has no distance and no pace, and printing "- км" for
 * it is noise. Only the metrics that actually carry a value are rendered, so each
 * row is as long as it has something to say.
 */
@Composable
fun WorkoutRow(
    workout: Workout,
    modifier: Modifier = Modifier,
    showDate: String? = null
) {
    val palette = metricPalette()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(palette.workout.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(exerciseEmoji(workout.exerciseType), style = MaterialTheme.typography.titleMedium)
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(
                    text = workout.title.ifBlank { exerciseName(workout.exerciseType) },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                val timeLine = buildString {
                    if (showDate != null) {
                        append(showDate)
                        append(" · ")
                    }
                    append(formatTimeRange(workout.startTimeMillis, workout.endTimeMillis))
                }
                Text(
                    text = timeLine,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val facts = buildList {
                    add(formatDuration(workout.durationMinutes))
                    if (workout.distanceMeters > 0) add(formatDistance(workout.distanceMeters))
                    if (usesPace(workout.exerciseType)) {
                        formatPace(workout.distanceMeters, workout.durationMinutes)?.let { add(it) }
                    }
                    if (workout.activeCaloriesKcal > 0) add(formatCalories(workout.activeCaloriesKcal))
                    if (workout.avgHeartRate > 0) add("♥ ${workout.avgHeartRate}")
                }

                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    facts.forEach { fact ->
                        Text(
                            text = fact,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
