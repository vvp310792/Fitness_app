package com.fitnessapp.summary.sync

import kotlinx.coroutines.NonCancellable
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
    /** Must atomically validate count and switch the active pointer. */
    suspend fun activate(ownerUid: String, generation: String, status: ServerCatalogStatus)
    /** Must NEVER delete an active generation (including cancellation just after commit). */
    suspend fun discardInactive(ownerUid: String, generation: String)
}

/** Shadow-only import. Existing UI and its Room tables are untouched. */
class ServerSnapshotImporter(private val sink: ServerSnapshotSink) {
    private val mutex = Mutex()

    suspend fun refresh(ownerUid: String, gateway: ServerSnapshotGateway): ServerCatalogStatus = mutex.withLock {
        require(ownerUid.isNotBlank())
        gateway.requireOwner()
        val before = gateway.status()
        val manifests = gateway.manifests()
        ServerSnapshotContract.validateCatalog(before, manifests)
        require(ServerSnapshotContract.samePublication(before, gateway.status()))
        val generation = UUID.randomUUID().toString()
        val targets = manifests.flatMap { m -> m.parts.map { it to m } }.toMap()
        val counts = mutableMapOf<String, Long>()
        var activated = false
        try {
            for (ids in targets.keys.toList().chunked(10)) {
                gateway.requireOwner()
                val parts = gateway.parts(ids)
                require(parts.size == ids.size && parts.map { it.id }.toSet() == ids.toSet())
                val rows = parts.flatMap { part ->
                    val manifest = targets.getValue(part.id)
                    val verified = ServerSnapshotContract.validatePart(manifest, part)
                    val n = (counts[manifest.id] ?: 0L) + verified.size
                    require(n <= manifest.count)
                    counts[manifest.id] = n
                    verified
                }
                sink.stage(ownerUid, generation, rows)
            }
            require(manifests.all { counts[it.id] == it.count })
            require(counts.values.sum() == before.records)
            require(ServerSnapshotContract.samePublication(before, gateway.status()))
            gateway.requireOwner()
            sink.activate(ownerUid, generation, before)
            activated = true
            before
        } finally {
            if (!activated) withContext(NonCancellable) {
                // Crash leftovers are isolated by generation; cleanup must not
                // mask the original failure or touch the last activated snapshot.
                runCatching { sink.discardInactive(ownerUid, generation) }
            }
        }
    }
}
