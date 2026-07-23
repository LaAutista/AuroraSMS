// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import org.aurorasms.core.index.conversation.ConversationSummary
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.BlockedSenderKey
import org.aurorasms.core.state.SpamClassification
import org.aurorasms.core.state.SpamParticipantSetKey
import org.aurorasms.core.state.SpamSafetyDecision
import org.aurorasms.core.state.SpamSafetyRevision
import org.aurorasms.core.state.SpamSafetyScope
import org.aurorasms.core.telephony.ResolvedContact
import org.aurorasms.feature.conversations.SpamSafetyReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalSpamRulesTest {
    @Test
    fun unknownPhoneWithLinkUrgencyAndSensitiveRequestWarns() {
        val address = ParticipantAddress("+12025550155")
        val summary = summary(
            address,
            "URGENT: verify your bank account now at https://example.invalid",
        )

        assertEquals(
            SpamSafetyReason.SUSPICIOUS_LINK_AND_REQUEST,
            spamSafetyReason(summary, mapOf(address to ResolvedContact(address, null, null)), null),
        )
    }

    @Test
    fun savedContactsShortCodesAndAlphanumericSendersAreTrustedByAutomaticRules() {
        val known = ParticipantAddress("+12025550156")
        assertEquals(
            SpamSafetyReason.SAVED_CONTACT,
            spamSafetyReason(
                summary(known, "URGENT bank account https://example.invalid"),
                mapOf(known to ResolvedContact(known, "Synthetic Contact", null)),
                null,
            ),
        )
        listOf(ParticipantAddress("611"), ParticipantAddress("SYNTHETICBANK")).forEach { address ->
            assertNull(
                spamSafetyReason(
                    summary(address, "URGENT bank account https://example.invalid"),
                    mapOf(address to ResolvedContact(address, null, null)),
                    null,
                ),
            )
        }
    }

    @Test
    fun automaticRulesPauseWhenContactTrustCannotBeVerified() {
        val address = ParticipantAddress("+12025550158")

        assertNull(
            spamSafetyReason(
                summary(address, "URGENT bank account https://example.invalid"),
                mapOf(address to ResolvedContact(address, null, null)),
                decision = null,
                automaticRulesAllowed = false,
            ),
        )
    }

    @Test
    fun userDecisionsOverrideAutomaticClassificationAndRemainExplainable() {
        val address = ParticipantAddress("+12025550157")
        val summary = summary(address, "URGENT bank account https://example.invalid")
        val contact = mapOf(address to ResolvedContact(address, null, null))

        assertEquals(
            SpamSafetyReason.USER_MARKED_NOT_SPAM,
            spamSafetyReason(summary, contact, decision(address, SpamClassification.NOT_SPAM, false)),
        )
        assertEquals(
            SpamSafetyReason.USER_BLOCKED,
            spamSafetyReason(summary, contact, decision(address, SpamClassification.SPAM, true)),
        )
    }

    private fun summary(address: ParticipantAddress, snippet: String) = ConversationSummary(
        providerThreadId = ProviderThreadId(55L),
        latestLocalRowId = 1L,
        latestProviderMessageId = ProviderMessageId(ProviderKind.SMS, 1L),
        latestTimestampMillis = 1L,
        latestSentTimestampMillis = 1L,
        latestDirection = MessageDirection.INCOMING,
        latestBox = MessageBox.INBOX,
        latestStatus = MessageStatus.COMPLETE,
        latestSubscriptionId = AuroraSubscriptionId(1),
        latestSenderAddress = address,
        latestSnippet = snippet,
        latestAttachmentCount = 0,
        latestAttachmentTypeSummary = "",
        latestRead = false,
        indexedMessageCount = 1L,
        indexedUnreadCount = 1L,
        participants = listOf(address),
        indexedParticipantCount = 1,
        participantsTruncated = false,
    )

    private fun decision(
        address: ParticipantAddress,
        classification: SpamClassification,
        blocked: Boolean,
    ) = SpamSafetyDecision(
        scope = SpamSafetyScope(
            participantSetKey = SpamParticipantSetKey.fromParticipants(listOf(address)),
            providerThreadId = ProviderThreadId(55L),
            singleSenderKey = BlockedSenderKey.fromSender(address),
        ),
        classification = classification,
        blocked = blocked,
        revision = SpamSafetyRevision(1L),
        updatedTimestampMillis = 1L,
    )
}
