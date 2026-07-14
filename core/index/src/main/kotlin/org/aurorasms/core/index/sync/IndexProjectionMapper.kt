// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.sync

import java.text.Normalizer
import java.util.Locale
import org.aurorasms.core.index.storage.IndexedMessageEntity
import org.aurorasms.core.index.storage.toIndexStorageCode
import org.aurorasms.core.telephony.MmsProviderMessage
import org.aurorasms.core.telephony.SmsProviderMessage

/** Maps bounded Telephony projections into the private, metadata-only index schema. */
object IndexProjectionMapper {
    fun projectionFromSms(
        message: SmsProviderMessage,
        generationId: Long,
    ): IndexedProviderProjection = IndexedProviderProjection(
        message = fromSms(message, generationId),
        participantAddresses = listOfNotNull(message.sender?.value),
        participantsTruncated = message.sender == null,
    )

    fun fromSms(
        message: SmsProviderMessage,
        generationId: Long,
    ): IndexedMessageEntity {
        require(generationId > 0L) { "Index generations must be positive" }
        return IndexedMessageEntity(
            providerKind = message.id.kind.toIndexStorageCode(),
            providerId = message.id.value,
            providerThreadId = message.providerThreadId.value,
            timestampMillis = message.timestampMillis,
            sentTimestampMillis = message.sentTimestampMillis,
            direction = message.direction.toIndexStorageCode(),
            messageBox = message.box.toStorageCode(),
            messageStatus = message.status.toStorageCode(),
            subscriptionId = message.subscriptionId?.value,
            senderAddress = message.sender?.value,
            body = message.body,
            subject = null,
            attachmentCount = 0,
            attachmentTypeSummary = "",
            attachmentTotalBytes = null,
            isRead = message.read,
            isSeen = message.seen,
            isLocked = message.locked,
            syncFingerprint = message.syncFingerprint.toStorageToken(),
            searchableText = normalizeSearchableText(listOfNotNull(message.sender?.value)),
            lastSeenGeneration = generationId,
        )
    }

    fun fromMms(
        message: MmsProviderMessage,
        generationId: Long,
    ): IndexedMessageEntity {
        require(generationId > 0L) { "Index generations must be positive" }
        val attachmentTypes = message.attachments.contentTypes
            .asSequence()
            .map { it.mimeType }
            .distinct()
            .sorted()
            .toList()
        return IndexedMessageEntity(
            providerKind = message.id.kind.toIndexStorageCode(),
            providerId = message.id.value,
            providerThreadId = message.providerThreadId.value,
            timestampMillis = message.timestampMillis,
            sentTimestampMillis = message.sentTimestampMillis,
            direction = message.direction.toIndexStorageCode(),
            messageBox = message.box.toStorageCode(),
            messageStatus = message.status.toStorageCode(),
            subscriptionId = message.subscriptionId?.value,
            senderAddress = message.sender?.value,
            body = message.body,
            subject = message.subject,
            attachmentCount = message.attachments.attachmentCount,
            attachmentTypeSummary = attachmentTypes.joinToString(","),
            attachmentTotalBytes = message.attachments.totalBytes,
            isRead = message.read,
            isSeen = message.seen,
            isLocked = message.locked,
            syncFingerprint = message.syncFingerprint.toStorageToken(),
            searchableText = normalizeSearchableText(
                buildList {
                    message.sender?.value?.let(::add)
                    message.participants.forEach { add(it.value) }
                    addAll(attachmentTypes)
                },
            ),
            lastSeenGeneration = generationId,
        )
    }

    fun projectionFromMms(
        message: MmsProviderMessage,
        generationId: Long,
    ): IndexedProviderProjection {
        val allParticipants = buildList {
            message.sender?.value?.let(::add)
            message.participants.forEach { add(it.value) }
        }.distinct()
        return IndexedProviderProjection(
            message = fromMms(message, generationId),
            participantAddresses = allParticipants.take(MAXIMUM_INDEXED_CONVERSATION_PARTICIPANTS),
            participantsTruncated = message.participantsTruncated ||
                allParticipants.isEmpty() ||
                allParticipants.size > MAXIMUM_INDEXED_CONVERSATION_PARTICIPANTS,
        )
    }

    private fun normalizeSearchableText(values: List<String>): String = values
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .joinToString(" ")
        .let { Normalizer.normalize(it, Normalizer.Form.NFC) }
        .lowercase(Locale.ROOT)
}
