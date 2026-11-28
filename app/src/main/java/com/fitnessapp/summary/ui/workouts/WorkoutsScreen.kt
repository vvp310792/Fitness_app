package com.fitnessapp.summary.ui.workouts

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.fitnessapp.summary.ui.components.pickDate
import com.fitnessapp.summary.ui.components.rememberCurrentDate
import com.fitnessapp.summary.ui.components.readScreen
import com.fitnessapp.summary.ui.components.ReadStatus
import com.fitnessapp.summary.analytics.WorkoutPeriod
import com.fitnessapp.summary.analytics.WorkoutDisplayGroup
import com.fitnessapp.summary.util.formatDayMonth

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

/** Unified archive with source-aware filters. Review candidates are grouped
 * for display, without falsely declaring them one workout. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkoutsScreen(app: FitnessSummaryApp) {
    val today=rememberCurrentDate()
    val context=LocalContext.current
    val stream=remember(app){app.unifiedRead.observeArchive()}
    var retry by remember{mutableStateOf(0)}
    val archiveRead=readScreen(stream,retry)
    val archive=archiveRead.value
    var sportName by rememberSaveable { mutableStateOf("ALL") }
    var period by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var fromDay by rememberSaveable{mutableStateOf(today.minusDays(27).toEpochDay())}
    var toDay by rememberSaveable{mutableStateOf(today.toEpochDay())}
    var source by rememberSaveable{mutableStateOf("ALL")}
    var status by rememberSaveable{mutableStateOf("ALL")}
    var oldestFirst by rememberSaveable{mutableStateOf(false)}
    var showFilters by rememberSaveable{mutableStateOf(false)}
    var showDetails by rememberSaveable{mutableStateOf(false)}
    fun reset(){sportName="ALL";period=0;query="";source="ALL";status="ALL";oldestFirst=false}
    val activeFilters=listOf(sportName!="ALL",period!=0,source!="ALL",status!="ALL",oldestFirst).count{it}
    val filtered=remember(archive,sportName,period,query,today,fromDay,toDay,source,status) {
        archive?.episodes.orEmpty().filter { e ->
            (sportName=="ALL" || e.primary.session.sport.name==sportName) &&
            (when {period==0->true;period==-1->e.primary.day in fromDay..toDay;else->e.primary.day>=today.minusDays(period.toLong()-1).toEpochDay()}) &&
            (source=="ALL" || e.observations.any{it.session.source.name==source}) &&
            (when(status){"review"->e.countable && e.episode.ambiguous;"probable"->e.probable;"short"->!e.countable;"countable"->e.countable && !e.episode.ambiguous;else->true}) &&
            (query.isBlank() || e.observations.any { it.title.contains(query.trim(),true) } ||
                e.sets.any { it.exerciseName.contains(query.trim(),true) })
        }
    }
    val totals=remember(filtered) { WorkoutPeriod(filtered) }

    LazyColumn(modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {
            Text("Тренировки",style=MaterialTheme.typography.headlineMedium)
            Text(if ((archive?.serverRawCount ?: 0)>0)
                "Единый архив • проверенная серверная копия + локальная база"
                else "Локальный архив • загрузите проверенную копию в разделе «Я»",
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            OutlinedTextField(value=query,onValueChange={query=it},singleLine=true,
                label={Text("Поиск занятий и упражнений")},modifier=Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={showFilters=!showFilters},modifier=Modifier.weight(1f)) {
                    Text(if(showFilters)"Скрыть фильтры" else "Фильтры"+if(activeFilters>0)" ($activeFilters)" else "")
                }
                if(activeFilters>0 || query.isNotBlank())TextButton(onClick={reset()}){Text("Сбросить")}
            }
            if(showFilters) {
                Text("Период",style=MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Всё время",28 to "4 недели",84 to "12 недель",365 to "Год",-1 to "Даты").forEach{(days,label)->
                        FilterChip(selected=period==days,onClick={period=days},label={Text(label)})
                    }
                }
                if(period==-1) {
                    OutlinedButton(onClick={pickDate(context,LocalDate.ofEpochDay(fromDay),today){d->fromDay=d.toEpochDay();if(toDay<fromDay)toDay=fromDay}},modifier=Modifier.fillMaxWidth()){Text("С "+LocalDate.ofEpochDay(fromDay))}
                    OutlinedButton(onClick={pickDate(context,LocalDate.ofEpochDay(toDay),today){d->toDay=d.toEpochDay();if(fromDay>toDay)fromDay=toDay}},modifier=Modifier.fillMaxWidth()){Text("По "+LocalDate.ofEpochDay(toDay))}
                }
                Text("Вид спорта",style=MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected=sportName=="ALL",onClick={sportName="ALL"},label={Text("Все виды")})
                    FitnessSport.entries.forEach { sport -> FilterChip(selected=sportName==sport.name,onClick={sportName=sport.name},label={Text(sport.label())}) }
                }
                Text("Источник",style=MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected=source=="ALL",onClick={source="ALL"},label={Text("Все")})
                    FitnessSource.entries.filter{it in setOf(FitnessSource.GARMIN,FitnessSource.GYMUP,FitnessSource.STRAVA,FitnessSource.HEALTH_CONNECT,FitnessSource.GOOGLE_HEALTH,FitnessSource.GOOGLE_FIT)}.forEach{src->
                        FilterChip(selected=source==src.name,onClick={source=src.name},label={Text(src.label())})
                    }
                }
                Text("Статус",style=MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    listOf("ALL" to "Все","countable" to "Учтённые","review" to "Для сверки","probable" to "Вероятные","short" to "Короткие").forEach{(v,label)->
                        FilterChip(selected=status==v,onClick={status=v},label={Text(label)})
                    }
                }
                FilterChip(selected=oldestFirst,onClick={oldestFirst=!oldestFirst},label={Text(if(oldestFirst)"Сначала старые" else "Сначала новые")})
            }
        }
        if(archive==null) {
            item { ReadStatus(listOf(archiveRead),{retry++}) }
        } else {
            item {
                InfoCard("За выбранный период") {
                    WorkoutTotals(totals,detailed=false)
                    TextButton(onClick={showDetails=!showDetails}){Text(if(showDetails)"Скрыть сведения о сверке" else "Подробнее о сверке")}
                    if(showDetails) {
                    if(archive?.canonicalStored==true) Text("Архив объединён в локальной базе.",style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    else Text("Каноническая база не подтверждена; показан резервный архив.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                    val server=archive
                    if (server!=null && server.serverRawCount>0) {
                        StatRow("Записей тренировок с ПК",formatCount(server.serverRawCount))
                        StatRow("Распознано с ПК",formatCount(server.serverMappedCount))
                        Text("Сверка всего архива",style=MaterialTheme.typography.titleSmall)
                        StatRow("Совпадений ID",formatCount(server.exactIdentityMatches))
                        StatRow("Объединено исходных наблюдений",formatCount(server.confirmedDuplicateObservations))
                        if(server.reviewedPolicyAvailable) {
                            StatRow("Решений сверки с ПК применено",formatCount(server.reviewedPolicyApplied))
                            if(server.reviewedPolicySkipped>0) Text("Не применено: ${server.reviewedPolicySkipped}. Исходные данные отличаются или ещё не загружены.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                        }
                        if(server.serverEmptyGymSessions>0) Text("Пустые планы GymUp: ${server.serverEmptyGymSessions}. Не считаются тренировками.",style=MaterialTheme.typography.bodySmall)
                        if(server.serverRejected>0 || server.serverIdentityConflicts>0) Text(
                            "Не распознано: ${server.serverRejected}; конфликтов ID: ${server.serverIdentityConflicts}. Исходники сохранены.",
                            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                    }
                    val rejected=archive?.rejectedObservations ?: 0
                    if(rejected>0) Text("Некорректных или повторных локальных идентификаторов: $rejected. Исходные записи сохранены.",
                        style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if(filtered.isEmpty()) item {
                Text(if(archive?.episodes.isNullOrEmpty()) "В доступном архиве пока нет занятий." else "Нет занятий по выбранным фильтрам.")
                if(!archive?.episodes.isNullOrEmpty()) TextButton(onClick={reset()}) { Text("Сбросить фильтры") }
            }
            val ordered=if(oldestFirst)totals.displayGroups.sortedBy{it.latest.startMillis} else totals.displayGroups
            ordered.groupBy { it.day }.forEach { (day,groups) ->
                item(key="date_$day") {
                    val date=LocalDate.ofEpochDay(day)
                    SectionHeader(formatDayHeader(date,today)+if(date.year!=today.year) ", ${date.year}" else "")
                }
                items(groups,key={it.key}) { group -> WorkoutGroupCard(group) }
            }
        }
    }
}

/** Same confirmed totals for Day, Week, Trends and Workouts. */
@Composable
fun WorkoutTotals(period:WorkoutPeriod,detailed:Boolean=true) {
    StatRow("Учтено занятий",formatCount(period.confirmed.size))
    if(period.probableCount>0) StatRow("Из них вероятные объединения",formatCount(period.probableCount))
    if(period.shortRecordCount>0) StatRow("Коротких записей вне статистики",formatCount(period.shortRecordCount))
    if(detailed)StatRow("Исходных наблюдений",formatCount(period.accountedObservationCount))
    if(period.ambiguousCount>0) {
        StatRow("Групп для сверки",formatCount(period.reviewGroupCount))
        if(detailed){
            StatRow("Эпизодов-кандидатов",formatCount(period.ambiguousCount))
            StatRow("Наблюдений в этих группах",formatCount(period.reviewObservationCount))
        }
    }
    StatRow("Записанное время",if(period.knownDurationCount==0) "Нет данных" else secondsLabel(period.durationSeconds))
    if(detailed || period.knownDurationCount<period.confirmed.size)Text("Длительность известна для ${period.knownDurationCount} из ${period.confirmed.size} учтённых занятий.",
        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    StatRow("Дистанция",if(period.knownDistanceCount==0) "Нет данных" else "${formatDecimal((period.distanceMeters/1000).toFloat())} км")
    if(detailed)Text("Дистанция известна для ${period.knownDistanceCount} из ${period.confirmed.size} учтённых занятий.",
        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(period.ambiguousCount>0 && detailed) Text("Суммы не включают группы с неопределённым числом занятий. Все кандидаты и их показатели доступны для сверки; их время и дистанция не складываются.",
        style=MaterialTheme.typography.bodySmall)
}

@Composable
fun WorkoutGroupCard(group: WorkoutDisplayGroup, showDate: Boolean = false) {
    if(!group.requiresReview) {
        ArchiveCard(group.members.single(),showDate)
        return
    }
    var expanded by rememberSaveable(group.key) { mutableStateOf(false) }
    Card(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Группа для сверки",style=MaterialTheme.typography.titleMedium)
            Text(group.members.map { it.primary.session.sport.label() }.distinct().joinToString(" · "),
                style=MaterialTheme.typography.labelMedium)
            Text("${group.members.size} эпизодов-кандидатов · ${group.observationCount} наблюдений",
                style=MaterialTheme.typography.bodySmall)
            Text(group.members.joinToString(" · ") {
                formatDayMonth(LocalDate.ofEpochDay(it.primary.day))+" "+formatTime(it.startMillis)
            },style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Не подтверждено, что это одно занятие. Исходные показатели сохранены.",
                color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            Text(if(expanded) "Свернуть" else "Посмотреть все записи",
                color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge)
            if(expanded) {
                group.members.forEach { member -> ArchiveCard(member,showDate=true) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArchiveCard(row: ArchiveEpisode, showDate: Boolean = false) {
    var expanded by rememberSaveable(row.episode.primary.key) { mutableStateOf(false) }
    Card(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(row.primary.session.sport.label()+" · "+row.primary.title,style=MaterialTheme.typography.titleMedium)
            Text((if(showDate) formatDayMonth(LocalDate.ofEpochDay(row.primary.day))+" · " else "")+
                formatTime(row.startMillis)+" · "+row.primary.session.sport.label(),
                style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                row.durationSeconds?.let { Text(secondsLabel(it)) }
                row.distanceMeters?.let { Text("${formatDecimal((it/1000).toFloat())} км") }
                row.averageHr?.let { Text("$it уд/мин") }
                if(row.sets.isNotEmpty()) Text("${row.sets.size} подходов")
            }
            Text(row.observations.map { it.session.source.label() }.distinct().joinToString(" · "),
                style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(row.episode.fragmentKeys.isNotEmpty()) Text("Вложенные записи: "+row.episode.fragmentKeys.size,
                style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(!row.countable) Text("Короткая запись · не входит в статистику тренировок",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            else if(row.probable) Text("Вероятное объединение · решение сверки ПК",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
            else if(row.resolution?.composite!=null) Text("Составное занятие · последовательные измеренные интервалы",style=MaterialTheme.typography.labelMedium)
            if(row.countable && row.episode.ambiguous) Text("Возможное совпадение с другой записью",style=MaterialTheme.typography.labelMedium,
                color=MaterialTheme.colorScheme.error)
            Text(if(expanded) "Свернуть" else "Подробнее",style=MaterialTheme.typography.labelLarge,
                color=MaterialTheme.colorScheme.primary)
            if(expanded) {
                row.maxHr?.let { StatRow("Максимальный пульс","$it уд/мин") }
                if(row.episode.fragmentKeys.isNotEmpty()) {
                    Text("Вложенные записи не увеличивают время, дистанцию и число занятий.",style=MaterialTheme.typography.bodySmall)
                    row.observations.filter { it.session.key in row.episode.fragmentKeys }.forEach { fragment ->
                        StatRow("Вложенная запись · "+fragment.session.source.label(),formatTime(fragment.session.startMillis)+
                            (fragment.trainingSeconds?.let { " · "+secondsLabel(it) } ?: ""))
                    }
                }
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
                if(row.countable && row.episode.ambiguous) Text("Совпадений для проверки: ${row.episode.possibleDuplicateKeys.size}. Записи не объединены.",
                    style=MaterialTheme.typography.bodySmall)
                if(row.durationSeconds==null) Text("Длительность не записана",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}
