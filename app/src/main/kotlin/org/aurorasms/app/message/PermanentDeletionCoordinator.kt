// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.PERMANENT_DELETION_UNDO_WINDOW_MILLIS
import org.aurorasms.core.state.PermanentDeletionId
import org.aurorasms.core.state.PermanentDeletionOperation
import org.aurorasms.core.state.PermanentDeletionPhase
import org.aurorasms.core.state.PermanentDeletionRepository
import org.aurorasms.core.state.PermanentDeletionRequest
import org.aurorasms.core.state.PermanentDeletionResult
import org.aurorasms.core.state.PermanentDeletionReviewReason
import org.aurorasms.core.state.PermanentDeletionTarget
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.PermanentDeletionProvider
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderDeletionCommitOutcome
import org.aurorasms.core.telephony.ProviderMessageDeletionTarget
import org.aurorasms.core.telephony.ProviderThreadDeletionSnapshot

internal interface PermanentDeletionClock {
    fun wallMillis(): Long
    fun elapsedMillis(): Long
}

internal object AndroidPermanentDeletionClock : PermanentDeletionClock {
    override fun wallMillis(): Long = System.currentTimeMillis()
    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
}

internal class PermanentDeletionCoordinator(
    private val applicationScope: CoroutineScope,
    private val roleState: DefaultSmsRoleState,
    private val repository: PermanentDeletionRepository,
    private val provider: PermanentDeletionProvider,
    private val alarms: PermanentDeletionAlarmDriver,
    private val onProviderChanged: () -> Unit,
    private val clock: PermanentDeletionClock = AndroidPermanentDeletionClock,
) : PermanentDeletionController {
    private val mutex = Mutex()
    private val fenceGeneration = AtomicLong(0L)
    private val completionSequence = AtomicLong(0L)
    private val timers = ConcurrentHashMap<Long, Job>()
    private val completions = MutableStateFlow<Map<Long, Completion>>(emptyMap())

    override fun observe(providerThreadId: ProviderThreadId): Flow<PermanentDeletionObservation> =
        combine(repository.observeByThread(providerThreadId), completions) { stored, completed ->
            when (stored) {
                is PermanentDeletionResult.Success -> stored.value?.toObservation()
                    ?: completed[providerThreadId.value]?.toObservation()
                    ?: PermanentDeletionObservation.None
                else -> PermanentDeletionObservation.Loading
            }
        }

    override suspend fun request(
        command: PermanentDeletionCommand,
    ): PermanentDeletionAttempt = mutex.withLock {
        val generation = fenceGeneration.get()
        if (!eligible(generation)) return@withLock PermanentDeletionAttempt.REFUSED
        val target = when (command) {
            is PermanentDeletionCommand.Message -> {
                val current = (provider.inspectMessage(command.providerMessageId)
                    as? ProviderAccessResult.Success)?.value
                    ?: return@withLock PermanentDeletionAttempt.REFUSED
                if (
                    current.providerThreadId != command.providerThreadId ||
                    current.syncFingerprint != command.syncFingerprint
                ) {
                    return@withLock PermanentDeletionAttempt.REFUSED
                }
                PermanentDeletionTarget.Message(
                    providerMessageId = current.providerMessageId,
                    providerThreadId = current.providerThreadId,
                    syncFingerprint = current.syncFingerprint,
                )
            }
            is PermanentDeletionCommand.Thread -> {
                val current = (provider.inspectThread(command.providerThreadId)
                    as? ProviderAccessResult.Success)?.value
                    ?: return@withLock PermanentDeletionAttempt.REFUSED
                if (current.messageCount <= 0L) return@withLock PermanentDeletionAttempt.REFUSED
                current.toStateTarget()
            }
        }
        if (!eligible(generation)) return@withLock PermanentDeletionAttempt.REFUSED
        val now = clock.wallMillis().coerceAtLeast(0L)
        if (now > Long.MAX_VALUE - PERMANENT_DELETION_UNDO_WINDOW_MILLIS) {
            return@withLock PermanentDeletionAttempt.REFUSED
        }
        val created = repository.create(
            PermanentDeletionRequest(
                target = target,
                dueTimestampMillis = now + PERMANENT_DELETION_UNDO_WINDOW_MILLIS,
                createdTimestampMillis = now,
                armedElapsedRealtimeMillis = clock.elapsedMillis().coerceAtLeast(0L),
            ),
        )
        val operation = (created as? PermanentDeletionResult.Success)?.value
            ?: return@withLock PermanentDeletionAttempt.REFUSED
        completions.value = completions.value - command.providerThreadId.value
        if (!alarms.arm(operation.id, operation.dueTimestampMillis)) {
            review(operation, PermanentDeletionReviewReason.ARMING_FAILED)
            return@withLock PermanentDeletionAttempt.ACCEPTED
        }
        scheduleTimer(operation)
        PermanentDeletionAttempt.ACCEPTED
    }

    override suspend fun undo(providerThreadId: ProviderThreadId): Boolean = mutex.withLock {
        val operation = (repository.readByThread(providerThreadId)
            as? PermanentDeletionResult.Success)?.value ?: return@withLock false
        if (operation.phase == PermanentDeletionPhase.COMMITTING) return@withLock false
        val removed = repository.removeUndoable(operation.id, operation.revision) is
            PermanentDeletionResult.Success
        if (removed) cancelWakeups(operation.id)
        removed
    }

    override suspend fun handleAlarm(id: PermanentDeletionId) = mutex.withLock {
        timers.remove(id.value)
        handlePending(id)
    }

    override suspend fun recover(reason: PermanentDeletionRecoveryReason) = mutex.withLock {
        val operations = (repository.recoverySnapshot() as? PermanentDeletionResult.Success)?.value
            ?: return@withLock
        operations.forEach { operation ->
            when (operation.phase) {
                PermanentDeletionPhase.REVIEW_REQUIRED -> {
                    cancelWakeups(operation.id)
                    if (
                        operation.reviewReason ==
                        PermanentDeletionReviewReason.INTERRUPTED_DURING_COMMIT
                    ) {
                        reconcileInterruptedReview(operation)
                    }
                }
                PermanentDeletionPhase.COMMITTING -> reconcileCommitting(operation)
                PermanentDeletionPhase.PENDING -> when {
                    reason == PermanentDeletionRecoveryReason.TIME_CHANGED ->
                        review(operation, PermanentDeletionReviewReason.CLOCK_CHANGED)
                    hasClockDrift(operation) ->
                        review(operation, PermanentDeletionReviewReason.CLOCK_CHANGED)
                    !roleState.isRoleHeld() ->
                        review(operation, PermanentDeletionReviewReason.PRECONDITION_FAILED)
                    isExcessivelyLate(operation) ->
                        review(operation, PermanentDeletionReviewReason.MISSED_AFTER_RESTART)
                    operation.dueTimestampMillis <= clock.wallMillis() -> handlePending(operation.id)
                    else -> rearm(operation)
                }
            }
        }
    }

    override suspend fun acknowledgeCompletion(providerThreadId: ProviderThreadId, epoch: Long) {
        mutex.withLock {
            val current = completions.value[providerThreadId.value]
            if (current?.epoch == epoch) {
                completions.value = completions.value - providerThreadId.value
            }
        }
    }

    override fun fence() {
        fenceGeneration.incrementAndGet()
        timers.values.forEach(Job::cancel)
        timers.clear()
        applicationScope.launch { recover(PermanentDeletionRecoveryReason.ROLE_CHANGED) }
    }

    private suspend fun handlePending(id: PermanentDeletionId) {
        var operation = (repository.read(id) as? PermanentDeletionResult.Success)?.value ?: return
        if (operation.phase != PermanentDeletionPhase.PENDING) return
        val now = clock.wallMillis().coerceAtLeast(0L)
        if (hasClockDrift(operation)) {
            review(operation, PermanentDeletionReviewReason.CLOCK_CHANGED)
            return
        }
        if (now < operation.dueTimestampMillis) {
            rearm(operation)
            return
        }
        if (isExcessivelyLate(operation)) {
            review(operation, PermanentDeletionReviewReason.MISSED_AFTER_RESTART)
            return
        }
        if (!roleState.isRoleHeld()) {
            review(operation, PermanentDeletionReviewReason.PRECONDITION_FAILED)
            return
        }
        val localReady = (repository.validateLocalPreconditions(operation)
            as? PermanentDeletionResult.Success)?.value == true
        if (!localReady) {
            review(operation, PermanentDeletionReviewReason.PRECONDITION_FAILED)
            return
        }
        when (inspect(operation.target)) {
            TargetInspection.ABSENT -> Unit
            TargetInspection.MATCHES -> Unit
            TargetInspection.CHANGED -> {
                review(operation, PermanentDeletionReviewReason.TARGET_CHANGED)
                return
            }
            TargetInspection.UNAVAILABLE -> {
                review(operation, PermanentDeletionReviewReason.PRECONDITION_FAILED)
                return
            }
        }
        operation = (repository.markCommitting(
            operation.id,
            operation.revision,
            nextTimestamp(operation),
        ) as? PermanentDeletionResult.Success)?.value ?: return
        cancelWakeups(operation.id)
        val outcome = delete(operation.target)
        when (outcome) {
            ProviderDeletionCommitOutcome.DELETED,
            ProviderDeletionCommitOutcome.ALREADY_ABSENT,
            -> complete(operation)
            ProviderDeletionCommitOutcome.TARGET_CHANGED ->
                review(operation, PermanentDeletionReviewReason.TARGET_CHANGED)
            null -> Unit // Ambiguous commit stays durable and is inspected during recovery.
        }
    }

    private suspend fun reconcileCommitting(operation: PermanentDeletionOperation) {
        cancelWakeups(operation.id)
        when (inspect(operation.target)) {
            TargetInspection.ABSENT -> complete(operation)
            TargetInspection.MATCHES ->
                review(operation, PermanentDeletionReviewReason.INTERRUPTED_DURING_COMMIT)
            TargetInspection.CHANGED ->
                review(operation, PermanentDeletionReviewReason.TARGET_CHANGED)
            TargetInspection.UNAVAILABLE -> Unit
        }
    }

    private suspend fun reconcileInterruptedReview(operation: PermanentDeletionOperation) {
        when (inspect(operation.target)) {
            TargetInspection.ABSENT -> complete(operation)
            TargetInspection.MATCHES -> {
                repository.removeUndoable(operation.id, operation.revision)
                Unit
            }
            TargetInspection.CHANGED,
            TargetInspection.UNAVAILABLE,
            -> Unit
        }
    }

    private suspend fun complete(operation: PermanentDeletionOperation) {
        val removed = repository.removeCommitted(operation.id, operation.revision) is
            PermanentDeletionResult.Success
        if (!removed) return
        cancelWakeups(operation.id)
        val completion = Completion(
            targetKind = operation.target.targetKind(),
            epoch = completionSequence.incrementAndGet(),
        )
        completions.value = (completions.value +
            (operation.target.providerThreadId.value to completion)).boundedCompletions()
        onProviderChanged()
    }

    private suspend fun inspect(target: PermanentDeletionTarget): TargetInspection = when (target) {
        is PermanentDeletionTarget.Message -> when (
            val result = provider.inspectMessage(target.providerMessageId)
        ) {
            is ProviderAccessResult.Success -> when (val current = result.value) {
                null -> TargetInspection.ABSENT
                target.toProviderTarget() -> TargetInspection.MATCHES
                else -> TargetInspection.CHANGED
            }
            else -> TargetInspection.UNAVAILABLE
        }
        is PermanentDeletionTarget.Thread -> when (
            val result = provider.inspectThread(target.providerThreadId)
        ) {
            is ProviderAccessResult.Success -> when {
                result.value.messageCount == 0L -> TargetInspection.ABSENT
                result.value == target.toProviderSnapshot() -> TargetInspection.MATCHES
                else -> TargetInspection.CHANGED
            }
            else -> TargetInspection.UNAVAILABLE
        }
    }

    private suspend fun delete(target: PermanentDeletionTarget): ProviderDeletionCommitOutcome? =
        when (target) {
            is PermanentDeletionTarget.Message ->
                (provider.deleteMessage(target.toProviderTarget()) as? ProviderAccessResult.Success)
                    ?.value
            is PermanentDeletionTarget.Thread ->
                (provider.deleteThread(target.toProviderSnapshot()) as? ProviderAccessResult.Success)
                    ?.value
        }

    private suspend fun review(
        operation: PermanentDeletionOperation,
        reason: PermanentDeletionReviewReason,
    ) {
        val reviewed = repository.markReviewRequired(
            operation.id,
            operation.revision,
            reason,
            nextTimestamp(operation),
        )
        if (reviewed is PermanentDeletionResult.Success) cancelWakeups(operation.id)
    }

    private suspend fun rearm(operation: PermanentDeletionOperation) {
        if (!alarms.arm(operation.id, operation.dueTimestampMillis)) {
            review(operation, PermanentDeletionReviewReason.ARMING_FAILED)
        } else {
            scheduleTimer(operation)
        }
    }

    private fun scheduleTimer(operation: PermanentDeletionOperation) {
        timers.remove(operation.id.value)?.cancel()
        timers[operation.id.value] = applicationScope.launch {
            delay((operation.dueTimestampMillis - clock.wallMillis()).coerceAtLeast(0L))
            handleAlarm(operation.id)
        }
    }

    private fun cancelWakeups(id: PermanentDeletionId) {
        timers.remove(id.value)?.cancel()
        alarms.cancel(id)
    }

    private fun eligible(generation: Long): Boolean =
        fenceGeneration.get() == generation && roleState.isRoleHeld()

    private fun nextTimestamp(operation: PermanentDeletionOperation): Long =
        clock.wallMillis().coerceAtLeast(operation.updatedTimestampMillis + 1L)

    private fun isExcessivelyLate(operation: PermanentDeletionOperation): Boolean =
        clock.wallMillis() - operation.dueTimestampMillis > MAXIMUM_COMMIT_LATENESS_MILLIS

    private fun hasClockDrift(operation: PermanentDeletionOperation): Boolean {
        val elapsedDelta = clock.elapsedMillis() - operation.armedElapsedRealtimeMillis
        if (elapsedDelta < 0L || operation.armedWallTimestampMillis > Long.MAX_VALUE - elapsedDelta) {
            return true
        }
        val expectedWall = operation.armedWallTimestampMillis + elapsedDelta
        val now = clock.wallMillis()
        return if (now >= expectedWall) {
            now - expectedWall > MAXIMUM_CLOCK_DRIFT_MILLIS
        } else {
            expectedWall - now > MAXIMUM_CLOCK_DRIFT_MILLIS
        }
    }

    private data class Completion(
        val targetKind: PermanentDeletionTargetKind,
        val epoch: Long,
    ) {
        fun toObservation() = PermanentDeletionObservation.Completed(targetKind, epoch)
    }

    private enum class TargetInspection { ABSENT, MATCHES, CHANGED, UNAVAILABLE }

    companion object {
        internal const val MAXIMUM_CLOCK_DRIFT_MILLIS = 2_000L
        internal const val MAXIMUM_COMMIT_LATENESS_MILLIS = 30_000L
        private const val MAXIMUM_COMPLETION_SIGNALS = 128
    }

    private fun Map<Long, Completion>.boundedCompletions(): Map<Long, Completion> =
        if (size <= MAXIMUM_COMPLETION_SIGNALS) this else entries
            .sortedByDescending { it.value.epoch }
            .take(MAXIMUM_COMPLETION_SIGNALS)
            .associate { it.toPair() }
}

private fun PermanentDeletionOperation.toObservation(): PermanentDeletionObservation {
    val kind = target.targetKind()
    return when (phase) {
        PermanentDeletionPhase.PENDING -> PermanentDeletionObservation.Pending(
            targetKind = kind,
            providerMessageId = (target as? PermanentDeletionTarget.Message)?.providerMessageId,
            dueTimestampMillis = dueTimestampMillis,
        )
        PermanentDeletionPhase.COMMITTING -> PermanentDeletionObservation.Committing(kind)
        PermanentDeletionPhase.REVIEW_REQUIRED -> PermanentDeletionObservation.ReviewRequired(
            targetKind = kind,
            reason = checkNotNull(reviewReason),
            commitMayHaveStarted = reviewReason ==
                PermanentDeletionReviewReason.INTERRUPTED_DURING_COMMIT,
        )
    }
}

private fun PermanentDeletionTarget.targetKind(): PermanentDeletionTargetKind = when (this) {
    is PermanentDeletionTarget.Message -> PermanentDeletionTargetKind.MESSAGE
    is PermanentDeletionTarget.Thread -> PermanentDeletionTargetKind.THREAD
}

private fun ProviderThreadDeletionSnapshot.toStateTarget() = PermanentDeletionTarget.Thread(
    providerThreadId = providerThreadId,
    smsCount = smsCount,
    latestSmsId = latestSmsId,
    mmsCount = mmsCount,
    latestMmsId = latestMmsId,
)

private fun PermanentDeletionTarget.Thread.toProviderSnapshot() = ProviderThreadDeletionSnapshot(
    providerThreadId = providerThreadId,
    smsCount = smsCount,
    latestSmsId = latestSmsId,
    mmsCount = mmsCount,
    latestMmsId = latestMmsId,
)

private fun PermanentDeletionTarget.Message.toProviderTarget() = ProviderMessageDeletionTarget(
    providerMessageId = providerMessageId,
    providerThreadId = providerThreadId,
    syncFingerprint = syncFingerprint,
)
