package com.fitnessapp.summary.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitnessapp.summary.FitnessSummaryApp
import com.fitnessapp.summary.sync.FirebaseServerSnapshotGateway
import com.fitnessapp.summary.sync.FirebaseSetup
import com.fitnessapp.summary.sync.ServerSnapshotDatabase
import com.fitnessapp.summary.sync.SnapshotProgress
import com.fitnessapp.summary.debug.AppLog
import com.fitnessapp.summary.sync.ServerSnapshotOverview
import com.fitnessapp.summary.ui.components.InfoCard
import com.fitnessapp.summary.ui.components.StatRow
import com.fitnessapp.summary.util.formatCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun phaseLabel(phase: String): String = when (phase) {
    "STATUS" -> "Читаем статус"
    "CATALOG_READ" -> "Загружаем каталог"
    "CATALOG_VERIFY" -> "Проверяем каталог"
    "LOCAL_REUSE" -> "Проверяем локальные блоки"
    "UNCHANGED" -> "Копия актуальна"
    "PART_READ" -> "Загружаем блоки"
    "PART_VERIFY" -> "Проверяем контрольные суммы"
    "LOCAL_WRITE" -> "Сохраняем на телефон"
    "FINAL_VERIFY" -> "Итоговая проверка"
    "ACTIVATE" -> "Активируем копию"
    else -> phase
}

private fun progressLabel(progress: SnapshotProgress): String =
    phaseLabel(progress.phase) + if (progress.partsTotal > 0)
        " · ${progress.partsDone}/${progress.partsTotal} блоков, ${formatCount(progress.rowsDone)}/${formatCount(progress.rowsTotal)} записей. Скачано: ${progress.downloadedParts}, повторно использовано: ${progress.reusedParts}"
    else "…"

private val dateFormat=DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm",Locale("ru"))
private fun snapshotTime(seconds: Long): String =
    dateFormat.format(Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()))

/** Experimental read-only server inspection. Never changes the legacy UI source.
 * UID-keyed state and reads prevent another account's shadow snapshot appearing. */
@Composable
fun ServerSnapshotPanel(app: FitnessSummaryApp) {
    InfoCard("Независимая база") {
        if(!FirebaseSetup.isConfigured) {
            Text("Облако не настроено. Локальный архив остаётся доступным.",
                style=MaterialTheme.typography.bodyMedium)
        } else {
            val user by remember(app) { app.authManager.authStateFlow() }
                .collectAsState(initial=app.authManager.currentUser)
            val uid=user?.uid
            if(uid==null) {
                Text("Войдите в аккаунт, чтобы проверить данные с ПК.",
                    style=MaterialTheme.typography.bodyMedium)
            } else key(uid) { OwnerSnapshotPanel(app,uid) }
        }
    }
}

@Composable
private fun OwnerSnapshotPanel(app: FitnessSummaryApp,uid: String) {
    val db=remember(app) { ServerSnapshotDatabase.getInstance(app) }
    val head by remember(db,uid) { db.snapshots().observeHead(uid) }.collectAsState(initial=null)
    val gateway=remember(uid) { FirebaseServerSnapshotGateway(uid) }
    val syncState by app.snapshotSync.state.collectAsState()
    val refresh = syncState?.takeIf { it.ownerUid == uid }
    val syncing = refresh?.running == true
    val scope=rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var remote by remember { mutableStateOf<ServerSnapshotOverview?>(null) }
    var note by remember { mutableStateOf("Автоматическая проверка при открытии и примерно раз в час при наличии сети.") }
    val workerRemote = refresh?.status?.let { s -> ServerSnapshotOverview(s.schemaVersion,s.manifestSetSha256,
        s.groups,s.records,s.publishedSeconds,s.publishedNanos) }
    val displayedRemote = listOfNotNull(remote,workerRemote).maxWithOrNull(
        compareBy<ServerSnapshotOverview> { it.publishedSeconds ?: 0L }.thenBy { it.publishedNanos ?: 0 })
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("На ПК",style=MaterialTheme.typography.titleSmall)
        displayedRemote?.let { r ->
            StatRow("Публикация",r.publishedSeconds?.let(::snapshotTime) ?: "Неизвестно")
            StatRow("Записей заявлено",r.records?.let(::formatCount) ?: "Неизвестно")
            val verified = head?.let { h -> h.manifestSetSha256 == r.manifestSetSha256 &&
                h.records == r.records && h.groups == r.groups } == true
            Text(when {
                !r.supportsVerifiedImport -> "Старый или неполный формат. Импорт заблокирован."
                verified -> "Содержимое сверено: проверенная копия актуальна."
                else -> "Формат поддерживается. Доступна новая серверная копия."
            },
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("На телефоне",style=MaterialTheme.typography.titleSmall)
        val local=head
        if(local==null) {
            Text("Проверенная серверная копия ещё не загружена.",
                style=MaterialTheme.typography.bodyMedium)
        } else {
            StatRow("Снимок ПК",snapshotTime(local.publishedSeconds))
            StatRow("Применён",snapshotTime(local.appliedAtMillis/1000))
            StatRow("Проверенных записей",formatCount(local.records))
        }
        Text("Проверенная копия дополняет локальный архив тренировок и данные Garmin на экранах «День», «Неделя» и «Тренды». Неподдержанные показатели остаются локальными.",
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(note,style=MaterialTheme.typography.bodySmall)
        when {
            syncing -> Text(refresh?.progress?.let(::progressLabel) ?: "Начинаем обновление…",style=MaterialTheme.typography.bodySmall)
            refresh?.error != null -> Text(refresh.error,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            refresh?.progress?.phase == "UNCHANGED" -> Text("Копия актуальна. Блоки не скачивались.",style=MaterialTheme.typography.bodySmall)
            refresh?.status != null -> Text("Проверенная копия обновлена. Скачано блоков: ${refresh.progress?.downloadedParts ?: 0}; повторно использовано: ${refresh.progress?.reusedParts ?: 0}.",style=MaterialTheme.typography.bodySmall)
        }
        Button(enabled=!busy && !syncing,modifier=Modifier.fillMaxWidth(),onClick={
            scope.launch {
                busy=true;note="Проверяем метаданные…"
                try {
                    val r=withContext(Dispatchers.IO) { gateway.overview() }
                    remote=r
                    note=if(r==null) "На сервере нет статуса публикации." else "Метаданные получены."
                } catch(e: TimeoutCancellationException) {
                    remote=null;note="Время проверки истекло. Локальная копия сохранена."
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) {
                    remote=null;note="Не удалось проверить сервер. Проверьте сеть и вход в аккаунт."
                } finally { busy=false }
            }
        }) { Text("Проверить сервер") }
        OutlinedButton(enabled=!busy && !syncing,modifier=Modifier.fillMaxWidth(),onClick={
            note="Обновление запланировано. При отсутствии сети оно запустится автоматически."
            app.snapshotSync.enqueueNow()
        }) { Text(if(head==null) "Загрузить проверенную копию" else "Обновить сейчас") }
    }
}
