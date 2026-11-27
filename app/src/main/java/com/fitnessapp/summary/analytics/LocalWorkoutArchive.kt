package com.fitnessapp.summary.analytics

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.fitnessapp.summary.sync.ServerWorkoutProjection
import com.fitnessapp.summary.data.GarminActivity
import com.fitnessapp.summary.data.StravaActivity
import com.fitnessapp.summary.data.StrengthSet
import com.fitnessapp.summary.data.Workout
import com.fitnessapp.summary.util.exerciseName
import com.fitnessapp.summary.util.garminSportName

/** Read-only adapter for the existing Room tables, while server v1 is being validated.
 * Raw observations stay in their original tables. Legacy moving/timer durations are
 * deliberately NOT treated as elapsed time for matching. */
data class ArchiveObservation(
    val session: FitnessSession,
    val day: Long,
    val title: String,
    val trainingSeconds: Long?,
    val averageHr: Int?,
    val maxHr: Int?,
    val sets: List<StrengthSet> = emptyList(),
    val garmin: GarminActivity? = null
)

data class ArchiveEpisode(
    val episode: SessionEpisode,
    val primary: ArchiveObservation,
    val observations: List<ArchiveObservation>
) {
    // Partial Google Fit records can add provenance, but their metrics describe
    // only a sub-interval and must never substitute for whole-session metrics.
    private val wholeObservations get() = observations.filterNot { it.session.key in episode.fragmentKeys }
    val durationSeconds: Long? get() = wholeObservations.sortedBy { sourceOrder(it.session.source) }
        .firstNotNullOfOrNull { it.trainingSeconds }
    val distanceMeters: Double? get() = wholeObservations.sortedBy { sourceOrder(it.session.source) }
        .firstNotNullOfOrNull { it.session.distanceMeters }
    val averageHr: Int? get() = wholeObservations.sortedBy { sourceOrder(it.session.source) }
        .firstNotNullOfOrNull { it.averageHr }
    val maxHr: Int? get() = wholeObservations.sortedBy { sourceOrder(it.session.source) }
        .firstNotNullOfOrNull { it.maxHr }
    val sets: List<StrengthSet> get() = observations.filter { it.session.source == FitnessSource.GYMUP }
        .flatMap { it.sets }.distinctBy { Triple(it.startMillis,it.exerciseName,it.setIndex) }
    val garmin: GarminActivity? get() = observations.firstNotNullOfOrNull { it.garmin }
}

data class ArchiveResult(
    val episodes: List<ArchiveEpisode>,
    val rejectedObservations: Int,
    val serverRawCount: Int = 0,
    val serverMappedCount: Int = 0,
    val serverRejected: Int = 0,
    val serverEmptyGymSessions: Int = 0,
    val serverIdentityConflicts: Int = 0,
    val exactIdentityMatches: Int = 0,
    val confirmedDuplicateObservations: Int = 0,
    /** True only after canonical episode/membership readback from Room. */
    val canonicalStored: Boolean = false
)

private fun sourceOrder(s: FitnessSource): Int = when (s) {
    FitnessSource.GARMIN -> 0
    FitnessSource.GYMUP -> 1
    FitnessSource.STRAVA -> 2
    FitnessSource.HEALTH_CONNECT -> 3
    else -> 4
}

object LocalWorkoutArchive {
    private fun DistanceSport?.asFitnessSport(): FitnessSport? = when (this) {
        DistanceSport.RUN -> FitnessSport.RUN
        DistanceSport.BIKE -> FitnessSport.CYCLE
        DistanceSport.SWIM -> FitnessSport.SWIM
        null -> null
    }
    fun garminSport(key: String): FitnessSport =
        DistanceSport.ofGarmin(key).asFitnessSport() ?: when {
            key.equals("strength_training",true) || key.equals("weightlifting",true) -> FitnessSport.STRENGTH
            key.contains("walking",true) -> FitnessSport.WALK
            key.isBlank() -> FitnessSport.UNKNOWN
            else -> FitnessSport.OTHER
        }
    fun stravaSport(key: String): FitnessSport =
        DistanceSport.ofStrava(key).asFitnessSport() ?: when {
            key.contains("силов",true) || key.equals("WeightTraining",true) || key.equals("Weight Training",true) -> FitnessSport.STRENGTH
            key.equals("Walk",true) || key.equals("Ходьба",true) -> FitnessSport.WALK
            key.isBlank() -> FitnessSport.UNKNOWN
            else -> FitnessSport.OTHER
        }
    fun healthSport(type: Int): FitnessSport =
        DistanceSport.ofHealthConnect(type).asFitnessSport() ?: when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> FitnessSport.STRENGTH
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> FitnessSport.WALK
            ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT -> FitnessSport.UNKNOWN
            else -> FitnessSport.OTHER
        }
    private fun Int.positive() = takeIf { it > 0 }
    private fun Long.duration() = takeIf { it in 1..604800 }
    private fun distance(value: Int) = value.positive()?.toDouble()
    private fun session(src: FitnessSource,id: String,sport: FitnessSport,start: Long,elapsed: Long?,distance: Int = 0): FitnessSession? =
        if (id.isBlank() || start <= 0) null else FitnessSession(src,id,sport,start,elapsed?.duration(),distance(distance))

    /** Use one common projection for archive consumers. Ambiguous groups remain
     * visible and are excluded from confirmed-only sums, never silently erased. */
    fun build(health: List<Workout>,garmin: List<GarminActivity>,strava: List<StravaActivity>,strength: List<StrengthSet>,
        server: ServerWorkoutProjection = ServerWorkoutProjection()): ArchiveResult {
        val rows = mutableListOf<ArchiveObservation>()
        var rejected = 0
        for (r in health) {
            val s=session(FitnessSource.HEALTH_CONNECT,r.recordId,healthSport(r.exerciseType),r.startTimeMillis,
                if(r.endTimeMillis>r.startTimeMillis) (r.endTimeMillis-r.startTimeMillis)/1000 else null,r.distanceMeters)
            if(s==null) { rejected++;continue }
            rows.add(ArchiveObservation(s,r.dateEpochDay,r.title.ifBlank { exerciseName(r.exerciseType) },
                (r.durationMinutes.toLong()*60).duration(),r.avgHeartRate.positive(),r.maxHeartRate.positive()))
        }
        for(r in garmin) {
            val s=session(FitnessSource.GARMIN,r.activityId.toString(),garminSport(r.typeKey),r.startTimeMillis,null,r.distanceMeters)
            if(s==null) { rejected++;continue }
            rows.add(ArchiveObservation(s,r.dateEpochDay,r.name.ifBlank { garminSportName(r.typeKey) },
                r.durationSeconds.toLong().duration(),r.avgHeartRate.positive(),r.maxHeartRate.positive(),garmin=r))
        }
        for(r in strava) {
            val s=session(FitnessSource.STRAVA,r.activityId.toString(),stravaSport(r.typeRaw),r.startTimeMillis,null,r.distanceMeters)
            if(s==null) { rejected++;continue }
            rows.add(ArchiveObservation(s,r.dateEpochDay,r.name.ifBlank { r.typeRaw.ifBlank { "Тренировка" } },
                r.durationSeconds.toLong().duration(),r.avgHeartRate.positive(),r.maxHeartRate.positive()))
        }
        for((start,sets) in strength.groupBy { it.startMillis }) {
            val first=sets.first()
            val s=session(FitnessSource.GYMUP,start.toString(),FitnessSport.STRENGTH,start,null)
            if(s==null) { rejected++;continue }
            rows.add(ArchiveObservation(s,first.dateEpochDay,"Силовая тренировка",null,null,null,sets=sets))
        }
        // Room primary keys prevent duplicate keys within a source; if an adapter
        // sees invalid repeated identities, retain a warning instead of crashing.
        val unique=rows.groupBy { it.session.key }
        rejected += unique.values.sumOf { it.size-1 }
        val byKey=unique.mapValues { it.value.first() }.toMutableMap()
        var identityConflicts=0
        var identityMatches=0
        val conflictedKeys=mutableSetOf<String>()
        for (remote in server.observations) {
            val key=remote.session.key
            val local=byKey[key]
            if (local==null) {
                byKey[key]=remote
                continue
            }
            // Same source + stable native identity, not just a matching date.
            // Do not hide a contradictory timestamp or known sport behind an overwrite.
            val knownLocal=local.session.sport !in setOf(FitnessSport.UNKNOWN,FitnessSport.OTHER)
            val knownRemote=remote.session.sport !in setOf(FitnessSport.UNKNOWN,FitnessSport.OTHER)
            if (kotlin.math.abs(local.session.startMillis-remote.session.startMillis)>180000 ||
                (knownLocal && knownRemote && local.session.sport!=remote.session.sport)) {
                identityConflicts++
                conflictedKeys.add(key)
                continue
            }
            identityMatches++
            // Keep detailed local data, enrich matching IDs with verified elapsed time.
            byKey[key]=local.copy(
                session=local.session.copy(
                    elapsedSeconds=remote.session.elapsedSeconds ?: local.session.elapsedSeconds,
                    distanceMeters=local.session.distanceMeters ?: remote.session.distanceMeters
                ),
                trainingSeconds=local.trainingSeconds ?: remote.trainingSeconds,
                averageHr=local.averageHr ?: remote.averageHr,
                maxHr=local.maxHr ?: remote.maxHr,
                sets=local.sets.ifEmpty { remote.sets },
                garmin=local.garmin ?: remote.garmin
            )
        }
        // Retain both timer and elapsed values; neither substitutes for the other.
        val enriched=byKey.mapValues { (_,o) ->
            o.copy(session=o.session.copy(recordedSeconds=o.trainingSeconds))
        }
        val episodes=CanonicalFitness.episodes(enriched.values.map { it.session }).flatMap { e ->
            // A contradictory identity must not be treated as a confirmed session,
            // including when a different source otherwise matches it.
            val resolved = if (e.observations.any { it.key in conflictedKeys }) e.observations.map { r ->
                SessionEpisode(r,listOf(r),true,(e.possibleDuplicateKeys+e.observations.filter { it.key!=r.key }.map { it.key }).distinct())
            } else listOf(e)
            resolved.map { v -> ArchiveEpisode(v,enriched.getValue(v.primary.key),v.observations.map { enriched.getValue(it.key) }) }
        }
        val merged=episodes.sumOf { it.observations.size-1 }
        return ArchiveResult(episodes.sortedByDescending { it.primary.session.startMillis },rejected,
            server.rawCount,server.observations.size,server.rejected,server.emptyGymSessions,identityConflicts,identityMatches,merged)
    }
}
