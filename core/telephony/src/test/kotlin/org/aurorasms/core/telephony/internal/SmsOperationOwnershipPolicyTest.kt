// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.COMPOSER_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.INLINE_REPLY_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.telephony.RecipientSet
import org.aurorasms.core.telephony.SmsSendRequest
import org.aurorasms.core.telephony.SmsSubmissionObserver
import org.aurorasms.core.telephony.SmsSubmissionOwnership
import org.aurorasms.core.telephony.hasValidOperationOwnership
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsOperationOwnershipPolicyTest {
    @Test
    fun eachNewAllocationRegionAcceptsOnlyItsExplicitOwner() {
        val respondVia = request(1L, TransportResult.OperationOrigin.UNMARKED)
        val composer = request(
            COMPOSER_OPERATION_ID_BOUNDARY,
            TransportResult.OperationOrigin.COMPOSER,
        )
        val inlineReply = request(
            INLINE_REPLY_OPERATION_ID_BOUNDARY,
            TransportResult.OperationOrigin.INLINE_REPLY,
        )

        assertTrue(respondVia.hasValidOperationOwnership(SmsSubmissionOwnership.TransportOwned))
        assertTrue(composer.hasValidOperationOwnership(CALLER_OWNED))
        assertTrue(inlineReply.hasValidOperationOwnership(CALLER_OWNED))

        assertFalse(composer.hasValidOperationOwnership(SmsSubmissionOwnership.TransportOwned))
        assertFalse(inlineReply.hasValidOperationOwnership(SmsSubmissionOwnership.TransportOwned))
        assertFalse(respondVia.hasValidOperationOwnership(CALLER_OWNED))
        assertFalse(
            request(COMPOSER_OPERATION_ID_BOUNDARY, TransportResult.OperationOrigin.INLINE_REPLY)
                .hasValidOperationOwnership(CALLER_OWNED),
        )
        assertFalse(
            request(INLINE_REPLY_OPERATION_ID_BOUNDARY, TransportResult.OperationOrigin.COMPOSER)
                .hasValidOperationOwnership(CALLER_OWNED),
        )
    }

    private fun request(
        operationId: Long,
        origin: TransportResult.OperationOrigin,
    ): SmsSendRequest = SmsSendRequest(
        operationId = MessageId(ProviderKind.PENDING_OPERATION, operationId),
        recipients = (RecipientSet.parse(listOf("+15550000001")) as
            RecipientSet.CreationResult.Valid).recipients,
        body = "test",
        subscriptionId = AuroraSubscriptionId(1),
        operationOrigin = origin,
    )

    private companion object {
        val CALLER_OWNED = SmsSubmissionOwnership.CallerOwned(
            object : SmsSubmissionObserver {
                override suspend fun onPrepared(
                    providerId: ProviderMessageId,
                    providerConversationId: ConversationId,
                    unitCount: Int,
                ): Boolean = true

                override suspend fun onSubmitting(
                    providerId: ProviderMessageId,
                    providerConversationId: ConversationId,
                    unitCount: Int,
                ): Boolean = true
            },
        )
    }
}
