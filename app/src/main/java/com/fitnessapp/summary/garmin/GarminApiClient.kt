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
import java.io.IOException
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
 * `src/garth/stats` packages) - the same grounding as the auth flow in [GarminAuthClient],
 * and for the same reason: this protocol has no documentation and changes without notice,
 * so guessing a key name just means a silently empty field.
 *
 * garth names fields in snake_case via a fixed regex
 * (`((?<=[a-z0-9])[A-Z]|(?!^)[A-Z](?=[a-z])|(?<=[a-zA-Z])[0-9])` -> `_`), so the
 * camelCase JSON key behind each garth field is recoverable: `vo_2_max` <- `vo2Max`,
 * `last_night_5_min_high` <- `lastNight5MinHigh`, `average_spo_2` <- `averageSpo2`. Where
 * that inversion is ambiguous (the sleep SpO2 keys) both plausible spellings are tried.
 *
 * **The User-Agent matters.** garth puts `GCM-iOS-5.22.1.4` on its session, so every data
 * call carries it, and uses `com.garmin.android.apps.connectmobile` ONLY for the OAuth
 * exchange. Sending the Android OAuth agent on data calls instead got 204/404 back from
 * exactly the newer recovery endpoints - sleep, HRV, training readiness - while the older
 * ones (daily summary, training status, weight, activities) answered normally. Garmin
 * evidently gates those by client, so this must stay matched to garth.
 *
 * Every public function is a `suspend fun` wrapped in `withContext(Dispatchers.IO)`,
 * because the calls below are blocking `OkHttpClient.execute()`, not OkHttp's async API -
 * without this, calling from a main-dispatched scope throws NetworkOnMainThreadException.
 * All of them return a three-way [GarminFetch] rather than a nullable: see that file for
 * why "Garmin has nothing" and "the call failed" must not look alike.
 */
class GarminApiClient(private val auth: GarminAuthClient) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Garmin's internal user name, needed only by the fallback sleep endpoint. */
    private var cachedUserName: String? = null

    // ---- Per-day endpoints ------------------------------------------------------

    /** garth `DailySummary`: `usersummary-service/usersummary/daily/?calendarDate=`. */
    suspend fun dailySummary(date: LocalDate): GarminFetch<GarminDailyExtra> = withContext(Dispatchers.IO) {
        fetchObject("usersummary-service/usersummary/daily/", mapOf("calendarDate" to date.toString()))
            .parse("сводка дня $date") { json ->
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
                    floorsAscended = json.rounded("floorsAscended"),
                    floorsDescended = json.rounded("floorsDescended"),
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
                    avgWakingRespiration = json.rounded("avgWakingRespirationValue"),
                    highestRespiration = json.rounded("highestRespirationValue"),
                    lowestRespiration = json.rounded("lowestRespirationValue")
                )
            }
    }

    /**
     * Sleep, from garth's two sleep endpoints in order:
     * 1. `sleep-service/sleep/dailySleepData?date=` (garth `DailySleepData`) - preferred,
     *    needs no user name and additionally carries Sleep Need, skin temperature and the
     *    night's Body Battery change;
     * 2. `wellness-service/wellness/dailySleepData/{userName}?nonSleepBufferMinutes=60&date=`
     *    (garth `SleepData`) - the older shape, tried when the first returns nothing.
     *
     * Both wrap the night in the same `dailySleepDTO` object, so one parser reads either.
     */
    suspend fun sleep(date: LocalDate): GarminFetch<GarminSleep> = withContext(Dispatchers.IO) {
        val primary = fetchObject("sleep-service/sleep/dailySleepData", mapOf("date" to date.toString()))
        if (primary is GarminFetch.Failed) return@withContext primary
        val fromPrimary = (primary as? GarminFetch.Ok)?.value?.let { root ->
            root.optJSONObject("dailySleepDTO")?.let { dto -> parseSleep(date, root, dto) }
        }
        if (fromPrimary != null && !fromPrimary.isEmpty) return@withContext GarminFetch.Ok(fromPrimary)

        // The condition for falling back is "nothing usable came back", not "the response
        // was missing its DTO". This endpoint answers 200 with a present-but-hollow
        // dailySleepDTO - no sleepTimeSeconds, no score - and an earlier version treated
        // that as a real, empty night and never tried the second endpoint at all.
        val user = userName() ?: return@withContext GarminFetch.NoData
        AppLog.d("GarminApiClient", "Сон за $date: основной эндпоинт пуст, пробую wellness-service")
        val fallback = fetchObject(
            "wellness-service/wellness/dailySleepData/$user",
            mapOf("nonSleepBufferMinutes" to "60", "date" to date.toString())
        )
        if (fallback is GarminFetch.Failed) return@withContext fallback
        val fromFallback = (fallback as? GarminFetch.Ok)?.value?.let { root ->
            root.optJSONObject("dailySleepDTO")?.let { dto -> parseSleep(date, root, dto) }
        }
        if (fromFallback != null && !fromFallback.isEmpty) GarminFetch.Ok(fromFallback) else GarminFetch.NoData
    }

    private fun parseSleep(date: LocalDate, root: JSONObject, dto: JSONObject): GarminSleep {
        val scores = dto.optJSONObject("sleepScores")
        val need = dto.optJSONObject("sleepNeed")
        val hasSkinTemp = root.optBoolean("skinTempDataExists", false) && root.has("avgSkinTempDeviationC")
        return GarminSleep(
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
    suspend fun hrv(date: LocalDate): GarminFetch<GarminHrv> = withContext(Dispatchers.IO) {
        fetchObject("hrv-service/hrv/$date").parse("ВСР за $date") { root ->
            val summary = root.optJSONObject("hrvSummary") ?: return@parse null
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
    }

    /**
     * garth `TrainingReadinessData`: `metrics-service/metrics/trainingreadiness/{date}`.
     * Returns a LIST - the score is recomputed through the day - so the newest entry by
     * `timestamp` wins.
     */
    suspend fun readiness(date: LocalDate): GarminFetch<GarminReadiness> = withContext(Dispatchers.IO) {
        fetchArray("metrics-service/metrics/trainingreadiness/$date").parse("готовность за $date") { array ->
            val latest = array.objects().maxByOrNull { it.str("timestamp") } ?: return@parse null
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
    }

    /**
     * Three endpoints merged into one row, all "where is my training at":
     * - garth `DailyTrainingStatus`: `mobile-gateway/usersummary/trainingstatus/latest/{date}`,
     *   read at `mostRecentTrainingStatus.payload.latestTrainingStatusData.<deviceId>` with
     *   `acuteTrainingLoadDTO` flattened in - exactly garth's `_parse_response` walk;
     * - garth `GarminScoresData`: `metrics-service/metrics/hillscore` and `.../endurancescore`,
     *   both `?calendarDate=`. VO2max rides along on the hill score response (`vo2Max`).
     *
     * Each is independently optional - a user whose watch has no endurance score still gets
     * their training status - so the merged row is [GarminFetch.Ok] if any part answered,
     * [GarminFetch.NoData] if all three said "nothing", and Failed only on a real failure.
     */
    suspend fun training(date: LocalDate): GarminFetch<GarminTraining> = withContext(Dispatchers.IO) {
        var result = GarminTraining(dateEpochDay = date.toEpochDay())
        var answered = false

        when (val status = fetchObject("mobile-gateway/usersummary/trainingstatus/latest/$date")) {
            is GarminFetch.Failed -> return@withContext status
            GarminFetch.NoData -> Unit
            is GarminFetch.Ok -> {
                val recent = status.value.optJSONObject("mostRecentTrainingStatus")
                val payload = recent?.optJSONObject("payload") ?: recent
                val latest = payload?.optJSONObject("latestTrainingStatusData")
                val device = latest?.let { data ->
                    data.keys().asSequence().mapNotNull { data.optJSONObject(it) }.firstOrNull()
                }
                if (device != null) {
                    answered = true
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
        }

        // A network-level failure on either of these still has to reach the caller, or a
        // backfill that lost connectivity would keep looping instead of aborting - these
        // two are the last calls of the day and would otherwise swallow the signal.
        when (val hill = fetchObject("metrics-service/metrics/hillscore", mapOf("calendarDate" to date.toString()))) {
            is GarminFetch.Failed -> if (hill.isNetwork) return@withContext hill
            GarminFetch.NoData -> Unit
            is GarminFetch.Ok -> {
                answered = true
                result = result.copy(
                    hillScore = hill.value.int("overallScore"),
                    hillEnduranceScore = hill.value.int("enduranceScore"),
                    hillStrengthScore = hill.value.int("strengthScore"),
                    vo2Max = hill.value.float("vo2Max"),
                    vo2MaxPrecise = hill.value.float("vo2MaxPreciseValue")
                )
            }
        }

        when (val endurance = fetchObject("metrics-service/metrics/endurancescore", mapOf("calendarDate" to date.toString()))) {
            is GarminFetch.Failed -> if (endurance.isNetwork) return@withContext endurance
            GarminFetch.NoData -> Unit
            is GarminFetch.Ok -> {
                answered = true
                result = result.copy(
                    enduranceScore = endurance.value.int("overallScore"),
                    enduranceClassification = endurance.value.int("classification")
                )
            }
        }

        if (!answered || result.isEmpty) GarminFetch.NoData else GarminFetch.Ok(result)
    }

    // ---- Range endpoints ----------------------------------------------------------

    /**
     * garth `DailyStress`: `usersummary-service/stats/stress/daily/{start}/{end}`, page
     * size 28 days. Keyed by epoch day. Durations arrive in seconds.
     */
    suspend fun stressZones(from: LocalDate, to: LocalDate): GarminFetch<Map<Long, StressZones>> = withContext(Dispatchers.IO) {
        val out = mutableMapOf<Long, StressZones>()
        forEachStatsItem("usersummary-service/stats/stress/daily", from, to, pageDays = 28, out = out) { epochDay, item ->
            out[epochDay] = StressZones(
                restSeconds = item.int("restStressDuration"),
                lowSeconds = item.int("lowStressDuration"),
                mediumSeconds = item.int("mediumStressDuration"),
                highSeconds = item.int("highStressDuration")
            )
        }
    }

    /** garth `DailyIntensityMinutes`: `usersummary-service/stats/im/daily/{start}/{end}` - only `weeklyGoal` is needed here. */
    suspend fun intensityMinutesGoal(from: LocalDate, to: LocalDate): GarminFetch<Map<Long, Int>> = withContext(Dispatchers.IO) {
        val out = mutableMapOf<Long, Int>()
        forEachStatsItem("usersummary-service/stats/im/daily", from, to, pageDays = 28, out = out) { epochDay, item ->
            val goal = item.int("weeklyGoal")
            if (goal > 0) out[epochDay] = goal
        }
    }

    /** garth `DailyHydration`: `usersummary-service/stats/hydration/daily/{start}/{end}` -> (valueInMl, goalInMl). */
    suspend fun hydration(from: LocalDate, to: LocalDate): GarminFetch<Map<Long, Pair<Int, Int>>> = withContext(Dispatchers.IO) {
        val out = mutableMapOf<Long, Pair<Int, Int>>()
        forEachStatsItem("usersummary-service/stats/hydration/daily", from, to, pageDays = 28, out = out) { epochDay, item ->
            val value = Math.round(item.optDouble("valueInMl", 0.0)).toInt()
            val goal = Math.round(item.optDouble("goalInMl", 0.0)).toInt()
            if (value > 0 || goal > 0) out[epochDay] = value.coerceAtLeast(0) to goal.coerceAtLeast(0)
        }
    }

    /**
     * garth `WeightData.list`: `weight-service/weight/range/{start}/{end}?includeAll=true`.
     * The response nests each day's weigh-ins under `dailyWeightSummaries[].allWeightMetrics[]`;
     * the latest measurement per day wins.
     */
    suspend fun bodyComposition(from: LocalDate, to: LocalDate): GarminFetch<List<GarminBodyComposition>> = withContext(Dispatchers.IO) {
        fetchObject("weight-service/weight/range/$from/$to", mapOf("includeAll" to "true"))
            .parse("вес $from..$to") { root ->
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
                byDay.values.toList().takeIf { it.isNotEmpty() }
            }
    }

    /**
     * Instants (unix seconds) of every weigh-in Garmin already holds in the range - the
     * same `weight-service/weight/range` response as [bodyComposition], read for its
     * `allWeightMetrics[].timestampGMT` instead of its values. Used before pushing scale
     * measurements so a weigh-in that already reached Garmin (through this app earlier, or
     * another tool) is never uploaded twice: a FIT weight message carries its timestamp to
     * the second, so the second is the identity. SmartScaleConnect dedupes exactly this way.
     */
    suspend fun weightTimestampsSeconds(from: LocalDate, to: LocalDate): GarminFetch<Set<Long>> = withContext(Dispatchers.IO) {
        fetchObject("weight-service/weight/range/$from/$to", mapOf("includeAll" to "true"))
            .parse("метки веса $from..$to") { root ->
                val out = mutableSetOf<Long>()
                for (day in (root.optJSONArray("dailyWeightSummaries") ?: JSONArray()).objects()) {
                    for (metric in day.optJSONArray("allWeightMetrics")?.objects().orEmpty()) {
                        val millis = metric.optLong("timestampGMT", 0L).takeIf { it > 0 } ?: metric.optLong("date", 0L)
                        if (millis > 0) out += millis / 1000
                    }
                }
                out // an empty set is a real answer ("Garmin holds nothing here"), not NoData
            }
    }

    /**
     * garth `Activity.list`: `activitylist-service/activities/search/activities?limit=&start=`,
     * newest first, paged until the page runs older than [from]. Returns the list-level
     * fields only; Training Effect, load, power and cadence live behind a per-activity
     * detail call - see [activityDetail].
     */
    suspend fun activities(from: LocalDate, to: LocalDate): GarminFetch<List<GarminActivity>> = withContext(Dispatchers.IO) {
        val fromEpoch = from.toEpochDay()
        val toEpoch = to.toEpochDay()
        val collected = mutableListOf<GarminActivity>()
        var start = 0
        var pages = 0
        while (pages < MAX_ACTIVITY_PAGES) {
            val page = when (
                val fetch = fetchArray(
                    "activitylist-service/activities/search/activities",
                    mapOf("limit" to ACTIVITY_PAGE_SIZE.toString(), "start" to start.toString())
                )
            ) {
                is GarminFetch.Ok -> fetch.value.objects()
                is GarminFetch.Failed -> return@withContext fetch
                GarminFetch.NoData -> emptyList()
            }
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

        if (collected.isEmpty()) return@withContext GarminFetch.NoData
        GarminFetch.Ok(collected)
    }

    /**
     * The per-activity detail call, split out from [activities] so the caller can skip the
     * ones it already has (see [com.fitnessapp.summary.data.GarminActivity.detailsLoaded]).
     * A 90-day backfill re-run would otherwise repeat one request per activity every time,
     * which is most of its cost.
     */
    suspend fun activityDetail(activity: GarminActivity): GarminActivity = withContext(Dispatchers.IO) {
        enrichActivity(activity)
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
            durationSeconds = item.rounded("duration"),
            distanceMeters = item.rounded("distance"),
            calories = item.rounded("calories"),
            avgHeartRate = item.rounded("averageHR"),
            maxHeartRate = item.rounded("maxHR"),
            steps = item.int("steps"),
            elevationGainMeters = item.rounded("elevationGain"),
            avgSpeedMetersPerSecond = item.float("averageSpeed"),
            avgRunCadence = item.rounded("averageRunningCadenceInStepsPerMinute")
        )
    }

    /** Detail lookup per activity; a failure here leaves the list-level activity as it is. */
    private suspend fun enrichActivity(activity: GarminActivity): GarminActivity {
        val summary = fetchObject("activity-service/activity/${activity.activityId}")
            .valueOrNull()?.optJSONObject("summaryDTO") ?: return activity
        return activity.copy(
            detailsLoaded = true,
            aerobicTrainingEffect = summary.float("trainingEffect"),
            anaerobicTrainingEffect = summary.float("anaerobicTrainingEffect"),
            trainingEffectLabel = summary.str("trainingEffectLabel"),
            activityTrainingLoad = summary.float("activityTrainingLoad"),
            avgRunCadence = if (activity.avgRunCadence > 0) activity.avgRunCadence else summary.rounded("averageRunCadence"),
            avgPower = summary.rounded("averagePower"),
            normalizedPower = summary.rounded("normalizedPower"),
            moderateIntensityMinutes = summary.rounded("moderateIntensityMinutes"),
            vigorousIntensityMinutes = summary.rounded("vigorousIntensityMinutes"),
            bodyBatteryDiff = Math.round(summary.optDouble("differenceBodyBattery", 0.0)).toInt(),
            steps = if (activity.steps > 0) activity.steps else summary.int("steps")
        )
    }

    /** garth `Client.username`: `userprofile-service/socialProfile` -> `userName`. Cached for the session. */
    private suspend fun userName(): String? {
        cachedUserName?.let { return it }
        val profile = fetchObject("userprofile-service/socialProfile").valueOrNull() ?: return null
        val name = profile.str("userName").ifBlank { profile.str("displayName") }
        return name.ifBlank { null }?.also { cachedUserName = it }
    }

    // ---- Transport ------------------------------------------------------------------

    /**
     * garth's `Stats.list` shape: `{path}/{start}/{end}`, split into pages of [pageDays],
     * each item either flat `{calendarDate, ...}` or `{calendarDate, values: {...}}` -
     * both handled, exactly as garth's `_parse_response` does. A page that fails the
     * network aborts the whole range rather than returning a half-filled map that would
     * silently read as "Garmin has no stress data for those days".
     */
    private suspend fun <T> forEachStatsItem(
        path: String,
        from: LocalDate,
        to: LocalDate,
        pageDays: Int,
        out: Map<Long, T>,
        onItem: (epochDay: Long, item: JSONObject) -> Unit
    ): GarminFetch<Map<Long, T>> {
        var pageEnd = to
        while (!pageEnd.isBefore(from)) {
            val pageStart = maxOf(from, pageEnd.minusDays((pageDays - 1).toLong()))
            when (val fetch = fetchArray("$path/$pageStart/$pageEnd")) {
                is GarminFetch.Failed -> return fetch
                GarminFetch.NoData -> Unit
                is GarminFetch.Ok -> fetch.value.objects().forEach { raw ->
                    val date = parseDate(raw.str("calendarDate"))
                    if (date != null) onItem(date.toEpochDay(), raw.optJSONObject("values") ?: raw)
                }
            }
            pageEnd = pageStart.minusDays(1)
        }
        return if (out.isEmpty()) GarminFetch.NoData else GarminFetch.Ok(out)
    }

    private suspend fun fetchObject(path: String, params: Map<String, String> = emptyMap()): GarminFetch<JSONObject> =
        fetch(path, params).parse("JSON-объект $path") { JSONObject(it) }

    private suspend fun fetchArray(path: String, params: Map<String, String> = emptyMap()): GarminFetch<JSONArray> =
        fetch(path, params).parse("JSON-массив $path") { JSONArray(it) }

    /**
     * One authenticated GET.
     *
     * A 204, a 404 or an empty body is [GarminFetch.NoData] - Garmin uses 404 for a
     * feature the account's device never produced, not just for a wrong URL - and it's
     * logged at DEBUG rather than dropped silently. That trace is the whole point: an
     * empty screen with nothing in the log is indistinguishable from a request that was
     * never sent, which is exactly how the first version of this hid three dead endpoints
     * for a full release.
     */
    private suspend fun fetch(path: String, params: Map<String, String>): GarminFetch<String> {
        val authHeader = auth.ensureAuthorizationHeader()
            ?: return GarminFetch.Failed("нет действующего токена Garmin", isNetwork = false).also {
                AppLog.w("GarminApiClient", "Нет действующего токена Garmin - не вошли")
            }

        val urlBuilder = HttpUrl.Builder()
            .scheme("https").host("connectapi.$DOMAIN")
            .addPathSegments(path)
        params.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .header("User-Agent", DATA_USER_AGENT)
            .header("Authorization", authHeader)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.code == 204 || response.code == 404 -> {
                        AppLog.d("GarminApiClient", "$path - Garmin ответил ${response.code}: данных нет")
                        GarminFetch.NoData
                    }
                    !response.isSuccessful -> {
                        AppLog.w("GarminApiClient", "$path - HTTP ${response.code}: ${text.take(200)}")
                        GarminFetch.Failed("HTTP ${response.code}", isNetwork = false)
                    }
                    text.isBlank() -> {
                        AppLog.d("GarminApiClient", "$path - пустой ответ, данных нет")
                        GarminFetch.NoData
                    }
                    else -> GarminFetch.Ok(text)
                }
            }
        } catch (e: IOException) {
            AppLog.w("GarminApiClient", "Не удалось прочитать $path", e)
            GarminFetch.Failed(e.message ?: e.javaClass.simpleName, isNetwork = true)
        } catch (e: Exception) {
            AppLog.w("GarminApiClient", "Не удалось прочитать $path", e)
            GarminFetch.Failed(e.message ?: e.javaClass.simpleName, isNetwork = false)
        }
    }

    /**
     * Maps a successful fetch through [transform], turning a null result into
     * [GarminFetch.NoData] (the response arrived but held nothing usable) and an exception
     * into a logged [GarminFetch.Failed] - so a JSON shape change on Garmin's side is a
     * named failure in the log, not a crash and not a silent blank.
     */
    private inline fun <T, R> GarminFetch<T>.parse(section: String, transform: (T) -> R?): GarminFetch<R> =
        when (this) {
            GarminFetch.NoData -> GarminFetch.NoData
            is GarminFetch.Failed -> this
            is GarminFetch.Ok -> try {
                transform(value)?.let { GarminFetch.Ok(it) } ?: GarminFetch.NoData
            } catch (e: Exception) {
                AppLog.w("GarminApiClient", "Не удалось разобрать: $section", e)
                GarminFetch.Failed(e.message ?: e.javaClass.simpleName, isNetwork = false)
            }
        }

    // ---- JSON / date helpers ---------------------------------------------------------

    private fun JSONObject.int(key: String): Int = optInt(key, 0).coerceAtLeast(0)
    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")
    /** Garmin sends several of these as floats ("duration": 3600.0), so read as double then round. */
    private fun JSONObject.rounded(key: String): Int = Math.round(optDouble(key, 0.0)).toInt().coerceAtLeast(0)
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

    internal companion object {
        const val DOMAIN = "garmin.com"

        /**
         * garth's session-wide User-Agent, used on every data call. NOT the Android OAuth
         * agent - see the class comment: sending that one gets 204/404 from the newer
         * recovery endpoints (sleep, HRV, readiness) while the older ones still answer.
         */
        const val DATA_USER_AGENT = "GCM-iOS-5.22.1.4"
        const val ACTIVITY_PAGE_SIZE = 50
        /** 20 pages x 50 = 1000 activities back, far more than a 90-day backfill can need. */
        const val MAX_ACTIVITY_PAGES = 20
    }
}
