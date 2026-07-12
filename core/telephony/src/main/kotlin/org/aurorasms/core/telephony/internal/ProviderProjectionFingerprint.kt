// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.MessageSyncFingerprint
import org.aurorasms.core.model.MmsAttachmentSummary
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId

/** Creates domain-separated, content-redacted synchronization fingerprints. */
internal object ProviderProjectionFingerprint {
    fun sms(
        providerId: ProviderMessageId,
        providerThreadId: ProviderThreadId,
        sender: ParticipantAddress?,
        body: String,
        box: MessageBox,
        status: MessageStatus,
        rawStatus: Int?,
        rawErrorCode: Int?,
        timestampMillis: Long,
        sentTimestampMillis: Long?,
        subscriptionId: AuroraSubscriptionId?,
        read: Boolean,
        seen: Boolean,
        locked: Boolean,
    ): MessageSyncFingerprint = CanonicalDigest(SMS_DOMAIN).run {
        string(providerId.kind.name)
        long(providerId.value)
        long(providerThreadId.value)
        string(sender?.value)
        string(body)
        string(box.toStorageCode())
        string(status.toStorageCode())
        nullableInt(rawStatus)
        nullableInt(rawErrorCode)
        long(timestampMillis)
        nullableLong(sentTimestampMillis)
        nullableInt(subscriptionId?.value)
        bool(read)
        bool(seen)
        bool(locked)
        finish()
    }

    fun mms(
        providerId: ProviderMessageId,
        providerThreadId: ProviderThreadId,
        sender: ParticipantAddress?,
        participants: List<ParticipantAddress>,
        participantsTruncated: Boolean,
        body: String?,
        subject: String?,
        box: MessageBox,
        status: MessageStatus,
        rawStatus: Int?,
        rawResponseStatus: Int?,
        rawRetrieveStatus: Int?,
        timestampMillis: Long,
        sentTimestampMillis: Long?,
        subscriptionId: AuroraSubscriptionId?,
        attachments: MmsAttachmentSummary,
        read: Boolean,
        seen: Boolean,
        locked: Boolean,
    ): MessageSyncFingerprint = CanonicalDigest(MMS_DOMAIN).run {
        string(providerId.kind.name)
        long(providerId.value)
        long(providerThreadId.value)
        string(sender?.value)
        val stableParticipants = participants.map { it.value }.sorted()
        int(stableParticipants.size)
        stableParticipants.forEach(::string)
        bool(participantsTruncated)
        string(body)
        string(subject)
        string(box.toStorageCode())
        string(status.toStorageCode())
        nullableInt(rawStatus)
        nullableInt(rawResponseStatus)
        nullableInt(rawRetrieveStatus)
        long(timestampMillis)
        nullableLong(sentTimestampMillis)
        nullableInt(subscriptionId?.value)
        int(attachments.attachmentCount)
        nullableLong(attachments.totalBytes)
        int(attachments.contentTypes.size)
        attachments.contentTypes.forEach { type ->
            string(type.mimeType)
            string(type.displayName)
        }
        bool(attachments.metadataTruncated)
        bool(read)
        bool(seen)
        bool(locked)
        finish()
    }

    private val SMS_DOMAIN = "AuroraSMS.PROVIDER_PROJECTION.SMS.v1".toByteArray(StandardCharsets.US_ASCII)
    private val MMS_DOMAIN = "AuroraSMS.PROVIDER_PROJECTION.MMS.v1".toByteArray(StandardCharsets.US_ASCII)
}

private class CanonicalDigest(domain: ByteArray) {
    private val digest = MessageDigest.getInstance("SHA-256").apply {
        updateInt(domain.size)
        update(domain)
    }

    fun bool(value: Boolean) {
        digest.update(if (value) 1.toByte() else 0.toByte())
    }

    fun int(value: Int) {
        digest.updateInt(value)
    }

    fun long(value: Long) {
        digest.updateLong(value)
    }

    fun nullableInt(value: Int?) {
        digest.update(if (value == null) 0.toByte() else 1.toByte())
        value?.let(digest::updateInt)
    }

    fun nullableLong(value: Long?) {
        digest.update(if (value == null) 0.toByte() else 1.toByte())
        value?.let(digest::updateLong)
    }

    fun string(value: String?) {
        digest.update(if (value == null) 0.toByte() else 1.toByte())
        if (value != null) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.updateInt(bytes.size)
            digest.update(bytes)
        }
    }

    fun finish(): MessageSyncFingerprint = MessageSyncFingerprint.fromSha256(digest.digest())
}

private fun MessageDigest.updateInt(value: Int) {
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())
}

private fun MessageDigest.updateLong(value: Long) {
    update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())
}
