package com.fitnessapp.summary.util

import androidx.health.connect.client.records.ExerciseSessionRecord

/**
 * Maps Health Connect's raw `EXERCISE_TYPE_*` ints to a Russian name and an emoji.
 *
 * Deliberately a lookup with a fallback rather than an exhaustive `when`: Health
 * Connect adds exercise types over time, and Garmin maps its own much longer sport
 * list onto them, so an unrecognised int is a normal event, not a bug. Anything
 * unmapped shows as "Тренировка" and still counts in every total - the alternative
 * (dropping it) would silently lose real training.
 */
private val NAMES: Map<Int, Pair<String, String>> = mapOf(
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to ("Бег" to "🏃"),
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL to ("Бег на дорожке" to "🏃"),
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING to ("Ходьба" to "🚶"),
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING to ("Поход" to "🥾"),
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING to ("Велосипед" to "🚴"),
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY to ("Велотренажёр" to "🚴"),
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to ("Бассейн" to "🏊"),
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to ("Открытая вода" to "🏊"),
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to ("Силовая" to "🏋"),
    ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING to ("Тяжёлая атлетика" to "🏋"),
    ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS to ("Работа с весом тела" to "🤸"),
    ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to ("Интервальная (HIIT)" to "🔥"),
    ExerciseSessionRecord.EXERCISE_TYPE_YOGA to ("Йога" to "🧘"),
    ExerciseSessionRecord.EXERCISE_TYPE_PILATES to ("Пилатес" to "🧘"),
    ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING to ("Растяжка" to "🤸"),
    ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL to ("Эллипс" to "🏃"),
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING to ("Гребля" to "🚣"),
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE to ("Гребной тренажёр" to "🚣"),
    ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING to ("Лестница" to "🪜"),
    ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE to ("Степпер" to "🪜"),
    ExerciseSessionRecord.EXERCISE_TYPE_SKIING to ("Лыжи" to "⛷"),
    ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING to ("Сноуборд" to "🏂"),
    ExerciseSessionRecord.EXERCISE_TYPE_SKATING to ("Коньки" to "⛸"),
    ExerciseSessionRecord.EXERCISE_TYPE_BOXING to ("Бокс" to "🥊"),
    ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS to ("Единоборства" to "🥋"),
    ExerciseSessionRecord.EXERCISE_TYPE_TENNIS to ("Теннис" to "🎾"),
    ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS to ("Настольный теннис" to "🏓"),
    ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON to ("Бадминтон" to "🏸"),
    ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL to ("Баскетбол" to "🏀"),
    ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL to ("Волейбол" to "🏐"),
    ExerciseSessionRecord.EXERCISE_TYPE_SOCCER to ("Футбол" to "⚽"),
    ExerciseSessionRecord.EXERCISE_TYPE_ICE_HOCKEY to ("Хоккей" to "🏒"),
    ExerciseSessionRecord.EXERCISE_TYPE_GOLF to ("Гольф" to "⛳"),
    ExerciseSessionRecord.EXERCISE_TYPE_DANCING to ("Танцы" to "💃"),
    ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING to ("Скалолазание" to "🧗"),
    ExerciseSessionRecord.EXERCISE_TYPE_SURFING to ("Сёрфинг" to "🏄"),
    ExerciseSessionRecord.EXERCISE_TYPE_PADDLING to ("Гребля на каяке" to "🛶"),
    ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS to ("Гимнастика" to "🤸"),
    ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS to ("Групповое занятие" to "🤸"),
    ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP to ("Функциональная" to "🔥"),
    ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING to ("Дыхание" to "🌬"),
    ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR to ("Коляска" to "🦽"),
    ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT to ("Тренировка" to "💪")
)

fun exerciseName(type: Int): String = NAMES[type]?.first ?: "Тренировка"

fun exerciseEmoji(type: Int): String = NAMES[type]?.second ?: "💪"

/**
 * Whether pace (min/km) is a meaningful readout for this sport. Only foot-based
 * ones: showing "3:12 /км" for a bike ride is technically computable but nobody
 * reads cycling that way, and for a pool swim the distance means something else
 * entirely.
 */
fun usesPace(type: Int): Boolean = type in setOf(
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING
)
