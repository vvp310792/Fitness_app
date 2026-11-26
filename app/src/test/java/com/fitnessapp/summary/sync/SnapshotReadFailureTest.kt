package com.fitnessapp.summary.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotReadFailureTest {
    @Test fun cancelledFirebaseTaskInActiveCoroutineIsRetryableReadFailure() = runBlocking {
        val cancelled = CancellationException("task cancelled")
        val failure = normalizeSnapshotReadFailure(cancelled)
        assertTrue(failure is SnapshotTaskInterrupted)
        assertSame(cancelled, failure.cause)
        assertTrue(SnapshotSyncCoordinator.retryable(failure))
    }

    @Test fun cancelledParentStaysCancelled() = runBlocking {
        var preserved = false
        launch {
            currentCoroutineContext().cancel()
            preserved = runCatching {
                normalizeSnapshotReadFailure(CancellationException("parent cancelled"))
            }.exceptionOrNull() is CancellationException
        }.join()
        assertTrue(preserved)
    }

    @Test fun ordinaryErrorIsNotChanged() = runBlocking {
        val error = IllegalArgumentException("bad document")
        assertSame(error, normalizeSnapshotReadFailure(error))
    }
}
