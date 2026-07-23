// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SendDelayContractTest {
    @Test
    fun participantIdentityIsCanonicalPurposeSeparatedAndRedacted() {
        val participants = listOf(
            ParticipantAddress("+15550000002"),
            ParticipantAddress("+15550000001"),
        )
        val delayed = SendDelayParticipantSetKey.fromParticipants(participants)
        val reordered = SendDelayParticipantSetKey.fromParticipants(participants.reversed())
        val scheduled = ScheduledSmsParticipantSetKey.fromParticipants(participants)

        assertEquals(delayed, reordered)
        assertNotEquals(scheduled.toStorageValue(), delayed.toStorageValue())
        assertFalse(delayed.toString().contains("1555"))
        assertFalse(delayed.toString().contains(delayed.toStorageValue()))
    }

    @Test
    fun operationEnforcesReviewPairingAndRequestEnforcesShortDelay() {
        val operation = operation()

        assertEquals(SendDelayReviewReason.CLOCK_CHANGED, operation.reviewReason)
        assertEquals(
            "SendDelayOperation(phase=REVIEW_REQUIRED, hasReview=true, REDACTED)",
            operation.toString(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            operation.copy(reviewReason = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            request(due = 999L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            request(due = 11_001L)
        }
    }

    private fun operation() = SendDelayOperation(
        id = SendDelayId(1L),
        participantSetKey = key(),
        providerThreadId = ProviderThreadId(2L),
        draftId = DraftId(3L),
        draftRevision = DraftRevision(4L),
        subscriptionId = AuroraSubscriptionId(0),
        dueTimestampMillis = 2_000L,
        phase = SendDelayPhase.REVIEW_REQUIRED,
        reviewReason = SendDelayReviewReason.CLOCK_CHANGED,
        armedWallTimestampMillis = 0L,
        armedElapsedRealtimeMillis = 5L,
        createdTimestampMillis = 0L,
        updatedTimestampMillis = 1L,
    )

    private fun request(due: Long) = SendDelayRequest(
        participantSetKey = key(),
        providerThreadId = ProviderThreadId(2L),
        draftId = DraftId(3L),
        expectedDraftRevision = DraftRevision(4L),
        subscriptionId = AuroraSubscriptionId(0),
        dueTimestampMillis = due,
        createdTimestampMillis = 0L,
        armedElapsedRealtimeMillis = 5L,
    )

    private fun key() = SendDelayParticipantSetKey.fromParticipants(
        listOf(ParticipantAddress("synthetic@example.invalid")),
    )
}
