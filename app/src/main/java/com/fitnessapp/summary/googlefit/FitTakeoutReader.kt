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
        var filesRead = 0
        var filesSkipped = 0
        var points = 0
        var jsonSeen = 0

        try {
            ZipInputStream(input.buffered(64 * 1024)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) { zip.closeEntry(); continue }
                    val name = entry.name
                    if (!name.endsWith(".json", ignoreCase = true)) {
                        filesSkipped++
                        zip.closeEntry()
                        continue
                    }
                    jsonSeen++

                    // A reader per entry is safe: ZipInputStream reports end-of-stream at the
                    // entry boundary, so it can never buffer bytes belonging to the next file.
                    val reader = BufferedReader(InputStreamReader(NonClosing(zip), Charsets.UTF_8), 64 * 1024)
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

        if (jsonSeen == 0) {
            return Result.Failed(
                "В архиве нет данных Google Fit. Выгрузка Takeout приходит несколькими частями — " +
                    "возьмите ту, внутри которой папка Fit."
            )
        }
        if (filesRead == 0) {
            return Result.Failed(
                "В архиве есть JSON, но ни одного объединённого потока Google Fit. " +
                    "Проверьте, что выбрана часть с папкой Fit."
            )
        }

        return Result.Ok(
            FitTakeoutParser.Outcome(
                days = accumulator.build(System.currentTimeMillis()),
                pointsByStream = accumulator.pointsByStream.toMap(),
                unknownStreams = unknown.toList(),
                filesRead = filesRead,
                filesSkipped = filesSkipped
            )
        )
    }

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
