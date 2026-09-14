package com.fitnessapp.summary.strava

import com.fitnessapp.summary.data.StravaActivity
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Reads `activities.csv` out of a Strava bulk export.
 *
 * Pure Kotlin on purpose - no Android, no I/O - so the whole of it is reachable from a JVM
 * test. The Android half (picking the file, decoding it, de-duplicating against what is
 * already stored) lives in [StravaImportManager].
 *
 * **Written against the user's actual export, not against Strava's documentation.** Six
 * things in the real file break a parser built from the docs, and every one of them is
 * silent:
 *
 * 1. **The headers are localised.** This export says `Дата тренировки`, `Тип активности` -
 *    not `Activity Date` / `Activity Type`. A parser keyed on the English names matches
 *    nothing at all and reports an empty file.
 * 2. **Four header names repeat.** `Общее время` sits at columns 5 and 15, `Макс. пульс`
 *    at 7 and 30, `Относительное усилие` at 8 and 37, `На работу` at 9 and 50. Anything
 *    that builds a name→index map keeps whichever it saw last, silently - and for
 *    `На работу` the two columns do not even agree on a format (`false` vs `0.0`).
 *    Hence [valueOf]: every name maps to a LIST of columns, first non-blank wins.
 * 3. **`Расстояние` is kilometres, `Дистанция` is metres.** Two nearly identical names,
 *    different units - and the kilometre one uses a decimal COMMA (`2,31`), so reading it
 *    as metres yields 2 instead of 2310. Metres are preferred and the comma is handled.
 * 4. **Dates are Russian-locale and separated by U+202F**, a narrow no-break space:
 *    `14 сент. 2026 г., 02:23:39`. Not a normal space, so `\s` in a naive regex misses it.
 *    Months are abbreviated with a dot except `мая`, which is genitive and has none.
 * 5. **The date is UTC**, which is not stated anywhere in the file. Verified rather than
 *    assumed: matching all 3028 rows against this account's Garmin activities by start
 *    time gives 1236 hits at offset zero and at most 3 at any other whole-hour offset.
 * 6. **Junk rows.** Two activities dated 1 Jan 1970 and one dated 2010 - dropped by
 *    [PLAUSIBLE_YEARS] rather than being drawn as a decade of empty weeks.
 */
object StravaExportParser {

    sealed class Result {
        data class Ok(
            val activities: List<StravaActivity>,
            /** Rows thrown away: unparseable date, implausible year, missing id. */
            val skippedRows: Int,
            /** Sport names as the file spells them, with counts - shown on screen. */
            val sports: Map<String, Int>
        ) : Result()

        data class Failed(val reason: String) : Result()
    }

    /** Before this the rows in this export are placeholders, not training. */
    private val PLAUSIBLE_YEARS = 2000..2100

    private const val COL_DATE = "Дата тренировки"
    private const val COL_ID = "ID физической активности"
    private const val COL_NAME = "Название тренировки"
    private const val COL_TYPE = "Тип активности"
    private const val COL_MOVING = "Время в движении"
    private const val COL_ELAPSED = "Общее время"
    private const val COL_DISTANCE_M = "Дистанция"
    private const val COL_DISTANCE_KM = "Расстояние"
    private const val COL_CALORIES = "Калории"
    private const val COL_HR_AVG = "Средний пульс"
    private const val COL_HR_MAX = "Макс. пульс"
    private const val COL_ELEVATION = "Набор высоты"

    fun parse(text: String, zone: ZoneId): Result {
        val rows = readCsv(text)
        if (rows.isEmpty()) return Result.Failed("Файл пуст")

        val header = rows.first()
        // Name -> every column carrying it, because four of them carry it twice.
        val columns: Map<String, List<Int>> = header.withIndex()
            .groupBy({ it.value.trim() }, { it.index })

        if (COL_DATE !in columns || COL_TYPE !in columns) {
            return Result.Failed(
                "Не похоже на activities.csv из Strava: нет колонок «$COL_DATE» и «$COL_TYPE». " +
                    "Выгрузка локализована — если она не на русском, колонки называются иначе."
            )
        }

        val out = mutableListOf<StravaActivity>()
        val sports = mutableMapOf<String, Int>()
        var skipped = 0

        for (row in rows.drop(1)) {
            fun value(name: String) = valueOf(row, columns[name])

            val startedAt = parseDate(value(COL_DATE))
            val id = value(COL_ID).toLongOrNull()
            if (startedAt == null || id == null || startedAt.year !in PLAUSIBLE_YEARS) {
                skipped++
                continue
            }

            val instant = startedAt.toInstant(ZoneOffset.UTC)
            val type = value(COL_TYPE).trim()
            // Moving time is the training number; elapsed only stands in when it is absent.
            val seconds = number(value(COL_MOVING)).takeIf { it > 0 } ?: number(value(COL_ELAPSED))
            val meters = number(value(COL_DISTANCE_M)).takeIf { it > 0 }
                ?: (number(value(COL_DISTANCE_KM)) * 1000)

            out += StravaActivity(
                activityId = id,
                dateEpochDay = instant.atZone(zone).toLocalDate().toEpochDay(),
                startTimeMillis = instant.toEpochMilli(),
                name = value(COL_NAME).trim(),
                typeRaw = type,
                durationSeconds = seconds.toInt(),
                distanceMeters = meters.toInt(),
                calories = number(value(COL_CALORIES)).toInt(),
                avgHeartRate = number(value(COL_HR_AVG)).toInt(),
                maxHeartRate = number(value(COL_HR_MAX)).toInt(),
                elevationGainMeters = number(value(COL_ELEVATION)).toInt()
            )
            if (type.isNotEmpty()) sports[type] = (sports[type] ?: 0) + 1
        }

        if (out.isEmpty()) {
            return Result.Failed("В файле нет ни одной тренировки с разбираемой датой")
        }
        return Result.Ok(out, skipped, sports)
    }

    /**
     * First non-blank of the columns carrying one header name.
     *
     * The duplicates hold the same value in different shapes (`1309` beside `1309.0`), so
     * either would do - but only as long as both are filled, and "first non-blank" is the
     * rule that stays right when one of them is not.
     */
    private fun valueOf(row: List<String>, indices: List<Int>?): String {
        if (indices == null) return ""
        for (i in indices) {
            val v = row.getOrNull(i)?.trim().orEmpty()
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    private val MONTHS = mapOf(
        "янв" to 1, "февр" to 2, "фев" to 2, "мар" to 3, "апр" to 4,
        "мая" to 5, "май" to 5, "июн" to 6, "июл" to 7, "авг" to 8,
        "сент" to 9, "сен" to 9, "окт" to 10, "нояб" to 11, "ноя" to 11, "дек" to 12
    )

    // \h would be nice; Kotlin has no such class, so every space Strava might use is
    // listed - U+202F (narrow no-break) is the one actually in the file, U+00A0 the one
    // the same exporter uses in other locales.
    private val DATE = Regex(
        """(\d{1,2})[\s  ]+([А-Яа-яЁё]+)\.?[\s  ]*(\d{4})[\s  ]*г\.?,?[\s  ]*(\d{1,2}):(\d{2})(?::(\d{2}))?"""
    )

    /** ISO, for an export in a locale whose dates this doesn't know. */
    private val ISO_DATE = Regex("""(\d{4})-(\d{2})-(\d{2})[T\s](\d{1,2}):(\d{2})(?::(\d{2}))?""")

    private fun parseDate(raw: String): LocalDateTime? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        DATE.find(text)?.let { m ->
            val month = MONTHS[m.groupValues[2].lowercase().trimEnd('.')] ?: return@let
            return runCatching {
                LocalDateTime.of(
                    m.groupValues[3].toInt(), month, m.groupValues[1].toInt(),
                    m.groupValues[4].toInt(), m.groupValues[5].toInt(),
                    m.groupValues[6].toIntOrNull() ?: 0
                )
            }.getOrNull()
        }

        ISO_DATE.find(text)?.let { m ->
            return runCatching {
                LocalDateTime.of(
                    m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(),
                    m.groupValues[4].toInt(), m.groupValues[5].toInt(),
                    m.groupValues[6].toIntOrNull() ?: 0
                )
            }.getOrNull()
        }
        return null
    }

    /** Accepts both decimal separators - the same file uses one in each of two columns. */
    private fun number(raw: String): Double =
        raw.trim().replace(',', '.').replace(" ", "").replace(" ", "")
            .toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: 0.0

    /**
     * Minimal RFC 4180 reader: quoted fields, doubled quotes inside them, CRLF or LF.
     *
     * Split-on-comma would be enough for this particular file - no field in it contains a
     * newline - but activity names and descriptions are free text the user types, and one
     * comma inside a name would shift every column after it by one without any error.
     */
    private fun readCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0

        // A UTF-8 BOM survives into the first header name and stops it matching.
        val body = text.removePrefix("﻿")

        fun endField() {
            row.add(field.toString())
            field.setLength(0)
        }

        fun endRow() {
            endField()
            if (row.size > 1 || row.firstOrNull()?.isNotEmpty() == true) rows.add(row)
            row = mutableListOf()
        }

        while (i < body.length) {
            val c = body[i]
            when {
                quoted && c == '"' && i + 1 < body.length && body[i + 1] == '"' -> {
                    field.append('"'); i++
                }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> endField()
                !quoted && c == '\n' -> endRow()
                !quoted && c == '\r' -> Unit
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    /** Millis to [Instant], for readers that would rather not think in longs. */
    fun instantOf(activity: StravaActivity): Instant = Instant.ofEpochMilli(activity.startTimeMillis)
}
