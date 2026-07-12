// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.TransportResult

/** Application-owned bridge used by system-created telephony components. */
interface TelephonyEntryPoint {
    val defaultSmsRoleState: DefaultSmsRoleState
    val incomingMessageSink: IncomingMessageSink
    val messageTransport: MessageTransport

    suspend fun onTransportResult(result: TransportResult)

    suspend fun onDownloadedMms(operationId: MessageId, pdu: EncodedMmsPdu)

    suspend fun onDefaultSmsRoleChanged(isDefaultSmsApp: Boolean)

    suspend fun onExternalProviderChanged()
}
