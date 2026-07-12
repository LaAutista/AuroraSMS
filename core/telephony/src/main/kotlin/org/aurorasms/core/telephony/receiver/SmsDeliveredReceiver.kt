// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.TransportResult

class SmsDeliveredReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SMS_DELIVERED) return
        val operationId = intent.pendingOperationIdOrNull() ?: return
        val unitIndex = intent.validUnitIndexOrNull() ?: return
        val unitCount = intent.validUnitCountOrNull() ?: return
        if (unitIndex >= unitCount) return
        val providerMessageId = intent.smsProviderIdOrNull()
        val code = resultCode
        val result = if (code == Activity.RESULT_OK) {
            TransportResult.Delivered(
                operationId = operationId,
                transport = MessageTransportKind.SMS,
                platformResultCode = code,
                unitIndex = unitIndex,
                unitCount = unitCount,
                providerMessageId = providerMessageId,
            )
        } else {
            TransportResult.Failed(
                operationId = operationId,
                transport = MessageTransportKind.SMS,
                reason = TransportResult.FailureReason.PLATFORM_REJECTED,
                retryable = false,
                platformResultCode = code,
                unitIndex = unitIndex,
                unitCount = unitCount,
                providerMessageId = providerMessageId,
            )
        }
        dispatchAsync(context) { it.onTransportResult(result) }
    }

    companion object {
        const val ACTION_SMS_DELIVERED = "org.aurorasms.core.telephony.action.SMS_DELIVERED"

        fun createIntent(
            context: Context,
            operationId: org.aurorasms.core.model.MessageId,
            providerMessageId: org.aurorasms.core.model.ProviderMessageId,
            unitIndex: Int,
            unitCount: Int,
        ): Intent = Intent(context, SmsDeliveredReceiver::class.java)
            .setAction(ACTION_SMS_DELIVERED)
            .putExtra(SmsSentReceiver.EXTRA_OPERATION_ID, operationId.value)
            .putExtra(SmsSentReceiver.EXTRA_PROVIDER_ID, providerMessageId.value)
            .putExtra(SmsSentReceiver.EXTRA_UNIT_INDEX, unitIndex)
            .putExtra(SmsSentReceiver.EXTRA_UNIT_COUNT, unitCount)
    }
}
