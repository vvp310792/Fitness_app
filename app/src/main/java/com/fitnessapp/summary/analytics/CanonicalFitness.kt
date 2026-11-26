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
    val upstreamId: String? = null
) {
    init {
        require(nativeId.isNotBlank() && startMillis >= 0)
        require(elapsedSeconds == null || elapsedSeconds in 1..604800)
        require(distanceMeters == null || (distanceMeters.isFinite() && distanceMeters >= 0))
        require(upstreamId == null || upstreamId.isNotBlank())
    }
    val key: String get() = "${source.name}:$nativeId"
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

    private fun match(a: FitnessSession, b: FitnessSession): Match {
        if (a.source == b.source) return Match.NONE
        val sameUpstream = a.upstreamId != null && a.upstreamId == b.upstreamId
        val near = abs(a.startMillis - b.startMillis) <= START_WINDOW_MS
        if (!near) return if (sameUpstream) Match.POSSIBLE else Match.NONE
        val knownSport = a.sport !in setOf(FitnessSport.OTHER, FitnessSport.UNKNOWN) &&
            b.sport !in setOf(FitnessSport.OTHER, FitnessSport.UNKNOWN)
        if (knownSport && a.sport != b.sport) return if (sameUpstream) Match.POSSIBLE else Match.NONE
        if (!knownSport && !sameUpstream) return Match.POSSIBLE
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
     * Conservative connected components: every pair must agree confidently and
     * each source can contribute at most one distinct observation. A chain A~B~C
     * is not evidence that A and C are duplicates. Keep uncertain observations
     * separate and mark them; do not silently discard any activity.
     */
    fun episodes(input: List<FitnessSession>): List<SessionEpisode> {
        require(input.map { it.key }.toSet().size == input.size) { "duplicate source identity" }
        val rows = input.sortedWith(compareBy<FitnessSession> { it.startMillis }.thenBy { it.key })
        val edges = Array(rows.size) { mutableSetOf<Int>() }
        fun connect(i: Int, j: Int) {
            if (match(rows[i], rows[j]) != Match.NONE) {
                edges[i].add(j)
                edges[j].add(i)
            }
        }
        for (i in rows.indices) {
            var j = i + 1
            while (j < rows.size && rows[j].startMillis - rows[i].startMillis <= START_WINDOW_MS) {
                connect(i, j++)
            }
        }
        // A shared upstream identity with a conflicting timestamp is also flagged.
        rows.indices.filter { rows[it].upstreamId != null }
            .groupBy { rows[it].upstreamId }.values.forEach { ids ->
                for (i in ids.indices) for (j in i + 1 until ids.size) connect(ids[i], ids[j])
            }
        val visited = BooleanArray(rows.size)
        val result = mutableListOf<SessionEpisode>()
        for (i in rows.indices) {
            if (visited[i]) continue
            val pending = ArrayDeque<Int>()
            val group = mutableListOf<Int>()
            pending.add(i); visited[i] = true
            while (pending.isNotEmpty()) {
                val n = pending.removeFirst()
                group.add(n)
                edges[n].forEach { k -> if (!visited[k]) { visited[k] = true; pending.add(k) } }
            }
            val observations = group.map { rows[it] }.sortedBy { it.key }
            val safe = observations.map { it.source }.toSet().size == observations.size &&
                observations.indices.all { a ->
                    (a + 1 until observations.size).all { b -> match(observations[a], observations[b]) == Match.CONFIDENT }
                }
            if (safe) {
                val primary = observations.minWith(compareBy<FitnessSession> { sessionRank(it) }.thenBy { it.key })
                result.add(SessionEpisode(primary, observations, false))
            } else {
                observations.forEach { row ->
                    result.add(SessionEpisode(row, listOf(row), true,
                        observations.filter { it.key != row.key }.map { it.key }))
                }
            }
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
