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
    synchronize: suspend (Set<IndexSignal>, Long?) -> IndexSyncOutcome,
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
        reconcile = { reasons, durableSequence ->
            val coveredSequence = claimDurableSequence(durableSequence)
            var acknowledgedComplete = false
            try {
                val outcome = synchronize(reasons, coveredSequence)
                _lastOutcome.value = outcome
                acknowledgedComplete = outcome is IndexSyncOutcome.Complete
                if (outcome is IndexSyncOutcome.Pending) {
                    schedulePendingContinuation()
                } else {
                    resetPendingContinuation()
                }
            } finally {
                if (!acknowledgedComplete) retainDurableSequence(coveredSequence)
            }
        },
    )
    private var periodicJob: Job? = null
    private var pendingRetryJob: Job? = null
    private var pendingRetryCount: Int = 0
    private var unresolvedDurableSequence: Long? = null

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
            signals.signal(
                IndexSignal.STARTUP,
                requiresDirtyMark = false,
            )
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

    suspend fun signal(
        signal: IndexSignal,
        durableSequence: Long? = null,
    ): Boolean {
        resetPendingContinuation()
        return signals.signal(
            signal = signal,
            // Explicit provider and authority transitions are ambiguous until
            // a fresh generation proves the complete eligible provider set.
            requiresDirtyMark = true,
            durableSequence = durableSequence,
        )
    }

    /** Restarts deferred provider work without falsely marking provider data dirty. */
    fun resumeAfterForeground() {
        resetPendingContinuation()
        applicationScope.launch {
            signals.signal(
                IndexSignal.FOREGROUND_RESUME,
                requiresDirtyMark = false,
            )
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
        synchronized(retryLock) {
            if (closed) return
            if (!permitted) return
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
            signals.signal(
                IndexSignal.FOREGROUND_RESUME,
                requiresDirtyMark = false,
            )
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

    private fun claimDurableSequence(durableSequence: Long?): Long? = synchronized(retryLock) {
        maxSequence(unresolvedDurableSequence, durableSequence).also {
            unresolvedDurableSequence = null
        }
    }

    private fun retainDurableSequence(durableSequence: Long?) {
        synchronized(retryLock) {
            unresolvedDurableSequence = maxSequence(
                unresolvedDurableSequence,
                durableSequence,
            )
        }
    }

    private fun maxSequence(first: Long?, second: Long?): Long? = when {
        first == null -> second
        second == null -> first
        else -> maxOf(first, second)
    }

    companion object {
        const val DEFAULT_PERIODIC_INTERVAL_MILLIS: Long = 15L * 60L * 1_000L
        const val DEFAULT_PENDING_RETRY_DELAY_MILLIS: Long = 500L
        const val DEFAULT_MAXIMUM_PENDING_RETRIES: Int = 4
    }
}
