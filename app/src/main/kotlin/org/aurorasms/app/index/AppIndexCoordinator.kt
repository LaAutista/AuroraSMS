// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.index

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
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
) : AutoCloseable {
    private val started = AtomicBoolean(false)
    private val _lastOutcome = MutableStateFlow<IndexSyncOutcome?>(null)
    val lastOutcome: StateFlow<IndexSyncOutcome?> = _lastOutcome.asStateFlow()

    private val signals = IndexSignalCoalescer(
        applicationScope = applicationScope,
        onSignal = { markPendingChanges() },
        reconcile = { reasons -> _lastOutcome.value = synchronize(reasons) },
    )
    private var periodicJob: Job? = null

    init {
        require(periodicIntervalMillis > 0L) { "Periodic reconciliation interval must be positive" }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        applicationScope.launch {
            signals.signal(IndexSignal.STARTUP, requiresDirtyMark = false)
        }
        periodicJob = applicationScope.launch {
            while (isActive) {
                delay(periodicIntervalMillis)
                signals.signal(
                    IndexSignal.PERIODIC_RECONCILIATION,
                    requiresDirtyMark = false,
                )
            }
        }
    }

    suspend fun signal(signal: IndexSignal): Boolean = signals.signal(signal)

    /** Restarts deferred provider work without falsely marking provider data dirty. */
    fun resumeAfterForeground() {
        applicationScope.launch {
            signals.signal(IndexSignal.FOREGROUND_RESUME, requiresDirtyMark = false)
        }
    }

    override fun close() {
        periodicJob?.cancel()
        signals.close()
    }

    companion object {
        const val DEFAULT_PERIODIC_INTERVAL_MILLIS: Long = 15L * 60L * 1_000L
    }
}
