package com.fitnessapp.summary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.util.UUID
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
            var currentStatus=status
            var currentCatalog=catalog.toList()
            val gateway=object : ServerSnapshotGateway {
                var corrupt=false
                var catalogReads=0
                var downloads=0
                override suspend fun status()=currentStatus
                override suspend fun manifests(): List<ServerManifest> {
                    catalogReads++
                    return currentCatalog
                }
                override suspend fun parts(ids: List<String>): List<ServerPart> {
                    downloads+=ids.size
                    return ids.map { id -> parts.getValue(id).let { p ->
                        if(corrupt) p.copy(sha256="0".repeat(64)) else p
                    } }
                }
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
            assertEquals(partCount,gateway.downloads)
            assertEquals(partCount,progress?.downloadedParts)
            // Even a new publication timestamp with identical commitment does not
            // download the catalog or any body, nor replace the local generation.
            currentStatus=status.copy(publishedNanos=1)
            importer.refresh("fixture-owner",gateway) { progress=it }
            assertEquals("UNCHANGED",progress?.phase)
            assertEquals(1,gateway.catalogReads)
            assertEquals(partCount,gateway.downloads)
            val unchanged=checkNotNull(db.snapshots().head("fixture-owner"))
            assertEquals(active.generation,unchanged.generation)
            assertEquals(active.appliedAtMillis,unchanged.appliedAtMillis)
            // One changed part, all other 718 content-addressed parts reusable.
            val first=currentCatalog.first()
            val original=parts.getValue(first.parts.first())
            val raw=(0 until original.count.toInt()).joinToString(",", "[", "]") { row ->
                """{"id":"fixture-$row","day":"2026-11-25","data":{"sample":$row,"label":"changed"}}"""
            }
            val hash=ServerSnapshotContract.digest(raw)
            val newId=first.id+"__"+hash
            parts[newId]=original.copy(id=newId,sha256=hash,rowsJson=raw,rowsZlib=null)
            val newIds=listOf(newId)+first.parts.drop(1)
            val changed=first.copy(parts=newIds,sha256=ServerSnapshotContract.digest(newIds.joinToString(",", "[", "]") { "\"$it\"" }))
            currentCatalog=listOf(changed)+currentCatalog.drop(1)
            currentStatus=status.copy(manifestSetSha256=ServerSnapshotContract.catalogDigest(currentCatalog),publishedNanos=2)
            gateway.corrupt=true
            try {
                importer.refresh("fixture-owner",gateway)
                fail("tampered part accepted")
            } catch (e: SnapshotImportFailure) {
                assertEquals("PART_VERIFY",e.phase)
            }
            assertEquals(unchanged,db.snapshots().head("fixture-owner"))
            assertEquals(totalRows.toLong(),db.snapshots().count("fixture-owner",active.generation))
            val beforeRetry=gateway.downloads
            gateway.corrupt=false
            importer.refresh("fixture-owner",gateway) { progress=it }
            assertEquals(1,gateway.downloads-beforeRetry)
            assertEquals(1,progress?.downloadedParts)
            assertEquals(partCount-1,progress?.reusedParts)
            val next=checkNotNull(db.snapshots().head("fixture-owner"))
            assertNotEquals(active.generation,next.generation)
            assertEquals(totalRows.toLong(),db.snapshots().count("fixture-owner",next.generation))
            assertEquals(0L,db.snapshots().count("fixture-owner",active.generation))
            assertEquals(original.count,db.snapshots().partCount("fixture-owner",next.generation,newId))
            assertEquals(0L,db.snapshots().partCount("fixture-owner",next.generation,original.id))
        } finally {
            db.close()
        }
    }

    @Test fun migratedV1KeepsVerifiedLocalRows() = runBlocking(Dispatchers.IO) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="snapshot-migration-"+UUID.randomUUID()+".db"
        val hash="a".repeat(64)
        try {
            context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use { old ->
                old.execSQL("CREATE TABLE snapshot_rows (ownerUid TEXT NOT NULL, generation TEXT NOT NULL, source TEXT NOT NULL, kind TEXT NOT NULL, nativeId TEXT NOT NULL, day TEXT NOT NULL, rowJson TEXT NOT NULL, PRIMARY KEY(ownerUid,generation,source,kind,nativeId))")
                old.execSQL("CREATE INDEX index_snapshot_rows_ownerUid_generation_source_kind_day ON snapshot_rows(ownerUid,generation,source,kind,day)")
                old.execSQL("CREATE TABLE snapshot_heads (ownerUid TEXT NOT NULL PRIMARY KEY, generation TEXT NOT NULL, manifestSetSha256 TEXT NOT NULL, groups INTEGER NOT NULL, records INTEGER NOT NULL, publishedSeconds INTEGER NOT NULL, publishedNanos INTEGER NOT NULL, appliedAtMillis INTEGER NOT NULL)")
                old.execSQL("INSERT INTO snapshot_rows VALUES ('owner','g','garmin','daily','id','2026-11-25','{}')")
                old.execSQL("INSERT INTO snapshot_heads VALUES ('owner','g',?,1,1,1700000000,0,10)",arrayOf(hash))
                old.version=1
            }
            val db=Room.databaseBuilder(context,ServerSnapshotDatabase::class.java,name)
                .addMigrations(ServerSnapshotDatabase.MIGRATION_1_2).build()
            try {
                val h=checkNotNull(db.snapshots().head("owner"))
                assertEquals("g",h.generation)
                assertEquals(1L,db.snapshots().count("owner","g"))
                assertEquals(1L,db.snapshots().partCount("owner","g",""))
                val sink=RoomServerSnapshotSink(db)
                assertTrue(sink.acknowledgeIfUnchanged("owner",ServerCatalogStatus(1,hash,1,1,1700000000,1)))
                assertEquals(0L,sink.reusePart("owner","new","unknown-part"))
                assertEquals("g",db.snapshots().head("owner")?.generation)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

}
