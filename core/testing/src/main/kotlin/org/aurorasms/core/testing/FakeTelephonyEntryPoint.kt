// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.testing

import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.telephony.EncodedMmsPdu
import org.aurorasms.core.telephony.IncomingMessageSink
import org.aurorasms.core.telephony.MessageTransport
import org.aurorasms.core.telephony.TelephonyEntryPoint

class FakeTelephonyEntryPoint(
    override val defaultSmsRoleState: FakeRoleState = FakeRoleState(),
    override val incomingMessageSink: IncomingMessageSink = FakeIncomingMessageSink(),
    override val messageTransport: MessageTransport = FakeMessageTransport(),
) : TelephonyEntryPoint {
    val transportResults = mutableListOf<TransportResult>()
    val downloadedMms = mutableListOf<DownloadedMms>()
    val roleChanges = mutableListOf<Boolean>()
    var externalProviderChangeCount: Int = 0
        private set

    override suspend fun onTransportResult(result: TransportResult) {
        transportResults += result
    }

    override suspend fun onDownloadedMms(operationId: MessageId, pdu: EncodedMmsPdu) {
        downloadedMms += DownloadedMms(operationId, pdu)
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
        val pdu: EncodedMmsPdu,
    )
}
