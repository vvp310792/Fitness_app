package com.fitnessapp.summary.update

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * If you fork or rename this repository, update these two constants -
 * everything else in the updater is derived from them.
 */
private const val GITHUB_OWNER = "vvp310792"
private const val GITHUB_REPO = "Fitness_app"

data class ReleaseInfo(
    val versionCode: Int,
    val releaseName: String,
    val downloadUrl: String
)

sealed class UpdateCheckResult {
    data class UpdateAvailable(val release: ReleaseInfo) : UpdateCheckResult()
    data object UpToDate : UpdateCheckResult()
    data class Failed(val reason: String) : UpdateCheckResult()
}

object UpdateChecker {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Looks up the latest GitHub Release for this repo (published by
     * .github/workflows/build.yml, tagged "build-<N>") and compares its
     * version number against [currentVersionCode].
     */
    fun check(currentVersionCode: Int): UpdateCheckResult {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val reason = when (response.code) {
                        404 -> "GitHub: релизов ещё нет (HTTP 404). Проверьте вкладку Releases в репозитории."
                        403 -> "GitHub: превышен лимит запросов (HTTP 403). Попробуйте позже."
                        else -> "GitHub вернул ошибку HTTP ${response.code}."
                    }
                    return UpdateCheckResult.Failed(reason)
                }
                val json = JSONObject(response.body?.string().orEmpty())

                val tagName = json.optString("tag_name") // e.g. "build-42"
                val versionCode = tagName.substringAfterLast('-').toIntOrNull()
                    ?: return UpdateCheckResult.Failed("Не удалось разобрать номер версии из тега \"$tagName\".")

                val assets = json.optJSONArray("assets")
                    ?: return UpdateCheckResult.Failed("В последнем релизе нет вложенных файлов.")
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name")
                    if (name.endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
                if (apkUrl.isNullOrBlank()) {
                    return UpdateCheckResult.Failed("В последнем релизе нет .apk файла.")
                }

                if (versionCode <= currentVersionCode) {
                    UpdateCheckResult.UpToDate
                } else {
                    UpdateCheckResult.UpdateAvailable(
                        ReleaseInfo(
                            versionCode = versionCode,
                            releaseName = json.optString("name", tagName),
                            downloadUrl = apkUrl
                        )
                    )
                }
            }
        } catch (e: IOException) {
            UpdateCheckResult.Failed("Ошибка сети: ${e.message ?: "нет подключения"}.")
        } catch (e: Exception) {
            UpdateCheckResult.Failed("Неожиданная ошибка: ${e.message ?: e.javaClass.simpleName}.")
        }
    }
}
