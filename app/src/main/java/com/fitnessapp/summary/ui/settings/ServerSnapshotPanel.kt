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
import com.fitnessapp.summary.sync.RoomServerSnapshotSink
import com.fitnessapp.summary.sync.ServerSnapshotDatabase
import com.fitnessapp.summary.sync.ServerSnapshotImporter
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
    val importer=remember(db) { ServerSnapshotImporter(RoomServerSnapshotSink(db)) }
    val scope=rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var remote by remember { mutableStateOf<ServerSnapshotOverview?>(null) }
    var note by remember { mutableStateOf("Сервер ещё не проверен.") }
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("На ПК",style=MaterialTheme.typography.titleSmall)
        remote?.let { r ->
            StatRow("Публикация",r.publishedSeconds?.let(::snapshotTime) ?: "Неизвестно")
            StatRow("Записей заявлено",r.records?.let(::formatCount) ?: "Неизвестно")
            Text(if(r.supportsVerifiedImport) "Формат поддерживается. Содержимое ещё не сверено."
                else "Старый или неполный формат. Импорт заблокирован.",
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
        Button(enabled=!busy,modifier=Modifier.fillMaxWidth(),onClick={
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
        if(remote?.supportsVerifiedImport==true) {
            OutlinedButton(enabled=!busy,modifier=Modifier.fillMaxWidth(),onClick={
                scope.launch {
                    busy=true;note="Проверяем и загружаем части снимка…"
                    try {
                        withContext(Dispatchers.IO) { importer.refresh(uid,gateway) }
                        note="Проверенная копия применена. Поддерживаемые показатели доступны на обычных экранах."
                    } catch(e: TimeoutCancellationException) {
                        note="Время ожидания истекло. Предыдущая проверенная копия сохранена."
                    } catch(e: CancellationException) { throw e }
                    catch(e: Exception) {
                        note="Снимок не принят: ошибка проверки или загрузки. Предыдущая копия сохранена."
                    } finally { busy=false }
                }
            }) { Text("Загрузить проверенную копию") }
        }
    }
}
