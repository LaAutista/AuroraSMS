// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageDeliveryFingerprint
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderMessageId

data class ProviderPageRequest(
    val limit: Int,
    val before: ProviderPageCursor? = null,
) {
    init {
        require(limit in 1..MAX_PROVIDER_PAGE_SIZE) { "Provider page size must be in 1..$MAX_PROVIDER_PAGE_SIZE" }
    }

    companion object {
        const val MAX_PROVIDER_PAGE_SIZE: Int = 200
    }
}

data class ProviderPageCursor(
    val timestampMillis: Long,
    val providerRowId: Long,
) {
    init {
        require(timestampMillis >= 0L) { "Provider timestamps cannot be negative" }
        require(providerRowId > 0L) { "Provider row IDs must be positive" }
    }
}

data class ProviderPage<out T>(
    val items: List<T>,
    val next: ProviderPageCursor?,
    val exhausted: Boolean,
)

sealed interface ProviderAccessResult<out T> {
    data class Success<T>(val value: T) : ProviderAccessResult<T>
    data object RoleRequired : ProviderAccessResult<Nothing>
    data object PermissionDenied : ProviderAccessResult<Nothing>
    data class Unsupported(val capability: String) : ProviderAccessResult<Nothing>
    data class Unavailable(val operation: String) : ProviderAccessResult<Nothing>
    data class InvalidInput(val field: String) : ProviderAccessResult<Nothing>
}

data class ProviderStoredMessage(
    val providerId: ProviderMessageId,
    val conversationId: ConversationId,
    val incomingDisposition: IncomingDeliveryDisposition? = null,
) {
    val newlyInserted: Boolean
        get() = incomingDisposition == null || incomingDisposition == IncomingDeliveryDisposition.NEWLY_INSERTED

    val notificationRequired: Boolean
        get() = incomingDisposition != IncomingDeliveryDisposition.COMPLETED_REPLAY
}

enum class IncomingDeliveryDisposition {
    NEWLY_INSERTED,
    RECOVERED_UNACKNOWLEDGED,
    COMPLETED_REPLAY,
}

data class SmsProviderMessage(
    val id: ProviderMessageId,
    val threadId: Long,
    val address: ParticipantAddress,
    val body: String,
    val direction: MessageDirection,
    val timestampMillis: Long,
    val sentTimestampMillis: Long?,
    val subscriptionId: AuroraSubscriptionId?,
    val read: Boolean,
    val seen: Boolean,
) {
    init {
        require(threadId >= 0L) { "Provider thread IDs cannot be negative" }
        require(timestampMillis >= 0L) { "Provider timestamps cannot be negative" }
        require(sentTimestampMillis == null || sentTimestampMillis >= 0L) {
            "Provider sent timestamps cannot be negative"
        }
    }
}

data class IncomingSmsRecord(
    val deliveryFingerprint: MessageDeliveryFingerprint,
    val sender: ParticipantAddress,
    val body: String,
    val sentTimestampMillis: Long,
    val receivedTimestampMillis: Long,
    val subscriptionId: AuroraSubscriptionId?,
) {
    init {
        require(body.length <= MAX_SMS_BODY_CHARACTERS) { "SMS body exceeds the bounded provider input" }
        require(sentTimestampMillis >= 0L && receivedTimestampMillis >= 0L) {
            "SMS timestamps cannot be negative"
        }
    }

    companion object {
        const val MAX_SMS_BODY_CHARACTERS: Int = 100_000
    }
}

data class OutgoingSmsRecord(
    val recipient: ParticipantAddress,
    val body: String,
    val timestampMillis: Long,
    val subscriptionId: AuroraSubscriptionId,
) {
    init {
        require(body.isNotEmpty()) { "Outgoing SMS body cannot be empty" }
        require(body.length <= IncomingSmsRecord.MAX_SMS_BODY_CHARACTERS) {
            "Outgoing SMS body exceeds the bounded provider input"
        }
        require(timestampMillis >= 0L) { "SMS timestamps cannot be negative" }
    }
}

enum class SmsProviderStatus {
    COMPLETE,
    FAILED,
    PENDING,
}

interface SmsProviderDataSource {
    suspend fun count(): ProviderAccessResult<Long>

    suspend fun readPage(request: ProviderPageRequest): ProviderAccessResult<ProviderPage<SmsProviderMessage>>

    suspend fun insertIncoming(message: IncomingSmsRecord): ProviderAccessResult<ProviderStoredMessage>

    suspend fun markIncomingHandled(
        deliveryFingerprint: MessageDeliveryFingerprint,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
    ): ProviderAccessResult<Unit>

    suspend fun insertOutgoing(message: OutgoingSmsRecord): ProviderAccessResult<ProviderStoredMessage>

    suspend fun updateStatus(
        id: ProviderMessageId,
        status: SmsProviderStatus,
    ): ProviderAccessResult<Unit>
}
