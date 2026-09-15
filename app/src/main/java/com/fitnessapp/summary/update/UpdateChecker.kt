package com.fitnessapp.summary.update

import com.fitnessapp.summary.debug.AppLog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    /**
     * Direct link to the `.apk`, for the one-tap install. **Empty when the release was found
     * through the feed instead of the API** - the feed does not list a release's files, and
     * building that URL from a filename convention would be a guess that fails as a 404 the
     * moment CI renames its output.
     */
    val downloadUrl: String,
    /** The release page, which always exists and can always be opened in a browser. */
    val pageUrl: String
) {
    val canInstallDirectly: Boolean get() = downloadUrl.isNotBlank()
}

sealed class UpdateCheckResult {
    data class UpdateAvailable(val release: ReleaseInfo) : UpdateCheckResult()
    data object UpToDate : UpdateCheckResult()
    data class Failed(val reason: String) : UpdateCheckResult()
}

/**
 * Finds the newest GitHub Release for this repo (published by `.github/workflows/build.yml`,
 * tagged `build-<N>`) and compares it against the installed version.
 *
 * **Two ways in, because the first one runs out.** `api.github.com` allows 60 unauthenticated
 * requests an hour **per IP address**, and a mobile carrier puts thousands of subscribers
 * behind one - so "GitHub: превышен лимит запросов (HTTP 403)" can appear to somebody who has
 * pressed the button exactly once. Shipping a token to lift the limit is not an option: a
 * token inside an APK is a public token.
 *
 * The fallback is `github.com/<owner>/<repo>/releases.atom` - the website's own feed, not the
 * REST API, and not counted against that quota. It carries the tag of every release, which is
 * all the version check needs. What it does NOT carry is the list of files, so an update found
 * this way offers the release page instead of a one-tap install: an honest extra tap beats a
 * download URL assembled from a naming convention.
 */
object UpdateChecker {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun check(currentVersionCode: Int): UpdateCheckResult {
        val viaApi = checkViaApi(currentVersionCode)
        if (viaApi !is UpdateCheckResult.Failed) return viaApi

        AppLog.w("UpdateChecker", "API не ответил (${viaApi.reason}), пробую ленту релизов")
        val viaFeed = checkViaFeed(currentVersionCode)
        // The feed is a fallback, not a better source: when it fails too, the API's reason is
        // the one worth showing - it is the one that says WHY, and "лимит до 20:14" is
        // actionable in a way that "не удалось разобрать ленту" is not.
        return if (viaFeed is UpdateCheckResult.Failed) viaApi else viaFeed
    }

    // ---- api.github.com: gives the .apk link, runs out after 60 requests an hour per IP ----

    private fun checkViaApi(currentVersionCode: Int): UpdateCheckResult {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val reason = when (response.code) {
                        404 -> "GitHub: релизов ещё нет (HTTP 404). Проверьте вкладку Releases в репозитории."
                        // GitHub names the exact second the quota comes back; saying it turns
                        // "попробуйте позже" into something the user can act on.
                        403, 429 -> "GitHub ограничил число запросов" + rateLimitNote(response.header("X-RateLimit-Reset")) +
                            " Это общий лимит на IP-адрес, а не на вас: у мобильного оператора его делят тысячи абонентов."
                        else -> "GitHub вернул ошибку HTTP ${response.code}."
                    }
                    return UpdateCheckResult.Failed(reason)
                }
                val json = JSONObject(response.body?.string().orEmpty())

                val tagName = json.optString("tag_name") // e.g. "build-42"
                val versionCode = versionOf(tagName)
                    ?: return UpdateCheckResult.Failed("Не удалось разобрать номер версии из тега \"$tagName\".")

                val assets = json.optJSONArray("assets")
                var apkUrl = ""
                for (i in 0 until (assets?.length() ?: 0)) {
                    val asset = assets!!.getJSONObject(i)
                    if (asset.optString("name").endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }

                verdict(
                    currentVersionCode = currentVersionCode,
                    versionCode = versionCode,
                    releaseName = json.optString("name", tagName),
                    downloadUrl = apkUrl,
                    pageUrl = json.optString("html_url", releasePage(tagName))
                )
            }
        } catch (e: IOException) {
            UpdateCheckResult.Failed("Ошибка сети: ${e.message ?: "нет подключения"}.")
        } catch (e: Exception) {
            UpdateCheckResult.Failed("Неожиданная ошибка: ${e.message ?: e.javaClass.simpleName}.")
        }
    }

    // ---- releases.atom: always answers, but knows only the tag ----

    private fun checkViaFeed(currentVersionCode: Int): UpdateCheckResult {
        val request = Request.Builder()
            .url("https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases.atom")
            .header("Accept", "application/atom+xml")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return UpdateCheckResult.Failed("Лента релизов вернула HTTP ${response.code}.")
                }
                val body = response.body?.string().orEmpty()
                val tag = newestTag(body)
                    ?: return UpdateCheckResult.Failed("В ленте релизов не нашлось ни одного тега build-<номер>.")
                val versionCode = versionOf(tag)
                    ?: return UpdateCheckResult.Failed("Не удалось разобрать номер версии из тега \"$tag\".")

                verdict(
                    currentVersionCode = currentVersionCode,
                    versionCode = versionCode,
                    releaseName = "Build $versionCode",
                    // The feed lists no files - see ReleaseInfo.downloadUrl.
                    downloadUrl = "",
                    pageUrl = releasePage(tag)
                )
            }
        } catch (e: IOException) {
            UpdateCheckResult.Failed("Ошибка сети: ${e.message ?: "нет подключения"}.")
        } catch (e: Exception) {
            UpdateCheckResult.Failed("Неожиданная ошибка: ${e.message ?: e.javaClass.simpleName}.")
        }
    }

    /**
     * The highest `build-<N>` in the feed, not the first one.
     *
     * The entries are newest-first today, and relying on that would be relying on a promise
     * nobody made - a re-published release, or a hand-made tag, reorders them. The number is
     * what the version check compares, so the number is what picks the winner.
     */
    internal fun newestTag(atom: String): String? =
        Regex("""build-(\d+)""").findAll(atom)
            .mapNotNull { match -> match.groupValues[1].toIntOrNull()?.let { it to match.value } }
            .maxByOrNull { it.first }
            ?.second

    private fun versionOf(tag: String): Int? = tag.substringAfterLast('-').toIntOrNull()

    private fun releasePage(tag: String) =
        "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/tag/$tag"

    private fun verdict(
        currentVersionCode: Int,
        versionCode: Int,
        releaseName: String,
        downloadUrl: String,
        pageUrl: String
    ): UpdateCheckResult = if (versionCode <= currentVersionCode) {
        UpdateCheckResult.UpToDate
    } else {
        UpdateCheckResult.UpdateAvailable(ReleaseInfo(versionCode, releaseName, downloadUrl, pageUrl))
    }

    /** `X-RateLimit-Reset` is a unix second; without it the sentence still has to read. */
    internal fun rateLimitNote(resetHeader: String?, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = resetHeader?.toLongOrNull()?.takeIf { it > 0 } ?: return " — попробуйте позже."
        val time = Instant.ofEpochSecond(at).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
        return " — лимит сбросится в $time."
    }
}
