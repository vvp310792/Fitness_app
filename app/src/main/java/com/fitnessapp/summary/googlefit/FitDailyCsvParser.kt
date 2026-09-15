package com.fitnessapp.summary.googlefit

import com.fitnessapp.summary.data.FitDay
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * `Показатели ежедневной активности.csv` - Google Fit's own daily summary, one row per day.
 *
 * The better source for everything it carries: these are the numbers Fit itself displayed,
 * not this app's re-aggregation of its raw streams. Same principle as reading Garmin's
 * `heartRateZones` instead of modelling zones from a guessed HRmax - if the source already
 * computed the answer, computing it again is at best a copy.
 *
 * **Measured on this account's real archive** (2026-09-14), not taken from documentation:
 *
 * - 2890 rows, one per calendar day, in the SECOND part of the Takeout download. The first
 *   part has no such file, which is why the stream aggregation stays as the fallback;
 * - headers are **localised**: `Дата`, `Число шагов`, `Калории (ккал)`. A parser written to
 *   Google's English column names finds nothing and reports an empty file - exactly what the
 *   Strava export did;
 * - the decimal separator here is a **dot** (`291.5733961111111`) and the field separator a
 *   comma. That is the opposite of the Strava export, where the number itself held a comma.
 *   Three imported formats in this project, three different conventions - which is why each
 *   one is checked against the real file rather than assumed from the last;
 * - **column names repeat**: `Продолжительность: Бег (мс)` sits at positions 24 and 27,
 *   `Продолжительность: Беговая дорожка (мс)` at 28 and 32. A name-to-index map silently
 *   keeps the last. None of the repeated ones is read here, but the lookup is a name-to-LIST
 *   anyway: the first version of the Strava parser was correct too, right up until it wasn't;
 * - **there is no resting heart rate column.** `Мин. пульс` is not it - the minimum over a
 *   day comes from any quiet moment, which is the very artefact the Garmin resting-rate
 *   series already has to filter out. Resting rate comes from the stream, or not at all;
 * - missing cells are genuinely empty, not the literal `null` the Zepp export used.
 */
object FitDailyCsvParser {

    /**
     * Header names, as the Russian export writes them. Matched by prefix and case-insensitively
     * so that a unit suffix or a capitalisation change does not silently drop a column - an
     * unmatched name here reads exactly like a metric the archive never had.
     */
    private const val DATE = "дата"
    private const val STEPS = "число шагов"
    private const val CALORIES = "калории"
    private const val DISTANCE = "дистанция"
    private const val HR_AVG = "средний пульс"
    private const val HR_MAX = "макс. пульс"
    private const val HR_MIN = "мин. пульс"
    private const val WEIGHT_AVG = "средний вес"

    data class Result(
        val days: List<FitDay>,
        /** Rows dropped: no parseable date, or nothing measured in them. */
        val skippedRows: Int,
        /** Header names the parser needed and did not find - named on screen, never swallowed. */
        val missingColumns: List<String>
    )

    fun parse(text: String, nowMillis: Long): Result {
        val rows = readCsv(text)
        if (rows.isEmpty()) return Result(emptyList(), 0, listOf(DATE))

        val header = rows.first()
        val columns = HashMap<String, MutableList<Int>>()
        header.forEachIndexed { index, name ->
            columns.getOrPut(name.trim().lowercase().removePrefix("﻿")) { mutableListOf() }.add(index)
        }

        fun indexOf(prefix: String): Int? =
            columns.entries.firstOrNull { it.key.startsWith(prefix) }?.value?.firstOrNull()

        val dateAt = indexOf(DATE)
        val missing = buildList {
            if (dateAt == null) add(DATE)
            listOf(STEPS, CALORIES, DISTANCE).forEach { if (indexOf(it) == null) add(it) }
        }
        if (dateAt == null) return Result(emptyList(), rows.size - 1, missing)

        val stepsAt = indexOf(STEPS)
        val caloriesAt = indexOf(CALORIES)
        val distanceAt = indexOf(DISTANCE)
        val hrAvgAt = indexOf(HR_AVG)
        val hrMaxAt = indexOf(HR_MAX)
        val hrMinAt = indexOf(HR_MIN)
        val weightAt = indexOf(WEIGHT_AVG)

        var skipped = 0
        val days = ArrayList<FitDay>(rows.size)

        for (row in rows.drop(1)) {
            val date = row.getOrNull(dateAt)?.trim()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (date == null || date.year < FIRST_PLAUSIBLE_YEAR) { skipped++; continue }

            val hrAvg = row.int(hrAvgAt)
            val day = FitDay(
                dateEpochDay = date.toEpochDay(),
                steps = row.double(stepsAt).roundToInt().toLong(),
                caloriesKcal = row.double(caloriesAt).roundToInt(),
                distanceMeters = row.double(distanceAt).roundToInt(),
                avgHeartRate = plausibleHr(hrAvg),
                maxHeartRate = plausibleHr(row.int(hrMaxAt)),
                minHeartRate = plausibleHr(row.int(hrMinAt)),
                weightGrams = (row.double(weightAt) * 1000).roundToInt().takeIf {
                    it in PLAUSIBLE_WEIGHT_MIN_G..PLAUSIBLE_WEIGHT_MAX_G
                } ?: 0,
                updatedAtMillis = nowMillis
            )
            // A row whose only content is the calorie figure is not a day - see FitDay.isEmpty.
            if (day.isEmpty) skipped++ else days += day
        }
        return Result(days, skipped, missing)
    }

    private fun plausibleHr(value: Int): Int =
        if (value in PLAUSIBLE_HR_MIN..PLAUSIBLE_HR_MAX) value else 0

    private fun List<String>.double(at: Int?): Double {
        val raw = at?.let { getOrNull(it) }?.trim().orEmpty()
        if (raw.isEmpty()) return 0.0
        // A dot in this export; accepting a comma too costs nothing, and the Strava export
        // proved that the same account's exports do not agree on this.
        return raw.replace(',', '.').toDoubleOrNull() ?: 0.0
    }

    private fun List<String>.int(at: Int?): Int = double(at).roundToInt()

    /**
     * Minimal RFC 4180 reader - quoted fields, doubled quotes, CRLF.
     *
     * `split(',')` is not enough even here: a localised header or a future column may hold a
     * comma, and a shifted row is the kind of corruption nothing downstream can notice.
     */
    fun readCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        val body = text.removePrefix("﻿")

        fun endField() { row.add(field.toString()); field.setLength(0) }
        fun endRow() {
            endField()
            if (row.size > 1 || row.firstOrNull()?.isNotBlank() == true) rows.add(row)
            row = ArrayList()
        }

        while (i < body.length) {
            val c = body[i]
            when {
                quoted && c == '"' && i + 1 < body.length && body[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> endField()
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < body.length && body[i + 1] == '\n') i++
                    endRow()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    private const val FIRST_PLAUSIBLE_YEAR = 2008
    private const val PLAUSIBLE_HR_MIN = 25
    private const val PLAUSIBLE_HR_MAX = 230
    private const val PLAUSIBLE_WEIGHT_MIN_G = 30_000
    private const val PLAUSIBLE_WEIGHT_MAX_G = 300_000
}
