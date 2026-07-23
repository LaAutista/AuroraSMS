// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.index

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.aurorasms.core.index.IndexCoverage
import org.aurorasms.core.index.IndexRunState
import org.aurorasms.core.index.sync.IndexSignal
import org.aurorasms.core.index.sync.IndexSyncOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppIndexCoordinatorTest {
    @Test
    fun startupAndDuplicateExternalSignalsAreSerializedAndDirtyFirst() = runTest {
        val events = mutableListOf<String>()
        val coordinator = AppIndexCoordinator(
            applicationScope = backgroundScope,
            markPendingChanges = { events += "dirty" },
            synchronize = { _, _ ->
                events += "sync"
                IndexSyncOutcome.Pending(PARTIAL_COVERAGE)
            },
        )
        coordinator.start()
        coordinator.start()
        runCurrent()
        assertEquals(listOf("sync"), events)

        repeat(100) {
            assertTrue(coordinator.signal(IndexSignal.EXTERNAL_PROVIDER_CHANGE))
        }
        runCurrent()
        assertEquals(listOf("sync", "dirty", "sync"), events)
        assertEquals(IndexSyncOutcome.Pending(PARTIAL_COVERAGE), coordinator.lastOutcome.value)
        coordinator.close()
    }

    @Test
    fun periodicReconciliationUsesBoundedProcessLifetimeTimer() = runTest {
        var runs = 0
        val coordinator = AppIndexCoordinator(
            applicationScope = backgroundScope,
            markPendingChanges = {},
            synchronize = { _, _ ->
                runs += 1
                IndexSyncOutcome.Pending(PARTIAL_COVERAGE)
            },
            periodicIntervalMillis = 1_000L,
        )
        coordinator.start()
        runCurrent()
        assertEquals(1, runs)
        advanceTimeBy(999L)
        runCurrent()
        assertEquals(1, runs)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, runs)
        coordinator.close()
    }

    @Test
    fun foregroundResumeSchedulesCleanReconciliationWithoutDirtyMark() = runTest {
        val events = mutableListOf<String>()
        val coordinator = AppIndexCoordinator(
            applicationScope = backgroundScope,
            markPendingChanges = { events += "dirty" },
            synchronize = { reasons, _ ->
                events += reasons.single().name
                IndexSyncOutcome.Pending(PARTIAL_COVERAGE)
            },
        )

        coordinator.resumeAfterForeground()
        runCurrent()

        assertEquals(listOf(IndexSignal.FOREGROUND_RESUME.name), events)
        coordinator.close()
    }

    @Test
    fun roleChangeDirtiesPartialHistoryBeforeReconciliation() = runTest {
        val events = mutableListOf<String>()
        val coordinator = AppIndexCoordinator(
            applicationScope = backgroundScope,
            markPendingChanges = { events += "dirty" },
            synchronize = { reasons, _ ->
                events += reasons.single().name
                IndexSyncOutcome.Pending(PARTIAL_COVERAGE)
            },
        )

        assertTrue(coordinator.signal(IndexSignal.ROLE_CHANGED))
        runCurrent()

        assertEquals(listOf("dirty", IndexSignal.ROLE_CHANGED.name), events)
        coordinator.close()
    }

    @Test
    fun pendingReconciliationContinuesWhileForegroundUntilComplete() = runTest {
        var runs = 0
        val coordinator = AppIndexCoordinator(
            applicationScope = backgroundScope,
            markPendingChanges = {},
            synchronize = { _, _ ->
                runs += 1
                if (runs < 3) {
                    IndexSyncOutcome.Pending(PARTIAL_COVERAGE)
                } else {
                    IndexSyncOutcome.Complete(COMPLETE_COVERAGE, deletedStaleRows = 0)
                }
            },
            shouldContinuePending = { true },
            pendingRetryDelayMillis = 100L,
        )

        coordinator.start()
        runCurrent()
        assertEquals(1, runs)
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(2, runs)
        advanceTimeBy(100L)
        runCurrent()

        assertEquals(3, runs)
        assertEquals(
            IndexSyncOutcome.Complete(COMPLETE_COVERAGE, deletedStaleRows = 0),
            coordinator.lastOutcome.value,
        )
        coordinator.close()
    }

    @Test
    fun pendingForegroundContinuationRetainsTheConsumedDurableSequence() = runTest {
        val runs = mutableListOf<Pair<Set<IndexSignal>, Long?>>()
        val coordinator = AppIndexCoordinator(
            applicationScope = backgroundScope,
            markPendingChanges = {},
            synchronize = { reasons, durableSequence ->
                runs += reasons to durableSequence
                if (runs.size == 1) {
                    IndexSyncOutcome.Pending(PARTIAL_COVERAGE)
                } else {
                    IndexSyncOutcome.Complete(COMPLETE_COVERAGE, deletedStaleRows = 0)
                }
            },
            shouldContinuePending = { true },
            pendingRetryDelayMillis = 100L,
        )

        assertTrue(coordinator.signal(IndexSignal.ROLE_CHANGED, durableSequence = 42L))
        runCurrent()
        advanceTimeBy(100L)
        runCurrent()

        assertEquals(
            listOf(
                setOf(IndexSignal.ROLE_CHANGED) to 42L,
                setOf(IndexSignal.FOREGROUND_RESUME) to 42L,
            ),
            runs,
        )
        coordinator.close()
    }

    @Test
    fun pendingReconciliationDoesNotContinueWithoutForegroundRoleEligibility() = runTest {
        var runs = 0
        val coordinator = AppIndexCoordinator(
            applicationScope = backgroundScope,
            markPendingChanges = {},
            synchronize = { _, _ ->
                runs += 1
                IndexSyncOutcome.Pending(PARTIAL_COVERAGE)
            },
            shouldContinuePending = { false },
            pendingRetryDelayMillis = 100L,
        )

        coordinator.start()
        runCurrent()
        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(1, runs)
        coordinator.close()
    }

    @Test
    fun foregroundResumeRecoversDurableSequenceHeldWhileReadsWereNotPermitted() = runTest {
        var permitted = false
        val runs = mutableListOf<Pair<Set<IndexSignal>, Long?>>()
        val coordinator = AppIndexCoordinator(
            applicationScope = backgroundScope,
            markPendingChanges = {},
            synchronize = { reasons, durableSequence ->
                runs += reasons to durableSequence
                if (permitted) {
                    IndexSyncOutcome.Complete(COMPLETE_COVERAGE, deletedStaleRows = 0)
                } else {
                    IndexSyncOutcome.Pending(PARTIAL_COVERAGE)
                }
            },
            shouldContinuePending = { permitted },
            pendingRetryDelayMillis = 100L,
        )

        assertTrue(coordinator.signal(IndexSignal.ROLE_CHANGED, durableSequence = 42L))
        runCurrent()
        permitted = true
        coordinator.resumeAfterForeground()
        runCurrent()

        assertEquals(
            listOf(
                setOf(IndexSignal.ROLE_CHANGED) to 42L,
                setOf(IndexSignal.FOREGROUND_RESUME) to 42L,
            ),
            runs,
        )
        coordinator.close()
    }

    @Test
    fun reconciliationFailureRetainsDurableSequenceForTheNextForegroundRun() = runTest {
        val runs = mutableListOf<Pair<Set<IndexSignal>, Long?>>()
        val coordinator = AppIndexCoordinator(
            applicationScope = backgroundScope,
            markPendingChanges = {},
            synchronize = { reasons, durableSequence ->
                runs += reasons to durableSequence
                if (runs.size == 1) {
                    throw IllegalStateException("synthetic redacted failure")
                }
                IndexSyncOutcome.Complete(COMPLETE_COVERAGE, deletedStaleRows = 0)
            },
        )

        assertTrue(coordinator.signal(IndexSignal.ROLE_CHANGED, durableSequence = 42L))
        runCurrent()
        coordinator.resumeAfterForeground()
        runCurrent()

        assertEquals(
            listOf(
                setOf(IndexSignal.ROLE_CHANGED) to 42L,
                setOf(IndexSignal.FOREGROUND_RESUME) to 42L,
            ),
            runs,
        )
        coordinator.close()
    }

    @Test
    fun startPreservesPreStartDurableSequenceThroughForegroundRecovery() = runTest {
        var permitted = false
        val runs = mutableListOf<Pair<Set<IndexSignal>, Long?>>()
        val coordinator = AppIndexCoordinator(
            applicationScope = backgroundScope,
            markPendingChanges = {},
            synchronize = { reasons, durableSequence ->
                runs += reasons to durableSequence
                if (permitted) {
                    IndexSyncOutcome.Complete(COMPLETE_COVERAGE, deletedStaleRows = 0)
                } else {
                    IndexSyncOutcome.Pending(PARTIAL_COVERAGE)
                }
            },
            shouldContinuePending = { permitted },
            pendingRetryDelayMillis = 100L,
        )

        assertTrue(coordinator.signal(IndexSignal.ROLE_CHANGED, durableSequence = 42L))
        runCurrent()
        coordinator.start()
        runCurrent()
        permitted = true
        coordinator.resumeAfterForeground()
        runCurrent()

        assertEquals(
            listOf(
                setOf(IndexSignal.ROLE_CHANGED) to 42L,
                setOf(IndexSignal.STARTUP) to 42L,
                setOf(IndexSignal.FOREGROUND_RESUME) to 42L,
            ),
            runs,
        )
        coordinator.close()
    }

    @Test
    fun pendingContinuationIsBoundedUntilANewExplicitSignal() = runTest {
        var runs = 0
        val coordinator = AppIndexCoordinator(
            applicationScope = backgroundScope,
            markPendingChanges = {},
            synchronize = { _, _ ->
                runs += 1
                IndexSyncOutcome.Pending(PARTIAL_COVERAGE)
            },
            shouldContinuePending = { true },
            pendingRetryDelayMillis = 100L,
            maximumPendingRetries = 2,
        )

        coordinator.start()
        runCurrent()
        repeat(4) {
            advanceTimeBy(100L)
            runCurrent()
        }
        assertEquals(3, runs)

        assertTrue(coordinator.signal(IndexSignal.EXTERNAL_PROVIDER_CHANGE))
        runCurrent()
        assertEquals(4, runs)
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(5, runs)
        coordinator.close()
    }

    private companion object {
        val PARTIAL_COVERAGE = IndexCoverage(
            generationId = 1L,
            state = IndexRunState.SCANNING,
            indexedMessageCount = 10L,
            smsExhausted = false,
            mmsExhausted = false,
            pendingChanges = false,
        )
        val COMPLETE_COVERAGE = PARTIAL_COVERAGE.copy(
            state = IndexRunState.COMPLETE,
            smsExhausted = true,
            mmsExhausted = true,
            generationCommittedCount = 10L,
            smsCheckpointCommittedCount = 10L,
        )
    }
}
