package com.fitnessapp.summary.debug

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app's own diagnostic log, separate from (and in addition to) logcat.
 *
 * Why this exists at all: several places in this codebase deliberately swallow
 * exceptions rather than crash the app - [com.fitnessapp.summary.health.HealthConnectReader]
 * catches per-section so one denied permission doesn't blank the whole day, and
 * [com.fitnessapp.summary.sync.FirestoreSyncManager]'s writes previously had no failure
 * listener at all. That's the right behaviour for the user (a partial read beats a crash),
 * but it means a real failure - the actual reason some Garmin data "doesn't come through" -
 * had nowhere to go. This does not change what's swallowed; it makes sure every swallow is
 * recorded somewhere a person can read it and hand to me.
 *
 * Two stores, deliberately:
 * - an in-memory ring buffer (last [MAX_ENTRIES]) for the in-app log viewer - instant, no I/O
 * - a capped file under [Context.getFilesDir] for [export], so a sync that ran in the
 *   background before anyone opened the app (see MainActivity's launch-time sync) is still
 *   captured, and so the whole thing can be shared as a file rather than a screenshot
 *
 * Deliberately metadata-only in what it logs: record types, counts, exception messages,
 * permission names, day identifiers - never the actual health values (step counts, heart
 * rates, sleep minutes). The point is diagnosing *why something is missing*, which never
 * requires knowing what the number would have been.
 */
object AppLog {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val timestampMillis: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val detail: String? = null
    )

    private const val MAX_ENTRIES = 500
    private const val MAX_FILE_BYTES = 512 * 1024L

    private val buffer = CopyOnWriteArrayList<Entry>()
    private var logFile: File? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val writeLock = Any()

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        logFile = File(dir, "app.log")
        i("AppLog", "=== Приложение запущено ===")
    }

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    fun w(tag: String, message: String, throwable: Throwable? = null) =
        log(Level.WARN, tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) =
        log(Level.ERROR, tag, message, throwable)

    private fun log(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        when (level) {
            Level.DEBUG -> Log.d(tag, message, throwable)
            Level.INFO -> Log.i(tag, message, throwable)
            Level.WARN -> Log.w(tag, message, throwable)
            Level.ERROR -> Log.e(tag, message, throwable)
        }

        val detail = throwable?.let { "${it.javaClass.simpleName}: ${it.message}" }
        val entry = Entry(System.currentTimeMillis(), level, tag, message, detail)

        buffer.add(entry)
        while (buffer.size > MAX_ENTRIES) {
            buffer.removeAt(0)
        }

        appendToFile(entry)
    }

    /** Most recent entries first - what the in-app log viewer shows. */
    fun recentEntries(): List<Entry> = buffer.asReversed()

    fun clear() {
        buffer.clear()
        synchronized(writeLock) {
            logFile?.writeText("")
        }
        i("AppLog", "=== Логи очищены ===")
    }

    fun logFileOrNull(): File? = logFile?.takeIf { it.exists() }

    /** Null when there's nothing to share yet - same shape as [com.fitnessapp.summary.export.DataExporter.shareIntent]. */
    fun shareIntent(context: Context): Intent? {
        val file = logFileOrNull() ?: return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Логи Фитнес-сводки")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Поделиться логами")
    }

    private fun appendToFile(entry: Entry) {
        val file = logFile ?: return
        synchronized(writeLock) {
            try {
                // Cheap size cap: once the file gets large, just start it over rather than
                // trimming from the front (which would mean rewriting the whole thing on
                // every line) - this is a debugging aid, not an audit trail, so losing old
                // history when it gets big is an acceptable trade for staying simple.
                if (file.exists() && file.length() > MAX_FILE_BYTES) {
                    file.writeText("")
                }
                val time = timeFormat.format(entry.timestampMillis)
                val line = buildString {
                    append(time).append(' ').append(entry.level.name.first()).append(' ')
                    append('[').append(entry.tag).append("] ")
                    append(entry.message)
                    if (entry.detail != null) append(" - ").append(entry.detail)
                    append('\n')
                }
                file.appendText(line)
            } catch (e: Exception) {
                // Logging must never be why something else crashes.
                Log.e("AppLog", "Не удалось записать лог в файл", e)
            }
        }
    }
}
