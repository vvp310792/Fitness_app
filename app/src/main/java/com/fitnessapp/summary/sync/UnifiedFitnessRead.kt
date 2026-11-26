package com.fitnessapp.summary.sync

import com.fitnessapp.summary.FitnessSummaryApp
import com.fitnessapp.summary.analytics.ArchiveResult
import com.fitnessapp.summary.analytics.LocalWorkoutArchive
import com.fitnessapp.summary.data.GarminActivity
import com.fitnessapp.summary.data.GarminDailyExtra
import com.fitnessapp.summary.data.GarminSleep
import com.fitnessapp.summary.data.StrengthSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.time.LocalDate

/** Owner-scoped, read-only views over the last fully verified snapshot and local Room.
 * Never passes snapshot data to writers, importers, mirror or the upload queue. */
@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedFitnessRead(private val app: FitnessSummaryApp) {
    private val db get() = app.database
    private val snapshots get() = ServerSnapshotDatabase.getInstance(app).snapshots()

    private fun ownerRows(load: (String) -> Flow<List<SnapshotRow>>): Flow<List<VerifiedServerRow>> {
        if (!FirebaseSetup.isConfigured) return flowOf(emptyList())
        return app.authManager.authStateFlow().map { it?.uid }.distinctUntilChanged().flatMapLatest { uid ->
            if (uid == null) flowOf(emptyList())
            else load(uid).map { rs ->
                if (app.authManager.currentUser?.uid != uid) emptyList()
                else rs.map { VerifiedServerRow(it.source,it.kind,it.nativeId,it.day,it.rowJson) }
            }.onStart { emit(emptyList()) }
        }
    }
    private fun rows(source: String, kinds: List<String>, from: Long, to: Long): Flow<List<VerifiedServerRow>> =
        ownerRows { uid -> snapshots.observeActiveKinds(uid,source,kinds,
            LocalDate.ofEpochDay(from).toString(),LocalDate.ofEpochDay(to).toString()) }

    private fun workoutRows(from: Long, to: Long) =
        ownerRows { uid -> snapshots.observeActiveWorkouts(uid,
            LocalDate.ofEpochDay(from).toString(),LocalDate.ofEpochDay(to).toString()) }
            .map(ServerFitnessProjection::workouts)

    fun observeArchive(): Flow<ArchiveResult> {
        val first=LocalDate.of(1900,1,1).toEpochDay()
        val last=LocalDate.of(9999,12,31).toEpochDay()
        return combine(db.workoutDao().observeAll(),
            db.garminActivityDao().observeRange(Long.MIN_VALUE,Long.MAX_VALUE),
            db.stravaActivityDao().observeRange(Long.MIN_VALUE,Long.MAX_VALUE),
            db.strengthSetDao().observeRange(Long.MIN_VALUE,Long.MAX_VALUE),
            workoutRows(first,last)) { h,g,s,w,server ->
            LocalWorkoutArchive.build(h,g,s,w,server)
        }.flowOn(Dispatchers.Default)
    }

    fun observeGarminDays(from: Long,to: Long): Flow<List<GarminDailyExtra>> =
        combine(db.garminDailyExtraDao().observeRange(from,to), rows("garmin",listOf("daily"),from,to)) { local,server ->
            val merged=local.associateBy { it.dateEpochDay }.toMutableMap()
            for (s in server.mapNotNull(ServerFitnessProjection::daily)) {
                val l=merged[s.dateEpochDay]
                merged[s.dateEpochDay]=if(l==null) s else fillDay(l,s)
            }
            merged.values.sortedBy { it.dateEpochDay }
        }.flowOn(Dispatchers.Default)

    fun observeGarminDay(day: Long): Flow<GarminDailyExtra?> =
        observeGarminDays(day,day).map { it.firstOrNull() }

    fun observeGarminSleeps(from: Long,to: Long): Flow<List<GarminSleep>> =
        combine(db.garminSleepDao().observeRange(from,to), rows("garmin",listOf("sleep_summary","sleep_detail"),from,to)) { local,server ->
            // Keep each night whole; detail beats summary, and a valid local night
            // retains proprietary scores and stages. Never splice stages across nights.
            val merged=mutableMapOf<Long,GarminSleep>()
            for (r in server.sortedBy { if(it.kind=="sleep_detail") 1 else 0 }) {
                ServerFitnessProjection.sleep(r)?.let { merged[it.dateEpochDay]=it }
            }
            for (l in local) if(l.sleepSeconds>0 || l.dateEpochDay !in merged) merged[l.dateEpochDay]=l
            merged.values.sortedBy { it.dateEpochDay }
        }.flowOn(Dispatchers.Default)

    fun observeGarminSleep(day: Long): Flow<GarminSleep?> =
        observeGarminSleeps(day,day).map { it.firstOrNull() }

    fun observeGarminActivities(from: Long,to: Long): Flow<List<GarminActivity>> =
        combine(db.garminActivityDao().observeRange(from,to), rows("garmin",listOf("activity"),from,to)) { local,server ->
            val remote=ServerFitnessProjection.workouts(server).observations.mapNotNull { it.garmin }
            // Exact activity IDs only; keep detailed local rows for matching IDs.
            (remote+local).associateBy { it.activityId }.values.sortedBy { it.startTimeMillis }
        }.flowOn(Dispatchers.Default)

    fun observeGarminActivitiesForDay(day: Long): Flow<List<GarminActivity>> =
        observeGarminActivities(day,day)

    fun observeStrengthSets(from: Long,to: Long): Flow<List<StrengthSet>> =
        combine(db.strengthSetDao().observeRange(from,to), rows("gymup",listOf("training"),from,to)) { local,server ->
            val remote=ServerFitnessProjection.workouts(server).observations.flatMap { it.sets }.groupBy { it.startMillis }
            // One whole set list per exact session start, not a union of differently
            // numbered exercises. Preserve existing local sets where already present.
            val merged=remote.toMutableMap()
            for ((start,sets) in local.groupBy { it.startMillis }) merged[start]=sets
            merged.values.flatten().sortedWith(compareBy<StrengthSet> { it.startMillis }.thenBy { it.setIndex })
        }.flowOn(Dispatchers.Default)

    companion object {
        /** Historical first pass: fill local gaps, never regress a local measurement with a
         * partial snapshot. Legacy zero-as-missing convention remains explicit. */
        internal fun fillDay(l: GarminDailyExtra,s: GarminDailyExtra): GarminDailyExtra {
            require(l.dateEpochDay==s.dateEpochDay)
            fun n(a:Int,b:Int)=if(a>0) a else b
            val zones=if(l.hasStressZones) l else s
            return l.copy(
                totalSteps=if(l.totalSteps>0) l.totalSteps else s.totalSteps,
                totalDistanceMeters=n(l.totalDistanceMeters,s.totalDistanceMeters),
                totalKilocalories=n(l.totalKilocalories,s.totalKilocalories),
                activeKilocalories=n(l.activeKilocalories,s.activeKilocalories),
                restingHeartRate=n(l.restingHeartRate,s.restingHeartRate),
                minHeartRate=n(l.minHeartRate,s.minHeartRate), maxHeartRate=n(l.maxHeartRate,s.maxHeartRate),
                averageStressLevel=n(l.averageStressLevel,s.averageStressLevel),
                maxStressLevel=n(l.maxStressLevel,s.maxStressLevel),
                stressQualifier=l.stressQualifier.ifBlank { s.stressQualifier },
                restStressSeconds=zones.restStressSeconds,lowStressSeconds=zones.lowStressSeconds,
                mediumStressSeconds=zones.mediumStressSeconds,highStressSeconds=zones.highStressSeconds,
                floorsAscended=n(l.floorsAscended,s.floorsAscended),floorsDescended=n(l.floorsDescended,s.floorsDescended),
                moderateIntensityMinutes=n(l.moderateIntensityMinutes,s.moderateIntensityMinutes),
                vigorousIntensityMinutes=n(l.vigorousIntensityMinutes,s.vigorousIntensityMinutes)
            )
        }
    }
}
