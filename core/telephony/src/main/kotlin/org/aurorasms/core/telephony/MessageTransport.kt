// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import java.util.Arrays
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.TransportResult

data class SmsSendRequest(
    val operationId: MessageId,
    val recipients: RecipientSet,
    val body: String,
    val subscriptionId: AuroraSubscriptionId,
    val requestDeliveryReport: Boolean = true,
) {
    init {
        require(operationId.kind == ProviderKind.PENDING_OPERATION) {
            "Transport operations need a pending-operation ID"
        }
        require(body.length <= MAX_OUTGOING_TEXT_CHARACTERS) { "SMS text is too large" }
    }

    companion object {
        const val MAX_OUTGOING_TEXT_CHARACTERS: Int = 100_000
    }
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
    suspend fun sendSms(request: SmsSendRequest): TransportResult

    suspend fun sendMms(request: MmsSendRequest): TransportResult

    suspend fun downloadMms(request: MmsDownloadRequest): TransportResult
}
