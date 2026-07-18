// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.testing

import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.telephony.MessageTransport
import org.aurorasms.core.telephony.MmsDownloadRequest
import org.aurorasms.core.telephony.MmsSendRequest
import org.aurorasms.core.telephony.SmsSendRequest
import org.aurorasms.core.telephony.SmsSubmissionOwnership
import org.aurorasms.core.telephony.SmsSubmissionObserver
import org.aurorasms.core.telephony.hasValidOperationOwnership

class FakeMessageTransport : MessageTransport {
    val smsRequests = mutableListOf<SmsSendRequest>()
    val smsSubmissionOwnership = mutableListOf<SmsSubmissionOwnership>()
    val mmsRequests = mutableListOf<MmsSendRequest>()
    val mmsDownloadRequests = mutableListOf<MmsDownloadRequest>()

    var smsResponder: (SmsSendRequest) -> TransportResult = { request ->
        TransportResult.Submitted(
            operationId = request.operationId,
            transport = MessageTransportKind.SMS,
            unitCount = 1,
            operationOrigin = request.operationOrigin,
        )
    }
    var smsResponderWithObserver:
        (suspend (SmsSendRequest, SmsSubmissionObserver) -> TransportResult)? = null
    var mmsResponder: (MmsSendRequest) -> TransportResult = { request ->
        TransportResult.Submitted(
            operationId = request.operationId,
            transport = MessageTransportKind.MMS,
            unitCount = 1,
        )
    }
    var mmsDownloadResponder: (MmsDownloadRequest) -> TransportResult = { request ->
        TransportResult.Submitted(
            operationId = request.operationId,
            transport = MessageTransportKind.MMS,
            unitCount = 1,
        )
    }

    override suspend fun sendSms(
        request: SmsSendRequest,
        ownership: SmsSubmissionOwnership,
    ): TransportResult {
        smsRequests += request
        smsSubmissionOwnership += ownership
        if (!request.hasValidOperationOwnership(ownership)) {
            return TransportResult.Failed(
                operationId = request.operationId,
                transport = MessageTransportKind.SMS,
                reason = TransportResult.FailureReason.INTERNAL_ERROR,
                retryable = false,
                operationOrigin = request.operationOrigin,
            )
        }
        val observer = (ownership as? SmsSubmissionOwnership.CallerOwned)?.observer
        return if (observer != null && smsResponderWithObserver != null) {
            requireNotNull(smsResponderWithObserver).invoke(request, observer)
        } else {
            smsResponder(request)
        }
    }

    override suspend fun sendMms(request: MmsSendRequest): TransportResult {
        mmsRequests += request
        return mmsResponder(request)
    }

    override suspend fun downloadMms(request: MmsDownloadRequest): TransportResult {
        mmsDownloadRequests += request
        return mmsDownloadResponder(request)
    }
}
