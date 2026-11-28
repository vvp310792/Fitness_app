package com.fitnessapp.summary.analytics

import android.content.Context
import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import org.json.JSONArray

/**
 * Derived database, not an upload queue. Raw Garmin, Health Connect, GymUp,
 * Strava and Google Health observations are left in their own tables.
 * One row per reconciled episode; one membership per source/native identity.
 */
@Entity(tableName="canonical_workouts",primaryKeys=["ownerKey","episodeKey"],
    indices=[Index(value=["ownerKey","day"])])
data class CanonicalWorkoutRow(
    val ownerKey:String,
    val episodeKey:String,
    val day:Long,
    val startMillis:Long,
    val sport:String,
    val title:String,
    val durationSeconds:Long?,
    val distanceMeters:Double?,
    val averageHr:Int?,
    val maxHr:Int?,
    val ambiguous:Boolean,
    val possibleKeysJson:String,
    @ColumnInfo(defaultValue="''") val resolutionJson:String=""
)

@Entity(tableName="canonical_workout_members",primaryKeys=["ownerKey","observationKey"],
    foreignKeys=[ForeignKey(entity=CanonicalWorkoutRow::class,
        parentColumns=["ownerKey","episodeKey"],childColumns=["ownerKey","episodeKey"],
        onDelete=ForeignKey.CASCADE)],
    indices=[Index(value=["ownerKey","episodeKey"])])
data class CanonicalWorkoutMember(
    val ownerKey:String,
    val observationKey:String,
    val episodeKey:String,
    @ColumnInfo(defaultValue="0") val isFragment:Boolean=false
)

@Dao
interface CanonicalWorkoutDao {
    @Query("SELECT * FROM canonical_workouts WHERE ownerKey=:owner ORDER BY startMillis DESC,episodeKey")
    suspend fun episodes(owner:String):List<CanonicalWorkoutRow>
    @Query("SELECT * FROM canonical_workout_members WHERE ownerKey=:owner ORDER BY observationKey")
    suspend fun members(owner:String):List<CanonicalWorkoutMember>
    @Query("DELETE FROM canonical_workouts WHERE ownerKey=:owner")
    suspend fun deleteEpisodes(owner:String)
    @Insert(onConflict=OnConflictStrategy.ABORT)
    suspend fun insertEpisodes(rows:List<CanonicalWorkoutRow>)
    @Insert(onConflict=OnConflictStrategy.ABORT)
    suspend fun insertMembers(rows:List<CanonicalWorkoutMember>)
}

@Database(entities=[CanonicalWorkoutRow::class,CanonicalWorkoutMember::class],version=3,exportSchema=false)
abstract class CanonicalWorkoutDatabase:RoomDatabase() {
    abstract fun canonical():CanonicalWorkoutDao
    companion object {
        const val NAME="canonical_workouts.db"
        val MIGRATION_1_2=object:Migration(1,2) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE canonical_workout_members ADD COLUMN isFragment INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_2_3=object:Migration(2,3) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE canonical_workouts ADD COLUMN resolutionJson TEXT NOT NULL DEFAULT ''")
            }
        }
        @Volatile private var instance:CanonicalWorkoutDatabase?=null
        fun get(context:Context):CanonicalWorkoutDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,CanonicalWorkoutDatabase::class.java,NAME)
                .addMigrations(MIGRATION_1_2,MIGRATION_2_3).build().also { instance=it }
        }
    }
}

class CanonicalWorkoutStore(private val db:CanonicalWorkoutDatabase) {
    private val dao get()=db.canonical()

    /**
     * Atomically materialize the reconciled index, then build the archive from
     * persisted memberships and the corresponding raw observations. Never
     * expose a partially replaced generation or merge different owners.
     * A repeated identical input does not write the index again.
     */
    suspend fun reconcile(ownerKey:String,archive:ArchiveResult):ArchiveResult=db.withTransaction {
        require(ownerKey.isNotBlank())
        val episodes=archive.episodes
        val resolutionByKey=episodes.associate{it.episode.primary.key to it.resolution}
        val observations=episodes.flatMap { it.observations }
        val byKey=observations.associateBy { it.session.key }
        require(byKey.size==observations.size) { "multiple canonical memberships" }
        val rows=episodes.map { e ->
            CanonicalWorkoutRow(ownerKey,e.episode.primary.key,e.primary.day,e.startMillis,
                e.primary.session.sport.name,e.primary.title,e.durationSeconds,e.distanceMeters,e.averageHr,e.maxHr,
                e.episode.ambiguous,JSONArray(e.episode.possibleDuplicateKeys.sorted()).toString(),
                e.resolution?.persistedJson().orEmpty())
        }.sortedWith(compareByDescending<CanonicalWorkoutRow> { it.startMillis }.thenBy { it.episodeKey })
        val members=episodes.flatMap { e ->
            require(e.episode.primary.key !in e.episode.fragmentKeys)
            require(e.episode.fragmentKeys.all { key -> e.observations.any { it.session.key==key } })
            e.observations.map { o -> CanonicalWorkoutMember(ownerKey,o.session.key,e.episode.primary.key,
                isFragment=o.session.key in e.episode.fragmentKeys) }
        }.sortedBy { it.observationKey }
        require(rows.map { it.episodeKey }.distinct().size==rows.size)
        var storedRows=dao.episodes(ownerKey).sortedWith(compareByDescending<CanonicalWorkoutRow> { it.startMillis }.thenBy { it.episodeKey })
        var storedMembers=dao.members(ownerKey).sortedBy { it.observationKey }
        if(storedRows!=rows || storedMembers!=members) {
            dao.deleteEpisodes(ownerKey)
            rows.chunked(250).forEach { dao.insertEpisodes(it) }
            members.chunked(250).forEach { dao.insertMembers(it) }
            storedRows=dao.episodes(ownerKey).sortedWith(compareByDescending<CanonicalWorkoutRow> { it.startMillis }.thenBy { it.episodeKey })
            storedMembers=dao.members(ownerKey).sortedBy { it.observationKey }
        }
        require(storedRows==rows && storedMembers==members) { "canonical index readback mismatch" }
        val grouped=storedMembers.groupBy { it.episodeKey }
        val result=storedRows.map { row ->
            val primary=byKey.getValue(row.episodeKey)
            val items=grouped.getValue(row.episodeKey).map { byKey.getValue(it.observationKey) }.sortedBy { it.session.key }
            require(items.any { it.session.key==primary.session.key })
            val related=JSONArray(row.possibleKeysJson).let { a -> (0 until a.length()).map { a.getString(it) } }
            val fragments=grouped.getValue(row.episodeKey).filter { it.isFragment }.map { it.observationKey }.sorted()
            val resolution=resolutionByKey[row.episodeKey]
            require(row.resolutionJson==resolution?.persistedJson().orEmpty())
            ArchiveEpisode(SessionEpisode(primary.session,items.map { it.session },row.ambiguous,related,fragments),primary,items,resolution)
        }
        require(result.sumOf { it.observations.size }==byKey.size)
        archive.copy(episodes=result,canonicalStored=true)
    }
}
