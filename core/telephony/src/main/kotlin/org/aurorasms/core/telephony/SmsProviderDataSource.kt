// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageDeliveryFingerprint
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.MessageSyncFingerprint
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId

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

    override fun toString(): String = "ProviderPageRequest(limit=$limit, before=${before != null})"
}

data class ProviderPageCursor(
    val timestampMillis: Long,
    val providerRowId: Long,
) {
    init {
        require(timestampMillis >= 0L) { "Provider timestamps cannot be negative" }
        require(providerRowId > 0L) { "Provider row IDs must be positive" }
    }

    override fun toString(): String = "ProviderPageCursor(REDACTED)"
}

data class ProviderPage<out T>(
    val items: List<T>,
    val next: ProviderPageCursor?,
    val exhausted: Boolean,
) {
    override fun toString(): String =
        "ProviderPage(itemCount=${items.size}, hasNext=${next != null}, exhausted=$exhausted)"
}

sealed interface ProviderAccessResult<out T> {
    data class Success<T>(val value: T) : ProviderAccessResult<T> {
        override fun toString(): String = "ProviderAccessResult.Success(REDACTED)"
    }
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

    override fun toString(): String = "ProviderStoredMessage(REDACTED)"
}

enum class IncomingDeliveryDisposition {
    NEWLY_INSERTED,
    RECOVERED_UNACKNOWLEDGED,
    COMPLETED_REPLAY,
}

data class SmsProviderMessage(
    val id: ProviderMessageId,
    val providerThreadId: ProviderThreadId,
    val sender: ParticipantAddress?,
    val body: String,
    val direction: MessageDirection,
    val box: MessageBox,
    val status: MessageStatus,
    val rawStatus: Int?,
    val rawErrorCode: Int?,
    val timestampMillis: Long,
    val sentTimestampMillis: Long?,
    val subscriptionId: AuroraSubscriptionId?,
    val read: Boolean,
    val seen: Boolean,
    val locked: Boolean,
    val syncFingerprint: MessageSyncFingerprint,
) {
    init {
        require(id.kind == org.aurorasms.core.model.ProviderKind.SMS) {
            "SMS provider messages need an SMS provider ID"
        }
        require(body.length <= MAX_PROVIDER_BODY_CHARACTERS) {
            "SMS body exceeds the bounded provider projection"
        }
        require(timestampMillis >= 0L) { "Provider timestamps cannot be negative" }
        require(sentTimestampMillis == null || sentTimestampMillis >= 0L) {
            "Provider sent timestamps cannot be negative"
        }
    }

    override fun toString(): String =
        "SmsProviderMessage(bodyLength=${body.length}, hasSender=${sender != null})"

    companion object {
        const val MAX_PROVIDER_BODY_CHARACTERS: Int = 100_000
    }
}

/**
 * Builds one logical provider page from a bounded raw page.
 *
 * Cursor progress is based on every raw row consumed, including a malformed row
 * that cannot be projected. This prevents one rejected row from making a full
 * raw provider window look exhausted or from being queried forever.
 */
internal fun <Raw, Projected> buildProviderPageFromRaw(
    request: ProviderPageRequest,
    rawRows: List<Raw>,
    cursorFor: (Raw) -> ProviderPageCursor,
    project: (Raw) -> Projected?,
): ProviderPage<Projected> {
    val rawLimit = request.limit + 1
    require(rawRows.size <= rawLimit) { "Raw provider page exceeded its bounded query limit" }

    val items = ArrayList<Projected>(request.limit)
    var consumed = 0
    var previousCursor = request.before
    var lastConsumedCursor: ProviderPageCursor? = null
    while (consumed < rawRows.size && items.size < request.limit) {
        val raw = rawRows[consumed]
        val rawCursor = cursorFor(raw)
        require(previousCursor == null || rawCursor.isStrictlyBefore(previousCursor)) {
            "Provider page did not make strict cursor progress"
        }
        consumed += 1
        previousCursor = rawCursor
        lastConsumedCursor = rawCursor
        project(raw)?.let(items::add)
    }

    val sourceMayHaveMore = consumed < rawRows.size || rawRows.size == rawLimit
    val next = if (sourceMayHaveMore && consumed > 0) {
        lastConsumedCursor
    } else {
        null
    }
    return ProviderPage(
        items = items,
        next = next,
        exhausted = !sourceMayHaveMore,
    )
}

private fun ProviderPageCursor.isStrictlyBefore(other: ProviderPageCursor): Boolean =
    timestampMillis < other.timestampMillis ||
        (timestampMillis == other.timestampMillis && providerRowId < other.providerRowId)

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

data class IncomingSmsNotificationReplayRequest(
    val limit: Int,
) {
    init {
        require(limit in 1..MAXIMUM_LIMIT) {
            "Incoming SMS notification replay limit must be in 1..$MAXIMUM_LIMIT"
        }
    }

    companion object {
        const val MAXIMUM_LIMIT: Int = 64
    }
}

/**
 * Provider-backed content for one durable incoming-notification retry.
 *
 * The delivery fingerprint and provider identifiers are sufficient to pass
 * this item back to [SmsProviderDataSource.markIncomingHandled] only after the
 * notification has been posted successfully.
 */
data class IncomingSmsNotificationReplay(
    val deliveryFingerprint: MessageDeliveryFingerprint,
    val providerId: ProviderMessageId,
    val conversationId: ConversationId,
    val sender: ParticipantAddress,
    val body: String,
    val receivedTimestampMillis: Long,
    val sentTimestampMillis: Long,
    val subscriptionId: AuroraSubscriptionId?,
) {
    init {
        require(providerId.kind == org.aurorasms.core.model.ProviderKind.SMS) {
            "Incoming SMS notification replays need an SMS provider ID"
        }
        require(body.length <= IncomingSmsRecord.MAX_SMS_BODY_CHARACTERS) {
            "Incoming SMS notification replay body exceeds the bounded provider projection"
        }
        require(receivedTimestampMillis >= 0L && sentTimestampMillis >= 0L) {
            "Incoming SMS notification replay timestamps cannot be negative"
        }
    }

    override fun toString(): String = "IncomingSmsNotificationReplay(REDACTED)"
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
    DELIVERY_FAILED,
    FAILED,
    PENDING,
}

/** Exact disposition of an app-owned pre-submission outgoing SMS row. */
enum class OutgoingSmsRollbackOutcome {
    /** The exact staged, armed, or already-terminal app-owned row is terminal. */
    TERMINALIZED,

    /** No provider row currently exists at the exact provider URI. */
    ROW_ABSENT,

    /** A row exists, but its ownership, conversation, or submission state differs. */
    OWNERSHIP_CONFLICT,
}

/** Exact disposition of a terminal status update for one app-owned outgoing row. */
enum class OutgoingSmsStatusUpdateOutcome {
    /** The requested status was written or an equal/stronger status was already present. */
    APPLIED,

    /** No provider row currently exists at the exact provider URI. */
    ROW_ABSENT,

    /** The row is foreign, belongs to another conversation, or is not an owned SMS state. */
    OWNERSHIP_CONFLICT,
}

/** Result of a generation-fenced incoming-SMS read transition. */
enum class ConversationReadThroughOutcome {
    /** Every matching incoming row through the source generation is read and seen. */
    APPLIED_OR_ALREADY_READ,

    /** The exact source row is absent, no longer incoming, or belongs to another thread. */
    SOURCE_ABSENT_OR_MISMATCH,
}

interface SmsProviderDataSource {
    suspend fun count(): ProviderAccessResult<Long>

    suspend fun readPage(request: ProviderPageRequest): ProviderAccessResult<ProviderPage<SmsProviderMessage>>

    /** Reads one exact provider identity for guarded local mutations. */
    suspend fun readExact(
        id: ProviderMessageId,
    ): ProviderAccessResult<SmsProviderMessage?> =
        ProviderAccessResult.Unsupported("read exact SMS")

    suspend fun insertIncoming(message: IncomingSmsRecord): ProviderAccessResult<ProviderStoredMessage>

    suspend fun readPendingIncomingNotifications(
        request: IncomingSmsNotificationReplayRequest,
    ): ProviderAccessResult<List<IncomingSmsNotificationReplay>> =
        ProviderAccessResult.Unsupported("recover pending incoming SMS notifications")

    suspend fun markIncomingHandled(
        deliveryFingerprint: MessageDeliveryFingerprint,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
    ): ProviderAccessResult<Unit>

    /**
     * Marks only incoming SMS rows in [conversationId] no newer than [throughMessageId].
     * Implementations must validate the exact source row before mutation.
     */
    suspend fun markConversationReadThrough(
        conversationId: ConversationId,
        throughMessageId: ProviderMessageId,
    ): ProviderAccessResult<ConversationReadThroughOutcome> =
        ProviderAccessResult.Unsupported("mark SMS conversation read")

    /**
     * Inserts an outgoing row in a canonical known-unsent failed state.
     *
     * The exact returned row must be durably checkpointed by the caller before
     * [armOutgoing] can make it eligible for an irreversible platform send.
     */
    suspend fun insertOutgoing(message: OutgoingSmsRecord): ProviderAccessResult<ProviderStoredMessage>

    /**
     * Conditionally moves one exact app-owned outgoing row from known-unsent
     * failed state to pending. This is deliberately one-shot: an already-pending
     * or otherwise changed row is not accepted as success.
     */
    suspend fun armOutgoing(id: ProviderMessageId): ProviderAccessResult<Unit> =
        ProviderAccessResult.Unsupported("arm outgoing SMS")

    /**
     * Conditionally terminalizes one exact app-created staged or armed row.
     * Foreign, recycled, or otherwise changed rows must fail closed.
     */
    suspend fun rollbackOutgoing(
        id: ProviderMessageId,
        conversationId: ConversationId,
    ): ProviderAccessResult<OutgoingSmsRollbackOutcome> =
        ProviderAccessResult.Unsupported("rollback outgoing SMS")

    /**
     * Monotonically updates one exact app-created outgoing row only while both
     * its provider identity and provider conversation still match.
     */
    suspend fun updateOutgoingStatus(
        id: ProviderMessageId,
        conversationId: ConversationId,
        status: SmsProviderStatus,
    ): ProviderAccessResult<OutgoingSmsStatusUpdateOutcome> =
        ProviderAccessResult.Unsupported("update exact outgoing SMS status")

    /** Legacy unfenced update retained for non-owner compatibility. */
    suspend fun updateStatus(
        id: ProviderMessageId,
        status: SmsProviderStatus,
    ): ProviderAccessResult<Unit>
}
