// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import kotlinx.coroutines.flow.Flow
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.COMPOSER_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.INLINE_REPLY_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.MessageTransportKind
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

/** Durable proof retained after a user releases a submission-unknown composer operation. */
enum class AcknowledgedComposerSmsCallbackProof {
    AWAITING_CALLBACK,
    SENT,
    FAILED,
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
        require(providerMessageId.kind.isTelephonyProvider) {
            "Composer operations require an SMS or MMS provider ID"
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
    val frozenSignature: MessageSignature? = null,
    val transport: MessageTransportKind = MessageTransportKind.SMS,
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
        require(providerBinding == null || providerBinding.providerMessageId.kind == transport.providerKind) {
            "Composer transport and provider binding disagree"
        }
    }

    val revision: ComposerSmsOperationRevision
        get() = ComposerSmsOperationRevision(updatedTimestampMillis)

    override fun toString(): String =
        "ComposerSmsOperation(phase=$phase, hasProviderBinding=${providerBinding != null}, REDACTED)"
}

/**
 * Content-free ownership for an exact callback that may arrive after the user keeps the draft.
 *
 * This receipt is separate from the active operation so it cannot lock the composer or clear the
 * preserved draft. It exists only long enough to reconcile the exact provider row.
 */
data class AcknowledgedComposerSmsReceipt(
    val operationId: MessageId,
    val providerBinding: ComposerSmsProviderBinding,
    val callbackProof: AcknowledgedComposerSmsCallbackProof,
    val acknowledgedTimestampMillis: Long,
    val updatedTimestampMillis: Long,
) {
    init {
        require(operationId.isComposerSmsOperationId()) {
            "Acknowledged composer SMS operation ID is outside its pending-operation namespace"
        }
        require(acknowledgedTimestampMillis >= 0L) {
            "Acknowledged composer SMS timestamp cannot be negative"
        }
        require(updatedTimestampMillis >= acknowledgedTimestampMillis) {
            "Acknowledged composer SMS update time cannot precede acknowledgement"
        }
        when (callbackProof) {
            AcknowledgedComposerSmsCallbackProof.AWAITING_CALLBACK ->
                require(updatedTimestampMillis == acknowledgedTimestampMillis) {
                    "Awaiting composer SMS receipt cannot have a later update"
                }
            AcknowledgedComposerSmsCallbackProof.SENT,
            AcknowledgedComposerSmsCallbackProof.FAILED,
            -> require(updatedTimestampMillis > acknowledgedTimestampMillis) {
                "Checkpointed composer SMS receipt requires a later update"
            }
        }
    }

    val revision: ComposerSmsOperationRevision
        get() = ComposerSmsOperationRevision(updatedTimestampMillis)

    override fun toString(): String =
        "AcknowledgedComposerSmsReceipt(callbackProof=$callbackProof, REDACTED)"
}

/** Exact acknowledged draft revision requested for transactional reservation. */
data class ComposerSmsReservationRequest(
    val providerThreadId: ProviderThreadId,
    val draftId: DraftId,
    val expectedDraftRevision: DraftRevision,
    val subscriptionId: AuroraSubscriptionId,
    val createdTimestampMillis: Long,
    val frozenSignature: MessageSignature? = null,
    val transport: MessageTransportKind = MessageTransportKind.SMS,
    val hasAttachments: Boolean = false,
) {
    init {
        require(createdTimestampMillis >= 0L) { "Composer SMS creation time cannot be negative" }
        require(!hasAttachments || transport == MessageTransportKind.MMS) {
            "Composer attachments require MMS transport"
        }
    }

    override fun toString(): String = "ComposerSmsReservationRequest(REDACTED)"
}

/** Reservation plus authoritative draft text read inside the committing transaction. */
data class ComposerSmsReservation(
    val operation: ComposerSmsOperation,
    val authoritativeBody: String?,
    val authoritativeSubject: String? = null,
) {
    init {
        require(authoritativeBody == null || authoritativeBody.isNotBlank()) {
            "A reserved composer body cannot be blank"
        }
        require(authoritativeBody == null || authoritativeBody.length <= Draft.MAX_BODY_CHARACTERS) {
            "A reserved composer SMS body is too large"
        }
        require(authoritativeBody != null || operation.transport == MessageTransportKind.MMS) {
            "Only MMS can reserve an attachment-only draft"
        }
        require(authoritativeSubject == null || authoritativeSubject.isNotBlank()) {
            "A reserved composer subject cannot be blank"
        }
        require(authoritativeSubject == null || authoritativeSubject.length <= Draft.MAX_SUBJECT_CHARACTERS) {
            "A reserved composer subject is too large"
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

    /** Returns durable late-callback receipts, bounded by the same fail-closed storage policy. */
    suspend fun acknowledgedRecoverySnapshot():
        ComposerSmsOperationResult<List<AcknowledgedComposerSmsReceipt>>

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

    /**
     * Removes a user-acknowledged terminal record. SUBMISSION_UNKNOWN atomically becomes a
     * content-free late-callback receipt; KNOWN_UNSENT needs no receipt.
     */
    suspend fun acknowledgeAndRemove(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        acknowledgedTimestampMillis: Long,
    ): ComposerSmsOperationResult<Unit>

    suspend fun readAcknowledged(
        operationId: MessageId,
    ): ComposerSmsOperationResult<AcknowledgedComposerSmsReceipt>

    /** Checkpoints an exact late SENT callback before provider reconciliation. */
    suspend fun markAcknowledgedSent(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<AcknowledgedComposerSmsReceipt>

    /** Checkpoints an exact late failed SENT callback before provider reconciliation. */
    suspend fun markAcknowledgedFailed(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<AcknowledgedComposerSmsReceipt>

    /** Removes only the exact receipt whose checkpointed proof has reached the provider terminal. */
    suspend fun completeAcknowledged(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        callbackProof: AcknowledgedComposerSmsCallbackProof,
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
const val MAXIMUM_ACKNOWLEDGED_COMPOSER_SMS_RECEIPTS: Int = 128
const val MAXIMUM_COMPOSER_SMS_UNIT_COUNT: Int = 1

internal val MessageTransportKind.providerKind: ProviderKind
    get() = when (this) {
        MessageTransportKind.SMS -> ProviderKind.SMS
        MessageTransportKind.MMS -> ProviderKind.MMS
    }
