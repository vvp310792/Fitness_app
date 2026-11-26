package com.fitnessapp.summary.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ServerSnapshotImporterTest {
    private val raw = """[{"id":"sample","day":"2026-11-25","data":{"steps":10}}]"""
    private val hash = ServerSnapshotContract.digest(raw)
    private val group = "garmin__daily__2026-11"
    private val id = group+"__"+hash
    private val manifest = ServerManifest(group,"garmin","daily","2026-11",1,listOf(id),
        ServerSnapshotContract.digest("[\"$id\"]"))
    private val part = ServerPart(id,"garmin","daily","2026-11",1,hash,rowsJson=raw)
    private val valid = ServerCatalogStatus(1,ServerSnapshotContract.catalogDigest(listOf(manifest)),1,1,1700000000,0)

    private inner class Gateway(
        val payload: List<ServerPart> = listOf(part),
        val statusAfter: ServerCatalogStatus = valid,
        val ownerLost: Boolean = false
    ) : ServerSnapshotGateway {
        var reads = 0
        override suspend fun status() = if (++reads >= 3) statusAfter else valid
        override suspend fun manifests() = listOf(manifest)
        override suspend fun parts(ids: List<String>) = payload
        override fun requireOwner() { if (ownerLost && reads >= 3) error("owner changed") }
    }

    private class Sink : ServerSnapshotSink {
        val staged = mutableListOf<VerifiedServerRow>()
        var active = "previous"
        var discards = 0
        override suspend fun stage(ownerUid: String, generation: String, rows: List<VerifiedServerRow>) {
            staged.addAll(rows)
        }
        override suspend fun activate(ownerUid: String, generation: String, status: ServerCatalogStatus) {
            require(staged.size.toLong() == status.records)
            active = generation
        }
        override suspend fun discardInactive(ownerUid: String, generation: String) {
            if (active != generation) { staged.clear(); discards++ }
        }
    }

    @Test fun verifiedCompleteSnapshotActivates() = runBlocking {
        val sink=Sink()
        assertEquals(valid,ServerSnapshotImporter(sink).refresh("test",Gateway()))
        assertNotEquals("previous",sink.active)
        assertEquals(1,sink.staged.size)
        assertEquals(0,sink.discards)
    }

    private suspend fun rejected(gateway: Gateway) {
        val sink=Sink()
        var rejected=false
        try { ServerSnapshotImporter(sink).refresh("test",gateway) }
        catch (_: Exception) { rejected=true }
        assertTrue(rejected)
        assertEquals("previous",sink.active)
        assertTrue(sink.staged.isEmpty())
        assertEquals(1,sink.discards)
    }

    @Test fun missingPartPreservesPrevious() = runBlocking { rejected(Gateway(payload=emptyList())) }
    @Test fun invalidPayloadPreservesPrevious() = runBlocking { rejected(Gateway(payload=listOf(part.copy(rowsJson=raw.replace("10","11"))))) }
    @Test fun concurrentPublicationPreservesPrevious() = runBlocking { rejected(Gateway(statusAfter=valid.copy(publishedNanos=1))) }
    @Test fun changedAccountPreservesPrevious() = runBlocking { rejected(Gateway(ownerLost=true)) }

    @Test fun progressReportsRealVerifiedCounts() = runBlocking {
        val events=mutableListOf<SnapshotProgress>()
        val sink=Sink()
        ServerSnapshotImporter(sink).refresh("test",Gateway()) { events.add(it) }
        assertEquals("STATUS",events.first().phase)
        assertEquals("ACTIVATE",events.last().phase)
        assertEquals(1,events.last().partsDone)
        assertEquals(1,events.last().partsTotal)
        assertEquals(1L,events.last().rowsDone)
        assertEquals(1L,events.last().rowsTotal)
    }

    @Test fun invalidPartHasSafeDiagnosticAndDoesNotActivate() = runBlocking {
        val sink=Sink()
        var failure: SnapshotImportFailure? = null
        try {
            ServerSnapshotImporter(sink).refresh("test",Gateway(payload=listOf(part.copy(rowsJson="[]"))))
        } catch (e: SnapshotImportFailure) { failure=e }
        assertEquals("PART_VERIFY",failure?.phase)
        assertEquals(0,failure?.completedParts)
        assertEquals(1,failure?.totalParts)
        assertEquals("previous",sink.active)
        assertEquals(1,sink.discards)
    }

}
