// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransportResultTest {
    @Test
    fun failedDefaultsToSynchronousSubmissionStage() {
        val result = TransportResult.Failed(
            operationId = MessageId(ProviderKind.PENDING_OPERATION, 17L),
            transport = MessageTransportKind.SMS,
            reason = TransportResult.FailureReason.INTERNAL_ERROR,
            retryable = true,
        )

        assertEquals(TransportResult.FailureStage.SUBMISSION, result.stage)
    }

    @Test
    fun submissionUnknownCanNeverBeMarkedRetryable() {
        assertThrows(IllegalArgumentException::class.java) {
            TransportResult.Failed(
                operationId = MessageId(ProviderKind.PENDING_OPERATION, 18L),
                transport = MessageTransportKind.SMS,
                reason = TransportResult.FailureReason.INTERNAL_ERROR,
                retryable = true,
                stage = TransportResult.FailureStage.SUBMISSION_UNKNOWN,
            )
        }
    }

    @Test
    fun submittedAndRejectedPreserveExplicitComposerOrigin() {
        val operationId = MessageId(
            ProviderKind.PENDING_OPERATION,
            COMPOSER_OPERATION_ID_BOUNDARY,
        )
        val submitted = TransportResult.Submitted(
            operationId = operationId,
            transport = MessageTransportKind.SMS,
            unitCount = 1,
            providerMessageId = ProviderMessageId(ProviderKind.SMS, 81L),
            providerConversationId = ConversationId(82L),
            operationOrigin = TransportResult.OperationOrigin.COMPOSER,
        )
        val rejected = TransportResult.Rejected(
            operationId = operationId,
            transport = MessageTransportKind.SMS,
            reason = TransportResult.FailureReason.ROLE_NOT_HELD,
            operationOrigin = TransportResult.OperationOrigin.COMPOSER,
        )

        assertEquals(TransportResult.OperationOrigin.COMPOSER, submitted.operationOrigin)
        assertEquals(ConversationId(82L), submitted.providerConversationId)
        assertEquals(TransportResult.OperationOrigin.COMPOSER, rejected.operationOrigin)
    }
}
