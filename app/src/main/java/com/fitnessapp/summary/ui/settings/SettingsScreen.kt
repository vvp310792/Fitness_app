package com.fitnessapp.summary.ui.settings

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fitnessapp.summary.BuildConfig
import com.fitnessapp.summary.FitnessSummaryApp
import com.fitnessapp.summary.export.DataExporter
import com.fitnessapp.summary.health.HealthConnectManager
import com.fitnessapp.summary.health.HealthSyncManager
import com.fitnessapp.summary.sync.FirebaseSetup
import com.fitnessapp.summary.ui.components.Chip
import com.fitnessapp.summary.ui.components.InfoCard
import com.fitnessapp.summary.ui.components.StatRow
import com.fitnessapp.summary.ui.theme.metricPalette
import com.fitnessapp.summary.update.ApkInstaller
import com.fitnessapp.summary.update.UpdateCheckResult
import com.fitnessapp.summary.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LAST_SYNC_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM, HH:mm")

@Composable
fun SettingsScreen(app: FitnessSummaryApp) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { HealthConnectSection(app) }
        item { SyncSection(app) }
        item { AccountSection(app) }
        item { ExportSection(app) }
        item { UpdateSection() }
        item { AboutSection() }
    }
}

@Composable
private fun HealthConnectSection(app: FitnessSummaryApp) {
    val context = LocalContext.current
    val palette = metricPalette()

    // Bumped whenever something might have changed permissions, to force a re-read.
    // The permission state lives in another app, so there's nothing to observe - it
    // can only be polled at the moments it plausibly changed.
    var refreshKey by remember { mutableIntStateOf(0) }
    var granted by remember { mutableStateOf<Set<String>>(emptySet()) }
    val availability = remember(refreshKey) { app.healthConnect.availability() }

    LaunchedEffect(refreshKey, availability) {
        granted = app.healthConnect.grantedPermissions()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = app.healthConnect.requestPermissionsContract()
    ) {
        refreshKey++
        // A fresh grant is only useful once something has been read with it.
        app.launchPersistent {
            if (app.healthConnect.grantedPermissions().isNotEmpty()) {
                app.healthSync.backfill()
            }
        }
    }

    InfoCard(title = "Health Connect") {
        Text(
            text = "Приложение только читает данные. Источник — Garmin Connect, который " +
                "пишет шаги, пульс, сон и тренировки в Health Connect.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        when (availability) {
            HealthConnectManager.Availability.NOT_INSTALLED -> {
                Text(
                    text = "Health Connect не найден на устройстве. На Android 13 и ниже его " +
                        "нужно установить отдельно, на Android 14+ он встроен в систему.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Button(
                    onClick = {
                        try {
                            context.startActivity(app.healthConnect.installIntent())
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(context, "Google Play недоступен", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("Установить Health Connect")
                }
            }

            HealthConnectManager.Availability.UPDATE_REQUIRED -> {
                Text(
                    text = "Health Connect установлен, но устарел — обновите его в Google Play.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Button(
                    onClick = {
                        try {
                            context.startActivity(app.healthConnect.installIntent())
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(context, "Google Play недоступен", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("Обновить")
                }
            }

            HealthConnectManager.Availability.AVAILABLE -> {
                val total = app.healthConnect.permissions.size
                Row(modifier = Modifier.padding(top = 10.dp)) {
                    Chip(
                        text = when {
                            granted.isEmpty() -> "Доступ не выдан"
                            granted.size < total -> "Выдано ${granted.size} из $total"
                            else -> "Доступ выдан полностью"
                        },
                        color = if (granted.size == total) palette.distance else palette.calories
                    )
                }

                if (granted.size < total) {
                    Button(
                        onClick = { permissionLauncher.launch(app.healthConnect.permissions) },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(if (granted.isEmpty()) "Разрешить чтение" else "Выдать остальные")
                    }
                    Text(
                        text = "Если окно с разрешениями больше не появляется, Android их " +
                            "заблокировал — выдайте доступ прямо в настройках Health Connect.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                TextButton(
                    onClick = {
                        try {
                            context.startActivity(app.healthConnect.settingsIntent())
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(
                                context,
                                "Не удалось открыть настройки Health Connect",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        refreshKey++
                    }
                ) {
                    Text("Открыть настройки Health Connect")
                }
            }
        }
    }
}

@Composable
private fun SyncSection(app: FitnessSummaryApp) {
    val syncState by app.healthSync.state.collectAsState()
    val palette = metricPalette()

    InfoCard(title = "Синхронизация") {
        val lastSync = app.healthSync.lastSyncMillis
        StatRow(
            label = "Последняя",
            value = if (lastSync > 0L) {
                LAST_SYNC_FORMAT.format(Instant.ofEpochMilli(lastSync).atZone(ZoneId.systemDefault()))
            } else {
                "ещё не было"
            }
        )

        when (val state = syncState) {
            is HealthSyncManager.State.Running -> {
                Text(
                    text = "Читаю день ${state.done + 1} из ${state.total}...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                LinearProgressIndicator(
                    progress = {
                        if (state.total == 0) 0f else state.done.toFloat() / state.total
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                )
            }

            is HealthSyncManager.State.Success -> {
                Text(
                    text = "Обновлено дней: ${state.daysWritten}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.distance,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            is HealthSyncManager.State.Failed -> {
                Text(
                    text = state.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            HealthSyncManager.State.Idle -> Unit
        }

        val running = syncState is HealthSyncManager.State.Running

        Row(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { app.launchPersistent { app.healthSync.syncRecent() } },
                enabled = !running
            ) {
                Text("Обновить")
            }
            OutlinedButton(
                onClick = { app.launchPersistent { app.healthSync.backfill() } },
                enabled = !running
            ) {
                Text("Загрузить историю")
            }
        }
        Text(
            text = "«Обновить» перечитывает последние ${HealthSyncManager.DEFAULT_RECENT_DAYS} дней — " +
                "часы нередко досылают вчерашние данные позже. «Загрузить историю» берёт " +
                "${HealthSyncManager.DEFAULT_BACKFILL_DAYS} дней назад.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun AccountSection(app: FitnessSummaryApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    InfoCard(title = "Аккаунт и облако") {
        if (!FirebaseSetup.isConfigured) {
            Text(
                text = "Облачная синхронизация не настроена: в репозитории лежит " +
                    "google-services.json-заглушка. Приложение полностью работает " +
                    "офлайн — данные хранятся на устройстве. Чтобы синхронизировать " +
                    "между устройствами, создайте проект Firebase (docs/SETUP_FIREBASE.md).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@InfoCard
        }

        val user by app.authManager.authStateFlow()
            .collectAsState(initial = app.authManager.currentUser)

        if (user == null) {
            Text(
                text = "Войдите, чтобы данные синхронизировались между устройствами.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    scope.launch {
                        val result = app.authManager.signInWithGoogle(context)
                        if (result.isFailure) {
                            Toast.makeText(
                                context,
                                "Не удалось войти: ${result.exceptionOrNull()?.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("Войти через Google")
            }
        } else {
            StatRow("Вы вошли как", user?.email ?: user?.uid.orEmpty())
            TextButton(onClick = { app.authManager.signOut() }) {
                Text("Выйти")
            }
        }
    }
}

@Composable
private fun ExportSection(app: FitnessSummaryApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }

    InfoCard(title = "Экспорт данных") {
        Text(
            text = "Выгружает все дни и тренировки в один JSON — удобно для анализа.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = {
                exporting = true
                scope.launch {
                    try {
                        val file = withContext(Dispatchers.IO) {
                            DataExporter.export(context, app.summaryRepository, app.workoutRepository)
                        }
                        context.startActivity(DataExporter.shareIntent(context, file))
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            "Не удалось выгрузить: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    } finally {
                        exporting = false
                    }
                }
            },
            enabled = !exporting,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text(if (exporting) "Готовлю..." else "Выгрузить JSON")
        }
    }
}

@Composable
private fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var downloading by remember { mutableStateOf(false) }

    InfoCard(title = "Обновления") {
        StatRow("Текущая версия", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")

        when (val current = result) {
            is UpdateCheckResult.UpToDate -> Text(
                text = "Установлена последняя версия.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )

            is UpdateCheckResult.Failed -> Text(
                text = current.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp)
            )

            is UpdateCheckResult.UpdateAvailable -> {
                Text(
                    text = "Доступна версия ${current.release.versionCode} — ${current.release.releaseName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Button(
                    onClick = {
                        if (!ApkInstaller.canRequestInstalls(context)) {
                            ApkInstaller.requestInstallPermission(context)
                            return@Button
                        }
                        downloading = true
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                ApkInstaller.download(context, current.release.downloadUrl)
                            }
                            downloading = false
                            if (file != null) {
                                ApkInstaller.install(context, file)
                            } else {
                                Toast.makeText(context, "Не удалось скачать APK", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !downloading,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(if (downloading) "Скачиваю..." else "Скачать и установить")
                }
            }

            null -> Unit
        }

        Row(modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(
                onClick = {
                    checking = true
                    scope.launch {
                        result = withContext(Dispatchers.IO) {
                            UpdateChecker.check(BuildConfig.VERSION_CODE)
                        }
                        checking = false
                    }
                },
                enabled = !checking
            ) {
                if (checking) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                }
                Text("Проверить обновления")
            }
        }
    }
}

@Composable
private fun AboutSection() {
    InfoCard(title = "О приложении") {
        Column {
            Text(
                text = "Данные берутся из Health Connect, куда их пишет Garmin Connect. " +
                    "Приложение не связано с Garmin и не использует Garmin Connect API.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Фирменные метрики Garmin — Body Battery, Stress, Training Load — " +
                    "в Health Connect не передаются, поэтому их здесь нет.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
