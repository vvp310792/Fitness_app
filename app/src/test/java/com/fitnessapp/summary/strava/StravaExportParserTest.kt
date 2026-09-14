package com.fitnessapp.summary.strava

import com.fitnessapp.summary.analytics.DistanceSport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * The six traps in a real Strava export, on a synthetic file that reproduces each one.
 *
 * Synthetic on purpose: the user's own export carries years of activity names and times,
 * and none of that belongs in a repository. Every quirk below was measured on the real
 * file first and then written into the fixture.
 */
class StravaExportParserTest {

    /**
     * Header shaped like the real thing: localised, with `Общее время` and `Макс. пульс`
     * appearing twice, `Расстояние` in kilometres beside `Дистанция` in metres.
     */
    private val HEADER = listOf(
        "ID физической активности",   // 0
        "Дата тренировки",            // 1
        "Название тренировки",        // 2
        "Тип активности",             // 3
        "Общее время",                // 4  <- first copy
        "Расстояние",                 // 5  <- kilometres, comma decimal
        "Макс. пульс",                // 6  <- first copy
        "Общее время",                // 7  <- second copy
        "Время в движении",           // 8
        "Дистанция",                  // 9  <- metres
        "Набор высоты",               // 10
        "Макс. пульс",                // 11 <- second copy
        "Средний пульс",              // 12
        "Калории"                     // 13
    ).joinToString(",")

    private fun file(vararg rows: String) = (listOf(HEADER) + rows).joinToString("\n")

    /** U+202F, the narrow no-break space the real export puts before "г." */
    private val NNBSP = ' '

    private fun row(
        id: String = "1",
        date: String = "14 сент. 2026${NNBSP}г., 02:23:39",
        name: String = "Утро",
        type: String = "Бег",
        elapsed1: String = "3600",
        km: String = "10,50",
        hr1: String = "170",
        elapsed2: String = "3600.0",
        moving: String = "3300.0",
        meters: String = "10500.0",
        elevation: String = "42.0",
        hr2: String = "170.0",
        hrAvg: String = "150.0",
        calories: String = "700.0"
    ) = listOf(
        id,
        // Quoted, exactly as the export writes them: the date holds a comma ("...2026 г.,
        // 02:23:39") and the kilometre column uses a decimal comma. Unquoted, both split
        // into two fields and shift every column after them - which is why the file needs
        // a real CSV reader and not a split on ','.
        "\"$date\"",
        if (name.startsWith("\"")) name else "\"$name\"",
        type, elapsed1, "\"$km\"", hr1, elapsed2, moving, meters, elevation, hr2, hrAvg, calories
    ).joinToString(",")

    private fun parse(text: String) = StravaExportParser.parse(text, ZoneOffset.UTC)

    @Test
    fun `reads a row through the localised header`() {
        val ok = parse(file(row())) as StravaExportParser.Result.Ok
        val a = ok.activities.single()
        assertEquals(1L, a.activityId)
        assertEquals("Бег", a.typeRaw)
        assertEquals("Утро", a.name)
        // Moving time, not elapsed: 3300 s, the number the training is measured in.
        assertEquals(3300, a.durationSeconds)
        assertEquals(10500, a.distanceMeters)
        assertEquals(150, a.avgHeartRate)
        assertEquals(170, a.maxHeartRate)
        assertEquals(42, a.elevationGainMeters)
        assertEquals(700, a.calories)
    }

    @Test
    fun `the date is read as UTC, narrow no-break space and all`() {
        // Verified against this account's Garmin activities: 1236 of 3028 rows match by
        // start time at offset zero, and at most 3 at any other whole-hour offset.
        val ok = parse(file(row())) as StravaExportParser.Result.Ok
        assertEquals(
            java.time.Instant.parse("2026-09-14T02:23:39Z").toEpochMilli(),
            ok.activities.single().startTimeMillis
        )
    }

    @Test
    fun `every month spelling in the export parses, including the dotless мая`() {
        val dates = listOf(
            "1 янв. 2020${NNBSP}г., 00:00:00", "2 февр. 2020${NNBSP}г., 01:00:00",
            "3 мар. 2020${NNBSP}г., 02:00:00", "4 апр. 2020${NNBSP}г., 03:00:00",
            "5 мая 2020${NNBSP}г., 04:00:00", "6 июн. 2020${NNBSP}г., 05:00:00",
            "7 июл. 2020${NNBSP}г., 06:00:00", "8 авг. 2020${NNBSP}г., 07:00:00",
            "9 сент. 2020${NNBSP}г., 08:00:00", "10 окт. 2020${NNBSP}г., 09:00:00",
            "11 нояб. 2020${NNBSP}г., 10:00:00", "12 дек. 2020${NNBSP}г., 11:00:00"
        )
        val rows = dates.mapIndexed { i, d -> row(id = (i + 1).toString(), date = d) }
        val ok = parse(file(*rows.toTypedArray())) as StravaExportParser.Result.Ok
        assertEquals(12, ok.activities.size)
        assertEquals(0, ok.skippedRows)
        assertEquals(
            (1..12).toList(),
            ok.activities.map { java.time.Instant.ofEpochMilli(it.startTimeMillis).atZone(ZoneOffset.UTC).monthValue }
                .sorted()
        )
    }

    @Test
    fun `metres win over the kilometre column, which uses a decimal comma`() {
        // Reading «Расстояние» as metres would store 10 instead of 10500 - the failure is
        // silent, the chart just draws a kilometre where ten belong.
        val ok = parse(file(row(km = "10,50", meters = "10500.0"))) as StravaExportParser.Result.Ok
        assertEquals(10500, ok.activities.single().distanceMeters)
    }

    @Test
    fun `the kilometre column is used when metres are missing, comma and all`() {
        val ok = parse(file(row(km = "2,31", meters = ""))) as StravaExportParser.Result.Ok
        assertEquals(2310, ok.activities.single().distanceMeters)
    }

    @Test
    fun `a duplicated header name falls through to the copy that is filled`() {
        // Keeping "the last column with this name" - what a name-to-index map does - loses
        // the value whenever only the other copy is populated.
        val ok = parse(file(row(hr1 = "182", hr2 = ""))) as StravaExportParser.Result.Ok
        assertEquals(182, ok.activities.single().maxHeartRate)

        val other = parse(file(row(hr1 = "", hr2 = "182.0"))) as StravaExportParser.Result.Ok
        assertEquals(182, other.activities.single().maxHeartRate)
    }

    @Test
    fun `elapsed time stands in when moving time is absent`() {
        val ok = parse(file(row(moving = "", elapsed1 = "1800", elapsed2 = ""))) as StravaExportParser.Result.Ok
        assertEquals(1800, ok.activities.single().durationSeconds)
    }

    @Test
    fun `placeholder rows are dropped, not drawn as a decade of empty weeks`() {
        val ok = parse(
            file(
                row(id = "1"),
                row(id = "2", date = "1 янв. 1970${NNBSP}г., 00:00:01"),
                row(id = "3", date = "")
            )
        ) as StravaExportParser.Result.Ok
        assertEquals(1, ok.activities.size)
        assertEquals(2, ok.skippedRows)
    }

    @Test
    fun `a comma inside an activity name does not shift the columns`() {
        val ok = parse(file(row(name = "Утро, наконец-то"))) as StravaExportParser.Result.Ok
        val a = ok.activities.single()
        assertEquals("Утро, наконец-то", a.name)
        // The proof that nothing shifted: the fields after the name are still themselves.
        assertEquals("Бег", a.typeRaw)
        assertEquals(10500, a.distanceMeters)
    }

    @Test
    fun `a BOM does not stop the first header from matching`() {
        val ok = parse("﻿" + file(row())) as StravaExportParser.Result.Ok
        assertEquals(1L, ok.activities.single().activityId)
    }

    @Test
    fun `a file that is not this export fails with a reason instead of coming back empty`() {
        val failed = parse("a,b,c\n1,2,3") as StravaExportParser.Result.Failed
        assertTrue(failed.reason.contains("Strava"))
    }

    @Test
    fun `sports map the way the charts will read them`() {
        assertEquals(DistanceSport.RUN, DistanceSport.ofStrava("Бег"))
        assertEquals(DistanceSport.BIKE, DistanceSport.ofStrava("Велосипед"))
        assertEquals(DistanceSport.SWIM, DistanceSport.ofStrava("Плавание"))
        // The English spelling of the same export, for a differently localised archive.
        assertEquals(DistanceSport.RUN, DistanceSport.ofStrava("Trail Run"))
        assertEquals(DistanceSport.BIKE, DistanceSport.ofStrava("Ride"))
        assertEquals(DistanceSport.SWIM, DistanceSport.ofStrava("Swim"))
        // The nine sports in this account's file that must land nowhere - walking and
        // hiking above all, which would otherwise inflate a running week with commuting.
        for (sport in listOf(
            "Ходьба", "Хайкинг", "Силовая тренировка", "Гребля", "Тренировка",
            "Лыжи", "Эллиптический тренажер", "Парусный спорт", "Кроссфит"
        )) {
            assertNull("«$sport» не должен попадать в объём", DistanceSport.ofStrava(sport))
        }
        assertNull(DistanceSport.ofStrava("Мотоцикл"))
        assertNull(DistanceSport.ofStrava("E-Bike Ride"))
    }

    @Test
    fun `counts the sports it saw, spelled as the file spells them`() {
        val ok = parse(
            file(
                row(id = "1", type = "Бег"),
                row(id = "2", type = "Бег", date = "13 сент. 2026${NNBSP}г., 02:23:39"),
                row(id = "3", type = "Ходьба", date = "12 сент. 2026${NNBSP}г., 02:23:39")
            )
        ) as StravaExportParser.Result.Ok
        assertEquals(mapOf("Бег" to 2, "Ходьба" to 1), ok.sports)
    }
}
