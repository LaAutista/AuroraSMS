// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import kotlinx.coroutines.flow.Flow
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.COMPOSER_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.INLINE_REPLY_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId

/** Stable, persisted lifecycle phases for one existing-thread composer SMS. */
enum class ComposerSmsOperationPhase {
    RESERVED,
    PREPARED,
    SUBMITTING,
    PLATFORM_ACCEPTED,
    SENT_CALLBACK_SUCCEEDED,
    SUBMISSION_UNKNOWN,
    KNOWN_UNSENT,
}

/** Optimistic-concurrency token for one composer operation. */
@JvmInline
value class ComposerSmsOperationRevision(val updatedTimestampMillis: Long) {
    init {
        require(updatedTimestampMillis >= 0L) { "Composer SMS revisions cannot be negative" }
    }

    override fun toString(): String = "ComposerSmsOperationRevision(REDACTED)"
}

/** Exact provider binding established before the irreversible platform boundary. */
data class ComposerSmsProviderBinding(
    val providerMessageId: ProviderMessageId,
    val providerConversationId: ConversationId,
    val unitCount: Int,
) {
    init {
        require(providerMessageId.kind == ProviderKind.SMS) {
            "Composer SMS operations require an SMS provider ID"
        }
        require(unitCount in 1..MAXIMUM_COMPOSER_SMS_UNIT_COUNT) {
            "Composer SMS unit count is out of bounds"
        }
    }

    override fun toString(): String = "ComposerSmsProviderBinding(unitCount=$unitCount, REDACTED)"
}

/** Content-free durable ownership for one accepted existing-thread SMS action. */
data class ComposerSmsOperation(
    val operationId: MessageId,
    val providerThreadId: ProviderThreadId,
    val draftId: DraftId,
    val draftRevision: DraftRevision,
    val subscriptionId: AuroraSubscriptionId,
    val phase: ComposerSmsOperationPhase,
    val providerBinding: ComposerSmsProviderBinding?,
    val createdTimestampMillis: Long,
    val updatedTimestampMillis: Long,
) {
    init {
        require(operationId.isComposerSmsOperationId()) {
            "Composer SMS operation ID is outside its pending-operation namespace"
        }
        require(createdTimestampMillis >= 0L) { "Composer SMS creation time cannot be negative" }
        require(updatedTimestampMillis >= createdTimestampMillis) {
            "Composer SMS update time cannot precede creation"
        }
        require(phase.acceptsBinding(providerBinding)) {
            "Composer SMS phase and provider binding disagree"
        }
    }

    val revision: ComposerSmsOperationRevision
        get() = ComposerSmsOperationRevision(updatedTimestampMillis)

    override fun toString(): String =
        "ComposerSmsOperation(phase=$phase, hasProviderBinding=${providerBinding != null}, REDACTED)"
}

/** Exact acknowledged draft revision requested for transactional reservation. */
data class ComposerSmsReservationRequest(
    val providerThreadId: ProviderThreadId,
    val draftId: DraftId,
    val expectedDraftRevision: DraftRevision,
    val subscriptionId: AuroraSubscriptionId,
    val createdTimestampMillis: Long,
) {
    init {
        require(createdTimestampMillis >= 0L) { "Composer SMS creation time cannot be negative" }
    }

    override fun toString(): String = "ComposerSmsReservationRequest(REDACTED)"
}

/** Reservation plus the authoritative body read inside the committing transaction. */
data class ComposerSmsReservation(
    val operation: ComposerSmsOperation,
    val authoritativeBody: String,
) {
    init {
        require(authoritativeBody.isNotBlank()) { "A reserved composer SMS body cannot be blank" }
        require(authoritativeBody.length <= Draft.MAX_BODY_CHARACTERS) {
            "A reserved composer SMS body is too large"
        }
    }

    override fun toString(): String = "ComposerSmsReservation(operation=$operation, body=REDACTED)"
}

/** Exact draft disposition committed atomically with a successful SENT callback. */
enum class ComposerSmsDraftClearance {
    CLEARED,
    ALREADY_ABSENT,
    NEWER_REVISION_PRESERVED,
}

data class ComposerSmsSentCompletion(
    val draftClearance: ComposerSmsDraftClearance,
)

/** Operation label that does not expose message content or database details. */
enum class ComposerSmsStorageOperation {
    RESERVE,
    READ,
    OBSERVE,
    RECOVER,
    TRANSITION,
    COMPLETE_SENT,
    ACKNOWLEDGE,
}

/** Explicit state-store result; safety failures never masquerade as missing data. */
sealed interface ComposerSmsOperationResult<out T> {
    data class Success<T>(val value: T) : ComposerSmsOperationResult<T> {
        override fun toString(): String = "ComposerSmsOperationResult.Success(REDACTED)"
    }

    data object NotFound : ComposerSmsOperationResult<Nothing>
    data object Conflict : ComposerSmsOperationResult<Nothing>
    data object LimitExceeded : ComposerSmsOperationResult<Nothing>
    data object StaleWrite : ComposerSmsOperationResult<Nothing>
    data object IneligibleDraft : ComposerSmsOperationResult<Nothing>
    data object PhaseMismatch : ComposerSmsOperationResult<Nothing>
    data object ProviderMismatch : ComposerSmsOperationResult<Nothing>
    data object InvalidTimestamp : ComposerSmsOperationResult<Nothing>
    data object CorruptData : ComposerSmsOperationResult<Nothing>

    data class StorageFailure(
        val operation: ComposerSmsStorageOperation,
    ) : ComposerSmsOperationResult<Nothing>
}

interface ComposerSmsOperationRepository {
    /** Reserves against the exact provider-thread draft revision without persisting its body. */
    suspend fun reserve(
        request: ComposerSmsReservationRequest,
    ): ComposerSmsOperationResult<ComposerSmsReservation>

    suspend fun read(
        operationId: MessageId,
    ): ComposerSmsOperationResult<ComposerSmsOperation>

    /** Emits the exact active operation for one thread, or a typed empty/failure result. */
    fun observeByThread(
        providerThreadId: ProviderThreadId,
    ): Flow<ComposerSmsOperationResult<ComposerSmsOperation?>>

    /** Returns every active operation; the hard table cap keeps this snapshot bounded. */
    suspend fun recoverySnapshot(): ComposerSmsOperationResult<List<ComposerSmsOperation>>

    suspend fun markPrepared(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation>

    suspend fun markSubmitting(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation>

    suspend fun markPlatformAccepted(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation>

    suspend fun markSubmissionUnknown(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation>

    /** Persists the exact successful SENT callback before provider reconciliation. */
    suspend fun markSentCallbackSucceeded(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation>

    /** Only RESERVED or PREPARED work can be proven known-unsent by recovery. */
    suspend fun markKnownUnsent(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation>

    /** An exact failed SENT callback proves this bound operation was not sent. */
    suspend fun markSentCallbackFailed(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation>

    /**
     * After the exact provider COMPLETE write, atomically clears only the reserved draft
     * revision and removes the durably successful operation.
     */
    suspend fun completeSentAndRemove(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
    ): ComposerSmsOperationResult<ComposerSmsSentCompletion>

    /** Removes only a user-acknowledged KNOWN_UNSENT or SUBMISSION_UNKNOWN terminal record. */
    suspend fun acknowledgeAndRemove(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
    ): ComposerSmsOperationResult<Unit>
}

fun MessageId.isComposerSmsOperationId(): Boolean =
    kind == ProviderKind.PENDING_OPERATION &&
        value in (COMPOSER_OPERATION_ID_BOUNDARY + 1L) until INLINE_REPLY_OPERATION_ID_BOUNDARY

internal fun ComposerSmsOperationPhase.acceptsBinding(binding: ComposerSmsProviderBinding?): Boolean =
    when (this) {
        ComposerSmsOperationPhase.RESERVED -> binding == null
        ComposerSmsOperationPhase.PREPARED,
        ComposerSmsOperationPhase.SUBMITTING,
        ComposerSmsOperationPhase.PLATFORM_ACCEPTED,
        ComposerSmsOperationPhase.SENT_CALLBACK_SUCCEEDED,
        ComposerSmsOperationPhase.SUBMISSION_UNKNOWN,
        -> binding != null
        ComposerSmsOperationPhase.KNOWN_UNSENT -> true
    }

const val MAXIMUM_COMPOSER_SMS_OPERATIONS: Int = 128
const val MAXIMUM_COMPOSER_SMS_UNIT_COUNT: Int = 1
