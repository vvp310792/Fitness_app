package com.fitnessapp.summary.analytics

import android.content.Context
import com.fitnessapp.summary.debug.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's own maximum heart rate, when they have told us one.
 *
 * Every zone boundary on the intensity card is a percentage of this single number, so it
 * is the one input that has to be settable rather than derived. [IntensityAnalytics.suggestHrMax]
 * can offer a floor from the recorded sessions, but only the user knows whether they have
 * actually tested their maximum, and a season of easy training makes the observed
 * percentile read 20-30 bpm low - which would shift every band and make an easy year look
 * like a hard one.
 *
 * 0 means "not set": the card then falls back to the suggestion and says out loud that it
 * is an estimate. It never quietly writes the suggestion in here - a stored number looks
 * like a decision the user made.
 *
 * Plain [android.content.SharedPreferences], like [com.fitnessapp.summary.garmin.GarminHistoryStore]
 * and for the same reason: a heart rate is a setting, not a credential, and encrypting it
 * would only blur what in this app actually needs protecting.
 *
 * The value is exposed as a [StateFlow] because two screens read it - the card on
 * «Тренды» and the field on «Я» - and an edit on one must redraw the other without a
 * navigation round trip.
 */
class HeartRateZoneStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("heart_rate_zones", Context.MODE_PRIVATE)

    private val _hrMax = MutableStateFlow(prefs.getInt(KEY_HR_MAX, 0))

    /** The user's set maximum, or 0 when they haven't set one. */
    val hrMax: StateFlow<Int> = _hrMax.asStateFlow()

    /**
     * Stores a manually entered maximum. Values outside [ALLOWED] are rejected rather than
     * clamped: a typo of 19 or 1900 is not a heart rate anybody meant, and silently
     * turning it into 120 or 220 would hide the typo behind plausible-looking zones.
     *
     * @return true if it was stored.
     */
    fun set(value: Int): Boolean {
        if (value !in ALLOWED) return false
        prefs.edit().putInt(KEY_HR_MAX, value).apply()
        _hrMax.value = value
        AppLog.i("HeartRateZoneStore", "Максимальный пульс задан вручную: $value")
        return true
    }

    /** Back to the estimate from the recorded sessions. */
    fun clear() {
        prefs.edit().remove(KEY_HR_MAX).apply()
        _hrMax.value = 0
        AppLog.i("HeartRateZoneStore", "Максимальный пульс сброшен - зоны считаются по оценке из тренировок")
    }

    companion object {
        /** Wide enough to hold any real athlete, narrow enough to catch a typo. */
        val ALLOWED = 120..230

        private const val KEY_HR_MAX = "hr_max"
    }
}
