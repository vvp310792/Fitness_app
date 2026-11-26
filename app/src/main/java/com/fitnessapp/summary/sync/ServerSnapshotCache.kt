package com.fitnessapp.summary.sync

import android.content.Context
import androidx.room.Database
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
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "snapshot_rows",
    primaryKeys = ["ownerUid", "generation", "source", "kind", "nativeId"],
    indices = [Index(value = ["ownerUid", "generation", "source", "kind", "day"])])
data class SnapshotRow(
    val ownerUid: String,
    val generation: String,
    val source: String,
    val kind: String,
    val nativeId: String,
    val day: String,
    val rowJson: String
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

    @Query("DELETE FROM snapshot_rows WHERE ownerUid = :uid AND generation = :generation " +
        "AND NOT EXISTS (SELECT 1 FROM snapshot_heads WHERE ownerUid = :uid AND generation = :generation)")
    suspend fun discardInactive(uid: String, generation: String)

    @Query("DELETE FROM snapshot_rows WHERE ownerUid = :uid AND generation != :activeGeneration")
    suspend fun deleteOtherGenerations(uid: String, activeGeneration: String)
}

/** Separate additive Room database. Does not migrate, overwrite or mirror AppDatabase. */
@Database(entities = [SnapshotRow::class, SnapshotHead::class], version = 1, exportSchema = false)
abstract class ServerSnapshotDatabase : RoomDatabase() {
    abstract fun snapshots(): ServerSnapshotDao

    companion object {
        const val NAME = "server_snapshot.db"
        @Volatile private var instance: ServerSnapshotDatabase? = null
        fun getInstance(context: Context): ServerSnapshotDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, ServerSnapshotDatabase::class.java, NAME)
                    .build().also { instance = it }
            }
    }
}

class RoomServerSnapshotSink(private val db: ServerSnapshotDatabase) : ServerSnapshotSink {
    private val dao get() = db.snapshots()

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
