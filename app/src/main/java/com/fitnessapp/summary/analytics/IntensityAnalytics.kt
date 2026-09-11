package com.fitnessapp.summary.analytics

import com.fitnessapp.summary.data.GarminActivity
import com.fitnessapp.summary.data.GarminHeartRateZone
import com.fitnessapp.summary.data.Workout
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The five heart-rate zones, as percentages of maximum heart rate.
 *
 * These are Garmin's own default bands - the ones the watch itself shows - so a zone
 * named here means the same thing it means on the watch: 60/70/80/90 % of HRmax. Keeping
 * Garmin's split rather than inventing a better one matters because the user reads both
 * screens; two different "Z2" would be worse than no zones at all.
 *
 * [lowerPercent] is inclusive and the upper bound is the next zone's lower bound, so the
 * bands tile the whole range with no gap and no overlap. Zone 1 starts at zero rather
 * than at Garmin's 50 %: below half of HRmax is not a sixth intensity, it is the same
 * "not really training" bucket, and splitting it out would only add an always-tiny row.
 */
enum class HeartRateZone(val number: Int, val title: String, val defaultLowerPercent: Int) {
    Z1(1, "Разминка", 0),
    Z2(2, "Лёгкая", 60),
    Z3(3, "Аэробная", 70),
    Z4(4, "Пороговая", 80),
    Z5(5, "Максимальная", 90);

    companion object {
        /** Zone 1 is open at the bottom: below it is not a sixth intensity, it is the same bucket. */
        fun of(heartRate: Int, boundaries: ZoneBoundaries): HeartRateZone =
            entries.last { heartRate >= boundaries.lowerBpm(it) || it == Z1 }
    }
}

/** Where the five boundaries on screen came from. The card says which, because it matters. */
enum class ZoneSource { GARMIN, MANUAL, ESTIMATED }

/**
 * The five zone floors actually in force, in beats per minute, plus where they came from.
 *
 * **Absolute bpm, not percentages.** The first version of this file modelled zones as
 * fixed percentages of a maximum heart rate, and that was the wrong shape twice over:
 * Garmin lets the user derive zones from heart-rate reserve or from lactate threshold as
 * well as from maximum, and the maximum itself was something the app had to guess because
 * it has never known the user's age. Absolute floors are what Garmin actually stores, so
 * reading them needs no model at all - and the percentage model survives only as
 * [fromHrMax], for installs with no Garmin login.
 *
 * [floors] is always five ascending values, Z1 first. Anything below the Z2 floor is Z1,
 * whatever Garmin nominally puts there (its own zone 1 usually starts near 50 % of max).
 */
data class ZoneBoundaries(
    val floors: List<Int>,
    val maxHeartRate: Int,
    val source: ZoneSource,
    /** Garmin's sport profile these came from, when [source] is [ZoneSource.GARMIN]. */
    val sport: String = "",
    /** How Garmin says it derived them - percent of max, of reserve, of threshold. */
    val method: String = ""
) {
    fun lowerBpm(zone: HeartRateZone): Int = floors[zone.ordinal]

    /** Upper bound, exclusive; null for [HeartRateZone.Z5], which is open-ended. */
    fun upperBpmExclusive(zone: HeartRateZone): Int? = floors.getOrNull(zone.ordinal + 1)

    companion object {
        /**
         * Garmin's own configured zones for one sport profile - the authoritative case.
         * Null when the row didn't parse into five ascending floors, because a partially
         * read row would put every session into whichever band the zeros collapsed into
         * and look exactly like data.
         */
        fun fromGarmin(row: GarminHeartRateZone): ZoneBoundaries? {
            if (!row.isUsable) return null
            return ZoneBoundaries(
                floors = row.floors,
                maxHeartRate = row.maxHeartRateUsed,
                source = ZoneSource.GARMIN,
                sport = row.sport,
                method = row.method
            )
        }

        /**
         * The fallback model: Garmin's default percentages of [hrMax] (60/70/80/90 %).
         * Used only when Garmin has not supplied real zones - either no login, or the
         * account has none configured.
         */
        fun fromHrMax(hrMax: Int, source: ZoneSource): ZoneBoundaries = ZoneBoundaries(
            floors = HeartRateZone.entries.map { ceil(hrMax * it.defaultLowerPercent / 100.0).toInt() },
            maxHeartRate = hrMax,
            source = source
        )
    }
}

/**
 * One recorded session, from whichever source knew about it, with the two numbers this
 * file needs: how long it lasted and what the heart rate was.
 *
 * [avgHeartRate] is 0 when the session carries no usable heart rate - the same "0 means
 * unknown" convention the rest of the app uses. Those sessions are kept in the list
 * rather than dropped: they have to be counted somewhere visible, or a wrist that reads
 * nothing underwater would quietly shrink the swim half of the training week.
 */
data class IntensitySession(
    val dateEpochDay: Long,
    val startTimeMillis: Long,
    val typeKey: String,
    val minutes: Int,
    val avgHeartRate: Int,
    val maxHeartRate: Int
) {
    val hasHeartRate: Boolean get() = avgHeartRate > 0
}

/** One zone's share of the period. [minutes] is 0 for a zone nothing landed in. */
data class ZoneShare(
    val zone: HeartRateZone,
    val minutes: Int,
    val sessions: Int,
    val lowerBpm: Int,
    val upperBpmExclusive: Int?
)

/**
 * Where the training time of a period actually sat.
 *
 * [minutesWithoutHeartRate] and [sportsWithoutHeartRate] are not an afterthought - they
 * are the reason this type exists rather than a bare map. A session with no heart rate
 * cannot be placed, and silently leaving it out makes the remaining percentages look like
 * the whole picture.
 */
data class IntensityBreakdown(
    val boundaries: ZoneBoundaries,
    val zones: List<ZoneShare>,
    val totalMinutes: Int,
    val totalSessions: Int,
    val minutesWithoutHeartRate: Int,
    val sessionsWithoutHeartRate: Int,
    /** typeKey -> session count, for the sessions that carried no heart rate. */
    val sportsWithoutHeartRate: Map<String, Int>
) {
    val isEmpty: Boolean get() = totalSessions == 0 && sessionsWithoutHeartRate == 0

    /** This zone's share of the classified time, 0..100. */
    fun sharePercent(share: ZoneShare): Int =
        if (totalMinutes <= 0) 0 else Math.round(share.minutes * 100f / totalMinutes)
}

/**
 * Training intensity: how much of a period's training time sat in which heart-rate zone.
 *
 * **This is the one thing the app could not answer at all.** Every activity row already
 * carries `avgHeartRate` and `maxHeartRate`, but nothing anywhere turned them into zones,
 * so a year in which the volume was cut correctly and the intensity was cut by accident
 * looked exactly like a year of sensible training. Reduced-volume training keeps its
 * adaptations through intensity, not through hours, which makes this the one distribution
 * worth having on the screen.
 *
 * ### The honest limitation, stated everywhere this is shown
 *
 * The app stores one average heart rate per session, not a per-minute series (Garmin's
 * `wellness-service/wellness/dailyHeartRate` is in the backlog and is not read). So the
 * whole duration of a session is charged to the zone of its **average** - a 40-minute run
 * averaging 128 counts as 40 minutes of Z2, even though some of it was certainly Z3.
 *
 * That makes the distribution narrower than the truth: it understates the extremes and
 * overstates the middle. It does not make it useless - the question these numbers answer
 * is "which zone is my typical session in", and for that the session average is the right
 * unit anyway - but the screen has to say so, in the same way [SportDistanceAnalytics]
 * names the sports it could not classify. A number whose method is invisible gets read as
 * a measurement.
 */
object IntensityAnalytics {

    /**
     * Heart rates outside this band are read as sensor artefacts, not as data.
     *
     * Not a hypothetical: this account's own export peaks at 244 and 214 bpm in years
     * whose 95th percentile is 177, which is a chest-strap dropout rather than a heart.
     * One such value in the suggestion below would push HRmax up by 60 bpm and shift
     * every zone boundary with it.
     */
    private val PLAUSIBLE_HEART_RATE = 40..230

    /**
     * How many sessions must carry a usable maximum before [suggestHrMax] will offer one.
     * A percentile over three rides is not an estimate, it is the largest of three rides.
     */
    const val MIN_SESSIONS_FOR_SUGGESTION = 10

    /**
     * Every recorded session of the window, from both sources, without double-counting.
     *
     * Same rule and the same window as the weekly volume charts: Garmin's row wins where
     * both sources describe one session off the same watch, and a Health Connect workout
     * is only added when no Garmin activity started within
     * [SportDistanceAnalytics.WORKOUT_MATCH_WINDOW_MILLIS] of it. Keeping Health Connect
     * in means an install that never logged into Garmin still gets this card.
     *
     * Unlike the distance charts this keeps **every** sport, not three: the question here
     * is where the training time went, and strength work and rowing are training time.
     */
    fun sessions(garmin: List<GarminActivity>, workouts: List<Workout>): List<IntensitySession> {
        val fromGarmin = garmin.mapNotNull { activity ->
            if (activity.durationMinutes <= 0) return@mapNotNull null
            IntensitySession(
                dateEpochDay = activity.dateEpochDay,
                startTimeMillis = activity.startTimeMillis,
                typeKey = activity.typeKey,
                minutes = activity.durationMinutes,
                avgHeartRate = plausible(activity.avgHeartRate),
                maxHeartRate = plausible(activity.maxHeartRate)
            )
        }
        val fromHealth = workouts.mapNotNull { workout ->
            if (workout.durationMinutes <= 0) return@mapNotNull null
            val alreadyKnown = garmin.any {
                abs(it.startTimeMillis - workout.startTimeMillis) <= SportDistanceAnalytics.WORKOUT_MATCH_WINDOW_MILLIS
            }
            if (alreadyKnown) return@mapNotNull null
            IntensitySession(
                dateEpochDay = workout.dateEpochDay,
                startTimeMillis = workout.startTimeMillis,
                // Health Connect has no sport key, only its own enum - and the only thing
                // the key is used for here is naming the sports that came without a heart
                // rate. Left blank so it is labelled "без типа" rather than mislabelled.
                typeKey = "",
                minutes = workout.durationMinutes,
                avgHeartRate = plausible(workout.avgHeartRate),
                maxHeartRate = plausible(workout.maxHeartRate)
            )
        }
        return (fromGarmin + fromHealth).sortedBy { it.startTimeMillis }
    }

    /** An artefact or a missing reading both come back as 0 - the app's "no data" everywhere else. */
    private fun plausible(heartRate: Int): Int = if (heartRate in PLAUSIBLE_HEART_RATE) heartRate else 0

    /**
     * The period's minutes split across the five zones at [hrMax].
     *
     * All five zones are always returned, empty ones included: a zero row is the finding
     * on a year with nothing above the aerobic band, and a chart that simply omits the row
     * hides exactly that.
     */
    fun breakdown(sessions: List<IntensitySession>, boundaries: ZoneBoundaries): IntensityBreakdown {
        val (withHr, withoutHr) = sessions.partition { it.hasHeartRate }
        val byZone = withHr.groupBy { HeartRateZone.of(it.avgHeartRate, boundaries) }
        return IntensityBreakdown(
            boundaries = boundaries,
            zones = HeartRateZone.entries.map { zone ->
                val inZone = byZone[zone].orEmpty()
                ZoneShare(
                    zone = zone,
                    minutes = inZone.sumOf { it.minutes },
                    sessions = inZone.size,
                    lowerBpm = boundaries.lowerBpm(zone),
                    upperBpmExclusive = boundaries.upperBpmExclusive(zone)
                )
            },
            totalMinutes = withHr.sumOf { it.minutes },
            totalSessions = withHr.size,
            minutesWithoutHeartRate = withoutHr.sumOf { it.minutes },
            sessionsWithoutHeartRate = withoutHr.size,
            sportsWithoutHeartRate = withoutHr
                .groupingBy { it.typeKey.ifBlank { "(без типа)" } }
                .eachCount()
                .toList()
                .sortedByDescending { it.second }
                .toMap()
        )
    }

    /**
     * Which five boundaries to actually use, from everything that might know them.
     *
     * Order: a maximum the user typed in wins, then Garmin's own configured zones, then
     * the estimate from their recorded maxima. Null when nothing knows - and null is shown
     * as "не задан", never quietly filled in.
     *
     * The manual value sits on top of Garmin deliberately, even though Garmin is the first
     * source everywhere else in this app: typing a number in is an explicit act, and
     * silently ignoring it would be worse than the thing this ordering is protecting
     * against. The card names which one is in force and offers to drop the override, so a
     * value entered before Garmin's zones arrived can't shadow them without saying so.
     *
     * Among Garmin's sport profiles the DEFAULT one is used. Per-sport zones are stored
     * and shown, but a single distribution across running, cycling and the gym has to be
     * counted against a single ladder, and swapping ladders per session would make the
     * percentages incomparable with each other.
     */
    fun resolveBoundaries(
        garminZones: List<GarminHeartRateZone>,
        manualHrMax: Int,
        estimatedHrMax: Int?
    ): ZoneBoundaries? {
        if (manualHrMax > 0) return ZoneBoundaries.fromHrMax(manualHrMax, ZoneSource.MANUAL)
        garminDefault(garminZones)?.let { return it }
        return estimatedHrMax?.let { ZoneBoundaries.fromHrMax(it, ZoneSource.ESTIMATED) }
    }

    /** Garmin's DEFAULT profile, or the first usable one if the account has no DEFAULT. */
    fun garminDefault(garminZones: List<GarminHeartRateZone>): ZoneBoundaries? {
        val usable = garminZones.filter { it.isUsable }
        val row = usable.firstOrNull { it.sport.equals("DEFAULT", ignoreCase = true) }
            ?: usable.firstOrNull()
            ?: return null
        return ZoneBoundaries.fromGarmin(row)
    }

    /**
     * How far back the HRmax estimate looks - fixed, and deliberately NOT the period the
     * user has selected on «Тренды».
     *
     * This was a real bug: the estimate was computed over whatever window the chips were
     * set to, so every boundary moved when the period changed, and the same number came
     * out differently on «Тренды» (selected window) and in «Я» (a year). A maximum heart
     * rate is a property of the person, not of the chart's x-axis.
     */
    const val HR_MAX_SAMPLE_DAYS = 365

    /**
     * A maximum heart rate estimated from the user's own sessions: the 95th percentile of
     * their per-session maxima, or null when there is not enough to say.
     *
     * The percentile, not the maximum. A single session peak is whatever the sensor did
     * on its worst day; the 95th percentile is the number that repeated often enough to
     * be a heart. Nearest-rank, which for the usual 30-200 sessions lands on a real
     * recorded value rather than an interpolation between two.
     *
     * It is deliberately offered as a **suggestion**, never applied silently. A percentile
     * of observed maxima is a floor on true HRmax, not an estimate of it - it can only see
     * efforts that were actually made, so a year of easy training reads low (this
     * account: 181 / 177 / 176 / 160 across four years, the last of them a deliberately
     * easy season). The user knows their tested HRmax or their age; the app knows only
     * what the watch recorded, and it should say which of the two it is showing.
     */
    fun suggestHrMax(sessions: List<IntensitySession>): Int? {
        val maxima = sessions.map { it.maxHeartRate }.filter { it > 0 }.sorted()
        if (maxima.size < MIN_SESSIONS_FOR_SUGGESTION) return null
        val rank = ceil(maxima.size * 0.95).toInt().coerceIn(1, maxima.size)
        return maxima[rank - 1]
    }
}
