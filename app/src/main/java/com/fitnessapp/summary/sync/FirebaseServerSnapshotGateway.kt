package com.fitnessapp.summary.sync

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/** Read-only gateway, scoped to the currently signed-in Firebase account.
 * Source.SERVER is required: cached documents cannot prove a coherent generation. */
class FirebaseServerSnapshotGateway(
    private val ownerUid: String,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) : ServerSnapshotGateway {
    override fun requireOwner() {
        require(ownerUid.isNotBlank() && auth.currentUser?.uid == ownerUid)
    }

    private fun user() = db.collection("users").document(ownerUid)
    private fun DocumentSnapshot.string(key: String): String = requireNotNull(getString(key))
    private fun DocumentSnapshot.number(key: String): Long = requireNotNull(getLong(key))
    private fun DocumentSnapshot.strings(key: String): List<String> =
        (get(key) as? List<*>)?.map { it as? String ?: error("invalid list item") } ?: error("missing list")

    /** Metadata only. Old v0 publishers remain visible, but not importable. */
    suspend fun overview(): ServerSnapshotOverview? {
        requireOwner()
        val doc=withTimeout(30_000L) { user().collection("server_sync").document("status").get(Source.SERVER).await() }
        requireOwner()
        if(!doc.exists()) return null
        val stamp=doc.getTimestamp("updatedAt")
        return ServerSnapshotOverview(doc.getLong("schemaVersion") ?: 0L,
            doc.getString("manifestSetSha256"),doc.getLong("groups"),doc.getLong("records"),
            stamp?.seconds,stamp?.nanoseconds)
    }

    override suspend fun status(): ServerCatalogStatus {
        requireOwner()
        val doc = withTimeout(30_000L) { user().collection("server_sync").document("status").get(Source.SERVER).await() }
        require(doc.exists())
        val stamp = requireNotNull(doc.getTimestamp("updatedAt"))
        requireOwner()
        return ServerCatalogStatus(doc.number("schemaVersion"),doc.string("manifestSetSha256"),
            doc.number("groups"),doc.number("records"),stamp.seconds,stamp.nanoseconds)
    }

    override suspend fun manifests(): List<ServerManifest> {
        requireOwner()
        val docs = withTimeout(45_000L) {
            user().collection("server_manifest").limit((ServerSnapshotContract.MAX_GROUPS+1).toLong()).get(Source.SERVER).await()
        }
        requireOwner()
        return docs.documents.map { d -> ServerManifest(d.id,d.string("source"),d.string("kind"),
            d.string("month"),d.number("count"),d.strings("parts"),d.string("sha256")) }
    }

    override suspend fun parts(ids: List<String>): List<ServerPart> {
        requireOwner()
        require(ids.isNotEmpty() && ids.size <= 10 && ids.distinct().size == ids.size)
        val docs = withTimeout(45_000L) {
            user().collection("server_records").whereIn(FieldPath.documentId(),ids).get(Source.SERVER).await()
        }
        requireOwner()
        return docs.documents.map { d -> ServerPart(d.id,d.string("source"),d.string("kind"),
            d.string("month"),d.number("count"),d.string("sha256"),d.getString("rowsJson"),d.getBlob("rowsZlib")?.toBytes()) }
    }
}
