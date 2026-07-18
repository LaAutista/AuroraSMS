// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.telephony.SmsProviderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransportFailurePolicyTest {
    @Test
    fun sendAndDeliveryFailuresMapToDistinctProviderStates() {
        assertEquals(
            SmsProviderStatus.FAILED,
            failure(TransportResult.FailureStage.SUBMISSION).smsProviderFailureStatus(),
        )
        assertEquals(
            SmsProviderStatus.FAILED,
            failure(TransportResult.FailureStage.SENT_CALLBACK).smsProviderFailureStatus(),
        )
        assertEquals(
            SmsProviderStatus.DELIVERY_FAILED,
            failure(TransportResult.FailureStage.DELIVERY_CALLBACK).smsProviderFailureStatus(),
        )
        assertNull(failure(TransportResult.FailureStage.DOWNLOAD_CALLBACK).smsProviderFailureStatus())
    }

    private fun failure(stage: TransportResult.FailureStage) = TransportResult.Failed(
        operationId = MessageId(ProviderKind.PENDING_OPERATION, 71L),
        transport = MessageTransportKind.SMS,
        reason = TransportResult.FailureReason.PLATFORM_REJECTED,
        retryable = false,
        stage = stage,
    )
}
