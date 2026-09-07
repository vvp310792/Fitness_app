package com.fitnessapp.summary.garmin

/**
 * The outcome of one call to Garmin, which is deliberately three-way rather than a
 * nullable result.
 *
 * "Garmin has nothing for this day" ([NoData] - a 204, a 404, or an empty body) and "the
 * call did not happen" ([Failed] - no token, a network drop, an HTTP error, a JSON shape
 * change) are completely different facts, and collapsing both into `null` is what made
 * the first version of this undiagnosable: sleep, HRV and readiness came back empty for
 * 14 days straight with not one line in the log, and there was no way to tell a watch
 * that doesn't measure HRV from a wrong URL from a request that was never sent.
 *
 * [GarminSyncManager] counts each outcome per section, so both the log line and the
 * "Я" tab can say WHICH section is empty and WHY.
 */
sealed class GarminFetch<out T> {
    data class Ok<T>(val value: T) : GarminFetch<T>()

    /** Garmin answered, and the answer was "nothing here" - 204, 404, or an empty body. */
    data object NoData : GarminFetch<Nothing>()

    /**
     * The call didn't produce an answer. [isNetwork] separates "the phone lost
     * connectivity" from everything else, because that one is worth aborting a long
     * backfill over instead of grinding through hundreds of doomed requests.
     */
    data class Failed(val reason: String, val isNetwork: Boolean) : GarminFetch<Nothing>()
}

fun <T> GarminFetch<T>.valueOrNull(): T? = (this as? GarminFetch.Ok)?.value
