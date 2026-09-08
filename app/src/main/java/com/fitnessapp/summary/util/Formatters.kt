package com.fitnessapp.summary.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

/** Thin space as a thousands separator: "12 480" rather than "12,480" or "12480". */
fun formatCount(value: Long): String {
    val s = value.toString()
    if (s.length <= 3) return s
    return s.reversed().chunked(3).joinToString(" ").reversed()
}

fun formatCount(value: Int): String = formatCount(value.toLong())

/**
 * Metres rendered the way a runner reads them: "8,4 км" once past a kilometre,
 * plain metres below it. A comma is the decimal separator in Russian.
 */
fun formatDistance(meters: Int): String = when {
    meters <= 0 -> "-"
    meters < 1000 -> "$meters м"
    else -> {
        val km = meters / 1000.0
        val rounded = Math.round(km * 10) / 10.0
        "${rounded.toString().replace('.', ',')} км"
    }
}

/** "1 ч 24 мин" / "45 мин". Returns "-" for zero, which everywhere here means "no data". */
fun formatDuration(minutes: Int): String = when {
    minutes <= 0 -> "-"
    minutes < 60 -> "$minutes мин"
    minutes % 60 == 0 -> "${minutes / 60} ч"
    else -> "${minutes / 60} ч ${minutes % 60} мин"
}

/** Same as [formatDuration] but always shows hours, for sleep: "7 ч 40 мин". */
fun formatSleepDuration(minutes: Int): String =
    if (minutes <= 0) "-" else "${minutes / 60} ч ${minutes % 60} мин"

fun formatHeartRate(bpm: Int): String = if (bpm <= 0) "-" else "$bpm"

fun formatCalories(kcal: Int): String = if (kcal <= 0) "-" else "${formatCount(kcal)} ккал"

/** Local wall-clock time of an instant: "07:42". */
fun formatTime(millis: Long): String =
    TIME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** "07:42 - 08:35" for a workout's span. */
fun formatTimeRange(startMillis: Long, endMillis: Long): String =
    "${formatTime(startMillis)} - ${formatTime(endMillis)}"

/**
 * For Garmin's `*TimestampLocal` fields, which are epoch millis already shifted to the
 * user's wall clock - reading them in UTC gives the clock time back; reading them in the
 * device zone would shift them a second time.
 */
fun formatWallClockUtc(millis: Long): String =
    if (millis <= 0L) "-" else TIME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC))

/** "72,4 кг" from grams, one decimal, Russian comma. */
fun formatKg(grams: Int): String =
    if (grams <= 0) "-" else "${(Math.round(grams / 100.0) / 10.0).toString().replace('.', ',')} кг"

/** One-decimal number with a Russian comma: 3.2 -> "3,2". */
fun formatDecimal(value: Float): String =
    (Math.round(value * 10) / 10.0).toString().replace('.', ',')

/** Hours as "1 ч 30 мин" from a fractional hour count. */
fun formatHours(hours: Float): String = formatDuration(Math.round(hours * 60))

/**
 * Signed percentage change, for week-over-week comparison. Null when there's no
 * meaningful baseline (a zero previous week would make every change "+infinity%").
 */
fun percentChange(current: Number, previous: Number): Int? {
    val prev = previous.toDouble()
    if (prev <= 0.0) return null
    return Math.round((current.toDouble() - prev) / prev * 100).toInt()
}

fun formatSignedPercent(percent: Int): String = if (percent > 0) "+$percent%" else "$percent%"

/** Pace in min/km, the number a runner actually cares about: "5:24 /км". */
fun formatPace(distanceMeters: Int, durationMinutes: Int): String? {
    if (distanceMeters < 100 || durationMinutes <= 0) return null
    val minutesPerKm = durationMinutes / (distanceMeters / 1000.0)
    if (minutesPerKm > 99) return null
    val wholeMinutes = minutesPerKm.toInt()
    val seconds = Math.round((minutesPerKm - wholeMinutes) * 60).toInt()
    // Rounding 59.7s up lands on 60 - carry it instead of printing "5:60".
    return if (seconds == 60) {
        "${wholeMinutes + 1}:00 /км"
    } else {
        "$wholeMinutes:${seconds.toString().padStart(2, '0')} /км"
    }
}

/**
 * Russian plural agreement: 1 день, 2 дня, 5 дней, 21 день, 14 дней.
 * Needed wherever a number of days is written into a sentence the user reads -
 * "среднее за 21 дней" is the kind of small wrongness that makes a whole screen
 * look machine-made.
 */
fun pluralRu(count: Int, one: String, few: String, many: String): String {
    val n = kotlin.math.abs(count)
    val lastTwo = n % 100
    if (lastTwo in 11..14) return many
    return when (n % 10) {
        1 -> one
        2, 3, 4 -> few
        else -> many
    }
}

/** "21 день" / "30 дней" */
fun formatDays(count: Int): String = "$count ${pluralRu(count, "день", "дня", "дней")}"
