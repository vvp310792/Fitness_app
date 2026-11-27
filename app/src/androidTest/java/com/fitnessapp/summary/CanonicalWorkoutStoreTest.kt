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
                val db=Room.databaseBuilder(context,CanonicalWorkoutDatabase::class.java,name).build()
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
                val db=Room.databaseBuilder(context,CanonicalWorkoutDatabase::class.java,name).build()
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
}
