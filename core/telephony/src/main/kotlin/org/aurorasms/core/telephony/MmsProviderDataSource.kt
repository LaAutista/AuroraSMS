// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import java.util.Arrays
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.MessageSyncFingerprint
import org.aurorasms.core.model.MmsAttachmentSummary
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind

data class MmsProviderMessage(
    val id: ProviderMessageId,
    val providerThreadId: ProviderThreadId,
    val sender: ParticipantAddress?,
    val participants: List<ParticipantAddress>,
    val participantsTruncated: Boolean,
    val body: String?,
    val subject: String?,
    val direction: MessageDirection,
    val box: MessageBox,
    val status: MessageStatus,
    val rawStatus: Int?,
    val rawResponseStatus: Int?,
    val rawRetrieveStatus: Int?,
    val timestampMillis: Long,
    val sentTimestampMillis: Long?,
    val subscriptionId: AuroraSubscriptionId?,
    val attachments: MmsAttachmentSummary,
    val read: Boolean,
    val seen: Boolean,
    val locked: Boolean,
    val syncFingerprint: MessageSyncFingerprint,
) {
    init {
        require(id.kind == org.aurorasms.core.model.ProviderKind.MMS) {
            "MMS provider messages need an MMS provider ID"
        }
        require(participants.size <= MAX_MMS_PARTICIPANTS) { "MMS participant list is too large" }
        require(body == null || body.length <= MAX_MMS_TEXT_CHARACTERS) { "MMS text is too long" }
        require(subject == null || subject.length <= MAX_MMS_SUBJECT_CHARACTERS) { "MMS subject is too long" }
        require(timestampMillis >= 0L) { "Provider timestamps cannot be negative" }
        require(sentTimestampMillis == null || sentTimestampMillis >= 0L) {
            "Provider sent timestamps cannot be negative"
        }
    }

    override fun toString(): String =
        "MmsProviderMessage(" +
            "participantCount=${participants.size}, " +
            "bodyLength=${body?.length ?: 0}, " +
            "hasSubject=${subject != null}, " +
            "attachmentCount=${attachments.attachmentCount})"

    companion object {
        const val MAX_MMS_PARTICIPANTS: Int = 100
        const val MAX_MMS_SUBJECT_CHARACTERS: Int = 1_000
        const val MAX_MMS_TEXT_CHARACTERS: Int = 100_000
    }
}

/** One defensively copied, already bounded part admitted by the incoming codec. */
class DecodedIncomingMmsPart(
    val contentType: String,
    val charsetMibEnum: Int?,
    val name: String?,
    val filename: String?,
    val contentLocation: String?,
    val contentId: String?,
    val contentDisposition: String?,
    val decodedText: String?,
    bytes: ByteArray,
) {
    private val bytes = bytes.copyOf()

    val size: Int
        get() = bytes.size

    init {
        require(MIME_TYPE.matches(contentType)) { "MMS part content type is invalid" }
        require(charsetMibEnum == null || charsetMibEnum >= 0) { "MMS part charset is invalid" }
        listOf(name, filename, contentLocation, contentId).forEach { value ->
            require(value == null || value.length <= MAX_METADATA_CHARACTERS) {
                "MMS part metadata is too long"
            }
            require(value?.any(Char::isISOControl) != true) { "MMS part metadata contains a control character" }
        }
        require(contentDisposition == null || contentDisposition.length <= MAX_DISPOSITION_CHARACTERS) {
            "MMS part disposition is too long"
        }
        require(contentDisposition?.any(Char::isISOControl) != true) {
            "MMS part disposition contains a control character"
        }
        require(decodedText == null || decodedText.length <= MmsProviderMessage.MAX_MMS_TEXT_CHARACTERS) {
            "MMS part text is too long"
        }
        require(size <= EncodedMmsPdu.MAX_ENCODED_BYTES) { "MMS part is too large" }
    }

    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is DecodedIncomingMmsPart &&
            contentType == other.contentType &&
            charsetMibEnum == other.charsetMibEnum &&
            name == other.name &&
            filename == other.filename &&
            contentLocation == other.contentLocation &&
            contentId == other.contentId &&
            contentDisposition == other.contentDisposition &&
            decodedText == other.decodedText &&
            Arrays.equals(bytes, other.bytes)

    override fun hashCode(): Int = 31 * contentType.hashCode() + Arrays.hashCode(bytes)

    override fun toString(): String =
        "DecodedIncomingMmsPart(contentType=$contentType, size=$size, hasText=${decodedText != null}, REDACTED)"

    companion object {
        const val MAX_METADATA_CHARACTERS: Int = 255
        const val MAX_DISPOSITION_CHARACTERS: Int = 64
        private val MIME_TYPE = Regex(
            "[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}",
        )
    }
}

/** Normalized, bounded record admitted for one idempotent incoming provider transaction. */
data class DecodedIncomingMmsRecord(
    val operationId: MessageId,
    val sender: ParticipantAddress,
    val participants: List<ParticipantAddress>,
    val to: List<ParticipantAddress>,
    val cc: List<ParticipantAddress>,
    val subject: String?,
    val text: String?,
    val sentTimestampMillis: Long,
    val receivedTimestampMillis: Long,
    val subscriptionId: AuroraSubscriptionId,
    val notificationTransactionId: String,
    val messageId: String?,
    val parts: List<DecodedIncomingMmsPart>,
) {
    init {
        require(operationId.kind == ProviderKind.PENDING_OPERATION) {
            "Incoming MMS persistence needs a pending-operation ID"
        }
        require(operationId.value > 0L) { "Incoming MMS operation ID must be positive" }
        require(participants.isNotEmpty()) { "An incoming MMS needs a participant" }
        require(sender in participants) { "Incoming MMS participants must include the sender" }
        require(participants.distinctBy(ParticipantAddress::value).size == participants.size) {
            "Incoming MMS participants must be unique"
        }
        require(participants.size <= MmsProviderMessage.MAX_MMS_PARTICIPANTS) {
            "MMS participant list is too large"
        }
        require(to.size + cc.size <= MmsProviderMessage.MAX_MMS_PARTICIPANTS) {
            "MMS address list is too large"
        }
        require(subject == null || subject.length <= MmsProviderMessage.MAX_MMS_SUBJECT_CHARACTERS) {
            "MMS subject is too long"
        }
        require(text == null || text.length <= MAX_MMS_TEXT_CHARACTERS) { "MMS text is too long" }
        require(sentTimestampMillis >= 0L && receivedTimestampMillis >= 0L) {
            "MMS timestamps cannot be negative"
        }
        require(TRANSACTION_ID.matches(notificationTransactionId)) {
            "MMS notification transaction ID is invalid"
        }
        require(messageId == null || MESSAGE_ID.matches(messageId)) { "MMS message ID is invalid" }
        require(parts.size in 1..MAX_MMS_PARTS) { "MMS part count is invalid" }
        require(parts.sumOf { it.size.toLong() } <= EncodedMmsPdu.MAX_ENCODED_BYTES) {
            "MMS parts are too large"
        }
    }

    override fun toString(): String =
        "DecodedIncomingMmsRecord(participantCount=${participants.size}, toCount=${to.size}, " +
            "ccCount=${cc.size}, hasSubject=${subject != null}, textLength=${text?.length ?: 0}, " +
            "partCount=${parts.size}, REDACTED)"

    companion object {
        const val MAX_MMS_TEXT_CHARACTERS: Int = 100_000
        const val MAX_MMS_PARTS: Int = 25
        private val TRANSACTION_ID = Regex("[ -~]{1,128}")
        private val MESSAGE_ID = Regex("[ -~]{1,256}")
    }
}

/** Exact, bounded provider projection for the first outgoing attachment surface. */
data class OutgoingVoiceMemoProviderRecord(
    val operationId: MessageId,
    val providerThreadId: ProviderThreadId,
    val recipients: RecipientSet,
    val text: String?,
    val subject: String?,
    val memo: OutgoingVoiceMemo,
    val encodedSize: Int,
    val transactionId: String,
    val timestampMillis: Long,
    val subscriptionId: AuroraSubscriptionId,
) {
    init {
        require(operationId.kind == ProviderKind.PENDING_OPERATION && operationId.value > 0L)
        require(recipients.size == 1) { "The first voice-memo provider path supports one recipient" }
        require(text == null || text.length <= MmsProviderMessage.MAX_MMS_TEXT_CHARACTERS)
        require(subject == null || subject.length <= MmsProviderMessage.MAX_MMS_SUBJECT_CHARACTERS)
        require(encodedSize in 1..EncodedMmsPdu.MAX_ENCODED_BYTES)
        require(transactionId.matches(Regex("[A-Za-z0-9._-]{1,64}")))
        require(timestampMillis >= 0L)
    }

    override fun toString(): String =
        "OutgoingVoiceMemoProviderRecord(recipientCount=${recipients.size}, " +
            "textLength=${text?.length ?: 0}, hasSubject=${subject != null}, memo=$memo, " +
            "encodedSize=$encodedSize, REDACTED)"
}

enum class OutgoingMmsProviderStatus {
    FAILED,
    OUTBOX,
    SENT,
}

enum class OutgoingMmsStatusUpdateOutcome {
    APPLIED,
    ROW_ABSENT,
    OWNERSHIP_CONFLICT,
}

interface MmsProviderDataSource {
    suspend fun count(): ProviderAccessResult<Long>

    suspend fun readPage(request: ProviderPageRequest): ProviderAccessResult<ProviderPage<MmsProviderMessage>>

    /** Reads one exact provider identity for guarded local mutations. */
    suspend fun readExact(
        id: ProviderMessageId,
    ): ProviderAccessResult<MmsProviderMessage?> =
        ProviderAccessResult.Unsupported("read exact MMS")

    suspend fun insertIncoming(message: DecodedIncomingMmsRecord): ProviderAccessResult<ProviderStoredMessage>

    suspend fun insertOutgoingVoiceMemo(
        message: OutgoingVoiceMemoProviderRecord,
    ): ProviderAccessResult<ProviderStoredMessage> =
        ProviderAccessResult.Unsupported("insert outgoing voice memo")

    suspend fun updateOutgoingStatus(
        id: ProviderMessageId,
        conversationId: ConversationId,
        status: OutgoingMmsProviderStatus,
    ): ProviderAccessResult<OutgoingMmsStatusUpdateOutcome> =
        ProviderAccessResult.Unsupported("update outgoing MMS status")

    /**
     * Removes only an Aurora-owned, failed provider row (and temporary parts)
     * whose exact preparation identity never crossed the platform-send boundary.
     */
    suspend fun rollbackOutgoingPreparation(
        operationId: MessageId,
        conversationId: ConversationId,
        transactionId: String,
    ): ProviderAccessResult<OutgoingMmsStatusUpdateOutcome> =
        ProviderAccessResult.Unsupported("rollback outgoing MMS preparation")
}
