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
        val operationOrigin = intent.transportOperationOrigin()
        val code = resultCode
        val result = smsSentResult(
            operationId,
            providerMessageId,
            unitIndex,
            unitCount,
            code,
            operationOrigin,
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

        fun createIntent(
            context: Context,
            operationId: MessageId,
            providerMessageId: ProviderMessageId,
            unitIndex: Int,
            unitCount: Int,
            operationOrigin: TransportResult.OperationOrigin =
                TransportResult.OperationOrigin.UNMARKED,
        ): Intent = Intent(context, SmsSentReceiver::class.java)
            .setAction(ACTION_SMS_SENT)
            .setData(
                smsCallbackIdentityUri(
                    channel = SENT_IDENTITY_CHANNEL,
                    operationId = operationId,
                    providerMessageId = providerMessageId,
                    unitIndex = unitIndex,
                    unitCount = unitCount,
                ),
            )
            .putExtra(EXTRA_OPERATION_ID, operationId.value)
            .putExtra(EXTRA_PROVIDER_ID, providerMessageId.value)
            .putExtra(EXTRA_UNIT_INDEX, unitIndex)
            .putExtra(EXTRA_UNIT_COUNT, unitCount)
            .putExtra(
                EXTRA_INLINE_REPLY_OWNED,
                operationOrigin == TransportResult.OperationOrigin.INLINE_REPLY,
            )

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
): TransportResult = if (platformResultCode == Activity.RESULT_OK) {
    TransportResult.Sent(
        operationId = operationId,
        transport = MessageTransportKind.SMS,
        platformResultCode = platformResultCode,
        unitIndex = unitIndex,
        unitCount = unitCount,
        providerMessageId = providerMessageId,
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

internal fun Intent.transportOperationOrigin(): TransportResult.OperationOrigin =
    if (getBooleanExtra(SmsSentReceiver.EXTRA_INLINE_REPLY_OWNED, false)) {
        TransportResult.OperationOrigin.INLINE_REPLY
    } else {
        TransportResult.OperationOrigin.UNMARKED
    }

internal fun smsCallbackIdentityUri(
    channel: String,
    operationId: MessageId,
    providerMessageId: ProviderMessageId,
    unitIndex: Int,
    unitCount: Int,
): Uri {
    require(channel == "sent" || channel == "delivered") { "Unknown SMS callback channel" }
    require(operationId.kind == ProviderKind.PENDING_OPERATION && operationId.value > 0L)
    require(providerMessageId.kind == ProviderKind.SMS && providerMessageId.value > 0L)
    require(unitCount in 1..255 && unitIndex in 0 until unitCount)
    return Uri.Builder()
        .scheme("aurorasms-sms-callback")
        .authority(channel)
        .appendPath(operationId.value.toString())
        .appendPath(providerMessageId.value.toString())
        .appendPath(unitIndex.toString())
        .appendPath(unitCount.toString())
        .build()
}

private fun Int.isRetryableSmsFailure(): Boolean =
    this == SmsManager.RESULT_ERROR_GENERIC_FAILURE ||
        this == SmsManager.RESULT_ERROR_RADIO_OFF ||
        this == SmsManager.RESULT_ERROR_NO_SERVICE
