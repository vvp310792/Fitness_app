package com.fitnessapp.summary.garmin

import com.fitnessapp.summary.data.GarminHeartRateZone
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns the `biometric-service/heartRateZones` response into rows, **without knowing what
 * Garmin calls its fields**.
 *
 * Every other payload in this client had its key names taken verbatim from a reference
 * implementation - garth for the recovery endpoints, python-garminconnect for the activity
 * date filter, SmartScaleConnect for the Zepp path. This one could not be: the endpoint
 * exists in python-garminconnect (`get_heart_rate_zones`, `/biometric-service/heartRateZones`)
 * but it returns the body untouched, and neither repository carries a fixture or a
 * dataclass showing the shape. Writing `optInt("zone1Floor")` from memory is precisely the
 * move this project has a scar for: a guessed key does not fail, it silently returns 0.
 *
 * So the parser matches **structurally** instead of by exact name:
 *
 * - any key containing "zone" and a digit 1-5 is that zone's floor, whatever the rest of
 *   the name is (`zone1Floor`, `zone_1_floor`, `secondaryZone1`, ...);
 * - a nested array of per-zone objects is handled too, since a payload could just as
 *   easily be `{"zones":[{"zoneNumber":1,"floor":93}, ...]}`;
 * - the max / resting / threshold heart rates and the calculation method are matched on
 *   the words that would have to appear in any naming.
 *
 * And whatever happens, [GarminHeartRateZone.rawJson] keeps the original object. If the
 * structure is something neither branch anticipated, the row still lands with the raw
 * response attached, the UI says the zones could not be read, and the actual shape is one
 * screen away instead of being a silent screenful of zeros.
 */
internal object GarminZoneParser {

    /** Plausible bpm. Anything outside is a field that isn't a heart rate at all. */
    private val PLAUSIBLE_BPM = 25..240

    /**
     * @param body the raw response. Garmin returns a JSON array of sport profiles here,
     *   but a single object is accepted too - one fewer way for this to come back empty.
     */
    fun parse(body: String): List<GarminHeartRateZone> {
        val trimmed = body.trim()
        val objects = when {
            trimmed.startsWith("[") -> JSONArray(trimmed).let { array ->
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            }
            trimmed.startsWith("{") -> listOf(JSONObject(trimmed))
            else -> emptyList()
        }
        return objects.mapNotNull { parseOne(it) }
    }

    private fun parseOne(json: JSONObject): GarminHeartRateZone? {
        val floors = IntArray(5)
        var maxHr = 0
        var restingHr = 0
        var thresholdHr = 0
        var method = ""
        var sport = ""

        for (key in json.keys()) {
            val lower = key.lowercase()
            val value = json.opt(key)

            // A nested per-zone array: [{zoneNumber: 1, floor: 93}, ...]. Read before
            // anything else, because the outer key ("zones") also contains "zone".
            if (value is JSONArray) {
                readNestedZones(value, floors)
                continue
            }

            // Split by VALUE type before matching names. A name can honestly belong to two
            // buckets - `heartRateZoneCalculationMethod` contains both "zone" and "method"
            // - and a name-first match sent it to the zone branch, where a string has no
            // number, so the calculation method silently never arrived. What the value IS
            // settles it: a heart rate is a number, a method is a word.
            val number = numberOf(value)
            if (number == null) {
                val text = (value as? String)?.takeIf { it.isNotBlank() } ?: continue
                when {
                    lower.contains("method") || lower.contains("calculation") -> method = text
                    lower == "sport" || lower.endsWith("sport") || lower.contains("sportkey") -> sport = text
                }
                continue
            }

            when {
                lower.contains("zone") -> {
                    val zoneNumber = lower.firstOrNull { it in '1'..'5' }?.digitToInt() ?: continue
                    // Several keys can mention the same zone (a floor and a ceiling). The
                    // floor is the smaller one, and it is the only bound we need: the next
                    // zone's floor is this zone's ceiling.
                    if (number in PLAUSIBLE_BPM &&
                        (floors[zoneNumber - 1] == 0 || number < floors[zoneNumber - 1])
                    ) {
                        floors[zoneNumber - 1] = number
                    }
                }
                lower.contains("max") && lower.contains("heart") ->
                    maxHr = number.takeIf { it in PLAUSIBLE_BPM } ?: maxHr
                lower.contains("resting") ->
                    restingHr = number.takeIf { it in PLAUSIBLE_BPM } ?: restingHr
                lower.contains("lactate") || lower.contains("threshold") ->
                    thresholdHr = number.takeIf { it in PLAUSIBLE_BPM } ?: thresholdHr
            }
        }

        // A row with neither a sport nor a single floor is not a zone profile - most likely
        // an error envelope. Returning it would put an empty row in the table that reads
        // like "Garmin has no zones".
        if (sport.isBlank() && floors.all { it == 0 }) return null

        return GarminHeartRateZone(
            sport = sport.ifBlank { "DEFAULT" }.uppercase(),
            zone1Floor = floors[0],
            zone2Floor = floors[1],
            zone3Floor = floors[2],
            zone4Floor = floors[3],
            zone5Floor = floors[4],
            maxHeartRateUsed = maxHr,
            restingHeartRateUsed = restingHr,
            lactateThresholdHeartRateUsed = thresholdHr,
            method = method,
            rawJson = json.toString()
        )
    }

    /** `[{zoneNumber: 1, floor: 93}, ...]` - the shape where zones are objects, not keys. */
    private fun readNestedZones(array: JSONArray, floors: IntArray) {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            var number = 0
            var bpm = 0
            for (key in item.keys()) {
                val lower = key.lowercase()
                val value = numberOf(item.opt(key)) ?: continue
                when {
                    lower.contains("number") || lower.contains("index") || lower == "zone" ->
                        if (value in 1..5) number = value
                    lower.contains("floor") || lower.contains("low") || lower.contains("min") ||
                        lower.contains("from") || lower.contains("start") ->
                        if (value in PLAUSIBLE_BPM) bpm = value
                }
            }
            // Positional fallback: an array of five zone objects in order needs no number.
            if (number == 0 && array.length() == 5) number = i + 1
            if (number in 1..5 && bpm > 0 && floors[number - 1] == 0) floors[number - 1] = bpm
        }
    }

    /**
     * Garmin sends bpm as ints and as floats ("zone1Floor": 93.0) - both are the same
     * number. Matched on [Number] rather than on the concrete types, because which type a
     * decimal arrives as is not stable: Android's bundled org.json hands back a Double,
     * while recent upstream org.json (what the JVM tests run against) hands back a
     * BigDecimal. Enumerating Int/Long/Double/Float parsed every zone on the phone and
     * none of them in the tests.
     */
    private fun numberOf(value: Any?): Int? = when (value) {
        is Number -> Math.round(value.toDouble()).toInt()
        is String -> value.toDoubleOrNull()?.let { Math.round(it).toInt() }
        else -> null
    }

    /**
     * The response's field names, for the log. Only names - never the numbers: a heart rate
     * is user data and the log carries metadata only (see debug/AppLog). If the parser ever
     * comes back empty, this line is what says which names Garmin actually used.
     */
    fun keyNames(body: String): List<String> = try {
        val trimmed = body.trim()
        val first = when {
            trimmed.startsWith("[") -> JSONArray(trimmed).optJSONObject(0)
            trimmed.startsWith("{") -> JSONObject(trimmed)
            else -> null
        }
        first?.keys()?.asSequence()?.toList().orEmpty()
    } catch (e: Exception) {
        emptyList()
    }
}
