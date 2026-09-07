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
import com.fitnessapp.summary.data.GarminActivity
import com.fitnessapp.summary.data.Workout
import com.fitnessapp.summary.ui.theme.metricPalette
import com.fitnessapp.summary.util.exerciseEmoji
import com.fitnessapp.summary.util.exerciseName
import com.fitnessapp.summary.util.formatCalories
import com.fitnessapp.summary.util.formatDecimal
import com.fitnessapp.summary.util.formatDistance
import com.fitnessapp.summary.util.formatDuration
import com.fitnessapp.summary.util.formatPace
import com.fitnessapp.summary.util.formatTimeRange
import com.fitnessapp.summary.util.garminSportEmoji
import com.fitnessapp.summary.util.garminSportName
import com.fitnessapp.summary.util.garminSportUsesPace
import com.fitnessapp.summary.util.trainingEffectLabel
import com.fitnessapp.summary.util.usesPace
import kotlin.math.abs

/**
 * One workout, summarised in a row.
 *
 * Which facts appear is driven by the session itself rather than being a fixed
 * template: a strength session has no distance and no pace, and printing "- км" for
 * it is noise. Only the metrics that actually carry a value are rendered, so each
 * row is as long as it has something to say.
 *
 * [garmin] is the same session as Garmin Connect itself recorded it, when the unofficial
 * client has one that starts within a few minutes of this one (see [matchGarminActivity]).
 * It adds the line Health Connect can't provide: Training Effect, load, Body Battery cost.
 */
@Composable
fun WorkoutRow(
    workout: Workout,
    modifier: Modifier = Modifier,
    showDate: String? = null,
    garmin: GarminActivity? = null
) {
    val facts = buildList {
        add(formatDuration(workout.durationMinutes))
        if (workout.distanceMeters > 0) add(formatDistance(workout.distanceMeters))
        if (usesPace(workout.exerciseType)) {
            formatPace(workout.distanceMeters, workout.durationMinutes)?.let { add(it) }
        }
        if (workout.activeCaloriesKcal > 0) add(formatCalories(workout.activeCaloriesKcal))
        if (workout.avgHeartRate > 0) add("♥ ${workout.avgHeartRate}")
    }
    ActivityCard(
        emoji = exerciseEmoji(workout.exerciseType),
        title = workout.title.ifBlank { exerciseName(workout.exerciseType) },
        timeLine = buildString {
            if (showDate != null) append(showDate).append(" · ")
            append(formatTimeRange(workout.startTimeMillis, workout.endTimeMillis))
        },
        facts = facts,
        garminLine = garmin?.let { garminEffectLine(it) },
        modifier = modifier
    )
}

/**
 * A Garmin activity that has no Health Connect twin (logged manually in Garmin Connect,
 * or from before Health Connect was connected). Same card as [WorkoutRow] so the list
 * reads as one archive, not two sources.
 */
@Composable
fun GarminActivityRow(
    activity: GarminActivity,
    modifier: Modifier = Modifier,
    showDate: String? = null
) {
    val facts = buildList {
        add(formatDuration(activity.durationMinutes))
        if (activity.distanceMeters > 0) add(formatDistance(activity.distanceMeters))
        if (garminSportUsesPace(activity.typeKey)) {
            formatPace(activity.distanceMeters, activity.durationMinutes)?.let { add(it) }
        }
        if (activity.calories > 0) add(formatCalories(activity.calories))
        if (activity.avgHeartRate > 0) add("♥ ${activity.avgHeartRate}")
    }
    val end = activity.startTimeMillis + activity.durationSeconds * 1000L
    ActivityCard(
        emoji = garminSportEmoji(activity.typeKey),
        title = activity.name.ifBlank { garminSportName(activity.typeKey) },
        timeLine = buildString {
            if (showDate != null) append(showDate).append(" · ")
            append(formatTimeRange(activity.startTimeMillis, end))
            append(" · Garmin")
        },
        facts = facts,
        garminLine = garminEffectLine(activity),
        modifier = modifier
    )
}

/**
 * The Garmin activity that is the same session as [workout], if any: Garmin and Health
 * Connect timestamp the same recording from the same watch, so a start within three
 * minutes is the same session. Null when Garmin has nothing that close.
 */
fun matchGarminActivity(workout: Workout, candidates: List<GarminActivity>): GarminActivity? =
    candidates
        .filter { abs(it.startTimeMillis - workout.startTimeMillis) <= MATCH_WINDOW_MILLIS }
        .minByOrNull { abs(it.startTimeMillis - workout.startTimeMillis) }

/** Garmin activities in [candidates] that match none of [workouts] - the ones to list on their own. */
fun unmatchedGarminActivities(workouts: List<Workout>, candidates: List<GarminActivity>): List<GarminActivity> {
    val matched = workouts.mapNotNull { matchGarminActivity(it, candidates)?.activityId }.toSet()
    return candidates.filter { it.activityId !in matched }
}

private const val MATCH_WINDOW_MILLIS = 3 * 60 * 1000L

/** "TE 3,2 аэробный · 0,8 анаэробный · Темп · нагрузка 85 · BB −12", only the parts present. */
private fun garminEffectLine(activity: GarminActivity): String? {
    val parts = buildList {
        if (activity.aerobicTrainingEffect > 0f) add("TE ${formatDecimal(activity.aerobicTrainingEffect)} аэробный")
        if (activity.anaerobicTrainingEffect > 0f) add("${formatDecimal(activity.anaerobicTrainingEffect)} анаэробный")
        trainingEffectLabel(activity.trainingEffectLabel).takeIf { it.isNotBlank() }?.let { add(it) }
        if (activity.activityTrainingLoad > 0f) add("нагрузка ${Math.round(activity.activityTrainingLoad)}")
        if (activity.bodyBatteryDiff != 0) add("BB ${if (activity.bodyBatteryDiff > 0) "+" else "−"}${abs(activity.bodyBatteryDiff)}")
        if (activity.avgPower > 0) add("${activity.avgPower} Вт")
        if (activity.elevationGainMeters > 0) add("↑${activity.elevationGainMeters} м")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun ActivityCard(
    emoji: String,
    title: String,
    timeLine: String,
    facts: List<String>,
    garminLine: String?,
    modifier: Modifier = Modifier
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
                Text(emoji, style = MaterialTheme.typography.titleMedium)
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = timeLine,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                if (garminLine != null) {
                    Text(
                        text = garminLine,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }
    }
}
