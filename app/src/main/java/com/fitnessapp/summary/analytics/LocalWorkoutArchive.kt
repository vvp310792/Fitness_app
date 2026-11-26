package com.fitnessapp.summary.analytics

import androidx.health.connect.client.records.ExerciseSessionRecord
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
    val durationSeconds: Long? get() = observations.sortedBy { sourceOrder(it.session.source) }
        .firstNotNullOfOrNull { it.trainingSeconds }
    val distanceMeters: Double? get() = observations.sortedBy { sourceOrder(it.session.source) }
        .firstNotNullOfOrNull { it.session.distanceMeters }
    val averageHr: Int? get() = observations.sortedBy { sourceOrder(it.session.source) }
        .firstNotNullOfOrNull { it.averageHr }
    val maxHr: Int? get() = observations.sortedBy { sourceOrder(it.session.source) }
        .firstNotNullOfOrNull { it.maxHr }
    val sets: List<StrengthSet> get() = observations.firstOrNull { it.session.source == FitnessSource.GYMUP }?.sets.orEmpty()
    val garmin: GarminActivity? get() = observations.firstNotNullOfOrNull { it.garmin }
}

data class ArchiveResult(val episodes: List<ArchiveEpisode>, val rejectedObservations: Int)

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
    fun build(health: List<Workout>,garmin: List<GarminActivity>,strava: List<StravaActivity>,strength: List<StrengthSet>): ArchiveResult {
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
        val byKey=unique.mapValues { it.value.first() }
        val episodes=CanonicalFitness.episodes(byKey.values.map { it.session }).map { e ->
            ArchiveEpisode(e,byKey.getValue(e.primary.key),e.observations.map { byKey.getValue(it.key) })
        }
        return ArchiveResult(episodes.sortedByDescending { it.primary.session.startMillis },rejected)
    }
}
