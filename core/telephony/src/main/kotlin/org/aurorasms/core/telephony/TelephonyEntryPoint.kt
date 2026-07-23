// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import android.net.Uri
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.TransportResult

/** Application-owned bridge used by system-created telephony components. */
interface TelephonyEntryPoint {
    val defaultSmsRoleState: DefaultSmsRoleState
    val incomingMessageSink: IncomingMessageSink
    val messageTransport: MessageTransport

    suspend fun onTransportResult(result: TransportResult)

    suspend fun onDownloadedMms(
        operationId: MessageId,
        stagedUri: Uri,
        pdu: EncodedMmsPdu,
    ): MmsStagedPduDisposition

    suspend fun onFailedMmsDownload(
        operationId: MessageId,
        stagedUri: Uri,
        result: TransportResult.Failed,
    ): MmsStagedPduDisposition

    suspend fun onDefaultSmsRoleChanged(isDefaultSmsApp: Boolean)

    suspend fun onExternalProviderChanged()
}
