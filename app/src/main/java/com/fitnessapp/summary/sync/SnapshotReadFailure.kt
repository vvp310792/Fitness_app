package com.fitnessapp.summary.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class SnapshotTaskInterrupted(cause: CancellationException) : Exception("Firebase read interrupted", cause)

/** Preserve actual parent cancellation; a cancelled Firebase Task is a read failure. */
internal suspend fun normalizeSnapshotReadFailure(error: Exception): Exception {
    currentCoroutineContext().ensureActive()
    return if (error is CancellationException && error !is TimeoutCancellationException)
        SnapshotTaskInterrupted(error)
    else error
}
