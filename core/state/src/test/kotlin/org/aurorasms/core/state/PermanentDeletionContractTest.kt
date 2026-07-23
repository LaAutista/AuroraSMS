// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import org.aurorasms.core.model.MessageSyncFingerprint
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PermanentDeletionContractTest {
    @Test
    fun messageTargetIsExactContentFreeAndRedacted() {
        val target = PermanentDeletionTarget.Message(
            providerMessageId = ProviderMessageId(ProviderKind.SMS, 4L),
            providerThreadId = ProviderThreadId(7L),
            syncFingerprint = fingerprint(),
        )

        assertEquals(ProviderKind.SMS, target.providerMessageId.kind)
        assertFalse(target.toString().contains(fingerprint().toStorageToken()))
        assertThrows(IllegalArgumentException::class.java) {
            target.copy(providerMessageId = ProviderMessageId(ProviderKind.DRAFT, 4L))
        }
    }

    @Test
    fun threadTargetRequiresConsistentBoundedMetadataAndDraftPairing() {
        val target = threadTarget()

        assertEquals(3L, target.smsCount + target.mmsCount)
        assertThrows(IllegalArgumentException::class.java) {
            target.copy(smsCount = 0L, latestSmsId = ProviderMessageId(ProviderKind.SMS, 1L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            target.copy(draftRevision = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            target.copy(smsCount = Long.MAX_VALUE, mmsCount = 1L)
        }
    }

    @Test
    fun operationPairsReviewStateAndRequestRequiresExactUndoWindowWithoutOverflow() {
        val operation = PermanentDeletionOperation(
            id = PermanentDeletionId(1L),
            target = threadTarget(),
            dueTimestampMillis = 6_000L,
            phase = PermanentDeletionPhase.REVIEW_REQUIRED,
            reviewReason = PermanentDeletionReviewReason.CLOCK_CHANGED,
            armedWallTimestampMillis = 1_000L,
            armedElapsedRealtimeMillis = 500L,
            createdTimestampMillis = 1_000L,
            updatedTimestampMillis = 1_001L,
        )

        assertEquals(PermanentDeletionReviewReason.CLOCK_CHANGED, operation.reviewReason)
        assertFalse(operation.toString().contains("7"))
        assertThrows(IllegalArgumentException::class.java) { operation.copy(reviewReason = null) }
        assertThrows(IllegalArgumentException::class.java) { request(due = 5_999L) }
        assertThrows(IllegalArgumentException::class.java) {
            PermanentDeletionRequest(
                target = threadTarget(),
                dueTimestampMillis = 4_999L,
                createdTimestampMillis = Long.MAX_VALUE,
                armedElapsedRealtimeMillis = 0L,
            )
        }
    }

    private fun request(due: Long) = PermanentDeletionRequest(
        target = threadTarget(),
        dueTimestampMillis = due,
        createdTimestampMillis = 1_000L,
        armedElapsedRealtimeMillis = 500L,
    )

    private fun threadTarget() = PermanentDeletionTarget.Thread(
        providerThreadId = ProviderThreadId(7L),
        smsCount = 2L,
        latestSmsId = ProviderMessageId(ProviderKind.SMS, 8L),
        mmsCount = 1L,
        latestMmsId = ProviderMessageId(ProviderKind.MMS, 9L),
        draftId = DraftId(10L),
        draftRevision = DraftRevision(11L),
    )

    private fun fingerprint() = MessageSyncFingerprint.fromSha256(ByteArray(32) { 3 })
}
