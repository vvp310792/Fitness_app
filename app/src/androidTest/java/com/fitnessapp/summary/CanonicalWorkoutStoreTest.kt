package com.fitnessapp.summary

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fitnessapp.summary.analytics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CanonicalWorkoutStoreTest {
    private fun archive():ArchiveResult {
        val start=1700000000000L
        val a=FitnessSession(FitnessSource.GARMIN,"a",FitnessSport.SWIM,start,4800,200.0,recordedSeconds=2400)
        val b=FitnessSession(FitnessSource.GOOGLE_HEALTH,"b",FitnessSport.SWIM,start,2400,
            transportOrigin="com.garmin.android.apps.connectmobile",recordedSeconds=2400)
        val c=FitnessSession(FitnessSource.HEALTH_CONNECT,"c",FitnessSport.SWIM,start,2400,200.0,recordedSeconds=2400)
        val members=listOf(a,b,c).map { ArchiveObservation(it,19675L,"Pool",2400,90,null) }
        val e=CanonicalFitness.episodes(listOf(a,b,c)).single()
        return ArchiveResult(listOf(ArchiveEpisode(e,members.first(),members)),0,confirmedDuplicateObservations=2)
    }
    @Test fun persistedMembershipSurvivesReopeningAndReconcileIsOwnerScoped()=runBlocking(Dispatchers.IO) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="canonical-fixture-"+UUID.randomUUID()+".db"
        val input=archive()
        try {
            run {
                val db=Room.databaseBuilder(context,CanonicalWorkoutDatabase::class.java,name).addMigrations(CanonicalWorkoutDatabase.MIGRATION_1_2,CanonicalWorkoutDatabase.MIGRATION_2_3).build()
                try {
                val result=CanonicalWorkoutStore(db).reconcile("owner-a",input)
                assertTrue(result.canonicalStored)
                assertEquals(1,result.episodes.size)
                assertEquals(3,db.canonical().members("owner-a").size)
                assertEquals(1,db.canonical().episodes("owner-a").size)
                CanonicalWorkoutStore(db).reconcile("owner-b",input)
                } finally { db.close() }
            }
            run {
                val db=Room.databaseBuilder(context,CanonicalWorkoutDatabase::class.java,name).addMigrations(CanonicalWorkoutDatabase.MIGRATION_1_2,CanonicalWorkoutDatabase.MIGRATION_2_3).build()
                try {
                val dao=db.canonical()
                assertEquals(1,dao.episodes("owner-a").size)
                assertEquals(3,dao.members("owner-a").size)
                assertEquals(1,dao.members("owner-a").map { it.episodeKey }.distinct().size)
                assertEquals(0,dao.members("owner-other").size)
                val repeat=CanonicalWorkoutStore(db).reconcile("owner-a",input)
                assertEquals(1,repeat.episodes.size)
                assertEquals(3,repeat.episodes.single().observations.size)
                CanonicalWorkoutStore(db).reconcile("owner-a",ArchiveResult(emptyList(),0))
                assertTrue(dao.episodes("owner-a").isEmpty())
                assertTrue(dao.members("owner-a").isEmpty())
                assertEquals(3,dao.members("owner-b").size)
                } finally { db.close() }
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun reviewedResolutionSurvivesCanonicalReadback()=runBlocking(Dispatchers.IO) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,CanonicalWorkoutDatabase::class.java).build()
        try {
            val original=archive()
            val episode=original.episodes.single().copy(resolution=ReviewedResolution("synthetic","probable_same_event",true))
            val reviewed=original.copy(episodes=listOf(episode),reviewedPolicyAvailable=true,reviewedPolicyApplied=1)
            val stored=CanonicalWorkoutStore(db).reconcile("owner",reviewed)
            assertTrue(stored.canonicalStored)
            assertTrue(stored.episodes.single().probable)
            assertEquals(episode.resolution?.persistedJson(),db.canonical().episodes("owner").single().resolutionJson)
            assertEquals(3,db.canonical().members("owner").size)
        } finally { db.close() }
    }
    @Test fun migrateV1AndPersistFragmentRoleWithoutLosingOriginalRows()=runBlocking(Dispatchers.IO) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="canonical-migration-"+UUID.randomUUID()+".db"
        try {
            context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use { old ->
                old.execSQL("CREATE TABLE canonical_workouts (ownerKey TEXT NOT NULL, episodeKey TEXT NOT NULL, day INTEGER NOT NULL, startMillis INTEGER NOT NULL, sport TEXT NOT NULL, title TEXT NOT NULL, durationSeconds INTEGER, distanceMeters REAL, averageHr INTEGER, maxHr INTEGER, ambiguous INTEGER NOT NULL, possibleKeysJson TEXT NOT NULL, PRIMARY KEY(ownerKey,episodeKey))")
                old.execSQL("CREATE INDEX index_canonical_workouts_ownerKey_day ON canonical_workouts(ownerKey,day)")
                old.execSQL("CREATE TABLE canonical_workout_members (ownerKey TEXT NOT NULL, observationKey TEXT NOT NULL, episodeKey TEXT NOT NULL, PRIMARY KEY(ownerKey,observationKey), FOREIGN KEY(ownerKey,episodeKey) REFERENCES canonical_workouts(ownerKey,episodeKey) ON DELETE CASCADE ON UPDATE NO ACTION)")
                old.execSQL("CREATE INDEX index_canonical_workout_members_ownerKey_episodeKey ON canonical_workout_members(ownerKey,episodeKey)")
                old.execSQL("INSERT INTO canonical_workouts VALUES ('other','old',19675,1700000000000,'SWIM','Pool',2400,200,90,NULL,0,'[]')")
                old.execSQL("INSERT INTO canonical_workout_members VALUES ('other','old','old')")
                old.version=1
            }
            val db=Room.databaseBuilder(context,CanonicalWorkoutDatabase::class.java,name)
                .addMigrations(CanonicalWorkoutDatabase.MIGRATION_1_2,CanonicalWorkoutDatabase.MIGRATION_2_3).build()
            try {
                assertFalse(db.canonical().members("other").single().isFragment)
                assertEquals("",db.canonical().episodes("other").single().resolutionJson)
                val start=1700000000000L
                val a=FitnessSession(FitnessSource.GARMIN,"watch",FitnessSport.STRENGTH,start,1800,recordedSeconds=1800)
                val f=FitnessSession(FitnessSource.GOOGLE_HEALTH,"fit",FitnessSport.STRENGTH,start+480000,900,
                    transportOrigin="com.google.android.apps.fitness",recordedSeconds=900)
                val members=listOf(ArchiveObservation(a,19675L,"Strength",1800,100,null),
                    ArchiveObservation(f,19675L,"Partial",900,160,null))
                val e=CanonicalFitness.episodes(listOf(a,f)).single()
                val result=CanonicalWorkoutStore(db).reconcile("owner",ArchiveResult(listOf(ArchiveEpisode(e,members.first(),members)),0))
                assertTrue(result.canonicalStored)
                assertEquals(1,result.episodes.size)
                assertEquals(listOf(f.key),result.episodes.single().episode.fragmentKeys)
                assertEquals(1800L,result.episodes.single().durationSeconds)
                assertEquals(100,result.episodes.single().averageHr)
                assertEquals(2,db.canonical().members("owner").size)
                assertEquals(f.key,db.canonical().members("owner").single { it.isFragment }.observationKey)
                assertEquals(1,db.canonical().members("other").size)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

}
