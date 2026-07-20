// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import java.util.Arrays
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageDeliveryFingerprint
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderMessageId

sealed interface IncomingMessage {
    val receivedTimestampMillis: Long
    val subscriptionId: AuroraSubscriptionId?

    data class Sms(
        val deliveryFingerprint: MessageDeliveryFingerprint,
        val sender: ParticipantAddress,
        val body: String,
        val sentTimestampMillis: Long,
        override val receivedTimestampMillis: Long,
        override val subscriptionId: AuroraSubscriptionId?,
        val sourcePduCount: Int,
    ) : IncomingMessage {
        init {
            require(body.length <= IncomingSmsRecord.MAX_SMS_BODY_CHARACTERS) { "Incoming SMS body is too large" }
            require(sentTimestampMillis >= 0L && receivedTimestampMillis >= 0L) {
                "Incoming SMS timestamps cannot be negative"
            }
            require(sourcePduCount in 1..MAX_SMS_PDUS) { "Incoming SMS PDU count is invalid" }
        }

        companion object {
            const val MAX_SMS_PDUS: Int = 255
        }
    }

    class MmsWapPush(
        pdu: ByteArray,
        val mimeType: String,
        override val receivedTimestampMillis: Long,
        override val subscriptionId: AuroraSubscriptionId?,
    ) : IncomingMessage {
        private val pdu: ByteArray = pdu.copyOf()

        val pduSize: Int
            get() = pdu.size

        init {
            require(pdu.isNotEmpty()) { "Incoming MMS PDU cannot be empty" }
            require(pdu.size <= MAX_WAP_PDU_BYTES) { "Incoming MMS PDU is too large" }
            require(mimeType == MMS_MIME_TYPE) { "Incoming WAP push has the wrong MIME type" }
            require(receivedTimestampMillis >= 0L) { "Incoming MMS timestamp cannot be negative" }
        }

        fun copyPdu(): ByteArray = pdu.copyOf()

        override fun equals(other: Any?): Boolean =
            other is MmsWapPush &&
                Arrays.equals(pdu, other.pdu) &&
                mimeType == other.mimeType &&
                receivedTimestampMillis == other.receivedTimestampMillis &&
                subscriptionId == other.subscriptionId

        override fun hashCode(): Int = Arrays.hashCode(pdu)

        override fun toString(): String = "MmsWapPush(pduSize=$pduSize)"

        companion object {
            const val MMS_MIME_TYPE: String = "application/vnd.wap.mms-message"
            const val MAX_WAP_PDU_BYTES: Int = 1_048_576
        }
    }
}

sealed interface IncomingPersistResult {
    data class Pending(val operationId: MessageId) : IncomingPersistResult

    data class Persisted(
        val providerId: ProviderMessageId,
        val conversationId: ConversationId,
    ) : IncomingPersistResult

    data class Duplicate(
        val providerId: ProviderMessageId?,
        val conversationId: ConversationId?,
    ) : IncomingPersistResult
    data class Rejected(val reason: Reason) : IncomingPersistResult

    enum class Reason {
        ROLE_NOT_HELD,
        PERMISSION_DENIED,
        MALFORMED_INPUT,
        CODEC_UNAVAILABLE,
        PROVIDER_UNAVAILABLE,
        NOTIFICATION_UNAVAILABLE,
        STORAGE_FULL,
    }
}

interface IncomingMessageSink {
    suspend fun persist(message: IncomingMessage): IncomingPersistResult
}
