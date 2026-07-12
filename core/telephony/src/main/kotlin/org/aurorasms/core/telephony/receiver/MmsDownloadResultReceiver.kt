// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.telephony.EncodedMmsPdu
import org.aurorasms.core.telephony.internal.MmsPduDirection
import org.aurorasms.core.telephony.internal.MmsPduStagingStore

class MmsDownloadResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MMS_DOWNLOADED) return
        val operationId = intent.pendingOperationIdOrNull() ?: return
        val uri = intent.stagedUriOrNull() ?: return
        val code = resultCode
        dispatchAsync(context) { entryPoint ->
            val store = MmsPduStagingStore(context)
            try {
                if (code == Activity.RESULT_OK) {
                    when (val downloaded = store.readCompletedDownload(
                        org.aurorasms.core.telephony.internal.StagedMmsPdu(
                            operationId = operationId,
                            uri = uri,
                            direction = MmsPduDirection.DOWNLOAD_TARGET,
                            fileName = uri.lastPathSegment.orEmpty(),
                        ),
                    )) {
                        is EncodedMmsPdu.CreationResult.Valid -> {
                            entryPoint.onDownloadedMms(operationId, downloaded.pdu)
                            entryPoint.onTransportResult(
                                TransportResult.Downloaded(
                                    operationId = operationId,
                                    transport = MessageTransportKind.MMS,
                                    platformResultCode = code,
                                    byteCount = downloaded.pdu.size,
                                ),
                            )
                        }
                        is EncodedMmsPdu.CreationResult.Rejected -> {
                            entryPoint.onTransportResult(
                                TransportResult.Failed(
                                    operationId = operationId,
                                    transport = MessageTransportKind.MMS,
                                    reason = if (downloaded.reason == EncodedMmsPdu.CreationResult.Reason.TOO_LARGE) {
                                        TransportResult.FailureReason.PAYLOAD_TOO_LARGE
                                    } else {
                                        TransportResult.FailureReason.PLATFORM_REJECTED
                                    },
                                    retryable = false,
                                    platformResultCode = code,
                                ),
                            )
                        }
                    }
                } else {
                    entryPoint.onTransportResult(
                        TransportResult.Failed(
                            operationId = operationId,
                            transport = MessageTransportKind.MMS,
                            reason = TransportResult.FailureReason.PLATFORM_REJECTED,
                            retryable = code == SmsManager.MMS_ERROR_RETRY || code == SmsManager.MMS_ERROR_NO_DATA_NETWORK,
                            platformResultCode = code,
                        ),
                    )
                }
            } finally {
                store.cleanup(uri, MmsPduDirection.DOWNLOAD_TARGET)
            }
        }
    }

    companion object {
        const val ACTION_MMS_DOWNLOADED = "org.aurorasms.core.telephony.action.MMS_DOWNLOADED"

        fun createIntent(context: Context, operationId: MessageId, stagedUri: Uri): Intent =
            Intent(context, MmsDownloadResultReceiver::class.java)
                .setAction(ACTION_MMS_DOWNLOADED)
                .putExtra(SmsSentReceiver.EXTRA_OPERATION_ID, operationId.value)
                .putExtra(MmsSendResultReceiver.EXTRA_STAGED_URI, stagedUri)
    }
}
