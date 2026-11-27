package com.fitnessapp.summary.analytics

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Normalized inputs retain their upstream identity. No raw source row is mutated. */
enum class FitnessSource { GARMIN, GYMUP, ZEPP, GOOGLE_HEALTH, HEALTH_CONNECT, STRAVA, GOOGLE_FIT, LEGACY }
enum class FitnessSport { RUN, CYCLE, SWIM, STRENGTH, WALK, OTHER, UNKNOWN }
enum class DailyMetric { STEPS, DISTANCE_METERS, ACTIVE_KCAL, TOTAL_KCAL, SLEEP_MINUTES, RESTING_HR, HRV_MS, STRESS, WEIGHT_KG, BODY_FAT_PERCENT, VO2_MAX }
enum class ObservationQuality { INVALID, ESTIMATED, MEASURED }

data class FitnessSession(
    val source: FitnessSource,
    val nativeId: String,
    val sport: FitnessSport,
    val startMillis: Long,
    /** Elapsed duration, not moving time. Null means not known. */
    val elapsedSeconds: Long?,
    val distanceMeters: Double? = null,
    /** Namespaced *verified* upstream identity, not an unrelated app-local ID. */
    val upstreamId: String? = null,
    /** Google Health can transport records from multiple apps. A package name
     * is a stream identifier, not proof of upstream workout identity. */
    val transportOrigin: String? = null,
    /** Recorded timer/active time, explicitly separate from elapsed time. */
    val recordedSeconds: Long? = null
) {
    init {
        require(nativeId.isNotBlank() && startMillis >= 0)
        require(elapsedSeconds == null || elapsedSeconds in 1..604800)
        require(distanceMeters == null || (distanceMeters.isFinite() && distanceMeters >= 0))
        require(upstreamId == null || upstreamId.isNotBlank())
        require(transportOrigin == null || transportOrigin.isNotBlank())
        require(recordedSeconds == null || recordedSeconds in 1..604800)
    }
    val key: String get() = "${source.name}:$nativeId"
    val channel: String get() = if (source == FitnessSource.GOOGLE_HEALTH && transportOrigin != null)
        "${source.name}:$transportOrigin" else source.name
}

data class SessionEpisode(
    val primary: FitnessSession,
    val observations: List<FitnessSession>,
    val ambiguous: Boolean,
    val possibleDuplicateKeys: List<String> = emptyList()
)

data class DailyObservation(
    val source: FitnessSource,
    val nativeId: String,
    val day: LocalDate,
    val metric: DailyMetric,
    val value: Double?,
    val quality: ObservationQuality,
    /** True only when the source has provided a complete observation for this metric. */
    val complete: Boolean,
    /** Verified origin when this is a transported copy. */
    val origin: FitnessSource? = null
) {
    init { require(nativeId.isNotBlank()) }
    val usable: Boolean get() {
        val v = value ?: return false
        if (!v.isFinite() || v < 0 || quality == ObservationQuality.INVALID) return false
        return when (metric) {
            DailyMetric.RESTING_HR, DailyMetric.WEIGHT_KG, DailyMetric.VO2_MAX -> v > 0
            DailyMetric.BODY_FAT_PERCENT, DailyMetric.STRESS -> v <= 100
            else -> true
        }
    }
}

data class DailyChoice(
    val selected: DailyObservation?,
    val observations: List<DailyObservation>,
    /** Differing usable values, not an assertion that one instrument is faulty. */
    val disagreement: Boolean
)

data class PersonalReference(val median: Double, val validDays: Int, val from: LocalDate, val through: LocalDate)

object CanonicalFitness {
    private const val START_WINDOW_MS = 180_000L
    private enum class Match { NONE, POSSIBLE, CONFIDENT }
    private val endurance = setOf(FitnessSport.RUN, FitnessSport.CYCLE, FitnessSport.SWIM, FitnessSport.WALK)

    /** Exact-time copies can omit distance or represent timer rather than elapsed
     * duration. Require a transported source, known identical sport, two independently
     * recorded times and no contradictory positive distance. Do not invent an upstream ID. */
    private fun exactTransportMatch(a: FitnessSession,b: FitnessSession): Boolean {
        val transport=setOf(FitnessSource.HEALTH_CONNECT,FitnessSource.GOOGLE_HEALTH)
        if(a.source !in transport && b.source !in transport) return false
        if(a.sport!=b.sport || a.sport in setOf(FitnessSport.UNKNOWN,FitnessSport.OTHER)) return false
        if(abs(a.startMillis-b.startMillis)>2000L) return false
        val x=a.distanceMeters?.takeIf { it>0 }
        val y=b.distanceMeters?.takeIf { it>0 }
        if(x!=null && y!=null && abs(x-y)>max(10.0,max(x,y)*0.05)) return false
        // Prefer exact timer evidence. Health Connect stores integer minutes;
        // its precise start/end interval is also available when recorded.
        val at=a.recordedSeconds ?: return false
        val bt=b.recordedSeconds ?: return false
        val hc=a.source==FitnessSource.HEALTH_CONNECT || b.source==FitnessSource.HEALTH_CONNECT
        val tolerance=if(hc) 59L else 2L
        if(abs(at-bt)>tolerance) return false
        // For Health Connect, distinguish rounded minutes from a genuinely
        // different interval. Garmin and a Garmin-origin Google Health record
        // may legitimately report different elapsed time.
        if(hc && a.source!=FitnessSource.GARMIN && b.source!=FitnessSource.GARMIN) {
            val ae=a.elapsedSeconds
            val be=b.elapsedSeconds
            if(ae!=null && be!=null && abs(ae-be)>60L && abs(at-bt)>2L) return false
        }
        return true
    }

    private fun match(a: FitnessSession, b: FitnessSession): Match {
        val sameUpstream = a.upstreamId != null && a.upstreamId == b.upstreamId
        val distinctOrigins = a.source == FitnessSource.GOOGLE_HEALTH && b.source == a.source &&
            a.transportOrigin != null && b.transportOrigin != null && a.transportOrigin != b.transportOrigin
        if (a.source == b.source && !distinctOrigins) {
            // Distinct IDs from a single stream are not silently merged. Flag
            // extremely close full-interval collisions for review instead.
            if (sameUpstream) return Match.POSSIBLE
            if (abs(a.startMillis-b.startMillis)>1000L || a.sport!=b.sport ||
                a.sport in setOf(FitnessSport.OTHER,FitnessSport.UNKNOWN)) return Match.NONE
            val ad=a.elapsedSeconds ?: return Match.NONE
            val bd=b.elapsedSeconds ?: return Match.NONE
            if (abs(ad-bd)>2L) return Match.NONE
            if (a.sport in endurance) {
                val x=a.distanceMeters ?: return Match.NONE
                val y=b.distanceMeters ?: return Match.NONE
                if (x<=0 || y<=0 || abs(x-y)>max(5.0,max(x,y)*0.01)) return Match.NONE
            }
            return Match.POSSIBLE
        }
        val near = abs(a.startMillis - b.startMillis) <= START_WINDOW_MS
        if (!near) return if (sameUpstream) Match.POSSIBLE else Match.NONE
        val knownSport = a.sport !in setOf(FitnessSport.OTHER, FitnessSport.UNKNOWN) &&
            b.sport !in setOf(FitnessSport.OTHER, FitnessSport.UNKNOWN)
        if (knownSport && a.sport != b.sport) return if (sameUpstream) Match.POSSIBLE else Match.NONE
        if (!knownSport && !sameUpstream) return Match.POSSIBLE
        if (exactTransportMatch(a,b)) return Match.CONFIDENT
        val ad = a.elapsedSeconds
        val bd = b.elapsedSeconds
        if (ad != null && bd != null) {
            val overlap = min(a.startMillis + ad * 1000, b.startMillis + bd * 1000) -
                max(a.startMillis, b.startMillis)
            if (overlap <= 0) return if (sameUpstream) Match.POSSIBLE else Match.NONE
            val durationOk = overlap.toDouble() / (max(ad, bd) * 1000.0) >= 0.80 &&
                abs(ad - bd) <= max(120.0, max(ad, bd) * 0.12)
            if (!durationOk) return Match.POSSIBLE
        } else if (!sameUpstream) return Match.POSSIBLE
        if (a.sport in endurance || b.sport in endurance) {
            val x = a.distanceMeters
            val y = b.distanceMeters
            // A verified origin can establish identity when an optional metric is
            // missing. Contradictory observed metrics still need explicit review.
            if (x != null && y != null && abs(x-y) > max(100.0,max(x,y)*0.05)) return Match.POSSIBLE
            if (!sameUpstream && (x == null || y == null || x <= 0 || y <= 0)) return Match.POSSIBLE
        }
        return Match.CONFIDENT
    }

    private fun sessionRank(s: FitnessSession): Int = when (s.source) {
        FitnessSource.GARMIN -> 0
        FitnessSource.GYMUP -> 1
        FitnessSource.STRAVA -> 2
        FitnessSource.HEALTH_CONNECT -> 3
        FitnessSource.GOOGLE_HEALTH -> 4
        FitnessSource.GOOGLE_FIT -> 5
        else -> 6
    }

    /**
     * Form complete-link confident components first; weak edges never pull
     * unrelated observations into a merged group. A confirmed clique can retain
     * possible links to other groups without being split into duplicate cards.
     * Non-clique or repeated-channel strong components remain separate.
     */
    fun episodes(input: List<FitnessSession>): List<SessionEpisode> {
        require(input.map { it.key }.toSet().size == input.size) { "duplicate source identity" }
        val rows=input.sortedWith(compareBy<FitnessSession> { it.startMillis }.thenBy { it.key })
        val strong=Array(rows.size) { mutableSetOf<Int>() }
        val related=Array(rows.size) { mutableSetOf<Int>() }
        fun connect(i:Int,j:Int) {
            val m=match(rows[i],rows[j])
            if(m==Match.NONE)return
            related[i].add(j);related[j].add(i)
            if(m==Match.CONFIDENT){strong[i].add(j);strong[j].add(i)}
        }
        for(i in rows.indices) {
            var j=i+1
            while(j<rows.size && rows[j].startMillis-rows[i].startMillis<=START_WINDOW_MS) connect(i,j++)
        }
        rows.indices.filter { rows[it].upstreamId!=null }.groupBy { rows[it].upstreamId }.values.forEach { ids ->
            for(i in ids.indices) for(j in i+1 until ids.size)connect(ids[i],ids[j])
        }
        val visited=BooleanArray(rows.size)
        val groups=mutableListOf<List<Int>>()
        for(i in rows.indices){
            if(visited[i])continue
            val pending=ArrayDeque<Int>(); val group=mutableListOf<Int>()
            pending.add(i);visited[i]=true
            while(pending.isNotEmpty()){
                val n=pending.removeFirst();group.add(n)
                strong[n].forEach { k->if(!visited[k]){visited[k]=true;pending.add(k)} }
            }
            val safe=group.map { rows[it].channel }.distinct().size==group.size &&
                group.all { a-> group.all { b-> a==b || b in strong[a] } }
            if(safe)groups.add(group) else group.forEach { groups.add(listOf(it)) }
        }
        // Ambiguous links are between episodes, not between the copies already
        // proven to be members of one episode. Preserve references for review.
        val result=groups.map { group->
            val members=group.toSet()
            val observations=group.map { rows[it] }.sortedBy { it.key }
            val external=group.flatMap { related[it] }.filter { it !in members }.distinct()
                .map { rows[it].key }.sorted()
            val primary=observations.minWith(compareBy<FitnessSession> { sessionRank(it) }.thenBy { it.key })
            SessionEpisode(primary,observations,external.isNotEmpty(),external)
        }
        return result.sortedWith(compareBy<SessionEpisode> { it.primary.startMillis }.thenBy { it.primary.key })
    }

    private fun metricRank(o: DailyObservation): Int {
        val origin = o.origin ?: o.source
        val priorities = when (o.metric) {
            DailyMetric.WEIGHT_KG, DailyMetric.BODY_FAT_PERCENT ->
                listOf(FitnessSource.ZEPP, FitnessSource.GARMIN, FitnessSource.GOOGLE_HEALTH, FitnessSource.HEALTH_CONNECT, FitnessSource.GOOGLE_FIT)
            else ->
                listOf(FitnessSource.GARMIN, FitnessSource.GOOGLE_HEALTH, FitnessSource.HEALTH_CONNECT, FitnessSource.GOOGLE_FIT)
        }
        return priorities.indexOf(origin).let { if (it < 0) priorities.size else it }
    }

    /** Select one value, never add overlapping daily totals. Keep every alternative. */
    fun chooseDaily(input: List<DailyObservation>): DailyChoice {
        require(input.map { it.day to it.metric }.distinct().size <= 1)
        val usable = input.filter { it.usable }.sortedWith(
            compareByDescending<DailyObservation> { it.quality == ObservationQuality.MEASURED }
                .thenByDescending { it.complete }
                .thenBy { metricRank(it) }
                .thenByDescending { it.origin == null || it.origin == it.source }
                .thenBy { it.source.name }
                .thenBy { it.nativeId }
        )
        return DailyChoice(usable.firstOrNull(), input.toList(), usable.map { it.value }.distinct().size > 1)
    }

    /** Daily metrics only: 28 previous days, 14 valid measured complete days minimum. */
    fun reference(today: LocalDate, metric: DailyMetric, input: List<DailyObservation>): PersonalReference? {
        require(metric !in setOf(DailyMetric.WEIGHT_KG, DailyMetric.BODY_FAT_PERCENT, DailyMetric.VO2_MAX)) {
            "Sparse measurements need a separate reference method"
        }
        val from = today.minusDays(28)
        val through = today.minusDays(1)
        val values = input.filter {
            it.metric == metric && it.day in from..through && it.complete &&
                it.quality == ObservationQuality.MEASURED && it.usable
        }.groupBy { it.day }.values.mapNotNull { chooseDaily(it).selected?.value }.sorted()
        if (values.size < 14) return null
        val mid = values.size / 2
        val median = if (values.size % 2 == 1) values[mid] else values[mid - 1] / 2 + values[mid] / 2
        return PersonalReference(median, values.size, from, through)
    }

    /** Number of fully elapsed calendar days; today is not a completed day. */
    fun completedWeekDays(weekStart: LocalDate, today: LocalDate): Int =
        (today.toEpochDay() - weekStart.toEpochDay()).coerceIn(0, 7).toInt()
}
