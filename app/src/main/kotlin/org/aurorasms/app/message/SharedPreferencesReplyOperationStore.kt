// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.annotation.SuppressLint
import android.content.Context
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.telephony.SmsProviderStatus

/**
 * Private, bounded, process-death-safe routing for notification reply callbacks.
 *
 * Records contain only provider-qualified numeric IDs, lifecycle times/state,
 * sent-part progress, and the bounded provider-status outbox. Bodies,
 * recipients, and subscription identifiers cannot enter this API.
 * Security-relevant writes use synchronous commits and fail closed.
 */
@SuppressLint("UseKtx")
internal class SharedPreferencesReplyOperationStore(
    context: Context,
    private val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES,
) : ReplyOperationStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    init {
        require(maximumEntries in 1..ABSOLUTE_MAXIMUM_ENTRIES) {
            "maximumEntries is out of bounds"
        }
    }

    override fun reserve(
        record: ReplyOperationRecord,
        nowMillis: Long,
    ): ReplyOperationStoreReservationResult = synchronized(PREFERENCES_LOCK) {
        val snapshot = snapshot(nowMillis)
        if (
            snapshot.operations.any { operation -> operation.operationId == record.operationId } ||
            record.operationId in snapshot.corruptOperationIds
        ) {
            return@synchronized if (removeKeys(snapshot.removableKeys)) {
                ReplyOperationStoreReservationResult.Collision
            } else {
                ReplyOperationStoreReservationResult.PersistenceFailure
            }
        }
        if (snapshot.operations.size + snapshot.corruptOperationIds.size >= maximumEntries) {
            return@synchronized if (removeKeys(snapshot.removableKeys)) {
                ReplyOperationStoreReservationResult.Full
            } else {
                ReplyOperationStoreReservationResult.PersistenceFailure
            }
        }
        val editor = preferences.edit()
        snapshot.removableKeys.forEach(editor::remove)
        val operation = StoredOperation(
            operationId = record.operationId,
            conversationId = record.conversationId,
            sourceMessageId = record.sourceMessageId,
            createdAtMillis = record.createdAtMillis,
            expiresAtMillis = record.expiresAtMillis,
            state = ReplyOperationState.RESERVED,
            expectedUnitCount = UNKNOWN_UNIT_COUNT,
            sentUnits = BooleanArray(0),
            providerMessageId = null,
            desiredProviderStatus = null,
            providerUpdatePending = false,
        )
        val encodedOperation = encode(operation)
            ?: return@synchronized ReplyOperationStoreReservationResult.PersistenceFailure
        if (editor.putString(key(record.operationId), encodedOperation).commit()) {
            ReplyOperationStoreReservationResult.Reserved
        } else {
            ReplyOperationStoreReservationResult.PersistenceFailure
        }
    }

    override fun recordSubmitted(
        operationId: Long,
        unitCount: Int,
        providerMessageId: ProviderMessageId?,
        nowMillis: Long,
    ): ReplyOperationSubmittedResult = synchronized(PREFERENCES_LOCK) {
        if (
            operationId <= 0L ||
            unitCount !in 1..MAXIMUM_UNIT_COUNT ||
            !providerMessageId.isValidSmsProviderId()
        ) {
            return@synchronized ReplyOperationSubmittedResult.Invalid
        }
        when (val current = read(operationId, nowMillis)) {
            CurrentOperation.Missing -> ReplyOperationSubmittedResult.Untracked
            CurrentOperation.CorruptOwnership -> ReplyOperationSubmittedResult.CorruptOwnership
            CurrentOperation.PersistenceFailure -> ReplyOperationSubmittedResult.PersistenceFailure
            is CurrentOperation.Found -> {
                when (current.operation.state) {
                    ReplyOperationState.RESERVED,
                    ReplyOperationState.CLAIMED,
                    ReplyOperationState.PREPARED ->
                        return@synchronized ReplyOperationSubmittedResult.PhaseMismatch
                    else -> Unit
                }
                if (
                    current.operation.expectedUnitCount != UNKNOWN_UNIT_COUNT &&
                    current.operation.expectedUnitCount != unitCount
                ) {
                    return@synchronized ReplyOperationSubmittedResult.UnitCountMismatch
                }
                val bound = current.operation.bindProvider(providerMessageId)
                    ?: return@synchronized ReplyOperationSubmittedResult.ProviderMismatch
                val counted = bound.bindUnitCountWithoutProgress(unitCount)
                    ?: return@synchronized ReplyOperationSubmittedResult.UnitCountMismatch
                val operation = if (counted.state == ReplyOperationState.SUBMITTING) {
                    counted.copy(state = ReplyOperationState.SUBMITTED)
                } else {
                    counted
                }
                val result = when (operation.state) {
                    ReplyOperationState.FAILURE_PENDING ->
                        ReplyOperationSubmittedResult.FailurePending
                    ReplyOperationState.FAILURE_NOTIFIED ->
                        ReplyOperationSubmittedResult.FailureNotified
                    ReplyOperationState.SUBMISSION_UNKNOWN_PENDING ->
                        ReplyOperationSubmittedResult.SubmissionUnknownPending
                    ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED ->
                        ReplyOperationSubmittedResult.SubmissionUnknownNotified
                    ReplyOperationState.SUCCESS_PENDING ->
                        ReplyOperationSubmittedResult.SuccessPending
                    ReplyOperationState.SUCCESS_COMPLETE ->
                        ReplyOperationSubmittedResult.SuccessComplete
                    ReplyOperationState.SUBMITTING,
                    ReplyOperationState.SUBMITTED -> ReplyOperationSubmittedResult.Tracked
                    ReplyOperationState.RESERVED,
                    ReplyOperationState.CLAIMED,
                    ReplyOperationState.PREPARED -> error("pre-submission phases returned above")
                }
                if (operation == current.operation || put(operation)) {
                    result
                } else {
                    ReplyOperationSubmittedResult.PersistenceFailure
                }
            }
        }
    }

    override fun markClaimed(
        operationId: Long,
        nowMillis: Long,
    ): ReplyOperationPhaseTransitionResult = synchronized(PREFERENCES_LOCK) {
        if (operationId <= 0L) return@synchronized ReplyOperationPhaseTransitionResult.Invalid
        when (val current = read(operationId, nowMillis)) {
            CurrentOperation.Missing -> ReplyOperationPhaseTransitionResult.Untracked
            CurrentOperation.CorruptOwnership ->
                ReplyOperationPhaseTransitionResult.CorruptOwnership
            CurrentOperation.PersistenceFailure ->
                ReplyOperationPhaseTransitionResult.PersistenceFailure
            is CurrentOperation.Found -> {
                val result = when (current.operation.state) {
                    ReplyOperationState.RESERVED -> ReplyOperationPhaseTransitionResult.Transitioned
                    ReplyOperationState.CLAIMED ->
                        ReplyOperationPhaseTransitionResult.AlreadyInPhase
                    ReplyOperationState.PREPARED,
                    ReplyOperationState.SUBMITTING,
                    ReplyOperationState.SUBMITTED ->
                        ReplyOperationPhaseTransitionResult.AlreadyAdvanced
                    ReplyOperationState.SUBMISSION_UNKNOWN_PENDING,
                    ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED ->
                        ReplyOperationPhaseTransitionResult.SubmissionUnknown
                    ReplyOperationState.FAILURE_PENDING,
                    ReplyOperationState.FAILURE_NOTIFIED,
                    ReplyOperationState.SUCCESS_PENDING,
                    ReplyOperationState.SUCCESS_COMPLETE ->
                        ReplyOperationPhaseTransitionResult.Terminal
                }
                if (current.operation.state != ReplyOperationState.RESERVED) {
                    result
                } else if (put(current.operation.copy(state = ReplyOperationState.CLAIMED))) {
                    result
                } else {
                    ReplyOperationPhaseTransitionResult.PersistenceFailure
                }
            }
        }
    }

    override fun recordTransportPhase(
        operationId: Long,
        providerMessageId: ProviderMessageId,
        unitCount: Int,
        phase: ReplyOperationTransportPhase,
        nowMillis: Long,
    ): ReplyOperationPhaseTransitionResult = synchronized(PREFERENCES_LOCK) {
        if (
            operationId <= 0L ||
            providerMessageId.kind != ProviderKind.SMS ||
            unitCount !in 1..MAXIMUM_UNIT_COUNT
        ) {
            return@synchronized ReplyOperationPhaseTransitionResult.Invalid
        }
        when (val current = read(operationId, nowMillis)) {
            CurrentOperation.Missing -> ReplyOperationPhaseTransitionResult.Untracked
            CurrentOperation.CorruptOwnership ->
                ReplyOperationPhaseTransitionResult.CorruptOwnership
            CurrentOperation.PersistenceFailure ->
                ReplyOperationPhaseTransitionResult.PersistenceFailure
            is CurrentOperation.Found -> {
                val operation = current.operation
                if (
                    operation.providerMessageId != null &&
                    operation.providerMessageId != providerMessageId
                ) {
                    return@synchronized ReplyOperationPhaseTransitionResult.ProviderMismatch
                }
                if (
                    operation.expectedUnitCount != UNKNOWN_UNIT_COUNT &&
                    operation.expectedUnitCount != unitCount
                ) {
                    return@synchronized ReplyOperationPhaseTransitionResult.UnitCountMismatch
                }
                val expectedState = when (phase) {
                    ReplyOperationTransportPhase.PREPARED -> ReplyOperationState.CLAIMED
                    ReplyOperationTransportPhase.SUBMITTING -> ReplyOperationState.PREPARED
                }
                val targetState = when (phase) {
                    ReplyOperationTransportPhase.PREPARED -> ReplyOperationState.PREPARED
                    ReplyOperationTransportPhase.SUBMITTING -> ReplyOperationState.SUBMITTING
                }
                val result = when (operation.state) {
                    ReplyOperationState.RESERVED ->
                        ReplyOperationPhaseTransitionResult.PhaseMismatch
                    expectedState -> ReplyOperationPhaseTransitionResult.Transitioned
                    targetState -> ReplyOperationPhaseTransitionResult.AlreadyInPhase
                    ReplyOperationState.SUBMITTING,
                    ReplyOperationState.SUBMITTED ->
                        ReplyOperationPhaseTransitionResult.AlreadyAdvanced
                    ReplyOperationState.CLAIMED,
                    ReplyOperationState.PREPARED ->
                        ReplyOperationPhaseTransitionResult.PhaseMismatch
                    ReplyOperationState.SUBMISSION_UNKNOWN_PENDING,
                    ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED ->
                        ReplyOperationPhaseTransitionResult.SubmissionUnknown
                    ReplyOperationState.FAILURE_PENDING,
                    ReplyOperationState.FAILURE_NOTIFIED,
                    ReplyOperationState.SUCCESS_PENDING,
                    ReplyOperationState.SUCCESS_COMPLETE ->
                        ReplyOperationPhaseTransitionResult.Terminal
                }
                if (
                    result == ReplyOperationPhaseTransitionResult.PhaseMismatch ||
                    result == ReplyOperationPhaseTransitionResult.Terminal ||
                    result == ReplyOperationPhaseTransitionResult.SubmissionUnknown
                ) {
                    return@synchronized result
                }
                val bound = operation.bindProvider(providerMessageId)
                    ?.bindUnitCountWithoutProgress(unitCount)
                    ?: return@synchronized ReplyOperationPhaseTransitionResult.ProviderMismatch
                val updated = if (result == ReplyOperationPhaseTransitionResult.Transitioned) {
                    bound.copy(state = targetState)
                } else {
                    bound
                }
                if (updated == operation || put(updated)) {
                    result
                } else {
                    ReplyOperationPhaseTransitionResult.PersistenceFailure
                }
            }
        }
    }

    override fun recordSent(
        operationId: Long,
        unitIndex: Int,
        unitCount: Int,
        providerMessageId: ProviderMessageId?,
        nowMillis: Long,
        terminalExpiresAtMillis: Long,
    ): ReplyOperationSentResult = synchronized(PREFERENCES_LOCK) {
        if (
            operationId <= 0L ||
            unitCount !in 1..MAXIMUM_UNIT_COUNT ||
            unitIndex !in 0 until unitCount ||
            !providerMessageId.isValidSmsProviderId() ||
            terminalExpiresAtMillis <= nowMillis
        ) {
            return@synchronized ReplyOperationSentResult.Invalid
        }
        when (val current = read(operationId, nowMillis)) {
            CurrentOperation.Missing -> ReplyOperationSentResult.Untracked
            CurrentOperation.CorruptOwnership -> ReplyOperationSentResult.CorruptOwnership
            CurrentOperation.PersistenceFailure -> ReplyOperationSentResult.PersistenceFailure
            is CurrentOperation.Found -> {
                if (current.operation.state == ReplyOperationState.RESERVED) {
                    return@synchronized ReplyOperationSentResult.PhaseMismatch
                }
                if (
                    current.operation.expectedUnitCount != UNKNOWN_UNIT_COUNT &&
                    current.operation.expectedUnitCount != unitCount
                ) {
                    return@synchronized ReplyOperationSentResult.UnitCountMismatch
                }
                var operation = current.operation.bindProvider(providerMessageId)
                    ?: return@synchronized ReplyOperationSentResult.ProviderMismatch
                when (operation.state) {
                    ReplyOperationState.FAILURE_PENDING,
                    ReplyOperationState.FAILURE_NOTIFIED -> {
                        operation = operation.requestProviderStatus(SmsProviderStatus.FAILED)
                        if (operation != current.operation && !put(operation)) {
                            return@synchronized ReplyOperationSentResult.PersistenceFailure
                        }
                        return@synchronized if (
                            operation.state == ReplyOperationState.FAILURE_PENDING
                        ) {
                            ReplyOperationSentResult.FailurePending(operation.conversationId)
                        } else {
                            ReplyOperationSentResult.FailureNotified(operation.conversationId)
                        }
                    }
                    ReplyOperationState.SUCCESS_PENDING,
                    ReplyOperationState.SUCCESS_COMPLETE -> {
                        operation = operation.requestProviderStatus(SmsProviderStatus.COMPLETE)
                        if (operation != current.operation && !put(operation)) {
                            return@synchronized ReplyOperationSentResult.PersistenceFailure
                        }
                        return@synchronized if (
                            operation.state == ReplyOperationState.SUCCESS_PENDING
                        ) {
                            ReplyOperationSentResult.SuccessPending(
                                operation.conversationId,
                                operation.sourceMessageId,
                            )
                        } else {
                            ReplyOperationSentResult.SuccessComplete(
                                operation.conversationId,
                                operation.sourceMessageId,
                            )
                        }
                    }
                    ReplyOperationState.CLAIMED,
                    ReplyOperationState.PREPARED,
                    ReplyOperationState.SUBMITTING,
                    ReplyOperationState.SUBMITTED,
                    ReplyOperationState.SUBMISSION_UNKNOWN_PENDING,
                    ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED -> Unit
                    ReplyOperationState.RESERVED -> error("reserved phase returned above")
                }
                val sentUnits = if (operation.expectedUnitCount == UNKNOWN_UNIT_COUNT) {
                    BooleanArray(unitCount)
                } else {
                    operation.sentUnits.copyOf()
                }
                if (sentUnits[unitIndex]) {
                    val duplicate = operation.advanceCallbackOwnedPhase()
                    if (duplicate != current.operation && !put(duplicate)) {
                        return@synchronized ReplyOperationSentResult.PersistenceFailure
                    }
                    return@synchronized ReplyOperationSentResult.Pending(
                        conversationId = operation.conversationId,
                        duplicate = true,
                    )
                }
                sentUnits[unitIndex] = true
                if (sentUnits.all { it }) {
                    val pending = operation.copy(
                        expiresAtMillis = maxOf(
                            operation.expiresAtMillis,
                            terminalExpiresAtMillis,
                        ),
                        state = ReplyOperationState.SUCCESS_PENDING,
                        expectedUnitCount = unitCount,
                        sentUnits = sentUnits,
                    ).requestProviderStatus(SmsProviderStatus.COMPLETE)
                    if (put(pending)) {
                        ReplyOperationSentResult.SuccessPending(
                            operation.conversationId,
                            operation.sourceMessageId,
                        )
                    } else {
                        ReplyOperationSentResult.PersistenceFailure
                    }
                } else {
                    val updated = operation.copy(
                        expectedUnitCount = unitCount,
                        sentUnits = sentUnits,
                    ).advanceCallbackOwnedPhase()
                    if (put(updated)) {
                        ReplyOperationSentResult.Pending(
                            conversationId = operation.conversationId,
                            duplicate = false,
                        )
                    } else {
                        ReplyOperationSentResult.PersistenceFailure
                    }
                }
            }
        }
    }

    override fun markFailurePending(
        operationId: Long,
        providerMessageId: ProviderMessageId?,
        unitIndex: Int,
        unitCount: Int,
        nowMillis: Long,
        terminalExpiresAtMillis: Long,
    ): ReplyOperationFailureResult = synchronized(PREFERENCES_LOCK) {
        if (
            operationId <= 0L ||
            !providerMessageId.isValidSmsProviderId() ||
            unitCount !in 1..MAXIMUM_UNIT_COUNT ||
            unitIndex !in 0 until unitCount ||
            terminalExpiresAtMillis <= nowMillis
        ) {
            return@synchronized ReplyOperationFailureResult.Invalid
        }
        when (val current = read(operationId, nowMillis)) {
            CurrentOperation.Missing -> ReplyOperationFailureResult.Untracked
            CurrentOperation.CorruptOwnership -> ReplyOperationFailureResult.CorruptOwnership
            CurrentOperation.PersistenceFailure -> ReplyOperationFailureResult.PersistenceFailure
            is CurrentOperation.Found -> {
                if (current.operation.state == ReplyOperationState.RESERVED) {
                    return@synchronized ReplyOperationFailureResult.PhaseMismatch
                }
                val bound = current.operation.bindProvider(providerMessageId)
                    ?: return@synchronized ReplyOperationFailureResult.ProviderMismatch
                val counted = bound.bindUnitCountWithoutProgress(unitCount)
                    ?: return@synchronized ReplyOperationFailureResult.UnitCountMismatch
                val operation = when (counted.state) {
                    ReplyOperationState.CLAIMED,
                    ReplyOperationState.PREPARED,
                    ReplyOperationState.SUBMITTING,
                    ReplyOperationState.SUBMITTED,
                    ReplyOperationState.SUBMISSION_UNKNOWN_PENDING -> {
                        counted.copy(
                            expiresAtMillis = maxOf(
                                counted.expiresAtMillis,
                                terminalExpiresAtMillis,
                            ),
                            state = ReplyOperationState.FAILURE_PENDING,
                        ).requestProviderStatus(SmsProviderStatus.FAILED)
                    }
                    ReplyOperationState.FAILURE_PENDING,
                    ReplyOperationState.FAILURE_NOTIFIED ->
                        counted.requestProviderStatus(SmsProviderStatus.FAILED)
                    ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED -> counted.copy(
                        expiresAtMillis = maxOf(
                            counted.expiresAtMillis,
                            terminalExpiresAtMillis,
                        ),
                        state = ReplyOperationState.FAILURE_NOTIFIED,
                    ).requestProviderStatus(SmsProviderStatus.FAILED)
                    ReplyOperationState.SUCCESS_PENDING,
                    ReplyOperationState.SUCCESS_COMPLETE -> counted
                    ReplyOperationState.RESERVED -> error("reserved phase returned above")
                }
                val result = when (operation.state) {
                    ReplyOperationState.RESERVED,
                    ReplyOperationState.CLAIMED,
                    ReplyOperationState.PREPARED,
                    ReplyOperationState.SUBMITTING,
                    ReplyOperationState.SUBMITTED,
                    ReplyOperationState.SUBMISSION_UNKNOWN_PENDING,
                    ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED ->
                        error("failure transition was not applied")
                    ReplyOperationState.FAILURE_PENDING -> ReplyOperationFailureResult.Pending(
                        conversationId = operation.conversationId,
                        duplicate = current.operation.state == ReplyOperationState.FAILURE_PENDING,
                    )
                    ReplyOperationState.FAILURE_NOTIFIED ->
                        ReplyOperationFailureResult.Notified(operation.conversationId)
                    ReplyOperationState.SUCCESS_PENDING,
                    ReplyOperationState.SUCCESS_COMPLETE ->
                        ReplyOperationFailureResult.SuccessTerminal(operation.conversationId)
                }
                if (operation == current.operation || put(operation)) {
                    result
                } else {
                    ReplyOperationFailureResult.PersistenceFailure
                }
            }
        }
    }

    override fun acknowledgeFailureNotification(
        operationId: Long,
        nowMillis: Long,
    ): ReplyOperationAcknowledgementResult = synchronized(PREFERENCES_LOCK) {
        if (operationId <= 0L) return@synchronized ReplyOperationAcknowledgementResult.Invalid
        when (val current = read(operationId, nowMillis)) {
            CurrentOperation.Missing -> ReplyOperationAcknowledgementResult.Untracked
            CurrentOperation.CorruptOwnership ->
                ReplyOperationAcknowledgementResult.CorruptOwnership
            CurrentOperation.PersistenceFailure ->
                ReplyOperationAcknowledgementResult.PersistenceFailure
            is CurrentOperation.Found -> {
                val acknowledgedState = when (current.operation.state) {
                    ReplyOperationState.FAILURE_PENDING -> ReplyOperationState.FAILURE_NOTIFIED
                    ReplyOperationState.SUBMISSION_UNKNOWN_PENDING ->
                        ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED
                    ReplyOperationState.FAILURE_NOTIFIED,
                    ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED ->
                        return@synchronized ReplyOperationAcknowledgementResult.AlreadyAcknowledged
                    else -> return@synchronized ReplyOperationAcknowledgementResult.NotPending
                }
                if (put(current.operation.copy(state = acknowledgedState))) {
                    ReplyOperationAcknowledgementResult.Acknowledged
                } else {
                    ReplyOperationAcknowledgementResult.PersistenceFailure
                }
            }
        }
    }

    override fun markSuccessPending(
        operationId: Long,
        nowMillis: Long,
        terminalExpiresAtMillis: Long,
    ): ReplyOperationSuccessResult = synchronized(PREFERENCES_LOCK) {
        if (operationId <= 0L || terminalExpiresAtMillis <= nowMillis) {
            return@synchronized ReplyOperationSuccessResult.Invalid
        }
        when (val current = read(operationId, nowMillis)) {
            CurrentOperation.Missing -> ReplyOperationSuccessResult.Untracked
            CurrentOperation.CorruptOwnership -> ReplyOperationSuccessResult.CorruptOwnership
            CurrentOperation.PersistenceFailure -> ReplyOperationSuccessResult.PersistenceFailure
            is CurrentOperation.Found -> {
                val operation = current.operation
                when (operation.state) {
                    ReplyOperationState.RESERVED -> ReplyOperationSuccessResult.PhaseMismatch
                    ReplyOperationState.CLAIMED,
                    ReplyOperationState.PREPARED,
                    ReplyOperationState.SUBMITTING,
                    ReplyOperationState.SUBMITTED -> {
                        if (!operation.hasDurableSuccessEvidence()) {
                            return@synchronized ReplyOperationSuccessResult.PhaseMismatch
                        }
                        val pending = operation.copy(
                            expiresAtMillis = maxOf(
                                operation.expiresAtMillis,
                                terminalExpiresAtMillis,
                            ),
                            state = ReplyOperationState.SUCCESS_PENDING,
                        )
                        if (put(pending)) {
                            ReplyOperationSuccessResult.Pending(
                                conversationId = operation.conversationId,
                                sourceMessageId = operation.sourceMessageId,
                                duplicate = false,
                            )
                        } else {
                            ReplyOperationSuccessResult.PersistenceFailure
                        }
                    }
                    ReplyOperationState.SUCCESS_PENDING -> ReplyOperationSuccessResult.Pending(
                        conversationId = operation.conversationId,
                        sourceMessageId = operation.sourceMessageId,
                        duplicate = true,
                    )
                    ReplyOperationState.SUCCESS_COMPLETE ->
                        ReplyOperationSuccessResult.Complete(
                            operation.conversationId,
                            operation.sourceMessageId,
                        )
                    ReplyOperationState.FAILURE_PENDING ->
                        ReplyOperationSuccessResult.FailurePending(operation.conversationId)
                    ReplyOperationState.FAILURE_NOTIFIED ->
                        ReplyOperationSuccessResult.FailureNotified(operation.conversationId)
                    ReplyOperationState.SUBMISSION_UNKNOWN_PENDING ->
                        ReplyOperationSuccessResult.SubmissionUnknownPending(
                            operation.conversationId,
                        )
                    ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED ->
                        ReplyOperationSuccessResult.SubmissionUnknownNotified(
                            operation.conversationId,
                        )
                }
            }
        }
    }

    override fun acknowledgeSuccessCancellation(
        operationId: Long,
        nowMillis: Long,
    ): ReplyOperationAcknowledgementResult = synchronized(PREFERENCES_LOCK) {
        acknowledge(
            operationId = operationId,
            nowMillis = nowMillis,
            pendingState = ReplyOperationState.SUCCESS_PENDING,
            acknowledgedState = ReplyOperationState.SUCCESS_COMPLETE,
        )
    }

    override fun pendingFailures(nowMillis: Long): ReplyOperationStorePendingFailuresResult =
        synchronized(PREFERENCES_LOCK) {
            when (val snapshot = cleanedSnapshot(nowMillis)) {
                is CleanedSnapshot.Available -> ReplyOperationStorePendingFailuresResult.Available(
                    snapshot.operations.pendingFailures(),
                )
                CleanedSnapshot.PersistenceFailure ->
                    ReplyOperationStorePendingFailuresResult.PersistenceFailure
            }
        }

    override fun recoverInheritedOperations(
        nowMillis: Long,
        terminalExpiresAtMillis: Long,
    ): ReplyOperationRecoveryResult = synchronized(PREFERENCES_LOCK) {
        if (terminalExpiresAtMillis <= nowMillis) {
            return@synchronized ReplyOperationRecoveryResult.PersistenceFailure
        }
        val snapshot = snapshot(nowMillis)
        val editor = preferences.edit()
        snapshot.removableKeys.forEach(editor::remove)
        var knownUnsent = 0
        var preparedFailed = 0
        var submissionUnknown = 0
        var encodingFailure = false
        snapshot.operations.forEach { operation ->
            val recovered = when (operation.state) {
                ReplyOperationState.RESERVED,
                ReplyOperationState.CLAIMED -> {
                    knownUnsent += 1
                    operation.copy(
                        expiresAtMillis = maxOf(
                            operation.expiresAtMillis,
                            terminalExpiresAtMillis,
                        ),
                        state = ReplyOperationState.FAILURE_PENDING,
                    )
                }
                ReplyOperationState.PREPARED -> {
                    preparedFailed += 1
                    operation.copy(
                        expiresAtMillis = maxOf(
                            operation.expiresAtMillis,
                            terminalExpiresAtMillis,
                        ),
                        state = ReplyOperationState.FAILURE_PENDING,
                    ).requestProviderStatus(SmsProviderStatus.FAILED)
                }
                ReplyOperationState.SUBMITTING,
                ReplyOperationState.SUBMITTED -> {
                    submissionUnknown += 1
                    operation.copy(
                        expiresAtMillis = maxOf(
                            operation.expiresAtMillis,
                            terminalExpiresAtMillis,
                        ),
                        state = ReplyOperationState.SUBMISSION_UNKNOWN_PENDING,
                    )
                }
                ReplyOperationState.FAILURE_PENDING,
                ReplyOperationState.FAILURE_NOTIFIED,
                ReplyOperationState.SUBMISSION_UNKNOWN_PENDING,
                ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED,
                ReplyOperationState.SUCCESS_PENDING,
                ReplyOperationState.SUCCESS_COMPLETE -> operation
            }
            if (recovered != operation) {
                val encodedOperation = encode(recovered)
                if (encodedOperation == null) {
                    encodingFailure = true
                } else {
                    editor.putString(key(operation.operationId), encodedOperation)
                }
            }
        }
        if (encodingFailure || !editor.commit()) {
            ReplyOperationRecoveryResult.PersistenceFailure
        } else {
            ReplyOperationRecoveryResult.Recovered(
                knownUnsentCount = knownUnsent,
                preparedFailureCount = preparedFailed,
                submissionUnknownCount = submissionUnknown,
                corruptCount = snapshot.corruptOperationIds.size,
            )
        }
    }

    override fun pendingSuccesses(nowMillis: Long): ReplyOperationStorePendingSuccessesResult =
        synchronized(PREFERENCES_LOCK) {
            when (val snapshot = cleanedSnapshot(nowMillis)) {
                is CleanedSnapshot.Available -> ReplyOperationStorePendingSuccessesResult.Available(
                    snapshot.operations.pending(ReplyOperationState.SUCCESS_PENDING),
                )
                CleanedSnapshot.PersistenceFailure ->
                    ReplyOperationStorePendingSuccessesResult.PersistenceFailure
            }
        }

    override fun recoverInterruptedOperation(
        operationId: Long,
        nowMillis: Long,
        terminalExpiresAtMillis: Long,
    ): ReplyOperationInterruptedRecoveryResult = synchronized(PREFERENCES_LOCK) {
        if (operationId <= 0L || terminalExpiresAtMillis <= nowMillis) {
            return@synchronized ReplyOperationInterruptedRecoveryResult.Invalid
        }
        when (val current = read(operationId, nowMillis)) {
            CurrentOperation.Missing -> ReplyOperationInterruptedRecoveryResult.Untracked
            CurrentOperation.CorruptOwnership ->
                ReplyOperationInterruptedRecoveryResult.CorruptOwnership
            CurrentOperation.PersistenceFailure ->
                ReplyOperationInterruptedRecoveryResult.PersistenceFailure
            is CurrentOperation.Found -> {
                val original = current.operation
                val failureKind: ReplyOperationFailureKind
                val transitioned: Boolean
                val recovered = when (original.state) {
                    ReplyOperationState.RESERVED,
                    ReplyOperationState.CLAIMED -> {
                        failureKind = ReplyOperationFailureKind.KNOWN_UNSENT
                        transitioned = true
                        original.copy(
                            expiresAtMillis = maxOf(
                                original.expiresAtMillis,
                                terminalExpiresAtMillis,
                            ),
                            state = ReplyOperationState.FAILURE_PENDING,
                        )
                    }
                    ReplyOperationState.PREPARED -> {
                        failureKind = ReplyOperationFailureKind.KNOWN_UNSENT
                        transitioned = true
                        original.copy(
                            expiresAtMillis = maxOf(
                                original.expiresAtMillis,
                                terminalExpiresAtMillis,
                            ),
                            state = ReplyOperationState.FAILURE_PENDING,
                        ).requestProviderStatus(SmsProviderStatus.FAILED)
                    }
                    ReplyOperationState.SUBMITTING,
                    ReplyOperationState.SUBMITTED -> {
                        failureKind = ReplyOperationFailureKind.SUBMISSION_UNKNOWN
                        transitioned = true
                        original.copy(
                            expiresAtMillis = maxOf(
                                original.expiresAtMillis,
                                terminalExpiresAtMillis,
                            ),
                            state = ReplyOperationState.SUBMISSION_UNKNOWN_PENDING,
                        )
                    }
                    ReplyOperationState.FAILURE_PENDING -> {
                        failureKind = ReplyOperationFailureKind.KNOWN_UNSENT
                        transitioned = false
                        original
                    }
                    ReplyOperationState.SUBMISSION_UNKNOWN_PENDING -> {
                        failureKind = ReplyOperationFailureKind.SUBMISSION_UNKNOWN
                        transitioned = false
                        original
                    }
                    ReplyOperationState.FAILURE_NOTIFIED -> return@synchronized interruptedNotified(
                        original,
                        ReplyOperationFailureKind.KNOWN_UNSENT,
                    )
                    ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED ->
                        return@synchronized interruptedNotified(
                            original,
                            ReplyOperationFailureKind.SUBMISSION_UNKNOWN,
                        )
                    ReplyOperationState.SUCCESS_PENDING,
                    ReplyOperationState.SUCCESS_COMPLETE ->
                        return@synchronized ReplyOperationInterruptedRecoveryResult.SuccessTerminal(
                            conversationId = original.conversationId,
                        )
                }
                if (recovered != original && !put(recovered)) {
                    ReplyOperationInterruptedRecoveryResult.PersistenceFailure
                } else {
                    interruptedPending(recovered, failureKind, transitioned)
                }
            }
        }
    }

    override fun recordDeliveryFailure(
        operationId: Long,
        unitIndex: Int,
        unitCount: Int,
        providerMessageId: ProviderMessageId?,
        nowMillis: Long,
        terminalExpiresAtMillis: Long,
    ): ReplyOperationProviderStatusResult = synchronized(PREFERENCES_LOCK) {
        if (
            operationId <= 0L ||
            !providerMessageId.isValidSmsProviderId() ||
            unitCount !in 1..MAXIMUM_UNIT_COUNT ||
            unitIndex !in 0 until unitCount ||
            terminalExpiresAtMillis <= nowMillis
        ) {
            return@synchronized ReplyOperationProviderStatusResult.Invalid
        }
        when (val current = read(operationId, nowMillis)) {
            CurrentOperation.Missing -> ReplyOperationProviderStatusResult.Untracked
            CurrentOperation.CorruptOwnership ->
                ReplyOperationProviderStatusResult.CorruptOwnership
            CurrentOperation.PersistenceFailure ->
                ReplyOperationProviderStatusResult.PersistenceFailure
            is CurrentOperation.Found -> {
                if (current.operation.state == ReplyOperationState.RESERVED) {
                    return@synchronized ReplyOperationProviderStatusResult.PhaseMismatch
                }
                val bound = current.operation.bindProvider(providerMessageId)
                    ?: return@synchronized ReplyOperationProviderStatusResult.ProviderMismatch
                val counted = bound.bindUnitCountWithoutProgress(unitCount)
                    ?: return@synchronized ReplyOperationProviderStatusResult.UnitCountMismatch
                val statusUpdated = counted.advanceCallbackOwnedPhase().copy(
                    expiresAtMillis = maxOf(
                        counted.expiresAtMillis,
                        terminalExpiresAtMillis,
                    ),
                ).requestProviderStatus(SmsProviderStatus.DELIVERY_FAILED)
                val statusChanged = statusUpdated.desiredProviderStatus !=
                    current.operation.desiredProviderStatus
                val (updated, result) = statusUpdated.recordDeliverySentEvidence(
                    unitIndex = unitIndex,
                    terminalExpiresAtMillis = terminalExpiresAtMillis,
                    nonTerminalResult = if (statusChanged) {
                        ReplyOperationProviderStatusResult.Recorded
                    } else {
                        ReplyOperationProviderStatusResult.Unchanged
                    },
                )
                if (updated != current.operation && !put(updated)) {
                    ReplyOperationProviderStatusResult.PersistenceFailure
                } else {
                    result
                }
            }
        }
    }

    override fun recordDeliverySuccess(
        operationId: Long,
        unitIndex: Int,
        unitCount: Int,
        providerMessageId: ProviderMessageId?,
        nowMillis: Long,
        terminalExpiresAtMillis: Long,
    ): ReplyOperationProviderStatusResult = synchronized(PREFERENCES_LOCK) {
        if (
            operationId <= 0L ||
            !providerMessageId.isValidSmsProviderId() ||
            unitCount !in 1..MAXIMUM_UNIT_COUNT ||
            unitIndex !in 0 until unitCount ||
            terminalExpiresAtMillis <= nowMillis
        ) {
            return@synchronized ReplyOperationProviderStatusResult.Invalid
        }
        when (val current = read(operationId, nowMillis)) {
            CurrentOperation.Missing -> ReplyOperationProviderStatusResult.Untracked
            CurrentOperation.CorruptOwnership ->
                ReplyOperationProviderStatusResult.CorruptOwnership
            CurrentOperation.PersistenceFailure ->
                ReplyOperationProviderStatusResult.PersistenceFailure
            is CurrentOperation.Found -> {
                if (current.operation.state == ReplyOperationState.RESERVED) {
                    return@synchronized ReplyOperationProviderStatusResult.PhaseMismatch
                }
                val bound = current.operation.bindProvider(providerMessageId)
                    ?: return@synchronized ReplyOperationProviderStatusResult.ProviderMismatch
                val counted = bound.bindUnitCountWithoutProgress(unitCount)
                    ?: return@synchronized ReplyOperationProviderStatusResult.UnitCountMismatch
                val advanced = counted.advanceCallbackOwnedPhase().copy(
                    expiresAtMillis = maxOf(
                        counted.expiresAtMillis,
                        terminalExpiresAtMillis,
                    ),
                )
                val (updated, result) = advanced.recordDeliverySentEvidence(
                    unitIndex = unitIndex,
                    terminalExpiresAtMillis = terminalExpiresAtMillis,
                    nonTerminalResult = ReplyOperationProviderStatusResult.Tracked,
                )
                if (updated == current.operation || put(updated)) {
                    result
                } else {
                    ReplyOperationProviderStatusResult.PersistenceFailure
                }
            }
        }
    }

    override fun pendingProviderUpdates(
        nowMillis: Long,
    ): ReplyOperationStorePendingProviderUpdatesResult = synchronized(PREFERENCES_LOCK) {
        when (val snapshot = cleanedSnapshot(nowMillis)) {
            is CleanedSnapshot.Available ->
                ReplyOperationStorePendingProviderUpdatesResult.Available(
                    snapshot.operations
                        .sortedBy(StoredOperation::operationId)
                        .mapNotNull(StoredOperation::pendingProviderUpdateOrNull),
                )
            CleanedSnapshot.PersistenceFailure ->
                ReplyOperationStorePendingProviderUpdatesResult.PersistenceFailure
        }
    }

    override fun pendingProviderUpdate(
        operationId: Long,
        nowMillis: Long,
    ): ReplyOperationStorePendingProviderUpdateResult = synchronized(PREFERENCES_LOCK) {
        if (operationId <= 0L) {
            return@synchronized ReplyOperationStorePendingProviderUpdateResult.Available(null)
        }
        when (val current = read(operationId, nowMillis)) {
            CurrentOperation.Missing ->
                ReplyOperationStorePendingProviderUpdateResult.Available(null)
            CurrentOperation.CorruptOwnership ->
                ReplyOperationStorePendingProviderUpdateResult.CorruptOwnership
            CurrentOperation.PersistenceFailure ->
                ReplyOperationStorePendingProviderUpdateResult.PersistenceFailure
            is CurrentOperation.Found ->
                ReplyOperationStorePendingProviderUpdateResult.Available(
                    current.operation.pendingProviderUpdateOrNull(),
                )
        }
    }

    override fun acknowledgeProviderUpdate(
        operationId: Long,
        providerMessageId: ProviderMessageId,
        status: SmsProviderStatus,
        nowMillis: Long,
    ): ReplyOperationProviderAcknowledgementResult = synchronized(PREFERENCES_LOCK) {
        if (
            operationId <= 0L ||
            providerMessageId.kind != ProviderKind.SMS ||
            status !in PROVIDER_TERMINAL_STATUSES
        ) {
            return@synchronized ReplyOperationProviderAcknowledgementResult.Invalid
        }
        when (val current = read(operationId, nowMillis)) {
            CurrentOperation.Missing -> ReplyOperationProviderAcknowledgementResult.Untracked
            CurrentOperation.CorruptOwnership ->
                ReplyOperationProviderAcknowledgementResult.CorruptOwnership
            CurrentOperation.PersistenceFailure ->
                ReplyOperationProviderAcknowledgementResult.PersistenceFailure
            is CurrentOperation.Found -> {
                val operation = current.operation
                if (
                    operation.providerMessageId != providerMessageId ||
                    operation.desiredProviderStatus != status
                ) {
                    ReplyOperationProviderAcknowledgementResult.Stale
                } else if (!operation.providerUpdatePending) {
                    ReplyOperationProviderAcknowledgementResult.AlreadyAcknowledged
                } else if (put(operation.copy(providerUpdatePending = false))) {
                    ReplyOperationProviderAcknowledgementResult.Acknowledged
                } else {
                    ReplyOperationProviderAcknowledgementResult.PersistenceFailure
                }
            }
        }
    }

    override fun remove(
        operationId: Long,
        nowMillis: Long,
    ): ReplyOperationRemovalResult = synchronized(PREFERENCES_LOCK) {
        if (operationId <= 0L) return@synchronized ReplyOperationRemovalResult.Invalid
        when (val current = read(operationId, nowMillis)) {
            CurrentOperation.Missing -> ReplyOperationRemovalResult.Untracked
            CurrentOperation.CorruptOwnership -> ReplyOperationRemovalResult.CorruptOwnership
            CurrentOperation.PersistenceFailure -> ReplyOperationRemovalResult.PersistenceFailure
            is CurrentOperation.Found -> {
                if (preferences.edit().remove(current.key).commit()) {
                    ReplyOperationRemovalResult.Removed
                } else {
                    ReplyOperationRemovalResult.PersistenceFailure
                }
            }
        }
    }

    override fun cleanupExpired(nowMillis: Long): ReplyOperationCleanupResult =
        synchronized(PREFERENCES_LOCK) {
            val removable = snapshot(nowMillis).removableKeys
            if (removeKeys(removable)) {
                ReplyOperationCleanupResult.Success(removable.size)
            } else {
                ReplyOperationCleanupResult.PersistenceFailure
            }
        }

    override fun clear(): Boolean = synchronized(PREFERENCES_LOCK) {
        preferences.edit().clear().commit()
    }

    private fun acknowledge(
        operationId: Long,
        nowMillis: Long,
        pendingState: ReplyOperationState,
        acknowledgedState: ReplyOperationState,
    ): ReplyOperationAcknowledgementResult {
        if (operationId <= 0L) return ReplyOperationAcknowledgementResult.Invalid
        return when (val current = read(operationId, nowMillis)) {
            CurrentOperation.Missing -> ReplyOperationAcknowledgementResult.Untracked
            CurrentOperation.CorruptOwnership ->
                ReplyOperationAcknowledgementResult.CorruptOwnership
            CurrentOperation.PersistenceFailure -> ReplyOperationAcknowledgementResult.PersistenceFailure
            is CurrentOperation.Found -> when (current.operation.state) {
                pendingState -> {
                    if (put(current.operation.copy(state = acknowledgedState))) {
                        ReplyOperationAcknowledgementResult.Acknowledged
                    } else {
                        ReplyOperationAcknowledgementResult.PersistenceFailure
                    }
                }
                acknowledgedState -> ReplyOperationAcknowledgementResult.AlreadyAcknowledged
                else -> ReplyOperationAcknowledgementResult.NotPending
            }
        }
    }

    private fun read(operationId: Long, nowMillis: Long): CurrentOperation {
        val preferenceKey = key(operationId)
        val storedValue = preferences.all[preferenceKey] ?: return CurrentOperation.Missing
        val decoded = (storedValue as? String)?.let { encoded ->
            decode(preferenceKey, encoded)
        }
        if (decoded == null) return CurrentOperation.CorruptOwnership
        val operation = decoded.operation
        if (decoded.requiresRewrite && !put(operation)) {
            return CurrentOperation.PersistenceFailure
        }
        if (operation.expiresAtMillis <= nowMillis) {
            return if (preferences.edit().remove(preferenceKey).commit()) {
                CurrentOperation.Missing
            } else {
                CurrentOperation.PersistenceFailure
            }
        }
        return CurrentOperation.Found(preferenceKey, operation)
    }

    private fun cleanedSnapshot(nowMillis: Long): CleanedSnapshot {
        val snapshot = snapshot(nowMillis)
        return if (removeKeys(snapshot.removableKeys)) {
            CleanedSnapshot.Available(snapshot.operations)
        } else {
            CleanedSnapshot.PersistenceFailure
        }
    }

    private fun snapshot(nowMillis: Long): StoreSnapshot {
        val operations = mutableListOf<StoredOperation>()
        val removableKeys = linkedSetOf<String>()
        val corruptOperationIds = linkedSetOf<Long>()
        preferences.all.forEach { (preferenceKey, value) ->
            if (!preferenceKey.startsWith(KEY_PREFIX)) {
                removableKeys += preferenceKey
                return@forEach
            }
            val operationId = canonicalOperationId(preferenceKey)
            if (operationId == null) {
                removableKeys += preferenceKey
                return@forEach
            }
            val decoded = (value as? String)?.let { encoded ->
                decode(preferenceKey, encoded)
            }
            if (decoded == null) {
                corruptOperationIds += operationId
            } else if (decoded.operation.expiresAtMillis <= nowMillis) {
                removableKeys += preferenceKey
            } else if (decoded.requiresRewrite && !put(decoded.operation)) {
                corruptOperationIds += operationId
            } else {
                operations += decoded.operation
            }
        }
        return StoreSnapshot(operations, removableKeys, corruptOperationIds)
    }

    private fun canonicalOperationId(preferenceKey: String): Long? {
        val suffix = preferenceKey.removePrefix(KEY_PREFIX)
        return suffix.toLongOrNull()?.takeIf { value ->
            value > 0L && suffix == value.toString()
        }
    }

    private fun put(operation: StoredOperation): Boolean {
        val encoded = encode(operation) ?: return false
        return preferences.edit().putString(key(operation.operationId), encoded).commit()
    }

    private fun removeKeys(keys: Set<String>): Boolean {
        if (keys.isEmpty()) return true
        val editor = preferences.edit()
        keys.forEach(editor::remove)
        return editor.commit()
    }

    /**
     * Source-less v1/v2 records remain in the source-less v2 envelope when changed. New and
     * migrated source-bearing records use the authenticated-shape v4 envelope exclusively.
     */
    private fun encode(operation: StoredOperation): String? =
        if (operation.sourceMessageId == null) {
            listOf(
                VERSION_TWO,
                operation.conversationId.value,
                operation.createdAtMillis,
                operation.expiresAtMillis,
                operation.state.encoded,
                operation.expectedUnitCount,
                encodeSentUnits(operation.sentUnits),
            ).joinToString(SEPARATOR)
        } else if (!hasValidVersionFourShape(operation)) {
            null
        } else {
            val payloadFields = versionFourPayloadFields(operation)
            val checksum = checksum(operation.operationId, payloadFields)
            (payloadFields + checksum).joinToString(SEPARATOR)
        }

    private fun decode(preferenceKey: String, encoded: String): DecodedOperation? {
        val suffix = preferenceKey.removePrefix(KEY_PREFIX)
        val operationId = suffix.toLongOrNull()?.takeIf { value ->
            value > 0L && suffix == value.toString()
        } ?: return null
        val fields = encoded.split(SEPARATOR)
        val version = when {
            fields.size == VERSION_ONE_FIELD_COUNT && fields[0] == VERSION_ONE -> 1
            fields.size == VERSION_TWO_FIELD_COUNT && fields[0] == VERSION_TWO -> 2
            fields.size == VERSION_THREE_FIELD_COUNT && fields[0] == VERSION_THREE -> 3
            fields.size == FIELD_COUNT && fields[0] == FORMAT_VERSION -> 4
            else -> return null
        }
        if (version == 4) {
            val payloadFields = fields.subList(0, CHECKSUM_INDEX)
            val expectedChecksum = checksum(operationId, payloadFields)
            val storedChecksum = fields[CHECKSUM_INDEX]
            if (
                storedChecksum.length != SHA_256_HEX_LENGTH ||
                storedChecksum.any { character -> character !in HEX } ||
                !MessageDigest.isEqual(
                    expectedChecksum.toByteArray(StandardCharsets.US_ASCII),
                    storedChecksum.toByteArray(StandardCharsets.US_ASCII),
                )
            ) {
                return null
            }
        }
        val conversationId = fields[1].toLongOrNull()
            ?.takeIf { it > 0L }
            ?.let(::ConversationId)
            ?: return null
        val createdAtMillis = fields[2].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val expiresAtMillis = fields[3].toLongOrNull()
            ?.takeIf { it > createdAtMillis }
            ?: return null
        val state = if (version == 1) {
            ReplyOperationState.SUBMISSION_UNKNOWN_PENDING
        } else {
            decodeState(fields[4]) ?: return null
        }
        val unitCountIndex = if (version == 1) 4 else 5
        val sentUnitsIndex = if (version == 1) 5 else 6
        val expectedUnitCount = fields[unitCountIndex].toIntOrNull()
            ?.takeIf { it in UNKNOWN_UNIT_COUNT..MAXIMUM_UNIT_COUNT }
            ?: return null
        val sentUnits = decodeSentUnits(fields[sentUnitsIndex], expectedUnitCount) ?: return null
        val sourceMessageId = if (version >= 3) {
            decodeSourceMessageId(fields[7], fields[8]) ?: return null
        } else {
            OptionalSourceMessageId.Missing
        }.value
        val providerMessageId = if (version >= 3) {
            val decodedProvider = decodeOptionalPositiveLong(fields[9]) ?: return null
            decodedProvider.value?.let { ProviderMessageId(ProviderKind.SMS, it) }
        } else {
            null
        }
        val desiredProviderStatus = if (version >= 3) {
            decodeOptionalProviderStatus(fields[10]) ?: return null
        } else {
            OptionalProviderStatus.Missing
        }.value
        val providerUpdatePending = if (version >= 3) {
            when (fields[11]) {
                PENDING_UPDATE -> true
                APPLIED_UPDATE -> false
                else -> return null
            }
        } else {
            false
        }
        if (
            providerUpdatePending &&
            (providerMessageId == null || desiredProviderStatus == null)
        ) {
            return null
        }
        val decoded = StoredOperation(
            operationId = operationId,
            conversationId = conversationId,
            sourceMessageId = sourceMessageId,
            createdAtMillis = createdAtMillis,
            expiresAtMillis = expiresAtMillis,
            state = state,
            expectedUnitCount = expectedUnitCount,
            sentUnits = sentUnits,
            providerMessageId = providerMessageId,
            desiredProviderStatus = desiredProviderStatus,
            providerUpdatePending = providerUpdatePending,
        )
        if (version == 4) {
            if (
                !hasValidVersionFourShape(decoded) ||
                fields.subList(0, CHECKSUM_INDEX) != versionFourPayloadFields(decoded)
            ) {
                return null
            }
            return DecodedOperation(decoded, requiresRewrite = false)
        }
        if (
            version <= 2 && !hasValidLegacyPhaseShape(
                state = state,
                expectedUnitCount = expectedUnitCount,
                providerMessageId = providerMessageId,
                desiredProviderStatus = desiredProviderStatus,
                providerUpdatePending = providerUpdatePending,
            )
        ) {
            return null
        }
        if (version <= 2) return DecodedOperation(decoded, requiresRewrite = false)

        // V3 had no integrity field. Even a syntactically perfect terminal success cannot be
        // trusted to cancel the source notification after an upgrade. Collapse it to the only
        // conservative terminal direction and discard all transport/provider evidence.
        return DecodedOperation(
            operation = decoded.copy(
                state = ReplyOperationState.FAILURE_PENDING,
                expectedUnitCount = UNKNOWN_UNIT_COUNT,
                sentUnits = BooleanArray(0),
                providerMessageId = null,
                desiredProviderStatus = null,
                providerUpdatePending = false,
            ),
            requiresRewrite = true,
        )
    }

    private fun hasValidLegacyPhaseShape(
        state: ReplyOperationState,
        expectedUnitCount: Int,
        providerMessageId: ProviderMessageId?,
        desiredProviderStatus: SmsProviderStatus?,
        providerUpdatePending: Boolean,
    ): Boolean = when (state) {
        ReplyOperationState.RESERVED,
        ReplyOperationState.CLAIMED ->
            expectedUnitCount == UNKNOWN_UNIT_COUNT &&
                providerMessageId == null &&
                desiredProviderStatus == null &&
                !providerUpdatePending
        ReplyOperationState.PREPARED,
        ReplyOperationState.SUBMITTING ->
            expectedUnitCount != UNKNOWN_UNIT_COUNT && providerMessageId != null
        ReplyOperationState.SUBMITTED -> expectedUnitCount != UNKNOWN_UNIT_COUNT
        ReplyOperationState.FAILURE_PENDING,
        ReplyOperationState.FAILURE_NOTIFIED,
        ReplyOperationState.SUBMISSION_UNKNOWN_PENDING,
        ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED,
        ReplyOperationState.SUCCESS_PENDING,
        ReplyOperationState.SUCCESS_COMPLETE -> true
    }

    private fun hasValidVersionFourShape(operation: StoredOperation): Boolean {
        val source = operation.sourceMessageId
        if (source == null || !source.kind.isTelephonyProvider) return false
        if (
            operation.expectedUnitCount == UNKNOWN_UNIT_COUNT && operation.sentUnits.isNotEmpty() ||
            operation.expectedUnitCount != UNKNOWN_UNIT_COUNT &&
            operation.sentUnits.size != operation.expectedUnitCount
        ) {
            return false
        }
        if (
            operation.providerUpdatePending &&
            (operation.providerMessageId == null || operation.desiredProviderStatus == null)
        ) {
            return false
        }
        if (operation.desiredProviderStatus == null && operation.providerUpdatePending) return false
        if (operation.providerMessageId == null && operation.providerUpdatePending) return false

        val hasCount = operation.expectedUnitCount != UNKNOWN_UNIT_COUNT
        val anySent = operation.sentUnits.any { it }
        val allSent = hasCount && operation.sentUnits.all { it }
        return when (operation.state) {
            ReplyOperationState.RESERVED,
            ReplyOperationState.CLAIMED ->
                !hasCount &&
                    operation.providerMessageId == null &&
                    operation.desiredProviderStatus == null &&
                    !operation.providerUpdatePending
            ReplyOperationState.PREPARED,
            ReplyOperationState.SUBMITTING ->
                hasCount &&
                    !anySent &&
                    operation.providerMessageId != null &&
                    operation.desiredProviderStatus == null &&
                    !operation.providerUpdatePending
            ReplyOperationState.SUBMITTED,
            ReplyOperationState.SUBMISSION_UNKNOWN_PENDING,
            ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED ->
                hasCount &&
                    !allSent &&
                    operation.providerMessageId != null &&
                    operation.desiredProviderStatus != SmsProviderStatus.COMPLETE &&
                    operation.desiredProviderStatus != SmsProviderStatus.FAILED
            ReplyOperationState.FAILURE_PENDING,
            ReplyOperationState.FAILURE_NOTIFIED -> if (!hasCount) {
                !anySent &&
                    operation.providerMessageId == null &&
                    operation.desiredProviderStatus == null &&
                    !operation.providerUpdatePending
            } else {
                operation.desiredProviderStatus == SmsProviderStatus.FAILED
            }
            ReplyOperationState.SUCCESS_PENDING,
            ReplyOperationState.SUCCESS_COMPLETE ->
                operation.providerMessageId != null && operation.hasDurableSuccessEvidence()
        }
    }

    private fun StoredOperation.hasDurableSuccessEvidence(): Boolean =
        expectedUnitCount != UNKNOWN_UNIT_COUNT &&
            sentUnits.size == expectedUnitCount &&
            sentUnits.all { it } &&
            desiredProviderStatus in SUCCESS_PROVIDER_STATUSES

    private fun versionFourPayloadFields(operation: StoredOperation): List<String> = listOf(
        FORMAT_VERSION,
        operation.conversationId.value.toString(),
        operation.createdAtMillis.toString(),
        operation.expiresAtMillis.toString(),
        operation.state.encoded,
        operation.expectedUnitCount.toString(),
        encodeSentUnits(operation.sentUnits),
        requireNotNull(operation.sourceMessageId).kind.encodedSourceKind,
        requireNotNull(operation.sourceMessageId).value.toString(),
        operation.providerMessageId?.value?.toString() ?: UNKNOWN_FIELD,
        operation.desiredProviderStatus?.encoded ?: UNKNOWN_FIELD,
        if (operation.providerUpdatePending) PENDING_UPDATE else APPLIED_UPDATE,
    )

    /** Binds the canonical preference key and every ordered payload field. */
    private fun checksum(operationId: Long, payloadFields: List<String>): String {
        val canonical = buildString {
            append(operationId)
            append(SEPARATOR)
            append(payloadFields.joinToString(SEPARATOR))
        }
        val digest = MessageDigest.getInstance(SHA_256)
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return buildString(SHA_256_HEX_LENGTH) {
            digest.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0f])
            }
        }
    }

    private fun decodeSourceMessageId(
        encodedKind: String,
        encodedValue: String,
    ): OptionalSourceMessageId? {
        if (encodedKind == UNKNOWN_FIELD && encodedValue == UNKNOWN_FIELD) {
            return OptionalSourceMessageId.Missing
        }
        val kind = when (encodedKind) {
            SOURCE_SMS -> ProviderKind.SMS
            SOURCE_MMS -> ProviderKind.MMS
            else -> return null
        }
        val value = encodedValue.toLongOrNull()?.takeIf { it > 0L } ?: return null
        return OptionalSourceMessageId.Present(MessageId(kind, value))
    }

    private fun decodeOptionalPositiveLong(encoded: String): OptionalPositiveLong? =
        if (encoded == UNKNOWN_FIELD) {
            OptionalPositiveLong.Missing
        } else {
            encoded.toLongOrNull()
                ?.takeIf { it > 0L }
                ?.let(OptionalPositiveLong::Present)
        }

    private fun decodeOptionalProviderStatus(encoded: String): OptionalProviderStatus? =
        if (encoded == UNKNOWN_FIELD) {
            OptionalProviderStatus.Missing
        } else {
            decodeProviderStatus(encoded)?.let(OptionalProviderStatus::Present)
        }

    private fun decodeState(encoded: String): ReplyOperationState? = when (encoded) {
        "r" -> ReplyOperationState.RESERVED
        "c" -> ReplyOperationState.CLAIMED
        "p" -> ReplyOperationState.PREPARED
        "i" -> ReplyOperationState.SUBMITTING
        "t" -> ReplyOperationState.SUBMITTED
        "a", "up" -> ReplyOperationState.SUBMISSION_UNKNOWN_PENDING
        "un" -> ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED
        "f", "fp" -> ReplyOperationState.FAILURE_PENDING
        "fn" -> ReplyOperationState.FAILURE_NOTIFIED
        "sp" -> ReplyOperationState.SUCCESS_PENDING
        "sc" -> ReplyOperationState.SUCCESS_COMPLETE
        else -> null
    }

    private val ReplyOperationState.encoded: String
        get() = when (this) {
            ReplyOperationState.RESERVED -> "r"
            ReplyOperationState.CLAIMED -> "c"
            ReplyOperationState.PREPARED -> "p"
            ReplyOperationState.SUBMITTING -> "i"
            ReplyOperationState.SUBMITTED -> "t"
            ReplyOperationState.FAILURE_PENDING -> "fp"
            ReplyOperationState.FAILURE_NOTIFIED -> "fn"
            ReplyOperationState.SUBMISSION_UNKNOWN_PENDING -> "up"
            ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED -> "un"
            ReplyOperationState.SUCCESS_PENDING -> "sp"
            ReplyOperationState.SUCCESS_COMPLETE -> "sc"
        }

    private val ProviderKind.encodedSourceKind: String
        get() = when (this) {
            ProviderKind.SMS -> SOURCE_SMS
            ProviderKind.MMS -> SOURCE_MMS
            ProviderKind.DRAFT,
            ProviderKind.SCHEDULED,
            ProviderKind.PENDING_OPERATION ->
                error("Only telephony provider IDs can be reply sources")
        }

    private fun decodeProviderStatus(encoded: String): SmsProviderStatus? = when (encoded) {
        STATUS_COMPLETE -> SmsProviderStatus.COMPLETE
        STATUS_DELIVERY_FAILED -> SmsProviderStatus.DELIVERY_FAILED
        STATUS_FAILED -> SmsProviderStatus.FAILED
        else -> null
    }

    private val SmsProviderStatus.encoded: String
        get() = when (this) {
            SmsProviderStatus.COMPLETE -> STATUS_COMPLETE
            SmsProviderStatus.DELIVERY_FAILED -> STATUS_DELIVERY_FAILED
            SmsProviderStatus.FAILED -> STATUS_FAILED
            SmsProviderStatus.PENDING -> error("Pending is not a terminal provider outbox status")
        }

    private fun encodeSentUnits(sentUnits: BooleanArray): String {
        if (sentUnits.isEmpty()) return UNKNOWN_SENT_UNITS
        val bytes = ByteArray((sentUnits.size + BITS_PER_BYTE - 1) / BITS_PER_BYTE)
        sentUnits.forEachIndexed { index, sent ->
            if (sent) {
                val byteIndex = index / BITS_PER_BYTE
                val bit = 1 shl (index % BITS_PER_BYTE)
                bytes[byteIndex] = (bytes[byteIndex].toInt() or bit).toByte()
            }
        }
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0f])
            }
        }
    }

    private fun decodeSentUnits(encoded: String, expectedUnitCount: Int): BooleanArray? {
        if (expectedUnitCount == UNKNOWN_UNIT_COUNT) {
            return if (encoded == UNKNOWN_SENT_UNITS) BooleanArray(0) else null
        }
        val byteCount = (expectedUnitCount + BITS_PER_BYTE - 1) / BITS_PER_BYTE
        if (encoded.length != byteCount * 2 || encoded.any { it !in HEX }) return null
        val bytes = ByteArray(byteCount)
        bytes.indices.forEach { index ->
            val high = HEX.indexOf(encoded[index * 2])
            val low = HEX.indexOf(encoded[index * 2 + 1])
            bytes[index] = ((high shl 4) or low).toByte()
        }
        val sentUnits = BooleanArray(expectedUnitCount) { index ->
            val unsigned = bytes[index / BITS_PER_BYTE].toInt() and 0xff
            unsigned and (1 shl (index % BITS_PER_BYTE)) != 0
        }
        val unusedEnd = byteCount * BITS_PER_BYTE
        if ((expectedUnitCount until unusedEnd).any { bitIndex ->
                val unsigned = bytes[bitIndex / BITS_PER_BYTE].toInt() and 0xff
                unsigned and (1 shl (bitIndex % BITS_PER_BYTE)) != 0
            }
        ) {
            return null
        }
        return sentUnits
    }

    private fun List<StoredOperation>.pending(
        state: ReplyOperationState,
    ): List<ReplyOperationStoredPending> =
        asSequence()
            .filter { operation -> operation.state == state }
            .sortedBy(StoredOperation::operationId)
            .map { operation ->
                ReplyOperationStoredPending(
                    operationId = operation.operationId,
                    conversationId = operation.conversationId,
                    sourceMessageId = operation.sourceMessageId,
                )
            }
            .toList()

    private fun List<StoredOperation>.pendingFailures(): List<ReplyOperationStoredPendingFailure> =
        mapNotNull { operation ->
            val failureKind = when (operation.state) {
                ReplyOperationState.FAILURE_PENDING -> ReplyOperationFailureKind.KNOWN_UNSENT
                ReplyOperationState.SUBMISSION_UNKNOWN_PENDING ->
                    ReplyOperationFailureKind.SUBMISSION_UNKNOWN
                else -> null
            } ?: return@mapNotNull null
            ReplyOperationStoredPendingFailure(
                operationId = operation.operationId,
                conversationId = operation.conversationId,
                sourceMessageId = operation.sourceMessageId,
                failureKind = failureKind,
            )
        }.sortedBy(ReplyOperationStoredPendingFailure::operationId)

    private fun interruptedPending(
        operation: StoredOperation,
        failureKind: ReplyOperationFailureKind,
        transitioned: Boolean,
    ) = ReplyOperationInterruptedRecoveryResult.Pending(
        operation = ReplyOperationPendingFailure(
            operationId = MessageId(ProviderKind.PENDING_OPERATION, operation.operationId),
            conversationId = operation.conversationId,
            sourceMessageId = operation.sourceMessageId,
            failureKind = failureKind,
        ),
        transitioned = transitioned,
    )

    private fun interruptedNotified(
        operation: StoredOperation,
        failureKind: ReplyOperationFailureKind,
    ) = ReplyOperationInterruptedRecoveryResult.Notified(
        operation = ReplyOperationPendingFailure(
            operationId = MessageId(ProviderKind.PENDING_OPERATION, operation.operationId),
            conversationId = operation.conversationId,
            sourceMessageId = operation.sourceMessageId,
            failureKind = failureKind,
        ),
    )

    private data class StoredOperation(
        val operationId: Long,
        val conversationId: ConversationId,
        val sourceMessageId: MessageId?,
        val createdAtMillis: Long,
        val expiresAtMillis: Long,
        val state: ReplyOperationState,
        val expectedUnitCount: Int,
        val sentUnits: BooleanArray,
        val providerMessageId: ProviderMessageId?,
        val desiredProviderStatus: SmsProviderStatus?,
        val providerUpdatePending: Boolean,
    ) {
        fun bindProvider(candidate: ProviderMessageId?): StoredOperation? {
            if (candidate == null || providerMessageId == candidate) return this
            if (providerMessageId != null) return null
            return copy(
                providerMessageId = candidate,
                providerUpdatePending = desiredProviderStatus != null,
            )
        }

        fun requestProviderStatus(requested: SmsProviderStatus): StoredOperation {
            val resolved = strongerProviderStatus(desiredProviderStatus, requested)
            if (resolved == desiredProviderStatus) return this
            return copy(
                desiredProviderStatus = resolved,
                providerUpdatePending = providerMessageId != null,
            )
        }

        fun bindUnitCountWithoutProgress(unitCount: Int): StoredOperation? = when {
            expectedUnitCount == UNKNOWN_UNIT_COUNT -> copy(
                expectedUnitCount = unitCount,
                sentUnits = BooleanArray(unitCount),
            )
            expectedUnitCount == unitCount -> this
            else -> null
        }

        fun advanceCallbackOwnedPhase(): StoredOperation =
            if (
                state == ReplyOperationState.CLAIMED ||
                state == ReplyOperationState.PREPARED ||
                state == ReplyOperationState.SUBMITTING
            ) {
                copy(state = ReplyOperationState.SUBMITTED)
            } else {
                this
            }

        fun recordDeliverySentEvidence(
            unitIndex: Int,
            terminalExpiresAtMillis: Long,
            nonTerminalResult: ReplyOperationProviderStatusResult,
        ): Pair<StoredOperation, ReplyOperationProviderStatusResult> {
            when (state) {
                ReplyOperationState.SUCCESS_PENDING ->
                    return this to ReplyOperationProviderStatusResult.SuccessPending
                ReplyOperationState.SUCCESS_COMPLETE ->
                    return this to ReplyOperationProviderStatusResult.SuccessComplete
                ReplyOperationState.RESERVED -> error("reserved delivery was rejected above")
                else -> Unit
            }
            val updatedUnits = sentUnits.copyOf().also { units -> units[unitIndex] = true }
            if (
                state == ReplyOperationState.FAILURE_PENDING ||
                state == ReplyOperationState.FAILURE_NOTIFIED ||
                !updatedUnits.all { it }
            ) {
                return copy(sentUnits = updatedUnits) to nonTerminalResult
            }
            val pending = copy(
                expiresAtMillis = maxOf(expiresAtMillis, terminalExpiresAtMillis),
                state = ReplyOperationState.SUCCESS_PENDING,
                sentUnits = updatedUnits,
            ).requestProviderStatus(SmsProviderStatus.COMPLETE)
            return pending to ReplyOperationProviderStatusResult.SuccessPending
        }

        fun pendingProviderUpdateOrNull(): ReplyOperationStoredProviderUpdate? =
            if (providerUpdatePending) {
                ReplyOperationStoredProviderUpdate(
                    operationId = operationId,
                    providerMessageId = requireNotNull(providerMessageId),
                    status = requireNotNull(desiredProviderStatus),
                )
            } else {
                null
            }
    }

    private sealed interface OptionalSourceMessageId {
        val value: MessageId?

        data object Missing : OptionalSourceMessageId {
            override val value: MessageId? = null
        }

        data class Present(override val value: MessageId) : OptionalSourceMessageId
    }

    private sealed interface OptionalPositiveLong {
        val value: Long?

        data object Missing : OptionalPositiveLong {
            override val value: Long? = null
        }

        data class Present(override val value: Long) : OptionalPositiveLong
    }

    private sealed interface OptionalProviderStatus {
        val value: SmsProviderStatus?

        data object Missing : OptionalProviderStatus {
            override val value: SmsProviderStatus? = null
        }

        data class Present(override val value: SmsProviderStatus) : OptionalProviderStatus
    }

    private data class DecodedOperation(
        val operation: StoredOperation,
        val requiresRewrite: Boolean,
    )

    private data class StoreSnapshot(
        val operations: List<StoredOperation>,
        val removableKeys: Set<String>,
        val corruptOperationIds: Set<Long>,
    )

    private sealed interface CleanedSnapshot {
        data class Available(val operations: List<StoredOperation>) : CleanedSnapshot

        data object PersistenceFailure : CleanedSnapshot
    }

    private sealed interface CurrentOperation {
        data class Found(
            val key: String,
            val operation: StoredOperation,
        ) : CurrentOperation

        data object Missing : CurrentOperation

        data object CorruptOwnership : CurrentOperation

        data object PersistenceFailure : CurrentOperation
    }

    internal companion object {
        const val PREFERENCES_NAME = "aurora_inline_reply_operations"
        private const val KEY_PREFIX = "operation."
        private const val FORMAT_VERSION = "4"
        private const val VERSION_THREE = "3"
        private const val VERSION_TWO = "2"
        private const val VERSION_ONE = "1"
        private const val SEPARATOR = "|"
        private const val FIELD_COUNT = 13
        private const val VERSION_THREE_FIELD_COUNT = 12
        private const val VERSION_TWO_FIELD_COUNT = 7
        private const val VERSION_ONE_FIELD_COUNT = 6
        private const val CHECKSUM_INDEX = 12
        private const val SHA_256 = "SHA-256"
        private const val SHA_256_HEX_LENGTH = 64
        private const val UNKNOWN_UNIT_COUNT = 0
        private const val UNKNOWN_SENT_UNITS = "-"
        private const val UNKNOWN_FIELD = "-"
        private const val SOURCE_SMS = "s"
        private const val SOURCE_MMS = "m"
        private const val STATUS_COMPLETE = "c"
        private const val STATUS_DELIVERY_FAILED = "d"
        private const val STATUS_FAILED = "f"
        private const val PENDING_UPDATE = "1"
        private const val APPLIED_UPDATE = "0"
        private const val MAXIMUM_UNIT_COUNT = 255
        private const val DEFAULT_MAXIMUM_ENTRIES = 4_096
        private const val ABSOLUTE_MAXIMUM_ENTRIES = 16_384
        private const val BITS_PER_BYTE = 8
        private const val HEX = "0123456789abcdef"
        private val SUCCESS_PROVIDER_STATUSES = setOf(
            SmsProviderStatus.COMPLETE,
            SmsProviderStatus.DELIVERY_FAILED,
        )
        private val PREFERENCES_LOCK = Any()

        private fun key(operationId: Long): String = KEY_PREFIX + operationId
    }
}
