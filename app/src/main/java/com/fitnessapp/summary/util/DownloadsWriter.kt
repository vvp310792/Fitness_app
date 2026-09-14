package com.fitnessapp.summary.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.fitnessapp.summary.debug.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Puts a file into the phone's own Downloads folder.
 *
 * Both files this app hands the user - the JSON export and the log - used to go out
 * through a share sheet. That works, but it makes the user pick a destination app for
 * something they only wanted to *keep*, and what lands where depends on whatever they
 * picked. Downloads is the one place on Android every file manager, messenger and
 * browser already opens.
 *
 * Writes through [MediaStore], so **no storage permission is involved at all** on
 * Android 10 and newer: the app owns what it inserts, and the system decides where the
 * bytes physically live. Older releases would need `WRITE_EXTERNAL_STORAGE` and a runtime
 * grant - not implemented, and said plainly rather than failing quietly, because this app
 * needs Android 14 for its actual purpose anyway (Garmin only writes to Health Connect
 * from there).
 */
object DownloadsWriter {

    sealed class Result {
        /**
         * What to tell the user. Success names the file, because MediaStore may have renamed
         * it and a bare «сохранено» leaves them hunting through Downloads for which one.
         */
        abstract val message: String

        /**
         * @param fileName what the file is **actually** called in Downloads, which is not
         *   always what was asked for - see [uniqueNameNote].
         */
        data class Saved(val fileName: String, val uri: Uri) : Result() {
            override val message get() = "Сохранено в «Загрузки»: $fileName"
        }

        data class Failed(val reason: String) : Result() {
            override val message get() = "Не удалось сохранить: $reason"
        }
    }

    suspend fun save(
        context: Context,
        source: File,
        fileName: String,
        mimeType: String
    ): Result = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return@withContext Result.Failed(
                "Сохранение в «Загрузки» требует Android 10 или новее."
            )
        }
        if (!source.exists() || source.length() == 0L) {
            return@withContext Result.Failed("Файл пуст - сохранять нечего.")
        }

        val resolver = context.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            // Hides the half-written file from other apps until the copy finishes - a file
            // manager that indexed it mid-write would show a truncated export.
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        var uri: Uri? = null
        return@withContext try {
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending)
                ?: return@withContext Result.Failed("Система не дала записать в «Загрузки».")

            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: return@withContext Result.Failed("Не удалось открыть файл для записи.")

            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null
            )

            val saved = uniqueNameNote(context, uri) ?: fileName
            AppLog.i("DownloadsWriter", "Сохранено в Загрузки: $saved")
            Result.Saved(saved, uri)
        } catch (e: Exception) {
            // A half-inserted row would sit in Downloads as a zero-byte file the user can
            // neither open nor explain.
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            AppLog.e("DownloadsWriter", "Сохранение в Загрузки не удалось", e)
            Result.Failed(e.message ?: "Не удалось сохранить файл.")
        }
    }

    /**
     * MediaStore never overwrites: a second export on the same day is saved as
     * `…_2026-09-14 (1).json`, silently. Telling the user the name it asked for would send
     * them looking for a file that holds yesterday's data, so the stored name is read back.
     */
    private fun uniqueNameNote(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
}
