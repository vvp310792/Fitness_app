package com.fitnessapp.summary.debug

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
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
 *   captured, and so the whole thing can be saved to Downloads as a file rather than a
 *   screenshot
 *
 * And a third, for warnings and errors only: an outbox file that
 * [com.fitnessapp.summary.sync.CloudLog] drains into Firestore, so the owner's PC scripts can
 * read failures without anyone exporting the log by hand. It is a file and not a call into
 * the uploader because the entries that matter most are the ones written when no upload can
 * happen - before sign-in, offline, or in the last moment before a crash.
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
        val detail: String? = null,
        /** Only travels to the cloud: the in-app viewer has no room for it, the PC has. */
        val stack: String? = null
    )

    private const val MAX_ENTRIES = 500
    private const val MAX_FILE_BYTES = 512 * 1024L

    /** A stack past this is the same frames of a coroutine machinery over and over. */
    private const val MAX_STACK_CHARS = 3_000

    /** Offline for weeks with a failing sync loop - keep the newest half, not everything. */
    private const val MAX_OUTBOX_BYTES = 1024 * 1024L

    private val buffer = CopyOnWriteArrayList<Entry>()
    private var logFile: File? = null
    private var outboxFile: File? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val writeLock = Any()
    private val outboxLock = Any()

    /** Nudged after every outbox append; set by the uploader once it exists. */
    @Volatile
    var onOutboxAppended: (() -> Unit)? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        logFile = File(dir, "app.log")
        outboxFile = File(dir, "cloud_outbox.jsonl")
        installCrashHandler()
        i("AppLog", "=== Приложение запущено ===")
    }

    /**
     * A crash is the one error the app never got to catch, so it was never logged at all.
     * Written synchronously to the outbox; the upload happens on the next start.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                e("Crash", "Приложение упало в потоке ${thread.name}", throwable)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
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
        val stack = throwable?.stackTraceToString()?.take(MAX_STACK_CHARS)
        val entry = Entry(System.currentTimeMillis(), level, tag, message, detail, stack)

        buffer.add(entry)
        while (buffer.size > MAX_ENTRIES) {
            buffer.removeAt(0)
        }

        appendToFile(entry)
        if (level == Level.WARN || level == Level.ERROR) appendToOutbox(entry)
    }

    /** One outbox line - a self-contained JSON object, so a line cut by a crash is just skipped. */
    fun outboxLine(entry: Entry): String = JSONObject()
        .put("t", entry.timestampMillis)
        .put("level", entry.level.name)
        .put("tag", entry.tag)
        .put("msg", entry.message)
        .put("detail", entry.detail ?: JSONObject.NULL)
        .put("stack", entry.stack ?: JSONObject.NULL)
        .toString()

    private fun appendToOutbox(entry: Entry) {
        val file = outboxFile ?: return
        try {
            synchronized(outboxLock) {
                if (file.exists() && file.length() > MAX_OUTBOX_BYTES) {
                    val lines = file.readLines()
                    file.writeText(lines.drop(lines.size / 2).joinToString("") { it + "\n" })
                }
                file.appendText(outboxLine(entry) + "\n")
            }
            onOutboxAppended?.invoke()
        } catch (e: Exception) {
            Log.e("AppLog", "Не удалось записать лог в очередь облака", e)
        }
    }

    /** Oldest waiting lines first; the caller removes them with [dropOutbox] once uploaded. */
    fun readOutbox(): List<String> = synchronized(outboxLock) {
        outboxFile?.takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() } ?: emptyList()
    }

    /**
     * Removes the uploaded lines by content, not by count: the cap in [appendToOutbox] may have
     * dropped the front of the file while the upload ran, and a count would then eat lines that
     * never left the phone.
     */
    fun dropOutbox(uploaded: Collection<String>) {
        if (uploaded.isEmpty()) return
        val gone = uploaded.toSet()
        synchronized(outboxLock) {
            val file = outboxFile ?: return
            if (!file.exists()) return
            val rest = file.readLines().filter { it.isNotBlank() && it !in gone }
            file.writeText(rest.joinToString("") { it + "\n" })
        }
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

    /** Name the log is saved under in Downloads - dated, so two reports don't look alike. */
    fun downloadFileName(): String = "fitness-summary-log_${LocalDate.now()}.txt"

    /** MIME type the saved copy carries in Downloads. */
    const val MIME_TYPE = "text/plain"

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
