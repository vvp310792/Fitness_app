package com.fitnessapp.summary.sync

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/** Read-only gateway. Always require the same signed-in owner and server reads.
 * The complete catalog and each immutable part are verified by the importer. */
class FirebaseServerSnapshotGateway(
    private val ownerUid: String,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) : ServerSnapshotGateway {
    override fun requireOwner() {
        require(ownerUid.isNotBlank() && auth.currentUser?.uid == ownerUid) { "owner changed" }
    }

    private fun user() = db.collection("users").document(ownerUid)
    private fun DocumentSnapshot.string(key: String): String = requireNotNull(getString(key))
    private fun DocumentSnapshot.number(key: String): Long = requireNotNull(getLong(key))
    private fun DocumentSnapshot.strings(key: String): List<String> =
        (get(key) as? List<*>)?.map { it as? String ?: error("invalid list item") } ?: error("missing list")

    private fun transient(e: Exception): Boolean = when (e) {
        is TimeoutCancellationException, is SnapshotTaskInterrupted -> true
        is FirebaseFirestoreException -> e.code in setOf(
            FirebaseFirestoreException.Code.UNAVAILABLE,
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
            FirebaseFirestoreException.Code.ABORTED,
            FirebaseFirestoreException.Code.INTERNAL,
            FirebaseFirestoreException.Code.UNKNOWN
        )
        else -> false
    }

    private suspend fun <T> serverRead(block: suspend () -> T): T {
        repeat(3) { attempt ->
            requireOwner()
            try {
                val result = withTimeout(90_000L) { block() }
                requireOwner()
                return result
            } catch (e: Exception) {
                // A cancelled Firebase Task can throw CancellationException while
                // our own coroutine is still active. Treat it as a retriable read.
                val failure = normalizeSnapshotReadFailure(e)
                if (attempt == 2 || !transient(failure)) throw failure
                delay(1000L * (attempt + 1))
            }
        }
        error("unreachable retry")
    }

    private suspend fun statusDocument(): DocumentSnapshot =
        serverRead { user().collection("server_sync").document("status").get(Source.SERVER).await() }

    suspend fun overview(): ServerSnapshotOverview? {
        val doc = statusDocument()
        if (!doc.exists()) return null
        val stamp = doc.getTimestamp("updatedAt")
        return ServerSnapshotOverview(doc.getLong("schemaVersion") ?: 0L,
            doc.getString("manifestSetSha256"), doc.getLong("groups"), doc.getLong("records"),
            stamp?.seconds, stamp?.nanoseconds)
    }

    override suspend fun status(): ServerCatalogStatus {
        val doc = statusDocument()
        require(doc.exists())
        val stamp = requireNotNull(doc.getTimestamp("updatedAt"))
        return ServerCatalogStatus(doc.number("schemaVersion"),doc.string("manifestSetSha256"),
            doc.number("groups"),doc.number("records"),stamp.seconds,stamp.nanoseconds)
    }

    override suspend fun manifests(): List<ServerManifest> {
        // A full collection query may exhaust the mobile SDK's local cache / transport.
        // Bound each page; validate count and catalog hash after the last page.
        val result = mutableListOf<ServerManifest>()
        val query = user().collection("server_manifest").orderBy(FieldPath.documentId())
        var last: DocumentSnapshot? = null
        val pageSize = 100L
        while (true) {
            val next: Query = last?.let { query.startAfter(it) } ?: query
            val page = serverRead { next.limit(pageSize).get(Source.SERVER).await() }.documents
            for (d in page) {
                result.add(ServerManifest(d.id,d.string("source"),d.string("kind"),
                    d.string("month"),d.number("count"),d.strings("parts"),d.string("sha256")))
                require(result.size <= ServerSnapshotContract.MAX_GROUPS)
            }
            if (page.size.toLong() < pageSize) break
            last = page.last()
        }
        requireOwner()
        return result
    }

    override suspend fun parts(ids: List<String>): List<ServerPart> {
        requireOwner()
        require(ids.isNotEmpty() && ids.size <= 10 && ids.distinct().size == ids.size)
        // Individual immutable-document reads avoid a large whereIn query result,
        // but still overlap up to four reads. No new permissions or cache trust.
        val result = mutableListOf<ServerPart>()
        val collection = user().collection("server_records")
        for (batch in ids.chunked(4)) {
            val parts = coroutineScope {
                batch.map { id ->
                    async {
                        val d = serverRead { collection.document(id).get(Source.SERVER).await() }
                        require(d.exists()) { "missing immutable part" }
                        ServerPart(d.id,d.string("source"),d.string("kind"),
                            d.string("month"),d.number("count"),d.string("sha256"),
                            d.getString("rowsJson"),d.getBlob("rowsZlib")?.toBytes())
                    }
                }.awaitAll()
            }
            result.addAll(parts)
        }
        requireOwner()
        return result
    }
}
