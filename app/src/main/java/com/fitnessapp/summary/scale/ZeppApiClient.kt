package com.fitnessapp.summary.scale

import com.fitnessapp.summary.data.ScaleMeasurement
import com.fitnessapp.summary.debug.AppLog
import com.fitnessapp.summary.garmin.GarminFetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Reads weigh-ins from the Zepp Life cloud. Endpoints and JSON keys follow
 * SmartScaleConnect's `pkg/zepp/client.go` (MIT), the same grounding as [ZeppAuthClient].
 *
 * - `GET api-mifit.zepp.com/users/{userId}/members/-1/weightRecords?limit=200&toTime={unix}`
 *   with header `apptoken` - `-1` is the account's own profile (family members have their
 *   own ids from `huami.health.scale.familymember.get.json`, which this app doesn't use).
 *   Pages backwards: the response's `next` is the `toTime` for the next page, `0` when done.
 * - Each `items[]` record: `generatedTime` (unix seconds), `deviceId`, `weightType` (only
 *   `0` is a real measurement - SmartScaleConnect found `3` carries broken weights and
 *   skips it), and a `summary` with `weight`, `height`, `bmi`, `fatRate`, `bodyWaterRate`,
 *   `boneMass`, `metabolism`, `muscleRate` (mass in kg, despite the name - see
 *   [ScaleMeasurement]), `muscleAge`, `proteinRatio`, `visceralFat`, `bodyScore`,
 *   `bodyStyle`, `impedance`.
 *
 * Returns [GarminFetch] like the Garmin client does, for the same reason: "Zepp has no
 * weigh-ins" and "the call failed" must not look alike to the sync manager.
 */
class ZeppApiClient(private val auth: ZeppAuthClient) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Every weigh-in newer than [sinceMillis] (exclusive), oldest first. Pass 0 for the
     * whole history - Zepp pages by 200, so even years of daily weigh-ins are a handful of
     * requests.
     */
    suspend fun weighIns(sinceMillis: Long = 0L): GarminFetch<List<ScaleMeasurement>> = withContext(Dispatchers.IO) {
        val session = auth.session()
            ?: return@withContext GarminFetch.Failed("нет сессии Zepp Life - не вошли", isNetwork = false)

        val out = mutableListOf<ScaleMeasurement>()
        var toTime = System.currentTimeMillis() / 1000 + 24 * 3600
        var pages = 0
        val zone = ZoneId.systemDefault()

        while (toTime > 0 && pages < MAX_PAGES) {
            val url = HttpUrl.Builder()
                .scheme("https").host(HOST)
                .addPathSegments("users/${session.userId}/members/-1/weightRecords")
                .addQueryParameter("limit", PAGE_SIZE.toString())
                .addQueryParameter("toTime", toTime.toString())
                .build()
            val json = when (val fetch = get(url, session)) {
                is GarminFetch.Ok -> fetch.value
                is GarminFetch.Failed -> return@withContext fetch
                GarminFetch.NoData -> break
            }

            val items = json.optJSONArray("items")
            var reachedSince = false
            if (items != null) {
                for (i in 0 until items.length()) {
                    val record = items.optJSONObject(i) ?: continue
                    if (record.optInt("weightType", 0) != 0) continue
                    val seconds = record.optLong("generatedTime", 0L)
                    if (seconds <= 0L) continue
                    val millis = seconds * 1000
                    if (millis <= sinceMillis) {
                        reachedSince = true
                        continue
                    }
                    val summary = record.optJSONObject("summary") ?: continue
                    val weightKg = summary.optDouble("weight", 0.0)
                    if (weightKg <= 0.0) continue
                    out += ScaleMeasurement(
                        timestampMillis = millis,
                        dateEpochDay = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay(),
                        weightGrams = Math.round(weightKg * 1000).toInt(),
                        heightCm = summary.float("height"),
                        bmi = summary.float("bmi"),
                        bodyFatPercent = summary.float("fatRate"),
                        bodyWaterPercent = summary.float("bodyWaterRate"),
                        boneMassGrams = Math.round(summary.optDouble("boneMass", 0.0) * 1000).toInt().coerceAtLeast(0),
                        muscleMassGrams = Math.round(summary.optDouble("muscleRate", 0.0) * 1000).toInt().coerceAtLeast(0),
                        metabolicAge = summary.optInt("muscleAge", 0).coerceAtLeast(0),
                        visceralFat = Math.round(summary.optDouble("visceralFat", 0.0)).toInt().coerceAtLeast(0),
                        basalMetabolismKcal = Math.round(summary.optDouble("metabolism", 0.0)).toInt().coerceAtLeast(0),
                        proteinPercent = summary.float("proteinRatio"),
                        bodyScore = summary.optInt("bodyScore", 0).coerceAtLeast(0),
                        physiqueRating = summary.optInt("bodyStyle", 0).coerceAtLeast(0),
                        impedance = summary.optInt("impedance", 0).coerceAtLeast(0),
                        deviceId = record.optString("deviceId", "")
                    )
                }
            }

            val next = json.optLong("next", 0L)
            // Guard against a server that hands back the same cursor forever.
            if (reachedSince || next <= 0L || next >= toTime) break
            toTime = next
            pages++
        }

        if (out.isEmpty()) GarminFetch.NoData else GarminFetch.Ok(out.sortedBy { it.timestampMillis })
    }

    /**
     * A cheap authenticated call, used to check whether a stored token still works -
     * SmartScaleConnect validates a restored token with exactly this request.
     */
    suspend fun sessionIsValid(): Boolean = withContext(Dispatchers.IO) {
        val session = auth.session() ?: return@withContext false
        val request = Request.Builder()
            .url("https://$HOST/huami.health.scale.familymember.get.json")
            .post(FormBody.Builder().add("fuid", "all").add("userid", session.userId).build())
            .header("apptoken", session.appToken)
            .build()
        try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: IOException) {
            AppLog.w("ZeppApiClient", "Не удалось проверить сессию Zepp Life", e)
            false
        }
    }

    private fun get(url: HttpUrl, session: ZeppSession): GarminFetch<JSONObject> {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("apptoken", session.appToken)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.code == 401 || response.code == 403 -> {
                        AppLog.w("ZeppApiClient", "${url.encodedPath} - HTTP ${response.code}: сессия Zepp Life недействительна")
                        GarminFetch.Failed("сессия Zepp Life истекла - войдите заново", isNetwork = false)
                    }
                    !response.isSuccessful -> {
                        AppLog.w("ZeppApiClient", "${url.encodedPath} - HTTP ${response.code}: ${text.take(200)}")
                        GarminFetch.Failed("HTTP ${response.code}", isNetwork = false)
                    }
                    text.isBlank() -> GarminFetch.NoData
                    else -> GarminFetch.Ok(JSONObject(text))
                }
            }
        } catch (e: IOException) {
            AppLog.w("ZeppApiClient", "Не удалось прочитать ${url.encodedPath}", e)
            GarminFetch.Failed(e.message ?: e.javaClass.simpleName, isNetwork = true)
        } catch (e: Exception) {
            AppLog.w("ZeppApiClient", "Не удалось разобрать ${url.encodedPath}", e)
            GarminFetch.Failed(e.message ?: e.javaClass.simpleName, isNetwork = false)
        }
    }

    private fun JSONObject.float(key: String): Float =
        if (has(key) && !isNull(key)) optDouble(key, 0.0).toFloat().coerceAtLeast(0f) else 0f

    private companion object {
        const val HOST = "api-mifit.zepp.com"
        const val PAGE_SIZE = 200
        /** 50 x 200 = 10 000 weigh-ins - decades of daily use. */
        const val MAX_PAGES = 50
    }
}
