// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class IndexCoverageTest {
    @Test
    fun committedProgressIsDistinctFromPhysicalRowsAndContentRedacted() {
        val coverage = IndexCoverage(
            generationId = 999_999L,
            state = IndexRunState.SCANNING,
            indexedMessageCount = 120L,
            smsExhausted = false,
            mmsExhausted = false,
            pendingChanges = true,
            generationCommittedCount = 75L,
            smsCheckpointCommittedCount = 50L,
            mmsCheckpointCommittedCount = 25L,
        )

        assertEquals(120L, coverage.indexedMessageCount)
        assertEquals(75L, coverage.generationCommittedCount)
        assertEquals(50L, coverage.smsCheckpointCommittedCount)
        assertEquals(25L, coverage.mmsCheckpointCommittedCount)
        val rendered = coverage.toString()
        assertTrue(rendered.contains("generationCommittedCount=75"))
        assertTrue(rendered.contains("smsCheckpointCommittedCount=50"))
        assertTrue(rendered.contains("mmsCheckpointCommittedCount=25"))
        assertFalse(rendered.contains("999999"))
        assertFalse(rendered.contains("providerId", ignoreCase = true))
        assertFalse(rendered.contains("fingerprint", ignoreCase = true))
        assertFalse(rendered.contains("address", ignoreCase = true))
        assertFalse(rendered.contains("body", ignoreCase = true))
    }

    @Test
    fun notStartedCoverageHasZeroCommittedProgress() {
        assertEquals(0L, IndexCoverage.NOT_STARTED.generationCommittedCount)
        assertEquals(0L, IndexCoverage.NOT_STARTED.smsCheckpointCommittedCount)
        assertEquals(0L, IndexCoverage.NOT_STARTED.mmsCheckpointCommittedCount)
    }

    @Test
    fun committedProgressRejectsNegativeOrGenerationlessValues() {
        expectIllegalArgument {
            coverage(generationCommittedCount = -1L)
        }
        expectIllegalArgument {
            coverage(smsCheckpointCommittedCount = -1L)
        }
        expectIllegalArgument {
            coverage(mmsCheckpointCommittedCount = -1L)
        }
        expectIllegalArgument {
            coverage(
                generationId = null,
                state = IndexRunState.NOT_STARTED,
                generationCommittedCount = 1L,
            )
        }
    }

    private fun coverage(
        generationId: Long? = 1L,
        state: IndexRunState = IndexRunState.SCANNING,
        generationCommittedCount: Long = 0L,
        smsCheckpointCommittedCount: Long = 0L,
        mmsCheckpointCommittedCount: Long = 0L,
    ) = IndexCoverage(
        generationId = generationId,
        state = state,
        indexedMessageCount = 0L,
        smsExhausted = false,
        mmsExhausted = false,
        pendingChanges = false,
        generationCommittedCount = generationCommittedCount,
        smsCheckpointCommittedCount = smsCheckpointCommittedCount,
        mmsCheckpointCommittedCount = mmsCheckpointCommittedCount,
    )

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected invariant rejection.
        }
    }
}
