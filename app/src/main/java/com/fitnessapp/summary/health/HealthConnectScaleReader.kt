package com.fitnessapp.summary.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.fitnessapp.summary.data.ScaleMeasurement
import com.fitnessapp.summary.debug.AppLog
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.reflect.KClass

/**
 * Reads weigh-ins out of Health Connect and shapes them as [ScaleMeasurement] rows.
 *
 * This is the primary scale source. The chain the user actually has is
 * Mi Body Composition Scale -> Zepp Life -> Google Fit -> Health Connect, and Health
 * Connect is where this app already reads everything else, with no login, no cloud
 * protocol to keep in step with anyone, and no extra account. (Reading Google Fit's own
 * cloud instead is not an option: its REST API stopped accepting new apps in May 2024
 * and is being switched off in 2026.)
 *
 * Health Connect stores a weigh-in as several independent records - weight, body fat,
 * body water, bone mass, basal metabolic rate - that merely share (roughly) a timestamp.
 * Weight is the anchor: one [ScaleMeasurement] per [WeightRecord], and the other types
 * are attached when a record of theirs lies within [COMPANION_WINDOW] of it. Anything
 * the writing app didn't sync stays 0, which the rest of the app already reads as
 * "unknown" - Google Fit forwards weight and usually body fat from Zepp Life, and often
 * nothing else, so most rows here are thinner than a Zepp-cloud row would be.
 *
 * Height is different: it changes on the scale of years, so the latest height at or
 * before the weigh-in is used, and BMI is derived from it when present.
 */
class HealthConnectScaleReader(private val manager: HealthConnectManager) {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /**
     * Every weigh-in in [from, to). Null when Health Connect is unavailable or the weight
     * read itself failed (most likely: permission not granted) - distinct from an empty
     * list, which means "readable, but nothing there".
     */
    suspend fun readWeighIns(from: Instant, to: Instant): List<ScaleMeasurement>? {
        val client = manager.clientOrNull() ?: run {
            AppLog.w("HealthConnectScaleReader", "Health Connect недоступен, вес не читаю")
            return null
        }
        val range = TimeRangeFilter.between(from, to)

        val weights = readAll(client, WeightRecord::class, range, "вес") ?: return null
        if (weights.isEmpty()) {
            AppLog.d("HealthConnectScaleReader", "Взвешиваний в Health Connect за окно нет")
            return emptyList()
        }

        // Companion records: each read is guarded on its own, so a denied body-fat
        // permission (say) costs that field, not the weigh-in.
        val fat = readAll(client, BodyFatRecord::class, range, "жир") ?: emptyList()
        val water = readAll(client, BodyWaterMassRecord::class, range, "вода") ?: emptyList()
        val bone = readAll(client, BoneMassRecord::class, range, "костная масса") ?: emptyList()
        val bmr = readAll(client, BasalMetabolicRateRecord::class, range, "базовый обмен") ?: emptyList()
        val heights = readAll(
            client, HeightRecord::class,
            TimeRangeFilter.between(from.minus(HEIGHT_LOOKBACK), to), "рост"
        ) ?: emptyList()

        val rows = weights.map { w ->
            val weightKg = w.weight.inKilograms
            val heightM = heights.filter { !it.time.isAfter(w.time) }.maxByOrNull { it.time }?.height?.inMeters
                ?: heights.minByOrNull { it.time }?.height?.inMeters
            val waterKg = nearest(water, w.time)?.mass?.inKilograms
            ScaleMeasurement(
                timestampMillis = w.time.toEpochMilli(),
                dateEpochDay = w.time.atZone(zone).toLocalDate().toEpochDay(),
                weightGrams = (weightKg * 1000).toInt(),
                heightCm = heightM?.let { (it * 100).toFloat() } ?: 0f,
                bmi = if (heightM != null && heightM > 0) (weightKg / (heightM * heightM)).toFloat() else 0f,
                bodyFatPercent = nearest(fat, w.time)?.percentage?.value?.toFloat() ?: 0f,
                bodyWaterPercent = if (waterKg != null && weightKg > 0) (waterKg / weightKg * 100).toFloat() else 0f,
                boneMassGrams = nearest(bone, w.time)?.mass?.inGrams?.toInt() ?: 0,
                basalMetabolismKcal = nearest(bmr, w.time)?.basalMetabolicRate?.inKilocaloriesPerDay?.toInt() ?: 0,
                deviceId = w.metadata.dataOrigin.packageName,
                source = ScaleMeasurement.SOURCE_HEALTH_CONNECT
            )
        }.filter { it.weightGrams > 0 }

        // Presence only, never values - same rule as the rest of the log.
        AppLog.d(
            "HealthConnectScaleReader",
            "Взвешиваний=${rows.size} жир=${fat.isNotEmpty()} вода=${water.isNotEmpty()} " +
                "кости=${bone.isNotEmpty()} обмен=${bmr.isNotEmpty()} рост=${heights.isNotEmpty()} " +
                "источники=${rows.map { it.deviceId }.distinct()}"
        )
        return rows
    }

    /** Which apps have written weight into Health Connect - for the settings card's diagnosis line. */
    suspend fun weightWriters(since: Instant): List<String> {
        val client = manager.clientOrNull() ?: return emptyList()
        val weights = readAll(client, WeightRecord::class, TimeRangeFilter.between(since, Instant.now()), "вес") ?: return emptyList()
        return weights.map { it.metadata.dataOrigin.packageName }.distinct()
    }

    /**
     * Full paged read. Health Connect caps a single page (default 1000), and a year of
     * daily weigh-ins from a household can exceed it, so the page token is followed.
     */
    private suspend fun <T : Record> readAll(
        client: HealthConnectClient,
        type: KClass<T>,
        range: TimeRangeFilter,
        section: String
    ): List<T>? = try {
        val out = ArrayList<T>()
        var token: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = range,
                    pageSize = PAGE_SIZE,
                    pageToken = token
                )
            )
            out += response.records
            token = response.pageToken
        } while (token != null && out.size < HARD_CAP)
        out
    } catch (e: Exception) {
        AppLog.w("HealthConnectScaleReader", "Не удалось прочитать: $section", e)
        null
    }

    private fun <T : Record> nearest(records: List<T>, at: Instant): T? =
        records.minByOrNull { abs(Duration.between(timeOf(it), at).toMillis()) }
            ?.takeIf { abs(Duration.between(timeOf(it), at).toMillis()) <= COMPANION_WINDOW.toMillis() }

    private fun timeOf(record: Record): Instant = when (record) {
        is WeightRecord -> record.time
        is BodyFatRecord -> record.time
        is BodyWaterMassRecord -> record.time
        is BoneMassRecord -> record.time
        is BasalMetabolicRateRecord -> record.time
        is HeightRecord -> record.time
        else -> Instant.EPOCH
    }

    private companion object {
        /** How far apart two records may be and still describe the same step onto the scale. */
        val COMPANION_WINDOW: Duration = Duration.ofMinutes(10)

        /** Height is entered once and rarely updated; look well back for the latest value. */
        val HEIGHT_LOOKBACK: Duration = Duration.ofDays(3 * 365)

        const val PAGE_SIZE = 1000

        /** Safety cap, not a limit anyone should hit: 20 years of five weigh-ins a day. */
        const val HARD_CAP = 40_000
    }
}
