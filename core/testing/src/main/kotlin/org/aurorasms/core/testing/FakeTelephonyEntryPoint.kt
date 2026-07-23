// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.testing

import android.net.Uri
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.telephony.EncodedMmsPdu
import org.aurorasms.core.telephony.IncomingMessageSink
import org.aurorasms.core.telephony.MessageTransport
import org.aurorasms.core.telephony.MmsStagedPduDisposition
import org.aurorasms.core.telephony.TelephonyEntryPoint

class FakeTelephonyEntryPoint(
    override val defaultSmsRoleState: FakeRoleState = FakeRoleState(),
    override val incomingMessageSink: IncomingMessageSink = FakeIncomingMessageSink(),
    override val messageTransport: MessageTransport = FakeMessageTransport(),
) : TelephonyEntryPoint {
    val transportResults = mutableListOf<TransportResult>()
    val downloadedMms = mutableListOf<DownloadedMms>()
    val failedMmsDownloads = mutableListOf<FailedMmsDownload>()
    val roleChanges = mutableListOf<Boolean>()
    var externalProviderChangeCount: Int = 0
        private set

    override suspend fun onTransportResult(result: TransportResult) {
        transportResults += result
    }

    override suspend fun onDownloadedMms(
        operationId: MessageId,
        stagedUri: Uri,
        pdu: EncodedMmsPdu,
    ): MmsStagedPduDisposition {
        downloadedMms += DownloadedMms(operationId, stagedUri, pdu)
        return MmsStagedPduDisposition.CLEANUP
    }

    override suspend fun onFailedMmsDownload(
        operationId: MessageId,
        stagedUri: Uri,
        result: TransportResult.Failed,
    ): MmsStagedPduDisposition {
        failedMmsDownloads += FailedMmsDownload(operationId, stagedUri, result)
        return MmsStagedPduDisposition.CLEANUP
    }

    override suspend fun onDefaultSmsRoleChanged(isDefaultSmsApp: Boolean) {
        roleChanges += isDefaultSmsApp
        defaultSmsRoleState.held = isDefaultSmsApp
    }

    override suspend fun onExternalProviderChanged() {
        externalProviderChangeCount += 1
    }

    data class DownloadedMms(
        val operationId: MessageId,
        val stagedUri: Uri,
        val pdu: EncodedMmsPdu,
    )

    data class FailedMmsDownload(
        val operationId: MessageId,
        val stagedUri: Uri,
        val result: TransportResult.Failed,
    )
}
