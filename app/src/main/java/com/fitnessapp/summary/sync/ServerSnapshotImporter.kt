package com.fitnessapp.summary.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

interface ServerSnapshotGateway {
    suspend fun status(): ServerCatalogStatus
    suspend fun manifests(): List<ServerManifest>
    suspend fun parts(ids: List<String>): List<ServerPart>
    fun requireOwner()
}

interface ServerSnapshotSink {
    suspend fun stage(ownerUid: String, generation: String, rows: List<VerifiedServerRow>)
    suspend fun activate(ownerUid: String, generation: String, status: ServerCatalogStatus)
    suspend fun discardInactive(ownerUid: String, generation: String)
}

/** Only stage names and counts, never private values or document identifiers. */
data class SnapshotProgress(
    val phase: String,
    val partsDone: Int = 0,
    val partsTotal: Int = 0,
    val rowsDone: Long = 0,
    val rowsTotal: Long = 0
)

class SnapshotImportFailure(
    val phase: String,
    val completedParts: Int,
    val totalParts: Int,
    val reasonCode: String,
    cause: Exception
) : Exception("$phase: $reasonCode ($completedParts/$totalParts)", cause)

class ServerSnapshotImporter(private val sink: ServerSnapshotSink) {
    private val mutex = Mutex()

    suspend fun refresh(
        ownerUid: String,
        gateway: ServerSnapshotGateway,
        onProgress: suspend (SnapshotProgress) -> Unit = {}
    ): ServerCatalogStatus = mutex.withLock {
        var phase = "STATUS"
        var partsDone = 0
        var partsTotal = 0
        var rowsDone = 0L
        var rowsTotal = 0L
        suspend fun progress(next: String) {
            phase = next
            onProgress(SnapshotProgress(phase, partsDone, partsTotal, rowsDone, rowsTotal))
        }
        var generation: String? = null
        var activated = false
        try {
            progress("STATUS")
            require(ownerUid.isNotBlank())
            gateway.requireOwner()
            val before = gateway.status()
            rowsTotal = before.records
            progress("CATALOG_READ")
            val manifests = gateway.manifests()
            progress("CATALOG_VERIFY")
            ServerSnapshotContract.validateCatalog(before, manifests)
            require(ServerSnapshotContract.samePublication(before, gateway.status())) { "publication changed" }
            val stageId = UUID.randomUUID().toString()
            generation = stageId
            val pairs = manifests.flatMap { m -> m.parts.map { it to m } }
            val targets = pairs.toMap()
            require(targets.size == pairs.size)
            partsTotal = targets.size
            val counts = mutableMapOf<String, Long>()
            for (ids in targets.keys.toList().chunked(4)) {
                progress("PART_READ")
                gateway.requireOwner()
                val parts = gateway.parts(ids)
                progress("PART_VERIFY")
                require(parts.size == ids.size && parts.map { it.id }.toSet() == ids.toSet())
                for (part in parts) {
                    phase = "PART_VERIFY"
                    val manifest = targets.getValue(part.id)
                    val verified = ServerSnapshotContract.validatePart(manifest, part)
                    val n = (counts[manifest.id] ?: 0L) + verified.size
                    require(n <= manifest.count)
                    counts[manifest.id] = n
                    phase = "LOCAL_WRITE"
                    // Bound each transaction to a single verified part. Chunk insertions
                    // to keep large Room batches off the main thread.
                    for (rows in verified.chunked(250)) sink.stage(ownerUid, stageId, rows)
                    partsDone++
                    rowsDone += verified.size.toLong()
                }
                progress("PART_READ")
            }
            progress("FINAL_VERIFY")
            require(manifests.all { counts[it.id] == it.count })
            require(counts.values.sum() == before.records)
            require(ServerSnapshotContract.samePublication(before, gateway.status())) { "publication changed" }
            gateway.requireOwner()
            progress("ACTIVATE")
            sink.activate(ownerUid, stageId, before)
            activated = true
            before
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            val code = if (e is TimeoutCancellationException) "TIMEOUT"
                else if (e is SnapshotImportFailure) e.reasonCode
                else e.javaClass.simpleName.ifBlank { "Exception" }
            throw SnapshotImportFailure(phase, partsDone, partsTotal, code, e)
        } finally {
            val stageId = generation
            if (!activated && stageId != null) withContext(NonCancellable) {
                runCatching { sink.discardInactive(ownerUid, stageId) }
            }
        }
    }
}
