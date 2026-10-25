package com.fitnessapp.summary.sync

import android.content.Context
import android.database.Cursor
import androidx.room.InvalidationTracker
import com.fitnessapp.summary.data.AppDatabase
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import com.google.android.gms.tasks.Task
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only copy of the whole Room database in Firestore, under
 * `users/{uid}/mirror/{table}__{YYYY-MM}[__pN]`, for the owner's scripts on a PC.
 *
 * The phone is the only writer, and nothing here is ever read back into Room - phones keep
 * syncing each other through [FirestoreSyncManager] alone. Changes travel the other way only
 * through the reviewed queue in [InboxApplier].
 *
 * Packing and the change test live in [MirrorLayout]; what is here is the device side: reading
 * every table straight off SQLite (so a table added by a migration is mirrored without a line
 * of code here), and remembering what was already uploaded so a quiet day costs a handful of
 * writes, not the whole history again.
 *
 * Two documents next to the months: `mirror_meta/schema` (columns and keys of every table, so
 * the reader can rebuild the tables with the same types) and `mirror_meta/manifest` (every
 * live document id with its hash, so the reader can tell a complete copy from a half-written
 * one).
 */
class CloudMirror(context: Context, private val database: AppDatabase) {

    private val db = FirebaseFirestore.getInstance()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO)

    /** One upload at a time: two passes over the same month would race each other's hashes. */
    private val pushLock = Mutex()

    private var observer: InvalidationTracker.Observer? = null
    private var startJob: Job? = null
    private var pending: Job? = null
    private val dirty = mutableSetOf<String>()
    private var uid: String? = null

    /**
     * Uploads everything that changed since the last upload, then keeps watching Room and
     * re-uploads the touched tables after [DEBOUNCE_MILLIS] of quiet - a Garmin backfill
     * writes hundreds of rows a minute, and a write per row would eat the daily quota.
     */
    @Synchronized
    fun start(uid: String) {
        if (this.uid == uid) return
        stop()
        this.uid = uid
        startJob = scope.launch {
            // Observer goes first: rows written during the long first upload still get a pass.
            // Table names come from sqlite_master because the Observer constructor throws on a
            // name Room does not know.
            val tables = mirroredTables().toTypedArray()
            if (tables.isNotEmpty()) {
                val obs = object : InvalidationTracker.Observer(tables) {
                    override fun onInvalidated(tables: Set<String>) = schedule(uid, tables)
                }
                observer = obs
                database.invalidationTracker.addObserver(obs)
            }
            pushChanged(uid)
        }
    }

    @Synchronized
    fun stop() {
        startJob?.cancel()
        startJob = null
        observer?.let { database.invalidationTracker.removeObserver(it) }
        observer = null
        pending?.cancel()
        pending = null
        synchronized(dirty) { dirty.clear() }
        uid = null
    }

    private fun schedule(uid: String, tables: Set<String>) {
        synchronized(dirty) { dirty += tables.filter { MirrorLayout.isMirrored(it) } }
        pending?.cancel()
        pending = scope.launch {
            delay(DEBOUNCE_MILLIS)
            val batch = synchronized(dirty) { dirty.toSet().also { dirty.clear() } }
            if (batch.isNotEmpty()) pushChanged(uid, batch)
        }
    }

    /**
     * Uploads the months of [tables] (all mirrored tables when null) whose content differs
     * from what this phone last uploaded. [force] forgets those hashes first - the inbox's
     * "mirror" command, for when the cloud copy was edited or deleted by hand.
     *
     * A hash is remembered only after its write succeeded, so a failed pass is simply retried
     * by the next one; nothing is ever marked uploaded that is not in the cloud.
     */
    suspend fun pushChanged(uid: String, tables: Set<String>? = null, force: Boolean = false) {
        pushLock.withLock {
            try {
                resetIfOtherUser(uid)
                val all = mirroredTables()
                val targets = tables?.filter { it in all } ?: all
                var written = 0
                var deleted = 0
                for (table in targets) {
                    val (w, d) = pushTable(uid, table, force)
                    written += w
                    deleted += d
                }
                writeMeta(uid, all)
                AppLog.i(TAG, "Зеркало: таблиц ${targets.size}, записано документов $written, удалено $deleted")
            } catch (e: Exception) {
                AppLog.e(TAG, "Зеркало не выгрузилось", e)
            }
        }
    }

    private suspend fun pushTable(uid: String, table: String, force: Boolean): Pair<Int, Int> {
        val months = readMonths(table)
        val collection = db.collection("users").document(uid).collection("mirror")
        var written = 0
        var deleted = 0

        val known = prefs.all.keys
            .filter { it.startsWith("$DOCS_PREFIX${table}__") }
            .map { it.removePrefix("$DOCS_PREFIX${table}__") }
        // A month that lost all its rows has to disappear from the cloud too, or the reader
        // would keep a deleted row forever.
        for (month in known - months.keys) {
            for (id in docsOf(table, month)) {
                collection.document(id).delete().confirmed()
                deleted++
            }
            prefs.edit().remove(hashKey(table, month)).remove(docsKey(table, month)).apply()
        }

        for ((month, rows) in months) {
            val parts = MirrorLayout.splitIntoParts(rows)
            val texts = parts.map { MirrorLayout.arrayJson(it) }
            val hash = MirrorLayout.sha256(texts.joinToString("\n"))
            if (!force && prefs.getString(hashKey(table, month), null) == hash) continue

            val ids = texts.indices.map { MirrorLayout.docId(table, month, it, texts.size) }
            texts.forEachIndexed { i, text ->
                collection.document(ids[i]).set(
                    mapOf(
                        "table" to table,
                        "month" to month,
                        "part" to i + 1,
                        "parts" to texts.size,
                        "rowsJson" to text,
                        "hash" to MirrorLayout.sha256(text),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                ).confirmed()
                written++
            }
            // The part count changed: the old ids would otherwise sit next to the new ones.
            for (stale in docsOf(table, month) - ids.toSet()) {
                collection.document(stale).delete().confirmed()
                deleted++
            }
            prefs.edit()
                .putString(hashKey(table, month), hash)
                .putString(docsKey(table, month), ids.joinToString(","))
                .apply()
        }
        return written to deleted
    }

    /** Manifest is rebuilt from prefs, which hold exactly what is known to be in the cloud. */
    private suspend fun writeMeta(uid: String, tables: List<String>) {
        val meta = db.collection("users").document(uid).collection("mirror_meta")
        val schema = JSONObject()
        for (table in tables) {
            val columns = JSONArray()
            for (c in columns(table)) {
                columns.put(
                    JSONObject()
                        .put("name", c.name)
                        .put("type", c.type)
                        .put("notNull", c.notNull)
                        .put("hasDefault", c.hasDefault)
                        .put("pk", c.pkPosition)
                )
            }
            schema.put(table, columns)
        }
        meta.document("schema").set(
            mapOf(
                "schemaJson" to schema.toString(),
                "dbVersion" to database.openHelper.readableDatabase.version,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        ).confirmed()

        val manifest = JSONObject()
        for (key in prefs.all.keys.filter { it.startsWith(DOCS_PREFIX) }) {
            val tableMonth = key.removePrefix(DOCS_PREFIX)
            val table = tableMonth.substringBefore("__")
            if (table !in tables) continue
            val ids = prefs.getString(key, null)?.split(',')?.filter { it.isNotEmpty() } ?: continue
            // Per-document hashes are not kept locally; the month hash plus the part count is
            // enough for the reader to know it has every part of every month.
            val hash = prefs.getString(HASH_PREFIX + tableMonth, null) ?: continue
            for (id in ids) manifest.put(id, JSONObject().put("monthHash", hash).put("parts", ids.size))
        }
        meta.document("manifest").set(
            mapOf(
                "manifestJson" to manifest.toString(),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        ).confirmed()
    }

    /** Rows of [table] as JSON objects, grouped by [MirrorLayout.monthKey]. */
    private fun readMonths(table: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        database.openHelper.readableDatabase.query("SELECT * FROM `$table`").use { cursor ->
            val names = cursor.columnNames.toList()
            while (cursor.moveToNext()) {
                val values = names.indices.map { cursor.valueAt(it) }
                val month = MirrorLayout.monthKey(names.zip(values).toMap())
                out.getOrPut(month) { mutableListOf() } += MirrorLayout.rowJson(names, values)
            }
        }
        return out
    }

    private fun Cursor.valueAt(i: Int): Any? = when (getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
        Cursor.FIELD_TYPE_BLOB -> getBlob(i)
        else -> getString(i)
    }

    /** Live list from SQLite rather than a list in code - a new table is mirrored on its own. */
    fun mirroredTables(): List<String> {
        val tables = mutableListOf<String>()
        database.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use { c ->
                while (c.moveToNext()) tables += c.getString(0)
            }
        return tables.filter { MirrorLayout.isMirrored(it) }
    }

    /** `PRAGMA table_info`, empty for a table that does not exist. */
    fun columns(table: String): List<MirrorLayout.Column> {
        val out = mutableListOf<MirrorLayout.Column>()
        database.openHelper.readableDatabase.query("PRAGMA table_info(`$table`)").use { c ->
            val name = c.getColumnIndexOrThrow("name")
            val type = c.getColumnIndexOrThrow("type")
            val notNull = c.getColumnIndexOrThrow("notnull")
            val default = c.getColumnIndexOrThrow("dflt_value")
            val pk = c.getColumnIndexOrThrow("pk")
            while (c.moveToNext()) {
                out += MirrorLayout.Column(
                    name = c.getString(name),
                    type = c.getString(type).orEmpty(),
                    notNull = c.getInt(notNull) != 0,
                    hasDefault = !c.isNull(default),
                    pkPosition = c.getInt(pk),
                )
            }
        }
        return out
    }

    /**
     * Hashes describe one account's cloud. After switching accounts they would claim months
     * are uploaded that the new account has never seen.
     */
    private fun resetIfOtherUser(uid: String) {
        if (prefs.getString(KEY_UID, null) == uid) return
        prefs.edit().clear().putString(KEY_UID, uid).apply()
    }

    private fun docsOf(table: String, month: String): Set<String> =
        prefs.getString(docsKey(table, month), null)?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    private fun hashKey(table: String, month: String) = "$HASH_PREFIX${table}__$month"
    private fun docsKey(table: String, month: String) = "$DOCS_PREFIX${table}__$month"

    /**
     * A Firestore write's task completes only when the server acknowledges it, so with the
     * network gone or the app frozen in the background the await never returns - the likely
     * reason the first real pass (build-50) stopped after one document, logged nothing (INFO
     * stays on the phone) and kept [pushLock] held, so every later pass queued behind it.
     * A timeout turns that into an
     * ERROR that reaches the cloud log and frees the lock; the write itself stays queued in
     * Firestore's local cache, and since a month's hash is remembered only after success the
     * next pass simply writes it again.
     *
     * Rethrown as IllegalStateException: a bare TimeoutCancellationException is a
     * CancellationException and would read as "the caller was cancelled".
     */
    private suspend fun <T> Task<T>.confirmed(): T = try {
        withTimeout(WRITE_TIMEOUT_MILLIS) { await() }
    } catch (e: TimeoutCancellationException) {
        throw IllegalStateException("Firestore не подтвердил запись за ${WRITE_TIMEOUT_MILLIS / 1000} с")
    }

    private companion object {
        const val TAG = "CloudMirror"
        const val PREFS = "cloud_mirror"
        const val KEY_UID = "uid"
        const val HASH_PREFIX = "hash:"
        const val DOCS_PREFIX = "docs:"

        /** Quiet period before touched tables are re-uploaded. */
        const val DEBOUNCE_MILLIS = 60_000L
        const val WRITE_TIMEOUT_MILLIS = 60_000L
    }
}
