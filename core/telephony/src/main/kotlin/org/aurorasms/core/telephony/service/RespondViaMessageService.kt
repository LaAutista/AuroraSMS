// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.SubscriptionManager
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.INLINE_REPLY_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.telephony.MmsSendRequest
import org.aurorasms.core.telephony.OutgoingMmsPayload
import org.aurorasms.core.telephony.RecipientSet
import org.aurorasms.core.telephony.SmsSendRequest
import org.aurorasms.core.telephony.TelephonyEntryPoint

class RespondViaMessageService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent?.toRequestOrNull() ?: return stop(startId)
        val entryPoint = applicationContext as? TelephonyEntryPoint ?: return stop(startId)
        if (!entryPoint.defaultSmsRoleState.isRoleHeld()) return stop(startId)

        serviceScope.launch {
            try {
                val operationId = MessageId(ProviderKind.PENDING_OPERATION, nextOperationId())
                val result = when (request.recipients.requiredTransport()) {
                    MessageTransportKind.SMS -> entryPoint.messageTransport.sendSms(
                        SmsSendRequest(
                            operationId = operationId,
                            recipients = request.recipients,
                            body = request.body,
                            subscriptionId = request.subscriptionId,
                        ),
                    )
                    MessageTransportKind.MMS -> entryPoint.messageTransport.sendMms(
                        MmsSendRequest(
                            operationId = operationId,
                            recipients = request.recipients,
                            payload = OutgoingMmsPayload.RequiresEncoding(
                                text = request.body,
                                subject = null,
                                attachmentCount = 0,
                            ),
                            subscriptionId = request.subscriptionId,
                        ),
                    )
                }
                entryPoint.onTransportResult(result)
            } finally {
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun stop(startId: Int): Int {
        stopSelfResult(startId)
        return START_NOT_STICKY
    }

    private fun Intent.toRequestOrNull(): RespondRequest? {
        if (action != ACTION_RESPOND_VIA_MESSAGE) return null
        val uri = data ?: return null
        if (uri.scheme?.lowercase() !in APPROVED_SCHEMES) return null
        val rawRecipientPart = uri.schemeSpecificPart
            ?.substringBefore('?')
            ?.takeIf { it.length <= MAX_RECIPIENT_URI_CHARACTERS }
            ?: return null
        val recipients = when (
            val result = RecipientSet.parse(rawRecipientPart.split(',', ';').take(RecipientSet.MAX_RECIPIENTS + 1))
        ) {
            is RecipientSet.CreationResult.Valid -> result.recipients
            is RecipientSet.CreationResult.Rejected -> return null
        }
        val body = getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?.toString()
            ?.takeIf { it.isNotEmpty() && it.length <= SmsSendRequest.MAX_OUTGOING_TEXT_CHARACTERS }
            ?: return null
        val rawSubscription = getIntExtra(
            SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
            SubscriptionManager.INVALID_SUBSCRIPTION_ID,
        )
        if (rawSubscription < 0) return null
        return RespondRequest(recipients, body, AuroraSubscriptionId(rawSubscription))
    }

    private data class RespondRequest(
        val recipients: RecipientSet,
        val body: String,
        val subscriptionId: AuroraSubscriptionId,
    )

    companion object {
        private const val ACTION_RESPOND_VIA_MESSAGE = "android.intent.action.RESPOND_VIA_MESSAGE"
        private const val MAX_RECIPIENT_URI_CHARACTERS = 32_000
        private val APPROVED_SCHEMES = setOf("sms", "smsto", "mms", "mmsto")
        private val random = SecureRandom()

        private fun nextOperationId(): Long = nextOrdinaryOperationId(random::nextLong)
    }
}

internal fun nextOrdinaryOperationId(nextLong: () -> Long): Long {
    var candidate: Long
    do {
        candidate = nextLong() and (INLINE_REPLY_OPERATION_ID_BOUNDARY - 1L)
    } while (candidate == 0L)
    return candidate
}
