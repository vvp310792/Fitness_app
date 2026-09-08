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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fitnessapp.summary.BuildConfig
import com.fitnessapp.summary.FitnessSummaryApp
import com.fitnessapp.summary.debug.AppLog
import com.fitnessapp.summary.export.DataExporter
import com.fitnessapp.summary.garmin.GarminLoginResult
import com.fitnessapp.summary.garmin.GarminSyncManager
import com.fitnessapp.summary.scale.ScaleSyncManager
import com.fitnessapp.summary.scale.ZeppLoginResult
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
import com.fitnessapp.summary.util.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
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
        item { GarminSection(app) }
        item { ZeppSection(app) }
        item { LogsSection() }
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
                            context.startActivity(app.healthConnect.settingsIntent(context))
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

private enum class GarminUiState { LOGGED_OUT, ENTERING_MFA, LOGGED_IN }

/**
 * Login for the unofficial Garmin Connect client (garmin/GarminAuthClient.kt) - the
 * only way to get Stress and Body Battery into this app, since Health Connect never
 * carries them (see CLAUDE.md). Deliberately upfront that this is NOT the official path:
 * the password only ever leaves the device once, to Garmin's own sign-in endpoint, and
 * nothing else - but this protocol is unsupported by Garmin and can break without notice
 * if they change it, unlike the Health Connect integration above.
 */
@Composable
private fun GarminSection(app: FitnessSummaryApp) {
    val scope = rememberCoroutineScope()
    var uiState by remember {
        mutableStateOf(if (app.garminAuth.isLoggedIn) GarminUiState.LOGGED_IN else GarminUiState.LOGGED_OUT)
    }
    var email by remember { mutableStateOf(app.garminAuth.savedEmail.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var mfaCode by remember { mutableStateOf("") }
    var mfaMethod by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val syncState by app.garminSync.state.collectAsState()

    InfoCard(title = "Garmin напрямую (неофициально)") {
        Text(
            text = "Собственные оценки Garmin — Sleep Score, ВСР, готовность к тренировке, " +
                "статус тренировок и VO2max, Body Battery, стресс по зонам, интенсивные " +
                "минуты, вес и Training Effect по тренировкам — в Health Connect не " +
                "передаются в принципе. Их можно получить только логином напрямую в Garmin " +
                "Connect тем же протоколом, что использует официальное приложение. Это не " +
                "поддерживается Garmin и может сломаться при их изменениях без " +
                "предупреждения. Пароль нигде не сохраняется - только при самом входе, " +
                "дальше используется токен.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        errorText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        when (uiState) {
            GarminUiState.LOGGED_OUT -> {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email Garmin") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Button(
                    onClick = {
                        errorText = null
                        busy = true
                        scope.launch {
                            when (val result = app.garminAuth.login(email.trim(), password)) {
                                is GarminLoginResult.Success -> {
                                    password = ""
                                    uiState = GarminUiState.LOGGED_IN
                                }
                                is GarminLoginResult.MfaRequired -> {
                                    mfaMethod = result.method
                                    uiState = GarminUiState.ENTERING_MFA
                                }
                                is GarminLoginResult.Failed -> {
                                    errorText = "Не удалось войти: ${result.reason}"
                                }
                            }
                            busy = false
                        }
                    },
                    enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    Text(if (busy) "Вхожу..." else "Войти в Garmin")
                }
            }

            GarminUiState.ENTERING_MFA -> {
                Text(
                    text = "Garmin прислал код подтверждения ($mfaMethod) - введите его:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 10.dp)
                )
                OutlinedTextField(
                    value = mfaCode,
                    onValueChange = { mfaCode = it },
                    label = { Text("Код") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Button(
                    onClick = {
                        errorText = null
                        busy = true
                        scope.launch {
                            when (val result = app.garminAuth.submitMfaCode(mfaCode.trim())) {
                                is GarminLoginResult.Success -> {
                                    mfaCode = ""
                                    uiState = GarminUiState.LOGGED_IN
                                }
                                is GarminLoginResult.Failed -> {
                                    errorText = "Код не подошёл: ${result.reason}"
                                }
                                is GarminLoginResult.MfaRequired -> {
                                    errorText = "Запросите код ещё раз"
                                }
                            }
                            busy = false
                        }
                    },
                    enabled = !busy && mfaCode.isNotBlank(),
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    Text(if (busy) "Проверяю..." else "Подтвердить")
                }
            }

            GarminUiState.LOGGED_IN -> {
                StatRow("Вошли как", app.garminAuth.savedEmail ?: "Garmin")

                when (val state = syncState) {
                    is GarminSyncManager.State.Running -> {
                        Text(
                            text = if (state.total > 0) {
                                "Читаю день ${state.done + 1} из ${state.total}..."
                            } else {
                                "Читаю историю, дней пройдено: ${state.done}..."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    is GarminSyncManager.State.Success -> {
                        Text(
                            text = buildString {
                                append("Обновлено дней: ${state.daysWritten}")
                                if (state.daysSkipped > 0) append(", пропущено уже загруженных: ${state.daysSkipped}")
                                append(" (${formatTime(state.atMillis)})")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = metricPalette().distance,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        // Which sections came back empty, and whether that was Garmin
                        // saying "nothing here" or a call that failed. Without this the
                        // only symptom of a dead endpoint is a screen that quietly lacks
                        // a card, which is indistinguishable from "you slept badly".
                        val quiet = state.sections.filter { it.stored == 0 && (it.noData > 0 || it.failed > 0) }
                        quiet.forEach { section ->
                            Text(
                                text = if (section.hasProblem) {
                                    "${section.label} — не удалось прочитать (${section.failed})"
                                } else {
                                    "${section.label} — Garmin не отдаёт эти данные для вашего аккаунта"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (section.hasProblem) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    is GarminSyncManager.State.Failed -> {
                        Text(
                            text = state.reason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    GarminSyncManager.State.Idle -> Unit
                }

                val running = syncState is GarminSyncManager.State.Running
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { app.launchPersistent { app.garminSync.syncRecent() } },
                        enabled = !running
                    ) {
                        Text("Синхронизировать")
                    }
                    OutlinedButton(
                        onClick = { app.launchPersistent { app.garminSync.syncAllHistory() } },
                        enabled = !running
                    ) {
                        Text("Вся история")
                    }
                }
                Text(
                    text = "«Вся история» идёт назад по 30 дней и останавливается там, где у Garmin " +
                        "кончаются данные — глубина не ограничена, дата начала аккаунта определяется " +
                        "сама. Уже загруженные дни повторно не запрашиваются, поэтому прерванная " +
                        "загрузка продолжается с того же места: можно просто нажать ещё раз. " +
                        "Последние дни перечитываются всегда — Garmin дописывает их задним числом.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                TextButton(
                    onClick = { app.launchPersistent { app.garminSync.forgetSyncMarks() } },
                    enabled = !running
                ) {
                    Text("Забыть отметки и перечитать всё")
                }
                TextButton(
                    onClick = {
                        app.garminAuth.logout()
                        uiState = GarminUiState.LOGGED_OUT
                        email = ""
                    }
                ) {
                    Text("Выйти")
                }
            }
        }
    }
}

/**
 * Surfaces AppLog (see debug/AppLog.kt) directly on the Я tab. Exists specifically so
 * "Garmin data doesn't come through" stops being a screenshot-and-guess conversation:
 * several read/write paths deliberately swallow exceptions rather than crash (a denied
 * Health Connect permission, a Firestore write rejected by the rules), and this is
 * where those now actually get recorded. "Поделиться логами" hands over the whole file.
 */
@Composable
private fun LogsSection() {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    val entries = remember(refreshKey) { AppLog.recentEntries().take(20) }

    InfoCard(title = "Логи") {
        Text(
            text = "Что реально произошло при последней синхронизации Health Connect и " +
                "Firestore. Если какие-то данные Garmin не приходят - нажми «Поделиться " +
                "логами» и пришли файл, по нему видна точная причина, а не только симптом.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (entries.isEmpty()) {
            Text(
                text = "Пока пусто - выполните синхронизацию, чтобы здесь что-то появилось.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                entries.forEach { entry -> LogEntryLine(entry) }
            }
        }

        Row(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { refreshKey++ }) {
                Text("Обновить")
            }
            Button(
                onClick = {
                    val intent = AppLog.shareIntent(context)
                    if (intent == null) {
                        Toast.makeText(context, "Логов пока нет", Toast.LENGTH_SHORT).show()
                    } else {
                        context.startActivity(intent)
                    }
                }
            ) {
                Text("Поделиться логами")
            }
            TextButton(
                onClick = {
                    AppLog.clear()
                    refreshKey++
                }
            ) {
                Text("Очистить")
            }
        }
    }
}

@Composable
private fun LogEntryLine(entry: AppLog.Entry) {
    val palette = metricPalette()
    val color = when (entry.level) {
        AppLog.Level.ERROR -> MaterialTheme.colorScheme.error
        AppLog.Level.WARN -> palette.calories
        AppLog.Level.INFO -> MaterialTheme.colorScheme.onSurface
        AppLog.Level.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = "${formatTime(entry.timestampMillis)}  [${entry.tag}] ${entry.message}" +
            (entry.detail?.let { " — $it" } ?: ""),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 2,
        modifier = Modifier.padding(vertical = 2.dp)
    )
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
            text = "Выгружает все дни, тренировки и все данные Garmin (сон, ВСР, готовность, " +
                "статус тренировок, вес, стресс) в один JSON — удобно для анализа.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = {
                exporting = true
                scope.launch {
                    try {
                        val file = withContext(Dispatchers.IO) {
                            DataExporter.export(context, app.summaryRepository, app.workoutRepository, app.database)
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

private enum class ZeppUiState { LOGGED_OUT, LOGGED_IN }

/**
 * Login for the Mi Body Composition Scale via the Zepp Life cloud (scale/ZeppAuthClient.kt)
 * and the switch for the one thing this app writes to Garmin - pushing those weigh-ins in.
 * Two facts the user must know before pressing "Войти", both stated on the card: logging
 * in here signs the phone's Zepp Life app out (Xiaomi keeps one session per app), and
 * a Xiaomi account with 2FA or a captcha challenge cannot be logged into from here.
 */
@Composable
private fun ZeppSection(app: FitnessSummaryApp) {
    val scope = rememberCoroutineScope()
    var uiState by remember {
        mutableStateOf(if (app.zeppAuth.isLoggedIn) ZeppUiState.LOGGED_IN else ZeppUiState.LOGGED_OUT)
    }
    var account by remember { mutableStateOf(app.zeppAuth.savedAccount.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var uploadToGarmin by remember { mutableStateOf(app.zeppTokenStore.uploadToGarmin) }
    val syncState by app.scaleSync.state.collectAsState()
    val count by remember { app.database.scaleMeasurementDao().observeCount() }.collectAsState(initial = 0)

    InfoCard(title = "Весы Mi (Zepp Life)") {
        Text(
            text = "Взвешивания Mi Body Composition Scale читаются из облака Zepp Life по " +
                "аккаунту Xiaomi — тем же способом, что и приложение Zepp Life. Оттуда они " +
                "попадают на экран «День» и в «Тренды», а при включённом переключателе ниже — " +
                "отправляются в Garmin Connect (единственное, что это приложение пишет в Garmin). " +
                "Внимание: вход здесь разлогинит Zepp Life на телефоне — Xiaomi держит одну " +
                "сессию на приложение. Аккаунт с двухфакторной защитой войти отсюда не сможет.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        errorText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        when (uiState) {
            ZeppUiState.LOGGED_OUT -> {
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it },
                    label = { Text("Аккаунт Xiaomi (email или телефон)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль Xiaomi") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Button(
                    onClick = {
                        errorText = null
                        busy = true
                        scope.launch {
                            when (val result = app.zeppAuth.login(account.trim(), password)) {
                                is ZeppLoginResult.Success -> {
                                    password = ""
                                    uiState = ZeppUiState.LOGGED_IN
                                    app.launchPersistent { app.scaleSync.sync() }
                                }
                                is ZeppLoginResult.Failed -> errorText = "Не удалось войти: ${result.reason}"
                            }
                            busy = false
                        }
                    },
                    enabled = !busy && account.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    Text(if (busy) "Вхожу..." else "Войти через Xiaomi")
                }
            }

            ZeppUiState.LOGGED_IN -> {
                StatRow("Аккаунт", app.zeppAuth.savedAccount ?: "Xiaomi")
                StatRow("Взвешиваний загружено", count.toString())

                when (val state = syncState) {
                    is ScaleSyncManager.State.Running -> Text(
                        text = state.step,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    is ScaleSyncManager.State.Success -> Text(
                        text = buildString {
                            append("Новых взвешиваний: ${state.newMeasurements}")
                            if (state.uploadedToGarmin > 0) append(", отправлено в Garmin: ${state.uploadedToGarmin}")
                            if (state.pendingUpload > 0) append(", ждут отправки: ${state.pendingUpload}")
                            append(" (${formatTime(state.atMillis)})")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = metricPalette().distance,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    is ScaleSyncManager.State.Failed -> Text(
                        text = state.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    ScaleSyncManager.State.Idle -> Unit
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        text = "Отправлять вес в Garmin",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Switch(
                        checked = uploadToGarmin,
                        onCheckedChange = {
                            uploadToGarmin = it
                            app.zeppTokenStore.uploadToGarmin = it
                        }
                    )
                }
                if (uploadToGarmin && !app.garminAuth.isLoggedIn) {
                    Text(
                        text = "Для отправки нужен вход в Garmin — секция выше.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                val running = syncState is ScaleSyncManager.State.Running
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { app.launchPersistent { app.scaleSync.sync() } },
                        enabled = !running
                    ) {
                        Text("Синхронизировать")
                    }
                    TextButton(
                        onClick = {
                            app.zeppAuth.logout()
                            uiState = ZeppUiState.LOGGED_OUT
                            account = ""
                        }
                    ) {
                        Text("Выйти")
                    }
                }
            }
        }
    }
}
