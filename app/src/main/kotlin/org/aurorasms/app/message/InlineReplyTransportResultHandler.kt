// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import java.util.concurrent.CancellationException
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.notifications.InlineReplyFailureKey
import org.aurorasms.core.notifications.MessageNotifier
import org.aurorasms.core.notifications.NotificationCancelResult
import org.aurorasms.core.notifications.NotificationPostResult

/** Routes only transport callbacks owned by a durable notification reply. */
internal class InlineReplyTransportResultHandler(
    private val replyOperations: ReplyOperationRegistry,
    private val messageNotifier: MessageNotifier,
    private val userVisibleEffectsAllowed: () -> Boolean = { true },
) {
    fun handle(result: TransportResult): InlineReplyTransportDisposition {
        if (result.transport != MessageTransportKind.SMS) {
            return InlineReplyTransportDisposition.Untracked
        }
        return when (result) {
            is TransportResult.Sent -> handleSent(result)
            is TransportResult.Delivered -> handleDeliverySuccess(result)
            is TransportResult.Failed -> handleFailure(result)
            is TransportResult.Rejected -> handleOwnedFailure(result.operationId)
            is TransportResult.Downloaded,
            is TransportResult.Submitted -> InlineReplyTransportDisposition.Untracked
        }
    }

    /** Replays side effects that may have been interrupted by process death. */
    fun reconcilePendingOperations() {
        if (!effectsAllowed()) return
        // Pre-operation-key alpha alerts identify only a conversation, so they
        // cannot be mapped safely when that conversation owns multiple durable
        // failures. Remove only still-active legacy tag/ID tuples; never reopen
        // acknowledged operations or guess an operation identity during upgrade.
        val legacyCleanup = runtimeResultOrNull {
            messageNotifier.cancelLegacyInlineReplyFailures()
        } ?: NotificationCancelResult.RetryableFailure
        if (legacyCleanup == NotificationCancelResult.RetryableFailure) return
        when (val successes = replyOperations.pendingSuccesses()) {
            is ReplyOperationPendingSuccessesResult.Available -> {
                successes.operations.forEach { operation ->
                    cancelPendingSuccess(
                        operationId = operation.operationId,
                        conversationId = operation.conversationId,
                        sourceMessageId = operation.sourceMessageId,
                    )
                }
            }
            ReplyOperationPendingSuccessesResult.PersistenceFailure -> Unit
        }
        when (val failures = replyOperations.pendingFailures()) {
            is ReplyOperationPendingFailuresResult.Available -> {
                failures.operations.forEach { operation ->
                    postPendingFailure(operation.operationId, operation.conversationId)
                }
            }
            ReplyOperationPendingFailuresResult.PersistenceFailure -> Unit
        }
    }

    fun handleOwnedFailure(
        operationId: MessageId,
        fallbackConversationId: ConversationId? = null,
        providerMessageId: ProviderMessageId? = null,
        unitIndex: Int = 0,
        unitCount: Int = 1,
        operationOrigin: TransportResult.OperationOrigin = TransportResult.OperationOrigin.UNMARKED,
    ): InlineReplyTransportDisposition =
        when (
            val result = replyOperations.markFailurePending(
                operationId = operationId,
                providerMessageId = providerMessageId,
                unitIndex = unitIndex,
                unitCount = unitCount,
            )
        ) {
            is ReplyOperationFailureResult.Pending ->
                postPendingFailure(operationId, result.conversationId)
            is ReplyOperationFailureResult.Notified ->
                InlineReplyTransportDisposition.TrackedFailure
            is ReplyOperationFailureResult.SuccessTerminal ->
                InlineReplyTransportDisposition.TrackedTerminal
            ReplyOperationFailureResult.PersistenceFailure -> {
                fallbackConversationId?.let { conversationId ->
                    postUnacknowledgedFailure(operationId, conversationId)
                }
                InlineReplyTransportDisposition.TrackedUnresolved
            }
            ReplyOperationFailureResult.ProviderMismatch,
            ReplyOperationFailureResult.UnitCountMismatch,
            ReplyOperationFailureResult.PhaseMismatch,
            ReplyOperationFailureResult.CorruptOwnership -> {
                fallbackConversationId?.let { conversationId ->
                    postUnacknowledgedFailure(operationId, conversationId)
                }
                InlineReplyTransportDisposition.TrackedUnresolved
            }
            ReplyOperationFailureResult.Invalid,
            ReplyOperationFailureResult.Untracked -> {
                if (
                    fallbackConversationId != null ||
                    operationOrigin == TransportResult.OperationOrigin.INLINE_REPLY
                ) {
                    fallbackConversationId?.let { conversationId ->
                        postUnacknowledgedFailure(operationId, conversationId)
                    }
                    InlineReplyTransportDisposition.TrackedUnresolved
                } else {
                    InlineReplyTransportDisposition.Untracked
                }
            }
        }

    fun handleOwnedSuccess(operationId: MessageId): InlineReplyTransportDisposition =
        when (val result = replyOperations.markSuccessPending(operationId)) {
            is ReplyOperationSuccessResult.Pending ->
                cancelPendingSuccess(
                    operationId = operationId,
                    conversationId = result.conversationId,
                    sourceMessageId = result.sourceMessageId,
                )
            is ReplyOperationSuccessResult.Complete ->
                InlineReplyTransportDisposition.TrackedTerminal
            is ReplyOperationSuccessResult.FailurePending ->
                postPendingFailure(operationId, result.conversationId)
            is ReplyOperationSuccessResult.FailureNotified ->
                InlineReplyTransportDisposition.TrackedFailure
            is ReplyOperationSuccessResult.SubmissionUnknownPending ->
                postPendingFailure(operationId, result.conversationId)
            is ReplyOperationSuccessResult.SubmissionUnknownNotified ->
                InlineReplyTransportDisposition.TrackedFailure
            ReplyOperationSuccessResult.PersistenceFailure ->
                InlineReplyTransportDisposition.TrackedUnresolved
            ReplyOperationSuccessResult.PhaseMismatch,
            ReplyOperationSuccessResult.CorruptOwnership ->
                InlineReplyTransportDisposition.TrackedUnresolved
            ReplyOperationSuccessResult.Invalid,
            ReplyOperationSuccessResult.Untracked ->
                InlineReplyTransportDisposition.TrackedUnresolved
        }

    /** Resolves a caller interruption without guessing whether platform submission occurred. */
    fun recoverInterruptedOperation(
        operationId: MessageId,
        fallbackConversationId: ConversationId? = null,
        operationOrigin: TransportResult.OperationOrigin = TransportResult.OperationOrigin.UNMARKED,
    ): InlineReplyTransportDisposition =
        when (val result = replyOperations.recoverInterruptedOperation(operationId)) {
            is ReplyOperationInterruptedRecoveryResult.Pending ->
                postPendingFailure(
                    operationId = result.operation.operationId,
                    conversationId = result.operation.conversationId,
                )
            is ReplyOperationInterruptedRecoveryResult.Notified ->
                InlineReplyTransportDisposition.TrackedFailure
            is ReplyOperationInterruptedRecoveryResult.SuccessTerminal ->
                InlineReplyTransportDisposition.TrackedTerminal
            ReplyOperationInterruptedRecoveryResult.CorruptOwnership,
            ReplyOperationInterruptedRecoveryResult.PersistenceFailure -> {
                fallbackConversationId?.let { conversationId ->
                    postUnacknowledgedFailure(operationId, conversationId)
                }
                InlineReplyTransportDisposition.TrackedUnresolved
            }
            ReplyOperationInterruptedRecoveryResult.Invalid,
            ReplyOperationInterruptedRecoveryResult.Untracked -> {
                if (
                    fallbackConversationId != null ||
                    operationOrigin == TransportResult.OperationOrigin.INLINE_REPLY
                ) {
                    fallbackConversationId?.let { conversationId ->
                        postUnacknowledgedFailure(operationId, conversationId)
                    }
                    InlineReplyTransportDisposition.TrackedUnresolved
                } else {
                    InlineReplyTransportDisposition.Untracked
                }
            }
        }

    private fun handleSent(result: TransportResult.Sent): InlineReplyTransportDisposition =
        when (
            val sent = replyOperations.recordSent(
                operationId = result.operationId,
                unitIndex = result.unitIndex,
                unitCount = result.unitCount,
                providerMessageId = result.providerMessageId,
            )
        ) {
            is ReplyOperationSentResult.Pending ->
                InlineReplyTransportDisposition.TrackedPending
            is ReplyOperationSentResult.SuccessPending ->
                cancelPendingSuccess(
                    operationId = result.operationId,
                    conversationId = sent.conversationId,
                    sourceMessageId = sent.sourceMessageId,
                )
            is ReplyOperationSentResult.SuccessComplete ->
                InlineReplyTransportDisposition.TrackedTerminal
            is ReplyOperationSentResult.FailurePending ->
                postPendingFailure(result.operationId, sent.conversationId)
            is ReplyOperationSentResult.FailureNotified ->
                InlineReplyTransportDisposition.TrackedFailure
            ReplyOperationSentResult.PersistenceFailure,
            ReplyOperationSentResult.ProviderMismatch,
            ReplyOperationSentResult.UnitCountMismatch,
            ReplyOperationSentResult.PhaseMismatch,
            ReplyOperationSentResult.CorruptOwnership ->
                InlineReplyTransportDisposition.TrackedUnresolved
            ReplyOperationSentResult.Invalid,
            ReplyOperationSentResult.Untracked ->
                result.operationOrigin.untrackedDisposition()
        }

    private fun handleFailure(result: TransportResult.Failed): InlineReplyTransportDisposition =
        when (result.stage) {
            TransportResult.FailureStage.SUBMISSION_UNKNOWN ->
                recoverInterruptedOperation(
                    operationId = result.operationId,
                    operationOrigin = result.operationOrigin,
                )
            TransportResult.FailureStage.SUBMISSION,
            TransportResult.FailureStage.SENT_CALLBACK ->
                handleOwnedFailure(
                    operationId = result.operationId,
                    providerMessageId = result.providerMessageId,
                    unitIndex = result.unitIndex,
                    unitCount = result.unitCount,
                    operationOrigin = result.operationOrigin,
                )
            TransportResult.FailureStage.DELIVERY_CALLBACK ->
                handleDeliveryFailure(result)
            TransportResult.FailureStage.DOWNLOAD_CALLBACK ->
                result.operationOrigin.untrackedDisposition()
        }

    private fun handleDeliverySuccess(
        result: TransportResult.Delivered,
    ): InlineReplyTransportDisposition = replyOperations.recordDeliverySuccess(
        operationId = result.operationId,
        unitIndex = result.unitIndex,
        unitCount = result.unitCount,
        providerMessageId = result.providerMessageId,
    ).toDeliveryDisposition(result.operationId, result.operationOrigin)

    private fun handleDeliveryFailure(
        result: TransportResult.Failed,
    ): InlineReplyTransportDisposition = replyOperations.recordDeliveryFailure(
        operationId = result.operationId,
        unitIndex = result.unitIndex,
        unitCount = result.unitCount,
        providerMessageId = result.providerMessageId,
    ).toDeliveryDisposition(result.operationId, result.operationOrigin)

    private fun ReplyOperationProviderStatusResult.toDeliveryDisposition(
        operationId: MessageId,
        operationOrigin: TransportResult.OperationOrigin,
    ):
        InlineReplyTransportDisposition = when (this) {
        ReplyOperationProviderStatusResult.SuccessPending ->
            handleOwnedSuccess(operationId)
        ReplyOperationProviderStatusResult.SuccessComplete ->
            InlineReplyTransportDisposition.TrackedTerminal
        ReplyOperationProviderStatusResult.Tracked,
        ReplyOperationProviderStatusResult.Recorded,
        ReplyOperationProviderStatusResult.Unchanged ->
            InlineReplyTransportDisposition.TrackedPending
        ReplyOperationProviderStatusResult.ProviderMismatch,
        ReplyOperationProviderStatusResult.UnitCountMismatch,
        ReplyOperationProviderStatusResult.PhaseMismatch,
        ReplyOperationProviderStatusResult.CorruptOwnership,
        ReplyOperationProviderStatusResult.PersistenceFailure ->
            InlineReplyTransportDisposition.TrackedUnresolved
        ReplyOperationProviderStatusResult.Invalid,
        ReplyOperationProviderStatusResult.Untracked ->
            operationOrigin.untrackedDisposition()
    }

    private fun cancelPendingSuccess(
        operationId: MessageId,
        conversationId: ConversationId,
        sourceMessageId: MessageId?,
    ): InlineReplyTransportDisposition {
        if (!effectsAllowed()) {
            return InlineReplyTransportDisposition.TrackedUnresolved
        }
        val failureCancellation = runtimeResultOrNull {
            messageNotifier.cancelInlineReplyFailure(
                InlineReplyFailureKey(conversationId, operationId),
            )
        } ?: NotificationCancelResult.RetryableFailure
        val cancellation = sourceMessageId?.let { expectedMessageId ->
            runtimeResultOrNull {
                messageNotifier.cancelIncomingConversation(conversationId, expectedMessageId)
            }
        } ?: NotificationCancelResult.RetryableFailure
        if (
            cancellation == NotificationCancelResult.RetryableFailure ||
            failureCancellation == NotificationCancelResult.RetryableFailure
        ) {
            return InlineReplyTransportDisposition.TrackedUnresolved
        }
        return when (replyOperations.acknowledgeSuccessCancellation(operationId)) {
            ReplyOperationAcknowledgementResult.Acknowledged,
            ReplyOperationAcknowledgementResult.AlreadyAcknowledged,
            ReplyOperationAcknowledgementResult.Untracked ->
                InlineReplyTransportDisposition.TrackedComplete
            ReplyOperationAcknowledgementResult.Invalid,
            ReplyOperationAcknowledgementResult.NotPending,
            ReplyOperationAcknowledgementResult.CorruptOwnership,
            ReplyOperationAcknowledgementResult.PersistenceFailure ->
                InlineReplyTransportDisposition.TrackedUnresolved
        }
    }

    private fun postPendingFailure(
        operationId: MessageId,
        conversationId: ConversationId,
    ): InlineReplyTransportDisposition {
        if (!effectsAllowed()) return InlineReplyTransportDisposition.TrackedFailure
        val posted = runtimeResultOrNull {
            messageNotifier.notifyInlineReplyFailure(
                InlineReplyFailureKey(conversationId, operationId),
            )
        }
        if (posted !is NotificationPostResult.Posted) {
            return InlineReplyTransportDisposition.TrackedFailure
        }
        return when (replyOperations.acknowledgeFailureNotification(operationId)) {
            ReplyOperationAcknowledgementResult.Acknowledged,
            ReplyOperationAcknowledgementResult.AlreadyAcknowledged,
            ReplyOperationAcknowledgementResult.Untracked ->
                InlineReplyTransportDisposition.TrackedFailure
            ReplyOperationAcknowledgementResult.Invalid,
            ReplyOperationAcknowledgementResult.NotPending,
            ReplyOperationAcknowledgementResult.CorruptOwnership,
            ReplyOperationAcknowledgementResult.PersistenceFailure ->
                InlineReplyTransportDisposition.TrackedUnresolved
        }
    }

    private fun effectsAllowed(): Boolean =
        runtimeResultOrNull(userVisibleEffectsAllowed) ?: false

    private fun postUnacknowledgedFailure(
        operationId: MessageId,
        conversationId: ConversationId,
    ) {
        if (!effectsAllowed()) return
        runtimeResultOrNull {
            messageNotifier.notifyInlineReplyFailure(
                InlineReplyFailureKey(conversationId, operationId),
            )
        }
    }

    private fun TransportResult.OperationOrigin.untrackedDisposition():
        InlineReplyTransportDisposition =
        if (this == TransportResult.OperationOrigin.INLINE_REPLY) {
            InlineReplyTransportDisposition.TrackedUnresolved
        } else {
            InlineReplyTransportDisposition.Untracked
        }
}

private inline fun <T> runtimeResultOrNull(block: () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: RuntimeException) {
    null
}

internal enum class InlineReplyTransportDisposition {
    Untracked,
    TrackedPending,
    TrackedComplete,
    TrackedTerminal,
    TrackedFailure,
    TrackedUnresolved,
}
