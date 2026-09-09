package com.fitnessapp.summary.garmin

import android.content.Context
import com.fitnessapp.summary.debug.AppLog
import java.time.LocalDate

/**
 * How deep into the past the "Вся история" walk has already got.
 *
 * The per-day marks in `garmin_sync_marks` already make re-asking cheap, but they cannot
 * answer the one question the walk itself needs: *where do I start*. Without this store
 * every press restarted at today, re-treading months of settled windows before reaching
 * new ground - and since a lost connection aborts the run, a phone with flaky mobile data
 * could never get past the same stretch. The on-screen counter restarting from zero was
 * the visible half of that; the invisible half was that the walk never went deeper.
 *
 * Deliberately plain [android.content.SharedPreferences], not the encrypted store the
 * OAuth tokens live in: a date and a counter are not a credential, and paying Keystore
 * costs for them would only blur what actually needs protecting.
 *
 * [logicVersion] is stamped alongside, for the same reason
 * [com.fitnessapp.summary.data.GarminSyncMark] carries one: when a fix changes what the
 * endpoints return, the conclusion "Garmin has nothing older than this" was reached by
 * logic that no longer applies and must not be trusted.
 */
class GarminHistoryStore(context: Context) {

    /**
     * @param oldestCoveredEpochDay first day of the oldest window the walk actually
     *   finished, or null if it has never run to the end of a single window.
     * @param emptyChunks how many consecutive windows ended with no data - carried across
     *   runs so an abort two windows into the three that mean "the end" doesn't throw that
     *   evidence away and make the walk prove it again.
     * @param complete the walk concluded that Garmin has nothing older.
     */
    data class Frontier(
        val oldestCoveredEpochDay: Long?,
        val emptyChunks: Int,
        val complete: Boolean
    ) {
        val oldestCovered: LocalDate? get() = oldestCoveredEpochDay?.let(LocalDate::ofEpochDay)
    }

    private val prefs = context.applicationContext
        .getSharedPreferences("garmin_history", Context.MODE_PRIVATE)

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
     * Records one finished window. Called after every window rather than at the end of the
     * walk, for the same reason the sync marks are - an abort or a killed process must not
     * cost the work already done.
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
        AppLog.i("GarminHistoryStore", "Отметка глубины истории сброшена - следующая «Вся история» пойдёт от сегодня")
    }

    private companion object {
        val EMPTY = Frontier(null, 0, false)

        const val KEY_OLDEST_DAY = "oldest_covered_epoch_day"
        const val KEY_EMPTY_CHUNKS = "empty_chunks"
        const val KEY_COMPLETE = "complete"
        const val KEY_LOGIC_VERSION = "logic_version"
    }
}
