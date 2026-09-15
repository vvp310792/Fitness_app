package com.fitnessapp.summary.googlefit

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * One `*_SLEEP.json` out of `Takeout/Fit/Все сеансы` - a night as Google Fit recorded it.
 *
 * The real sleep in the archive. The `sleep.segment` data stream holds 91 points on this
 * account - about a dozen nights - while these session files hold **269**, and the two are
 * not the same thing: the stream is what some app wrote as segments, the sessions are what
 * Fit filed as sleep. Reading only the stream is how the first version of this import
 * concluded there was almost no sleep here.
 *
 * Shape, from the real files:
 * ```
 * { "fitnessActivity": "sleep",
 *   "startTime": "2026-09-09T17:06:00Z",
 *   "endTime":   "2026-09-10T00:48:00Z",
 *   "duration":  "27720s",
 *   "segment": [ {"fitnessActivity":"sleep",       "startTime":…, "endTime":…},
 *                {"fitnessActivity":"sleep.awake", "startTime":…, "endTime":…} ] }
 * ```
 *
 * Four things that decide how it is read:
 *
 * - **times are UTC** and say so (`Z`). Unlike the Strava export, this needed no proving;
 * - **there are no stages.** Only `sleep` and `sleep.awake` - no light, deep or REM. So a Fit
 *   night carries a total and an awake time and nothing else, and the day card simply does
 *   not draw the stage rows, as it already does for any field that is missing;
 * - **the top-level `duration` spans the awake stretches too**, so the total is the sum of
 *   the `sleep` segments. Taking `duration` would count lying awake as sleeping;
 * - **not every file is a night.** The smallest here is 900 seconds with no segments at all.
 *   A fifteen-minute "night" is a nap or a glitch, and [MIN_NIGHT_MINUTES] drops it.
 *
 * The night belongs to the morning it ENDED on - the rule this whole app uses, and Garmin's.
 */
object FitSleepSessionParser {

    /** Shorter than this is not a night. A nap counted as one would ruin the night's average. */
    const val MIN_NIGHT_MINUTES = 60

    data class Night(
        val dateEpochDay: Long,
        val startMillis: Long,
        val sleepMinutes: Int,
        val awakeMinutes: Int
    )

    /**
     * @return the night, or null when the file is not a sleep session, is too short to be one,
     *   or cannot be read. Never throws: one unreadable file out of 269 must not stop an import.
     */
    fun parse(text: String, zone: ZoneId): Night? {
        if (!text.contains("\"sleep\"")) return null

        val start = instantOf(value(text, "\"startTime\"")) ?: return null
        val end = instantOf(value(text, "\"endTime\"")) ?: return null
        if (end <= start) return null

        var sleepMillis = 0L
        var awakeMillis = 0L
        for (segment in segments(text)) {
            val kind = value(segment, "\"fitnessActivity\"") ?: continue
            val from = instantOf(value(segment, "\"startTime\"")) ?: continue
            val to = instantOf(value(segment, "\"endTime\"")) ?: continue
            val millis = to.toEpochMilli() - from.toEpochMilli()
            if (millis <= 0) continue
            when (kind) {
                "sleep" -> sleepMillis += millis
                "sleep.awake" -> awakeMillis += millis
                // Anything else in a sleep session is neither slept nor spent awake in bed.
                else -> Unit
            }
        }

        // A session with no segments at all still knows how long it lasted, and a night is a
        // better fact than no night - the same call as an unstaged Health Connect session.
        if (sleepMillis == 0L && awakeMillis == 0L) {
            sleepMillis = end.toEpochMilli() - start.toEpochMilli()
        }

        val sleepMinutes = (sleepMillis / 60_000L).toInt()
        if (sleepMinutes < MIN_NIGHT_MINUTES) return null

        return Night(
            dateEpochDay = end.atZone(zone).toLocalDate().toEpochDay(),
            startMillis = start.toEpochMilli(),
            sleepMinutes = sleepMinutes,
            awakeMinutes = (awakeMillis / 60_000L).toInt()
        )
    }

    /** Every `{...}` inside the `segment` array, as raw text. */
    private fun segments(text: String): List<String> {
        val at = text.indexOf("\"segment\"")
        if (at < 0) return emptyList()
        val open = text.indexOf('[', at)
        if (open < 0) return emptyList()

        val out = ArrayList<String>()
        var depth = 0
        var from = -1
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> { if (depth == 0) from = i; depth++ }
                '}' -> { depth--; if (depth == 0 && from >= 0) { out.add(text.substring(from, i + 1)); from = -1 } }
                ']' -> if (depth == 0) return out
            }
            i++
        }
        return out
    }

    /** The string value of `key` in this chunk of JSON. */
    private fun value(text: String, key: String): String? {
        val at = text.indexOf(key)
        if (at < 0) return null
        val colon = text.indexOf(':', at + key.length)
        if (colon < 0) return null
        val open = text.indexOf('"', colon + 1)
        if (open < 0) return null
        val close = text.indexOf('"', open + 1)
        if (close < 0) return null
        return text.substring(open + 1, close)
    }

    private fun instantOf(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return try {
            Instant.parse(value)
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
