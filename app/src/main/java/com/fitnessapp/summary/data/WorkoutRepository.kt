package com.fitnessapp.summary.data

import com.fitnessapp.summary.sync.FirestoreSyncManager
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class WorkoutRepository(
    private val dao: WorkoutDao,
    private val syncManager: FirestoreSyncManager? = null,
    private val currentUid: () -> String? = { null }
) {

    fun observeForDay(date: LocalDate): Flow<List<Workout>> = dao.observeForDay(date.toEpochDay())

    fun observeRange(from: LocalDate, to: LocalDate): Flow<List<Workout>> =
        dao.observeRange(from.toEpochDay(), to.toEpochDay())

    fun observeRecent(limit: Int = 100): Flow<List<Workout>> = dao.observeRecent(limit)

    suspend fun getAllOnce(): List<Workout> = dao.getAllOnce()

    /**
     * Replaces a day's sessions with exactly what Health Connect currently reports.
     *
     * The delete-then-insert is what keeps a workout the user deleted upstream from
     * living on here forever - a plain upsert would only ever add. It's scoped to the
     * single day being synced, and an empty [workouts] on a day that Health Connect
     * couldn't be read at all never reaches here (see HealthSyncManager), so this
     * can't quietly erase history because of a permission blip.
     */
    suspend fun replaceDay(date: LocalDate, workouts: List<Workout>) {
        dao.deleteForDay(date.toEpochDay())
        if (workouts.isNotEmpty()) {
            dao.upsertAll(workouts)
        }
        val uid = currentUid()
        if (uid != null && syncManager != null) {
            workouts.forEach { syncManager.pushWorkout(uid, it) }
        }
    }

    /** Merges a workout that arrived from Firestore. Skips the push-back, to avoid a loop. */
    suspend fun upsertFromSync(workout: Workout) {
        dao.upsert(workout)
    }
}
