// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.TransportResult

class SmsDeliveredReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SMS_DELIVERED) return
        val operationId = intent.pendingOperationIdOrNull() ?: return
        val unitIndex = intent.validUnitIndexOrNull() ?: return
        val unitCount = intent.validUnitCountOrNull() ?: return
        if (unitIndex >= unitCount) return
        val providerMessageId = intent.smsProviderIdOrNull()
        val operationOrigin = intent.transportOperationOrigin()
        val code = resultCode
        val result = smsDeliveredResult(
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
        const val ACTION_SMS_DELIVERED = "org.aurorasms.core.telephony.action.SMS_DELIVERED"

        fun createIntent(
            context: Context,
            operationId: MessageId,
            providerMessageId: ProviderMessageId,
            unitIndex: Int,
            unitCount: Int,
            operationOrigin: TransportResult.OperationOrigin =
                TransportResult.OperationOrigin.UNMARKED,
        ): Intent = Intent(context, SmsDeliveredReceiver::class.java)
            .setAction(ACTION_SMS_DELIVERED)
            .setData(
                smsCallbackIdentityUri(
                    channel = DELIVERED_IDENTITY_CHANNEL,
                    operationId = operationId,
                    providerMessageId = providerMessageId,
                    unitIndex = unitIndex,
                    unitCount = unitCount,
                ),
            )
            .putExtra(SmsSentReceiver.EXTRA_OPERATION_ID, operationId.value)
            .putExtra(SmsSentReceiver.EXTRA_PROVIDER_ID, providerMessageId.value)
            .putExtra(SmsSentReceiver.EXTRA_UNIT_INDEX, unitIndex)
            .putExtra(SmsSentReceiver.EXTRA_UNIT_COUNT, unitCount)
            .putExtra(
                SmsSentReceiver.EXTRA_INLINE_REPLY_OWNED,
                operationOrigin == TransportResult.OperationOrigin.INLINE_REPLY,
            )

        private const val DELIVERED_IDENTITY_CHANNEL = "delivered"
    }
}

internal fun smsDeliveredResult(
    operationId: MessageId,
    providerMessageId: ProviderMessageId?,
    unitIndex: Int,
    unitCount: Int,
    platformResultCode: Int,
    operationOrigin: TransportResult.OperationOrigin = TransportResult.OperationOrigin.UNMARKED,
): TransportResult = if (platformResultCode == Activity.RESULT_OK) {
    TransportResult.Delivered(
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
        retryable = false,
        platformResultCode = platformResultCode,
        unitIndex = unitIndex,
        unitCount = unitCount,
        providerMessageId = providerMessageId,
        stage = TransportResult.FailureStage.DELIVERY_CALLBACK,
        operationOrigin = operationOrigin,
    )
}
