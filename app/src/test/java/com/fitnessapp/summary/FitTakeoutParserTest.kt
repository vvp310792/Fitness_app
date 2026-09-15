package com.fitnessapp.summary

import com.fitnessapp.summary.googlefit.FitTakeoutParser
import com.fitnessapp.summary.googlefit.FitTakeoutParser.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Written against the shapes in this account's real Takeout archive (2026-09-14), read out
 * of it by the inspection script rather than taken from Google's documentation - the same
 * rule that the Zepp, Strava and GymUp parsers were held to.
 */
class FitTakeoutParserTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    private fun nanosAt(date: String, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(LocalDate.parse(date), java.time.LocalTime.of(hour, minute), zone)
            .toInstant().toEpochMilli() * 1_000_000L

    private fun point(value: String, startNanos: Long, endNanos: Long = startNanos, type: String = "com.google.step_count.delta") =
        """{"fitValue":[{"value":{$value}}],"originDataSourceId":"raw:x","endTimeNanos":$endNanos,""" +
            """"dataTypeName":"$type","startTimeNanos":$startNanos,"modifiedTimeMillis":1761822679552,"rawTimestampNanos":0},"""

    // ---- classify ---------------------------------------------------------------------------

    @Test
    fun `merged streams are recognised`() {
        assertEquals(
            Stream.STEPS_ESTIMATED,
            FitTakeoutParser.classify("derived:com.google.step_count.delta:com.google.android.gms:estimated_steps")
        )
        assertEquals(
            Stream.STEPS_MERGED,
            FitTakeoutParser.classify("derived:com.google.step_count.delta:com.google.android.gms:merge_step_deltas")
        )
        assertEquals(
            Stream.CALORIES,
            FitTakeoutParser.classify("derived:com.google.calories.expended:com.google.android.gms:merge_calories_expended")
        )
        assertEquals(
            Stream.HEART_RATE,
            FitTakeoutParser.classify("derived:com.google.heart_rate.bpm:com.google.android.gms:merge_heart_rate_bpm")
        )
        assertEquals(
            Stream.WEIGHT,
            FitTakeoutParser.classify("derived:com.google.weight:com.google.android.gms:merge_weight")
        )
        assertEquals(
            Stream.SLEEP,
            FitTakeoutParser.classify("derived:com.google.sleep.segment:com.google.android.gms:merged")
        )
    }

    /**
     * The archive writes the derivation arrow as the JSON escape, and the source id is read
     * straight out of the raw line. Without undoing it, this id and the per-device ones are
     * told apart only by how many colons happen to survive.
     */
    @Test
    fun `resting heart rate is its own stream, escaped arrow and all`() {
        assertEquals(
            Stream.RESTING_HEART_RATE,
            FitTakeoutParser.classify(
                "derived:com.google.heart_rate.bpm:com.google.android.gms:resting_heart_rate\\u003c-merge_heart_rate_bpm"
            )
        )
    }

    /** Per-device streams are the double-counting trap: five phones, two bands, one walk. */
    @Test
    fun `per-device streams are not read`() {
        assertNull(
            FitTakeoutParser.classify(
                "derived:com.google.step_count.delta:com.google.android.gms:HUAWEI:JSN-L21:6fb42c729b26d4bc:" +
                    "derive_step_deltas\\u003c-raw:com.google.step_count.cumulative:HUAWEI:JSN-L21:6fb42c729b26d4bc:step counter"
            )
        )
        assertNull(FitTakeoutParser.classify("raw:com.google.step_count.delta:com.mc.miband1:Mi Band Notify steps"))
        assertNull(FitTakeoutParser.classify("raw:com.google.heart_rate.bpm:com.garmin.android.apps.connectmobile:health_platform"))
        assertNull(FitTakeoutParser.classify("derived:com.google.step_count.delta:com.google.android.fit:Xiaomi:22071212AG:hash:top_level"))
    }

    @Test
    fun `location and speed are ignored even when merged`() {
        assertNull(
            FitTakeoutParser.classify(
                "derived:com.google.distance.delta:com.google.android.gms:from_high_accuracy_location" +
                    "\\u003c-derived:com.google.location.sample:com.google.android.gms:merge_high_fidelity"
            )
        )
        assertNull(FitTakeoutParser.classify("derived:com.google.activity.segment:com.google.android.gms:merge_activity_segments"))
    }

    /** An unrecognised merge must be reportable, not silently equal to "no such metric". */
    @Test
    fun `an unknown gms merge still looks merged`() {
        val id = "derived:com.google.hydration:com.google.android.gms:merge_hydration"
        assertNull(FitTakeoutParser.classify(id))
        assertTrue(FitTakeoutParser.looksMerged(id))
        assertTrue(!FitTakeoutParser.looksMerged("raw:com.google.weight:com.mc.miband1:"))
    }

    // ---- parsePoint -------------------------------------------------------------------------

    @Test
    fun `int and float values are both read`() {
        val steps = FitTakeoutParser.parsePoint(point("\"intVal\":48", 1_761_818_175_682_234_280L))
        assertNotNull(steps)
        assertEquals(48.0, steps!!.value, 0.0001)

        val kcal = FitTakeoutParser.parsePoint(point("\"fpVal\":39.59845851178147", 1_683_292_417_418_000_000L))
        assertEquals(39.59845851178147, kcal!!.value, 1e-9)
    }

    /**
     * `endTimeNanos` comes BEFORE `startTimeNanos` in every line of the archive, and
     * `rawTimestampNanos` sits in the same object. Matching "Nanos" loosely would read the
     * wrong number and nothing would look broken.
     */
    @Test
    fun `start and end are not confused with each other or with rawTimestampNanos`() {
        val start = 1_761_818_175_682_234_280L
        val end = 1_761_818_231_058_209_917L
        val p = FitTakeoutParser.parsePoint(point("\"intVal\":48", start, end))!!
        assertEquals(start / 1_000_000L, p.startMillis)
        assertEquals(end / 1_000_000L, p.endMillis)
    }

    @Test
    fun `wrapper lines are not points`() {
        assertNull(FitTakeoutParser.parsePoint("{"))
        assertNull(FitTakeoutParser.parsePoint("    \"Data Points\": ["))
        assertNull(FitTakeoutParser.parsePoint("]}"))
    }

    // ---- aggregation ------------------------------------------------------------------------

    @Test
    fun `steps are summed into local days`() {
        val acc = FitTakeoutParser.Accumulator(zone)
        acc.feedLine(Stream.STEPS_ESTIMATED, point("\"intVal\":100", nanosAt("2016-05-10", 9)))
        acc.feedLine(Stream.STEPS_ESTIMATED, point("\"intVal\":250", nanosAt("2016-05-10", 19)))
        acc.feedLine(Stream.STEPS_ESTIMATED, point("\"intVal\":7", nanosAt("2016-05-11", 8)))

        val days = acc.build(0L)
        assertEquals(2, days.size)
        assertEquals(350L, days.first { it.dateEpochDay == LocalDate.parse("2016-05-10").toEpochDay() }.steps)
        assertEquals(7L, days.first { it.dateEpochDay == LocalDate.parse("2016-05-11").toEpochDay() }.steps)
    }

    /**
     * Both step merges exist in the archive and describe the same walk. Counting both would
     * double every day; `estimated_steps` is what Google Fit itself shows, so it wins.
     */
    @Test
    fun `the two step streams never add up`() {
        val acc = FitTakeoutParser.Accumulator(zone)
        acc.feedLine(Stream.STEPS_ESTIMATED, point("\"intVal\":100", nanosAt("2016-05-10", 9)))
        acc.feedLine(Stream.STEPS_MERGED, point("\"intVal\":98", nanosAt("2016-05-10", 9)))
        assertEquals(100L, acc.build(0L).single().steps)
    }

    /** With no `estimated_steps` in the archive, the other merge is used rather than nothing. */
    @Test
    fun `the fallback step stream is used when the preferred one is absent`() {
        val acc = FitTakeoutParser.Accumulator(zone)
        acc.feedLine(Stream.STEPS_MERGED, point("\"intVal\":98", nanosAt("2016-05-10", 9)))
        assertEquals(98L, acc.build(0L).single().steps)
    }

    @Test
    fun `heart rate becomes an average, a minimum and a maximum`() {
        val acc = FitTakeoutParser.Accumulator(zone)
        listOf(60, 80, 160).forEach {
            acc.feedLine(Stream.HEART_RATE, point("\"fpVal\":$it", nanosAt("2019-03-01", 12)))
        }
        val day = acc.build(0L).single()
        assertEquals(100, day.avgHeartRate)
        assertEquals(60, day.minHeartRate)
        assertEquals(160, day.maxHeartRate)
    }

    /** The same rule as everywhere else here: a sensor glitch is "no data", not a real value. */
    @Test
    fun `implausible heart rate is dropped, not stored`() {
        val acc = FitTakeoutParser.Accumulator(zone)
        acc.feedLine(Stream.HEART_RATE, point("\"fpVal\":70", nanosAt("2019-03-01", 12)))
        acc.feedLine(Stream.HEART_RATE, point("\"fpVal\":600", nanosAt("2019-03-01", 13)))
        acc.feedLine(Stream.HEART_RATE, point("\"fpVal\":0", nanosAt("2019-03-01", 14)))
        val day = acc.build(0L).single()
        assertEquals(70, day.avgHeartRate)
        assertEquals(70, day.maxHeartRate)
    }

    @Test
    fun `resting heart rate keeps the lowest value republished for the day`() {
        val acc = FitTakeoutParser.Accumulator(zone)
        acc.feedLine(Stream.RESTING_HEART_RATE, point("\"fpVal\":62", nanosAt("2019-03-01", 8)))
        acc.feedLine(Stream.RESTING_HEART_RATE, point("\"fpVal\":57", nanosAt("2019-03-01", 20)))
        assertEquals(57, acc.build(0L).single().restingHeartRate)
    }

    /** The points are NOT in file order, so "latest wins" has to compare timestamps. */
    @Test
    fun `the last weighing of the day wins regardless of the order it is read in`() {
        val acc = FitTakeoutParser.Accumulator(zone)
        acc.feedLine(Stream.WEIGHT, point("\"fpVal\":78.4", nanosAt("2015-06-24", 21)))
        acc.feedLine(Stream.WEIGHT, point("\"fpVal\":75.0", nanosAt("2015-06-24", 7)))
        assertEquals(78400, acc.build(0L).single().weightGrams)
    }

    @Test
    fun `sleep belongs to the morning it ended on and splits by stage`() {
        val acc = FitTakeoutParser.Accumulator(zone)
        // 23:00 Monday -> 00:00 Tuesday, light; then 00:00 -> 01:00 Tuesday, deep.
        acc.feedLine(
            Stream.SLEEP,
            point("\"intVal\":4", nanosAt("2019-06-10", 23), nanosAt("2019-06-11", 0), "com.google.sleep.segment")
        )
        acc.feedLine(
            Stream.SLEEP,
            point("\"intVal\":5", nanosAt("2019-06-11", 0), nanosAt("2019-06-11", 1), "com.google.sleep.segment")
        )
        val day = acc.build(0L).single()
        assertEquals(LocalDate.parse("2019-06-11").toEpochDay(), day.dateEpochDay)
        assertEquals(120, day.sleepTotalMinutes)
        assertEquals(60, day.sleepLightMinutes)
        assertEquals(60, day.sleepDeepMinutes)
    }

    /** Awake time is counted separately and never inside the total - as in every other source. */
    @Test
    fun `awake is not sleep and out-of-bed is nothing`() {
        val acc = FitTakeoutParser.Accumulator(zone)
        acc.feedLine(
            Stream.SLEEP,
            point("\"intVal\":1", nanosAt("2019-06-11", 3), nanosAt("2019-06-11", 4), "com.google.sleep.segment")
        )
        acc.feedLine(
            Stream.SLEEP,
            point("\"intVal\":3", nanosAt("2019-06-11", 4), nanosAt("2019-06-11", 5), "com.google.sleep.segment")
        )
        val days = acc.build(0L)
        // Awake alone is not a measurement of anything, so the day stays empty and is dropped.
        assertTrue(days.isEmpty())
    }

    /**
     * Junk timestamps are normal in exported data - the Strava export carried 1970 and 2010
     * rows, and one `.tcx` in this very archive is stamped 2007. A single one of them would
     * have the weekly charts draw a decade of confident zeroes.
     */
    @Test
    fun `absurdly old points are dropped`() {
        val acc = FitTakeoutParser.Accumulator(zone)
        acc.feedLine(Stream.STEPS_ESTIMATED, point("\"intVal\":5", 1_000_000_000L))
        acc.feedLine(Stream.STEPS_ESTIMATED, point("\"intVal\":5", nanosAt("2007-01-09", 1)))
        acc.feedLine(Stream.STEPS_ESTIMATED, point("\"intVal\":90", nanosAt("2016-05-10", 9)))
        assertEquals(1, acc.build(0L).size)
    }

    /** All-zero days never reach the table, whatever produced them. */
    @Test
    fun `a day with nothing measured is not a day`() {
        val acc = FitTakeoutParser.Accumulator(zone)
        acc.feedLine(Stream.STEPS_ESTIMATED, point("\"intVal\":0", nanosAt("2016-05-10", 9)))
        assertTrue(acc.build(0L).isEmpty())
    }

    /** A pretty-printed archive gives one point per line; a compact one would not. */
    @Test
    fun `several points on one line are all read`() {
        val acc = FitTakeoutParser.Accumulator(zone)
        val line = point("\"intVal\":10", nanosAt("2016-05-10", 9)) +
            point("\"intVal\":20", nanosAt("2016-05-10", 10)) +
            point("\"intVal\":30", nanosAt("2016-05-10", 11))
        assertEquals(3, acc.feedLine(Stream.STEPS_ESTIMATED, line))
        assertEquals(60L, acc.build(0L).single().steps)
    }

    @Test
    fun `points are counted per stream`() {
        val acc = FitTakeoutParser.Accumulator(zone)
        acc.feedLine(Stream.STEPS_ESTIMATED, point("\"intVal\":10", nanosAt("2016-05-10", 9)))
        acc.feedLine(Stream.CALORIES, point("\"fpVal\":12.5", nanosAt("2016-05-10", 9)))
        acc.feedLine(Stream.CALORIES, point("\"fpVal\":13.5", nanosAt("2016-05-10", 10)))
        assertEquals(1, acc.pointsByStream[Stream.STEPS_ESTIMATED])
        assertEquals(2, acc.pointsByStream[Stream.CALORIES])
        assertEquals(26, acc.build(0L).single().caloriesKcal)
    }
}
