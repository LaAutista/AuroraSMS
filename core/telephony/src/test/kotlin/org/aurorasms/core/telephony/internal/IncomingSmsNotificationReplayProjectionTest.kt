// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.provider.Telephony
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageDeliveryFingerprint
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.telephony.IncomingSmsNotificationReplayRequest
import org.aurorasms.core.telephony.IncomingSmsRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingSmsNotificationReplayProjectionTest {
    @Test
    fun `exact bounded inbox row reconstructs notification replay`() {
        val replay = validRow().toIncomingSmsNotificationReplayOrNull(
            fingerprint = FINGERPRINT,
            expectedProviderId = PROVIDER_ID,
            expectedConversationId = CONVERSATION_ID,
            expectedReceivedTimestampMillis = RECEIVED_AT,
            expectedSentTimestampMillis = SENT_AT,
            expectedSubscriptionId = AuroraSubscriptionId(4),
            expectedProviderContentDigest = CONTENT_DIGEST,
        )

        requireNotNull(replay)
        assertEquals(FINGERPRINT, replay.deliveryFingerprint)
        assertEquals(PROVIDER_ID, replay.providerId)
        assertEquals(CONVERSATION_ID, replay.conversationId)
        assertEquals(ParticipantAddress("+15550102020"), replay.sender)
        assertEquals("provider-backed body", replay.body)
        assertEquals(RECEIVED_AT, replay.receivedTimestampMillis)
        assertEquals(SENT_AT, replay.sentTimestampMillis)
        assertEquals(AuroraSubscriptionId(4), replay.subscriptionId)
    }

    @Test
    fun `missing mismatched and corrupt provider fields fail closed`() {
        val invalidRows = listOf(
            validRow().copy(providerId = null),
            validRow().copy(providerId = PROVIDER_ID.value + 1L),
            validRow().copy(providerThreadId = CONVERSATION_ID.value + 1L),
            validRow().copy(sender = null),
            validRow().copy(sender = " untrimmed "),
            validRow().copy(body = null),
            validRow().copy(body = "x".repeat(IncomingSmsRecord.MAX_SMS_BODY_CHARACTERS + 1)),
            validRow().copy(messageType = Telephony.Sms.MESSAGE_TYPE_SENT),
            validRow().copy(receivedTimestampMillis = RECEIVED_AT + 1L),
            validRow().copy(sentTimestampMillis = SENT_AT + 1L),
            validRow().copy(subscriptionId = -2),
            validRow().copy(subscriptionId = 5),
        )

        invalidRows.forEach { row ->
            assertNull(
                row.toIncomingSmsNotificationReplayOrNull(
                    fingerprint = FINGERPRINT,
                    expectedProviderId = PROVIDER_ID,
                    expectedConversationId = CONVERSATION_ID,
                    expectedReceivedTimestampMillis = RECEIVED_AT,
                    expectedSentTimestampMillis = SENT_AT,
                    expectedSubscriptionId = AuroraSubscriptionId(4),
                    expectedProviderContentDigest = CONTENT_DIGEST,
                ),
            )
        }
    }

    @Test
    fun `invalid subscription sentinel is accepted only for journaled no-subscription delivery`() {
        val replay = validRow().copy(subscriptionId = -1).toIncomingSmsNotificationReplayOrNull(
            fingerprint = FINGERPRINT,
            expectedProviderId = PROVIDER_ID,
            expectedConversationId = CONVERSATION_ID,
            expectedReceivedTimestampMillis = RECEIVED_AT,
            expectedSentTimestampMillis = SENT_AT,
            expectedSubscriptionId = null,
            expectedProviderContentDigest = CONTENT_DIGEST,
        )

        requireNotNull(replay)
        assertNull(replay.subscriptionId)
    }

    @Test
    fun `replay request bounds are enforced`() {
        assertEquals(1, IncomingSmsNotificationReplayRequest(1).limit)
        assertEquals(64, IncomingSmsNotificationReplayRequest(64).limit)
        assertTrue(
            runCatching { IncomingSmsNotificationReplayRequest(0) }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { IncomingSmsNotificationReplayRequest(65) }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun `pending recovery candidate requires exact redacted content digest`() {
        val candidate = validRow().toPendingRecoveryCandidateOrNull(
            expectedReceivedTimestampMillis = RECEIVED_AT,
            expectedSentTimestampMillis = SENT_AT,
            expectedSubscriptionId = AuroraSubscriptionId(4),
            expectedProviderContentDigest = CONTENT_DIGEST,
        )
        assertEquals(PROVIDER_ID, requireNotNull(candidate).providerId)
        assertEquals(CONVERSATION_ID, candidate.conversationId)

        assertNull(
            validRow().copy(body = "mutated body").toPendingRecoveryCandidateOrNull(
                expectedReceivedTimestampMillis = RECEIVED_AT,
                expectedSentTimestampMillis = SENT_AT,
                expectedSubscriptionId = AuroraSubscriptionId(4),
                expectedProviderContentDigest = CONTENT_DIGEST,
            ),
        )
    }

    private fun validRow() = RawIncomingSmsNotificationReplayRow(
        providerId = PROVIDER_ID.value,
        providerThreadId = CONVERSATION_ID.value,
        sender = "+15550102020",
        body = "provider-backed body",
        messageType = Telephony.Sms.MESSAGE_TYPE_INBOX,
        receivedTimestampMillis = RECEIVED_AT,
        sentTimestampMillis = SENT_AT,
        subscriptionId = 4,
    )

    private companion object {
        val FINGERPRINT: MessageDeliveryFingerprint = MessageDeliveryFingerprint.fromSha256(
            ByteArray(MessageDeliveryFingerprint.SHA_256_BYTES) { (it + 7).toByte() },
        )
        val PROVIDER_ID = ProviderMessageId(ProviderKind.SMS, 41L)
        val CONVERSATION_ID = ConversationId(51L)
        val CONTENT_DIGEST = IncomingSmsProviderContentDigest.fromContent(
            sender = "+15550102020",
            body = "provider-backed body",
        )
        const val RECEIVED_AT = 2_000L
        const val SENT_AT = 1_900L
    }
}
