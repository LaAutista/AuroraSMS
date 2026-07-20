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
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.telephony.internal.MmsPduDirection
import org.aurorasms.core.telephony.internal.MmsPduStagingStore

class MmsSendResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MMS_SENT) return
        val operationId = intent.pendingOperationIdOrNull() ?: return
        val uri = intent.stagedUriOrNull() ?: return
        val providerIdentity = intent.outgoingMmsProviderIdentity()
        val code = resultCode
        dispatchAsync(context) { entryPoint ->
            MmsPduStagingStore(context).cleanup(uri, MmsPduDirection.SEND_SOURCE)
            val exactIdentity = providerIdentity as? OutgoingMmsProviderIntentIdentity.Exact
            val result = mmsSendResult(
                operationId = operationId,
                platformResultCode = code,
                providerId = exactIdentity?.providerId,
                conversationId = exactIdentity?.conversationId,
            )
            entryPoint.onTransportResult(result)
        }
    }

    companion object {
        const val ACTION_MMS_SENT = "org.aurorasms.core.telephony.action.MMS_SENT"
        internal const val EXTRA_STAGED_URI = "staged_uri"
        internal const val EXTRA_PROVIDER_ID = "provider_id"
        internal const val EXTRA_CONVERSATION_ID = "conversation_id"

        fun createIntent(
            context: Context,
            operationId: MessageId,
            stagedUri: Uri,
            providerId: ProviderMessageId? = null,
            conversationId: ConversationId? = null,
        ): Intent {
            require((providerId == null) == (conversationId == null)) {
                "Outgoing MMS callback identity must be complete"
            }
            require(providerId == null || providerId.kind == ProviderKind.MMS)
            return Intent(context, MmsSendResultReceiver::class.java)
                .setAction(ACTION_MMS_SENT)
                .putExtra(SmsSentReceiver.EXTRA_OPERATION_ID, operationId.value)
                .putExtra(EXTRA_STAGED_URI, stagedUri)
                .apply {
                    if (providerId != null && conversationId != null) {
                        putExtra(EXTRA_PROVIDER_ID, providerId.value)
                        putExtra(EXTRA_CONVERSATION_ID, conversationId.value)
                    }
                }
        }
    }
}

internal fun mmsSendResult(
    operationId: MessageId,
    platformResultCode: Int,
    providerId: ProviderMessageId? = null,
    conversationId: ConversationId? = null,
): TransportResult = if (platformResultCode == Activity.RESULT_OK) {
    TransportResult.Sent(
        operationId = operationId,
        transport = MessageTransportKind.MMS,
        platformResultCode = platformResultCode,
        providerMessageId = providerId,
        providerConversationId = conversationId,
    )
} else {
    TransportResult.Failed(
        operationId = operationId,
        transport = MessageTransportKind.MMS,
        reason = TransportResult.FailureReason.PLATFORM_REJECTED,
        retryable = platformResultCode == SmsManager.MMS_ERROR_RETRY ||
            platformResultCode == SmsManager.MMS_ERROR_NO_DATA_NETWORK,
        platformResultCode = platformResultCode,
        stage = TransportResult.FailureStage.SENT_CALLBACK,
        providerMessageId = providerId,
        providerConversationId = conversationId,
    )
}

internal sealed interface OutgoingMmsProviderIntentIdentity {
    data object Absent : OutgoingMmsProviderIntentIdentity
    data object Malformed : OutgoingMmsProviderIntentIdentity
    data class Exact(
        val providerId: ProviderMessageId,
        val conversationId: ConversationId,
    ) : OutgoingMmsProviderIntentIdentity
}

internal fun Intent.outgoingMmsProviderIdentity(): OutgoingMmsProviderIntentIdentity {
    val hasProvider = hasExtra(MmsSendResultReceiver.EXTRA_PROVIDER_ID)
    val hasConversation = hasExtra(MmsSendResultReceiver.EXTRA_CONVERSATION_ID)
    if (!hasProvider && !hasConversation) return OutgoingMmsProviderIntentIdentity.Absent
    if (!hasProvider || !hasConversation) return OutgoingMmsProviderIntentIdentity.Malformed
    val providerId = getLongExtra(MmsSendResultReceiver.EXTRA_PROVIDER_ID, 0L)
    val conversationId = getLongExtra(MmsSendResultReceiver.EXTRA_CONVERSATION_ID, 0L)
    if (providerId <= 0L || conversationId <= 0L) return OutgoingMmsProviderIntentIdentity.Malformed
    return OutgoingMmsProviderIntentIdentity.Exact(
        ProviderMessageId(ProviderKind.MMS, providerId),
        ConversationId(conversationId),
    )
}

@Suppress("DEPRECATION")
internal fun Intent.stagedUriOrNull(): Uri? =
    if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(MmsSendResultReceiver.EXTRA_STAGED_URI, Uri::class.java)
    } else {
        getParcelableExtra(MmsSendResultReceiver.EXTRA_STAGED_URI)
    }
