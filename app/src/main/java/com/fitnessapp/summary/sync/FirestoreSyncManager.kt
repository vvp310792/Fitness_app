package com.fitnessapp.summary.sync

import com.fitnessapp.summary.data.AppDatabase
import com.fitnessapp.summary.data.DailySummary
import com.fitnessapp.summary.data.Workout
import com.fitnessapp.summary.debug.AppLog
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Mirrors Room into Firestore under `users/{uid}/...`, and merges back whatever
 * other devices have written there.
 *
 * Document ids are the rows' own natural keys - the epoch day for a summary, Health
 * Connect's record id for a workout - so the same day synced from two phones lands
 * on one document instead of two, and a re-sync is idempotent by construction.
 *
 * Writes go straight to Firestore; its SDK queues them offline and retries on its
 * own, so there's no WorkManager retry logic here. Room stays the thing the UI
 * reads: this class only ever feeds it.
 *
 * Note this is a *second* sync layer, downstream of Health Connect and with a
 * different job. Health Connect answers "what did the watch record on this phone";
 * Firestore answers "what has any of my devices ever seen", and keeps history that
 * Health Connect itself has long since pruned.
 */
class FirestoreSyncManager(private val database: AppDatabase) {

    private val db = FirebaseFirestore.getInstance()
    private var summariesListener: ListenerRegistration? = null
    private var workoutsListener: ListenerRegistration? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private fun summariesRef(uid: String) =
        db.collection("users").document(uid).collection("dailySummaries")

    private fun workoutsRef(uid: String) =
        db.collection("users").document(uid).collection("workouts")

    /** Attaches realtime listeners for [uid]'s data. Call on sign-in. Safe to call again. */
    fun start(uid: String) {
        stop()
        AppLog.i("FirestoreSyncManager", "Слушатели Firestore подключены")
        summariesListener = summariesRef(uid).addSnapshotListener { snapshot, error ->
            // error was previously discarded here - a permission-denied rules failure
            // or a dropped listener would fail completely silently, no different from
            // "nothing changed". This is very much a live suspect for "cloud data
            // doesn't come through": firestore.rules requires request.auth.uid == uid,
            // so anything wrong with the signed-in user's token shows up exactly here.
            if (error != null) {
                AppLog.e("FirestoreSyncManager", "Слушатель dailySummaries упал", error)
                return@addSnapshotListener
            }
            val changes = snapshot?.documentChanges ?: return@addSnapshotListener
            scope.launch { mergeSummaryChanges(changes) }
        }
        workoutsListener = workoutsRef(uid).addSnapshotListener { snapshot, error ->
            if (error != null) {
                AppLog.e("FirestoreSyncManager", "Слушатель workouts упал", error)
                return@addSnapshotListener
            }
            val changes = snapshot?.documentChanges ?: return@addSnapshotListener
            scope.launch { mergeWorkoutChanges(changes) }
        }
    }

    /** Detaches listeners. Call on sign-out. */
    fun stop() {
        summariesListener?.remove()
        workoutsListener?.remove()
        summariesListener = null
        workoutsListener = null
    }

    // ---- push ---------------------------------------------------------------

    fun pushDailySummary(uid: String, summary: DailySummary) {
        val data = mapOf(
            "dateEpochDay" to summary.dateEpochDay,
            "steps" to summary.steps,
            "activeCaloriesKcal" to summary.activeCaloriesKcal,
            "totalCaloriesKcal" to summary.totalCaloriesKcal,
            "distanceMeters" to summary.distanceMeters,
            "restingHeartRate" to summary.restingHeartRate,
            "avgHeartRate" to summary.avgHeartRate,
            "minHeartRate" to summary.minHeartRate,
            "maxHeartRate" to summary.maxHeartRate,
            "sleepTotalMinutes" to summary.sleepTotalMinutes,
            "sleepDeepMinutes" to summary.sleepDeepMinutes,
            "sleepLightMinutes" to summary.sleepLightMinutes,
            "sleepRemMinutes" to summary.sleepRemMinutes,
            "sleepAwakeMinutes" to summary.sleepAwakeMinutes,
            "workoutCount" to summary.workoutCount,
            "workoutMinutes" to summary.workoutMinutes,
            "updatedAtMillis" to summary.updatedAtMillis
        )
        summariesRef(uid).document(summary.dateEpochDay.toString())
            .set(data, SetOptions.merge())
            // DEBUG, not silence - a run of PERMISSION_DENIED errors followed by a sync
            // with *no* log lines at all used to be genuinely ambiguous (fixed and quiet,
            // or nothing attempted?). This is what turns "no errors" into "confirmed sent".
            .addOnSuccessListener {
                AppLog.d("FirestoreSyncManager", "Сводка за ${summary.dateEpochDay} отправлена")
            }
            .addOnFailureListener { e ->
                AppLog.e("FirestoreSyncManager", "Не удалось отправить сводку за ${summary.dateEpochDay}", e)
            }
    }

    fun pushWorkout(uid: String, workout: Workout) {
        val data = mapOf(
            "recordId" to workout.recordId,
            "dateEpochDay" to workout.dateEpochDay,
            "startTimeMillis" to workout.startTimeMillis,
            "endTimeMillis" to workout.endTimeMillis,
            "exerciseType" to workout.exerciseType,
            "title" to workout.title,
            "durationMinutes" to workout.durationMinutes,
            "distanceMeters" to workout.distanceMeters,
            "activeCaloriesKcal" to workout.activeCaloriesKcal,
            "avgHeartRate" to workout.avgHeartRate,
            "maxHeartRate" to workout.maxHeartRate,
            "updatedAtMillis" to workout.updatedAtMillis
        )
        workoutsRef(uid).document(sanitizeDocId(workout.recordId))
            .set(data, SetOptions.merge())
            .addOnSuccessListener {
                AppLog.d("FirestoreSyncManager", "Тренировка ${workout.recordId} отправлена")
            }
            .addOnFailureListener { e ->
                AppLog.e("FirestoreSyncManager", "Не удалось отправить тренировку ${workout.recordId}", e)
            }
    }

    /** One-shot upload of everything held locally. Used right after a first sign-in. */
    suspend fun pushAll(uid: String) {
        database.dailySummaryDao().getAllOnce().forEach { pushDailySummary(uid, it) }
        database.workoutDao().getAllOnce().forEach { pushWorkout(uid, it) }
    }

    // ---- merge back ---------------------------------------------------------

    private suspend fun mergeSummaryChanges(changes: List<DocumentChange>) {
        AppLog.d("FirestoreSyncManager", "Получено изменений dailySummaries: ${changes.size}")
        val dao = database.dailySummaryDao()
        for (change in changes) {
            if (change.type == DocumentChange.Type.REMOVED) continue
            val doc = change.document
            val dateEpochDay = doc.getLong("dateEpochDay") ?: continue

            val incoming = DailySummary(
                dateEpochDay = dateEpochDay,
                steps = doc.getLong("steps") ?: 0L,
                activeCaloriesKcal = doc.getLong("activeCaloriesKcal")?.toInt() ?: 0,
                totalCaloriesKcal = doc.getLong("totalCaloriesKcal")?.toInt() ?: 0,
                distanceMeters = doc.getLong("distanceMeters")?.toInt() ?: 0,
                restingHeartRate = doc.getLong("restingHeartRate")?.toInt() ?: 0,
                avgHeartRate = doc.getLong("avgHeartRate")?.toInt() ?: 0,
                minHeartRate = doc.getLong("minHeartRate")?.toInt() ?: 0,
                maxHeartRate = doc.getLong("maxHeartRate")?.toInt() ?: 0,
                sleepTotalMinutes = doc.getLong("sleepTotalMinutes")?.toInt() ?: 0,
                sleepDeepMinutes = doc.getLong("sleepDeepMinutes")?.toInt() ?: 0,
                sleepLightMinutes = doc.getLong("sleepLightMinutes")?.toInt() ?: 0,
                sleepRemMinutes = doc.getLong("sleepRemMinutes")?.toInt() ?: 0,
                sleepAwakeMinutes = doc.getLong("sleepAwakeMinutes")?.toInt() ?: 0,
                workoutCount = doc.getLong("workoutCount")?.toInt() ?: 0,
                workoutMinutes = doc.getLong("workoutMinutes")?.toInt() ?: 0,
                updatedAtMillis = doc.getLong("updatedAtMillis") ?: 0L
            )

            // Last-write-wins on updatedAtMillis. Both sides derive from the same
            // upstream Health Connect data, so there's nothing to merge field by
            // field - the fresher read is simply the better one. Without this check
            // a stale document arriving from another device could overwrite a day
            // this phone had just re-read.
            val existing = dao.getDayOnce(dateEpochDay)
            if (existing == null || incoming.updatedAtMillis >= existing.updatedAtMillis) {
                dao.upsert(incoming)
            }
        }
    }

    private suspend fun mergeWorkoutChanges(changes: List<DocumentChange>) {
        AppLog.d("FirestoreSyncManager", "Получено изменений workouts: ${changes.size}")
        val dao = database.workoutDao()
        for (change in changes) {
            if (change.type == DocumentChange.Type.REMOVED) continue
            val doc = change.document
            val recordId = doc.getString("recordId") ?: continue

            dao.upsert(
                Workout(
                    recordId = recordId,
                    dateEpochDay = doc.getLong("dateEpochDay") ?: 0L,
                    startTimeMillis = doc.getLong("startTimeMillis") ?: 0L,
                    endTimeMillis = doc.getLong("endTimeMillis") ?: 0L,
                    exerciseType = doc.getLong("exerciseType")?.toInt() ?: 0,
                    title = doc.getString("title").orEmpty(),
                    durationMinutes = doc.getLong("durationMinutes")?.toInt() ?: 0,
                    distanceMeters = doc.getLong("distanceMeters")?.toInt() ?: 0,
                    activeCaloriesKcal = doc.getLong("activeCaloriesKcal")?.toInt() ?: 0,
                    avgHeartRate = doc.getLong("avgHeartRate")?.toInt() ?: 0,
                    maxHeartRate = doc.getLong("maxHeartRate")?.toInt() ?: 0,
                    updatedAtMillis = doc.getLong("updatedAtMillis") ?: 0L
                )
            )
        }
    }

    /**
     * Firestore document ids may not contain "/" (and must not be "." or ".."). Health
     * Connect ids are UUIDs in practice, but they're an opaque provider-supplied string
     * by contract, so they get sanitised rather than trusted.
     */
    private fun sanitizeDocId(raw: String): String {
        val cleaned = raw.replace('/', '_')
        return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") "id_${raw.hashCode()}" else cleaned
    }
}
