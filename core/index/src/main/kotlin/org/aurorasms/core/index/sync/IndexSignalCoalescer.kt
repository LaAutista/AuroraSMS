// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.sync

import java.util.EnumSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * A content-free reason to reconcile the disposable Telephony index.
 *
 * Signals deliberately cannot carry an address, message body, provider ID, or
 * URI. They are therefore safe to include in redacted diagnostics.
 */
enum class IndexSignal {
    STARTUP,
    FOREGROUND_RESUME,
    ROLE_CHANGED,
    INCOMING_INSERT,
    EXTERNAL_PROVIDER_CHANGE,
    CONTENT_OBSERVER_CHANGE,
    PERIODIC_RECONCILIATION,
}

/** Redacted diagnostic events emitted by [IndexSignalCoalescer]. */
enum class IndexSignalDiagnostic {
    RECONCILIATION_FAILED,
}

/**
 * Serializes application-lifetime index reconciliation behind one bounded,
 * conflated wake-up.
 *
 * [onSignal] runs before a dirty wake-up is accepted. The production implementation
 * can use that suspend boundary to persist the active generation's dirty bit,
 * so process death between the database write and reconciliation remains
 * recoverable. It runs once for a pending reconciliation epoch that contains a
 * provider-changing signal; duplicates join that epoch without repeating the
 * database write. Startup and periodic health checks may explicitly opt out.
 *
 * Only one [reconcile] call can run at a time. Signals already pending are
 * represented by a seven-value enum set, while the channel holds at most one
 * wake-up. Signals received during a reconciliation therefore cause exactly
 * one subsequent call, regardless of duplicate volume.
 *
 * Cancellation is never converted into a failure diagnostic. Closing this
 * object cancels only its worker child; it does not cancel [applicationScope].
 */
class IndexSignalCoalescer(
    applicationScope: CoroutineScope,
    private val reconcile: suspend (Set<IndexSignal>) -> Unit,
    private val onSignal: suspend (IndexSignal) -> Unit = {},
    private val onDiagnostic: (IndexSignalDiagnostic) -> Unit = {},
) : AutoCloseable {
    private val stateLock = Any()
    private val wakeUps = Channel<Unit>(capacity = Channel.CONFLATED)

    private var pendingEpoch: PendingEpoch? = null

    @Volatile
    private var closed = false

    private val worker: Job = applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
        runWorker()
    }.also { job ->
        job.invokeOnCompletion {
            closeState()
        }
    }

    /**
     * Marks this reconciliation epoch durably (when configured) and adds the
     * signal to its bounded reason set.
     *
     * Returns `false` without invoking [onSignal] once the coalescer is closed.
     * If closure races the suspend callback, the callback may finish but the
     * return value is `false`; its durable dirty mark is intentionally retained
     * for the next application startup. Callback cancellation and failures are
     * propagated to the caller and do not enqueue a wake-up.
     */
    suspend fun signal(
        signal: IndexSignal,
        requiresDirtyMark: Boolean = true,
    ): Boolean {
        val reservation = synchronized(stateLock) {
            if (closed) return false
            val epoch = pendingEpoch ?: PendingEpoch().also { pendingEpoch = it }
            epoch.signals.add(signal)
            val ownsDirtyCallback = requiresDirtyMark && !epoch.dirtyMarked && epoch.dirtyInFlight == null
            if (ownsDirtyCallback) epoch.dirtyInFlight = CompletableDeferred()
            SignalReservation(epoch = epoch, ownsDirtyCallback = ownsDirtyCallback)
        }

        if (reservation.ownsDirtyCallback) {
            try {
                onSignal(signal)
                currentCoroutineContext().ensureActive()
                synchronized(stateLock) {
                    if (pendingEpoch === reservation.epoch) {
                        reservation.epoch.dirtyMarked = true
                    }
                    reservation.epoch.dirtyInFlight?.complete(Unit)
                }
            } catch (failure: Throwable) {
                failEpoch(reservation.epoch, failure)
                throw failure
            }
        }

        reservation.epoch.dirtyInFlight?.await()
        acceptEpoch(reservation.epoch)
        return reservation.epoch.accepted.await()
    }

    /** Cancels in-flight reconciliation without cancelling the application scope. */
    override fun close() {
        closeState()
        worker.cancel()
    }

    /** [close] plus a suspension point that waits for cancellation cleanup. */
    suspend fun cancelAndJoin() {
        closeState()
        worker.cancelAndJoin()
    }

    override fun toString(): String =
        "IndexSignalCoalescer(state=${if (closed) "closed" else "active"})"

    private suspend fun runWorker() {
        while (true) {
            if (wakeUps.receiveCatching().isClosed) return
            val signals = takePendingSignals()
            if (signals.isEmpty()) continue

            try {
                reconcile(signals)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                reportDiagnostic(IndexSignalDiagnostic.RECONCILIATION_FAILED)
            }
        }
    }

    /**
     * Takes the enum set and drains its associated conflated token atomically
     * with producers. A producer is therefore either included in this run or
     * leaves one token and one signal for the following run, never both.
     */
    private fun takePendingSignals(): Set<IndexSignal> = synchronized(stateLock) {
        while (wakeUps.tryReceive().isSuccess) {
            // One token is enough; the bounded enum set retains all reasons.
        }
        val epoch = pendingEpoch ?: return@synchronized emptySet()
        if (epoch.dirtyInFlight?.isCompleted == false) {
            epoch.needsWakeAfterDirty = true
            return@synchronized emptySet()
        }
        pendingEpoch = null
        epoch.signals.toSet()
    }

    private fun reportDiagnostic(diagnostic: IndexSignalDiagnostic) {
        try {
            onDiagnostic(diagnostic)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Diagnostics must never replace the original redacted failure or
            // disable future reconciliation signals.
        }
    }

    private fun closeState() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
            pendingEpoch?.dirtyInFlight?.complete(Unit)
            pendingEpoch?.accepted?.complete(false)
            pendingEpoch = null
            wakeUps.close()
        }
    }

    private fun failEpoch(epoch: PendingEpoch, failure: Throwable) {
        synchronized(stateLock) {
            if (pendingEpoch === epoch) pendingEpoch = null
            epoch.dirtyInFlight?.completeExceptionally(failure)
            epoch.accepted.completeExceptionally(failure)
        }
    }

    private fun acceptEpoch(epoch: PendingEpoch) {
        synchronized(stateLock) {
            if (closed || pendingEpoch !== epoch) {
                if (!epoch.accepted.isCompleted) epoch.accepted.complete(false)
                return
            }
            if (epoch.accepted.isCompleted && !epoch.needsWakeAfterDirty) return
            val accepted = wakeUps.trySend(Unit).isSuccess
            epoch.needsWakeAfterDirty = false
            if (!accepted) pendingEpoch = null
            if (!epoch.accepted.isCompleted) epoch.accepted.complete(accepted)
        }
    }

    private class PendingEpoch {
        val signals: EnumSet<IndexSignal> = EnumSet.noneOf(IndexSignal::class.java)
        val accepted = CompletableDeferred<Boolean>()
        var dirtyInFlight: CompletableDeferred<Unit>? = null
        var dirtyMarked: Boolean = false
        var needsWakeAfterDirty: Boolean = false
    }

    private data class SignalReservation(
        val epoch: PendingEpoch,
        val ownsDirtyCallback: Boolean,
    )
}
