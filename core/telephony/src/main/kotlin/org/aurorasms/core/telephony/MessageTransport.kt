// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import java.util.Arrays
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.PendingOperationNamespace
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.model.pendingOperationNamespaceOrNull

data class SmsSendRequest(
    val operationId: MessageId,
    val recipients: RecipientSet,
    val body: String,
    val subscriptionId: AuroraSubscriptionId,
    val requestDeliveryReport: Boolean = true,
    val operationOrigin: TransportResult.OperationOrigin = TransportResult.OperationOrigin.UNMARKED,
) {
    init {
        require(operationId.kind == ProviderKind.PENDING_OPERATION) {
            "Transport operations need a pending-operation ID"
        }
        require(recipients.singleSmsRecipientOrNull() != null) {
            "SMS requests accept exactly one canonical recipient; groups require one MMS operation"
        }
        require(body.length <= MAX_OUTGOING_TEXT_CHARACTERS) { "SMS text is too large" }
    }

    companion object {
        const val MAX_OUTGOING_TEXT_CHARACTERS: Int = 100_000
    }
}

/**
 * Durability gates awaited inline around the irreversible SMS platform call.
 * A callback may suspend for its durable commit, but the transport must await
 * its result and must not cross the corresponding boundary first. Implementations
 * must not retain message content or invoke either callback after that boundary.
 */
interface SmsSubmissionObserver {
    suspend fun onPrepared(
        providerId: ProviderMessageId,
        providerConversationId: ConversationId,
        unitCount: Int,
    ): Boolean

    suspend fun onSubmitting(
        providerId: ProviderMessageId,
        providerConversationId: ConversationId,
        unitCount: Int,
    ): Boolean
}

/**
 * Selects the single durable owner of the pre-submission SMS lifecycle.
 *
 * [TransportOwned] uses the transport's private, content-free journal. A
 * [CallerOwned] observer must durably persist both callbacks before returning
 * success and fail closed when either checkpoint cannot be committed.
 */
sealed interface SmsSubmissionOwnership {
    data object TransportOwned : SmsSubmissionOwnership

    data class CallerOwned(val observer: SmsSubmissionObserver) : SmsSubmissionOwnership
}

/**
 * Checks only the structural origin/range pairing for a new submission.
 * Durable journal or state-store membership remains the ownership authority.
 */
fun SmsSendRequest.hasValidOperationOwnership(
    ownership: SmsSubmissionOwnership,
): Boolean = when (ownership) {
    SmsSubmissionOwnership.TransportOwned ->
        operationOrigin == TransportResult.OperationOrigin.UNMARKED &&
            operationId.pendingOperationNamespaceOrNull() == PendingOperationNamespace.RESPOND_VIA
    is SmsSubmissionOwnership.CallerOwned -> when (operationOrigin) {
        TransportResult.OperationOrigin.UNMARKED -> false
        TransportResult.OperationOrigin.COMPOSER ->
            operationId.pendingOperationNamespaceOrNull() == PendingOperationNamespace.COMPOSER
        TransportResult.OperationOrigin.INLINE_REPLY ->
            operationId.pendingOperationNamespaceOrNull() == PendingOperationNamespace.INLINE_REPLY
    }
}

/** Startup disposition for the transport-owned, content-free SMS journal. */
sealed interface TransportOwnedSmsRecoveryResult {
    /** The journal was readable; individual provider rows may still need a later retry. */
    data class Available(
        val recoveredCount: Int,
        val deferredCount: Int,
    ) : TransportOwnedSmsRecoveryResult {
        init {
            require(recoveredCount >= 0)
            require(deferredCount >= 0)
        }
    }

    /** Journal integrity or persistence could not be established, so new work fails closed. */
    data object JournalBlocked : TransportOwnedSmsRecoveryResult
}

val TransportOwnedSmsRecoveryResult.acceptsNewSubmissions: Boolean
    get() = this is TransportOwnedSmsRecoveryResult.Available

val TransportOwnedSmsRecoveryResult.followUpRequired: Boolean
    get() = when (this) {
        is TransportOwnedSmsRecoveryResult.Available -> deferredCount > 0
        TransportOwnedSmsRecoveryResult.JournalBlocked -> true
    }

class EncodedMmsPdu private constructor(bytes: ByteArray) {
    private val bytes: ByteArray = bytes.copyOf()

    val size: Int
        get() = bytes.size

    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is EncodedMmsPdu && Arrays.equals(bytes, other.bytes)

    override fun hashCode(): Int = Arrays.hashCode(bytes)

    override fun toString(): String = "EncodedMmsPdu(size=$size)"

    companion object {
        const val MAX_ENCODED_BYTES: Int = 1_048_576

        fun create(bytes: ByteArray): CreationResult =
            when {
                bytes.isEmpty() -> CreationResult.Rejected(CreationResult.Reason.EMPTY)
                bytes.size > MAX_ENCODED_BYTES -> CreationResult.Rejected(CreationResult.Reason.TOO_LARGE)
                else -> CreationResult.Valid(EncodedMmsPdu(bytes))
            }
    }

    sealed interface CreationResult {
        data class Valid(val pdu: EncodedMmsPdu) : CreationResult
        data class Rejected(val reason: Reason) : CreationResult

        enum class Reason {
            EMPTY,
            TOO_LARGE,
        }
    }
}

sealed interface OutgoingMmsPayload {
    data class Encoded(val pdu: EncodedMmsPdu) : OutgoingMmsPayload

    /** High-level content that cannot be sent until an audited codec exists. */
    data class RequiresEncoding(
        val text: String?,
        val subject: String?,
        val attachmentCount: Int,
    ) : OutgoingMmsPayload {
        init {
            require(text == null || text.length <= SmsSendRequest.MAX_OUTGOING_TEXT_CHARACTERS) {
                "MMS text is too large"
            }
            require(subject == null || subject.length <= MmsProviderMessage.MAX_MMS_SUBJECT_CHARACTERS) {
                "MMS subject is too large"
            }
            require(attachmentCount in 0..MAX_ATTACHMENTS) { "MMS attachment count is invalid" }
        }

        companion object {
            const val MAX_ATTACHMENTS: Int = 25
        }
    }
}

data class MmsSendRequest(
    val operationId: MessageId,
    val recipients: RecipientSet,
    val payload: OutgoingMmsPayload,
    val subscriptionId: AuroraSubscriptionId,
) {
    init {
        require(operationId.kind == ProviderKind.PENDING_OPERATION) {
            "Transport operations need a pending-operation ID"
        }
    }
}

data class MmsDownloadRequest(
    val operationId: MessageId,
    val contentLocation: String,
    val subscriptionId: AuroraSubscriptionId,
) {
    init {
        require(operationId.kind == ProviderKind.PENDING_OPERATION) {
            "Transport operations need a pending-operation ID"
        }
        require(contentLocation.length in 1..MAX_CONTENT_LOCATION_CHARACTERS) {
            "MMS content location is invalid"
        }
        require(contentLocation.none(Char::isISOControl)) { "MMS content location contains a control character" }
    }

    companion object {
        const val MAX_CONTENT_LOCATION_CHARACTERS: Int = 2_048
    }
}

interface MessageTransport {
    suspend fun sendSms(
        request: SmsSendRequest,
        ownership: SmsSubmissionOwnership,
    ): TransportResult

    suspend fun sendMms(request: MmsSendRequest): TransportResult

    suspend fun downloadMms(request: MmsDownloadRequest): TransportResult
}
