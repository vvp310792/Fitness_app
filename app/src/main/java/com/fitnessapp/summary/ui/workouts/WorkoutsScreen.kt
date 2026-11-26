package com.fitnessapp.summary.ui.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitnessapp.summary.FitnessSummaryApp
import com.fitnessapp.summary.analytics.ArchiveEpisode
import com.fitnessapp.summary.analytics.ArchiveResult
import com.fitnessapp.summary.analytics.FitnessSource
import com.fitnessapp.summary.analytics.FitnessSport
import com.fitnessapp.summary.analytics.LocalWorkoutArchive
import com.fitnessapp.summary.ui.components.InfoCard
import com.fitnessapp.summary.ui.components.SectionHeader
import com.fitnessapp.summary.ui.components.StatRow
import com.fitnessapp.summary.util.formatCount
import com.fitnessapp.summary.util.formatDayHeader
import com.fitnessapp.summary.util.formatDecimal
import com.fitnessapp.summary.util.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import java.time.LocalDate

private fun FitnessSport.label() = when(this) {
    FitnessSport.RUN -> "Бег"
    FitnessSport.CYCLE -> "Велосипед"
    FitnessSport.SWIM -> "Плавание"
    FitnessSport.STRENGTH -> "Силовые"
    FitnessSport.WALK -> "Ходьба"
    FitnessSport.OTHER -> "Другие"
    FitnessSport.UNKNOWN -> "Не определён"
}
private fun FitnessSource.label() = when(this) {
    FitnessSource.GARMIN -> "Garmin"
    FitnessSource.GYMUP -> "GymUp / силовой журнал"
    FitnessSource.STRAVA -> "Strava"
    FitnessSource.HEALTH_CONNECT -> "Health Connect"
    FitnessSource.GOOGLE_HEALTH -> "Google Health"
    FitnessSource.GOOGLE_FIT -> "Google Fit"
    FitnessSource.ZEPP -> "Zepp Life"
    FitnessSource.LEGACY -> "Архив"
}
private fun secondsLabel(seconds: Long): String {
    val h=seconds/3600
    val m=seconds/60%60
    return when {
        h>0 -> "$h ч $m мин"
        m>0 -> "$m мин"
        else -> "$seconds с"
    }
}

/** Whole LOCAL archive, not a claim that the server archive is already connected.
 * Lazy rendering and source-aware filters; preserve uncertain matches as separate rows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkoutsScreen(app: FitnessSummaryApp) {
    val today=LocalDate.now()
    val stream=remember(app) {
        combine(app.database.workoutDao().observeAll(),
            app.database.garminActivityDao().observeRange(Long.MIN_VALUE,Long.MAX_VALUE),
            app.database.stravaActivityDao().observeRange(Long.MIN_VALUE,Long.MAX_VALUE),
            app.database.strengthSetDao().observeRange(Long.MIN_VALUE,Long.MAX_VALUE)) { h,g,s,w ->
            LocalWorkoutArchive.build(h,g,s,w)
        }.flowOn(Dispatchers.Default)
    }
    val archive: ArchiveResult? by stream.collectAsState(initial=null)
    var sportName by rememberSaveable { mutableStateOf("ALL") }
    var period by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val filtered=remember(archive,sportName,period,query,today) {
        archive?.episodes.orEmpty().filter { e ->
            (sportName=="ALL" || e.primary.session.sport.name==sportName) &&
            (period==0 || e.primary.day>=today.minusDays(period.toLong()-1).toEpochDay()) &&
            (query.isBlank() || e.observations.any { it.title.contains(query.trim(),true) } ||
                e.sets.any { it.exerciseName.contains(query.trim(),true) })
        }
    }
    val confirmed=remember(filtered) { filtered.filterNot { it.episode.ambiguous } }
    val uncertain=filtered.size-confirmed.size
    val durations=confirmed.mapNotNull { it.durationSeconds }
    val distances=confirmed.mapNotNull { it.distanceMeters }

    LazyColumn(modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {
            Text("Тренировки",style=MaterialTheme.typography.headlineMedium)
            Text("Локальный архив • данные сервера пока не подключены",
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf(0 to "Всё время",28 to "4 недели",84 to "12 недель",365 to "Год").forEach { (days,label) ->
                    FilterChip(selected=period==days,onClick={period=days},label={Text(label)})
                }
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                FilterChip(selected=sportName=="ALL",onClick={sportName="ALL"},label={Text("Все виды")})
                FitnessSport.entries.forEach { sport ->
                    FilterChip(selected=sportName==sport.name,onClick={sportName=sport.name},label={Text(sport.label())})
                }
            }
            OutlinedTextField(value=query,onValueChange={query=it},singleLine=true,
                label={Text("Поиск по названию или упражнению")},modifier=Modifier.fillMaxWidth())
        }
        if(archive==null) {
            item { CircularProgressIndicator(); Text("Читаем локальный архив…") }
        } else {
            item {
                InfoCard("За выбранный период") {
                    StatRow("Однозначных занятий",formatCount(confirmed.size))
                    StatRow("Требуют сверки",formatCount(uncertain))
                    StatRow("Записанное время",if(durations.isEmpty()) "Нет данных" else secondsLabel(durations.sum()))
                    Text("Длительность известна для ${durations.size} из ${confirmed.size} однозначных занятий.",
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    StatRow("Дистанция",if(distances.isEmpty()) "Нет данных" else "${formatDecimal((distances.sum()/1000).toFloat())} км")
                    Text("Дистанция известна для ${distances.size} из ${confirmed.size} однозначных занятий.",
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(uncertain>0) Text("Сомнительные совпадения показаны отдельно и не входят в эти суммы.",
                        style=MaterialTheme.typography.bodySmall)
                    val rejected=archive?.rejectedObservations ?: 0
                    if(rejected>0) Text("Некорректных или повторных локальных идентификаторов: $rejected. Исходные записи сохранены.",
                        style=MaterialTheme.typography.bodySmall)
                }
            }
            if(filtered.isEmpty()) item {
                Text(if(archive?.episodes.isNullOrEmpty()) "В локальной базе пока нет занятий." else "Нет занятий по выбранным фильтрам.")
                if(!archive?.episodes.isNullOrEmpty()) TextButton(onClick={period=0;sportName="ALL";query=""}) { Text("Сбросить фильтры") }
            }
            filtered.groupBy { it.primary.day }.forEach { (day,episodes) ->
                item(key="date_$day") {
                    val date=LocalDate.ofEpochDay(day)
                    SectionHeader(formatDayHeader(date,today)+if(date.year!=today.year) ", ${date.year}" else "")
                }
                items(episodes,key={it.episode.primary.key}) { row -> ArchiveCard(row) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArchiveCard(row: ArchiveEpisode) {
    var expanded by rememberSaveable(row.episode.primary.key) { mutableStateOf(false) }
    Card(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(row.primary.title,style=MaterialTheme.typography.titleMedium)
            Text(formatTime(row.primary.session.startMillis)+" · "+row.primary.session.sport.label(),
                style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                row.durationSeconds?.let { Text(secondsLabel(it)) }
                row.distanceMeters?.let { Text("${formatDecimal((it/1000).toFloat())} км") }
                row.averageHr?.let { Text("$it уд/мин") }
                if(row.sets.isNotEmpty()) Text("${row.sets.size} подходов")
            }
            Text(row.observations.map { it.session.source.label() }.distinct().joinToString(" · "),
                style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(row.episode.ambiguous) Text("Возможное совпадение с другой записью",style=MaterialTheme.typography.labelMedium,
                color=MaterialTheme.colorScheme.error)
            Text(if(expanded) "Свернуть" else "Подробнее",style=MaterialTheme.typography.labelLarge,
                color=MaterialTheme.colorScheme.primary)
            if(expanded) {
                row.maxHr?.let { StatRow("Максимальный пульс","$it уд/мин") }
                row.garmin?.let { g ->
                    if(g.activityTrainingLoad>0) StatRow("Нагрузка Garmin",formatDecimal(g.activityTrainingLoad))
                    g.zoneSeconds?.forEachIndexed { i,v -> StatRow("Зона Z${i+1}",secondsLabel(v.toLong())) }
                }
                row.sets.groupBy { it.exerciseName }.forEach { (exercise,sets) ->
                    Text(exercise,style=MaterialTheme.typography.titleSmall)
                    sets.sortedBy { it.setIndex }.forEach { s ->
                        Text("Подход ${s.setIndex}: ${formatDecimal(s.weightKg)} кг × ${s.reps}",
                            style=MaterialTheme.typography.bodyMedium)
                    }
                }
                if(row.episode.ambiguous) Text("Совпадений для проверки: ${row.episode.possibleDuplicateKeys.size}. Записи не объединены.",
                    style=MaterialTheme.typography.bodySmall)
                if(row.durationSeconds==null) Text("Длительность не записана",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}
