package com.fitnessapp.summary.analytics

import kotlin.math.roundToInt

/** All screens derive totals from the same reconciled archive and selected dates.
 * Ambiguous observations remain visible but never enter confirmed sums. */
data class WorkoutPeriod(
    val episodes: List<ArchiveEpisode>
) {
    val confirmed = episodes.filterNot { it.episode.ambiguous }
    val ambiguousCount = episodes.size-confirmed.size
    val knownDurationCount = confirmed.count { it.durationSeconds!=null }
    val knownDistanceCount = confirmed.count { it.distanceMeters!=null }
    val durationSeconds = confirmed.sumOf { it.durationSeconds ?: 0L }
    val distanceMeters = confirmed.sumOf { it.distanceMeters ?: 0.0 }
    val garminActivities = confirmed.mapNotNull { it.garmin }
    fun sportSessions():List<SportSession> = confirmed.mapNotNull { row ->
        val sport=when(row.primary.session.sport) {
            FitnessSport.RUN -> DistanceSport.RUN
            FitnessSport.CYCLE -> DistanceSport.BIKE
            FitnessSport.SWIM -> DistanceSport.SWIM
            else -> null
        } ?: return@mapNotNull null
        val meters=row.distanceMeters?.takeIf { it>0 && it<=Int.MAX_VALUE }?.roundToInt() ?: return@mapNotNull null
        SportSession(sport,row.primary.day,row.primary.session.startMillis,meters)
    }
    fun intensitySessions():List<IntensitySession> = confirmed.mapNotNull { row ->
        val seconds=row.durationSeconds ?: return@mapNotNull null
        val minutes=(seconds/60).toInt()
        if(minutes<=0) return@mapNotNull null
        val sport=row.garmin?.typeKey ?: when(row.primary.session.sport){
            FitnessSport.RUN->"running"
            FitnessSport.CYCLE->"cycling"
            FitnessSport.SWIM->"swimming"
            FitnessSport.STRENGTH->"strength_training"
            FitnessSport.WALK->"walking"
            else->"other"
        }
        fun hr(v:Int?)=v?.takeIf { it in 20..250 } ?: 0
        IntensitySession(row.primary.day,row.primary.session.startMillis,sport,minutes,hr(row.averageHr),hr(row.maxHr),row.garmin?.zoneSeconds)
    }
    companion object {
        fun between(archive:ArchiveResult?,from:Long,through:Long):WorkoutPeriod =
            WorkoutPeriod(archive?.episodes.orEmpty().filter { it.primary.day in from..through })
    }
}
