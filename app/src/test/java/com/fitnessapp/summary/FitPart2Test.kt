package com.fitnessapp.summary

import com.fitnessapp.summary.data.FitDay
import com.fitnessapp.summary.data.FitDayDao
import com.fitnessapp.summary.googlefit.FitDailyCsvParser
import com.fitnessapp.summary.googlefit.FitSleepSessionParser
import com.fitnessapp.summary.googlefit.FitTakeoutParser
import com.fitnessapp.summary.googlefit.FitTakeoutReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The second part of the Takeout download: Google's own daily table and the sleep sessions.
 *
 * Written against this account's real archive (2026-09-14) - the header below is the real one,
 * repeated column names and all.
 */
class FitPart2Test {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    /** The real header, verbatim: 34 columns, two of the names appearing twice. */
    private val HEADER = "Дата,Минуты активности,Калории (ккал),Дистанция (м),Баллы кардиотренировок," +
        "Минуты кардиотренировок,Средний пульс (уд/мин),Макс. пульс (уд/мин),Мин. пульс (уд/мин)," +
        "Мин. широта (град.),Мин. долгота (град.),Макс. широта (град.),Макс. долгота (град.)," +
        "Средняя скорость (м/с),Макс. скорость (м/с),Мин. скорость (м/с),Число шагов," +
        "Средний вес (кг),Макс. вес (кг),Мин. вес (кг),Продолжительность: Велосипед (мс)," +
        "Продолжительность: Отдых (мс),Продолжительность: Ходьба (мс),Продолжительность: Бег (мс)," +
        "Продолжительность: Инерционный велотренажер (мс),Продолжительность: Медитация (мс)," +
        "Продолжительность: Бег (мс),Продолжительность: Беговая дорожка (мс)," +
        "Продолжительность: Силовой тренинг (мс),Продолжительность: Плавание (мс)," +
        "Продолжительность: Плавание в бассейне (мс),Продолжительность: Беговая дорожка (мс)," +
        "Продолжительность: Тяжелая атлетика (мс),Продолжительность: Йога (мс)"

    private fun row(
        date: String, calories: String = "", distance: String = "", steps: String = "",
        hrAvg: String = "", hrMax: String = "", hrMin: String = "", weight: String = "", rest: String = ""
    ): String {
        val f = MutableList(34) { "" }
        f[0] = date; f[2] = calories; f[3] = distance
        f[6] = hrAvg; f[7] = hrMax; f[8] = hrMin
        f[16] = steps; f[17] = weight; f[21] = rest
        return f.joinToString(",")
    }

    private fun csv(vararg rows: String) = (listOf(HEADER) + rows).joinToString("\n")

    // ---- daily CSV ---------------------------------------------------------------------------

    @Test
    fun `the localised header is read and the day lands where it should`() {
        val result = FitDailyCsvParser.parse(
            csv(row("2019-05-02", calories = "2510.5", distance = "8123.75", steps = "11024", hrAvg = "71", hrMax = "158", hrMin = "52")),
            nowMillis = 0L
        )
        val day = result.days.single()
        assertEquals(LocalDate.parse("2019-05-02").toEpochDay(), day.dateEpochDay)
        assertEquals(11024L, day.steps)
        assertEquals(2511, day.caloriesKcal)
        assertEquals(8124, day.distanceMeters)
        assertEquals(71, day.avgHeartRate)
        assertEquals(158, day.maxHeartRate)
        assertEquals(52, day.minHeartRate)
        assertTrue(result.missingColumns.isEmpty())
    }

    /**
     * The decimal separator here is a dot and the field separator a comma - the opposite of the
     * Strava export, where the number itself carried the comma. Three imported formats in this
     * project, three conventions.
     */
    @Test
    fun `a dot is the decimal separator, not a column break`() {
        val day = FitDailyCsvParser.parse(csv(row("2019-05-02", calories = "291.5733961111111", steps = "10")), 0L).days.single()
        assertEquals(292, day.caloriesKcal)
        assertEquals(10L, day.steps)
    }

    /**
     * The exact trap that once cost the app months of missing rides, in a new file: a name-to-
     * index map keeps only the last of a repeated column.
     */
    @Test
    fun `a repeated column name does not shadow the one that is read`() {
        val header = FitDailyCsvParser.readCsv(csv()).single()
        assertEquals(34, header.size)
        assertEquals(2, header.count { it == "Продолжительность: Бег (мс)" })
        assertEquals(2, header.count { it == "Продолжительность: Беговая дорожка (мс)" })
        // Steps still read correctly despite the duplicates sitting after them.
        assertEquals(7000L, FitDailyCsvParser.parse(csv(row("2020-01-01", steps = "7000")), 0L).days.single().steps)
    }

    /**
     * 469 rows out of 2744 on this account had nothing but a calorie figure, including a fake
     * "history starts in 2008". Google derives the number from the profile; the archive even
     * ships the basal stream it uses.
     */
    @Test
    fun `a row with only calories is not a day`() {
        val result = FitDailyCsvParser.parse(
            csv(
                row("2008-01-11", calories = "877", rest = "14378962"),
                row("2026-04-01", calories = "1801"),
                row("2020-01-01", calories = "2200", steps = "9000")
            ),
            0L
        )
        assertEquals(1, result.days.size)
        assertEquals(LocalDate.parse("2020-01-01").toEpochDay(), result.days.single().dateEpochDay)
        assertEquals(2, result.skippedRows)
    }

    @Test
    fun `an unrecognised header is named rather than silently read as empty`() {
        val english = "Date,Calories (kcal),Step count\n2019-05-02,2510,11024"
        val result = FitDailyCsvParser.parse(english, 0L)
        assertTrue(result.days.isEmpty())
        assertTrue(result.missingColumns.isNotEmpty())
    }

    @Test
    fun `an implausible weight or heart rate reads as no data`() {
        val day = FitDailyCsvParser.parse(
            csv(row("2020-01-01", steps = "9000", weight = "0.0", hrAvg = "0")),
            0L
        ).days.single()
        assertEquals(0, day.weightGrams)
        assertEquals(0, day.avgHeartRate)
    }

    // ---- sleep sessions ----------------------------------------------------------------------

    private val NIGHT = """
        { "fitnessActivity": "sleep",
          "startTime": "2019-06-10T20:06:00Z",
          "endTime": "2019-06-11T04:48:00Z",
          "duration": "31320s",
          "segment": [
            {"fitnessActivity":"sleep","startTime":"2019-06-10T20:06:00Z","endTime":"2019-06-11T00:06:00Z"},
            {"fitnessActivity":"sleep.awake","startTime":"2019-06-11T00:06:00Z","endTime":"2019-06-11T00:36:00Z"},
            {"fitnessActivity":"sleep","startTime":"2019-06-11T00:36:00Z","endTime":"2019-06-11T04:48:00Z"}
          ] }
    """.trimIndent()

    @Test
    fun `a night is the sum of its sleep segments, not its span`() {
        val night = FitSleepSessionParser.parse(NIGHT, zone)!!
        // 4h + 4h12m slept, 30 min awake - the top-level duration would have counted 8h42m.
        assertEquals(240 + 252, night.sleepMinutes)
        assertEquals(30, night.awakeMinutes)
    }

    /** The morning it ended on, as everywhere else in this app and as Garmin counts it. */
    @Test
    fun `a night belongs to the morning it ended on`() {
        val night = FitSleepSessionParser.parse(NIGHT, zone)!!
        assertEquals(LocalDate.parse("2019-06-11").toEpochDay(), night.dateEpochDay)
    }

    /** The smallest file in the real archive is 900 seconds with no segments at all. */
    @Test
    fun `a fifteen-minute nap is not a night`() {
        val nap = """
            { "fitnessActivity": "sleep", "startTime": "2019-05-07T17:06:00Z",
              "endTime": "2019-05-07T17:21:00Z", "duration": "900s",
              "aggregate": [{"metricName":"com.google.calories.expended","floatValue":17.5}] }
        """.trimIndent()
        assertNull(FitSleepSessionParser.parse(nap, zone))
    }

    @Test
    fun `a session with no segments still counts as a night when it is long enough`() {
        val plain = """
            { "fitnessActivity": "sleep", "startTime": "2019-05-07T20:00:00Z",
              "endTime": "2019-05-08T04:00:00Z", "duration": "28800s" }
        """.trimIndent()
        val night = FitSleepSessionParser.parse(plain, zone)!!
        assertEquals(480, night.sleepMinutes)
        assertEquals(0, night.awakeMinutes)
    }

    @Test
    fun `a workout session is not a night`() {
        val biking = """
            { "fitnessActivity": "biking", "startTime": "2019-05-11T08:44:00.276Z",
              "endTime": "2019-05-11T09:13:11.976Z", "duration": "1751.700s" }
        """.trimIndent()
        assertNull(FitSleepSessionParser.parse(biking, zone))
    }

    // ---- file classification -------------------------------------------------------------------

    @Test
    fun `the one summary table is told apart from the 2920 per-day files`() {
        assertTrue(FitTakeoutReader.isDailySummaryCsv("Показатели ежедневной активности.csv"))
        assertTrue(!FitTakeoutReader.isDailySummaryCsv("2019-05-02.csv"))
        assertTrue(!FitTakeoutReader.isDailySummaryCsv("2026-09-13.csv"))
    }

    /** Takeout exports eight of the sleep files a second time under a `(1)` suffix. */
    @Test
    fun `sleep sessions are recognised, duplicates included`() {
        assertTrue(FitTakeoutReader.isSleepSession("2026-09-09T20_06_00+03_00_SLEEP.json"))
        assertTrue(FitTakeoutReader.isSleepSession("2019-05-07T20_06_00+03_00_SLEEP(1).json"))
        assertTrue(!FitTakeoutReader.isSleepSession("2019-05-11T11_44_00.276+03_00_BIKING.json"))
        assertTrue(!FitTakeoutReader.isSleepSession("derived_com.google.weight_com.google.android.gms_merge_weight.json"))
    }

    @Test
    fun `the basal calorie stream is recognised`() {
        assertEquals(
            FitTakeoutParser.Stream.CALORIES_BMR,
            FitTakeoutParser.classify("derived:com.google.calories.bmr:com.google.android.gms:merged")
        )
    }

    @Test
    fun `active calories are a subtraction of two measured numbers, or nothing`() {
        assertEquals(1100, FitDay(dateEpochDay = 1, caloriesKcal = 2800, caloriesBmrKcal = 1700).activeCaloriesKcal)
        // No basal figure - the active line simply has no point that day.
        assertEquals(0, FitDay(dateEpochDay = 1, caloriesKcal = 2800).activeCaloriesKcal)
        // Streams disagreeing is not a restful day.
        assertEquals(0, FitDay(dateEpochDay = 1, caloriesKcal = 1500, caloriesBmrKcal = 1700).activeCaloriesKcal)
    }

    // ---- combine and merge ---------------------------------------------------------------------

    @Test
    fun `the CSV wins over the streams, but keeps what only they have`() {
        val day = 18_000L
        val combined = FitTakeoutParser.combine(
            streamDays = listOf(
                FitDay(dateEpochDay = day, steps = 9500, restingHeartRate = 57, caloriesBmrKcal = 1700, distanceMeters = 6000)
            ),
            csvDays = listOf(FitDay(dateEpochDay = day, steps = 11024, caloriesKcal = 2800)),
            nights = emptyList(),
            nowMillis = 0L
        ).single()
        assertEquals(11024L, combined.steps)
        assertEquals(2800, combined.caloriesKcal)
        // Neither of these has a column in the CSV.
        assertEquals(57, combined.restingHeartRate)
        assertEquals(1700, combined.caloriesBmrKcal)
        // Silent in the CSV, so the stream still fills it.
        assertEquals(6000, combined.distanceMeters)
    }

    @Test
    fun `a session night replaces whatever the sleep stream produced`() {
        val day = 18_000L
        val combined = FitTakeoutParser.combine(
            streamDays = listOf(FitDay(dateEpochDay = day, steps = 100, sleepTotalMinutes = 60, sleepDeepMinutes = 20)),
            csvDays = emptyList(),
            nights = listOf(FitSleepSessionParser.Night(day, 0L, sleepMinutes = 430, awakeMinutes = 25)),
            nowMillis = 0L
        ).single()
        assertEquals(430, combined.sleepTotalMinutes)
        assertEquals(25, combined.sleepAwakeMinutes)
        // Fit has no stages at all - a leftover 20 minutes of "deep" would be someone else's.
        assertEquals(0, combined.sleepDeepMinutes)
    }

    @Test
    fun `two exports of the same night do not add up`() {
        val day = 18_000L
        val combined = FitTakeoutParser.combine(
            streamDays = emptyList(),
            csvDays = emptyList(),
            nights = listOf(
                FitSleepSessionParser.Night(day, 0L, 430, 25),
                FitSleepSessionParser.Night(day, 0L, 430, 25)
            ),
            nowMillis = 0L
        ).single()
        assertEquals(430, combined.sleepTotalMinutes)
    }

    /**
     * The download is split, and the parts carry different things. A plain upsert of the second
     * one would blank what the first brought - the same shape as an `activitylist` row erasing
     * a Garmin activity's Training Effect.
     */
    @Test
    fun `importing the second part does not blank the first`() {
        val fromPart1 = FitDay(dateEpochDay = 18_000L, steps = 9500, restingHeartRate = 57, caloriesBmrKcal = 1700)
        val fromPart2 = FitDay(dateEpochDay = 18_000L, steps = 11024, caloriesKcal = 2800, sleepTotalMinutes = 430)
        val merged = FitTakeoutParser.mergeOnto(fromPart1, fromPart2)
        assertEquals(11024L, merged.steps)
        assertEquals(2800, merged.caloriesKcal)
        assertEquals(57, merged.restingHeartRate)
        assertEquals(1700, merged.caloriesBmrKcal)
        assertEquals(430, merged.sleepTotalMinutes)

        // ...and the other way round, part one arriving second.
        val other = FitTakeoutParser.mergeOnto(fromPart2, fromPart1)
        assertEquals(2800, other.caloriesKcal)
        assertEquals(430, other.sleepTotalMinutes)
        assertEquals(57, other.restingHeartRate)
    }

    // ---- the delete rule, pinned -----------------------------------------------------------------

    /**
     * Two copies of a rule that DELETES rows drift apart at the first edit, so the SQL is held
     * against the property. Exactly the arrangement `FabricatedDayTest` makes for Health Connect
     * - whose lesson this import failed to carry over the first time.
     */
    @Test
    fun `the SQL delete rule matches isEmpty field for field`() {
        val sql = FitDayDao.FABRICATED_WHERE_DELETE
        listOf("steps", "distanceMeters", "avgHeartRate", "restingHeartRate", "weightGrams", "sleepTotalMinutes")
            .forEach { assertTrue("$it must be in the delete rule", sql.contains(it)) }
        assertTrue("calories must NOT decide whether a day existed", !sql.contains("calories", ignoreCase = true))
    }

    @Test
    fun `a day with only calories is empty and one with a measurement is not`() {
        assertTrue(FitDay(dateEpochDay = 1, caloriesKcal = 877).isEmpty)
        assertTrue(FitDay(dateEpochDay = 1, caloriesKcal = 877, caloriesBmrKcal = 800).isEmpty)
        assertTrue(!FitDay(dateEpochDay = 1, steps = 10).isEmpty)
        assertTrue(!FitDay(dateEpochDay = 1, restingHeartRate = 57).isEmpty)
        assertTrue(!FitDay(dateEpochDay = 1, sleepTotalMinutes = 430).isEmpty)
    }
}
