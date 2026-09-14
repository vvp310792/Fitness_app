package com.fitnessapp.summary.strength

/**
 * Exercise `_id` -> name for the gym app's **built-in** exercise catalogue.
 *
 * This table exists because of a hard limitation of the backup format: the backup carries
 * the catalogue table, but every stock row has `name = NULL`. All 572 of them. The names
 * live in the gym app's own resources and are joined in at display time, so a backup read
 * from outside that app knows *which* exercise a set belongs to and not *what it is*.
 *
 * **The mapping is derived, not guessed.** It was recovered from the user's own text export
 * of the same sessions by aligning each backup exercise to an exported one **by its sets** -
 * the exact ordered sequence of weight x reps - rather than by position, which is ambiguous
 * whenever an exercise is skipped or a superset reorders the block. 511 of 515 overlapping
 * sessions aligned to a unique perfect match and voted; all 24 ids below came out unanimous,
 * with zero competing names. Guessing a name here would be the same failure mode this project
 * has paid for before: a wrong key does not throw, it quietly produces a plausible number.
 *
 * The ids are stable over time and safe to use on much older backups: comparing the
 * catalogue in a 2021 backup against a 2026 one, 571 of 572 shared rows are identical in
 * every other column, and the one that differs differs in a single field.
 *
 * Anything not listed here falls back to the row's own [ownName] - user-added exercises do
 * carry their name - and failing that to a placeholder naming the id ([placeholderFor]).
 * A placeholder is deliberately readable and stable: it says on screen that this exercise
 * was recorded and not identified, instead of dropping the sets or filing them under a
 * guess. The nine such ids in the 2018-2021 history are listed in `docs/ANALYSIS-2026-09.md`.
 */
object GymUpExerciseCatalog {

    private val NAMES: Map<Long, String> = mapOf(
        39L to "Сгибание ног в тренажере лёжа",
        47L to "Становая тяга сумо",
        60L to "Подъём гантелей на бицепс стоя",
        66L to "Подъём штанги на бицепс",
        104L to "Жим в тренажере",
        121L to "Жим штанги лёжа средним хватом",
        128L to "Отжимания",
        159L to "Подъём на носки в тренажере для жима ногами",
        160L to "Подъём на носки в тренажере сидя",
        180L to "Выпрямление ног в тренажере",
        190L to "Жим ногами",
        220L to "Приседания со штангой",
        268L to "Гиперэкстензия",
        284L to "Становая тяга со штангой",
        289L to "Армейский жим стоя",
        292L to "Жим гантелей от Арнольда Шварценеггера",
        361L to "Тяга штанги к груди в наклоне",
        413L to "Подъём ног в тренажере с упорами для локтей",
        427L to "Скручивания",
        458L to "Тяга на нижнем блоке",
        498L to "Отжимания на брусьях",
        499L to "Отжимания от скамьи из-за спины",
        542L to "Подтягивания",
        559L to "Тяга верхнего блока широким хватом"
    )

    /** True when [id] is a stock exercise this app can name without help from the backup. */
    fun isKnown(id: Long): Boolean = NAMES.containsKey(id)

    /** What an unidentified exercise is called on screen and in the database. */
    fun placeholderFor(id: Long): String = "Упражнение #$id"

    /**
     * The name to store for exercise [id].
     *
     * Order matters and is not arbitrary. The verified catalogue wins over [ownName] because
     * the gym app leaves `name` set on a *stock* row only in odd migration states, whereas
     * this table was checked against real sessions. [ownName] is trusted next because a
     * user-added exercise is the one case where the backup does carry the real name. The
     * placeholder is last, and is never silent.
     */
    fun nameFor(id: Long, ownName: String?): String {
        NAMES[id]?.let { return it }
        val own = ownName?.trim()
        if (!own.isNullOrEmpty()) return own
        return placeholderFor(id)
    }
}
