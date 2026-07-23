// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import org.aurorasms.core.index.sync.IndexSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppContainerLedgerPolicyTest {
    @Test
    fun unchangedMessagingEligibilityDoesNotReplayAfterProcessStyleRecreation() {
        var persisted: Boolean? = null
        val firstProcess = MessagingEligibilityObservationLedger(
            initialValue = persisted,
            persist = {
                persisted = it
                true
            },
        )

        assertTrue(firstProcess.record(observed = true))

        val recreatedProcess = MessagingEligibilityObservationLedger(
            initialValue = persisted,
            persist = {
                persisted = it
                true
            },
        )
        assertFalse(recreatedProcess.record(observed = true))
        assertTrue(recreatedProcess.record(observed = false))
        assertFalse(recreatedProcess.record(observed = false))
    }

    @Test
    fun failedEligibilityPersistenceRemainsRetryable() {
        var attempts = 0
        val ledger = MessagingEligibilityObservationLedger(
            initialValue = false,
            persist = {
                attempts += 1
                attempts > 1
            },
        )

        assertTrue(ledger.record(observed = true))
        assertTrue(ledger.record(observed = true))
        assertFalse(ledger.record(observed = true))
    }

    @Test
    fun eligibilityNeverAdvancesAheadOfDurableAuthorityGap() {
        var authorityGapDurable = false
        var persistedEligibility = false
        val ledger = MessagingEligibilityObservationLedger(
            initialValue = false,
            persist = {
                persistedEligibility = it
                true
            },
        )

        assertTrue(
            ledger.record(observed = true) {
                authorityGapDurable
            },
        )
        assertFalse(persistedEligibility)

        authorityGapDurable = true
        assertTrue(
            ledger.record(observed = true) {
                authorityGapDurable
            },
        )
        assertTrue(persistedEligibility)
        assertFalse(ledger.record(observed = true))
    }

    @Test
    fun drainedAmbiguousSignalsCarryOnlyTheirExactDurableSequence() {
        val firstBatch = snapshotPendingIndexSignalBatch(
            signals = setOf(IndexSignal.ROLE_CHANGED),
            ambiguousSignalLedgered = true,
            ambiguousSignalSequence = 41L,
        )
        val laterBatch = snapshotPendingIndexSignalBatch(
            signals = setOf(IndexSignal.EXTERNAL_PROVIDER_CHANGE),
            ambiguousSignalLedgered = true,
            ambiguousSignalSequence = 42L,
        )

        assertTrue(firstBatch.signals.contains(IndexSignal.ROLE_CHANGED))
        assertTrue(laterBatch.signals.contains(IndexSignal.EXTERNAL_PROVIDER_CHANGE))
        assertEquals(41L, firstBatch.durableSequence)
        assertEquals(42L, laterBatch.durableSequence)
    }

    @Test
    fun cleanSignalsNeverAcquireAnUnconsumedDurableSequence() {
        val batch = snapshotPendingIndexSignalBatch(
            signals = setOf(
                IndexSignal.FOREGROUND_RESUME,
                IndexSignal.PERIODIC_RECONCILIATION,
            ),
            ambiguousSignalLedgered = true,
            ambiguousSignalSequence = 42L,
        )

        assertNull(batch.durableSequence)
    }
}
