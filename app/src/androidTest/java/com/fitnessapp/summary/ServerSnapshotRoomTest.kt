package com.fitnessapp.summary

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fitnessapp.summary.sync.RoomServerSnapshotSink
import com.fitnessapp.summary.sync.ServerCatalogStatus
import com.fitnessapp.summary.sync.ServerManifest
import com.fitnessapp.summary.sync.ServerPart
import com.fitnessapp.summary.sync.ServerSnapshotContract
import com.fitnessapp.summary.sync.ServerSnapshotDatabase
import com.fitnessapp.summary.sync.ServerSnapshotGateway
import com.fitnessapp.summary.sync.ServerSnapshotImporter
import com.fitnessapp.summary.sync.SnapshotImportFailure
import com.fitnessapp.summary.sync.SnapshotProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.DeflaterOutputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class ServerSnapshotRoomTest {
    @Test fun productionScaleImportAndFailedRefreshPreserveActiveGeneration() = runBlocking(Dispatchers.IO) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,ServerSnapshotDatabase::class.java).build()
        try {
            val parts=linkedMapOf<String,ServerPart>()
            val catalog=mutableListOf<ServerManifest>()
            var nextRow=0
            val totalRows=58181
            val groupCount=680
            val partCount=719
            var nextPart=0
            for (g in 0 until groupCount) {
                val source="fixture"
                val kind="metric_$g"
                val month="2026-11"
                val group="${source}__${kind}__${month}"
                val ids=mutableListOf<String>()
                var groupRows=0L
                repeat(if(g<39) 2 else 1) {
                    val count=totalRows/partCount + if(nextPart<totalRows%partCount) 1 else 0
                    val raw=(0 until count).joinToString(",", "[", "]") {
                        val row=nextRow++
                        """{"id":"fixture-$row","day":"2026-11-25","data":{"sample":$row,"label":"synthetic"}}"""
                    }
                    val hash=ServerSnapshotContract.digest(raw)
                    val id=group+"__"+hash
                    val compressed=if(nextPart%2==0) ByteArrayOutputStream().let { out ->
                        DeflaterOutputStream(out).use { it.write(raw.toByteArray(Charsets.UTF_8)) }
                        out.toByteArray()
                    } else null
                    parts[id]=ServerPart(id,source,kind,month,count.toLong(),hash,
                        rowsJson=if(compressed==null) raw else null,rowsZlib=compressed)
                    ids.add(id)
                    groupRows+=count
                    nextPart++
                }
                catalog.add(ServerManifest(group,source,kind,month,groupRows,ids,
                    ServerSnapshotContract.digest(ids.joinToString(",", "[", "]") { "\"$it\"" })))
            }
            assertEquals(totalRows,nextRow)
            assertEquals(partCount,parts.size)
            val status=ServerCatalogStatus(1,ServerSnapshotContract.catalogDigest(catalog),
                groupCount.toLong(),totalRows.toLong(),1700000000,0)
            val gateway=object : ServerSnapshotGateway {
                var corrupt=false
                override suspend fun status()=status
                override suspend fun manifests()=catalog
                override suspend fun parts(ids: List<String>): List<ServerPart> =
                    ids.map { id -> parts.getValue(id).let { p ->
                        if(corrupt) p.copy(sha256="0".repeat(64)) else p
                    } }
                override fun requireOwner()=Unit
            }
            var progress: SnapshotProgress?=null
            val importer=ServerSnapshotImporter(RoomServerSnapshotSink(db))
            importer.refresh("fixture-owner",gateway) { progress=it }
            val active=checkNotNull(db.snapshots().head("fixture-owner"))
            assertEquals(totalRows.toLong(),active.records)
            assertEquals(totalRows.toLong(),db.snapshots().count("fixture-owner",active.generation))
            assertEquals(partCount,progress?.partsDone)
            assertEquals(totalRows.toLong(),progress?.rowsDone)
            assertNull(db.snapshots().head("another-owner"))
            gateway.corrupt=true
            try {
                importer.refresh("fixture-owner",gateway)
                fail("tampered part accepted")
            } catch (e: SnapshotImportFailure) {
                assertEquals("PART_VERIFY",e.phase)
            }
            assertEquals(active,db.snapshots().head("fixture-owner"))
            assertEquals(totalRows.toLong(),db.snapshots().count("fixture-owner",active.generation))
        } finally {
            db.close()
        }
    }
}
