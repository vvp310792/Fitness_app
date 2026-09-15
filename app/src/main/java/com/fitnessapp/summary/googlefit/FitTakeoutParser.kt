package com.fitnessapp.summary.googlefit

import com.fitnessapp.summary.data.FitDay
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Turns the raw per-source dumps of a Google Takeout "Fit" archive into one row per day.
 *
 * Pure Kotlin on purpose: everything that touches the zip and the `content://` URI lives in
 * [FitTakeoutReader], and the meaning of the bytes lives here, where a JVM test can reach it.
 *
 * **What the archive actually looks like** - measured on this account's real export
 * (2026-09-14), not taken from documentation:
 *
 * - `Takeout/Fit/Все данные/` holds **233 JSON files, 1264 MB uncompressed**: one file per
 *   (metric x device x derivation). `Takeout/Fit/Тренировки/` holds 3541 `.tcx` - the same
 *   sessions already imported from Strava and Garmin, deliberately not read.
 * - There is **no daily-summary CSV** in this export. The per-day numbers have to be
 *   aggregated from the streams.
 * - Every file is `{"Data Source": "<id>", "Data Points": [ {...}, {...} ] }` with one point
 *   per line, flat:
 *   `{"fitValue":[{"value":{"intVal":48}}],"originDataSourceId":"...","endTimeNanos":...,`
 *   `"dataTypeName":"com.google.step_count.delta","startTimeNanos":...,"modifiedTimeMillis":...}`
 *   Note `endTimeNanos` comes **before** `startTimeNanos`, and `rawTimestampNanos` sits in
 *   the same object - so the keys are matched exactly, not by "contains Nanos".
 * - **The points are not in chronological order.** The first and last line of a file are
 *   not its first and last day, so nothing here may assume sorting.
 *
 * **Only Google's own merged streams are read.** Fit already de-duplicates across devices
 * and publishes the result as a stream of its own (`estimated_steps`, `merge_calories_expended`,
 * `merge_heart_rate_bpm`, ...). Summing the per-device files instead would count the same
 * steps once per phone that saw them - the very bug that `dataOriginFilter` exists to prevent
 * on the Health Connect side. This account has five phones plus two Mi Bands in the archive.
 *
 * Recognising those streams is **structural**, not a list of file names: a merged stream is a
 * source id whose head (before any `<-`) has exactly four `:`-separated parts and whose owner
 * is `com.google.android.gms`. Per-device ids carry extra segments (`...:gms:HUAWEI:JSN-L21:hash:...`)
 * and fall out on their own. Anything that looks merged but is not recognised is reported
 * rather than dropped ([Outcome.unknownStreams]) - the same rule as `unclassified()` in
 * `SportDistanceAnalytics` and `GarminFetch.NoData`: a swallowed case must leave a trace.
 */
object FitTakeoutParser {

    /** The streams worth reading. Everything else in the archive is ignored by design. */
    enum class Stream {
        /** `estimated_steps` - what Google Fit itself shows. Preferred over [STEPS_MERGED]. */
        STEPS_ESTIMATED,
        /** `merge_step_deltas` - the merge one step earlier in the chain; a fallback. */
        STEPS_MERGED,
        CALORIES,
        DISTANCE,
        HEART_RATE,
        RESTING_HEART_RATE,
        WEIGHT,
        SLEEP
    }

    data class Outcome(
        val days: List<FitDay>,
        /** Points actually read, per stream - the honest denominator for everything above. */
        val pointsByStream: Map<Stream, Int>,
        /** Merged streams found in the archive that this parser does not know. */
        val unknownStreams: List<String>,
        /** Files opened and files skipped without reading, for the log. */
        val filesRead: Int,
        val filesSkipped: Int
    )

    // Google's sleep segment codes. 1 awake, 3 out-of-bed, the rest are sleep.
    private const val SLEEP_AWAKE = 1
    private const val SLEEP_GENERIC = 2
    private const val SLEEP_OUT_OF_BED = 3
    private const val SLEEP_LIGHT = 4
    private const val SLEEP_DEEP = 5
    private const val SLEEP_REM = 6

    private const val PLAUSIBLE_HR_MIN = 25
    private const val PLAUSIBLE_HR_MAX = 230
    private const val PLAUSIBLE_WEIGHT_MIN_G = 30_000
    private const val PLAUSIBLE_WEIGHT_MAX_G = 300_000

    /**
     * Earliest day worth keeping. The Strava export carried rows stamped 1970 and 2010, and
     * one `.tcx` in this very archive is named `2007-01-09` - junk timestamps are normal in
     * exported data, and a single one of them would have the weekly charts draw a decade of
     * confident zeroes.
     */
    private const val FIRST_PLAUSIBLE_YEAR = 2008

    /**
     * Is this a stream Google merged across devices, i.e. one we may read without double
     * counting? Returns null for per-device and per-app streams.
     */
    fun classify(dataSourceId: String): Stream? {
        val parts = head(dataSourceId).split(':')
        if (parts.size != 4) return null
        val (_, dataType, owner, stream) = parts
        if (owner != "com.google.android.gms") return null

        return when (dataType) {
            "com.google.step_count.delta" -> when {
                stream == "estimated_steps" -> Stream.STEPS_ESTIMATED
                stream.startsWith("merge") -> Stream.STEPS_MERGED
                else -> null
            }
            // `startsWith("merge")` rather than the exact name: the merged distance stream was
            // filtered out of the archive survey before its spelling was seen, and a guessed
            // key does not fail loudly - it silently reads nothing. An unrecognised merged
            // stream is reported instead.
            "com.google.calories.expended" -> if (stream.startsWith("merge")) Stream.CALORIES else null
            "com.google.distance.delta" -> if (stream.startsWith("merge")) Stream.DISTANCE else null
            "com.google.heart_rate.bpm" -> when {
                stream.startsWith("resting_heart_rate") -> Stream.RESTING_HEART_RATE
                stream.startsWith("merge") -> Stream.HEART_RATE
                else -> null
            }
            "com.google.weight" -> if (stream.startsWith("merge")) Stream.WEIGHT else null
            "com.google.sleep.segment" -> if (stream == "merged" || stream.startsWith("merge")) Stream.SLEEP else null
            else -> null
        }
    }

    /**
     * Does this source id look like a cross-device merge at all? Used to tell "a stream we
     * chose not to read" from "a stream we failed to recognise" - only the second is worth
     * putting on screen.
     */
    fun looksMerged(dataSourceId: String): Boolean {
        val parts = head(dataSourceId).split(':')
        return parts.size == 4 && parts[2] == "com.google.android.gms"
    }

    /**
     * The part of a source id before its `<-` derivation chain.
     *
     * The archive writes that arrow as the JSON escape `\u003c-`, not as a literal `<`, and the
     * value is read out of the raw line rather than through a JSON decoder - so the escape is
     * undone here. Without it `from_high_accuracy_location\u003c-derived:...` still happens to
     * be rejected (too many `:` segments) while `resting_heart_rate\u003c-merge_heart_rate_bpm`
     * still happens to be accepted, and a rule that works by luck breaks on the next stream.
     */
    private fun head(dataSourceId: String): String = dataSourceId
        .replace("\\u003c", "<")
        .replace("\\u003C", "<")
        .substringBefore("<-")

    /** One data point, as far as this app cares. */
    data class Point(val startMillis: Long, val endMillis: Long, val value: Double)

    /**
     * Reads one data point out of a line of the `Data Points` array.
     *
     * Hand-rolled rather than `JSONObject`: the shape is flat, fixed and verified against the
     * real archive, and there are roughly 730 000 of these lines in the streams we read.
     * Returns null for the wrapper lines (`{`, `"Data Points": [`, `]`, `}`).
     */
    fun parsePoint(text: String): Point? {
        val start = longAfter(text, "\"startTimeNanos\":") ?: return null
        val end = longAfter(text, "\"endTimeNanos\":") ?: start
        val value = firstValue(text) ?: return null
        return Point(start / 1_000_000L, end / 1_000_000L, value)
    }

    /** `"intVal":48` or `"fpVal":39.59845851178147`, whichever comes first in `fitValue`. */
    private fun firstValue(text: String): Double? {
        val i = text.indexOf("\"intVal\":")
        val f = text.indexOf("\"fpVal\":")
        return when {
            i >= 0 && (f < 0 || i < f) -> doubleAt(text, i + 9)
            f >= 0 -> doubleAt(text, f + 8)
            else -> null
        }
    }

    private fun longAfter(text: String, key: String): Long? {
        val at = text.indexOf(key)
        if (at < 0) return null
        var i = at + key.length
        // Tolerates a quoted number: some Google exports write 64-bit values as strings.
        while (i < text.length && (text[i] == ' ' || text[i] == '"')) i++
        val from = i
        while (i < text.length && text[i].isDigit()) i++
        return if (i > from) text.substring(from, i).toLongOrNull() else null
    }

    private fun doubleAt(text: String, from: Int): Double? {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '"')) i++
        val start = i
        if (i < text.length && (text[i] == '-' || text[i] == '+')) i++
        while (i < text.length && (text[i].isDigit() || text[i] == '.' || text[i] == 'e' || text[i] == 'E' ||
                ((text[i] == '-' || text[i] == '+') && (text[i - 1] == 'e' || text[i - 1] == 'E')))
        ) i++
        return if (i > start) text.substring(start, i).toDoubleOrNull() else null
    }

    /**
     * Collects points into days. One instance per import; fed stream by stream, file by file.
     *
     * Deliberately mutable and incremental: the streams that matter are 220 MB uncompressed
     * together, they arrive one zip entry at a time and cannot be held in memory as objects.
     * What is held is one bucket per calendar day - a few thousand of them.
     */
    class Accumulator(private val zone: ZoneId) {

        private class Bucket {
            var steps = 0.0
            var stepsMerged = 0.0
            var calories = 0.0
            var distance = 0.0
            var hrSum = 0.0
            var hrCount = 0
            var hrMin = 0
            var hrMax = 0
            var resting = 0
            var weightGrams = 0
            var weightAtMillis = 0L
            var sleepTotal = 0L
            var sleepDeep = 0L
            var sleepLight = 0L
            var sleepRem = 0L
            var sleepAwake = 0L
        }

        private val buckets = HashMap<Long, Bucket>()
        private val counts = HashMap<Stream, Int>()
        private var sawEstimatedSteps = false

        val pointsByStream: Map<Stream, Int> get() = counts

        /**
         * Feeds one line. A line normally holds exactly one point; if an export ever writes
         * the whole array on one line, it is split on the `fitValue` marker instead of being
         * silently read as a single point.
         */
        fun feedLine(stream: Stream, line: String): Int {
            if (!line.contains("\"fitValue\"")) return 0
            val marker = "{\"fitValue\""
            val first = line.indexOf(marker)
            val second = if (first >= 0) line.indexOf(marker, first + 1) else -1
            if (second < 0) return if (feedPoint(stream, line)) 1 else 0

            var read = 0
            var at = first
            while (at >= 0) {
                val next = line.indexOf(marker, at + 1)
                val chunk = if (next < 0) line.substring(at) else line.substring(at, next)
                if (feedPoint(stream, chunk)) read++
                at = next
            }
            return read
        }

        private fun feedPoint(stream: Stream, text: String): Boolean {
            val point = parsePoint(text) ?: return false
            val day = dayOf(point.startMillis) ?: return false
            counts[stream] = (counts[stream] ?: 0) + 1

            when (stream) {
                Stream.STEPS_ESTIMATED -> {
                    sawEstimatedSteps = true
                    bucket(day).steps += point.value
                }
                Stream.STEPS_MERGED -> bucket(day).stepsMerged += point.value
                Stream.CALORIES -> bucket(day).calories += point.value
                Stream.DISTANCE -> bucket(day).distance += point.value
                Stream.HEART_RATE -> {
                    val bpm = point.value.roundToInt()
                    if (bpm in PLAUSIBLE_HR_MIN..PLAUSIBLE_HR_MAX) {
                        val b = bucket(day)
                        b.hrSum += bpm
                        b.hrCount++
                        if (b.hrMin == 0 || bpm < b.hrMin) b.hrMin = bpm
                        if (bpm > b.hrMax) b.hrMax = bpm
                    }
                }
                Stream.RESTING_HEART_RATE -> {
                    val bpm = point.value.roundToInt()
                    if (bpm in PLAUSIBLE_HR_MIN..PLAUSIBLE_HR_MAX) {
                        val b = bucket(day)
                        // Google republishes the day's resting value as it is recomputed;
                        // the lowest is the one that means "resting".
                        if (b.resting == 0 || bpm < b.resting) b.resting = bpm
                    }
                }
                Stream.WEIGHT -> {
                    val grams = (point.value * 1000).roundToInt()
                    if (grams in PLAUSIBLE_WEIGHT_MIN_G..PLAUSIBLE_WEIGHT_MAX_G) {
                        val b = bucket(day)
                        // Last weighing of the day wins; the points are not sorted.
                        if (point.startMillis >= b.weightAtMillis) {
                            b.weightGrams = grams
                            b.weightAtMillis = point.startMillis
                        }
                    }
                }
                Stream.SLEEP -> feedSleep(point)
            }
            return true
        }

        /**
         * Sleep belongs to the morning it ended on - the same rule as everywhere else in this
         * app, and the one Garmin itself uses. A segment is filed by its END, not its start,
         * so a night from 23:40 Monday to 07:10 Tuesday is Tuesday's.
         */
        private fun feedSleep(point: Point) {
            val stage = point.value.roundToInt()
            if (stage == SLEEP_OUT_OF_BED) return
            val day = dayOf(point.endMillis) ?: return
            val minutes = (point.endMillis - point.startMillis) / 60_000L
            if (minutes <= 0) return
            val b = bucket(day)
            when (stage) {
                SLEEP_AWAKE -> b.sleepAwake += minutes
                SLEEP_DEEP -> { b.sleepDeep += minutes; b.sleepTotal += minutes }
                SLEEP_LIGHT -> { b.sleepLight += minutes; b.sleepTotal += minutes }
                SLEEP_REM -> { b.sleepRem += minutes; b.sleepTotal += minutes }
                SLEEP_GENERIC -> b.sleepTotal += minutes
                else -> Unit
            }
        }

        private fun bucket(day: Long) = buckets.getOrPut(day) { Bucket() }

        private fun dayOf(millis: Long): Long? {
            if (millis <= 0) return null
            val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
            if (date.year < FIRST_PLAUSIBLE_YEAR) return null
            return date.toEpochDay()
        }

        /** Rows for storage, all-zero days dropped. */
        fun build(nowMillis: Long): List<FitDay> = buckets.entries
            .sortedBy { it.key }
            .map { (day, b) ->
                FitDay(
                    dateEpochDay = day,
                    // `estimated_steps` is what Google Fit shows; `merge_step_deltas` is the
                    // same walk one link earlier. Whichever stream the archive actually has
                    // is used, and never both - that would count every step twice.
                    steps = (if (sawEstimatedSteps) b.steps else b.stepsMerged).roundToInt().toLong(),
                    caloriesKcal = b.calories.roundToInt(),
                    distanceMeters = b.distance.roundToInt(),
                    avgHeartRate = if (b.hrCount > 0) (b.hrSum / b.hrCount).roundToInt() else 0,
                    minHeartRate = b.hrMin,
                    maxHeartRate = b.hrMax,
                    restingHeartRate = b.resting,
                    weightGrams = b.weightGrams,
                    sleepTotalMinutes = b.sleepTotal.toInt(),
                    sleepDeepMinutes = b.sleepDeep.toInt(),
                    sleepLightMinutes = b.sleepLight.toInt(),
                    sleepRemMinutes = b.sleepRem.toInt(),
                    sleepAwakeMinutes = b.sleepAwake.toInt(),
                    updatedAtMillis = nowMillis
                )
            }
            .filterNot { it.isEmpty }
    }
}
