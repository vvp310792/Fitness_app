package com.fitnessapp.summary.googlefit

import android.content.Context
import android.net.Uri
import com.fitnessapp.summary.debug.AppLog
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.time.ZoneId
import java.util.zip.ZipInputStream

/**
 * Streams a Google Takeout `.zip` and hands its Fit data to [FitTakeoutParser].
 *
 * Everything Android- and IO-shaped lives here; the meaning of the bytes lives in the parser,
 * where a JVM test can reach it. Same split as `GymUpBackupReader` / `GymUpBackupParser`.
 *
 * **Streamed, never extracted.** The archive is 68 MB but holds 1264 MB of JSON, and the
 * phone has no business writing that to disk. `ZipInputStream` reads it sequentially: for each
 * entry the first lines are read to learn its `Data Source`, and unless that is one of Google's
 * merged streams the rest of the entry is skipped. Skipping still inflates the bytes - that is
 * unavoidable without random access - but inflation is cheap; parsing 1.2 GB of JSON would not be.
 *
 * **`.tcx` are ignored**: those 3541 files are the same sessions already imported from Strava
 * and Garmin, and a third copy of a workout only makes "no Training Effect" ambiguous.
 */
object FitTakeoutReader {

    /** How much of an entry may be read before giving up on finding its `Data Source`. */
    private const val HEADER_LINES = 4

    sealed class Result {
        data class Ok(val outcome: FitTakeoutParser.Outcome) : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * @param onProgress called with (files opened so far, points read so far) - the archive
     *   takes tens of seconds, and a progress line that names the whole walk rather than one
     *   file is the difference between "working" and "stuck" (see CLAUDE.md, «Прогресс,
     *   который описывает шаг вместо пути»).
     */
    fun read(
        context: Context,
        uri: Uri,
        zone: ZoneId = ZoneId.systemDefault(),
        onProgress: (files: Int, points: Int) -> Unit = { _, _ -> }
    ): Result {
        val input: InputStream = context.contentResolver.openInputStream(uri)
            ?: return Result.Failed("Не удалось открыть файл")
        return input.use { read(it, zone, onProgress) }
    }

    /**
     * The same walk over a plain stream, so a JVM test can hand it a real zip.
     *
     * Everything that can quietly go wrong here is about the walk itself - whether a reader
     * built on one entry can see the next one's bytes, whether the `Data Source` line is
     * found before the points start, whether a skipped entry leaves the archive positioned
     * correctly - and none of that is visible from the parser's side.
     */
    fun read(
        input: InputStream,
        zone: ZoneId = ZoneId.systemDefault(),
        onProgress: (files: Int, points: Int) -> Unit = { _, _ -> }
    ): Result {
        val accumulator = FitTakeoutParser.Accumulator(zone)
        val unknown = LinkedHashSet<String>()
        val nights = ArrayList<FitSleepSessionParser.Night>()
        var csvDays: List<com.fitnessapp.summary.data.FitDay> = emptyList()
        var csvSkippedRows = 0
        var csvMissingColumns: List<String> = emptyList()
        var filesRead = 0
        var filesSkipped = 0
        var points = 0
        var usefulSeen = 0
        var sleepFilesSeen = 0

        try {
            ZipInputStream(input.buffered(64 * 1024)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) { zip.closeEntry(); continue }
                    val name = entry.name.substringAfterLast('/').substringAfterLast('\\')

                    // A reader per entry is safe: ZipInputStream reports end-of-stream at the
                    // entry boundary, so it can never buffer bytes belonging to the next file.
                    val reader = BufferedReader(InputStreamReader(NonClosing(zip), Charsets.UTF_8), 64 * 1024)

                    // --- the daily summary: one row per day, and the best source for most of
                    // what this import stores. It lives only in the SECOND part of the download.
                    if (name.endsWith(".csv", ignoreCase = true)) {
                        if (isDailySummaryCsv(name)) {
                            usefulSeen++
                            filesRead++
                            val parsed = FitDailyCsvParser.parse(reader.readText(), System.currentTimeMillis())
                            csvDays = parsed.days
                            csvSkippedRows = parsed.skippedRows
                            csvMissingColumns = parsed.missingColumns
                            onProgress(filesRead, points)
                        } else {
                            // The per-day CSVs are 15-minute series - 2920 of them, and nothing
                            // on any screen is drawn from inside a day.
                            filesSkipped++
                        }
                        zip.closeEntry()
                        continue
                    }

                    if (!name.endsWith(".json", ignoreCase = true)) {
                        filesSkipped++
                        zip.closeEntry()
                        continue
                    }

                    // --- a sleep session: the only real source of nights here. 269 of them on
                    // this archive against 91 points in the `sleep.segment` stream.
                    if (isSleepSession(name)) {
                        sleepFilesSeen++
                        usefulSeen++
                        FitSleepSessionParser.parse(reader.readText(), zone)?.let { nights += it }
                        zip.closeEntry()
                        continue
                    }

                    val sourceId = readDataSource(reader)
                    if (sourceId == null) {
                        filesSkipped++
                        zip.closeEntry()
                        continue
                    }

                    val stream = FitTakeoutParser.classify(sourceId)
                    if (stream == null) {
                        if (FitTakeoutParser.looksMerged(sourceId)) unknown += sourceId
                        filesSkipped++
                        zip.closeEntry()
                        continue
                    }
                    usefulSeen++

                    // Both step streams describe the same walk and only one of them is used.
                    // Parsing the loser costs 316 494 lines out of 100 MB of JSON here.
                    if (stream == FitTakeoutParser.Stream.STEPS_MERGED && accumulator.sawEstimatedSteps) {
                        filesSkipped++
                        zip.closeEntry()
                        continue
                    }

                    filesRead++
                    var line = reader.readLine()
                    while (line != null) {
                        points += accumulator.feedLine(stream, line)
                        line = reader.readLine()
                    }
                    onProgress(filesRead, points)
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            AppLog.e("FitTakeoutReader", "Чтение архива Takeout упало", e)
            return Result.Failed(e.message ?: "Не удалось прочитать архив")
        }

        // "Nothing useful in here" is the only honest failure. The download is split in two,
        // and the parts hold different things: the first has the streams, the second the daily
        // CSV and the sleep sessions - and it has NO merged stream at all beyond speed. The
        // first version of this reader demanded a stream and therefore rejected the half of
        // the archive that carries the best data in it.
        if (usefulSeen == 0) {
            return Result.Failed(
                "В архиве нет данных Google Fit. Выгрузка Takeout приходит несколькими частями — " +
                    "возьмите ту, внутри которой папка Fit."
            )
        }

        val nowMillis = System.currentTimeMillis()
        val days = FitTakeoutParser.combine(
            streamDays = accumulator.build(nowMillis),
            csvDays = csvDays,
            nights = nights,
            nowMillis = nowMillis
        )

        return Result.Ok(
            FitTakeoutParser.Outcome(
                days = days,
                pointsByStream = accumulator.pointsByStream.toMap(),
                unknownStreams = unknown.toList(),
                filesRead = filesRead,
                filesSkipped = filesSkipped,
                csvDays = csvDays.size,
                csvSkippedRows = csvSkippedRows,
                csvMissingColumns = csvMissingColumns,
                sleepFilesSeen = sleepFilesSeen,
                nights = nights.size
            )
        )
    }


    /**
     * The one daily-summary CSV among 2921 of them.
     *
     * Told apart by shape, not by its Russian name: `Показатели ежедневной активности.csv`
     * would have to be matched through whatever encoding the zip used for entry names, while
     * "the CSV whose name is not a date" needs no encoding at all. Every other CSV in that
     * folder is called `2019-05-02.csv` and holds a 15-minute series.
     */
    internal fun isDailySummaryCsv(name: String): Boolean =
        name.endsWith(".csv", ignoreCase = true) && !name.matches(DATED_FILE)

    /** `2026-09-09T20_06_00+03_00_SLEEP.json`, and the `_SLEEP(1).json` duplicates too. */
    internal fun isSleepSession(name: String): Boolean =
        name.matches(SLEEP_SESSION)

    private val DATED_FILE = Regex("^\\d{4}-\\d{2}-\\d{2}.*")
    private val SLEEP_SESSION = Regex("^\\d{4}-\\d{2}-\\d{2}T.*_SLEEP(\\(\\d+\\))?\\.json$", RegexOption.IGNORE_CASE)

    /** `"Data Source": "derived:com.google.step_count.delta:com.google.android.gms:estimated_steps"` */
    private fun readDataSource(reader: BufferedReader): String? {
        repeat(HEADER_LINES) {
            val line = reader.readLine() ?: return null
            val at = line.indexOf("\"Data Source\"")
            if (at >= 0) {
                val colon = line.indexOf(':', at + 13)
                if (colon < 0) return null
                val open = line.indexOf('"', colon + 1)
                if (open < 0) return null
                val close = line.indexOf('"', open + 1)
                if (close < 0) return null
                return line.substring(open + 1, close)
            }
        }
        return null
    }

    /** Keeps `InputStreamReader.close()` from closing the whole archive. */
    private class NonClosing(private val delegate: InputStream) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun available(): Int = delegate.available()
        override fun close() = Unit
    }
}
