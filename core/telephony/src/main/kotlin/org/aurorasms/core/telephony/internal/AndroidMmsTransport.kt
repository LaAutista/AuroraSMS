// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.MmsDownloadRequest
import org.aurorasms.core.telephony.MmsSendRequest
import org.aurorasms.core.telephony.OutgoingMmsPayload
import org.aurorasms.core.telephony.SubscriptionRepository
import org.aurorasms.core.telephony.receiver.MmsDownloadResultReceiver
import org.aurorasms.core.telephony.receiver.MmsSendResultReceiver

class AndroidMmsTransport(
    context: Context,
    private val roleState: DefaultSmsRoleState,
    private val subscriptions: SubscriptionRepository,
    private val stagingStore: MmsPduStagingStore,
) {
    private val appContext = context.applicationContext

    suspend fun sendMms(request: MmsSendRequest): TransportResult {
        val rejection = preflight(request.subscriptionId.value)
        if (rejection != null) return request.rejected(rejection)
        val encoded = when (val payload = request.payload) {
            is OutgoingMmsPayload.Encoded -> payload.pdu
            is OutgoingMmsPayload.RequiresEncoding -> {
                return request.rejected(TransportResult.FailureReason.CODEC_UNAVAILABLE)
            }
        }
        val staged = when (val result = stagingStore.stageSend(request.operationId, encoded)) {
            is MmsStagingResult.Ready -> result.staged
            is MmsStagingResult.Failed -> {
                return request.rejected(
                    if (result.reason == MmsStagingResult.Reason.PAYLOAD_TOO_LARGE) {
                        TransportResult.FailureReason.PAYLOAD_TOO_LARGE
                    } else {
                        TransportResult.FailureReason.INTERNAL_ERROR
                    },
                )
            }
        }
        return try {
            val resultIntent = PendingIntent.getBroadcast(
                appContext,
                AndroidSmsTransport.requestCode(request.operationId.value, 0, MMS_SEND_CHANNEL),
                MmsSendResultReceiver.createIntent(appContext, request.operationId, staged.uri),
                CALLBACK_FLAGS,
            )
            val manager = smsManager(request.subscriptionId.value)
            if (Build.VERSION.SDK_INT >= 31) {
                manager.sendMultimediaMessage(
                    appContext,
                    staged.uri,
                    null,
                    null,
                    resultIntent,
                    request.operationId.value,
                )
            } else {
                manager.sendMultimediaMessage(appContext, staged.uri, null, null, resultIntent)
            }
            TransportResult.Submitted(request.operationId, MessageTransportKind.MMS, unitCount = 1)
        } catch (_: SecurityException) {
            stagingStore.cleanup(staged.uri, MmsPduDirection.SEND_SOURCE)
            request.failed(TransportResult.FailureReason.PERMISSION_DENIED, false)
        } catch (_: UnsupportedOperationException) {
            stagingStore.cleanup(staged.uri, MmsPduDirection.SEND_SOURCE)
            request.failed(TransportResult.FailureReason.FEATURE_UNAVAILABLE, false)
        } catch (_: IllegalArgumentException) {
            stagingStore.cleanup(staged.uri, MmsPduDirection.SEND_SOURCE)
            request.failed(TransportResult.FailureReason.PLATFORM_REJECTED, false)
        } catch (_: RuntimeException) {
            stagingStore.cleanup(staged.uri, MmsPduDirection.SEND_SOURCE)
            request.failed(TransportResult.FailureReason.INTERNAL_ERROR, true)
        }
    }

    suspend fun downloadMms(request: MmsDownloadRequest): TransportResult {
        val rejection = preflight(request.subscriptionId.value)
        if (rejection != null) return request.rejected(rejection)
        if (!isValidContentLocation(request.contentLocation)) {
            return request.rejected(TransportResult.FailureReason.PLATFORM_REJECTED)
        }
        val staged = when (val result = stagingStore.createDownloadTarget(request.operationId)) {
            is MmsStagingResult.Ready -> result.staged
            is MmsStagingResult.Failed -> return request.rejected(TransportResult.FailureReason.INTERNAL_ERROR)
        }
        return try {
            val resultIntent = PendingIntent.getBroadcast(
                appContext,
                AndroidSmsTransport.requestCode(request.operationId.value, 0, MMS_DOWNLOAD_CHANNEL),
                MmsDownloadResultReceiver.createIntent(appContext, request.operationId, staged.uri),
                CALLBACK_FLAGS,
            )
            val manager = smsManager(request.subscriptionId.value)
            if (Build.VERSION.SDK_INT >= 31) {
                manager.downloadMultimediaMessage(
                    appContext,
                    request.contentLocation,
                    staged.uri,
                    null,
                    resultIntent,
                    request.operationId.value,
                )
            } else {
                manager.downloadMultimediaMessage(
                    appContext,
                    request.contentLocation,
                    staged.uri,
                    null,
                    resultIntent,
                )
            }
            TransportResult.Submitted(request.operationId, MessageTransportKind.MMS, unitCount = 1)
        } catch (_: SecurityException) {
            stagingStore.cleanup(staged.uri, MmsPduDirection.DOWNLOAD_TARGET)
            request.failed(TransportResult.FailureReason.PERMISSION_DENIED, false)
        } catch (_: UnsupportedOperationException) {
            stagingStore.cleanup(staged.uri, MmsPduDirection.DOWNLOAD_TARGET)
            request.failed(TransportResult.FailureReason.FEATURE_UNAVAILABLE, false)
        } catch (_: IllegalArgumentException) {
            stagingStore.cleanup(staged.uri, MmsPduDirection.DOWNLOAD_TARGET)
            request.failed(TransportResult.FailureReason.PLATFORM_REJECTED, false)
        } catch (_: RuntimeException) {
            stagingStore.cleanup(staged.uri, MmsPduDirection.DOWNLOAD_TARGET)
            request.failed(TransportResult.FailureReason.INTERNAL_ERROR, true)
        }
    }

    private suspend fun preflight(subscriptionId: Int): TransportResult.FailureReason? =
        TransportPolicy.mmsRejection(
            roleHeld = roleState.isRoleHeld(),
            featureAvailable = hasMessagingFeature(),
            permissionGranted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED,
            subscriptionActive = subscriptions.activeSubscriptions().let { snapshot ->
                (snapshot as? org.aurorasms.core.telephony.SubscriptionSnapshot.Available)
                    ?.subscriptions
                    ?.any { it.id.value == subscriptionId && it.smsCapable } == true
            },
        )

    private fun hasMessagingFeature(): Boolean {
        val packageManager = appContext.packageManager
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return false
        return Build.VERSION.SDK_INT < 33 ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)
    }

    @Suppress("DEPRECATION")
    private fun smsManager(subscriptionId: Int): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) {
            checkNotNull(appContext.getSystemService(SmsManager::class.java)).createForSubscriptionId(subscriptionId)
        } else {
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }

    // The dependency policy intentionally admits androidx.core, not core-ktx;
    // keep the platform parser rather than widening runtime dependencies.
    @SuppressLint("UseKtx")
    private fun isValidContentLocation(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.fragment == null
    }.getOrDefault(false)

    companion object {
        private const val MMS_SEND_CHANNEL = 0x4D51
        private const val MMS_DOWNLOAD_CHANNEL = 0x4D71
        private const val CALLBACK_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
    }
}

private fun MmsSendRequest.rejected(reason: TransportResult.FailureReason): TransportResult.Rejected =
    TransportResult.Rejected(operationId, MessageTransportKind.MMS, reason)

private fun MmsSendRequest.failed(
    reason: TransportResult.FailureReason,
    retryable: Boolean,
): TransportResult.Failed = TransportResult.Failed(
    operationId = operationId,
    transport = MessageTransportKind.MMS,
    reason = reason,
    retryable = retryable,
)

private fun MmsDownloadRequest.rejected(reason: TransportResult.FailureReason): TransportResult.Rejected =
    TransportResult.Rejected(operationId, MessageTransportKind.MMS, reason)

private fun MmsDownloadRequest.failed(
    reason: TransportResult.FailureReason,
    retryable: Boolean,
): TransportResult.Failed = TransportResult.Failed(
    operationId = operationId,
    transport = MessageTransportKind.MMS,
    reason = reason,
    retryable = retryable,
)
