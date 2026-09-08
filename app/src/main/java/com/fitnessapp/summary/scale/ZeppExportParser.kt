package com.fitnessapp.summary.scale

import com.fitnessapp.summary.data.ScaleMeasurement
import com.fitnessapp.summary.debug.AppLog
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.inputstream.ZipInputStream
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Reads the weigh-in history out of a Zepp Life **data export** - the archive Zepp mails
 * after Профиль → Настройки → Аккаунт и безопасность → Экспорт данных.
 *
 * Why this exists at all: the live paths only ever bring the recent past. Health Connect
 * gets what Google Fit forwarded *after* the two were connected, and the Zepp cloud login
 * is rejected outright for accounts Xiaomi's anti-fraud check dislikes. The export is the
 * only route to the years that came before, and it is the account's own full history -
 * for the file this was written against, 321 weigh-ins going back to 2021.
 *
 * What the file actually looks like, verified against a real export rather than assumed:
 *
 * - The archive is **WinZip AES** encrypted (compression method 99), which `java.util.zip`
 *   cannot read at all, hence zip4j. The password comes in the same email.
 * - Weights live in `BODY/BODY_<millis>.csv`. Other folders (ACTIVITY, SLEEP, HEARTRATE,
 *   SPORT, USER, ...) are not touched - Garmin is the source for all of those.
 * - The CSV starts with a **UTF-8 BOM**, and its header is
 *   `time,weight,height,bmi,fatRate,bodyWaterRate,boneMass,metabolism,muscleRate,visceralFat`.
 *   Columns are matched **by name**, not position, so a reordered or extended export
 *   still reads.
 * - `time` is a real UTC instant with an offset: `2021-02-05 00:30:20+0000`. (Cross-checked
 *   against the same export's SLEEP rows, whose day boundaries land exactly on local
 *   midnight - the offsets are not decorative.)
 * - Missing values are the literal string **`null`**, not an empty cell: a weigh-in where
 *   the impedance measurement failed keeps weight and BMI but has `null` in all six
 *   body-composition columns. 17 of 321 rows were like that. Those become 0 = "unknown",
 *   the same convention the rest of the app uses.
 * - `muscleRate` is muscle MASS in kilograms despite the name (57.9 for a 77 kg person) -
 *   the same trap documented on [ScaleMeasurement].
 *
 * The export carries fewer fields than the Zepp cloud API: no protein, body score,
 * physique rating, metabolic age or impedance. Those stay 0, and the Day screen simply
 * doesn't draw the rows it has no numbers for.
 */
object ZeppExportParser {

    sealed class Result {
        data class Ok(
            val measurements: List<ScaleMeasurement>,
            /** Rows present in the file but unusable (no weight, unparseable date, absurd value). */
            val skippedRows: Int,
            val entryName: String
        ) : Result()

        /** [needsPassword] means the archive is encrypted and the given password was missing or wrong. */
        data class Failed(val reason: String, val needsPassword: Boolean = false) : Result()
    }

    /**
     * Parses [input] - either the export .zip or a single BODY csv pulled out of it.
     * The choice is made on the stream's own magic bytes, not the file name, because a
     * content:// pick often hides the name.
     */
    fun parse(
        input: InputStream,
        password: String? = null,
        zone: ZoneId = ZoneId.systemDefault()
    ): Result {
        val stream = BufferedInputStream(input)
        return if (looksLikeZip(stream)) parseZip(stream, password, zone) else parseCsv(stream, "CSV", zone)
    }

    private fun looksLikeZip(stream: BufferedInputStream): Boolean {
        stream.mark(4)
        val head = ByteArray(2)
        val read = stream.read(head)
        stream.reset()
        return read == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
    }

    private fun parseZip(stream: BufferedInputStream, password: String?, zone: ZoneId): Result {
        val zip = ZipInputStream(stream, password?.takeIf { it.isNotBlank() }?.toCharArray())
        val seen = mutableListOf<String>()
        try {
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.fileName
                seen += name
                if (entry.isDirectory) continue
                if (!name.endsWith(".csv", ignoreCase = true)) continue
                // BODY/BODY_<millis>.csv - matched loosely so a renamed or nested copy still works.
                if (!name.substringAfterLast('/').startsWith("BODY", ignoreCase = true)) continue
                return parseCsv(zip, name, zone)
            }
        } catch (e: ZipException) {
            val wrongPassword = e.message?.contains("password", ignoreCase = true) == true ||
                e.type == ZipException.Type.WRONG_PASSWORD
            AppLog.w("ZeppExportParser", "Архив не открылся: ${e.message}")
            return Result.Failed(
                if (wrongPassword) "неверный пароль архива" else (e.message ?: "архив не читается"),
                needsPassword = wrongPassword
            )
        } catch (e: Exception) {
            AppLog.w("ZeppExportParser", "Архив не открылся", e)
            return Result.Failed(e.message ?: e.javaClass.simpleName)
        }
        AppLog.w("ZeppExportParser", "В архиве нет BODY-файла, записей: ${seen.size}")
        return Result.Failed("в архиве нет папки BODY с историей взвешиваний")
    }

    private fun parseCsv(input: InputStream, entryName: String, zone: ZoneId): Result {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val header = generateSequence { reader.readLine() }.firstOrNull { it.isNotBlank() }
            ?: return Result.Failed("файл пустой")

        val columns = splitRow(header.removePrefix("﻿")).withIndex()
            .associate { (index, name) -> name.trim().lowercase() to index }
        val timeAt = columns["time"] ?: return notBodyFile(columns.keys)
        val weightAt = columns["weight"] ?: return notBodyFile(columns.keys)

        val now = System.currentTimeMillis()
        // Keyed by instant: a file can legitimately repeat one, and the primary key would
        // collapse them anyway - better to decide here (last wins) than to miscount below.
        val byInstant = LinkedHashMap<Long, ScaleMeasurement>()
        var skipped = 0

        reader.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val cells = splitRow(line)
            fun cell(at: Int?): String? = at?.let { cells.getOrNull(it) }
            fun number(name: String): Float? = number(cell(columns[name]))

            val instant = parseInstant(cell(timeAt))
            val weightKg = number(cell(weightAt))
            if (instant == null || weightKg == null || weightKg !in PLAUSIBLE_WEIGHT_KG) {
                skipped++
                return@forEachLine
            }

            val heightCm = number("height") ?: 0f
            val bmi = number("bmi")
                ?: heightCm.takeIf { it > 0 }?.let { weightKg / ((it / 100) * (it / 100)) }
                ?: 0f
            byInstant[instant.toEpochMilli()] = ScaleMeasurement(
                timestampMillis = instant.toEpochMilli(),
                dateEpochDay = instant.atZone(zone).toLocalDate().toEpochDay(),
                weightGrams = (weightKg * 1000).roundToInt(),
                heightCm = heightCm,
                bmi = bmi,
                bodyFatPercent = number("fatrate") ?: 0f,
                bodyWaterPercent = number("bodywaterrate") ?: 0f,
                boneMassGrams = number("bonemass")?.let { (it * 1000).roundToInt() } ?: 0,
                // Kilograms, not a percentage - see the class comment and ScaleMeasurement.
                muscleMassGrams = number("musclerate")?.let { (it * 1000).roundToInt() } ?: 0,
                visceralFat = number("visceralfat")?.roundToInt() ?: 0,
                basalMetabolismKcal = number("metabolism")?.roundToInt() ?: 0,
                source = ScaleMeasurement.SOURCE_ZEPP_FILE,
                updatedAtMillis = now
            )
        }

        if (byInstant.isEmpty()) {
            return Result.Failed("в файле нет ни одного взвешивания (строк пропущено: $skipped)")
        }
        AppLog.i(
            "ZeppExportParser",
            "Разобран $entryName: взвешиваний ${byInstant.size}, пропущено строк $skipped"
        )
        return Result.Ok(byInstant.values.sortedBy { it.timestampMillis }, skipped, entryName)
    }

    private fun notBodyFile(headers: Set<String>): Result.Failed {
        AppLog.w("ZeppExportParser", "Не похоже на BODY-файл, колонки: ${headers.joinToString()}")
        return Result.Failed("это не файл BODY из выгрузки Zepp Life — в нём нет колонок time и weight")
    }

    /** Values are numbers and timestamps, but quoted cells cost nothing to tolerate. */
    private fun splitRow(line: String): List<String> =
        line.split(',').map { it.trim().trim('"') }

    /** Zepp writes missing numbers as the literal `null`; empty and other filler read the same. */
    private fun number(raw: String?): Float? {
        val text = raw?.trim()?.trim('"').orEmpty()
        if (text.isEmpty() || text.lowercase() in NOT_A_NUMBER) return null
        return text.replace(',', '.').toFloatOrNull()
    }

    private fun parseInstant(raw: String?): Instant? {
        val text = raw?.trim()?.trim('"').orEmpty()
        if (text.isEmpty()) return null

        text.toLongOrNull()?.let { epoch ->
            // Some exports carry a raw stamp; seconds and millis are told apart by magnitude.
            return if (epoch > 100_000_000_000L) Instant.ofEpochMilli(epoch) else Instant.ofEpochSecond(epoch)
        }
        for (format in OFFSET_FORMATS) {
            runCatching { return java.time.OffsetDateTime.parse(text, format).toInstant() }
        }
        // No offset in the text: Zepp's own convention everywhere else in the export is UTC.
        for (format in LOCAL_FORMATS) {
            runCatching { return LocalDateTime.parse(text, format).toInstant(ZoneOffset.UTC) }
        }
        return null
    }

    private val OFFSET_FORMATS = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZ"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX"),
        DateTimeFormatter.ISO_OFFSET_DATE_TIME
    )

    private val LOCAL_FORMATS = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ISO_LOCAL_DATE_TIME
    )

    private val NOT_A_NUMBER = setOf("null", "none", "nan", "-", "--")

    /** Anything outside this is a broken row, not a person. */
    private val PLAUSIBLE_WEIGHT_KG = 20f..400f
}
