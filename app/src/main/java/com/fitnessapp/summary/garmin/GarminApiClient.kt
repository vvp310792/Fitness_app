package com.fitnessapp.summary.garmin

import com.fitnessapp.summary.data.GarminActivity
import com.fitnessapp.summary.data.GarminBodyComposition
import com.fitnessapp.summary.data.GarminDailyExtra
import com.fitnessapp.summary.data.GarminHrv
import com.fitnessapp.summary.data.GarminReadiness
import com.fitnessapp.summary.data.GarminSleep
import com.fitnessapp.summary.data.GarminTraining
import com.fitnessapp.summary.debug.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Seconds per stress zone for one day - see [GarminApiClient.stressZones]. */
data class StressZones(
    val restSeconds: Int,
    val lowSeconds: Int,
    val mediumSeconds: Int,
    val highSeconds: Int
)

/**
 * Reads data straight from Garmin Connect's own (unofficial, undocumented) mobile API,
 * authenticated via [GarminAuthClient]. Every endpoint path and JSON key here is taken
 * from garth's data classes (https://github.com/matin/garth, the `src/garth/data` and
 * `src/garth/stats` packages) - the same grounding as the auth flow in [GarminAuthClient], and
 * for the same reason: this protocol has no documentation and changes without notice,
 * so guessing a key name just means a silently empty field.
 *
 * garth names fields in snake_case via a fixed regex
 * (`((?<=[a-z0-9])[A-Z]|(?!^)[A-Z](?=[a-z])|(?<=[a-zA-Z])[0-9])` -> `_`), so the
 * camelCase JSON key behind each garth field is recoverable: `vo_2_max` <- `vo2Max`,
 * `last_night_5_min_high` <- `lastNight5MinHigh`, `average_spo_2` <- `averageSpo2`. Where
 * that inversion is ambiguous (the sleep SpO2 keys) both plausible spellings are tried.
 *
 * Every public function is a `suspend fun` wrapped in `withContext(Dispatchers.IO)`,
 * because the calls below are blocking `OkHttpClient.execute()`, not OkHttp's async API -
 * without this, calling from a main-dispatched scope throws NetworkOnMainThreadException.
 * Each returns null (or an empty collection) when Garmin has nothing - a 204, an empty
 * body, or a 404 for a feature the user's watch doesn't have - and logs anything else.
 */
class GarminApiClient(private val auth: GarminAuthClient) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // ---- Per-day endpoints ------------------------------------------------------

    /** garth `DailySummary`: `usersummary-service/usersummary/daily/?calendarDate=`. */
    suspend fun dailySummary(date: LocalDate): GarminDailyExtra? = withContext(Dispatchers.IO) {
        val json = getObject("usersummary-service/usersummary/daily/", mapOf("calendarDate" to date.toString()))
            ?: return@withContext null
        GarminDailyExtra(
            dateEpochDay = date.toEpochDay(),
            averageStressLevel = json.int("averageStressLevel"),
            maxStressLevel = json.int("maxStressLevel"),
            bodyBatteryAtWake = json.int("bodyBatteryAtWakeTime"),
            bodyBatteryHighest = json.int("bodyBatteryHighestValue"),
            bodyBatteryLowest = json.int("bodyBatteryLowestValue"),
            stressQualifier = json.str("stressQualifier"),
            totalSteps = json.optLong("totalSteps", 0L).coerceAtLeast(0L),
            totalDistanceMeters = json.int("totalDistanceMeters"),
            totalKilocalories = json.int("totalKilocalories"),
            activeKilocalories = json.int("activeKilocalories"),
            floorsAscended = Math.round(json.optDouble("floorsAscended", 0.0)).toInt().coerceAtLeast(0),
            floorsDescended = Math.round(json.optDouble("floorsDescended", 0.0)).toInt().coerceAtLeast(0),
            moderateIntensityMinutes = json.int("moderateIntensityMinutes"),
            vigorousIntensityMinutes = json.int("vigorousIntensityMinutes"),
            activeSeconds = json.int("activeSeconds"),
            highlyActiveSeconds = json.int("highlyActiveSeconds"),
            sedentarySeconds = json.int("sedentarySeconds"),
            sleepingSeconds = json.int("sleepingSeconds"),
            restingHeartRate = json.int("restingHeartRate"),
            minHeartRate = json.int("minHeartRate"),
            maxHeartRate = json.int("maxHeartRate"),
            lastSevenDaysAvgRestingHeartRate = json.int("lastSevenDaysAvgRestingHeartRate"),
            averageSpo2 = json.int("averageSpo2"),
            lowestSpo2 = json.int("lowestSpo2"),
            avgWakingRespiration = json.int("avgWakingRespirationValue"),
            highestRespiration = json.int("highestRespirationValue"),
            lowestRespiration = json.int("lowestRespirationValue")
        )
    }

    /**
     * garth `DailySleepData`: `sleep-service/sleep/dailySleepData?date=`. Chosen over the
     * older `wellness-service/.../dailySleepData/{username}` variant garth also has because
     * it needs no username and additionally returns Sleep Need, the overnight SpO2 summary,
     * skin temperature and the night's Body Battery change.
     */
    suspend fun sleep(date: LocalDate): GarminSleep? = withContext(Dispatchers.IO) {
        val root = getObject("sleep-service/sleep/dailySleepData", mapOf("date" to date.toString()))
            ?: return@withContext null
        val dto = root.optJSONObject("dailySleepDTO") ?: return@withContext null
        val scores = dto.optJSONObject("sleepScores")
        val need = dto.optJSONObject("sleepNeed")
        val hasSkinTemp = root.optBoolean("skinTempDataExists", false) && root.has("avgSkinTempDeviationC")

        GarminSleep(
            dateEpochDay = date.toEpochDay(),
            sleepSeconds = dto.int("sleepTimeSeconds"),
            napSeconds = dto.int("napTimeSeconds"),
            deepSeconds = dto.int("deepSleepSeconds"),
            lightSeconds = dto.int("lightSleepSeconds"),
            remSeconds = dto.int("remSleepSeconds"),
            awakeSeconds = dto.int("awakeSleepSeconds"),
            unmeasurableSeconds = dto.int("unmeasurableSleepSeconds"),
            awakeCount = dto.int("awakeCount"),
            sleepStartLocalMillis = dto.optLong("sleepStartTimestampLocal", 0L),
            sleepEndLocalMillis = dto.optLong("sleepEndTimestampLocal", 0L),
            score = scores?.optJSONObject("overall")?.int("value") ?: 0,
            scoreQualifier = scores.qualifier("overall"),
            durationQualifier = scores.qualifier("totalDuration"),
            stressQualifier = scores.qualifier("stress"),
            awakeCountQualifier = scores.qualifier("awakeCount"),
            remQualifier = scores.qualifier("remPercentage"),
            restlessnessQualifier = scores.qualifier("restlessness"),
            lightQualifier = scores.qualifier("lightPercentage"),
            deepQualifier = scores.qualifier("deepPercentage"),
            feedback = dto.str("sleepScoreFeedback"),
            insight = dto.str("sleepScoreInsight"),
            needBaselineMinutes = need?.int("baseline") ?: 0,
            needActualMinutes = need?.int("actual") ?: 0,
            needFeedback = need?.str("feedback").orEmpty(),
            avgSpo2 = dto.float("averageSpO2Value", "averageSpo2Value"),
            lowestSpo2 = dto.optInt("lowestSpO2Value", dto.optInt("lowestSpo2Value", 0)).coerceAtLeast(0),
            avgRespiration = dto.float("averageRespirationValue"),
            avgSleepStress = dto.float("avgSleepStress"),
            restingHeartRate = root.int("restingHeartRate"),
            bodyBatteryChange = root.optInt("bodyBatteryChange", 0),
            skinTempDeviationC = if (hasSkinTemp) root.optDouble("avgSkinTempDeviationC", 0.0).toFloat() else 0f,
            hasSkinTemp = hasSkinTemp
        )
    }

    /** garth `HRVData`: `hrv-service/hrv/{date}`; only `hrvSummary` is kept, the per-reading series isn't. */
    suspend fun hrv(date: LocalDate): GarminHrv? = withContext(Dispatchers.IO) {
        val root = getObject("hrv-service/hrv/$date") ?: return@withContext null
        val summary = root.optJSONObject("hrvSummary") ?: return@withContext null
        val baseline = summary.optJSONObject("baseline")
        GarminHrv(
            dateEpochDay = date.toEpochDay(),
            weeklyAvg = summary.int("weeklyAvg"),
            lastNightAvg = summary.int("lastNightAvg"),
            lastNight5MinHigh = summary.int("lastNight5MinHigh"),
            status = summary.str("status"),
            feedbackPhrase = summary.str("feedbackPhrase"),
            baselineLowUpper = baseline?.int("lowUpper") ?: 0,
            baselineBalancedLow = baseline?.int("balancedLow") ?: 0,
            baselineBalancedUpper = baseline?.int("balancedUpper") ?: 0,
            baselineMarkerValue = baseline?.float("markerValue") ?: 0f
        )
    }

    /**
     * garth `TrainingReadinessData`: `metrics-service/metrics/trainingreadiness/{date}`.
     * Returns a LIST - the score is recomputed through the day - so the newest entry by
     * `timestamp` wins.
     */
    suspend fun readiness(date: LocalDate): GarminReadiness? = withContext(Dispatchers.IO) {
        val array = getArray("metrics-service/metrics/trainingreadiness/$date") ?: return@withContext null
        val latest = array.objects().maxByOrNull { it.str("timestamp") } ?: return@withContext null
        GarminReadiness(
            dateEpochDay = date.toEpochDay(),
            score = latest.int("score"),
            level = latest.str("level"),
            feedbackShort = latest.str("feedbackShort"),
            feedbackLong = latest.str("feedbackLong"),
            timestampLocal = latest.str("timestampLocal"),
            sleepScore = latest.int("sleepScore"),
            sleepScoreFactorPercent = latest.int("sleepScoreFactorPercent"),
            sleepScoreFactorFeedback = latest.str("sleepScoreFactorFeedback"),
            recoveryTimeHours = latest.float("recoveryTime"),
            recoveryTimeFactorPercent = latest.int("recoveryTimeFactorPercent"),
            recoveryTimeFactorFeedback = latest.str("recoveryTimeFactorFeedback"),
            acwrFactorPercent = latest.int("acwrFactorPercent"),
            acwrFactorFeedback = latest.str("acwrFactorFeedback"),
            acuteLoad = latest.int("acuteLoad"),
            stressHistoryFactorPercent = latest.int("stressHistoryFactorPercent"),
            stressHistoryFactorFeedback = latest.str("stressHistoryFactorFeedback"),
            hrvFactorPercent = latest.int("hrvFactorPercent"),
            hrvFactorFeedback = latest.str("hrvFactorFeedback"),
            hrvWeeklyAverage = latest.int("hrvWeeklyAverage"),
            sleepHistoryFactorPercent = latest.int("sleepHistoryFactorPercent"),
            sleepHistoryFactorFeedback = latest.str("sleepHistoryFactorFeedback")
        )
    }

    /**
     * Three endpoints merged into one row, all "where is my training at":
     * - garth `DailyTrainingStatus`: `mobile-gateway/usersummary/trainingstatus/latest/{date}`,
     *   read at `mostRecentTrainingStatus.payload.latestTrainingStatusData.<deviceId>` with
     *   `acuteTrainingLoadDTO` flattened in - exactly garth's `_parse_response` walk;
     * - garth `GarminScoresData`: `metrics-service/metrics/hillscore` and `.../endurancescore`,
     *   both `?calendarDate=`. VO2max rides along on the hill score response (`vo2Max`).
     * Each of the three is independently optional - a user without an endurance-capable
     * watch still gets their training status.
     */
    suspend fun training(date: LocalDate): GarminTraining? = withContext(Dispatchers.IO) {
        var result = GarminTraining(dateEpochDay = date.toEpochDay())

        runCatchingSection("статус тренировок") {
            val root = getObject("mobile-gateway/usersummary/trainingstatus/latest/$date")
            val recent = root?.optJSONObject("mostRecentTrainingStatus")
            val payload = recent?.optJSONObject("payload") ?: recent
            val latest = payload?.optJSONObject("latestTrainingStatusData")
            val device = latest?.let { data -> data.keys().asSequence().mapNotNull { data.optJSONObject(it) }.firstOrNull() }
            if (device != null) {
                val acute = device.optJSONObject("acuteTrainingLoadDTO")
                result = result.copy(
                    trainingStatus = device.int("trainingStatus"),
                    statusFeedbackPhrase = device.str("trainingStatusFeedbackPhrase"),
                    weeklyTrainingLoad = device.int("weeklyTrainingLoad"),
                    loadTunnelMin = device.int("loadTunnelMin"),
                    loadTunnelMax = device.int("loadTunnelMax"),
                    fitnessTrend = device.optInt("fitnessTrend", 0),
                    trainingPaused = device.optBoolean("trainingPaused", false),
                    acwrPercent = acute?.int("acwrPercent") ?: 0,
                    acwrStatus = acute?.str("acwrStatus").orEmpty(),
                    acwrStatusFeedback = acute?.str("acwrStatusFeedback").orEmpty(),
                    dailyTrainingLoadAcute = acute?.int("dailyTrainingLoadAcute") ?: 0,
                    dailyTrainingLoadChronic = acute?.int("dailyTrainingLoadChronic") ?: 0,
                    acuteChronicRatio = acute?.float("dailyAcuteChronicWorkloadRatio") ?: 0f
                )
            }
        }

        runCatchingSection("hill score / VO2max") {
            getObject("metrics-service/metrics/hillscore", mapOf("calendarDate" to date.toString()))?.let { hill ->
                result = result.copy(
                    hillScore = hill.int("overallScore"),
                    hillEnduranceScore = hill.int("enduranceScore"),
                    hillStrengthScore = hill.int("strengthScore"),
                    vo2Max = hill.float("vo2Max"),
                    vo2MaxPrecise = hill.float("vo2MaxPreciseValue")
                )
            }
        }

        runCatchingSection("endurance score") {
            getObject("metrics-service/metrics/endurancescore", mapOf("calendarDate" to date.toString()))?.let { endurance ->
                result = result.copy(
                    enduranceScore = endurance.int("overallScore"),
                    enduranceClassification = endurance.int("classification")
                )
            }
        }

        if (result.isEmpty) null else result
    }

    // ---- Range endpoints ----------------------------------------------------------

    /**
     * garth `DailyStress`: `usersummary-service/stats/stress/daily/{start}/{end}`, page
     * size 28 days. Keyed by epoch day. Durations arrive in seconds.
     */
    suspend fun stressZones(from: LocalDate, to: LocalDate): Map<Long, StressZones> = withContext(Dispatchers.IO) {
        val out = mutableMapOf<Long, StressZones>()
        forEachStatsItem("usersummary-service/stats/stress/daily", from, to, pageDays = 28) { epochDay, item ->
            out[epochDay] = StressZones(
                restSeconds = item.int("restStressDuration"),
                lowSeconds = item.int("lowStressDuration"),
                mediumSeconds = item.int("mediumStressDuration"),
                highSeconds = item.int("highStressDuration")
            )
        }
        out
    }

    /** garth `DailyIntensityMinutes`: `usersummary-service/stats/im/daily/{start}/{end}` - only `weeklyGoal` is needed here. */
    suspend fun intensityMinutesGoal(from: LocalDate, to: LocalDate): Map<Long, Int> = withContext(Dispatchers.IO) {
        val out = mutableMapOf<Long, Int>()
        forEachStatsItem("usersummary-service/stats/im/daily", from, to, pageDays = 28) { epochDay, item ->
            val goal = item.int("weeklyGoal")
            if (goal > 0) out[epochDay] = goal
        }
        out
    }

    /** garth `DailyHydration`: `usersummary-service/stats/hydration/daily/{start}/{end}` -> (valueInMl, goalInMl). */
    suspend fun hydration(from: LocalDate, to: LocalDate): Map<Long, Pair<Int, Int>> = withContext(Dispatchers.IO) {
        val out = mutableMapOf<Long, Pair<Int, Int>>()
        forEachStatsItem("usersummary-service/stats/hydration/daily", from, to, pageDays = 28) { epochDay, item ->
            val value = Math.round(item.optDouble("valueInMl", 0.0)).toInt()
            val goal = Math.round(item.optDouble("goalInMl", 0.0)).toInt()
            if (value > 0 || goal > 0) out[epochDay] = value.coerceAtLeast(0) to goal.coerceAtLeast(0)
        }
        out
    }

    /**
     * garth `WeightData.list`: `weight-service/weight/range/{start}/{end}?includeAll=true`.
     * The response nests each day's weigh-ins under `dailyWeightSummaries[].allWeightMetrics[]`;
     * the latest measurement per day wins.
     */
    suspend fun bodyComposition(from: LocalDate, to: LocalDate): List<GarminBodyComposition> = withContext(Dispatchers.IO) {
        val root = getObject("weight-service/weight/range/$from/$to", mapOf("includeAll" to "true"))
            ?: return@withContext emptyList()
        val byDay = mutableMapOf<Long, GarminBodyComposition>()
        val days = root.optJSONArray("dailyWeightSummaries") ?: JSONArray()
        for (daySummary in days.objects()) {
            val metrics = daySummary.optJSONArray("allWeightMetrics")?.objects().orEmpty()
            val latest = metrics.maxByOrNull { it.optLong("timestampGMT", 0L) } ?: continue
            val date = parseDate(daySummary.str("summaryDate").ifBlank { latest.str("calendarDate") }) ?: continue
            byDay[date.toEpochDay()] = GarminBodyComposition(
                dateEpochDay = date.toEpochDay(),
                weightGrams = latest.int("weight"),
                bmi = latest.float("bmi"),
                bodyFatPercent = latest.float("bodyFat"),
                bodyWaterPercent = latest.float("bodyWater"),
                boneMassGrams = latest.int("boneMass"),
                muscleMassGrams = latest.int("muscleMass"),
                visceralFat = latest.float("visceralFat"),
                metabolicAge = latest.int("metabolicAge"),
                sourceType = latest.str("sourceType")
            )
        }
        byDay.values.toList()
    }

    /**
     * garth `Activity.list`: `activitylist-service/activities/search/activities?limit=&start=`,
     * newest first, paged until the page runs older than [from]; then each activity in
     * range is enriched from `activity-service/activity/{id}` (`summaryDTO`, garth
     * `Summary`) for Training Effect, load, power and cadence - the fields the list
     * endpoint doesn't carry under garth-verified names.
     */
    suspend fun activities(from: LocalDate, to: LocalDate): List<GarminActivity> = withContext(Dispatchers.IO) {
        val fromEpoch = from.toEpochDay()
        val toEpoch = to.toEpochDay()
        val collected = mutableListOf<GarminActivity>()
        var start = 0
        var pages = 0
        while (pages < MAX_ACTIVITY_PAGES) {
            val page = getArray(
                "activitylist-service/activities/search/activities",
                mapOf("limit" to ACTIVITY_PAGE_SIZE.toString(), "start" to start.toString())
            )?.objects().orEmpty()
            if (page.isEmpty()) break

            var sawOlder = false
            for (item in page) {
                val startLocal = parseDateTime(item.str("startTimeLocal")) ?: continue
                val epochDay = startLocal.toLocalDate().toEpochDay()
                if (epochDay < fromEpoch) {
                    sawOlder = true
                    continue
                }
                if (epochDay > toEpoch) continue
                collected += parseActivity(item, epochDay)
            }
            if (sawOlder || page.size < ACTIVITY_PAGE_SIZE) break
            start += ACTIVITY_PAGE_SIZE
            pages++
        }

        collected.map { activity ->
            runCatchingSection("детали тренировки ${activity.activityId}") {
                enrichActivity(activity)
            } ?: activity
        }
    }

    private fun parseActivity(item: JSONObject, epochDay: Long): GarminActivity {
        val startGmt = parseDateTime(item.str("startTimeGMT"))
        val startMillis = startGmt?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
            ?: parseDateTime(item.str("startTimeLocal"))?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            ?: 0L
        return GarminActivity(
            activityId = item.optLong("activityId", 0L),
            dateEpochDay = epochDay,
            startTimeMillis = startMillis,
            name = item.str("activityName"),
            typeKey = item.optJSONObject("activityType")?.str("typeKey").orEmpty(),
            locationName = item.str("locationName"),
            durationSeconds = Math.round(item.optDouble("duration", 0.0)).toInt().coerceAtLeast(0),
            distanceMeters = Math.round(item.optDouble("distance", 0.0)).toInt().coerceAtLeast(0),
            calories = Math.round(item.optDouble("calories", 0.0)).toInt().coerceAtLeast(0),
            avgHeartRate = Math.round(item.optDouble("averageHR", 0.0)).toInt().coerceAtLeast(0),
            maxHeartRate = Math.round(item.optDouble("maxHR", 0.0)).toInt().coerceAtLeast(0),
            steps = item.int("steps"),
            elevationGainMeters = Math.round(item.optDouble("elevationGain", 0.0)).toInt().coerceAtLeast(0),
            avgSpeedMetersPerSecond = item.float("averageSpeed"),
            avgRunCadence = Math.round(item.optDouble("averageRunningCadenceInStepsPerMinute", 0.0)).toInt().coerceAtLeast(0)
        )
    }

    private suspend fun enrichActivity(activity: GarminActivity): GarminActivity {
        val root = getObject("activity-service/activity/${activity.activityId}") ?: return activity
        val summary = root.optJSONObject("summaryDTO") ?: return activity
        return activity.copy(
            aerobicTrainingEffect = summary.float("trainingEffect"),
            anaerobicTrainingEffect = summary.float("anaerobicTrainingEffect"),
            trainingEffectLabel = summary.str("trainingEffectLabel"),
            activityTrainingLoad = summary.float("activityTrainingLoad"),
            avgRunCadence = if (activity.avgRunCadence > 0) activity.avgRunCadence else Math.round(summary.optDouble("averageRunCadence", 0.0)).toInt().coerceAtLeast(0),
            avgPower = Math.round(summary.optDouble("averagePower", 0.0)).toInt().coerceAtLeast(0),
            normalizedPower = Math.round(summary.optDouble("normalizedPower", 0.0)).toInt().coerceAtLeast(0),
            moderateIntensityMinutes = Math.round(summary.optDouble("moderateIntensityMinutes", 0.0)).toInt().coerceAtLeast(0),
            vigorousIntensityMinutes = Math.round(summary.optDouble("vigorousIntensityMinutes", 0.0)).toInt().coerceAtLeast(0),
            bodyBatteryDiff = Math.round(summary.optDouble("differenceBodyBattery", 0.0)).toInt(),
            steps = if (activity.steps > 0) activity.steps else summary.int("steps")
        )
    }

    // ---- Transport ------------------------------------------------------------------

    /**
     * garth's `Stats.list` shape: `{path}/{start}/{end}`, split into pages of [pageDays],
     * each item either flat `{calendarDate, ...}` or `{calendarDate, values: {...}}` -
     * both handled, exactly as garth's `_parse_response` does.
     */
    private suspend fun forEachStatsItem(
        path: String,
        from: LocalDate,
        to: LocalDate,
        pageDays: Int,
        onItem: (epochDay: Long, item: JSONObject) -> Unit
    ) {
        var pageEnd = to
        while (!pageEnd.isBefore(from)) {
            val pageStart = maxOf(from, pageEnd.minusDays((pageDays - 1).toLong()))
            val array = getArray("$path/$pageStart/$pageEnd")
            array?.objects()?.forEach { raw ->
                val date = parseDate(raw.str("calendarDate")) ?: return@forEach
                val item = raw.optJSONObject("values") ?: raw
                onItem(date.toEpochDay(), item)
            }
            pageEnd = pageStart.minusDays(1)
        }
    }

    private suspend fun getObject(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        val text = getText(path, params) ?: return null
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            AppLog.w("GarminApiClient", "Ответ $path не является JSON-объектом", e)
            null
        }
    }

    private suspend fun getArray(path: String, params: Map<String, String> = emptyMap()): JSONArray? {
        val text = getText(path, params) ?: return null
        return try {
            JSONArray(text)
        } catch (e: Exception) {
            AppLog.w("GarminApiClient", "Ответ $path не является JSON-массивом", e)
            null
        }
    }

    /**
     * One authenticated GET. Null for "Garmin has nothing here" - 204, an empty body, or a
     * 404 (Garmin uses 404 for features a device never produced, not just bad URLs) - with
     * everything else logged at WARN with the status so a protocol change shows up in the
     * log as a specific endpoint, not a generic empty screen.
     */
    private suspend fun getText(path: String, params: Map<String, String>): String? {
        val authHeader = auth.ensureAuthorizationHeader() ?: run {
            AppLog.w("GarminApiClient", "Нет действующего токена Garmin - не вошли")
            return null
        }

        val urlBuilder = HttpUrl.Builder()
            .scheme("https").host("connectapi.$DOMAIN")
            .addPathSegments(path)
        params.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .header("User-Agent", OAUTH_USER_AGENT)
            .header("Authorization", authHeader)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.code == 204 || response.code == 404 -> null
                    !response.isSuccessful -> {
                        AppLog.w("GarminApiClient", "$path - HTTP ${response.code}: ${text.take(200)}")
                        null
                    }
                    text.isBlank() -> null
                    else -> text
                }
            }
        } catch (e: Exception) {
            AppLog.w("GarminApiClient", "Не удалось прочитать $path", e)
            null
        }
    }

    private inline fun <T> runCatchingSection(name: String, block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            AppLog.w("GarminApiClient", "Не удалось разобрать: $name", e)
            null
        }

    // ---- JSON / date helpers ---------------------------------------------------------

    private fun JSONObject.int(key: String): Int = optInt(key, 0).coerceAtLeast(0)
    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")
    private fun JSONObject.float(vararg keys: String): Float {
        for (key in keys) {
            if (has(key) && !isNull(key)) {
                val value = optDouble(key, Double.NaN)
                if (!value.isNaN()) return value.toFloat()
            }
        }
        return 0f
    }
    private fun JSONObject?.qualifier(component: String): String =
        this?.optJSONObject(component)?.str("qualifierKey").orEmpty()
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    private fun parseDate(text: String): LocalDate? =
        try { LocalDate.parse(text.take(10)) } catch (e: Exception) { null }

    /** "2026-09-07 06:12:00" or "2026-09-07T06:12:00.0" - Garmin uses both. */
    private fun parseDateTime(text: String): LocalDateTime? {
        if (text.length < 19) return null
        return try {
            LocalDateTime.parse(text.take(19).replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val DOMAIN = "garmin.com"
        const val OAUTH_USER_AGENT = "com.garmin.android.apps.connectmobile"
        const val ACTIVITY_PAGE_SIZE = 50
        /** 20 pages x 50 = 1000 activities back, far more than a 90-day backfill can need. */
        const val MAX_ACTIVITY_PAGES = 20
    }
}
