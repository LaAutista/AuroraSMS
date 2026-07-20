// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import org.aurorasms.core.index.sync.IndexSignal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppContainerLedgerPolicyTest {
    @Test
    fun foregroundResumeCanClearPreviouslyLedgeredAmbiguousWork() {
        assertTrue(
            reconciliationCoversAmbiguousSignalLedger(
                setOf(IndexSignal.FOREGROUND_RESUME),
            ),
        )
    }

    @Test
    fun cleanHealthSignalsDoNotClearAmbiguousLedgerByThemselves() {
        assertFalse(
            reconciliationCoversAmbiguousSignalLedger(
                setOf(
                    IndexSignal.STARTUP,
                    IndexSignal.ROLE_CHANGED,
                    IndexSignal.PERIODIC_RECONCILIATION,
                ),
            ),
        )
    }

    @Test
    fun ambiguousProviderSignalsStillCoverTheirDurableLedger() {
        assertTrue(
            reconciliationCoversAmbiguousSignalLedger(
                setOf(IndexSignal.CONTENT_OBSERVER_CHANGE),
            ),
        )
    }
}
