package com.fitnessapp.summary.sync

import android.content.Context
import androidx.room.Database
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "snapshot_rows",
    primaryKeys = ["ownerUid", "generation", "source", "kind", "nativeId"],
    indices = [Index(value = ["ownerUid", "generation", "source", "kind", "day"]),
        Index(value = ["ownerUid", "generation", "partId"])])
data class SnapshotRow(
    val ownerUid: String,
    val generation: String,
    val source: String,
    val kind: String,
    val nativeId: String,
    val day: String,
    val rowJson: String,
    // Empty for migrated v1 rows, which lack a trustworthy immutable-part identity.
    @ColumnInfo(defaultValue = "''") val partId: String = ""
)

@Entity(tableName = "snapshot_heads")
data class SnapshotHead(
    @PrimaryKey val ownerUid: String,
    val generation: String,
    val manifestSetSha256: String,
    val groups: Long,
    val records: Long,
    val publishedSeconds: Long,
    val publishedNanos: Int,
    val appliedAtMillis: Long
)

@Dao
interface ServerSnapshotDao {
    // ABORT catches duplicate source/native identities even across different months or parts.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRows(rows: List<SnapshotRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setHead(head: SnapshotHead)

    @Query("SELECT * FROM snapshot_heads WHERE ownerUid = :uid")
    suspend fun head(uid: String): SnapshotHead?

    @Query("SELECT * FROM snapshot_heads WHERE ownerUid = :uid")
    fun observeHead(uid: String): Flow<SnapshotHead?>

    @Query("SELECT COUNT(*) FROM snapshot_rows WHERE ownerUid = :uid AND generation = :generation")
    suspend fun count(uid: String, generation: String): Long

    @Query("SELECT * FROM snapshot_rows WHERE ownerUid = :uid AND source = :source AND kind = :kind " +
        "AND generation = (SELECT generation FROM snapshot_heads WHERE ownerUid = :uid) " +
        "AND day BETWEEN :fromDay AND :throughDay ORDER BY day, nativeId")
    fun observeActiveRows(uid: String, source: String, kind: String, fromDay: String, throughDay: String): Flow<List<SnapshotRow>>

    @Query("SELECT * FROM snapshot_rows WHERE ownerUid = :uid AND source = :source AND kind IN (:kinds) " +
        "AND generation = (SELECT generation FROM snapshot_heads WHERE ownerUid = :uid) " +
        "AND day BETWEEN :fromDay AND :throughDay ORDER BY day, nativeId")
    fun observeActiveKinds(uid: String, source: String, kinds: List<String>, fromDay: String, throughDay: String): Flow<List<SnapshotRow>>

    @Query("SELECT * FROM snapshot_rows WHERE ownerUid = :uid " +
        "AND generation = (SELECT generation FROM snapshot_heads WHERE ownerUid = :uid) " +
        "AND ((source = 'garmin' AND kind = 'activity') OR (source = 'gymup' AND kind = 'training') " +
        "OR (source = 'google_health' AND kind = 'exercise')) " +
        "AND day BETWEEN :fromDay AND :throughDay ORDER BY day, source, nativeId")
    fun observeActiveWorkouts(uid: String, fromDay: String, throughDay: String): Flow<List<SnapshotRow>>

    @Query("SELECT * FROM snapshot_rows WHERE ownerUid = :uid " +
        "AND generation = (SELECT generation FROM snapshot_heads WHERE ownerUid = :uid) " +
        "AND ((source IN ('zepp','zepp_archive','google_health') AND kind = 'weight') " +
        "OR (source = 'garmin' AND kind IN ('weight','body_composition','body_composition_range','weigh_ins')) " +
        "OR (source = 'legacy_app' AND kind = 'scale_measurements')) ORDER BY day,source,nativeId")
    fun observeActiveBody(uid: String): Flow<List<SnapshotRow>>

    @Query("SELECT COUNT(*) FROM snapshot_rows WHERE ownerUid = :uid AND generation = :generation AND partId = :partId")
    suspend fun partCount(uid: String, generation: String, partId: String): Long

    @Query("INSERT INTO snapshot_rows (ownerUid,generation,source,kind,nativeId,day,rowJson,partId) " +
        "SELECT ownerUid,:target,source,kind,nativeId,day,rowJson,partId FROM snapshot_rows " +
        "WHERE ownerUid = :uid AND generation = :active AND partId = :partId")
    suspend fun copyPart(uid: String, active: String, target: String, partId: String)

    @Query("DELETE FROM snapshot_rows WHERE ownerUid = :uid AND NOT EXISTS " +
        "(SELECT 1 FROM snapshot_heads WHERE ownerUid = :uid AND generation = snapshot_rows.generation)")
    suspend fun pruneInactive(uid: String)

    @Query("DELETE FROM snapshot_rows WHERE ownerUid = :uid AND generation = :generation " +
        "AND NOT EXISTS (SELECT 1 FROM snapshot_heads WHERE ownerUid = :uid AND generation = :generation)")
    suspend fun discardInactive(uid: String, generation: String)

    @Query("DELETE FROM snapshot_rows WHERE ownerUid = :uid AND generation != :activeGeneration")
    suspend fun deleteOtherGenerations(uid: String, activeGeneration: String)
}

/** Separate additive Room database. Does not migrate, overwrite or mirror AppDatabase. */
@Database(entities = [SnapshotRow::class, SnapshotHead::class], version = 2, exportSchema = false)
abstract class ServerSnapshotDatabase : RoomDatabase() {
    abstract fun snapshots(): ServerSnapshotDao

    companion object {
        const val NAME = "server_snapshot.db"
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE snapshot_rows ADD COLUMN partId TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_snapshot_rows_ownerUid_generation_partId ON snapshot_rows(ownerUid,generation,partId)")
            }
        }
        @Volatile private var instance: ServerSnapshotDatabase? = null
        fun getInstance(context: Context): ServerSnapshotDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, ServerSnapshotDatabase::class.java, NAME)
                    .addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}

class RoomServerSnapshotSink(private val db: ServerSnapshotDatabase) : ServerSnapshotSink {
    private val dao get() = db.snapshots()

    override suspend fun prepare(ownerUid: String) {
        dao.pruneInactive(ownerUid)
    }

    override suspend fun acknowledgeIfUnchanged(ownerUid: String, status: ServerCatalogStatus): Boolean = db.withTransaction {
        val head = dao.head(ownerUid) ?: return@withTransaction false
        if (head.manifestSetSha256 != status.manifestSetSha256 || head.groups != status.groups ||
            head.records != status.records || dao.count(ownerUid,head.generation) != status.records) return@withTransaction false
        val newerOrSame = status.publishedSeconds > head.publishedSeconds ||
            (status.publishedSeconds == head.publishedSeconds && status.publishedNanos >= head.publishedNanos)
        require(newerOrSame) { "older publication" }
        if (head.publishedSeconds != status.publishedSeconds || head.publishedNanos != status.publishedNanos)
            dao.setHead(head.copy(publishedSeconds=status.publishedSeconds,publishedNanos=status.publishedNanos))
        true
    }

    override suspend fun reusePart(ownerUid: String, generation: String, partId: String): Long = db.withTransaction {
        require(partId.isNotBlank())
        val active = dao.head(ownerUid)?.generation ?: return@withTransaction 0L
        val count = dao.partCount(ownerUid,active,partId)
        if (count == 0L) return@withTransaction 0L
        dao.copyPart(ownerUid,active,generation,partId)
        require(dao.partCount(ownerUid,generation,partId) == count)
        count
    }

    override suspend fun stageVerifiedPart(ownerUid: String, generation: String, partId: String, rows: List<VerifiedServerRow>) {
        require(partId.isNotBlank() && rows.isNotEmpty())
        db.withTransaction {
            require(dao.partCount(ownerUid,generation,partId) == 0L)
            for (chunk in rows.chunked(250)) dao.insertRows(chunk.map {
                SnapshotRow(ownerUid,generation,it.source,it.kind,it.nativeId,it.day,it.rowJson,partId)
            })
        }
    }

    override suspend fun stage(ownerUid: String, generation: String, rows: List<VerifiedServerRow>) {
        require(ownerUid.isNotBlank() && generation.isNotBlank())
        dao.insertRows(rows.map { SnapshotRow(ownerUid,generation,it.source,it.kind,it.nativeId,it.day,it.rowJson) })
    }

    override suspend fun activate(ownerUid: String, generation: String, status: ServerCatalogStatus) {
        db.withTransaction {
            require(dao.count(ownerUid,generation) == status.records)
            val prior = dao.head(ownerUid)
            if (prior != null) {
                val newer = status.publishedSeconds > prior.publishedSeconds ||
                    (status.publishedSeconds == prior.publishedSeconds && status.publishedNanos > prior.publishedNanos)
                val same = status.publishedSeconds == prior.publishedSeconds &&
                    status.publishedNanos == prior.publishedNanos &&
                    status.manifestSetSha256 == prior.manifestSetSha256
                require(newer || same) { "older or conflicting publication" }
                require(prior.records == 0L || status.records > 0L) { "refuse replacement of history with an empty snapshot" }
            }
            dao.setHead(SnapshotHead(ownerUid,generation,status.manifestSetSha256,status.groups,status.records,
                status.publishedSeconds,status.publishedNanos,System.currentTimeMillis()))
            dao.deleteOtherGenerations(ownerUid,generation)
        }
    }

    override suspend fun discardInactive(ownerUid: String, generation: String) =
        dao.discardInactive(ownerUid,generation)
}
