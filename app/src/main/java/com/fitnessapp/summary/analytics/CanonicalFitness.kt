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
    val possibleDuplicateKeys: List<String> = emptyList(),
    /** Transported partial intervals, not independent workouts or full-session metrics. */
    val fragmentKeys: List<String> = emptyList()
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

    private val transportSources=setOf(FitnessSource.GOOGLE_HEALTH,FitnessSource.HEALTH_CONNECT,FitnessSource.GOOGLE_FIT)
    private val deviceSources=setOf(FitnessSource.GARMIN,FitnessSource.STRAVA)
    private val anchorSources=deviceSources+FitnessSource.GYMUP

    /** Google Fit and a performed GymUp log can share an exact start timestamp.
     * This is strong corroboration, not proof of an upstream ID or of wall-clock
     * training time. Require a unique pair with overlapping measured intervals. */
    private fun journalFitPair(journal:FitnessSession,fit:FitnessSession):Boolean {
        if(journal.source!=FitnessSource.GYMUP || journal.sport!=FitnessSport.STRENGTH ||
            fit.sport!=FitnessSport.STRENGTH ||
            !(fit.source==FitnessSource.GOOGLE_FIT ||
                (fit.source==FitnessSource.GOOGLE_HEALTH && fit.transportOrigin=="com.google.android.apps.fitness")) ||
            abs(journal.startMillis-fit.startMillis)>1000L)return false
        val j=journal.elapsedSeconds ?: return false
        val f=fit.elapsedSeconds ?: return false
        val timer=fit.recordedSeconds ?: return false
        if(min(j,f)<180L || abs(f-timer)>max(120.0,f*0.15))return false
        val overlap=min(journal.startMillis+j*1000L,fit.startMillis+f*1000L)-
            max(journal.startMillis,fit.startMillis)
        return overlap>=180_000L && overlap.toDouble()/(min(j,f)*1000.0)>=0.90
    }

    private fun fitSupportsDevice(fit:FitnessSession,watch:FitnessSession):Boolean {
        if(watch.source !in deviceSources || watch.sport!=FitnessSport.STRENGTH ||
            abs(fit.startMillis-watch.startMillis)>900_000L)return false
        val f=fit.elapsedSeconds ?: return false
        val w=watch.elapsedSeconds ?: return false
        val timer=watch.recordedSeconds ?: return false
        if(abs(w-timer)>max(120.0,w*0.15))return false
        val overlap=min(fit.startMillis+f*1000L,watch.startMillis+w*1000L)-
            max(fit.startMillis,watch.startMillis)
        return overlap>=180_000L && overlap.toDouble()/(min(f,w)*1000.0)>=0.80
    }

    /** A measured shorter interval or a complementary strength journal can
     * belong to a complete device session. Do not confuse journal wall time
     * with watch timer time. Candidate does not mean confirmed. */
    private fun nestedMatch(child:FitnessSession,anchor:FitnessSession):Match {
        val journal=child.source==FitnessSource.GYMUP && anchor.source in deviceSources
        if(!journal && (child.source !in transportSources || anchor.source !in anchorSources))return Match.NONE
        if(child.sport!=anchor.sport || child.sport in setOf(FitnessSport.OTHER,FitnessSport.UNKNOWN))return Match.NONE
        if(journal && child.sport!=FitnessSport.STRENGTH)return Match.NONE
        val f=child.elapsedSeconds
        val a=anchor.elapsedSeconds
        if(f==null || a==null) {
            // Missing duration does not establish containment.
            return if(abs(child.startMillis-anchor.startMillis)<=START_WINDOW_MS) Match.POSSIBLE else Match.NONE
        }
        val overlap=min(child.startMillis+f*1000L,anchor.startMillis+a*1000L)-
            max(child.startMillis,anchor.startMillis)
        val short=min(f,a)
        val long=max(f,a)
        if(overlap<180_000L || overlap.toDouble()/(short*1000.0)<0.50)return Match.NONE
        val coveredShort=overlap.toDouble()/(short*1000.0)
        val coveredLong=overlap.toDouble()/(long*1000.0)
        if(coveredShort<0.90 && coveredLong<0.80)return Match.POSSIBLE
        if(journal) {
            // Direct watch + strength journal are complementary. Even when
            // the journal begins before or ends after the watch, its set log
            // is retained, but its wall-clock duration must not replace timer.
            return Match.CONFIDENT
        }
        val timer=anchor.recordedSeconds ?: return Match.POSSIBLE
        // Long pauses are not evidence of recorded exercise throughout the interval.
        if(abs(a-timer)>max(120.0,a*0.15))return Match.POSSIBLE
        val x=child.distanceMeters?.takeIf { it>0 }
        val y=anchor.distanceMeters?.takeIf { it>0 }
        if(f<a-60L) {
            if(coveredShort<0.90 || child.startMillis<anchor.startMillis-60_000L ||
                child.startMillis+f*1000L>anchor.startMillis+a*1000L+60_000L ||
                timer<f-60L)return Match.POSSIBLE
            if(x!=null && y!=null && x>y+max(10.0,y*0.05))return Match.POSSIBLE
            return Match.CONFIDENT
        }
        // Comparable full endurance intervals need independent distance evidence.
        // Exact recorded timer matches can already be established by match();
        // approximate endpoints alone cannot prove a duplicate with missing distance.
        if(child.sport in endurance && (x==null || y==null))return Match.POSSIBLE
        if(coveredLong<0.85 || abs(f-a)>max(120.0,a*0.15))return Match.POSSIBLE
        if(x!=null && y!=null && abs(x-y)>max(10.0,max(x,y)*0.05))return Match.POSSIBLE
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
            val journalDevice=(rows[i].source==FitnessSource.GYMUP && rows[j].source!=FitnessSource.GYMUP) ||
                (rows[j].source==FitnessSource.GYMUP && rows[i].source!=FitnessSource.GYMUP)
            if(m==Match.CONFIDENT && !journalDevice){strong[i].add(j);strong[j].add(i)}
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
        // Attach complementary log/transport groups only to a unique direct
        // anchor. Work with the original complete-link groups; never create a
        // chain through a transported fragment. Ambiguous links remain reviewable.
        val attached=mutableMapOf<Int,Int>()
        val fragmentIndices=mutableSetOf<Int>()
        val byGroup=groups.withIndex().flatMap { (g,indices) -> indices.map { it to g } }.toMap()
        val anchors=rows.indices.filter { rows[it].source in anchorSources }
        val exactJournalFit=mutableMapOf<Int,Int>()
        val pairs=rows.indices.filter { rows[it].source==FitnessSource.GYMUP }.flatMap { j ->
            rows.indices.filter { f -> journalFitPair(rows[j],rows[f]) }.map { f -> j to f }
        }
        val countsJ=pairs.groupingBy { it.first }.eachCount()
        val countsF=pairs.groupingBy { it.second }.eachCount()
        pairs.filter { countsJ[it.first]==1 && countsF[it.second]==1 }.forEach { (j,f) ->
            exactJournalFit[j]=f; exactJournalFit[f]=j
            related[j].add(f);related[f].add(j)
        }
        fun root(g:Int):Int = attached[g] ?: g
        fun candidates(group:Int,children:List<Int>,journal:Boolean):Map<Int,List<Pair<Int,Match>>> {
            val out=mutableMapOf<Int,MutableList<Pair<Int,Match>>>()
            for(i in children) for(j in anchors) {
                val target=root(byGroup.getValue(j))
                if(target==group || (journal && rows[j].source !in deviceSources))continue
                val n=nestedMatch(rows[i],rows[j])
                // A full transported copy of a standalone journal can still
                // supply its measured heart rate. Never use a nested journal
                // as independent evidence to extend a device interval.
                val full=!journal && byGroup.getValue(j) !in attached &&
                    match(rows[i],rows[j])==Match.CONFIDENT
                // An exact journal/Fit pair can corroborate a longer Fit
                // interval, but only when the same measured watch session
                // independently overlaps it. Do not extend the watch's time.
                val paired=exactJournalFit[i]
                val corroborated=when {
                    paired==null -> false
                    journal -> fitSupportsDevice(rows[paired],rows[j])
                    rows[j].source==FitnessSource.GYMUP && j==paired &&
                        byGroup.getValue(j) in attached ->
                        groups[target].any { k -> fitSupportsDevice(rows[i],rows[k]) }
                    rows[j].source in deviceSources && byGroup.getValue(paired) in attached &&
                        root(byGroup.getValue(paired))==target ->
                        fitSupportsDevice(rows[i],rows[j])
                    else -> false
                }
                val m=when {
                    full || corroborated -> Match.CONFIDENT
                    byGroup.getValue(j) in attached && n!=Match.NONE -> Match.POSSIBLE
                    else -> n
                }
                if(m==Match.NONE)continue
                related[i].add(j);related[j].add(i)
                out.getOrPut(target){mutableListOf()}.add(i to m)
            }
            return out
        }
        fun attach(group:Int,target:Int,journal:Boolean) {
            attached[group]=target
            fragmentIndices.addAll(groups[group].filter { i ->
                journal || groups[target].none { j ->
                    rows[j].source in anchorSources && match(rows[i],rows[j])==Match.CONFIDENT
                }
            })
        }
        // A single performed journal per watch episode may be nested. If two
        // separate GymUp sessions both point to one watch, retain them for
        // review rather than counting duplicated exercises as independent sets.
        val journals=groups.indices.filter { g ->
            groups[g].any { rows[it].source==FitnessSource.GYMUP } &&
                groups[g].none { rows[it].source in deviceSources }
        }
        val journalChoices=mutableMapOf<Int,Int>()
        for(g in journals) {
            val c=candidates(g,groups[g].filter { rows[it].source==FitnessSource.GYMUP },true)
            if(c.size==1 && c.values.single().all { it.second==Match.CONFIDENT })journalChoices[g]=c.keys.single()
        }
        journalChoices.entries.groupBy { it.value }.values.filter { it.size==1 }.forEach { entries ->
            val (g,target)=entries.single()
            attach(g,target,true)
        }
        for(g in groups.indices) {
            if(g in attached || groups[g].any { rows[it].source !in transportSources })continue
            val c=candidates(g,groups[g],false)
            if(c.size!=1)continue
            val target=c.keys.single()
            // Every member must have positive evidence for this same original
            // workout, and a contradictory confident external link blocks it.
            val evidence=c.values.single()
            if(evidence.none { it.second==Match.CONFIDENT } ||
                groups[g].any { i -> evidence.none { it.first==i && it.second==Match.CONFIDENT } } ||
                groups[g].any { i -> strong[i].any { j -> root(byGroup.getValue(j))!=target && byGroup.getValue(j)!=g } })
                continue
            attach(g,target,false)
        }
        // Standalone performed journal and unique exact-time Fit export
        // represent one event even when the journal was left open for days.
        // Without a measured watch, do not assert a device-recorded duration.
        for((j,f) in pairs.filter { countsJ[it.first]==1 && countsF[it.second]==1 }) {
            if((rows[j].elapsedSeconds ?: 0)<=4*3600L)continue
            val jg=byGroup.getValue(j);val fg=byGroup.getValue(f)
            if(jg==fg || jg in attached || fg in attached ||
                jg in attached.values || fg in attached.values ||
                groups[jg].size!=1 || groups[fg].size!=1)continue
            if(anchors.any { k ->
                rows[k].source in deviceSources &&
                    (nestedMatch(rows[f],rows[k])!=Match.NONE ||
                     match(rows[f],rows[k])!=Match.NONE ||
                     fitSupportsDevice(rows[f],rows[k]))
            })continue
            attached[jg]=fg
            fragmentIndices.add(j)
        }
        val fragmentsByGroup=attached.entries.groupBy({it.value},{it.key})
        val mergedGroups=groups.indices.filter { it !in attached }.map { g ->
            groups[g]+fragmentsByGroup[g].orEmpty().flatMap { groups[it] }
        }
        // Ambiguous links are between episodes, not between the copies or
        // partial intervals that are already members of one episode.
        val result=mergedGroups.map { group->
            val members=group.toSet()
            val observations=group.map { rows[it] }.sortedBy { it.key }
            val external=group.flatMap { related[it] }.filter { it !in members }.distinct()
                .map { rows[it].key }.sorted()
            val fragments=group.filter { it in fragmentIndices }.map { rows[it].key }.sorted()
            val primary=observations.filterNot { it.key in fragments }
                .minWith(compareBy<FitnessSession> { sessionRank(it) }.thenBy { it.key })
            SessionEpisode(primary,observations,external.isNotEmpty(),external,fragments)
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
