package com.fitnessapp.summary.sync

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.fitnessapp.summary.data.AppDatabase
import com.fitnessapp.summary.debug.AppLog
import com.fitnessapp.summary.strength.StrengthImportManager
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.time.LocalDate

/**
 * Applies row edits that the owner's PC scripts queue in `users/{uid}/inbox/{id}`.
 *
 * Why a queue and not direct writes into the mirror: the mirror is a copy, and a copy that
 * two sides write to needs a merge rule nobody wants to maintain. Here the phone stays the
 * only writer of Room; the PC only asks, and every answer (`appliedAt` + `result`) is
 * written back next to the question, so the asker can tell "applied" from "refused" from
 * "phone has not seen it yet".
 *
 * Documents are applied in id order - the writer makes ids sortable by time, so a patch
 * that follows an upsert of the same row never lands first. Each one is applied once:
 * `appliedAt` marks it in the cloud, [handled] covers the snapshot that our own `appliedAt`
 * write triggers - a pending server timestamp reads as null locally, so the filter alone
 * would apply the document a second time.
 *
 * Rows of `garmin_*` tables edited this way are overwritten by the next Garmin sync of that
 * day - that is the Garmin-first rule, not a bug, and the PC side is told so in CLAUDE.md.
 */
class InboxApplier(
    private val database: AppDatabase,
    private val mirror: CloudMirror,
    /** strength/StrengthImportManager.importBackupBytes - a lambda so tests need no Context. */
    private val gymBackupImporter: (suspend (ByteArray) -> StrengthImportManager.State)? = null,
) {

    private val db = FirebaseFirestore.getInstance()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val lock = Mutex()

    private var listener: ListenerRegistration? = null
    private var uid: String? = null
    private val handled = mutableSetOf<String>()

    /**
     * Same uid again (auth state re-emits on token refresh) is a no-op: a restart would clear
     * [handled] while our own `appliedAt` writes may still be pending, and re-apply them.
     */
    @Synchronized
    fun start(uid: String) {
        if (this.uid == uid && listener != null) return
        stop()
        this.uid = uid
        listener = db.collection("users").document(uid).collection("inbox")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    AppLog.e(TAG, "Входящие: слушатель оборвался", error)
                    return@addSnapshotListener
                }
                val docs = snapshot?.documents
                    ?.filter { it.get("appliedAt") == null }
                    ?.sortedBy { it.id }
                    ?: return@addSnapshotListener
                if (docs.isNotEmpty()) scope.launch { applyAll(uid, docs) }
            }
    }

    @Synchronized
    fun stop() {
        listener?.remove()
        listener = null
        uid = null
        synchronized(handled) { handled.clear() }
    }

    private suspend fun applyAll(uid: String, docs: List<DocumentSnapshot>) = lock.withLock {
        val touched = mutableSetOf<String>()
        var applied = 0
        var refused = 0
        for (doc in docs) {
            if (!synchronized(handled) { handled.add(doc.id) }) continue
            val outcome = applyOne(uid, doc)
            outcome.table?.let { touched += it }
            if (outcome.ok) applied++ else refused++
            try {
                doc.reference.set(
                    mapOf("appliedAt" to FieldValue.serverTimestamp(), "result" to outcome.result),
                    SetOptions.merge(),
                ).await()
            } catch (e: Exception) {
                // The edit is in Room already; without the mark it would be re-applied after a
                // restart, which is harmless for upsert/patch/delete - they are idempotent.
                AppLog.e(TAG, "Входящие: не удалось отметить документ ${doc.id}", e)
            }
        }
        AppLog.i(TAG, "Входящие: применено $applied, отклонено $refused")
        if (touched.isNotEmpty()) mirror.pushChanged(uid, touched)
    }

    private class Outcome(val ok: Boolean, val result: String, val table: String? = null)

    private suspend fun applyOne(uid: String, doc: DocumentSnapshot): Outcome {
        val op = doc.getString("op")
        val table = doc.getString("table")
        if (op == "mirror") {
            // Re-upload on request: the reader found the cloud copy damaged or edited by hand.
            mirror.pushChanged(uid, table?.takeIf { it.isNotBlank() }?.let { setOf(it) }, force = true)
            return Outcome(true, "ok: зеркало обновлено")
        }
        if (op == OP_GYMUP_BACKUP) return applyGymBackup(doc)
        val parsed = MirrorLayout.parseOp(op, table, doc.getString("rowJson"), doc.getString("keyJson"))
            .getOrElse { return Outcome(false, "ошибка: ${it.message}") }
        MirrorLayout.validate(parsed, mirror.columns(parsed.table))
            ?.let { return Outcome(false, "ошибка: $it") }
        return try {
            val rows = write(parsed)
            if (parsed is MirrorLayout.InboxOp.Patch && rows == 0) {
                Outcome(false, "ошибка: строка не найдена")
            } else {
                Outcome(true, "ok: $rows строк", parsed.table)
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Входящие: ${doc.id} (${parsed.table}) не применён", e)
            Outcome(false, "ошибка: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * A whole gym-app backup, not a row: the file is several hundred KB, and replaying it as
     * thousands of single-row upserts would neither fit the free Firestore quota nor remove
     * sets deleted in the gym app. The PC writes the file as base64 parts under
     * `parts/{n}` BEFORE the inbox document itself, so the parts are there when we see it.
     */
    private suspend fun applyGymBackup(doc: DocumentSnapshot): Outcome {
        val importer = gymBackupImporter ?: return Outcome(false, "ошибка: импорт бэкапа не подключён")
        return try {
            val expected = doc.getLong("parts")?.toInt()
                ?: return Outcome(false, "ошибка: нет поля parts")
            val parts = doc.reference.collection("parts").get().await().documents
            val bytes = GymBackupParts.assemble(
                parts.map { (it.id.toIntOrNull() ?: -1) to (it.getString("b64") ?: "") },
                expected,
                doc.getString("sha256"),
            ).getOrElse { return Outcome(false, "ошибка: ${it.message}") }
            when (val state = importer(bytes)) {
                is StrengthImportManager.State.Success -> Outcome(
                    true,
                    "ok: тренировок ${state.sessions}, подходов ${state.sets}, " +
                        "${LocalDate.ofEpochDay(state.firstEpochDay)}..${LocalDate.ofEpochDay(state.lastEpochDay)}, " +
                        "неопознанных упражнений ${state.unidentified.size}",
                    "strength_sets",
                )
                is StrengthImportManager.State.Failed -> Outcome(false, "ошибка: ${state.reason}")
                else -> Outcome(false, "ошибка: импорт не завершился")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Входящие: бэкап ${doc.id} не применён", e)
            Outcome(false, "ошибка: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Room's invalidation tracker sees writes through the open helper, so screens refresh. */
    private fun write(op: MirrorLayout.InboxOp): Int {
        var rows = 0
        database.runInTransaction {
            val w = database.openHelper.writableDatabase
            rows = when (op) {
                is MirrorLayout.InboxOp.Upsert -> {
                    w.insert(op.table, SQLiteDatabase.CONFLICT_REPLACE, op.values.toContentValues())
                    1
                }
                is MirrorLayout.InboxOp.Patch -> {
                    val (where, args) = keyClause(op.key)
                    w.update(op.table, SQLiteDatabase.CONFLICT_ABORT, op.values.toContentValues(), where, args)
                }
                is MirrorLayout.InboxOp.Delete -> {
                    val (where, args) = keyClause(op.key)
                    w.delete(op.table, where, args)
                }
            }
        }
        return rows
    }

    private fun keyClause(key: Map<String, Any?>): Pair<String, Array<Any?>> =
        key.keys.joinToString(" AND ") { "`$it` = ?" } to key.values.map { it.toString() }.toTypedArray<Any?>()

    private fun Map<String, Any?>.toContentValues() = ContentValues().also { cv ->
        for ((k, v) in this) when (v) {
            null -> cv.putNull(k)
            is Long -> cv.put(k, v)
            is Double -> cv.put(k, v)
            is String -> cv.put(k, v)
            else -> cv.put(k, v.toString())
        }
    }

    private companion object {
        const val TAG = "InboxApplier"
        const val OP_GYMUP_BACKUP = "gymupBackup"
    }
}
