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
    suspend fun prepare(ownerUid: String) {}
    suspend fun acknowledgeIfUnchanged(ownerUid: String, status: ServerCatalogStatus): Boolean = false
    /** Returns number of verified rows copied from the current active generation; zero = fetch. */
    suspend fun reusePart(ownerUid: String, generation: String, partId: String): Long = 0L
    suspend fun stageVerifiedPart(ownerUid: String, generation: String, partId: String, rows: List<VerifiedServerRow>) {
        for (chunk in rows.chunked(250)) stage(ownerUid,generation,chunk)
    }
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
    val rowsTotal: Long = 0,
    val reusedParts: Int = 0,
    val downloadedParts: Int = 0
)

class SnapshotImportFailure(
    val phase: String,
    val completedParts: Int,
    val totalParts: Int,
    val reasonCode: String,
    cause: Exception
) : Exception("$phase: $reasonCode ($completedParts/$totalParts)", cause)

private class PublicationChanged : Exception()

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
        var reusedParts = 0
        var downloadedParts = 0
        suspend fun progress(next: String) {
            phase = next
            onProgress(SnapshotProgress(phase, partsDone, partsTotal, rowsDone, rowsTotal, reusedParts, downloadedParts))
        }
        var generation: String? = null
        var activated = false
        try {
            progress("STATUS")
            require(ownerUid.isNotBlank())
            gateway.requireOwner()
            val before = gateway.status()
            ServerSnapshotContract.validateStatus(before)
            rowsTotal = before.records
            sink.prepare(ownerUid)
            gateway.requireOwner()
            // Same committed content, even if the publisher advanced updatedAt.
            if (sink.acknowledgeIfUnchanged(ownerUid,before)) {
                rowsDone = rowsTotal
                progress("UNCHANGED")
                return@withLock before
            }
            progress("CATALOG_READ")
            val manifests = gateway.manifests()
            progress("CATALOG_VERIFY")
            ServerSnapshotContract.validateCatalog(before, manifests)
            if (!ServerSnapshotContract.samePublication(before, gateway.status())) throw PublicationChanged()
            val stageId = UUID.randomUUID().toString()
            generation = stageId
            val pairs = manifests.flatMap { m -> m.parts.map { it to m } }
            val targets = pairs.toMap()
            require(targets.size == pairs.size)
            partsTotal = targets.size
            val counts = mutableMapOf<String, Long>()
            fun countPart(id: String, count: Long) {
                val manifest = targets.getValue(id)
                require(count > 0 && count <= manifest.count)
                val n = (counts[manifest.id] ?: 0L) + count
                require(n <= manifest.count)
                counts[manifest.id] = n
                partsDone++
                rowsDone += count
            }
            for (ids in targets.keys.toList().chunked(4)) {
                progress("LOCAL_REUSE")
                gateway.requireOwner()
                val missing = mutableListOf<String>()
                for (id in ids) {
                    val copied = sink.reusePart(ownerUid,stageId,id)
                    if (copied == 0L) missing.add(id)
                    else {
                        countPart(id,copied)
                        reusedParts++
                    }
                }
                if (missing.isEmpty()) continue
                progress("PART_READ")
                val parts = gateway.parts(missing)
                progress("PART_VERIFY")
                require(parts.size == missing.size && parts.map { it.id }.toSet() == missing.toSet())
                for (part in parts) {
                    phase = "PART_VERIFY"
                    val verified = ServerSnapshotContract.validatePart(targets.getValue(part.id), part)
                    phase = "LOCAL_WRITE"
                    sink.stageVerifiedPart(ownerUid,stageId,part.id,verified)
                    countPart(part.id,verified.size.toLong())
                    downloadedParts++
                }
            }
            progress("FINAL_VERIFY")
            require(manifests.all { counts[it.id] == it.count })
            require(counts.values.sum() == before.records)
            if (!ServerSnapshotContract.samePublication(before, gateway.status())) throw PublicationChanged()
            gateway.requireOwner()
            progress("ACTIVATE")
            sink.activate(ownerUid, stageId, before)
            activated = true
            before
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            val code = if (e is TimeoutCancellationException) "TIMEOUT"
                else if (e is PublicationChanged) "PUBLICATION_CHANGED"
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
