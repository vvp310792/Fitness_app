package com.fitnessapp.summary.util

/*
 * Russian labels for the enum-like strings Garmin Connect returns. Every function falls
 * back to a readable form of the raw key rather than an empty string, so a value Garmin
 * adds tomorrow still shows up as *something* instead of a blank row - the same
 * "unknown is normal, not a bug" stance as ExerciseTypes.kt.
 */

/** Sleep Score component grades (`qualifierKey`): EXCELLENT / GOOD / FAIR / POOR. */
fun qualifierLabel(key: String): String = when (key.uppercase()) {
    "EXCELLENT" -> "Отлично"
    "GOOD" -> "Хорошо"
    "FAIR" -> "Так себе"
    "POOR" -> "Плохо"
    "" -> ""
    else -> humanize(key)
}

/** Daily summary `stressQualifier`. */
fun stressQualifierLabel(key: String): String = when (key.lowercase()) {
    "calm" -> "спокойный день"
    "balanced" -> "сбалансированный день"
    "stressful" -> "напряжённый день"
    "very_stressful" -> "очень напряжённый день"
    "" -> ""
    else -> humanize(key)
}

/** HRV Status. Garmin also uses NONE while the 3-week baseline is still forming. */
fun hrvStatusLabel(status: String): String = when (status.uppercase()) {
    "BALANCED" -> "Сбалансирована"
    "UNBALANCED" -> "Несбалансирована"
    "LOW" -> "Низкая"
    "POOR" -> "Плохая"
    "NONE", "" -> "Базовая линия ещё формируется"
    else -> humanize(status)
}

/** Training Readiness `level`. */
fun readinessLevelLabel(level: String): String = when (level.uppercase()) {
    "PRIME" -> "Пик"
    "HIGH" -> "Высокая"
    "MODERATE" -> "Средняя"
    "LOW" -> "Низкая"
    "NONE", "" -> ""
    else -> humanize(level)
}

/**
 * Training Status, derived from the feedback phrase ("PRODUCTIVE_1", "RECOVERY_3"...)
 * rather than Garmin's numeric code - the phrase names the status in plain text, the
 * code's mapping isn't documented anywhere and would have had to be guessed.
 */
fun trainingStatusLabel(feedbackPhrase: String): String {
    val key = feedbackPhrase.uppercase().substringBefore('_').ifBlank { return "" }
    return when {
        feedbackPhrase.uppercase().startsWith("NO_STATUS") -> "Нет статуса"
        key == "PRODUCTIVE" -> "Продуктивный"
        key == "MAINTAINING" -> "Поддержание"
        key == "RECOVERY" -> "Восстановление"
        key == "PEAKING" -> "Пик формы"
        key == "DETRAINING" -> "Потеря формы"
        key == "UNPRODUCTIVE" -> "Непродуктивный"
        key == "OVERREACHING" -> "Перегрузка"
        key == "STRAINED" -> "Перенапряжение"
        key == "PAUSED" -> "На паузе"
        else -> humanize(key)
    }
}

/** Acute:chronic workload ratio status. */
fun acwrStatusLabel(status: String): String = when (status.uppercase()) {
    "OPTIMAL" -> "оптимальная"
    "HIGH" -> "высокая"
    "VERY_HIGH" -> "очень высокая"
    "LOW" -> "низкая"
    "VERY_LOW" -> "очень низкая"
    "" -> ""
    else -> humanize(status).lowercase()
}

/** Training Effect primary benefit label on an activity. */
fun trainingEffectLabel(label: String): String = when (label.uppercase()) {
    "RECOVERY" -> "Восстановление"
    "AEROBIC_BASE" -> "Аэробная база"
    "TEMPO" -> "Темп"
    "LACTATE_THRESHOLD" -> "Лактатный порог"
    "VO2MAX" -> "VO2max"
    "ANAEROBIC_CAPACITY" -> "Анаэробная мощность"
    "SPEED" -> "Скорость"
    "UNKNOWN", "NONE", "" -> ""
    else -> humanize(label)
}

/** Garmin's own 0-5 Training Effect scale, as words. */
fun trainingEffectScale(value: Float): String = when {
    value < 1f -> "без эффекта"
    value < 2f -> "небольшой"
    value < 3f -> "поддерживающий"
    value < 4f -> "развивающий"
    value < 5f -> "сильно развивающий"
    else -> "чрезмерный"
}

/** Body Battery bands as Garmin presents them (0-100). */
fun bodyBatteryLabel(value: Int): String = when {
    value <= 0 -> ""
    value < 25 -> "очень низкий"
    value < 50 -> "низкий"
    value < 75 -> "средний"
    else -> "высокий"
}

/** Garmin stress scale (0-100): rest <25, low 26-50, medium 51-75, high 76+. */
fun stressLevelLabel(value: Int): String = when {
    value <= 0 -> ""
    value <= 25 -> "покой"
    value <= 50 -> "низкий"
    value <= 75 -> "средний"
    else -> "высокий"
}

private val SPORT_NAMES: Map<String, Pair<String, String>> = mapOf(
    "running" to ("Бег" to "🏃"),
    "treadmill_running" to ("Бег на дорожке" to "🏃"),
    "trail_running" to ("Трейл" to "🏃"),
    "track_running" to ("Бег на стадионе" to "🏃"),
    "indoor_running" to ("Бег в зале" to "🏃"),
    "walking" to ("Ходьба" to "🚶"),
    "casual_walking" to ("Прогулка" to "🚶"),
    "speed_walking" to ("Быстрая ходьба" to "🚶"),
    "hiking" to ("Поход" to "🥾"),
    "cycling" to ("Велосипед" to "🚴"),
    "road_biking" to ("Шоссе" to "🚴"),
    "mountain_biking" to ("Маунтинбайк" to "🚴"),
    "indoor_cycling" to ("Велотренажёр" to "🚴"),
    "virtual_ride" to ("Виртуальный заезд" to "🚴"),
    "gravel_cycling" to ("Гравел" to "🚴"),
    "lap_swimming" to ("Бассейн" to "🏊"),
    "open_water_swimming" to ("Открытая вода" to "🏊"),
    "strength_training" to ("Силовая" to "🏋"),
    "cardio" to ("Кардио" to "🔥"),
    "hiit" to ("Интервальная (HIIT)" to "🔥"),
    "indoor_cardio" to ("Кардио в зале" to "🔥"),
    "fitness_equipment" to ("Тренажёры" to "🏋"),
    "elliptical" to ("Эллипс" to "🏃"),
    "stair_climbing" to ("Степпер" to "🪜"),
    "indoor_rowing" to ("Гребной тренажёр" to "🚣"),
    "rowing" to ("Гребля" to "🚣"),
    "yoga" to ("Йога" to "🧘"),
    "pilates" to ("Пилатес" to "🧘"),
    "breathwork" to ("Дыхание" to "🧘"),
    "stretching" to ("Растяжка" to "🤸"),
    "resort_skiing_snowboarding" to ("Горные лыжи" to "⛷"),
    "cross_country_skiing" to ("Беговые лыжи" to "⛷"),
    "skate_skiing" to ("Лыжи, конёк" to "⛷"),
    "snowboarding" to ("Сноуборд" to "🏂"),
    "ice_skating" to ("Коньки" to "⛸"),
    "inline_skating" to ("Ролики" to "🛼"),
    "tennis" to ("Теннис" to "🎾"),
    "padel" to ("Падел" to "🎾"),
    "table_tennis" to ("Настольный теннис" to "🏓"),
    "badminton" to ("Бадминтон" to "🏸"),
    "basketball" to ("Баскетбол" to "🏀"),
    "soccer" to ("Футбол" to "⚽"),
    "volleyball" to ("Волейбол" to "🏐"),
    "boxing" to ("Бокс" to "🥊"),
    "mixed_martial_arts" to ("Единоборства" to "🥋"),
    "golf" to ("Гольф" to "⛳"),
    "multi_sport" to ("Мультиспорт" to "🏅"),
    "other" to ("Тренировка" to "🏅")
)

/** Russian name for a Garmin `activityType.typeKey`; unknown keys are humanized, never dropped. */
fun garminSportName(typeKey: String): String =
    SPORT_NAMES[typeKey.lowercase()]?.first ?: humanize(typeKey).ifBlank { "Тренировка" }

fun garminSportEmoji(typeKey: String): String = SPORT_NAMES[typeKey.lowercase()]?.second ?: "🏅"

/** Sports whose distance is normally read as a pace rather than a speed. */
fun garminSportUsesPace(typeKey: String): Boolean =
    typeKey.lowercase().let { it.contains("running") || it.contains("walking") || it == "hiking" || it.contains("swimming") }

/** "very_stressful" / "AEROBIC_BASE" -> "Very stressful" / "Aerobic base" - the last resort for an unmapped key. */
private fun humanize(key: String): String =
    key.replace('_', ' ').lowercase().replaceFirstChar { it.uppercaseChar() }
