package com.fitnessapp.summary.sync

import com.fitnessapp.summary.FitnessSummaryApp
import com.fitnessapp.summary.analytics.ArchiveResult
import com.fitnessapp.summary.analytics.CanonicalWorkoutDatabase
import com.fitnessapp.summary.analytics.CanonicalWorkoutStore
import com.fitnessapp.summary.debug.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.shareIn
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

    /** One daily value per metric, with Google Health filling gaps in local Health Connect.
     * The existing DayView still gives original Garmin measurements precedence. */
    fun observeHealthDays(from: Long,to: Long): Flow<List<GoogleHealthDay>> =
        combine(db.dailySummaryDao().observeRange(from,to),rows("google_health",listOf(
            "steps","distance","active-energy-burned","heart-rate","daily-resting-heart-rate",
            "sleep","total-calories","floors"),from-1,to+1)) { local,remote ->
            val byDay=local.associateBy { it.dateEpochDay }
            val google=GoogleHealthProjection.days(remote).associateBy { it.summary.dateEpochDay }
            (byDay.keys+google.keys).filter { it in from..to }.sorted().mapNotNull { day ->
                val r=google[day]
                GoogleHealthProjection.merge(byDay[day],r?.summary)?.let {
                    GoogleHealthDay(it,r?.floors,r?.metricSources.orEmpty())
                }
            }
        }.flowOn(Dispatchers.Default)

    fun observeHeightUpTo(day:Long):Flow<GoogleHeight?> =
        rows("google_health",listOf("height"),LocalDate.of(1900,1,1).toEpochDay(),day+1)
            .map { GoogleHealthProjection.heights(it).lastOrNull { r -> r.day<=day } }.flowOn(Dispatchers.Default)

    /** Select one coherent weigh-in per calendar day across primary and transported copies. */
    fun observeBodyDays(from:Long,to:Long):Flow<List<BodyDay>> = combine(
        db.scaleMeasurementDao().observeRange(from,to),
        db.garminBodyCompositionDao().observeRange(from,to),
        db.fitDayDao().observeRange(from,to),
        ownerRows { uid -> snapshots.observeActiveBody(uid) }
    ) { scales,garmin,fit,remote ->
        BodyProjection.choose(scales.mapNotNull(BodyProjection::scale)+garmin.mapNotNull(BodyProjection::garmin)+
            fit.mapNotNull(BodyProjection::fit)+BodyProjection.readings(remote))
            .filter { it.day in from..to }
    }.flowOn(Dispatchers.Default)

    private data class ScopedArchive(val uid:String?,val archive:ArchiveResult)
    private val archiveScope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val canonicalStore by lazy { CanonicalWorkoutStore(CanonicalWorkoutDatabase.get(app)) }
    private fun currentArchiveOwner():String? =
        if(FirebaseSetup.isConfigured) app.authManager.currentUser?.uid else null

    /** One materialized canonical index, shared across all archive readers.
     * No partial server projection is emitted before the active snapshot query completes.
     * Account changes cancel the old read and every result is scoped to its owner. */
    private val sharedArchive by lazy {
        val owner:Flow<String?> = if(FirebaseSetup.isConfigured)
            app.authManager.authStateFlow().map { it?.uid }.distinctUntilChanged()
        else flowOf(null)
        owner.flatMapLatest { uid ->
            val first="1900-01-01"
            val last="9999-12-31"
            val remote:Flow<ServerWorkoutProjection> = if(uid==null) flowOf(ServerWorkoutProjection())
            else snapshots.observeActiveWorkouts(uid,first,last).map { rows ->
                ServerFitnessProjection.workouts(rows.map {
                    VerifiedServerRow(it.source,it.kind,it.nativeId,it.day,it.rowJson)
                })
            }
            val remoteWithReview=combine(remote,ReviewedWorkoutPolicyGateway.observe(uid)){server,review->server to review}
            combine(db.workoutDao().observeAll(),
                db.garminActivityDao().observeRange(Long.MIN_VALUE,Long.MAX_VALUE),
                db.stravaActivityDao().observeRange(Long.MIN_VALUE,Long.MAX_VALUE),
                db.strengthSetDao().observeRange(Long.MIN_VALUE,Long.MAX_VALUE),
                remoteWithReview) { h,g,s,w,remoteData ->
                val (server,review)=remoteData
                // Owner confirmed post-2021-10-27 Strava is a Garmin export. Keep unmatched fallback.
                LocalWorkoutArchive.build(h,g,s,w,server,garminStravaCutoffMillis=1635267600000L,reviewedPlan=review)
            }.filter { currentArchiveOwner()==uid }.map { archive ->
                val stored=try {
                    canonicalStore.reconcile(uid?.let { "firebase:$it" } ?: "local",archive)
                } catch(e:CancellationException) {
                    throw e
                } catch(e:Exception) {
                    // The raw archive remains usable; never claim persistence on a failed write.
                    AppLog.w("CanonicalWorkout","Canonical index failed: "+e.javaClass.simpleName)
                    archive
                }
                ScopedArchive(uid,stored)
            }.filter { currentArchiveOwner()==uid }
        }.flowOn(Dispatchers.Default).shareIn(archiveScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis=0,replayExpirationMillis=0),replay=1)
    }

    fun observeArchive(): Flow<ArchiveResult> =
        sharedArchive.filter { it.uid==currentArchiveOwner() }.map { it.archive }

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
