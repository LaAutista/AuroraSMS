// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.receiver

import android.app.Activity
import android.telephony.SmsManager
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.TransportResult
import org.junit.Assert.assertEquals
import org.junit.Test

class TransportFailureStageTest {
    private val operationId = MessageId(ProviderKind.PENDING_OPERATION, 23L)
    private val providerMessageId = ProviderMessageId(ProviderKind.SMS, 29L)

    @Test
    fun smsSentFailureHasSentCallbackStage() {
        val result = smsSentResult(
            operationId = operationId,
            providerMessageId = providerMessageId,
            unitIndex = 1,
            unitCount = 2,
            platformResultCode = SmsManager.RESULT_ERROR_NO_SERVICE,
        ) as TransportResult.Failed

        assertEquals(TransportResult.FailureStage.SENT_CALLBACK, result.stage)
    }

    @Test
    fun smsDeliveryFailureHasDeliveryCallbackStage() {
        val result = smsDeliveredResult(
            operationId = operationId,
            providerMessageId = providerMessageId,
            unitIndex = 0,
            unitCount = 1,
            platformResultCode = Activity.RESULT_CANCELED,
        ) as TransportResult.Failed

        assertEquals(TransportResult.FailureStage.DELIVERY_CALLBACK, result.stage)
    }

    @Test
    fun smsCallbackResultPreservesExplicitInlineReplyOwnership() {
        val providerConversationId = ConversationId(31L)
        val sent = smsSentResult(
            operationId = operationId,
            providerMessageId = providerMessageId,
            unitIndex = 0,
            unitCount = 1,
            platformResultCode = Activity.RESULT_OK,
            operationOrigin = TransportResult.OperationOrigin.INLINE_REPLY,
            providerConversationId = providerConversationId,
        )
        val delivered = smsDeliveredResult(
            operationId = operationId,
            providerMessageId = providerMessageId,
            unitIndex = 0,
            unitCount = 1,
            platformResultCode = Activity.RESULT_CANCELED,
            operationOrigin = TransportResult.OperationOrigin.INLINE_REPLY,
            providerConversationId = providerConversationId,
        )

        assertEquals(TransportResult.OperationOrigin.INLINE_REPLY, sent.operationOrigin)
        assertEquals(TransportResult.OperationOrigin.INLINE_REPLY, delivered.operationOrigin)
        assertEquals(providerConversationId, (sent as TransportResult.Sent).providerConversationId)
        assertEquals(
            providerConversationId,
            (delivered as TransportResult.Failed).providerConversationId,
        )
    }

    @Test
    fun mmsSendFailureHasSentCallbackStage() {
        val result = mmsSendResult(
            operationId = operationId,
            platformResultCode = SmsManager.MMS_ERROR_RETRY,
        ) as TransportResult.Failed

        assertEquals(TransportResult.FailureStage.SENT_CALLBACK, result.stage)
    }

    @Test
    fun mmsDownloadFailureHasDownloadCallbackStage() {
        val result = mmsDownloadFailureResult(
            operationId = operationId,
            reason = TransportResult.FailureReason.PLATFORM_REJECTED,
            retryable = true,
            platformResultCode = SmsManager.MMS_ERROR_RETRY,
        )

        assertEquals(TransportResult.FailureStage.DOWNLOAD_CALLBACK, result.stage)
    }
}
