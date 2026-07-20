// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.index

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.aurorasms.core.index.sync.IndexSignal
import org.aurorasms.core.index.sync.IndexSignalCoalescer
import org.aurorasms.core.index.sync.IndexSyncOutcome

/** Application-lifetime owner for bounded index signals and periodic reconciliation. */
class AppIndexCoordinator(
    private val applicationScope: CoroutineScope,
    synchronize: suspend (Set<IndexSignal>) -> IndexSyncOutcome,
    markPendingChanges: suspend () -> Unit,
    private val periodicIntervalMillis: Long = DEFAULT_PERIODIC_INTERVAL_MILLIS,
    private val shouldContinuePending: () -> Boolean = { false },
    private val pendingRetryDelayMillis: Long = DEFAULT_PENDING_RETRY_DELAY_MILLIS,
    private val maximumPendingRetries: Int = DEFAULT_MAXIMUM_PENDING_RETRIES,
) : AutoCloseable {
    private val started = AtomicBoolean(false)
    private val retryLock = Any()
    private val _lastOutcome = MutableStateFlow<IndexSyncOutcome?>(null)
    val lastOutcome: StateFlow<IndexSyncOutcome?> = _lastOutcome.asStateFlow()

    private val signals = IndexSignalCoalescer(
        applicationScope = applicationScope,
        onSignal = { markPendingChanges() },
        reconcile = { reasons ->
            val outcome = synchronize(reasons)
            _lastOutcome.value = outcome
            if (outcome is IndexSyncOutcome.Pending) {
                schedulePendingContinuation()
            } else {
                resetPendingContinuation()
            }
        },
    )
    private var periodicJob: Job? = null
    private var pendingRetryJob: Job? = null
    private var pendingRetryCount: Int = 0

    @Volatile
    private var closed: Boolean = false

    init {
        require(periodicIntervalMillis > 0L) { "Periodic reconciliation interval must be positive" }
        require(pendingRetryDelayMillis > 0L) { "Pending retry delay must be positive" }
        require(maximumPendingRetries > 0) { "Pending retry count must be positive" }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        resetPendingContinuation()
        applicationScope.launch {
            signals.signal(IndexSignal.STARTUP, requiresDirtyMark = false)
        }
        periodicJob = applicationScope.launch {
            while (isActive) {
                delay(periodicIntervalMillis)
                resetPendingContinuation()
                signals.signal(
                    IndexSignal.PERIODIC_RECONCILIATION,
                    requiresDirtyMark = false,
                )
            }
        }
    }

    suspend fun signal(signal: IndexSignal): Boolean {
        resetPendingContinuation()
        return signals.signal(
            signal = signal,
            // Gaining or losing provider authority is not itself evidence that
            // provider rows changed. Keeping this signal clean lets a paused,
            // checkpointed first-history scan resume instead of abandoning its
            // progress every time the user temporarily changes SMS apps.
            requiresDirtyMark = signal != IndexSignal.ROLE_CHANGED,
        )
    }

    /** Restarts deferred provider work without falsely marking provider data dirty. */
    fun resumeAfterForeground() {
        resetPendingContinuation()
        applicationScope.launch {
            signals.signal(IndexSignal.FOREGROUND_RESUME, requiresDirtyMark = false)
        }
    }

    override fun close() {
        closed = true
        periodicJob?.cancel()
        resetPendingContinuation()
        signals.close()
    }

    private fun schedulePendingContinuation() {
        val permitted = runCatching(shouldContinuePending).getOrDefault(false)
        if (!permitted || closed) return
        synchronized(retryLock) {
            if (
                closed ||
                pendingRetryJob?.isActive == true ||
                pendingRetryCount >= maximumPendingRetries
            ) return
            pendingRetryCount += 1
            val job = applicationScope.launch(start = CoroutineStart.LAZY) {
                runPendingContinuation()
            }
            pendingRetryJob = job
            job.start()
        }
    }

    private suspend fun runPendingContinuation() {
        val currentJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        try {
            delay(pendingRetryDelayMillis)
            if (closed || !runCatching(shouldContinuePending).getOrDefault(false)) return
            // Release the slot before enqueueing so a fast reconciliation can
            // schedule the next bounded continuation without racing this job's
            // completion callback.
            synchronized(retryLock) {
                if (pendingRetryJob === currentJob) pendingRetryJob = null
            }
            signals.signal(IndexSignal.FOREGROUND_RESUME, requiresDirtyMark = false)
        } finally {
            synchronized(retryLock) {
                if (pendingRetryJob === currentJob) pendingRetryJob = null
            }
        }
    }

    private fun resetPendingContinuation() {
        synchronized(retryLock) {
            pendingRetryJob?.cancel()
            pendingRetryJob = null
            pendingRetryCount = 0
        }
    }

    companion object {
        const val DEFAULT_PERIODIC_INTERVAL_MILLIS: Long = 15L * 60L * 1_000L
        const val DEFAULT_PENDING_RETRY_DELAY_MILLIS: Long = 500L
        const val DEFAULT_MAXIMUM_PENDING_RETRIES: Int = 4
    }
}
