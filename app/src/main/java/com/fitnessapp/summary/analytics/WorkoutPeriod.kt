package com.fitnessapp.summary.analytics

import kotlin.math.roundToInt

/** A presentation-only group of mutually linked review candidates. It is not
 * a merged workout: every underlying canonical episode remains accessible. */
data class WorkoutDisplayGroup(val members: List<ArchiveEpisode>, val requiresReview: Boolean) {
    init { require(members.isNotEmpty()) }
    val latest = members.maxWith(compareBy<ArchiveEpisode> { it.primary.session.startMillis }.thenBy { it.episode.primary.key })
    val day get() = latest.primary.day
    val key get() = (if(requiresReview) "review:" else "workout:") + members.minOf { it.episode.primary.key }
    val observationCount get() = members.sumOf { it.observations.size }
}

/** All screens derive totals from the same reconciled archive and selected dates.
 * Ambiguous observations remain visible as linked review groups but never enter confirmed sums. */
data class WorkoutPeriod(
    val episodes: List<ArchiveEpisode>
) {
    val confirmed = episodes.filterNot { it.episode.ambiguous }
    val ambiguousCount = episodes.size-confirmed.size
    // Group only unresolved candidates present in the selected view. Possible
    // links never merge source records or alter confirmed totals. A link to a
    // different period/filter remains visible in the episode details.
    val displayGroups:List<WorkoutDisplayGroup> by lazy {
        val pending=episodes.filter { it.episode.ambiguous }
        val index=mutableMapOf<String,Int>()
        pending.forEachIndexed { i,e -> e.observations.forEach { index[it.session.key]=i } }
        val adj=Array(pending.size) { mutableSetOf<Int>() }
        pending.forEachIndexed { i,e ->
            e.episode.possibleDuplicateKeys.mapNotNull { index[it] }.filter { it!=i }.forEach { j ->
                adj[i].add(j);adj[j].add(i)
            }
        }
        val visited=BooleanArray(pending.size)
        val groups=mutableListOf<WorkoutDisplayGroup>()
        for(i in pending.indices) {
            if(visited[i])continue
            val todo=ArrayDeque<Int>();val members=mutableListOf<ArchiveEpisode>()
            todo.add(i);visited[i]=true
            while(todo.isNotEmpty()) {
                val j=todo.removeFirst();members.add(pending[j])
                adj[j].forEach { k -> if(!visited[k]){visited[k]=true;todo.add(k)} }
            }
            groups.add(WorkoutDisplayGroup(members.sortedWith(compareByDescending<ArchiveEpisode> {
                it.primary.session.startMillis
            }.thenBy { it.episode.primary.key }),true))
        }
        groups.addAll(confirmed.map { WorkoutDisplayGroup(listOf(it),false) })
        val accounted=groups.flatMap { it.members }.map { it.episode.primary.key }
        require(accounted.size==episodes.size && accounted.toSet()==episodes.map { it.episode.primary.key }.toSet())
        groups.sortedWith(compareByDescending<WorkoutDisplayGroup> { it.latest.primary.session.startMillis }.thenBy { it.key })
    }
    val reviewGroupCount get() = displayGroups.count { it.requiresReview }
    val reviewObservationCount get() = displayGroups.filter { it.requiresReview }.sumOf { it.observationCount }
    val accountedObservationCount get() = episodes.sumOf { it.observations.size }
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
