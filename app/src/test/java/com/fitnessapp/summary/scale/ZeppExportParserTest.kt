package com.fitnessapp.summary.scale

import com.fitnessapp.summary.data.ScaleMeasurement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The tricky parts of a real Zepp export, as a fixture: the UTF-8 BOM, the literal
 * `null` in the body-composition columns of a weigh-in whose impedance failed, the
 * `+0000` offset, and `muscleRate` being kilograms rather than a percentage.
 *
 * The numbers are shaped like the real file this was written against but are not the
 * user's own - personal weight history has no business in a repository.
 */
class ZeppExportParserTest {

    private val utc = ZoneId.of("UTC")

    private val csv = """
        ${'﻿'}time,weight,height,bmi,fatRate,bodyWaterRate,boneMass,metabolism,muscleRate,visceralFat
        2021-02-05 00:30:20+0000,77.1,182.0,23.2,20.7,54.3,3.1,1644.0,57.9,9.0
        2022-08-09 16:51:06+0000,80.0,182.0,24.2,null,null,null,null,null,null
        2026-09-05 01:43:58+0000,78.1,182.0,23.5,21.361177,53.94623,3.1289797,1605.0,58.287937,10.0
        ,,,,,,,,,
        2026-09-05 01:43:58+0000,78.4,182.0,23.7,21.4,53.9,3.13,1600.0,58.3,10.0
    """.trimIndent()

    private fun parse(text: String = csv) =
        ZeppExportParser.parse(text.byteInputStream(), zone = utc) as ZeppExportParser.Result.Ok

    @Test
    fun `reads a weigh-in with every column present`() {
        val first = parse().measurements.first()
        assertEquals(77_100, first.weightGrams)
        assertEquals(182f, first.heightCm)
        assertEquals(23.2f, first.bmi)
        assertEquals(20.7f, first.bodyFatPercent)
        assertEquals(54.3f, first.bodyWaterPercent)
        assertEquals(3_100, first.boneMassGrams)
        assertEquals(1644, first.basalMetabolismKcal)
        // muscleRate is mass in kg, not a percentage - 57.9 kg for a 77.1 kg person.
        assertEquals(57_900, first.muscleMassGrams)
        assertEquals(9, first.visceralFat)
        assertEquals(ScaleMeasurement.SOURCE_ZEPP_FILE, first.source)
    }

    @Test
    fun `time is read as a real UTC instant`() {
        val first = parse().measurements.first()
        assertEquals(1612485020_000L, first.timestampMillis)
        assertEquals(18_663L, first.dateEpochDay) // 2021-02-05
    }

    @Test
    fun `a failed impedance reading keeps weight and leaves the rest unknown`() {
        val row = parse().measurements[1]
        assertEquals(80_000, row.weightGrams)
        assertEquals(0f, row.bodyFatPercent)
        assertEquals(0, row.muscleMassGrams)
        assertEquals(0, row.basalMetabolismKcal)
    }

    @Test
    fun `blank rows are skipped and a repeated instant collapses to one row`() {
        val result = parse()
        assertEquals(3, result.measurements.size)
        assertTrue(result.skippedRows >= 1)
        // Last one wins for a repeated timestamp.
        assertEquals(78_400, result.measurements.last().weightGrams)
    }

    @Test
    fun `columns are matched by name, not position`() {
        val reordered = """
            weight,time,visceralFat
            77.1,2021-02-05 00:30:20+0000,9
        """.trimIndent()
        val row = parse(reordered).measurements.single()
        assertEquals(77_100, row.weightGrams)
        assertEquals(9, row.visceralFat)
        assertEquals(1612485020_000L, row.timestampMillis)
    }

    @Test
    fun `bmi is derived when the export omits it`() {
        val noBmi = """
            time,weight,height
            2021-02-05 00:30:20+0000,80.0,200.0
        """.trimIndent()
        assertEquals(20f, parse(noBmi).measurements.single().bmi)
    }

    @Test
    fun `a file without the weight column is rejected, not silently empty`() {
        val wrong = """
            date,steps,distance,calories
            2023-11-16,13807,9954,362
        """.trimIndent()
        val result = ZeppExportParser.parse(wrong.byteInputStream(), zone = utc)
        assertTrue(result is ZeppExportParser.Result.Failed)
    }
}
