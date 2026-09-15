package com.fitnessapp.summary

import com.fitnessapp.summary.googlefit.FitTakeoutParser.Stream
import com.fitnessapp.summary.googlefit.FitTakeoutReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The walk over the archive itself, against a real zip built to the shape of this account's
 * export: two folders, a `.tcx` alongside the JSON, per-device streams next to the merged
 * ones, and Cyrillic in the entry names.
 *
 * This is the half the parser tests cannot see. A reader built on one zip entry must not be
 * able to consume the next entry's bytes, a skipped file must leave the archive positioned
 * correctly, and the `Data Source` line must be found before the points begin - all of which
 * fail silently as "the archive had no data".
 */
class FitTakeoutReaderTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    private fun nanosAt(date: String, hour: Int): Long =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.of(hour, 0), zone).toInstant().toEpochMilli() * 1_000_000L

    /** One file exactly as the archive writes it: pretty-printed, one point per line. */
    private fun dump(source: String, points: List<Pair<String, Long>>): String = buildString {
        append("{\n")
        append("    \"Data Source\": \"$source\",\n")
        append("    \"Data Points\": [\n")
        points.forEachIndexed { i, (value, nanos) ->
            append(
                "        {\"fitValue\":[{\"value\":{$value}}],\"originDataSourceId\":\"raw:x\"," +
                    "\"endTimeNanos\":$nanos,\"dataTypeName\":\"t\",\"startTimeNanos\":$nanos," +
                    "\"modifiedTimeMillis\":1761822679552,\"rawTimestampNanos\":0}"
            )
            if (i != points.lastIndex) append(",")
            append("\n")
        }
        append("    ]\n}\n")
    }

    private fun archive(files: Map<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun read(files: Map<String, String>) =
        FitTakeoutReader.read(ByteArrayInputStream(archive(files)), zone)

    @Test
    fun `merged streams are read and per-device ones are not`() {
        val result = read(
            mapOf(
                "Takeout/Fit/Все данные/derived_step_estimated.json" to dump(
                    "derived:com.google.step_count.delta:com.google.android.gms:estimated_steps",
                    listOf("\"intVal\":4000" to nanosAt("2016-05-10", 9), "\"intVal\":1500" to nanosAt("2016-05-10", 18))
                ),
                // The same walk again, per phone. Reading it would double the day.
                "Takeout/Fit/Все данные/derived_step_huawei.json" to dump(
                    "derived:com.google.step_count.delta:com.google.android.gms:HUAWEI:JSN-L21:hash:" +
                        "derive_step_deltas\\u003c-raw:com.google.step_count.cumulative:HUAWEI:JSN-L21:hash:step counter",
                    listOf("\"intVal\":4000" to nanosAt("2016-05-10", 9))
                ),
                "Takeout/Fit/Все данные/raw_step_miband.json" to dump(
                    "raw:com.google.step_count.delta:com.mc.miband1:Mi Band Notify steps",
                    listOf("\"intVal\":4000" to nanosAt("2016-05-10", 9))
                ),
                "Takeout/Fit/Тренировки/2015-06-23T17_04_34+03_00_PT1H25M28S_Бег.tcx" to "<TrainingCenterDatabase/>",
                "Takeout/archive_browser.html" to "<html></html>"
            )
        )

        val ok = result as FitTakeoutReader.Result.Ok
        assertEquals(1, ok.outcome.filesRead)
        assertEquals(5500L, ok.outcome.days.single().steps)
        assertEquals(2, ok.outcome.pointsByStream[Stream.STEPS_ESTIMATED])
    }

    /**
     * Four streams in a row, with skipped files in between: if a reader made for one entry
     * could buffer past its end, or a skip left the archive mispositioned, the later files
     * would come back empty and look exactly like an archive that never had them.
     */
    @Test
    fun `every stream after a skipped file is still read in full`() {
        val result = read(
            mapOf(
                "Takeout/Fit/Все данные/a_skip.json" to dump(
                    "derived:com.google.location.sample:com.google.android.gms:merge_high_fidelity",
                    List(200) { "\"fpVal\":55.5" to nanosAt("2016-05-10", 9) }
                ),
                "Takeout/Fit/Все данные/b_steps.json" to dump(
                    "derived:com.google.step_count.delta:com.google.android.gms:estimated_steps",
                    listOf("\"intVal\":4000" to nanosAt("2016-05-10", 9))
                ),
                "Takeout/Fit/Все данные/c_skip.json" to dump(
                    "derived:com.google.activity.segment:com.google.android.gms:merge_activity_segments",
                    List(200) { "\"intVal\":7" to nanosAt("2016-05-10", 9) }
                ),
                "Takeout/Fit/Все данные/d_calories.json" to dump(
                    "derived:com.google.calories.expended:com.google.android.gms:merge_calories_expended",
                    listOf("\"fpVal\":1200.5" to nanosAt("2016-05-10", 9))
                ),
                "Takeout/Fit/Все данные/e_resting.json" to dump(
                    "derived:com.google.heart_rate.bpm:com.google.android.gms:resting_heart_rate\\u003c-merge_heart_rate_bpm",
                    listOf("\"fpVal\":57" to nanosAt("2016-05-10", 9))
                ),
                "Takeout/Fit/Все данные/f_weight.json" to dump(
                    "derived:com.google.weight:com.google.android.gms:merge_weight",
                    listOf("\"fpVal\":78.4" to nanosAt("2016-05-10", 21))
                )
            )
        )

        val ok = result as FitTakeoutReader.Result.Ok
        assertEquals(4, ok.outcome.filesRead)
        val day = ok.outcome.days.single()
        assertEquals(4000L, day.steps)
        assertEquals(1201, day.caloriesKcal)
        assertEquals(57, day.restingHeartRate)
        assertEquals(78400, day.weightGrams)
    }

    /** An unrecognised cross-device merge has to be named, not silently equal to "absent". */
    @Test
    fun `an unknown merged stream is reported`() {
        val ok = read(
            mapOf(
                "Takeout/Fit/Все данные/steps.json" to dump(
                    "derived:com.google.step_count.delta:com.google.android.gms:estimated_steps",
                    listOf("\"intVal\":4000" to nanosAt("2016-05-10", 9))
                ),
                "Takeout/Fit/Все данные/hydration.json" to dump(
                    "derived:com.google.hydration:com.google.android.gms:merge_hydration",
                    listOf("\"fpVal\":0.5" to nanosAt("2016-05-10", 9))
                )
            )
        ) as FitTakeoutReader.Result.Ok

        assertEquals(
            listOf("derived:com.google.hydration:com.google.android.gms:merge_hydration"),
            ok.outcome.unknownStreams
        )
    }

    /** The Takeout download arrives in parts, and one of them holds only the index page. */
    @Test
    fun `the part with no Fit data says so instead of reporting an empty import`() {
        val failed = read(mapOf("Takeout/archive_browser.html" to "<html></html>"))
        assertTrue(failed is FitTakeoutReader.Result.Failed)
        assertTrue((failed as FitTakeoutReader.Result.Failed).reason.contains("Fit"))
    }

    @Test
    fun `json without a single merged stream is a failure, not an empty success`() {
        val failed = read(
            mapOf(
                "Takeout/Fit/Все данные/raw.json" to dump(
                    "raw:com.google.step_count.delta:com.mc.miband1:Mi Band Notify steps",
                    listOf("\"intVal\":4000" to nanosAt("2016-05-10", 9))
                )
            )
        )
        assertTrue(failed is FitTakeoutReader.Result.Failed)
    }
}
