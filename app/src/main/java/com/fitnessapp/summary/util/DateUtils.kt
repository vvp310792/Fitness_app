package com.fitnessapp.summary.util

import java.time.LocalDate

/** Monday-first short weekday labels, index 0 = Monday .. 6 = Sunday. */
val WEEKDAY_LABELS = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

private val MONTHS_GENITIVE = listOf(
    "января", "февраля", "марта", "апреля", "мая", "июня",
    "июля", "августа", "сентября", "октября", "ноября", "декабря"
)

/**
 * Monday of the week [date] falls in. The whole app is Monday-first (ISO), matching
 * both the weekday labels above and how Garmin Connect itself groups a training week.
 */
fun weekStart(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())

fun weekEnd(date: LocalDate): LocalDate = weekStart(date).plusDays(6)

/** The seven dates of [date]'s week, Monday first. */
fun weekDates(date: LocalDate): List<LocalDate> {
    val start = weekStart(date)
    return (0L..6L).map { start.plusDays(it) }
}

/** "7 сентября" */
fun formatDayMonth(date: LocalDate): String = "${date.dayOfMonth} ${MONTHS_GENITIVE[date.monthValue - 1]}"

/** "Сегодня" / "Вчера" / "пт, 5 сентября" - relative to [today] so it stays testable. */
fun formatDayHeader(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "Сегодня"
    today.minusDays(1) -> "Вчера"
    today.plusDays(1) -> "Завтра"
    else -> {
        val weekday = WEEKDAY_LABELS[date.dayOfWeek.value - 1].lowercase()
        "$weekday, ${formatDayMonth(date)}"
    }
}

/**
 * "1-7 сентября" for a week inside one month, "28 августа - 3 сентября" when it
 * straddles two. The month name is only repeated when it actually changes.
 */
fun formatWeekRange(start: LocalDate, end: LocalDate): String =
    if (start.month == end.month) {
        "${start.dayOfMonth}-${end.dayOfMonth} ${MONTHS_GENITIVE[start.monthValue - 1]}"
    } else {
        "${formatDayMonth(start)} - ${formatDayMonth(end)}"
    }

/** "Эта неделя" / "Прошлая неделя" / the date range. */
fun formatWeekHeader(start: LocalDate, today: LocalDate = LocalDate.now()): String {
    val thisWeek = weekStart(today)
    return when (start) {
        thisWeek -> "Эта неделя"
        thisWeek.minusWeeks(1) -> "Прошлая неделя"
        else -> formatWeekRange(start, start.plusDays(6))
    }
}

/** Correct Russian declension of "день/дня/дней" for a count. */
fun declineDays(n: Int): String = when {
    n % 100 in 11..14 -> "дней"
    n % 10 == 1 -> "день"
    n % 10 in 2..4 -> "дня"
    else -> "дней"
}

/** Correct Russian declension of "тренировка/тренировки/тренировок" for a count. */
fun declineWorkouts(n: Int): String = when {
    n % 100 in 11..14 -> "тренировок"
    n % 10 == 1 -> "тренировка"
    n % 10 in 2..4 -> "тренировки"
    else -> "тренировок"
}
