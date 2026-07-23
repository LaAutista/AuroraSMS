// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import java.security.SecureRandom
import org.aurorasms.core.model.INLINE_REPLY_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.telephony.SmsProviderStatus

/**
 * Allocates and durably tracks notification-reply transport operations.
 *
 * Reservations are persisted before transport starts. Terminal operations stay
 * as bounded tombstones until expiry so late or duplicate platform callbacks
 * can never escape into the ordinary SMS tracker. User-visible side effects use
 * pending/acknowledged states, making both notification posting and source-SBN
 * cancellation safe to retry after process death.
 */
internal class ReplyOperationRegistry(
    private val store: ReplyOperationStore,
    private val retentionMillis: Long = DEFAULT_RETENTION_MILLIS,
    private val maximumIdentifierAttempts: Int = DEFAULT_IDENTIFIER_ATTEMPTS,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val identifierGenerator: ReplyOperationIdentifierGenerator =
        SecureRandomReplyOperationIdentifierGenerator(),
) {
    @Volatile
    private var inheritedRecoveryResult: ReplyOperationRecoveryResult
    private val inheritedRecoveryLock = Any()

    init {
        require(retentionMillis > 0L) { "retentionMillis must be positive" }
        require(maximumIdentifierAttempts in 1..MAXIMUM_IDENTIFIER_ATTEMPTS) {
            "maximumIdentifierAttempts is out of bounds"
        }
        inheritedRecoveryResult = runInheritedRecovery()
    }

    fun reserve(
        conversationId: ConversationId,
        sourceMessageId: MessageId,
    ): ReplyOperationReservationResult {
        if (!sourceMessageId.kind.isTelephonyProvider) {
            return ReplyOperationReservationResult.InvalidSource
        }
        // Never admit current-process work while the inherited-operation scan is
        // unresolved. Otherwise a later retry could mistake a live reservation for
        // interrupted work and terminalize it underneath an active submission.
        if (recoverInheritedOperations() !is ReplyOperationRecoveryResult.Recovered) {
            return ReplyOperationReservationResult.PersistenceFailure
        }
        val nowMillis = nowMillis()
            ?: return ReplyOperationReservationResult.PersistenceFailure
        val expiresAtMillis = saturatingAdd(nowMillis, retentionMillis)
        repeat(maximumIdentifierAttempts) {
            val candidate = runCatching { identifierGenerator.nextPositiveLong() }
                .getOrNull()
                ?.takeIf { it > 0L }
                ?.let(::inlineReplyOperationId)
                ?: return@repeat
            val record = ReplyOperationRecord(
                operationId = candidate,
                conversationId = conversationId,
                sourceMessageId = sourceMessageId,
                createdAtMillis = nowMillis,
                expiresAtMillis = expiresAtMillis,
            )
            when (store.reserve(record, nowMillis)) {
                ReplyOperationStoreReservationResult.Reserved -> {
                    return ReplyOperationReservationResult.Reserved(
                        MessageId(ProviderKind.PENDING_OPERATION, candidate),
                    )
                }
                ReplyOperationStoreReservationResult.Collision -> Unit
                ReplyOperationStoreReservationResult.Full ->
                    return ReplyOperationReservationResult.Full
                ReplyOperationStoreReservationResult.PersistenceFailure ->
                    return ReplyOperationReservationResult.PersistenceFailure
            }
        }
        return ReplyOperationReservationResult.IdentifierCollision
    }

    fun recordSubmitted(
        operationId: MessageId,
        unitCount: Int,
        providerMessageId: ProviderMessageId? = null,
    ): ReplyOperationSubmittedResult {
        if (
            !operationId.isPendingOperation() ||
            unitCount !in 1..MAXIMUM_UNIT_COUNT ||
            !providerMessageId.isValidSmsProviderId()
        ) {
            return ReplyOperationSubmittedResult.Invalid
        }
        return store.recordSubmitted(
            operationId = operationId.value,
            unitCount = unitCount,
            providerMessageId = providerMessageId,
            nowMillis = clockMillis().coerceAtLeast(0L),
        )
    }

    fun markClaimed(operationId: MessageId): ReplyOperationPhaseTransitionResult {
        if (!operationId.isPendingOperation()) return ReplyOperationPhaseTransitionResult.Invalid
        return store.markClaimed(
            operationId = operationId.value,
            nowMillis = clockMillis().coerceAtLeast(0L),
        )
    }

    fun recordPrepared(
        operationId: MessageId,
        providerMessageId: ProviderMessageId,
        unitCount: Int,
    ): ReplyOperationPhaseTransitionResult = recordTransportPhase(
        operationId = operationId,
        providerMessageId = providerMessageId,
        unitCount = unitCount,
        phase = ReplyOperationTransportPhase.PREPARED,
    )

    fun recordSubmitting(
        operationId: MessageId,
        providerMessageId: ProviderMessageId,
        unitCount: Int,
    ): ReplyOperationPhaseTransitionResult = recordTransportPhase(
        operationId = operationId,
        providerMessageId = providerMessageId,
        unitCount = unitCount,
        phase = ReplyOperationTransportPhase.SUBMITTING,
    )

    /** Returns cached success, but retries a transient failed recovery on an explicit trigger. */
    fun recoverInheritedOperations(): ReplyOperationRecoveryResult {
        val current = inheritedRecoveryResult
        if (current is ReplyOperationRecoveryResult.Recovered) return current
        return synchronized(inheritedRecoveryLock) {
            val synchronizedCurrent = inheritedRecoveryResult
            if (synchronizedCurrent is ReplyOperationRecoveryResult.Recovered) {
                synchronizedCurrent
            } else {
                runInheritedRecovery().also { retry ->
                    inheritedRecoveryResult = retry
                }
            }
        }
    }

    /**
     * Atomically resolves a same-process interruption at the last durable transport phase.
     *
     * This is intentionally idempotent: retrying after an uncertain caller cancellation
     * returns the already-persisted pending/notified/terminal result without weakening it.
     */
    fun recoverInterruptedOperation(
        operationId: MessageId,
    ): ReplyOperationInterruptedRecoveryResult {
        if (!operationId.isPendingOperation()) {
            return ReplyOperationInterruptedRecoveryResult.Invalid
        }
        val nowMillis = nowMillis()
            ?: return ReplyOperationInterruptedRecoveryResult.PersistenceFailure
        return store.recoverInterruptedOperation(
            operationId = operationId.value,
            nowMillis = nowMillis,
            terminalExpiresAtMillis = saturatingAdd(nowMillis, retentionMillis),
        )
    }

    fun recordSent(
        operationId: MessageId,
        unitIndex: Int,
        unitCount: Int,
        providerMessageId: ProviderMessageId? = null,
    ): ReplyOperationSentResult {
        if (
            !operationId.isPendingOperation() ||
            unitCount !in 1..MAXIMUM_UNIT_COUNT ||
            unitIndex !in 0 until unitCount ||
            !providerMessageId.isValidSmsProviderId()
        ) {
            return ReplyOperationSentResult.Invalid
        }
        val nowMillis = nowMillis() ?: return ReplyOperationSentResult.PersistenceFailure
        return store.recordSent(
            operationId = operationId.value,
            unitIndex = unitIndex,
            unitCount = unitCount,
            providerMessageId = providerMessageId,
            nowMillis = nowMillis,
            terminalExpiresAtMillis = saturatingAdd(nowMillis, retentionMillis),
        )
    }

    fun markFailurePending(
        operationId: MessageId,
        providerMessageId: ProviderMessageId? = null,
        unitIndex: Int = 0,
        unitCount: Int = 1,
    ): ReplyOperationFailureResult {
        if (
            !operationId.isPendingOperation() ||
            !providerMessageId.isValidSmsProviderId() ||
            unitCount !in 1..MAXIMUM_UNIT_COUNT ||
            unitIndex !in 0 until unitCount
        ) {
            return ReplyOperationFailureResult.Invalid
        }
        val nowMillis = nowMillis() ?: return ReplyOperationFailureResult.PersistenceFailure
        return store.markFailurePending(
            operationId = operationId.value,
            providerMessageId = providerMessageId,
            unitIndex = unitIndex,
            unitCount = unitCount,
            nowMillis = nowMillis,
            terminalExpiresAtMillis = saturatingAdd(nowMillis, retentionMillis),
        )
    }

    fun acknowledgeFailureNotification(
        operationId: MessageId,
    ): ReplyOperationAcknowledgementResult {
        if (!operationId.isPendingOperation()) return ReplyOperationAcknowledgementResult.Invalid
        return store.acknowledgeFailureNotification(
            operationId = operationId.value,
            nowMillis = clockMillis().coerceAtLeast(0L),
        )
    }

    fun markSuccessPending(operationId: MessageId): ReplyOperationSuccessResult {
        if (!operationId.isPendingOperation()) return ReplyOperationSuccessResult.Invalid
        val nowMillis = nowMillis() ?: return ReplyOperationSuccessResult.PersistenceFailure
        return store.markSuccessPending(
            operationId = operationId.value,
            nowMillis = nowMillis,
            terminalExpiresAtMillis = saturatingAdd(nowMillis, retentionMillis),
        )
    }

    fun acknowledgeSuccessCancellation(
        operationId: MessageId,
    ): ReplyOperationAcknowledgementResult {
        if (!operationId.isPendingOperation()) return ReplyOperationAcknowledgementResult.Invalid
        return store.acknowledgeSuccessCancellation(
            operationId = operationId.value,
            nowMillis = clockMillis().coerceAtLeast(0L),
        )
    }

    fun pendingFailures(): ReplyOperationPendingFailuresResult =
        when (val result = store.pendingFailures(clockMillis().coerceAtLeast(0L))) {
            is ReplyOperationStorePendingFailuresResult.Available ->
                ReplyOperationPendingFailuresResult.Available(
                    result.operations.map { stored ->
                        ReplyOperationPendingFailure(
                            operationId = MessageId(
                                ProviderKind.PENDING_OPERATION,
                                stored.operationId,
                            ),
                            conversationId = stored.conversationId,
                            sourceMessageId = stored.sourceMessageId,
                            failureKind = stored.failureKind,
                        )
                    },
                )
            ReplyOperationStorePendingFailuresResult.PersistenceFailure ->
                ReplyOperationPendingFailuresResult.PersistenceFailure
        }

    fun pendingSuccesses(): ReplyOperationPendingSuccessesResult =
        when (val result = store.pendingSuccesses(clockMillis().coerceAtLeast(0L))) {
            is ReplyOperationStorePendingSuccessesResult.Available ->
                ReplyOperationPendingSuccessesResult.Available(
                    result.operations.map(::toPendingOperation),
                )
            ReplyOperationStorePendingSuccessesResult.PersistenceFailure ->
                ReplyOperationPendingSuccessesResult.PersistenceFailure
        }

    /** Records delivery failure and treats that receipt as positive sent evidence. */
    fun recordDeliveryFailure(
        operationId: MessageId,
        unitIndex: Int,
        unitCount: Int,
        providerMessageId: ProviderMessageId? = null,
    ): ReplyOperationProviderStatusResult {
        if (
            !operationId.isPendingOperation() ||
            !providerMessageId.isValidSmsProviderId() ||
            unitCount !in 1..MAXIMUM_UNIT_COUNT ||
            unitIndex !in 0 until unitCount
        ) {
            return ReplyOperationProviderStatusResult.Invalid
        }
        val nowMillis = nowMillis() ?: return ReplyOperationProviderStatusResult.PersistenceFailure
        return store.recordDeliveryFailure(
            operationId = operationId.value,
            unitIndex = unitIndex,
            unitCount = unitCount,
            providerMessageId = providerMessageId,
            nowMillis = nowMillis,
            terminalExpiresAtMillis = saturatingAdd(nowMillis, retentionMillis),
        )
    }

    /** Binds a successful delivery receipt and treats it as positive sent evidence. */
    fun recordDeliverySuccess(
        operationId: MessageId,
        unitIndex: Int,
        unitCount: Int,
        providerMessageId: ProviderMessageId? = null,
    ): ReplyOperationProviderStatusResult {
        if (
            !operationId.isPendingOperation() ||
            !providerMessageId.isValidSmsProviderId() ||
            unitCount !in 1..MAXIMUM_UNIT_COUNT ||
            unitIndex !in 0 until unitCount
        ) {
            return ReplyOperationProviderStatusResult.Invalid
        }
        val nowMillis = nowMillis() ?: return ReplyOperationProviderStatusResult.PersistenceFailure
        return store.recordDeliverySuccess(
            operationId = operationId.value,
            unitIndex = unitIndex,
            unitCount = unitCount,
            providerMessageId = providerMessageId,
            nowMillis = nowMillis,
            terminalExpiresAtMillis = saturatingAdd(nowMillis, retentionMillis),
        )
    }

    fun pendingProviderUpdates(): ReplyOperationPendingProviderUpdatesResult =
        when (val result = store.pendingProviderUpdates(clockMillis().coerceAtLeast(0L))) {
            is ReplyOperationStorePendingProviderUpdatesResult.Available ->
                ReplyOperationPendingProviderUpdatesResult.Available(
                    result.updates.map { update ->
                        ReplyOperationProviderUpdate(
                            operationId = MessageId(
                                ProviderKind.PENDING_OPERATION,
                                update.operationId,
                            ),
                            conversationId = update.conversationId,
                            providerMessageId = update.providerMessageId,
                            status = update.status,
                        )
                    },
                )
            ReplyOperationStorePendingProviderUpdatesResult.PersistenceFailure ->
                ReplyOperationPendingProviderUpdatesResult.PersistenceFailure
        }

    fun pendingProviderUpdate(
        operationId: MessageId,
    ): ReplyOperationPendingProviderUpdateResult {
        if (!operationId.isPendingOperation()) {
            return ReplyOperationPendingProviderUpdateResult.Invalid
        }
        return when (
            val result = store.pendingProviderUpdate(
                operationId.value,
                clockMillis().coerceAtLeast(0L),
            )
        ) {
            is ReplyOperationStorePendingProviderUpdateResult.Available ->
                ReplyOperationPendingProviderUpdateResult.Available(
                    result.update?.let { update ->
                        ReplyOperationProviderUpdate(
                            operationId = operationId,
                            conversationId = update.conversationId,
                            providerMessageId = update.providerMessageId,
                            status = update.status,
                        )
                    },
                )
            ReplyOperationStorePendingProviderUpdateResult.PersistenceFailure ->
                ReplyOperationPendingProviderUpdateResult.PersistenceFailure
            ReplyOperationStorePendingProviderUpdateResult.CorruptOwnership ->
                ReplyOperationPendingProviderUpdateResult.CorruptOwnership
        }
    }

    fun acknowledgeProviderUpdate(
        update: ReplyOperationProviderUpdate,
    ): ReplyOperationProviderAcknowledgementResult {
        if (!update.operationId.isPendingOperation()) {
            return ReplyOperationProviderAcknowledgementResult.Invalid
        }
        return store.acknowledgeProviderUpdate(
            operationId = update.operationId.value,
            providerMessageId = update.providerMessageId,
            status = update.status,
            nowMillis = clockMillis().coerceAtLeast(0L),
        )
    }

    fun discard(operationId: MessageId): ReplyOperationRemovalResult {
        if (!operationId.isPendingOperation()) return ReplyOperationRemovalResult.Invalid
        return store.remove(
            operationId = operationId.value,
            nowMillis = clockMillis().coerceAtLeast(0L),
        )
    }

    fun cleanupExpired(): ReplyOperationCleanupResult =
        store.cleanupExpired(clockMillis().coerceAtLeast(0L))

    fun clear(): Boolean = store.clear()

    private fun nowMillis(): Long? =
        clockMillis().coerceAtLeast(0L).takeUnless { it == Long.MAX_VALUE }

    private fun runInheritedRecovery(): ReplyOperationRecoveryResult {
        val recoveryNowMillis = nowMillis()
            ?: return ReplyOperationRecoveryResult.PersistenceFailure
        return store.recoverInheritedOperations(
            nowMillis = recoveryNowMillis,
            terminalExpiresAtMillis = saturatingAdd(recoveryNowMillis, retentionMillis),
        )
    }

    private fun MessageId.isPendingOperation(): Boolean =
        kind == ProviderKind.PENDING_OPERATION

    private fun recordTransportPhase(
        operationId: MessageId,
        providerMessageId: ProviderMessageId,
        unitCount: Int,
        phase: ReplyOperationTransportPhase,
    ): ReplyOperationPhaseTransitionResult {
        if (
            !operationId.isPendingOperation() ||
            providerMessageId.kind != ProviderKind.SMS ||
            unitCount !in 1..MAXIMUM_UNIT_COUNT
        ) {
            return ReplyOperationPhaseTransitionResult.Invalid
        }
        return store.recordTransportPhase(
            operationId = operationId.value,
            providerMessageId = providerMessageId,
            unitCount = unitCount,
            phase = phase,
            nowMillis = clockMillis().coerceAtLeast(0L),
        )
    }

    private fun toPendingOperation(
        stored: ReplyOperationStoredPending,
    ) = ReplyOperationPending(
        operationId = MessageId(ProviderKind.PENDING_OPERATION, stored.operationId),
        conversationId = stored.conversationId,
        sourceMessageId = stored.sourceMessageId,
    )

    private companion object {
        const val DEFAULT_RETENTION_MILLIS = 24L * 60L * 60L * 1_000L
        const val DEFAULT_IDENTIFIER_ATTEMPTS = 8
        const val MAXIMUM_IDENTIFIER_ATTEMPTS = 64
        const val MAXIMUM_UNIT_COUNT = 255

        fun saturatingAdd(value: Long, increment: Long): Long =
            if (Long.MAX_VALUE - value < increment) Long.MAX_VALUE else value + increment
    }
}

internal fun MessageId.isInlineReplyOperationId(): Boolean =
    kind == ProviderKind.PENDING_OPERATION && value >= INLINE_REPLY_OPERATION_ID_BOUNDARY

private fun inlineReplyOperationId(candidate: Long): Long =
    candidate or INLINE_REPLY_OPERATION_ID_BOUNDARY

private enum class ProviderBindingResult {
    MATCHED,
    MISMATCH,
}

internal val PROVIDER_TERMINAL_STATUSES = setOf(
    SmsProviderStatus.COMPLETE,
    SmsProviderStatus.DELIVERY_FAILED,
    SmsProviderStatus.FAILED,
)

internal fun ProviderMessageId?.isValidSmsProviderId(): Boolean =
    this == null || kind == ProviderKind.SMS

internal fun strongerProviderStatus(
    current: SmsProviderStatus?,
    requested: SmsProviderStatus,
): SmsProviderStatus {
    require(requested in PROVIDER_TERMINAL_STATUSES) { "provider status must be terminal" }
    return when {
        current == SmsProviderStatus.FAILED || requested == SmsProviderStatus.FAILED ->
            SmsProviderStatus.FAILED
        current == SmsProviderStatus.DELIVERY_FAILED ||
            requested == SmsProviderStatus.DELIVERY_FAILED -> SmsProviderStatus.DELIVERY_FAILED
        else -> SmsProviderStatus.COMPLETE
    }
}

internal fun interface ReplyOperationIdentifierGenerator {
    fun nextPositiveLong(): Long
}

private class SecureRandomReplyOperationIdentifierGenerator : ReplyOperationIdentifierGenerator {
    private val secureRandom = SecureRandom()

    override fun nextPositiveLong(): Long {
        var candidate: Long
        do {
            candidate = secureRandom.nextLong() and Long.MAX_VALUE
        } while (candidate == 0L)
        return candidate
    }
}

internal data class ReplyOperationRecord(
    val operationId: Long,
    val conversationId: ConversationId,
    val sourceMessageId: MessageId,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
) {
    init {
        require(operationId > 0L) { "operationId must be positive" }
        require(sourceMessageId.kind.isTelephonyProvider) {
            "sourceMessageId must belong to the SMS or MMS provider"
        }
        require(createdAtMillis >= 0L) { "createdAtMillis cannot be negative" }
        require(expiresAtMillis > createdAtMillis) { "expiresAtMillis must follow creation" }
    }
}

internal enum class ReplyOperationState {
    RESERVED,
    CLAIMED,
    PREPARED,
    SUBMITTING,
    SUBMITTED,
    FAILURE_PENDING,
    FAILURE_NOTIFIED,
    SUBMISSION_UNKNOWN_PENDING,
    SUBMISSION_UNKNOWN_NOTIFIED,
    SUCCESS_PENDING,
    SUCCESS_COMPLETE,
}

internal enum class ReplyOperationTransportPhase {
    PREPARED,
    SUBMITTING,
}

internal interface ReplyOperationStore {
    fun reserve(
        record: ReplyOperationRecord,
        nowMillis: Long,
    ): ReplyOperationStoreReservationResult

    fun recordSubmitted(
        operationId: Long,
        unitCount: Int,
        providerMessageId: ProviderMessageId?,
        nowMillis: Long,
    ): ReplyOperationSubmittedResult

    fun markClaimed(
        operationId: Long,
        nowMillis: Long,
    ): ReplyOperationPhaseTransitionResult

    fun recordTransportPhase(
        operationId: Long,
        providerMessageId: ProviderMessageId,
        unitCount: Int,
        phase: ReplyOperationTransportPhase,
        nowMillis: Long,
    ): ReplyOperationPhaseTransitionResult

    fun recordSent(
        operationId: Long,
        unitIndex: Int,
        unitCount: Int,
        providerMessageId: ProviderMessageId?,
        nowMillis: Long,
        terminalExpiresAtMillis: Long,
    ): ReplyOperationSentResult

    fun markFailurePending(
        operationId: Long,
        providerMessageId: ProviderMessageId?,
        unitIndex: Int,
        unitCount: Int,
        nowMillis: Long,
        terminalExpiresAtMillis: Long,
    ): ReplyOperationFailureResult

    fun acknowledgeFailureNotification(
        operationId: Long,
        nowMillis: Long,
    ): ReplyOperationAcknowledgementResult

    fun markSuccessPending(
        operationId: Long,
        nowMillis: Long,
        terminalExpiresAtMillis: Long,
    ): ReplyOperationSuccessResult

    fun acknowledgeSuccessCancellation(
        operationId: Long,
        nowMillis: Long,
    ): ReplyOperationAcknowledgementResult

    fun pendingFailures(nowMillis: Long): ReplyOperationStorePendingFailuresResult

    fun pendingSuccesses(nowMillis: Long): ReplyOperationStorePendingSuccessesResult

    fun recoverInheritedOperations(
        nowMillis: Long,
        terminalExpiresAtMillis: Long,
    ): ReplyOperationRecoveryResult

    fun recoverInterruptedOperation(
        operationId: Long,
        nowMillis: Long,
        terminalExpiresAtMillis: Long,
    ): ReplyOperationInterruptedRecoveryResult

    fun recordDeliveryFailure(
        operationId: Long,
        unitIndex: Int,
        unitCount: Int,
        providerMessageId: ProviderMessageId?,
        nowMillis: Long,
        terminalExpiresAtMillis: Long,
    ): ReplyOperationProviderStatusResult

    fun recordDeliverySuccess(
        operationId: Long,
        unitIndex: Int,
        unitCount: Int,
        providerMessageId: ProviderMessageId?,
        nowMillis: Long,
        terminalExpiresAtMillis: Long,
    ): ReplyOperationProviderStatusResult

    fun pendingProviderUpdates(nowMillis: Long): ReplyOperationStorePendingProviderUpdatesResult

    fun pendingProviderUpdate(
        operationId: Long,
        nowMillis: Long,
    ): ReplyOperationStorePendingProviderUpdateResult

    fun acknowledgeProviderUpdate(
        operationId: Long,
        providerMessageId: ProviderMessageId,
        status: SmsProviderStatus,
        nowMillis: Long,
    ): ReplyOperationProviderAcknowledgementResult

    fun remove(
        operationId: Long,
        nowMillis: Long,
    ): ReplyOperationRemovalResult

    fun cleanupExpired(nowMillis: Long): ReplyOperationCleanupResult

    fun clear(): Boolean
}

/** Host-test store with the same bounded, no-eviction lifecycle as the durable store. */
internal class InMemoryReplyOperationStore(
    private val maximumEntries: Int,
) : ReplyOperationStore {
    private val operations = linkedMapOf<Long, InMemoryOperation>()

    init {
        require(maximumEntries in 1..ABSOLUTE_MAXIMUM_ENTRIES) {
            "maximumEntries is out of bounds"
        }
    }

    @Synchronized
    override fun reserve(
        record: ReplyOperationRecord,
        nowMillis: Long,
    ): ReplyOperationStoreReservationResult {
        removeExpired(nowMillis)
        if (record.operationId in operations) return ReplyOperationStoreReservationResult.Collision
        if (operations.size >= maximumEntries) return ReplyOperationStoreReservationResult.Full
        operations[record.operationId] = InMemoryOperation(record)
        return ReplyOperationStoreReservationResult.Reserved
    }

    @Synchronized
    override fun recordSubmitted(
        operationId: Long,
        unitCount: Int,
        providerMessageId: ProviderMessageId?,
        nowMillis: Long,
    ): ReplyOperationSubmittedResult {
        if (
            operationId <= 0L ||
            unitCount !in 1..MAXIMUM_UNIT_COUNT ||
            !providerMessageId.isValidSmsProviderId()
        ) {
            return ReplyOperationSubmittedResult.Invalid
        }
        val operation = active(operationId, nowMillis)
            ?: return ReplyOperationSubmittedResult.Untracked
        when (operation.state) {
            ReplyOperationState.RESERVED,
            ReplyOperationState.CLAIMED,
            ReplyOperationState.PREPARED -> return ReplyOperationSubmittedResult.PhaseMismatch
            else -> Unit
        }
        if (!operation.providerMatches(providerMessageId)) {
            return ReplyOperationSubmittedResult.ProviderMismatch
        }
        if (!operation.unitCountMatches(unitCount)) {
            return ReplyOperationSubmittedResult.UnitCountMismatch
        }
        when (operation.bindProvider(providerMessageId)) {
            ProviderBindingResult.MATCHED -> Unit
            ProviderBindingResult.MISMATCH -> return ReplyOperationSubmittedResult.ProviderMismatch
        }
        operation.bindUnitCountWithoutProgress(unitCount)
        when (operation.state) {
            ReplyOperationState.FAILURE_PENDING -> return ReplyOperationSubmittedResult.FailurePending
            ReplyOperationState.FAILURE_NOTIFIED -> return ReplyOperationSubmittedResult.FailureNotified
            ReplyOperationState.SUBMISSION_UNKNOWN_PENDING ->
                return ReplyOperationSubmittedResult.SubmissionUnknownPending
            ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED ->
                return ReplyOperationSubmittedResult.SubmissionUnknownNotified
            ReplyOperationState.SUCCESS_PENDING -> return ReplyOperationSubmittedResult.SuccessPending
            ReplyOperationState.SUCCESS_COMPLETE -> return ReplyOperationSubmittedResult.SuccessComplete
            ReplyOperationState.SUBMITTING -> {
                operation.state = ReplyOperationState.SUBMITTED
                return ReplyOperationSubmittedResult.Tracked
            }
            ReplyOperationState.SUBMITTED -> return ReplyOperationSubmittedResult.Tracked
            ReplyOperationState.RESERVED,
            ReplyOperationState.CLAIMED,
            ReplyOperationState.PREPARED -> error("pre-submission phases returned above")
        }
    }

    @Synchronized
    override fun markClaimed(
        operationId: Long,
        nowMillis: Long,
    ): ReplyOperationPhaseTransitionResult {
        if (operationId <= 0L) return ReplyOperationPhaseTransitionResult.Invalid
        val operation = active(operationId, nowMillis)
            ?: return ReplyOperationPhaseTransitionResult.Untracked
        return when (operation.state) {
            ReplyOperationState.RESERVED -> {
                operation.state = ReplyOperationState.CLAIMED
                ReplyOperationPhaseTransitionResult.Transitioned
            }
            ReplyOperationState.CLAIMED -> ReplyOperationPhaseTransitionResult.AlreadyInPhase
            ReplyOperationState.PREPARED,
            ReplyOperationState.SUBMITTING,
            ReplyOperationState.SUBMITTED -> ReplyOperationPhaseTransitionResult.AlreadyAdvanced
            ReplyOperationState.SUBMISSION_UNKNOWN_PENDING,
            ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED ->
                ReplyOperationPhaseTransitionResult.SubmissionUnknown
            ReplyOperationState.FAILURE_PENDING,
            ReplyOperationState.FAILURE_NOTIFIED,
            ReplyOperationState.SUCCESS_PENDING,
            ReplyOperationState.SUCCESS_COMPLETE -> ReplyOperationPhaseTransitionResult.Terminal
        }
    }

    @Synchronized
    override fun recordTransportPhase(
        operationId: Long,
        providerMessageId: ProviderMessageId,
        unitCount: Int,
        phase: ReplyOperationTransportPhase,
        nowMillis: Long,
    ): ReplyOperationPhaseTransitionResult {
        if (
            operationId <= 0L ||
            providerMessageId.kind != ProviderKind.SMS ||
            unitCount !in 1..MAXIMUM_UNIT_COUNT
        ) {
            return ReplyOperationPhaseTransitionResult.Invalid
        }
        val operation = active(operationId, nowMillis)
            ?: return ReplyOperationPhaseTransitionResult.Untracked
        if (!operation.providerMatches(providerMessageId)) {
            return ReplyOperationPhaseTransitionResult.ProviderMismatch
        }
        if (!operation.unitCountMatches(unitCount)) {
            return ReplyOperationPhaseTransitionResult.UnitCountMismatch
        }
        val expectedState = when (phase) {
            ReplyOperationTransportPhase.PREPARED -> ReplyOperationState.CLAIMED
            ReplyOperationTransportPhase.SUBMITTING -> ReplyOperationState.PREPARED
        }
        val targetState = when (phase) {
            ReplyOperationTransportPhase.PREPARED -> ReplyOperationState.PREPARED
            ReplyOperationTransportPhase.SUBMITTING -> ReplyOperationState.SUBMITTING
        }
        when (operation.state) {
            ReplyOperationState.RESERVED -> return ReplyOperationPhaseTransitionResult.PhaseMismatch
            ReplyOperationState.CLAIMED -> if (expectedState != ReplyOperationState.CLAIMED) {
                return ReplyOperationPhaseTransitionResult.PhaseMismatch
            }
            ReplyOperationState.PREPARED -> if (targetState == ReplyOperationState.PREPARED) {
                // Idempotent preparation still validates and binds below.
            } else if (expectedState != ReplyOperationState.PREPARED) {
                return ReplyOperationPhaseTransitionResult.PhaseMismatch
            }
            ReplyOperationState.SUBMITTING,
            ReplyOperationState.SUBMITTED -> Unit
            ReplyOperationState.SUBMISSION_UNKNOWN_PENDING,
            ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED ->
                return ReplyOperationPhaseTransitionResult.SubmissionUnknown
            ReplyOperationState.FAILURE_PENDING,
            ReplyOperationState.FAILURE_NOTIFIED,
            ReplyOperationState.SUCCESS_PENDING,
            ReplyOperationState.SUCCESS_COMPLETE ->
                return ReplyOperationPhaseTransitionResult.Terminal
        }
        operation.bindProvider(providerMessageId)
        operation.bindUnitCountWithoutProgress(unitCount)
        return when {
            operation.state == targetState -> ReplyOperationPhaseTransitionResult.AlreadyInPhase
            operation.state == expectedState -> {
                operation.state = targetState
                ReplyOperationPhaseTransitionResult.Transitioned
            }
            operation.state == ReplyOperationState.SUBMITTING ||
                operation.state == ReplyOperationState.SUBMITTED ->
                ReplyOperationPhaseTransitionResult.AlreadyAdvanced
            else -> ReplyOperationPhaseTransitionResult.PhaseMismatch
        }
    }

    @Synchronized
    override fun recordSent(
        operationId: Long,
        unitIndex: Int,
        unitCount: Int,
        providerMessageId: ProviderMessageId?,
        nowMillis: Long,
        terminalExpiresAtMillis: Long,
    ): ReplyOperationSentResult {
        if (
            operationId <= 0L ||
            unitCount !in 1..MAXIMUM_UNIT_COUNT ||
            unitIndex !in 0 until unitCount ||
            !providerMessageId.isValidSmsProviderId() ||
            terminalExpiresAtMillis <= nowMillis
        ) {
            return ReplyOperationSentResult.Invalid
        }
        val operation = active(operationId, nowMillis) ?: return ReplyOperationSentResult.Untracked
        if (operation.state == ReplyOperationState.RESERVED) {
            return ReplyOperationSentResult.PhaseMismatch
        }
        if (!operation.providerMatches(providerMessageId)) {
            return ReplyOperationSentResult.ProviderMismatch
        }
        if (!operation.unitCountMatches(unitCount)) {
            return ReplyOperationSentResult.UnitCountMismatch
        }
        when (operation.bindProvider(providerMessageId)) {
            ProviderBindingResult.MATCHED -> Unit
            ProviderBindingResult.MISMATCH -> return ReplyOperationSentResult.ProviderMismatch
        }
        when (operation.state) {
            ReplyOperationState.FAILURE_PENDING ->
                return ReplyOperationSentResult.FailurePending(operation.record.conversationId)
            ReplyOperationState.FAILURE_NOTIFIED ->
                return ReplyOperationSentResult.FailureNotified(operation.record.conversationId)
            ReplyOperationState.SUCCESS_PENDING ->
                return ReplyOperationSentResult.SuccessPending(
                    operation.record.conversationId,
                    operation.record.sourceMessageId,
                )
            ReplyOperationState.SUCCESS_COMPLETE ->
                return ReplyOperationSentResult.SuccessComplete(
                    operation.record.conversationId,
                    operation.record.sourceMessageId,
                )
            ReplyOperationState.CLAIMED,
            ReplyOperationState.PREPARED,
            ReplyOperationState.SUBMITTING,
            ReplyOperationState.SUBMITTED,
            ReplyOperationState.SUBMISSION_UNKNOWN_PENDING,
            ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED -> Unit
            ReplyOperationState.RESERVED -> error("reserved phase returned above")
        }
        if (
            operation.expectedUnitCount != UNKNOWN_UNIT_COUNT &&
            operation.expectedUnitCount != unitCount
        ) {
            return ReplyOperationSentResult.UnitCountMismatch
        }
        if (operation.expectedUnitCount == UNKNOWN_UNIT_COUNT) {
            operation.expectedUnitCount = unitCount
            operation.sentUnits = BooleanArray(unitCount)
        }
        if (operation.sentUnits[unitIndex]) {
            return ReplyOperationSentResult.Pending(
                conversationId = operation.record.conversationId,
                duplicate = true,
            )
        }
        operation.sentUnits[unitIndex] = true
        return if (operation.sentUnits.all { it }) {
            operation.extendExpiry(terminalExpiresAtMillis)
            operation.state = ReplyOperationState.SUCCESS_PENDING
            operation.requestProviderStatus(SmsProviderStatus.COMPLETE)
            ReplyOperationSentResult.SuccessPending(
                operation.record.conversationId,
                operation.record.sourceMessageId,
            )
        } else {
            if (
                operation.state == ReplyOperationState.CLAIMED ||
                operation.state == ReplyOperationState.PREPARED ||
                operation.state == ReplyOperationState.SUBMITTING
            ) {
                operation.state = ReplyOperationState.SUBMITTED
            }
            ReplyOperationSentResult.Pending(
                conversationId = operation.record.conversationId,
                duplicate = false,
            )
        }
    }

    @Synchronized
    override fun markFailurePending(
        operationId: Long,
        providerMessageId: ProviderMessageId?,
        unitIndex: Int,
        unitCount: Int,
        nowMillis: Long,
        terminalExpiresAtMillis: Long,
    ): ReplyOperationFailureResult {
        if (
            operationId <= 0L ||
            !providerMessageId.isValidSmsProviderId() ||
            unitCount !in 1..MAXIMUM_UNIT_COUNT ||
            unitIndex !in 0 until unitCount ||
            terminalExpiresAtMillis <= nowMillis
        ) {
            return ReplyOperationFailureResult.Invalid
        }
        val operation = active(operationId, nowMillis)
            ?: return ReplyOperationFailureResult.Untracked
        if (operation.state == ReplyOperationState.RESERVED) {
            return ReplyOperationFailureResult.PhaseMismatch
        }
        if (!operation.providerMatches(providerMessageId)) {
            return ReplyOperationFailureResult.ProviderMismatch
        }
        if (!operation.unitCountMatches(unitCount)) {
            return ReplyOperationFailureResult.UnitCountMismatch
        }
        when (operation.bindProvider(providerMessageId)) {
            ProviderBindingResult.MATCHED -> Unit
            ProviderBindingResult.MISMATCH -> return ReplyOperationFailureResult.ProviderMismatch
        }
        if (!operation.bindUnitCountWithoutProgress(unitCount)) {
            return ReplyOperationFailureResult.UnitCountMismatch
        }
        return when (operation.state) {
            ReplyOperationState.CLAIMED,
            ReplyOperationState.PREPARED,
            ReplyOperationState.SUBMITTING,
            ReplyOperationState.SUBMITTED,
            ReplyOperationState.SUBMISSION_UNKNOWN_PENDING -> {
                operation.extendExpiry(terminalExpiresAtMillis)
                operation.state = ReplyOperationState.FAILURE_PENDING
                operation.requestProviderStatus(SmsProviderStatus.FAILED)
                ReplyOperationFailureResult.Pending(
                    conversationId = operation.record.conversationId,
                    duplicate = false,
                )
            }
            ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED -> {
                operation.extendExpiry(terminalExpiresAtMillis)
                operation.state = ReplyOperationState.FAILURE_NOTIFIED
                operation.requestProviderStatus(SmsProviderStatus.FAILED)
                ReplyOperationFailureResult.Notified(operation.record.conversationId)
            }
            ReplyOperationState.FAILURE_PENDING -> ReplyOperationFailureResult.Pending(
                conversationId = operation.record.conversationId,
                duplicate = true,
            )
            ReplyOperationState.FAILURE_NOTIFIED ->
                ReplyOperationFailureResult.Notified(operation.record.conversationId)
            ReplyOperationState.SUCCESS_PENDING,
            ReplyOperationState.SUCCESS_COMPLETE ->
                ReplyOperationFailureResult.SuccessTerminal(operation.record.conversationId)
            ReplyOperationState.RESERVED -> error("reserved phase returned above")
        }
    }

    @Synchronized
    override fun acknowledgeFailureNotification(
        operationId: Long,
        nowMillis: Long,
    ): ReplyOperationAcknowledgementResult {
        if (operationId <= 0L) return ReplyOperationAcknowledgementResult.Invalid
        val operation = active(operationId, nowMillis)
            ?: return ReplyOperationAcknowledgementResult.Untracked
        return when (operation.state) {
            ReplyOperationState.FAILURE_PENDING -> {
                operation.state = ReplyOperationState.FAILURE_NOTIFIED
                ReplyOperationAcknowledgementResult.Acknowledged
            }
            ReplyOperationState.SUBMISSION_UNKNOWN_PENDING -> {
                operation.state = ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED
                ReplyOperationAcknowledgementResult.Acknowledged
            }
            ReplyOperationState.FAILURE_NOTIFIED,
            ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED ->
                ReplyOperationAcknowledgementResult.AlreadyAcknowledged
            else -> ReplyOperationAcknowledgementResult.NotPending
        }
    }

    @Synchronized
    override fun markSuccessPending(
        operationId: Long,
        nowMillis: Long,
        terminalExpiresAtMillis: Long,
    ): ReplyOperationSuccessResult {
        if (operationId <= 0L || terminalExpiresAtMillis <= nowMillis) {
            return ReplyOperationSuccessResult.Invalid
        }
        val operation = active(operationId, nowMillis)
            ?: return ReplyOperationSuccessResult.Untracked
        return when (operation.state) {
            ReplyOperationState.RESERVED -> ReplyOperationSuccessResult.PhaseMismatch
            ReplyOperationState.CLAIMED,
            ReplyOperationState.PREPARED,
            ReplyOperationState.SUBMITTING,
            ReplyOperationState.SUBMITTED -> {
                operation.extendExpiry(terminalExpiresAtMillis)
                operation.state = ReplyOperationState.SUCCESS_PENDING
                ReplyOperationSuccessResult.Pending(
                    conversationId = operation.record.conversationId,
                    sourceMessageId = operation.record.sourceMessageId,
                    duplicate = false,
                )
            }
            ReplyOperationState.SUCCESS_PENDING -> ReplyOperationSuccessResult.Pending(
                conversationId = operation.record.conversationId,
                sourceMessageId = operation.record.sourceMessageId,
                duplicate = true,
            )
            ReplyOperationState.SUCCESS_COMPLETE ->
                ReplyOperationSuccessResult.Complete(
                    operation.record.conversationId,
                    operation.record.sourceMessageId,
                )
            ReplyOperationState.FAILURE_PENDING ->
                ReplyOperationSuccessResult.FailurePending(operation.record.conversationId)
            ReplyOperationState.FAILURE_NOTIFIED ->
                ReplyOperationSuccessResult.FailureNotified(operation.record.conversationId)
            ReplyOperationState.SUBMISSION_UNKNOWN_PENDING ->
                ReplyOperationSuccessResult.SubmissionUnknownPending(
                    operation.record.conversationId,
                )
            ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED ->
                ReplyOperationSuccessResult.SubmissionUnknownNotified(
                    operation.record.conversationId,
                )
        }
    }

    @Synchronized
    override fun acknowledgeSuccessCancellation(
        operationId: Long,
        nowMillis: Long,
    ): ReplyOperationAcknowledgementResult = acknowledge(
        operationId = operationId,
        nowMillis = nowMillis,
        pendingState = ReplyOperationState.SUCCESS_PENDING,
        acknowledgedState = ReplyOperationState.SUCCESS_COMPLETE,
    )

    @Synchronized
    override fun pendingFailures(nowMillis: Long): ReplyOperationStorePendingFailuresResult {
        removeExpired(nowMillis)
        return ReplyOperationStorePendingFailuresResult.Available(
            pendingFailureOperations(),
        )
    }

    @Synchronized
    override fun pendingSuccesses(nowMillis: Long): ReplyOperationStorePendingSuccessesResult {
        removeExpired(nowMillis)
        return ReplyOperationStorePendingSuccessesResult.Available(
            pendingOperations(ReplyOperationState.SUCCESS_PENDING),
        )
    }

    @Synchronized
    override fun recoverInheritedOperations(
        nowMillis: Long,
        terminalExpiresAtMillis: Long,
    ): ReplyOperationRecoveryResult {
        if (terminalExpiresAtMillis <= nowMillis) {
            return ReplyOperationRecoveryResult.PersistenceFailure
        }
        removeExpired(nowMillis)
        var knownUnsent = 0
        var preparedFailed = 0
        var submissionUnknown = 0
        operations.values.forEach { operation ->
            when (operation.state) {
                ReplyOperationState.RESERVED,
                ReplyOperationState.CLAIMED -> {
                    operation.state = ReplyOperationState.FAILURE_PENDING
                    operation.extendExpiry(terminalExpiresAtMillis)
                    knownUnsent += 1
                }
                ReplyOperationState.PREPARED -> {
                    operation.state = ReplyOperationState.FAILURE_PENDING
                    operation.extendExpiry(terminalExpiresAtMillis)
                    operation.requestProviderStatus(SmsProviderStatus.FAILED)
                    preparedFailed += 1
                }
                ReplyOperationState.SUBMITTING,
                ReplyOperationState.SUBMITTED -> {
                    operation.state = ReplyOperationState.SUBMISSION_UNKNOWN_PENDING
                    operation.extendExpiry(terminalExpiresAtMillis)
                    submissionUnknown += 1
                }
                ReplyOperationState.FAILURE_PENDING,
                ReplyOperationState.FAILURE_NOTIFIED,
                ReplyOperationState.SUBMISSION_UNKNOWN_PENDING,
                ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED,
                ReplyOperationState.SUCCESS_PENDING,
                ReplyOperationState.SUCCESS_COMPLETE -> Unit
            }
        }
        return ReplyOperationRecoveryResult.Recovered(
            knownUnsentCount = knownUnsent,
            preparedFailureCount = preparedFailed,
            submissionUnknownCount = submissionUnknown,
            corruptCount = 0,
        )
    }

    @Synchronized
    override fun recoverInterruptedOperation(
        operationId: Long,
        nowMillis: Long,
        terminalExpiresAtMillis: Long,
    ): ReplyOperationInterruptedRecoveryResult {
        if (operationId <= 0L || terminalExpiresAtMillis <= nowMillis) {
            return ReplyOperationInterruptedRecoveryResult.Invalid
        }
        val operation = active(operationId, nowMillis)
            ?: return ReplyOperationInterruptedRecoveryResult.Untracked
        val (failureKind, transitioned) = when (operation.state) {
            ReplyOperationState.RESERVED,
            ReplyOperationState.CLAIMED -> {
                operation.state = ReplyOperationState.FAILURE_PENDING
                operation.extendExpiry(terminalExpiresAtMillis)
                ReplyOperationFailureKind.KNOWN_UNSENT to true
            }
            ReplyOperationState.PREPARED -> {
                operation.state = ReplyOperationState.FAILURE_PENDING
                operation.extendExpiry(terminalExpiresAtMillis)
                operation.requestProviderStatus(SmsProviderStatus.FAILED)
                ReplyOperationFailureKind.KNOWN_UNSENT to true
            }
            ReplyOperationState.SUBMITTING,
            ReplyOperationState.SUBMITTED -> {
                operation.state = ReplyOperationState.SUBMISSION_UNKNOWN_PENDING
                operation.extendExpiry(terminalExpiresAtMillis)
                ReplyOperationFailureKind.SUBMISSION_UNKNOWN to true
            }
            ReplyOperationState.FAILURE_PENDING ->
                ReplyOperationFailureKind.KNOWN_UNSENT to false
            ReplyOperationState.SUBMISSION_UNKNOWN_PENDING ->
                ReplyOperationFailureKind.SUBMISSION_UNKNOWN to false
            ReplyOperationState.FAILURE_NOTIFIED -> return interruptedNotified(
                operation = operation,
                failureKind = ReplyOperationFailureKind.KNOWN_UNSENT,
            )
            ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED -> return interruptedNotified(
                operation = operation,
                failureKind = ReplyOperationFailureKind.SUBMISSION_UNKNOWN,
            )
            ReplyOperationState.SUCCESS_PENDING,
            ReplyOperationState.SUCCESS_COMPLETE ->
                return ReplyOperationInterruptedRecoveryResult.SuccessTerminal(
                    conversationId = operation.record.conversationId,
                )
        }
        return interruptedPending(
            operation = operation,
            failureKind = failureKind,
            transitioned = transitioned,
        )
    }

    @Synchronized
    override fun recordDeliveryFailure(
        operationId: Long,
        unitIndex: Int,
        unitCount: Int,
        providerMessageId: ProviderMessageId?,
        nowMillis: Long,
        terminalExpiresAtMillis: Long,
    ): ReplyOperationProviderStatusResult {
        if (
            operationId <= 0L ||
            !providerMessageId.isValidSmsProviderId() ||
            unitCount !in 1..MAXIMUM_UNIT_COUNT ||
            unitIndex !in 0 until unitCount ||
            terminalExpiresAtMillis <= nowMillis
        ) {
            return ReplyOperationProviderStatusResult.Invalid
        }
        val operation = active(operationId, nowMillis)
            ?: return ReplyOperationProviderStatusResult.Untracked
        if (operation.state == ReplyOperationState.RESERVED) {
            return ReplyOperationProviderStatusResult.PhaseMismatch
        }
        if (!operation.providerMatches(providerMessageId)) {
            return ReplyOperationProviderStatusResult.ProviderMismatch
        }
        if (!operation.unitCountMatches(unitCount)) {
            return ReplyOperationProviderStatusResult.UnitCountMismatch
        }
        when (operation.bindProvider(providerMessageId)) {
            ProviderBindingResult.MATCHED -> Unit
            ProviderBindingResult.MISMATCH ->
                return ReplyOperationProviderStatusResult.ProviderMismatch
        }
        if (!operation.bindUnitCountWithoutProgress(unitCount)) {
            return ReplyOperationProviderStatusResult.UnitCountMismatch
        }
        operation.advanceCallbackOwnedPhase()
        operation.extendExpiry(terminalExpiresAtMillis)
        val statusChanged = operation.requestProviderStatus(SmsProviderStatus.DELIVERY_FAILED)
        return operation.recordDeliverySentEvidence(
            unitIndex = unitIndex,
            terminalExpiresAtMillis = terminalExpiresAtMillis,
            nonTerminalResult = if (statusChanged) {
                ReplyOperationProviderStatusResult.Recorded
            } else {
                ReplyOperationProviderStatusResult.Unchanged
            },
        )
    }

    @Synchronized
    override fun recordDeliverySuccess(
        operationId: Long,
        unitIndex: Int,
        unitCount: Int,
        providerMessageId: ProviderMessageId?,
        nowMillis: Long,
        terminalExpiresAtMillis: Long,
    ): ReplyOperationProviderStatusResult {
        if (
            operationId <= 0L ||
            !providerMessageId.isValidSmsProviderId() ||
            unitCount !in 1..MAXIMUM_UNIT_COUNT ||
            unitIndex !in 0 until unitCount ||
            terminalExpiresAtMillis <= nowMillis
        ) {
            return ReplyOperationProviderStatusResult.Invalid
        }
        val operation = active(operationId, nowMillis)
            ?: return ReplyOperationProviderStatusResult.Untracked
        if (operation.state == ReplyOperationState.RESERVED) {
            return ReplyOperationProviderStatusResult.PhaseMismatch
        }
        if (!operation.providerMatches(providerMessageId)) {
            return ReplyOperationProviderStatusResult.ProviderMismatch
        }
        if (!operation.unitCountMatches(unitCount)) {
            return ReplyOperationProviderStatusResult.UnitCountMismatch
        }
        when (operation.bindProvider(providerMessageId)) {
            ProviderBindingResult.MATCHED -> Unit
            ProviderBindingResult.MISMATCH ->
                return ReplyOperationProviderStatusResult.ProviderMismatch
        }
        if (!operation.bindUnitCountWithoutProgress(unitCount)) {
            return ReplyOperationProviderStatusResult.UnitCountMismatch
        }
        operation.advanceCallbackOwnedPhase()
        operation.extendExpiry(terminalExpiresAtMillis)
        return operation.recordDeliverySentEvidence(
            unitIndex = unitIndex,
            terminalExpiresAtMillis = terminalExpiresAtMillis,
            nonTerminalResult = ReplyOperationProviderStatusResult.Tracked,
        )
    }

    @Synchronized
    override fun pendingProviderUpdates(
        nowMillis: Long,
    ): ReplyOperationStorePendingProviderUpdatesResult {
        removeExpired(nowMillis)
        return ReplyOperationStorePendingProviderUpdatesResult.Available(
            operations.values
                .asSequence()
                .filter(InMemoryOperation::providerUpdatePending)
                .sortedBy { operation -> operation.record.operationId }
                .map { operation ->
                    ReplyOperationStoredProviderUpdate(
                        operationId = operation.record.operationId,
                        conversationId = operation.record.conversationId,
                        providerMessageId = requireNotNull(operation.providerMessageId),
                        status = requireNotNull(operation.desiredProviderStatus),
                    )
                }
                .toList(),
        )
    }

    @Synchronized
    override fun pendingProviderUpdate(
        operationId: Long,
        nowMillis: Long,
    ): ReplyOperationStorePendingProviderUpdateResult {
        if (operationId <= 0L) {
            return ReplyOperationStorePendingProviderUpdateResult.Available(null)
        }
        val operation = active(operationId, nowMillis)
            ?: return ReplyOperationStorePendingProviderUpdateResult.Available(null)
        return ReplyOperationStorePendingProviderUpdateResult.Available(
            operation.takeIf(InMemoryOperation::providerUpdatePending)?.let { pending ->
                ReplyOperationStoredProviderUpdate(
                    operationId = pending.record.operationId,
                    conversationId = pending.record.conversationId,
                    providerMessageId = requireNotNull(pending.providerMessageId),
                    status = requireNotNull(pending.desiredProviderStatus),
                )
            },
        )
    }

    @Synchronized
    override fun acknowledgeProviderUpdate(
        operationId: Long,
        providerMessageId: ProviderMessageId,
        status: SmsProviderStatus,
        nowMillis: Long,
    ): ReplyOperationProviderAcknowledgementResult {
        if (
            operationId <= 0L ||
            providerMessageId.kind != ProviderKind.SMS ||
            status !in PROVIDER_TERMINAL_STATUSES
        ) {
            return ReplyOperationProviderAcknowledgementResult.Invalid
        }
        val operation = active(operationId, nowMillis)
            ?: return ReplyOperationProviderAcknowledgementResult.Untracked
        if (
            operation.providerMessageId != providerMessageId ||
            operation.desiredProviderStatus != status
        ) {
            return ReplyOperationProviderAcknowledgementResult.Stale
        }
        return if (operation.providerUpdatePending) {
            operation.providerUpdatePending = false
            ReplyOperationProviderAcknowledgementResult.Acknowledged
        } else {
            ReplyOperationProviderAcknowledgementResult.AlreadyAcknowledged
        }
    }

    @Synchronized
    override fun remove(
        operationId: Long,
        nowMillis: Long,
    ): ReplyOperationRemovalResult {
        if (operationId <= 0L) return ReplyOperationRemovalResult.Invalid
        active(operationId, nowMillis) ?: return ReplyOperationRemovalResult.Untracked
        operations.remove(operationId)
        return ReplyOperationRemovalResult.Removed
    }

    @Synchronized
    override fun cleanupExpired(nowMillis: Long): ReplyOperationCleanupResult {
        val before = operations.size
        removeExpired(nowMillis)
        return ReplyOperationCleanupResult.Success(before - operations.size)
    }

    @Synchronized
    override fun clear(): Boolean {
        operations.clear()
        return true
    }

    private fun acknowledge(
        operationId: Long,
        nowMillis: Long,
        pendingState: ReplyOperationState,
        acknowledgedState: ReplyOperationState,
    ): ReplyOperationAcknowledgementResult {
        if (operationId <= 0L) return ReplyOperationAcknowledgementResult.Invalid
        val operation = active(operationId, nowMillis)
            ?: return ReplyOperationAcknowledgementResult.Untracked
        return when (operation.state) {
            pendingState -> {
                operation.state = acknowledgedState
                ReplyOperationAcknowledgementResult.Acknowledged
            }
            acknowledgedState -> ReplyOperationAcknowledgementResult.AlreadyAcknowledged
            else -> ReplyOperationAcknowledgementResult.NotPending
        }
    }

    private fun active(operationId: Long, nowMillis: Long): InMemoryOperation? {
        val operation = operations[operationId] ?: return null
        if (operation.record.expiresAtMillis <= nowMillis) {
            operations.remove(operationId)
            return null
        }
        return operation
    }

    private fun removeExpired(nowMillis: Long) {
        operations.entries.removeAll { (_, operation) -> operation.record.expiresAtMillis <= nowMillis }
    }

    private fun pendingOperations(state: ReplyOperationState): List<ReplyOperationStoredPending> =
        operations.values
            .filter { operation -> operation.state == state }
            .sortedBy { operation -> operation.record.operationId }
            .map { operation ->
                ReplyOperationStoredPending(
                    operationId = operation.record.operationId,
                    conversationId = operation.record.conversationId,
                    sourceMessageId = operation.record.sourceMessageId,
                )
            }

    private fun pendingFailureOperations(): List<ReplyOperationStoredPendingFailure> =
        operations.values
            .mapNotNull { operation ->
                val failureKind = when (operation.state) {
                    ReplyOperationState.FAILURE_PENDING -> ReplyOperationFailureKind.KNOWN_UNSENT
                    ReplyOperationState.SUBMISSION_UNKNOWN_PENDING ->
                        ReplyOperationFailureKind.SUBMISSION_UNKNOWN
                    else -> null
                } ?: return@mapNotNull null
                ReplyOperationStoredPendingFailure(
                    operationId = operation.record.operationId,
                    conversationId = operation.record.conversationId,
                    sourceMessageId = operation.record.sourceMessageId,
                    failureKind = failureKind,
                )
            }
            .sortedBy(ReplyOperationStoredPendingFailure::operationId)

    private fun interruptedPending(
        operation: InMemoryOperation,
        failureKind: ReplyOperationFailureKind,
        transitioned: Boolean,
    ) = ReplyOperationInterruptedRecoveryResult.Pending(
        operation = ReplyOperationPendingFailure(
            operationId = MessageId(
                ProviderKind.PENDING_OPERATION,
                operation.record.operationId,
            ),
            conversationId = operation.record.conversationId,
            sourceMessageId = operation.record.sourceMessageId,
            failureKind = failureKind,
        ),
        transitioned = transitioned,
    )

    private fun interruptedNotified(
        operation: InMemoryOperation,
        failureKind: ReplyOperationFailureKind,
    ) = ReplyOperationInterruptedRecoveryResult.Notified(
        operation = ReplyOperationPendingFailure(
            operationId = MessageId(
                ProviderKind.PENDING_OPERATION,
                operation.record.operationId,
            ),
            conversationId = operation.record.conversationId,
            sourceMessageId = operation.record.sourceMessageId,
            failureKind = failureKind,
        ),
    )

    private data class InMemoryOperation(
        var record: ReplyOperationRecord,
        var state: ReplyOperationState = ReplyOperationState.RESERVED,
        var expectedUnitCount: Int = UNKNOWN_UNIT_COUNT,
        var sentUnits: BooleanArray = BooleanArray(0),
        var providerMessageId: ProviderMessageId? = null,
        var desiredProviderStatus: SmsProviderStatus? = null,
        var providerUpdatePending: Boolean = false,
    ) {
        fun extendExpiry(requestedExpiry: Long) {
            if (requestedExpiry > record.expiresAtMillis) {
                record = record.copy(expiresAtMillis = requestedExpiry)
            }
        }

        fun bindProvider(candidate: ProviderMessageId?): ProviderBindingResult {
            if (candidate == null) return ProviderBindingResult.MATCHED
            val current = providerMessageId
            if (current != null) {
                return if (current == candidate) {
                    ProviderBindingResult.MATCHED
                } else {
                    ProviderBindingResult.MISMATCH
                }
            }
            providerMessageId = candidate
            if (desiredProviderStatus != null) providerUpdatePending = true
            return ProviderBindingResult.MATCHED
        }

        fun providerMatches(candidate: ProviderMessageId?): Boolean =
            candidate == null || providerMessageId == null || providerMessageId == candidate

        fun unitCountMatches(unitCount: Int): Boolean =
            expectedUnitCount == UNKNOWN_UNIT_COUNT || expectedUnitCount == unitCount

        fun requestProviderStatus(requested: SmsProviderStatus): Boolean {
            val resolved = strongerProviderStatus(desiredProviderStatus, requested)
            if (resolved == desiredProviderStatus) return false
            desiredProviderStatus = resolved
            providerUpdatePending = providerMessageId != null
            return true
        }

        fun bindUnitCountWithoutProgress(unitCount: Int): Boolean {
            if (expectedUnitCount == UNKNOWN_UNIT_COUNT) {
                expectedUnitCount = unitCount
                sentUnits = BooleanArray(unitCount)
                return true
            }
            return expectedUnitCount == unitCount
        }

        fun advanceCallbackOwnedPhase() {
            if (
                state == ReplyOperationState.CLAIMED ||
                state == ReplyOperationState.PREPARED ||
                state == ReplyOperationState.SUBMITTING
            ) {
                state = ReplyOperationState.SUBMITTED
            }
        }

        fun recordDeliverySentEvidence(
            unitIndex: Int,
            terminalExpiresAtMillis: Long,
            nonTerminalResult: ReplyOperationProviderStatusResult,
        ): ReplyOperationProviderStatusResult {
            when (state) {
                ReplyOperationState.SUCCESS_PENDING ->
                    return ReplyOperationProviderStatusResult.SuccessPending
                ReplyOperationState.SUCCESS_COMPLETE ->
                    return ReplyOperationProviderStatusResult.SuccessComplete
                ReplyOperationState.FAILURE_PENDING,
                ReplyOperationState.FAILURE_NOTIFIED -> {
                    sentUnits[unitIndex] = true
                    return nonTerminalResult
                }
                ReplyOperationState.RESERVED -> error("reserved delivery was rejected above")
                ReplyOperationState.CLAIMED,
                ReplyOperationState.PREPARED,
                ReplyOperationState.SUBMITTING,
                ReplyOperationState.SUBMITTED,
                ReplyOperationState.SUBMISSION_UNKNOWN_PENDING,
                ReplyOperationState.SUBMISSION_UNKNOWN_NOTIFIED -> Unit
            }
            sentUnits[unitIndex] = true
            if (!sentUnits.all { it }) return nonTerminalResult
            extendExpiry(terminalExpiresAtMillis)
            state = ReplyOperationState.SUCCESS_PENDING
            requestProviderStatus(SmsProviderStatus.COMPLETE)
            return ReplyOperationProviderStatusResult.SuccessPending
        }
    }

    private companion object {
        const val UNKNOWN_UNIT_COUNT = 0
        const val MAXIMUM_UNIT_COUNT = 255
        const val ABSOLUTE_MAXIMUM_ENTRIES = 16_384
    }
}

internal enum class ReplyOperationStoreReservationResult {
    Reserved,
    Collision,
    Full,
    PersistenceFailure,
}

internal sealed interface ReplyOperationReservationResult {
    data class Reserved(val operationId: MessageId) : ReplyOperationReservationResult

    data object IdentifierCollision : ReplyOperationReservationResult

    data object InvalidSource : ReplyOperationReservationResult

    data object Full : ReplyOperationReservationResult

    data object PersistenceFailure : ReplyOperationReservationResult
}

internal sealed interface ReplyOperationSubmittedResult {
    data object Tracked : ReplyOperationSubmittedResult

    data object FailurePending : ReplyOperationSubmittedResult

    data object FailureNotified : ReplyOperationSubmittedResult

    data object SuccessPending : ReplyOperationSubmittedResult

    data object SuccessComplete : ReplyOperationSubmittedResult

    data object SubmissionUnknownPending : ReplyOperationSubmittedResult

    data object SubmissionUnknownNotified : ReplyOperationSubmittedResult

    data object Untracked : ReplyOperationSubmittedResult

    data object UnitCountMismatch : ReplyOperationSubmittedResult

    data object ProviderMismatch : ReplyOperationSubmittedResult

    data object PhaseMismatch : ReplyOperationSubmittedResult

    data object CorruptOwnership : ReplyOperationSubmittedResult

    data object Invalid : ReplyOperationSubmittedResult

    data object PersistenceFailure : ReplyOperationSubmittedResult
}

internal sealed interface ReplyOperationSentResult {
    data class Pending(
        val conversationId: ConversationId,
        val duplicate: Boolean,
    ) : ReplyOperationSentResult

    data class SuccessPending(
        val conversationId: ConversationId,
        val sourceMessageId: MessageId?,
    ) : ReplyOperationSentResult

    data class SuccessComplete(
        val conversationId: ConversationId,
        val sourceMessageId: MessageId?,
    ) : ReplyOperationSentResult

    data class FailurePending(val conversationId: ConversationId) : ReplyOperationSentResult

    data class FailureNotified(val conversationId: ConversationId) : ReplyOperationSentResult

    data object Untracked : ReplyOperationSentResult

    data object UnitCountMismatch : ReplyOperationSentResult

    data object ProviderMismatch : ReplyOperationSentResult

    data object PhaseMismatch : ReplyOperationSentResult

    data object CorruptOwnership : ReplyOperationSentResult

    data object Invalid : ReplyOperationSentResult

    data object PersistenceFailure : ReplyOperationSentResult
}

internal sealed interface ReplyOperationFailureResult {
    data class Pending(
        val conversationId: ConversationId,
        val duplicate: Boolean,
    ) : ReplyOperationFailureResult

    data class Notified(val conversationId: ConversationId) : ReplyOperationFailureResult

    data class SuccessTerminal(val conversationId: ConversationId) : ReplyOperationFailureResult

    data object ProviderMismatch : ReplyOperationFailureResult

    data object UnitCountMismatch : ReplyOperationFailureResult

    data object PhaseMismatch : ReplyOperationFailureResult

    data object CorruptOwnership : ReplyOperationFailureResult

    data object Untracked : ReplyOperationFailureResult

    data object Invalid : ReplyOperationFailureResult

    data object PersistenceFailure : ReplyOperationFailureResult
}

internal sealed interface ReplyOperationSuccessResult {
    data class Pending(
        val conversationId: ConversationId,
        val sourceMessageId: MessageId?,
        val duplicate: Boolean,
    ) : ReplyOperationSuccessResult

    data class Complete(
        val conversationId: ConversationId,
        val sourceMessageId: MessageId?,
    ) : ReplyOperationSuccessResult

    data class FailurePending(val conversationId: ConversationId) : ReplyOperationSuccessResult

    data class FailureNotified(val conversationId: ConversationId) : ReplyOperationSuccessResult

    data class SubmissionUnknownPending(
        val conversationId: ConversationId,
    ) : ReplyOperationSuccessResult

    data class SubmissionUnknownNotified(
        val conversationId: ConversationId,
    ) : ReplyOperationSuccessResult

    data object PhaseMismatch : ReplyOperationSuccessResult

    data object CorruptOwnership : ReplyOperationSuccessResult

    data object Untracked : ReplyOperationSuccessResult

    data object Invalid : ReplyOperationSuccessResult

    data object PersistenceFailure : ReplyOperationSuccessResult
}

internal sealed interface ReplyOperationAcknowledgementResult {
    data object Acknowledged : ReplyOperationAcknowledgementResult

    data object AlreadyAcknowledged : ReplyOperationAcknowledgementResult

    data object NotPending : ReplyOperationAcknowledgementResult

    data object Untracked : ReplyOperationAcknowledgementResult

    data object Invalid : ReplyOperationAcknowledgementResult

    data object CorruptOwnership : ReplyOperationAcknowledgementResult

    data object PersistenceFailure : ReplyOperationAcknowledgementResult
}

internal data class ReplyOperationPending(
    val operationId: MessageId,
    val conversationId: ConversationId,
    val sourceMessageId: MessageId?,
)

internal sealed interface ReplyOperationPendingFailuresResult {
    data class Available(
        val operations: List<ReplyOperationPendingFailure>,
    ) : ReplyOperationPendingFailuresResult

    data object PersistenceFailure : ReplyOperationPendingFailuresResult
}

internal sealed interface ReplyOperationPendingSuccessesResult {
    data class Available(
        val operations: List<ReplyOperationPending>,
    ) : ReplyOperationPendingSuccessesResult

    data object PersistenceFailure : ReplyOperationPendingSuccessesResult
}

internal data class ReplyOperationStoredPending(
    val operationId: Long,
    val conversationId: ConversationId,
    val sourceMessageId: MessageId?,
)

internal enum class ReplyOperationFailureKind {
    KNOWN_UNSENT,
    SUBMISSION_UNKNOWN,
}

internal data class ReplyOperationPendingFailure(
    val operationId: MessageId,
    val conversationId: ConversationId,
    val sourceMessageId: MessageId?,
    val failureKind: ReplyOperationFailureKind,
)

internal data class ReplyOperationStoredPendingFailure(
    val operationId: Long,
    val conversationId: ConversationId,
    val sourceMessageId: MessageId?,
    val failureKind: ReplyOperationFailureKind,
)

internal data class ReplyOperationProviderUpdate(
    val operationId: MessageId,
    val conversationId: ConversationId,
    val providerMessageId: ProviderMessageId,
    val status: SmsProviderStatus,
) {
    init {
        require(operationId.kind == ProviderKind.PENDING_OPERATION)
        require(conversationId.value > 0L)
        require(providerMessageId.kind == ProviderKind.SMS)
        require(status in PROVIDER_TERMINAL_STATUSES)
    }
}

internal data class ReplyOperationStoredProviderUpdate(
    val operationId: Long,
    val conversationId: ConversationId,
    val providerMessageId: ProviderMessageId,
    val status: SmsProviderStatus,
)

internal sealed interface ReplyOperationPendingProviderUpdatesResult {
    data class Available(
        val updates: List<ReplyOperationProviderUpdate>,
    ) : ReplyOperationPendingProviderUpdatesResult

    data object PersistenceFailure : ReplyOperationPendingProviderUpdatesResult
}

internal sealed interface ReplyOperationPendingProviderUpdateResult {
    data class Available(
        val update: ReplyOperationProviderUpdate?,
    ) : ReplyOperationPendingProviderUpdateResult

    data object Invalid : ReplyOperationPendingProviderUpdateResult

    data object CorruptOwnership : ReplyOperationPendingProviderUpdateResult

    data object PersistenceFailure : ReplyOperationPendingProviderUpdateResult
}

internal sealed interface ReplyOperationStorePendingProviderUpdatesResult {
    data class Available(
        val updates: List<ReplyOperationStoredProviderUpdate>,
    ) : ReplyOperationStorePendingProviderUpdatesResult

    data object PersistenceFailure : ReplyOperationStorePendingProviderUpdatesResult
}

internal sealed interface ReplyOperationStorePendingProviderUpdateResult {
    data class Available(
        val update: ReplyOperationStoredProviderUpdate?,
    ) : ReplyOperationStorePendingProviderUpdateResult

    data object PersistenceFailure : ReplyOperationStorePendingProviderUpdateResult

    data object CorruptOwnership : ReplyOperationStorePendingProviderUpdateResult
}

internal enum class ReplyOperationPhaseTransitionResult {
    Transitioned,
    AlreadyInPhase,
    AlreadyAdvanced,
    Terminal,
    SubmissionUnknown,
    Untracked,
    ProviderMismatch,
    UnitCountMismatch,
    PhaseMismatch,
    CorruptOwnership,
    Invalid,
    PersistenceFailure,
}

internal sealed interface ReplyOperationRecoveryResult {
    data class Recovered(
        val knownUnsentCount: Int,
        val preparedFailureCount: Int,
        val submissionUnknownCount: Int,
        val corruptCount: Int,
    ) : ReplyOperationRecoveryResult

    data object PersistenceFailure : ReplyOperationRecoveryResult
}

internal sealed interface ReplyOperationInterruptedRecoveryResult {
    data class Pending(
        val operation: ReplyOperationPendingFailure,
        val transitioned: Boolean,
    ) : ReplyOperationInterruptedRecoveryResult

    data class Notified(
        val operation: ReplyOperationPendingFailure,
    ) : ReplyOperationInterruptedRecoveryResult

    data class SuccessTerminal(
        val conversationId: ConversationId,
    ) : ReplyOperationInterruptedRecoveryResult

    data object Untracked : ReplyOperationInterruptedRecoveryResult

    data object CorruptOwnership : ReplyOperationInterruptedRecoveryResult

    data object Invalid : ReplyOperationInterruptedRecoveryResult

    data object PersistenceFailure : ReplyOperationInterruptedRecoveryResult
}

internal enum class ReplyOperationProviderStatusResult {
    Tracked,
    Recorded,
    Unchanged,
    SuccessPending,
    SuccessComplete,
    Untracked,
    ProviderMismatch,
    UnitCountMismatch,
    PhaseMismatch,
    CorruptOwnership,
    Invalid,
    PersistenceFailure,
}

internal enum class ReplyOperationProviderAcknowledgementResult {
    Acknowledged,
    AlreadyAcknowledged,
    Stale,
    Untracked,
    Invalid,
    CorruptOwnership,
    PersistenceFailure,
}

internal sealed interface ReplyOperationStorePendingFailuresResult {
    data class Available(
        val operations: List<ReplyOperationStoredPendingFailure>,
    ) : ReplyOperationStorePendingFailuresResult

    data object PersistenceFailure : ReplyOperationStorePendingFailuresResult
}

internal sealed interface ReplyOperationStorePendingSuccessesResult {
    data class Available(
        val operations: List<ReplyOperationStoredPending>,
    ) : ReplyOperationStorePendingSuccessesResult

    data object PersistenceFailure : ReplyOperationStorePendingSuccessesResult
}

internal sealed interface ReplyOperationRemovalResult {
    data object Removed : ReplyOperationRemovalResult

    data object Untracked : ReplyOperationRemovalResult

    data object Invalid : ReplyOperationRemovalResult

    data object CorruptOwnership : ReplyOperationRemovalResult

    data object PersistenceFailure : ReplyOperationRemovalResult
}

internal sealed interface ReplyOperationCleanupResult {
    data class Success(val removedCount: Int) : ReplyOperationCleanupResult

    data object PersistenceFailure : ReplyOperationCleanupResult
}
