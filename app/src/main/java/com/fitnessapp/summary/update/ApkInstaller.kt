package com.fitnessapp.summary.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.fitnessapp.summary.debug.AppLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object ApkInstaller {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    sealed class DownloadResult {
        data class Ready(val file: File) : DownloadResult()
        data class Failed(val reason: String) : DownloadResult()
    }

    private fun fail(reason: String): DownloadResult.Failed {
        // Never log the redirected/signed asset URL.
        AppLog.w("ApkInstaller", reason)
        return DownloadResult.Failed(reason)
    }

    fun download(context: Context, url: String, expectedVersion: Int): DownloadResult {
        var temporary: File? = null
        return try {
            val external = context.getExternalFilesDir(null)
                ?: return fail("Нет доступа к папке обновлений.")
            val dir = File(external, "update")
            if (!dir.isDirectory && !dir.mkdirs()) return fail("Не удалось создать папку обновлений.")
            val part = File.createTempFile("fitness-update-", ".part.apk", dir)
            temporary = part
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return fail("Сервер загрузки: HTTP ${response.code}.")
                val body = response.body ?: return fail("Сервер вернул пустой ответ.")
                val expected = body.contentLength()
                val limit = 256L * 1024 * 1024
                if (expected > limit) return fail("Слишком большой файл обновления.")
                var received = 0L
                part.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            received += n
                            if (received > limit) return fail("Слишком большой файл обновления.")
                            out.write(buffer, 0, n)
                        }
                    }
                }
                if (received == 0L || (expected >= 0 && expected != received))
                    return fail("APK скачан не полностью.")
            }
            val info = context.packageManager.getPackageArchiveInfo(part.absolutePath, 0)
                ?: return fail("Скачанный файл не является корректным APK.")
            @Suppress("DEPRECATION")
            val version = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                info.longVersionCode else info.versionCode.toLong()
            if (info.packageName != context.packageName || version != expectedVersion.toLong())
                return fail("Пакет или версия APK не совпадает с обновлением.")
            val outFile = File(dir, "fitness-summary-update-$expectedVersion.apk")
            if (outFile.exists() && !outFile.delete()) return fail("Не удалось заменить старый файл обновления.")
            if (!part.renameTo(outFile)) return fail("Не удалось сохранить скачанный APK.")
            DownloadResult.Ready(outFile)
        } catch (_: java.net.SocketTimeoutException) {
            fail("Истекло время загрузки APK. Попробуйте другую сеть или браузер.")
        } catch (_: java.net.UnknownHostException) {
            fail("Сервер скачивания недоступен по DNS.")
        } catch (_: javax.net.ssl.SSLException) {
            fail("Не удалось установить защищённое соединение с сервером скачивания.")
        } catch (e: IOException) {
            fail("Ошибка скачивания или записи APK (${e.javaClass.simpleName}).")
        } catch (e: Exception) {
            fail("Не удалось подготовить обновление (${e.javaClass.simpleName}).")
        } finally {
            temporary?.delete()
        }
    }

    /** Whether the app is currently allowed to trigger the system installer. */
    fun canRequestInstalls(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
    }

    /** Sends the user to the system settings screen where they can grant "install unknown apps". */
    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    /** Launches the system package installer for [apkFile]. */
    fun install(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }
}
