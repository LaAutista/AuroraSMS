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
            synchronize = { _ ->
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
            synchronize = { _ ->
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

    private companion object {
        val PARTIAL_COVERAGE = IndexCoverage(
            generationId = 1L,
            state = IndexRunState.SCANNING,
            indexedMessageCount = 10L,
            smsExhausted = false,
            mmsExhausted = false,
            pendingChanges = false,
        )
    }
}
