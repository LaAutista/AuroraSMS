// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.service

import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.telephony.MessageTransport
import org.aurorasms.core.telephony.MmsDownloadRequest
import org.aurorasms.core.telephony.MmsSendRequest
import org.aurorasms.core.telephony.MmsSubmissionOwnership
import org.aurorasms.core.telephony.RecipientSet
import org.aurorasms.core.telephony.SmsSendRequest
import org.aurorasms.core.telephony.SmsSubmissionOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RespondViaMessageRoutingTest {
    @Test
    fun twoAndThreeRecipientsProduceOneMmsSubmission() = runTest {
        listOf(2, 3).forEach { count ->
            val recipients = recipients(count)
            val submission = respondViaMessageSubmission(OPERATION_ID, recipients, "synthetic", SUBSCRIPTION)
            assertTrue(submission is RespondViaMessageSubmission.Mms)

            val transport = RecordingTransport()
            val result = transport.submitRespondViaMessage(submission)

            assertEquals(MessageTransportKind.MMS, result.transport)
            assertEquals(0, transport.smsCount)
            assertEquals(1, transport.mmsRequests.size)
            assertEquals(recipients, transport.mmsRequests.single().recipients)
            assertEquals(OPERATION_ID, transport.mmsRequests.single().operationId)
        }
    }

    @Test
    fun mmsFailureIsReturnedWithoutSmsFanoutOrFallback() = runTest {
        val submission = respondViaMessageSubmission(
            OPERATION_ID,
            recipients(3),
            "synthetic",
            SUBSCRIPTION,
        )
        val transport = RecordingTransport(mmsFailure = true)

        val result = transport.submitRespondViaMessage(submission)

        assertTrue(result is TransportResult.Rejected)
        assertEquals(MessageTransportKind.MMS, result.transport)
        assertEquals(0, transport.smsCount)
        assertEquals(1, transport.mmsRequests.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun groupCannotBeConstructedAsSmsRequest() {
        SmsSendRequest(
            operationId = OPERATION_ID,
            recipients = recipients(2),
            body = "synthetic",
            subscriptionId = SUBSCRIPTION,
        )
    }

    private fun recipients(count: Int): RecipientSet =
        (RecipientSet.parse((1..count).map { "+1555000000$it" }) as RecipientSet.CreationResult.Valid)
            .recipients

    private class RecordingTransport(
        private val mmsFailure: Boolean = false,
    ) : MessageTransport {
        var smsCount: Int = 0
        val mmsRequests = mutableListOf<MmsSendRequest>()

        override suspend fun sendSms(
            request: SmsSendRequest,
            ownership: SmsSubmissionOwnership,
        ): TransportResult {
            smsCount += 1
            return TransportResult.Submitted(
                request.operationId,
                MessageTransportKind.SMS,
                unitCount = 1,
            )
        }

        override suspend fun sendMms(
            request: MmsSendRequest,
            ownership: MmsSubmissionOwnership,
        ): TransportResult {
            mmsRequests += request
            return if (mmsFailure) {
                TransportResult.Rejected(
                    request.operationId,
                    MessageTransportKind.MMS,
                    TransportResult.FailureReason.CODEC_UNAVAILABLE,
                )
            } else {
                TransportResult.Submitted(
                    request.operationId,
                    MessageTransportKind.MMS,
                    unitCount = 1,
                )
            }
        }

        override suspend fun downloadMms(request: MmsDownloadRequest): TransportResult =
            throw AssertionError("Respond-via routing must not download MMS")
    }

    companion object {
        private val OPERATION_ID = MessageId(ProviderKind.PENDING_OPERATION, 17L)
        private val SUBSCRIPTION = AuroraSubscriptionId(1)
    }
}
