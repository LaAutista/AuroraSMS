// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SubscriptionManager
import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageDeliveryFingerprint
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.telephony.IncomingMessage
import org.aurorasms.core.telephony.TelephonyEntryPoint

class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val entryPoint = context.telephonyEntryPointOrNull() ?: return
        if (!entryPoint.defaultSmsRoleState.isRoleHeld()) return
        val rawPdus = boundedPdusOrNull(intent) ?: return
        val subscriptionId = intent.subscriptionIdOrNull()
        val rawFormat = intent.getStringExtra(EXTRA_SMS_FORMAT)
        if (rawFormat != null && !rawFormat.isBoundedSmsFormat()) return
        val format = rawFormat.orEmpty()
        val deliveryFingerprint = SmsDeliveryFingerprintFactory.create(
            pdus = rawPdus,
            format = format,
            subscriptionId = subscriptionId,
        ) ?: return

        val incoming = runCatching {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                .takeIf { it.isNotEmpty() && it.size <= IncomingMessage.Sms.MAX_SMS_PDUS }
                ?: return@runCatching null
            val rawSender = messages.first().displayOriginatingAddress?.trim().orEmpty()
            val sender = runCatching { ParticipantAddress(rawSender) }.getOrNull()
                ?: return@runCatching null
            if (messages.any { it.displayOriginatingAddress?.trim() != rawSender }) {
                return@runCatching null
            }
            val body = buildString {
                messages.forEach { append(it.displayMessageBody.orEmpty()) }
            }
            if (body.length > MAX_INCOMING_SMS_CHARACTERS) return@runCatching null
            IncomingMessage.Sms(
                deliveryFingerprint = deliveryFingerprint,
                sender = sender,
                body = body,
                sentTimestampMillis = messages.minOf { it.timestampMillis.coerceAtLeast(0L) },
                receivedTimestampMillis = System.currentTimeMillis(),
                subscriptionId = subscriptionId,
                sourcePduCount = messages.size,
            )
        }.getOrNull() ?: return
        dispatchAsync(context) { it.incomingMessageSink.persist(incoming) }
    }

    private fun boundedPdusOrNull(intent: Intent): List<ByteArray>? {
        val raw = intent.rawPdusOrNull() ?: return null
        if (raw.isEmpty() || raw.size > IncomingMessage.Sms.MAX_SMS_PDUS) return null
        var total = 0
        val result = ArrayList<ByteArray>(raw.size)
        raw.forEach { entry ->
            val bytes = entry as? ByteArray ?: return null
            if (bytes.isEmpty() || bytes.size > MAX_TOTAL_SMS_PDU_BYTES - total) return null
            total += bytes.size
            result += bytes
        }
        return result
    }

    companion object {
        private const val MAX_TOTAL_SMS_PDU_BYTES = 256 * 1_024
        private const val MAX_INCOMING_SMS_CHARACTERS = 100_000
        private const val MAX_SMS_FORMAT_CHARACTERS = 16
        private const val EXTRA_SMS_FORMAT = "format"
    }
}

internal object SmsDeliveryFingerprintFactory {
    private val domain = "AuroraSMS.SMS_DELIVERY.v1".toByteArray(StandardCharsets.US_ASCII)

    fun create(
        pdus: List<ByteArray>,
        format: String,
        subscriptionId: AuroraSubscriptionId?,
    ): MessageDeliveryFingerprint? = runCatching {
        require(pdus.isNotEmpty() && pdus.size <= IncomingMessage.Sms.MAX_SMS_PDUS)
        require(format.isBoundedSmsFormat())
        var totalBytes = 0
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(domain)
        digest.updateInt(subscriptionId?.value ?: -1)
        val formatBytes = format.toByteArray(StandardCharsets.US_ASCII)
        digest.updateInt(formatBytes.size)
        digest.update(formatBytes)
        digest.updateInt(pdus.size)
        pdus.forEach { pdu ->
            require(pdu.isNotEmpty())
            require(pdu.size <= 256 * 1_024 - totalBytes)
            totalBytes += pdu.size
            digest.updateInt(pdu.size)
            digest.update(pdu)
        }
        MessageDeliveryFingerprint.fromSha256(digest.digest())
    }.getOrNull()

    private fun MessageDigest.updateInt(value: Int) {
        update((value ushr 24).toByte())
        update((value ushr 16).toByte())
        update((value ushr 8).toByte())
        update(value.toByte())
    }
}

private fun String.isBoundedSmsFormat(): Boolean =
    length <= 16 && all { it.code in 0x20..0x7e }

private fun Intent.rawPdusOrNull(): Array<*>? =
    if (Build.VERSION.SDK_INT >= 33) {
        getSerializableExtra("pdus", Serializable::class.java) as? Array<*>
    } else {
        legacySerializablePdus()
    }

// The typed Serializable overload was introduced in API 33. The legacy call
// is confined to the min-SDK branch and its result is still shape/size checked.
@Suppress("DEPRECATION")
private fun Intent.legacySerializablePdus(): Array<*>? =
    getSerializableExtra("pdus") as? Array<*>

internal fun Context.telephonyEntryPointOrNull(): TelephonyEntryPoint? =
    applicationContext as? TelephonyEntryPoint

internal fun Intent.subscriptionIdOrNull(): AuroraSubscriptionId? {
    val modern = getIntExtra(
        SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
        SubscriptionManager.INVALID_SUBSCRIPTION_ID,
    )
    if (modern >= 0) return AuroraSubscriptionId(modern)

    // SMS_DELIVER documentation retains the historical "subscription" key
    // and describes its value as a long. Some platform releases/OEMs use an
    // int, so accept either bounded scalar without choosing a default SIM.
    val legacyLong = getLongExtra(LEGACY_SUBSCRIPTION_EXTRA, -1L)
    if (legacyLong in 0L..Int.MAX_VALUE.toLong()) {
        return AuroraSubscriptionId(legacyLong.toInt())
    }
    val legacyInt = getIntExtra(LEGACY_SUBSCRIPTION_EXTRA, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
    return legacyInt.takeIf { it >= 0 }?.let(::AuroraSubscriptionId)
}

private const val LEGACY_SUBSCRIPTION_EXTRA = "subscription"

internal fun BroadcastReceiver.dispatchAsync(
    context: Context,
    block: suspend (TelephonyEntryPoint) -> Unit,
) {
    val entryPoint = context.telephonyEntryPointOrNull() ?: return
    val pendingResult = goAsync()
    // The broadcast's bounded wait is not ownership of the underlying work.
    // Cancelling that work at the deadline could strand a provider-backed SMS
    // between its durable journal checkpoints with no process-local retry.
    val work = TelephonyReceiverScope.scope.launch {
        runCatching { block(entryPoint) }
    }
    TelephonyReceiverScope.scope.launch {
        try {
            awaitReceiverWorkWithoutCancelling(
                work = work,
                timeoutMillis = TelephonyReceiverScope.MAX_RECEIVER_WORK_MILLIS,
            )
        } finally {
            pendingResult.finish()
        }
    }
}

internal suspend fun awaitReceiverWorkWithoutCancelling(
    work: Job,
    timeoutMillis: Long,
) {
    require(timeoutMillis > 0L)
    withTimeoutOrNull(timeoutMillis) { work.join() }
}

private object TelephonyReceiverScope {
    const val MAX_RECEIVER_WORK_MILLIS: Long = 8_000L
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
