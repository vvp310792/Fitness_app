package com.fitnessapp.summary.sync

import android.content.Context
import android.os.Build
import com.fitnessapp.summary.BuildConfig
import com.fitnessapp.summary.debug.AppLog
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Drains [AppLog]'s outbox of warnings and errors into `users/{uid}/logs/{id}`, so the owner's
 * PC scripts read failures straight from the cloud instead of waiting for a log export.
 *
 * One document per batch, not per entry, and at most one batch per [THROTTLE_MILLIS]: a sync
 * loop that fails every second would otherwise spend the daily write quota the mirror needs.
 * The throttle does not restart on every new entry - a steady trickle must still go out.
 *
 * Lines leave the outbox only after the write is acknowledged, so a batch written while offline
 * or cut by a kill is sent again. The id is derived from the batch content, which makes that
 * resend overwrite the same document instead of duplicating it.
 */
class CloudLog(context: Context) {

    private val db = FirebaseFirestore.getInstance()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val flushLock = Mutex()

    @Volatile
    private var uid: String? = null
    private var pending: Job? = null

    /** Tells two phones of one account apart - the model alone does not. */
    private val installId: String by lazy {
        prefs.getString(KEY_INSTALL, null) ?: ByteArray(3).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
            .also { prefs.edit().putString(KEY_INSTALL, it).apply() }
    }

    @Synchronized
    fun start(uid: String) {
        if (this.uid == uid) return
        this.uid = uid
        AppLog.onOutboxAppended = { schedule(0) }
        // Whatever piled up before sign-in - including a crash from the last run - goes now.
        schedule(0)
    }

    @Synchronized
    fun stop() {
        AppLog.onOutboxAppended = null
        pending?.cancel()
        pending = null
        uid = null
    }

    @Synchronized
    private fun schedule(extraDelay: Long) {
        if (pending?.isActive == true) return
        pending = scope.launch {
            delay(FIRST_DELAY_MILLIS + extraDelay)
            val sent = flush()
            // Read the outbox again rather than trust the batch: lines appended during the write
            // found this job still active and did not schedule one of their own. After a failure
            // the next append (or sign-in) retries - looping here would just fail again.
            if (sent && AppLog.readOutbox().isNotEmpty()) {
                synchronized(this@CloudLog) { pending = null }
                schedule(THROTTLE_MILLIS)
            }
        }
    }

    /** True when the batch went out (or there was nothing to send). */
    private suspend fun flush(): Boolean = flushLock.withLock {
        val uid = uid ?: return false
        val lines = AppLog.readOutbox()
        if (lines.isEmpty()) return true
        val batch = takeBatch(lines, MAX_BATCH_BYTES)
        val entries = batch.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        try {
            if (entries.isNotEmpty()) {
                db.collection("users").document(uid).collection("logs")
                    .document(docId(entries.first().optLong("t"), installId, batch))
                    .set(
                        mapOf(
                            "installId" to installId,
                            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                            "sdk" to Build.VERSION.SDK_INT,
                            "appVersion" to BuildConfig.VERSION_NAME,
                            "versionCode" to BuildConfig.VERSION_CODE,
                            "count" to entries.size,
                            "errors" to entries.count { it.optString("level") == "ERROR" },
                            "firstAt" to entries.first().optLong("t"),
                            "lastAt" to entries.last().optLong("t"),
                            "entriesJson" to entries.joinToString(",", "[", "]"),
                            "createdAt" to FieldValue.serverTimestamp(),
                        )
                    ).await()
            }
            // Unparseable lines (cut by a crash mid-write) are dropped with the batch.
            AppLog.dropOutbox(batch)
            true
        } catch (e: Exception) {
            // INFO on purpose: a WARN would land in the outbox and schedule the next failure.
            AppLog.i(TAG, "Логи в облако не ушли, повторю позже: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "CloudLog"
        private const val PREFS = "cloud_log"
        private const val KEY_INSTALL = "installId"

        /** Lets an error burst (one failure logged by three layers) leave as one document. */
        private const val FIRST_DELAY_MILLIS = 5_000L
        private const val THROTTLE_MILLIS = 30_000L

        /** Well under Firestore's 1 MiB document limit, with room for the other fields. */
        const val MAX_BATCH_BYTES = 400_000

        private val ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC)

        /** Oldest lines first, as many as fit; a single oversized line still goes alone. */
        fun takeBatch(lines: List<String>, maxBytes: Int): List<String> {
            val out = mutableListOf<String>()
            var size = 2
            for (line in lines) {
                val bytes = line.toByteArray().size + 1
                if (out.isNotEmpty() && size + bytes > maxBytes) break
                out += line
                size += bytes
            }
            return out
        }

        /** Sorts by time on the reader's side; same batch, same id. */
        fun docId(firstMillis: Long, installId: String, batch: List<String>): String =
            ID_TIME.format(Instant.ofEpochMilli(firstMillis)) + "-" + installId + "-" +
                MirrorLayout.sha256(batch.joinToString("\n")).take(8)
    }
}
