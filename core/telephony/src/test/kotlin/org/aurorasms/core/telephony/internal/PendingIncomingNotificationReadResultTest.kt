// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageDeliveryFingerprint
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.telephony.IncomingSmsNotificationReplay
import org.aurorasms.core.telephony.ProviderAccessResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingIncomingNotificationReadResultTest {
    @Test
    fun legacyPendingEntryWithoutContentDigestHasUnresolvedOwnership() {
        assertFalse(canVerifyPendingIncomingProviderOwnership(providerContentDigest = null))
        assertTrue(
            canVerifyPendingIncomingProviderOwnership(
                IncomingSmsProviderContentDigest.fromContent(
                    sender = "+15550102020",
                    body = "verifiable",
                ),
            ),
        )
    }

    @Test
    fun unresolvedPendingEntryWithoutReplayDefersRecovery() {
        val result = pendingIncomingNotificationReadResult(
            replays = emptyList(),
            hasUnresolvedPendingEntry = true,
        )

        assertTrue(result is ProviderAccessResult.Unavailable)
    }

    @Test
    fun recoverableReplayDrainsBeforeUnresolvedPendingEntryDefersNextRead() {
        val replays = listOf(replay())

        val firstRead = pendingIncomingNotificationReadResult(
            replays = replays,
            hasUnresolvedPendingEntry = true,
        )
        val secondRead = pendingIncomingNotificationReadResult(
            replays = emptyList(),
            hasUnresolvedPendingEntry = true,
        )

        assertTrue(firstRead is ProviderAccessResult.Success)
        assertSame(replays, (firstRead as ProviderAccessResult.Success).value)
        assertTrue(secondRead is ProviderAccessResult.Unavailable)
    }

    @Test
    fun emptyJournalCompletesWithAnEmptyReplayPage() {
        val result = pendingIncomingNotificationReadResult(
            replays = emptyList(),
            hasUnresolvedPendingEntry = false,
        )

        assertTrue(result is ProviderAccessResult.Success)
        assertEquals(
            emptyList<IncomingSmsNotificationReplay>(),
            (result as ProviderAccessResult.Success).value,
        )
    }

    @Test
    fun terminalStoredRowFailuresAreQuarantinedButProviderUnavailabilityDefers() {
        assertEquals(
            IncomingSmsReplayJournal.QuarantineReason.PROVIDER_ROW_MISSING,
            incomingStoredReplayQuarantineReason(IncomingStoredReplayRowFailure.MISSING),
        )
        assertEquals(
            IncomingSmsReplayJournal.QuarantineReason.PROVIDER_ROW_INVALID,
            incomingStoredReplayQuarantineReason(IncomingStoredReplayRowFailure.INVALID),
        )
        assertEquals(
            IncomingSmsReplayJournal.QuarantineReason.PROVIDER_ROW_MISMATCH,
            incomingStoredReplayQuarantineReason(
                IncomingStoredReplayRowFailure.VALIDATION_MISMATCH,
            ),
        )
        assertNull(
            incomingStoredReplayQuarantineReason(IncomingStoredReplayRowFailure.UNAVAILABLE),
        )
    }

    private fun replay() = IncomingSmsNotificationReplay(
        deliveryFingerprint = MessageDeliveryFingerprint.fromSha256(
            ByteArray(MessageDeliveryFingerprint.SHA_256_BYTES) { it.toByte() },
        ),
        providerId = ProviderMessageId(ProviderKind.SMS, 41L),
        conversationId = ConversationId(17L),
        sender = ParticipantAddress("+15550102020"),
        body = "recoverable",
        receivedTimestampMillis = 1_704_067_200_000L,
        sentTimestampMillis = 1_704_067_199_900L,
        subscriptionId = AuroraSubscriptionId(1),
    )
}
