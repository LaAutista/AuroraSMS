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

class ScheduledSmsContractTest {
    @Test
    fun participantIdentityIsCanonicalPurposeSeparatedAndRedacted() {
        val participants = listOf(ParticipantAddress("+15550000002"), ParticipantAddress("+15550000001"))
        val scheduled = ScheduledSmsParticipantSetKey.fromParticipants(participants)
        val reordered = ScheduledSmsParticipantSetKey.fromParticipants(participants.reversed())
        val preference = ConversationSubscriptionParticipantSetKey.fromParticipants(participants)

        assertEquals(scheduled, reordered)
        assertNotEquals(preference.toStorageValue(), scheduled.toStorageValue())
        assertFalse(scheduled.toString().contains("1555"))
        assertFalse(scheduled.toString().contains(scheduled.toStorageValue()))
    }

    @Test
    fun reviewPhaseRequiresReasonAndRequestRequiresFutureDueTime() {
        val schedule = ScheduledSms(
            id = ScheduledSmsId(1L),
            participantSetKey = ScheduledSmsParticipantSetKey.fromParticipants(
                listOf(ParticipantAddress("synthetic@example.invalid")),
            ),
            providerThreadId = ProviderThreadId(2L),
            draftId = DraftId(3L),
            draftRevision = DraftRevision(4L),
            subscriptionId = AuroraSubscriptionId(0),
            dueTimestampMillis = 10L,
            phase = ScheduledSmsPhase.REVIEW_REQUIRED,
            precision = ScheduledSmsPrecision.INEXACT,
            reviewReason = ScheduledSmsReviewReason.CLOCK_CHANGED,
            armedWallTimestampMillis = 5L,
            armedElapsedRealtimeMillis = 6L,
            createdTimestampMillis = 5L,
            updatedTimestampMillis = 7L,
        )

        assertEquals(ScheduledSmsReviewReason.CLOCK_CHANGED, schedule.reviewReason)
        assertEquals("ScheduledSms(phase=REVIEW_REQUIRED, precision=INEXACT, hasReview=true, REDACTED)", schedule.toString())
        assertThrows(IllegalArgumentException::class.java) {
            schedule.copy(reviewReason = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            schedule.copy(dueTimestampMillis = schedule.createdTimestampMillis)
        }
        assertThrows(IllegalArgumentException::class.java) {
            schedule.copy(updatedTimestampMillis = Long.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScheduledSmsRequest(
                participantSetKey = schedule.participantSetKey,
                providerThreadId = schedule.providerThreadId,
                draftId = schedule.draftId,
                expectedDraftRevision = schedule.draftRevision,
                subscriptionId = schedule.subscriptionId,
                dueTimestampMillis = 5L,
                createdTimestampMillis = 5L,
                armedElapsedRealtimeMillis = 1L,
            )
        }
    }
}
