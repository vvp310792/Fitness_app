package com.fitnessapp.summary.garmin

import com.fitnessapp.summary.debug.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * One day's worth of the two metrics Health Connect fundamentally cannot provide -
 * they're Garmin's own proprietary wellness scores, never exposed as Health Connect
 * record types (see CLAUDE.md, "Почему Health Connect, а не Garmin Connect API").
 *
 * Both come off the *same* Garmin Connect endpoint as a side effect of reading the
 * day's overall summary - there's no separate stress/body-battery call needed for the
 * daily-level numbers (a full Body Battery time series is a different, messier
 * endpoint this app doesn't read; a single wake/high/low reading is what the Garmin
 * widget itself leads with, and matches the day-level grain the rest of this app uses).
 */
data class GarminDailyExtra(
    val averageStressLevel: Int = 0,
    val maxStressLevel: Int = 0,
    val bodyBatteryAtWake: Int = 0,
    val bodyBatteryHighest: Int = 0,
    val bodyBatteryLowest: Int = 0
) {
    val isEmpty: Boolean
        get() = averageStressLevel == 0 && maxStressLevel == 0 &&
            bodyBatteryAtWake == 0 && bodyBatteryHighest == 0 && bodyBatteryLowest == 0
}

/**
 * Reads data straight from Garmin Connect's own (unofficial, undocumented) mobile API,
 * authenticated via [GarminAuthClient]. Field names and the endpoint path are taken
 * verbatim from garth's `DailySummary` data class
 * (https://github.com/matin/garth/blob/main/src/garth/data/daily_summary.py) - the
 * same grounding as the auth flow in [GarminAuthClient].
 */
class GarminApiClient(private val auth: GarminAuthClient) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Null when not logged in, the request fails, or Garmin has nothing for [date].
     *
     * Wrapped in `withContext(Dispatchers.IO)` because the actual call below is a
     * blocking `OkHttpClient.execute()`, not OkHttp's async callback API - without this,
     * calling from a main-dispatched scope throws `NetworkOnMainThreadException`.
     */
    suspend fun dailyExtra(date: LocalDate): GarminDailyExtra? = withContext(Dispatchers.IO) {
        val authHeader = auth.ensureAuthorizationHeader() ?: run {
            AppLog.w("GarminApiClient", "Нет действующего токена Garmin - не вошли")
            return@withContext null
        }

        val url = HttpUrl.Builder()
            .scheme("https").host("connectapi.$DOMAIN")
            .addPathSegments("usersummary-service/usersummary/daily/")
            .addQueryParameter("calendarDate", date.toString())
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", OAUTH_USER_AGENT)
            .header("Authorization", authHeader)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    AppLog.w("GarminApiClient", "Дневная сводка Garmin за $date - HTTP ${response.code}")
                    return@withContext null
                }
                val json = JSONObject(text)
                GarminDailyExtra(
                    averageStressLevel = json.optInt("averageStressLevel", 0).coerceAtLeast(0),
                    maxStressLevel = json.optInt("maxStressLevel", 0).coerceAtLeast(0),
                    bodyBatteryAtWake = json.optInt("bodyBatteryAtWakeTime", 0).coerceAtLeast(0),
                    bodyBatteryHighest = json.optInt("bodyBatteryHighestValue", 0).coerceAtLeast(0),
                    bodyBatteryLowest = json.optInt("bodyBatteryLowestValue", 0).coerceAtLeast(0)
                )
            }
        } catch (e: Exception) {
            AppLog.w("GarminApiClient", "Не удалось прочитать дневную сводку Garmin за $date", e)
            null
        }
    }

    private companion object {
        const val DOMAIN = "garmin.com"
        const val OAUTH_USER_AGENT = "com.garmin.android.apps.connectmobile"
    }
}
