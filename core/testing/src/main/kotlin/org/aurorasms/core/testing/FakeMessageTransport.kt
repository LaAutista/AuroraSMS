// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.testing

import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.telephony.MessageTransport
import org.aurorasms.core.telephony.MmsDownloadRequest
import org.aurorasms.core.telephony.MmsSendRequest
import org.aurorasms.core.telephony.SmsSendRequest

class FakeMessageTransport : MessageTransport {
    val smsRequests = mutableListOf<SmsSendRequest>()
    val mmsRequests = mutableListOf<MmsSendRequest>()
    val mmsDownloadRequests = mutableListOf<MmsDownloadRequest>()

    var smsResponder: (SmsSendRequest) -> TransportResult = { request ->
        TransportResult.Submitted(
            operationId = request.operationId,
            transport = MessageTransportKind.SMS,
            unitCount = 1,
        )
    }
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

    override suspend fun sendSms(request: SmsSendRequest): TransportResult {
        smsRequests += request
        return smsResponder(request)
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
