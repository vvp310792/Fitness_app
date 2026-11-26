package com.fitnessapp.summary.sync

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

class ServerSnapshotContractTest {
    private val raw = """[{"id":"a","day":"2026-11-25","data":{"steps":12}}]"""
    private val hash = ServerSnapshotContract.digest(raw)
    private val id = "garmin__daily__2026-11"
    private val partId = id + "__" + hash
    private val manifest = ServerManifest(id,"garmin","daily","2026-11",1,listOf(partId),
        ServerSnapshotContract.digest("[\"$partId\"]"))
    private val part = ServerPart(partId,"garmin","daily","2026-11",1,hash,rowsJson=raw)
    private fun status(m: List<ServerManifest> = listOf(manifest)) = ServerCatalogStatus(1,
        ServerSnapshotContract.catalogDigest(m),m.size.toLong(),m.sumOf { it.count },1_700_000_000,123)
    private fun reject(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        assertTrue("expected invalid snapshot to be rejected", failed)
    }

    @Test fun validCatalogAndUncompressedPart() {
        ServerSnapshotContract.validateCatalog(status(),listOf(manifest))
        val row=ServerSnapshotContract.validatePart(manifest,part).single()
        assertEquals("a",row.nativeId)
        assertEquals("2026-11-25",row.day)
    }
    @Test fun compressedPartMatchesSameHash() {
        val out=ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(raw.toByteArray(Charsets.UTF_8)) }
        assertEquals(ServerSnapshotContract.validatePart(manifest,part),
            ServerSnapshotContract.validatePart(manifest,part.copy(rowsJson=null,rowsZlib=out.toByteArray())))
    }
    @Test fun legacyStatusCannotActivate() {
        reject { ServerSnapshotContract.validateCatalog(status().copy(schemaVersion=0),listOf(manifest)) }
    }
    @Test fun tamperedCatalogCountOrHashRejected() {
        reject { ServerSnapshotContract.validateCatalog(status().copy(records=2),listOf(manifest)) }
        reject { ServerSnapshotContract.validateCatalog(status().copy(manifestSetSha256="0".repeat(64)),listOf(manifest)) }
    }
    @Test fun catalogOrderDoesNotChangeDigest() {
        val otherId="gymup__training__2026-11"
        val otherPart=otherId+"__"+hash
        val other=ServerManifest(otherId,"gymup","training","2026-11",1,listOf(otherPart),
            ServerSnapshotContract.digest("[\"$otherPart\"]"))
        assertEquals(ServerSnapshotContract.catalogDigest(listOf(manifest,other)),ServerSnapshotContract.catalogDigest(listOf(other,manifest)))
    }
    @Test fun duplicateGroupsOrPartIdsRejected() {
        reject { ServerSnapshotContract.catalogDigest(listOf(manifest,manifest)) }
        reject { ServerSnapshotContract.validateManifest(manifest.copy(parts=listOf(partId,partId),count=2)) }
    }
    @Test fun contentOrMetadataMismatchRejected() {
        reject { ServerSnapshotContract.validatePart(manifest,part.copy(rowsJson=raw.replace("12","13"))) }
        reject { ServerSnapshotContract.validatePart(manifest,part.copy(count=2)) }
        reject { ServerSnapshotContract.validatePart(manifest,part.copy(source="strava")) }
        reject { ServerSnapshotContract.validatePart(manifest,part.copy(rowsZlib=byteArrayOf(1))) }
    }
    @Test fun validHashDoesNotAllowWrongMonthOrDuplicateRows() {
        for (payload in listOf(raw.replace("2026-11-25","2026-10-25"),"["+raw.removePrefix("[").removeSuffix("]")+","+raw.removePrefix("[").removeSuffix("]")+"]")) {
            val h=ServerSnapshotContract.digest(payload)
            val p=id+"__"+h
            val n=if(payload.contains("2026-10-25")) 1L else 2L
            val m=manifest.copy(count=n,parts=listOf(p),sha256=ServerSnapshotContract.digest("[\"$p\"]"))
            reject { ServerSnapshotContract.validatePart(m,part.copy(id=p,count=n,sha256=h,rowsJson=payload)) }
        }
    }
    @Test fun changedPublicationCannotCommit() {
        assertTrue(ServerSnapshotContract.samePublication(status(),status()))
        assertFalse(ServerSnapshotContract.samePublication(status(),status().copy(publishedNanos=124)))
    }
}
