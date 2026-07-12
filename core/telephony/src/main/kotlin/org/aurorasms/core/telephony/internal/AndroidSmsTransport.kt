// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.ArrayList
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.MessageTransport
import org.aurorasms.core.telephony.MmsDownloadRequest
import org.aurorasms.core.telephony.MmsSendRequest
import org.aurorasms.core.telephony.OutgoingSmsRecord
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderStoredMessage
import org.aurorasms.core.telephony.SmsProviderDataSource
import org.aurorasms.core.telephony.SmsProviderStatus
import org.aurorasms.core.telephony.SmsSendRequest
import org.aurorasms.core.telephony.SubscriptionRepository
import org.aurorasms.core.telephony.receiver.SmsDeliveredReceiver
import org.aurorasms.core.telephony.receiver.SmsSentReceiver

class AndroidSmsTransport(
    context: Context,
    private val roleState: DefaultSmsRoleState,
    private val subscriptions: SubscriptionRepository,
    private val smsProvider: SmsProviderDataSource,
    private val mmsTransport: AndroidMmsTransport,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : MessageTransport {
    private val appContext = context.applicationContext

    override suspend fun sendSms(request: SmsSendRequest): TransportResult {
        val recipient = request.recipients.singleSmsRecipientOrNull()
        val rejection = TransportPolicy.smsRejection(
            roleHeld = roleState.isRoleHeld(),
            featureAvailable = hasMessagingFeature(),
            permissionGranted = hasSendPermission(),
            subscriptionActive = subscriptions.findActive(request.subscriptionId)?.smsCapable == true,
            singleRecipient = recipient != null,
            contentPresent = request.body.isNotEmpty(),
            emergencyRecipient = recipient?.let(::isEmergencyNumber) == true,
        )
        if (rejection != null) {
            return TransportResult.Rejected(request.operationId, MessageTransportKind.SMS, rejection)
        }
        checkNotNull(recipient)

        val stored = when (
            val result = smsProvider.insertOutgoing(
                OutgoingSmsRecord(
                    recipient = recipient,
                    body = request.body,
                    timestampMillis = nowMillis().coerceAtLeast(0L),
                    subscriptionId = request.subscriptionId,
                ),
            )
        ) {
            is ProviderAccessResult.Success -> result.value
            ProviderAccessResult.RoleRequired -> return request.rejected(TransportResult.FailureReason.ROLE_NOT_HELD)
            ProviderAccessResult.PermissionDenied -> return request.rejected(TransportResult.FailureReason.PERMISSION_DENIED)
            is ProviderAccessResult.InvalidInput -> return request.rejected(TransportResult.FailureReason.INVALID_RECIPIENT)
            is ProviderAccessResult.Unsupported,
            is ProviderAccessResult.Unavailable
            -> return request.rejected(TransportResult.FailureReason.PROVIDER_UNAVAILABLE)
        }

        return try {
            val manager = smsManager(request.subscriptionId.value)
            val parts = manager.divideMessage(request.body)
            if (parts.isEmpty() || parts.size > MAX_SMS_PARTS) {
                smsProvider.updateStatus(stored.providerId, SmsProviderStatus.FAILED)
                return request.failed(
                    reason = TransportResult.FailureReason.PAYLOAD_TOO_LARGE,
                    retryable = false,
                    provider = stored,
                )
            }
            val sent = ArrayList<PendingIntent>(parts.size)
            val delivered = if (request.requestDeliveryReport) ArrayList<PendingIntent>(parts.size) else null
            parts.indices.forEach { index ->
                sent += sentPendingIntent(request, stored, index, parts.size)
                delivered?.add(deliveredPendingIntent(request, stored, index, parts.size))
            }
            if (parts.size == 1) {
                if (Build.VERSION.SDK_INT >= 30) {
                    manager.sendTextMessage(
                        recipient.value,
                        null,
                        parts.single(),
                        sent.single(),
                        delivered?.singleOrNull(),
                        request.operationId.value,
                    )
                } else {
                    manager.sendTextMessage(
                        recipient.value,
                        null,
                        parts.single(),
                        sent.single(),
                        delivered?.singleOrNull(),
                    )
                }
            } else if (Build.VERSION.SDK_INT >= 30) {
                manager.sendMultipartTextMessage(
                    recipient.value,
                    null,
                    parts,
                    sent,
                    delivered,
                    request.operationId.value,
                )
            } else {
                manager.sendMultipartTextMessage(recipient.value, null, parts, sent, delivered)
            }
            TransportResult.Submitted(
                operationId = request.operationId,
                transport = MessageTransportKind.SMS,
                unitCount = parts.size,
                providerMessageId = stored.providerId,
            )
        } catch (_: SecurityException) {
            smsProvider.updateStatus(stored.providerId, SmsProviderStatus.FAILED)
            request.failed(TransportResult.FailureReason.PERMISSION_DENIED, false, stored)
        } catch (_: UnsupportedOperationException) {
            smsProvider.updateStatus(stored.providerId, SmsProviderStatus.FAILED)
            request.failed(TransportResult.FailureReason.FEATURE_UNAVAILABLE, false, stored)
        } catch (_: IllegalArgumentException) {
            smsProvider.updateStatus(stored.providerId, SmsProviderStatus.FAILED)
            request.failed(TransportResult.FailureReason.INVALID_RECIPIENT, false, stored)
        } catch (_: RuntimeException) {
            smsProvider.updateStatus(stored.providerId, SmsProviderStatus.FAILED)
            request.failed(TransportResult.FailureReason.INTERNAL_ERROR, true, stored)
        }
    }

    override suspend fun sendMms(request: MmsSendRequest): TransportResult =
        mmsTransport.sendMms(request)

    override suspend fun downloadMms(request: MmsDownloadRequest): TransportResult =
        mmsTransport.downloadMms(request)

    private fun hasSendPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasMessagingFeature(): Boolean {
        val packageManager = appContext.packageManager
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return false
        return Build.VERSION.SDK_INT < 33 ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)
    }

    private fun isEmergencyNumber(address: org.aurorasms.core.model.ParticipantAddress): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                appContext.getSystemService(TelephonyManager::class.java)?.isEmergencyNumber(address.value) == true
            } else {
                @Suppress("DEPRECATION")
                PhoneNumberUtils.isEmergencyNumber(address.value)
            }
        } catch (_: RuntimeException) {
            true
        }

    @Suppress("DEPRECATION")
    private fun smsManager(subscriptionId: Int): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) {
            checkNotNull(appContext.getSystemService(SmsManager::class.java)).createForSubscriptionId(subscriptionId)
        } else {
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }

    private fun sentPendingIntent(
        request: SmsSendRequest,
        stored: ProviderStoredMessage,
        unitIndex: Int,
        unitCount: Int,
    ): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        requestCode(request.operationId.value, unitIndex, SENT_CHANNEL),
        SmsSentReceiver.createIntent(appContext, request.operationId, stored.providerId, unitIndex, unitCount),
        CALLBACK_FLAGS,
    )

    private fun deliveredPendingIntent(
        request: SmsSendRequest,
        stored: ProviderStoredMessage,
        unitIndex: Int,
        unitCount: Int,
    ): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        requestCode(request.operationId.value, unitIndex, DELIVERED_CHANNEL),
        SmsDeliveredReceiver.createIntent(appContext, request.operationId, stored.providerId, unitIndex, unitCount),
        CALLBACK_FLAGS,
    )

    companion object {
        private const val MAX_SMS_PARTS = 255
        private const val SENT_CHANNEL = 0x51
        private const val DELIVERED_CHANNEL = 0x71
        private const val CALLBACK_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE

        internal fun requestCode(operationId: Long, unitIndex: Int, channel: Int): Int =
            (operationId xor (unitIndex.toLong() shl 32) xor channel.toLong()).hashCode() and Int.MAX_VALUE
    }
}

internal object TransportPolicy {
    fun smsRejection(
        roleHeld: Boolean,
        featureAvailable: Boolean,
        permissionGranted: Boolean,
        subscriptionActive: Boolean,
        singleRecipient: Boolean,
        contentPresent: Boolean,
        emergencyRecipient: Boolean,
    ): TransportResult.FailureReason? = when {
        !roleHeld -> TransportResult.FailureReason.ROLE_NOT_HELD
        !featureAvailable -> TransportResult.FailureReason.FEATURE_UNAVAILABLE
        !permissionGranted -> TransportResult.FailureReason.PERMISSION_DENIED
        !subscriptionActive -> TransportResult.FailureReason.SUBSCRIPTION_UNAVAILABLE
        !singleRecipient || emergencyRecipient -> TransportResult.FailureReason.INVALID_RECIPIENT
        !contentPresent -> TransportResult.FailureReason.EMPTY_CONTENT
        else -> null
    }

    fun mmsRejection(
        roleHeld: Boolean,
        featureAvailable: Boolean,
        permissionGranted: Boolean,
        subscriptionActive: Boolean,
    ): TransportResult.FailureReason? = when {
        !roleHeld -> TransportResult.FailureReason.ROLE_NOT_HELD
        !featureAvailable -> TransportResult.FailureReason.FEATURE_UNAVAILABLE
        !permissionGranted -> TransportResult.FailureReason.PERMISSION_DENIED
        !subscriptionActive -> TransportResult.FailureReason.SUBSCRIPTION_UNAVAILABLE
        else -> null
    }
}

private fun SmsSendRequest.rejected(reason: TransportResult.FailureReason): TransportResult.Rejected =
    TransportResult.Rejected(operationId, MessageTransportKind.SMS, reason)

private fun SmsSendRequest.failed(
    reason: TransportResult.FailureReason,
    retryable: Boolean,
    provider: ProviderStoredMessage,
): TransportResult.Failed = TransportResult.Failed(
    operationId = operationId,
    transport = MessageTransportKind.SMS,
    reason = reason,
    retryable = retryable,
    providerMessageId = provider.providerId,
)
