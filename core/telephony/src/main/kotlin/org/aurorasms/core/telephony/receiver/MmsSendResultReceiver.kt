// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.telephony.internal.MmsPduDirection
import org.aurorasms.core.telephony.internal.MmsPduStagingStore

class MmsSendResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MMS_SENT) return
        val operationId = intent.pendingOperationIdOrNull() ?: return
        val uri = intent.stagedUriOrNull() ?: return
        val code = resultCode
        dispatchAsync(context) { entryPoint ->
            MmsPduStagingStore(context).cleanup(uri, MmsPduDirection.SEND_SOURCE)
            val result = mmsSendResult(operationId, code)
            entryPoint.onTransportResult(result)
        }
    }

    companion object {
        const val ACTION_MMS_SENT = "org.aurorasms.core.telephony.action.MMS_SENT"
        internal const val EXTRA_STAGED_URI = "staged_uri"

        fun createIntent(context: Context, operationId: MessageId, stagedUri: Uri): Intent =
            Intent(context, MmsSendResultReceiver::class.java)
                .setAction(ACTION_MMS_SENT)
                .putExtra(SmsSentReceiver.EXTRA_OPERATION_ID, operationId.value)
                .putExtra(EXTRA_STAGED_URI, stagedUri)
    }
}

internal fun mmsSendResult(
    operationId: MessageId,
    platformResultCode: Int,
): TransportResult = if (platformResultCode == Activity.RESULT_OK) {
    TransportResult.Sent(operationId, MessageTransportKind.MMS, platformResultCode)
} else {
    TransportResult.Failed(
        operationId = operationId,
        transport = MessageTransportKind.MMS,
        reason = TransportResult.FailureReason.PLATFORM_REJECTED,
        retryable = platformResultCode == SmsManager.MMS_ERROR_RETRY ||
            platformResultCode == SmsManager.MMS_ERROR_NO_DATA_NETWORK,
        platformResultCode = platformResultCode,
        stage = TransportResult.FailureStage.SENT_CALLBACK,
    )
}

@Suppress("DEPRECATION")
internal fun Intent.stagedUriOrNull(): Uri? =
    if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(MmsSendResultReceiver.EXTRA_STAGED_URI, Uri::class.java)
    } else {
        getParcelableExtra(MmsSendResultReceiver.EXTRA_STAGED_URI)
    }
