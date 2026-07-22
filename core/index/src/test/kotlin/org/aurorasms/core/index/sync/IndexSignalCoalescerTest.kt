// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.aurorasms.core.index.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class IndexSignalCoalescerTest {
    @Test
    fun `dirty signal joining accepted startup waits for durable mark before reconcile`() = runTest {
        val dirtyStarted = CompletableDeferred<Unit>()
        val releaseDirty = CompletableDeferred<Unit>()
        val runs = mutableListOf<Set<IndexSignal>>()
        var dirtyWrites = 0
        val coalescer = IndexSignalCoalescer(
            applicationScope = backgroundScope,
            onSignal = {
                dirtyWrites += 1
                dirtyStarted.complete(Unit)
                releaseDirty.await()
            },
            reconcile = { signals, _ -> runs += signals },
        )

        assertTrue(coalescer.signal(IndexSignal.STARTUP, requiresDirtyMark = false))
        val dirty = async(start = CoroutineStart.UNDISPATCHED) {
            coalescer.signal(IndexSignal.EXTERNAL_PROVIDER_CHANGE)
        }
        runCurrent()
        assertTrue(dirtyStarted.isCompleted)
        assertEquals(0, runs.size)

        releaseDirty.complete(Unit)
        runCurrent()
        assertTrue(dirty.await())
        assertEquals(1, dirtyWrites)
        assertEquals(
            listOf(setOf(IndexSignal.STARTUP, IndexSignal.EXTERNAL_PROVIDER_CHANGE)),
            runs,
        )
        coalescer.cancelAndJoin()
    }

    @Test
    fun `all pending signal types and duplicate volume coalesce into one bounded run`() = runTest {
        val runs = mutableListOf<Set<IndexSignal>>()
        var dirtyCallbackCount = 0
        val coalescer = IndexSignalCoalescer(
            applicationScope = backgroundScope,
            onSignal = { dirtyCallbackCount += 1 },
            reconcile = { signals, _ -> runs += signals },
        )

        IndexSignal.entries.forEach { signal ->
            assertTrue(coalescer.signal(signal))
        }
        repeat(10_000) {
            assertTrue(coalescer.signal(IndexSignal.CONTENT_OBSERVER_CHANGE))
        }

        runCurrent()

        assertEquals(1, runs.size)
        assertEquals(IndexSignal.entries.toSet(), runs.single())
        assertEquals(1, dirtyCallbackCount)
        coalescer.cancelAndJoin()
    }

    @Test
    fun `signals received during a run cause exactly one serialized subsequent run`() = runTest {
        val started = Channel<Set<IndexSignal>>(capacity = Channel.UNLIMITED)
        val releases = Channel<Unit>(capacity = Channel.UNLIMITED)
        val runs = mutableListOf<Set<IndexSignal>>()
        var activeRuns = 0
        var maximumActiveRuns = 0
        val coalescer = IndexSignalCoalescer(
            applicationScope = backgroundScope,
            reconcile = { signals, _ ->
                activeRuns += 1
                maximumActiveRuns = maxOf(maximumActiveRuns, activeRuns)
                runs += signals
                started.send(signals)
                try {
                    releases.receive()
                } finally {
                    activeRuns -= 1
                }
            },
        )

        assertTrue(coalescer.signal(IndexSignal.STARTUP))
        runCurrent()
        assertTrue(started.tryReceive().isSuccess)
        assertEquals(1, activeRuns)

        repeat(100) {
            assertTrue(coalescer.signal(IndexSignal.CONTENT_OBSERVER_CHANGE))
            assertTrue(coalescer.signal(IndexSignal.EXTERNAL_PROVIDER_CHANGE))
        }
        runCurrent()
        assertEquals(1, runs.size)
        assertEquals(1, activeRuns)

        assertTrue(releases.trySend(Unit).isSuccess)
        runCurrent()
        assertTrue(started.tryReceive().isSuccess)
        assertEquals(2, runs.size)
        assertEquals(
            setOf(IndexSignal.CONTENT_OBSERVER_CHANGE, IndexSignal.EXTERNAL_PROVIDER_CHANGE),
            runs[1],
        )
        assertEquals(1, activeRuns)
        assertEquals(1, maximumActiveRuns)

        assertTrue(releases.trySend(Unit).isSuccess)
        runCurrent()
        assertEquals(0, activeRuns)
        assertEquals(2, runs.size)
        coalescer.cancelAndJoin()
    }

    @Test
    fun `durable sequence belongs only to the reconciliation epoch that consumed it`() = runTest {
        val started = Channel<Pair<Set<IndexSignal>, Long?>>(capacity = Channel.UNLIMITED)
        val releases = Channel<Unit>(capacity = Channel.UNLIMITED)
        val coalescer = IndexSignalCoalescer(
            applicationScope = backgroundScope,
            reconcile = { signals, durableSequence ->
                started.send(signals to durableSequence)
                releases.receive()
            },
        )

        assertTrue(
            coalescer.signal(
                IndexSignal.EXTERNAL_PROVIDER_CHANGE,
                durableSequence = 41L,
            ),
        )
        runCurrent()
        assertEquals(
            setOf(IndexSignal.EXTERNAL_PROVIDER_CHANGE) to 41L,
            started.tryReceive().getOrNull(),
        )

        assertTrue(
            coalescer.signal(
                IndexSignal.ROLE_CHANGED,
                durableSequence = 42L,
            ),
        )
        assertTrue(releases.trySend(Unit).isSuccess)
        runCurrent()
        assertEquals(
            setOf(IndexSignal.ROLE_CHANGED) to 42L,
            started.tryReceive().getOrNull(),
        )

        assertTrue(releases.trySend(Unit).isSuccess)
        runCurrent()
        coalescer.cancelAndJoin()
    }

    @Test
    fun `concurrent duplicates wait behind one durable callback before enqueue`() = runTest {
        val dirtyStarted = CompletableDeferred<Unit>()
        val releaseDirty = CompletableDeferred<Unit>()
        val runs = mutableListOf<Set<IndexSignal>>()
        var dirtyCallbackCount = 0
        val coalescer = IndexSignalCoalescer(
            applicationScope = backgroundScope,
            onSignal = {
                dirtyCallbackCount += 1
                dirtyStarted.complete(Unit)
                releaseDirty.await()
            },
            reconcile = { signals, _ -> runs += signals },
        )

        val first = async { coalescer.signal(IndexSignal.STARTUP) }
        runCurrent()
        assertTrue(dirtyStarted.isCompleted)

        val duplicates = List(1_000) { index ->
            async {
                coalescer.signal(IndexSignal.entries[index % IndexSignal.entries.size])
            }
        }
        runCurrent()

        assertEquals(1, dirtyCallbackCount)
        assertEquals(0, runs.size)
        assertFalse(first.isCompleted)
        assertTrue(duplicates.none { it.isCompleted })

        releaseDirty.complete(Unit)
        runCurrent()

        assertTrue(first.await())
        assertTrue(duplicates.all { it.await() })
        assertEquals(1, dirtyCallbackCount)
        assertEquals(1, runs.size)
        assertEquals(IndexSignal.entries.toSet(), runs.single())
        coalescer.cancelAndJoin()
    }

    @Test
    fun `dirty callback completes before reconciliation is enqueued`() = runTest {
        val events = mutableListOf<String>()
        val coalescer = IndexSignalCoalescer(
            applicationScope = backgroundScope,
            onSignal = { signal -> events += "dirty:${signal.name}" },
            reconcile = { signals, _ -> events += "reconcile:${signals.single().name}" },
        )

        assertTrue(coalescer.signal(IndexSignal.INCOMING_INSERT))
        assertEquals(listOf("dirty:INCOMING_INSERT"), events)

        runCurrent()

        assertEquals(
            listOf("dirty:INCOMING_INSERT", "reconcile:INCOMING_INSERT"),
            events,
        )
        coalescer.cancelAndJoin()
    }

    @Test
    fun `onSignal cancellation propagates and does not enqueue reconciliation`() = runTest {
        var runCount = 0
        val coalescer = IndexSignalCoalescer(
            applicationScope = backgroundScope,
            onSignal = { throw CancellationException("synthetic cancellation") },
            reconcile = { _, _ -> runCount += 1 },
        )

        try {
            coalescer.signal(IndexSignal.ROLE_CHANGED)
            fail("Expected signal cancellation")
        } catch (_: CancellationException) {
            // Expected: cancellation is not converted into a diagnostic/run.
        }
        runCurrent()

        assertEquals(0, runCount)
        coalescer.cancelAndJoin()
    }

    @Test
    fun `closing cancels an active run without cancelling the application scope`() = runTest {
        val parent = SupervisorJob()
        val applicationScope = CoroutineScope(parent + StandardTestDispatcher(testScheduler))
        val runStarted = CompletableDeferred<Unit>()
        val runCancelled = CompletableDeferred<Unit>()
        var dirtyCallbackCount = 0
        val coalescer = IndexSignalCoalescer(
            applicationScope = applicationScope,
            onSignal = { dirtyCallbackCount += 1 },
            reconcile = { _, _ ->
                runStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    runCancelled.complete(Unit)
                }
            },
        )

        assertTrue(coalescer.signal(IndexSignal.STARTUP))
        runCurrent()
        assertTrue(runStarted.isCompleted)

        coalescer.cancelAndJoin()

        assertTrue(runCancelled.isCompleted)
        assertTrue(parent.isActive)
        assertFalse(coalescer.signal(IndexSignal.PERIODIC_RECONCILIATION))
        assertEquals(1, dirtyCallbackCount)
        assertTrue(coalescer.toString().contains("state=closed"))
        parent.cancel()
    }

    @Test
    fun `close racing a dirty callback retains dirty work but rejects enqueue`() = runTest {
        val dirtyStarted = CompletableDeferred<Unit>()
        val releaseDirty = CompletableDeferred<Unit>()
        var runCount = 0
        val coalescer = IndexSignalCoalescer(
            applicationScope = backgroundScope,
            onSignal = {
                dirtyStarted.complete(Unit)
                releaseDirty.await()
            },
            reconcile = { _, _ -> runCount += 1 },
        )
        val accepted = async {
            coalescer.signal(IndexSignal.EXTERNAL_PROVIDER_CHANGE)
        }
        runCurrent()
        assertTrue(dirtyStarted.isCompleted)

        coalescer.close()
        releaseDirty.complete(Unit)
        runCurrent()

        assertFalse(accepted.await())
        assertEquals(0, runCount)
        coalescer.cancelAndJoin()
    }

    @Test
    fun `ordinary failure emits only a redacted diagnostic and worker remains usable`() = runTest {
        val privateMessage = "private message body and address"
        val diagnostics = mutableListOf<IndexSignalDiagnostic>()
        var attempts = 0
        val coalescer = IndexSignalCoalescer(
            applicationScope = backgroundScope,
            reconcile = { _, _ ->
                attempts += 1
                if (attempts == 1) throw IllegalStateException(privateMessage)
            },
            onDiagnostic = { diagnostics += it },
        )

        assertTrue(coalescer.signal(IndexSignal.CONTENT_OBSERVER_CHANGE))
        runCurrent()
        assertEquals(listOf(IndexSignalDiagnostic.RECONCILIATION_FAILED), diagnostics)
        assertFalse(diagnostics.toString().contains(privateMessage))
        assertFalse(coalescer.toString().contains(privateMessage))

        assertTrue(coalescer.signal(IndexSignal.PERIODIC_RECONCILIATION))
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(1, diagnostics.size)
        coalescer.cancelAndJoin()
    }

    @Test
    fun `closing before dispatch discards pending in-memory work idempotently`() = runTest {
        var runCount = 0
        val coalescer = IndexSignalCoalescer(
            applicationScope = backgroundScope,
            reconcile = { _, _ -> runCount += 1 },
        )

        assertTrue(coalescer.signal(IndexSignal.STARTUP))
        coalescer.close()
        coalescer.close()
        runCurrent()

        assertEquals(0, runCount)
        assertFalse(coalescer.signal(IndexSignal.STARTUP))
        coalescer.cancelAndJoin()
    }
}
