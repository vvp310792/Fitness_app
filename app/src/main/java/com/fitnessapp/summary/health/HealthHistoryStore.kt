package com.fitnessapp.summary.health

import android.content.Context
import com.fitnessapp.summary.debug.AppLog
import java.time.LocalDate

/**
 * How deep into the past the «Вся история Health Connect» walk has already got.
 *
 * Without it every press restarted at today, walked the same first windows again and
 * stopped in the same place - on screen that reads as "it just counts thirty days over
 * and over", because that is literally what it does. The walk was cheap to repeat and
 * completely unable to advance, which is the same scar
 * [com.fitnessapp.summary.garmin.GarminHistoryStore] exists for; Health Connect has no
 * per-day marks to soften it, so here it is the *only* thing that makes a second press
 * mean anything.
 *
 * Deliberately plain [android.content.SharedPreferences]: a date and a counter are not a
 * credential.
 *
 * [logicVersion] is stamped alongside for the same reason it is there - the conclusion
 * "Health Connect has nothing older than this" was reached by logic that a later fix can
 * invalidate, and a stale conclusion is worse than no conclusion because nobody re-checks it.
 */
class HealthHistoryStore(context: Context) {

    /**
     * @param oldestCoveredEpochDay first day of the oldest window the walk actually
     *   finished, or null if it has never finished one.
     * @param emptyChunks consecutive windows that came back with nothing, carried across
     *   runs so a walk stopped part way doesn't have to prove that emptiness again.
     * @param complete the walk concluded Health Connect holds nothing older.
     */
    data class Frontier(
        val oldestCoveredEpochDay: Long?,
        val emptyChunks: Int,
        val complete: Boolean
    ) {
        val oldestCovered: LocalDate? get() = oldestCoveredEpochDay?.let(LocalDate::ofEpochDay)
    }

    private val prefs = context.applicationContext
        .getSharedPreferences("health_history", Context.MODE_PRIVATE)

    fun read(logicVersion: Int): Frontier {
        if (prefs.getInt(KEY_LOGIC_VERSION, -1) != logicVersion) return EMPTY
        val oldest = prefs.getLong(KEY_OLDEST_DAY, Long.MIN_VALUE)
        return Frontier(
            oldestCoveredEpochDay = oldest.takeIf { it != Long.MIN_VALUE },
            emptyChunks = prefs.getInt(KEY_EMPTY_CHUNKS, 0),
            complete = prefs.getBoolean(KEY_COMPLETE, false)
        )
    }

    /**
     * Records one finished window - after every window, never at the end of the walk. A
     * killed process or a user leaving the screen must not cost the days already read.
     */
    fun advance(oldestCoveredEpochDay: Long, emptyChunks: Int, complete: Boolean, logicVersion: Int) {
        prefs.edit()
            .putLong(KEY_OLDEST_DAY, oldestCoveredEpochDay)
            .putInt(KEY_EMPTY_CHUNKS, emptyChunks)
            .putBoolean(KEY_COMPLETE, complete)
            .putInt(KEY_LOGIC_VERSION, logicVersion)
            .apply()
    }

    fun reset() {
        prefs.edit().clear().apply()
        AppLog.i("HealthHistoryStore", "Глубина истории Health Connect забыта - следующая «Вся история» пойдёт от сегодня")
    }

    private companion object {
        val EMPTY = Frontier(null, 0, false)

        const val KEY_OLDEST_DAY = "oldest_covered_epoch_day"
        const val KEY_EMPTY_CHUNKS = "empty_chunks"
        const val KEY_COMPLETE = "complete"
        const val KEY_LOGIC_VERSION = "logic_version"
    }
}
