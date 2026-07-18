// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.TransportResult

class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SMS_SENT) return
        val operationId = intent.pendingOperationIdOrNull() ?: return
        val unitIndex = intent.validUnitIndexOrNull() ?: return
        val unitCount = intent.validUnitCountOrNull() ?: return
        if (unitIndex >= unitCount) return
        val providerMessageId = intent.smsProviderIdOrNull()
        val providerConversationId = intent.smsProviderConversationIdOrNull()
        val operationOrigin = intent.transportOperationOrigin()
        val code = resultCode
        val result = smsSentResult(
            operationId,
            providerMessageId,
            unitIndex,
            unitCount,
            code,
            operationOrigin,
            providerConversationId,
        )
        dispatchAsync(context) { it.onTransportResult(result) }
    }

    companion object {
        const val ACTION_SMS_SENT = "org.aurorasms.core.telephony.action.SMS_SENT"
        internal const val EXTRA_OPERATION_ID = "operation_id"
        internal const val EXTRA_UNIT_INDEX = "unit_index"
        internal const val EXTRA_UNIT_COUNT = "unit_count"
        internal const val EXTRA_PROVIDER_ID = "provider_id"
        internal const val EXTRA_INLINE_REPLY_OWNED = "inline_reply_owned_v1"
        internal const val EXTRA_PROVIDER_CONVERSATION_ID = "provider_conversation_id_v2"
        internal const val EXTRA_OPERATION_ORIGIN = "operation_origin_v2"

        fun createIntent(
            context: Context,
            operationId: MessageId,
            providerMessageId: ProviderMessageId,
            unitIndex: Int,
            unitCount: Int,
            operationOrigin: TransportResult.OperationOrigin =
                TransportResult.OperationOrigin.UNMARKED,
            providerConversationId: ConversationId? = null,
        ): Intent {
            require(providerMessageId.kind == ProviderKind.SMS && providerMessageId.value > 0L)
            require(unitCount in 1..255 && unitIndex in 0 until unitCount)
            return Intent(context, SmsSentReceiver::class.java)
                .setAction(ACTION_SMS_SENT)
                .setData(
                    smsCallbackIdentityUri(
                        channel = SENT_IDENTITY_CHANNEL,
                        operationOrigin = operationOrigin,
                        operationId = operationId,
                        unitIndex = unitIndex,
                    ),
                )
                .putExtra(EXTRA_OPERATION_ID, operationId.value)
                .putExtra(EXTRA_PROVIDER_ID, providerMessageId.value)
                .putExtra(EXTRA_UNIT_INDEX, unitIndex)
                .putExtra(EXTRA_UNIT_COUNT, unitCount)
                .putExtra(EXTRA_OPERATION_ORIGIN, operationOrigin.toStorageCode())
                .putExtra(
                    EXTRA_INLINE_REPLY_OWNED,
                    operationOrigin == TransportResult.OperationOrigin.INLINE_REPLY,
                )
                .also { intent ->
                    providerConversationId?.let { conversationId ->
                        intent.putExtra(EXTRA_PROVIDER_CONVERSATION_ID, conversationId.value)
                    }
                }
        }

        private const val SENT_IDENTITY_CHANNEL = "sent"
    }
}

internal fun smsSentResult(
    operationId: MessageId,
    providerMessageId: ProviderMessageId?,
    unitIndex: Int,
    unitCount: Int,
    platformResultCode: Int,
    operationOrigin: TransportResult.OperationOrigin = TransportResult.OperationOrigin.UNMARKED,
    providerConversationId: ConversationId? = null,
): TransportResult = if (platformResultCode == Activity.RESULT_OK) {
    TransportResult.Sent(
        operationId = operationId,
        transport = MessageTransportKind.SMS,
        platformResultCode = platformResultCode,
        unitIndex = unitIndex,
        unitCount = unitCount,
        providerMessageId = providerMessageId,
        providerConversationId = providerConversationId,
        operationOrigin = operationOrigin,
    )
} else {
    TransportResult.Failed(
        operationId = operationId,
        transport = MessageTransportKind.SMS,
        reason = TransportResult.FailureReason.PLATFORM_REJECTED,
        retryable = platformResultCode.isRetryableSmsFailure(),
        platformResultCode = platformResultCode,
        unitIndex = unitIndex,
        unitCount = unitCount,
        providerMessageId = providerMessageId,
        providerConversationId = providerConversationId,
        stage = TransportResult.FailureStage.SENT_CALLBACK,
        operationOrigin = operationOrigin,
    )
}

internal fun Intent.pendingOperationIdOrNull(): MessageId? =
    getLongExtra(SmsSentReceiver.EXTRA_OPERATION_ID, -1L)
        .takeIf { it > 0L }
        ?.let { MessageId(ProviderKind.PENDING_OPERATION, it) }

internal fun Intent.validUnitIndexOrNull(): Int? =
    getIntExtra(SmsSentReceiver.EXTRA_UNIT_INDEX, -1).takeIf { it >= 0 }

internal fun Intent.validUnitCountOrNull(): Int? =
    getIntExtra(SmsSentReceiver.EXTRA_UNIT_COUNT, -1).takeIf { it in 1..255 }

internal fun Intent.smsProviderIdOrNull(): ProviderMessageId? =
    getLongExtra(SmsSentReceiver.EXTRA_PROVIDER_ID, -1L)
        .takeIf { it > 0L }
        ?.let { ProviderMessageId(ProviderKind.SMS, it) }

internal fun Intent.smsProviderConversationIdOrNull(): ConversationId? =
    getLongExtra(SmsSentReceiver.EXTRA_PROVIDER_CONVERSATION_ID, -1L)
        .takeIf { it > 0L }
        ?.let(::ConversationId)

internal fun Intent.transportOperationOrigin(): TransportResult.OperationOrigin =
    if (hasExtra(SmsSentReceiver.EXTRA_OPERATION_ORIGIN)) {
        when (getIntExtra(SmsSentReceiver.EXTRA_OPERATION_ORIGIN, -1)) {
            OPERATION_ORIGIN_INLINE_REPLY -> TransportResult.OperationOrigin.INLINE_REPLY
            OPERATION_ORIGIN_COMPOSER -> TransportResult.OperationOrigin.COMPOSER
            else -> TransportResult.OperationOrigin.UNMARKED
        }
    } else if (getBooleanExtra(SmsSentReceiver.EXTRA_INLINE_REPLY_OWNED, false)) {
        TransportResult.OperationOrigin.INLINE_REPLY
    } else {
        TransportResult.OperationOrigin.UNMARKED
    }

internal fun TransportResult.OperationOrigin.toStorageCode(): Int = when (this) {
    TransportResult.OperationOrigin.UNMARKED -> OPERATION_ORIGIN_UNMARKED
    TransportResult.OperationOrigin.INLINE_REPLY -> OPERATION_ORIGIN_INLINE_REPLY
    TransportResult.OperationOrigin.COMPOSER -> OPERATION_ORIGIN_COMPOSER
}

internal fun smsCallbackIdentityUri(
    channel: String,
    operationOrigin: TransportResult.OperationOrigin,
    operationId: MessageId,
    unitIndex: Int,
): Uri {
    require(channel == "sent" || channel == "delivered") { "Unknown SMS callback channel" }
    require(operationId.kind == ProviderKind.PENDING_OPERATION && operationId.value > 0L)
    require(unitIndex >= 0)
    return Uri.Builder()
        .scheme("aurorasms-sms-callback")
        .authority(channel)
        .appendPath(operationOrigin.identityToken())
        .appendPath(operationId.value.toString())
        .appendPath(unitIndex.toString())
        .build()
}

private fun TransportResult.OperationOrigin.identityToken(): String = when (this) {
    TransportResult.OperationOrigin.UNMARKED -> "unmarked"
    TransportResult.OperationOrigin.INLINE_REPLY -> "inline-reply"
    TransportResult.OperationOrigin.COMPOSER -> "composer"
}

private fun Int.isRetryableSmsFailure(): Boolean =
    this == SmsManager.RESULT_ERROR_GENERIC_FAILURE ||
        this == SmsManager.RESULT_ERROR_RADIO_OFF ||
        this == SmsManager.RESULT_ERROR_NO_SERVICE

private const val OPERATION_ORIGIN_UNMARKED: Int = 0
private const val OPERATION_ORIGIN_INLINE_REPLY: Int = 1
private const val OPERATION_ORIGIN_COMPOSER: Int = 2
