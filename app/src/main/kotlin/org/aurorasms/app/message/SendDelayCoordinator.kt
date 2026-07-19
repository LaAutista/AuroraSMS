// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.aurorasms.core.index.conversation.ConversationLookupResult
import org.aurorasms.core.index.conversation.ConversationRepository
import org.aurorasms.core.index.conversation.VerifiedConversationIdentity
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.ConversationSubscriptionParticipantSetKey
import org.aurorasms.core.state.ConversationSubscriptionPreferenceRepository
import org.aurorasms.core.state.ConversationSubscriptionRepositoryResult
import org.aurorasms.core.state.ConversationSubscriptionScope
import org.aurorasms.core.state.MAXIMUM_SEND_DELAY_MILLIS
import org.aurorasms.core.state.MINIMUM_SEND_DELAY_MILLIS
import org.aurorasms.core.state.SendDelayDispatchReconciliation
import org.aurorasms.core.state.SendDelayId
import org.aurorasms.core.state.SendDelayOperation
import org.aurorasms.core.state.SendDelayParticipantSetKey
import org.aurorasms.core.state.SendDelayPhase
import org.aurorasms.core.state.SendDelayRepository
import org.aurorasms.core.state.SendDelayRequest
import org.aurorasms.core.state.SendDelayResult
import org.aurorasms.core.state.SendDelayReviewReason
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.SubscriptionRepository

internal interface SendDelayClock {
    fun wallMillis(): Long
    fun elapsedMillis(): Long
}

internal object AndroidSendDelayClock : SendDelayClock {
    override fun wallMillis(): Long = System.currentTimeMillis()
    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
}

internal class SendDelayCoordinator(
    private val applicationScope: CoroutineScope,
    private val roleState: DefaultSmsRoleState,
    private val conversations: ConversationRepository,
    private val subscriptions: SubscriptionRepository,
    private val subscriptionPreferences: ConversationSubscriptionPreferenceRepository,
    private val repository: SendDelayRepository,
    private val alarms: SendDelayAlarmDriver,
    private val sender: ThreadSmsSendController,
    private val segmentCounter: SmsSegmentCounter = AndroidSmsSegmentCounter,
    private val clock: SendDelayClock = AndroidSendDelayClock,
) : SendDelayController {
    private val mutex = Mutex()
    private val fenceGeneration = AtomicLong(0L)
    private val timers = ConcurrentHashMap<Long, Job>()

    override fun observe(providerThreadId: ProviderThreadId): Flow<SendDelayObservation> =
        repository.observeByThread(providerThreadId).map { result ->
            when (result) {
                is SendDelayResult.Success -> result.value?.toObservation()
                    ?: SendDelayObservation.None
                else -> SendDelayObservation.Loading
            }
        }.distinctUntilChanged()

    override suspend fun enqueue(command: SendDelayCommand): SendDelayAttempt = mutex.withLock {
        val generation = fenceGeneration.get()
        val now = clock.wallMillis().coerceAtLeast(0L)
        if (
            command.delayMillis !in MINIMUM_SEND_DELAY_MILLIS..MAXIMUM_SEND_DELAY_MILLIS ||
            now > Long.MAX_VALUE - command.delayMillis ||
            !eligible(generation) ||
            !revalidate(command.identity, command.subscriptionId)
        ) {
            return@withLock SendDelayAttempt.REFUSED
        }
        val key = runCatching {
            SendDelayParticipantSetKey.fromParticipants(command.identity.participants)
        }.getOrNull() ?: return@withLock SendDelayAttempt.REFUSED
        val due = now + command.delayMillis
        val create = repository.create(
            SendDelayRequest(
                participantSetKey = key,
                providerThreadId = command.identity.providerThreadId,
                draftId = command.draftId,
                expectedDraftRevision = command.draftRevision,
                subscriptionId = command.subscriptionId,
                dueTimestampMillis = due,
                createdTimestampMillis = now,
                armedElapsedRealtimeMillis = clock.elapsedMillis().coerceAtLeast(0L),
            ),
        )
        val reservation = (create as? SendDelayResult.Success)?.value
        val operation = reservation?.operation ?: when (create) {
            is SendDelayResult.StorageFailure,
            SendDelayResult.CorruptData,
            -> when (val read = repository.readByThread(command.identity.providerThreadId)) {
                is SendDelayResult.Success -> read.value.takeIf { existing ->
                    existing.draftId == command.draftId &&
                        existing.draftRevision == command.draftRevision &&
                        existing.subscriptionId == command.subscriptionId &&
                        existing.dueTimestampMillis == due &&
                        existing.participantSetKey == key
                } ?: return@withLock SendDelayAttempt.REFUSED
                SendDelayResult.NotFound -> return@withLock SendDelayAttempt.REFUSED
                else -> return@withLock SendDelayAttempt.ACCEPTED
            }
            else -> return@withLock SendDelayAttempt.REFUSED
        }
        if (reservation != null && segmentCounter.count(reservation.authoritativeBody) != 1) {
            repository.remove(operation.id, operation.revision)
            return@withLock SendDelayAttempt.REFUSED
        }
        if (!alarms.arm(operation.id, operation.dueTimestampMillis)) {
            review(operation, SendDelayReviewReason.ARMING_FAILED)
            return@withLock SendDelayAttempt.ACCEPTED
        }
        scheduleTimer(operation)
        SendDelayAttempt.ACCEPTED
    }

    override suspend fun undo(providerThreadId: ProviderThreadId): Boolean = mutex.withLock {
        val operation = (repository.readByThread(providerThreadId) as? SendDelayResult.Success)
            ?.value ?: return@withLock false
        if (operation.phase == SendDelayPhase.DISPATCHING) return@withLock false
        val removed = repository.remove(operation.id, operation.revision) is SendDelayResult.Success
        if (removed) cancelWakeups(operation.id)
        removed
    }

    override suspend fun handleAlarm(id: SendDelayId) = mutex.withLock {
        timers.remove(id.value)
        handleAlarmLocked(id)
    }

    private suspend fun handleAlarmLocked(id: SendDelayId) {
        var operation = (repository.read(id) as? SendDelayResult.Success)?.value ?: return
        if (operation.phase != SendDelayPhase.PENDING) return
        val now = clock.wallMillis().coerceAtLeast(0L)
        if (hasClockDrift(operation)) {
            review(operation, SendDelayReviewReason.CLOCK_CHANGED)
            return
        }
        if (now < operation.dueTimestampMillis) {
            rearm(operation)
            return
        }
        if (now - operation.dueTimestampMillis > MAXIMUM_DISPATCH_LATENESS_MILLIS) {
            review(operation, SendDelayReviewReason.MISSED_AFTER_RESTART)
            return
        }
        val identity = revalidateOperation(operation) ?: run {
            review(operation, SendDelayReviewReason.PRECONDITION_FAILED)
            return
        }
        when (sender.recover()) {
            ThreadSmsRecoveryResult.READY,
            ThreadSmsRecoveryResult.READY_WITH_DEFERRED_OPERATIONS,
            -> Unit
            ThreadSmsRecoveryResult.DEFERRED,
            ThreadSmsRecoveryResult.STORAGE_BLOCKED,
            -> {
                review(operation, SendDelayReviewReason.PRECONDITION_FAILED)
                return
            }
        }
        operation = (repository.markDispatching(
            operation.id,
            operation.revision,
            nextTimestamp(operation),
        ) as? SendDelayResult.Success)?.value ?: return
        cancelWakeups(operation.id)
        val attempt = try {
            withContext(NonCancellable) {
                sender.send(
                    ThreadSmsSendCommand(
                        identity = identity,
                        subscriptionId = operation.subscriptionId,
                        draftId = operation.draftId,
                        draftRevision = operation.draftRevision,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            ThreadSmsSendAttempt.STARTED
        }
        if (attempt == ThreadSmsSendAttempt.REFUSED) {
            review(operation, SendDelayReviewReason.PRECONDITION_FAILED)
        } else {
            reconcileOne(operation.id)
        }
    }

    override suspend fun recover(reason: SendDelayRecoveryReason): Unit = mutex.withLock {
        val operations = (repository.recoverySnapshot() as? SendDelayResult.Success)?.value
            ?: return@withLock
        operations.forEach { operation ->
            when (operation.phase) {
                SendDelayPhase.REVIEW_REQUIRED -> cancelWakeups(operation.id)
                SendDelayPhase.DISPATCHING -> reconcileOne(operation.id)
                SendDelayPhase.PENDING -> when {
                    reason == SendDelayRecoveryReason.TIME_CHANGED ->
                        review(operation, SendDelayReviewReason.CLOCK_CHANGED)
                    hasClockDrift(operation) ->
                        review(operation, SendDelayReviewReason.CLOCK_CHANGED)
                    !roleState.isRoleHeld() ->
                        review(operation, SendDelayReviewReason.PRECONDITION_FAILED)
                    operation.dueTimestampMillis <= clock.wallMillis() &&
                        clock.wallMillis() - operation.dueTimestampMillis >
                        MAXIMUM_DISPATCH_LATENESS_MILLIS ->
                        review(operation, SendDelayReviewReason.MISSED_AFTER_RESTART)
                    operation.dueTimestampMillis <= clock.wallMillis() ->
                        handleAlarmLocked(operation.id)
                    else -> rearm(operation)
                }
            }
        }
    }

    override suspend fun reconcileDispatches(): Unit = mutex.withLock {
        val operations = (repository.recoverySnapshot() as? SendDelayResult.Success)?.value
            ?: return@withLock
        operations.filter { it.phase == SendDelayPhase.DISPATCHING }
            .forEach { reconcileOne(it.id) }
    }

    override fun fence() {
        fenceGeneration.incrementAndGet()
        timers.values.forEach(Job::cancel)
        timers.clear()
        applicationScope.launch {
            recover(SendDelayRecoveryReason.ROLE_CHANGED)
        }
    }

    private suspend fun reconcileOne(id: SendDelayId) {
        when (val result = repository.reconcileDispatch(id, clock.wallMillis().coerceAtLeast(1L))) {
            is SendDelayResult.Success -> if (
                result.value != SendDelayDispatchReconciliation.IN_PROGRESS
            ) {
                cancelWakeups(id)
            }
            else -> Unit
        }
    }

    private suspend fun rearm(operation: SendDelayOperation) {
        if (!alarms.arm(operation.id, operation.dueTimestampMillis)) {
            review(operation, SendDelayReviewReason.ARMING_FAILED)
            return
        }
        scheduleTimer(operation)
    }

    private fun scheduleTimer(operation: SendDelayOperation) {
        timers.remove(operation.id.value)?.cancel()
        val job = applicationScope.launch {
            val wait = (operation.dueTimestampMillis - clock.wallMillis()).coerceAtLeast(0L)
            delay(wait)
            handleAlarm(operation.id)
        }
        timers[operation.id.value] = job
    }

    private fun cancelWakeups(id: SendDelayId) {
        timers.remove(id.value)?.cancel()
        alarms.cancel(id)
    }

    private suspend fun review(operation: SendDelayOperation, reason: SendDelayReviewReason) {
        val reviewed = repository.markReviewRequired(
            operation.id,
            operation.revision,
            reason,
            nextTimestamp(operation),
        )
        if (reviewed is SendDelayResult.Success) cancelWakeups(operation.id)
    }

    private suspend fun revalidateOperation(
        operation: SendDelayOperation,
    ): VerifiedConversationIdentity? {
        val found = conversations.loadConversation(operation.providerThreadId)
            as? ConversationLookupResult.Found ?: return null
        val identity = found.verifiedIdentity ?: return null
        if (
            identity.participants.size != 1 ||
            SendDelayParticipantSetKey.fromParticipants(identity.participants) !=
            operation.participantSetKey ||
            !revalidate(identity, operation.subscriptionId)
        ) {
            return null
        }
        return identity
    }

    private suspend fun revalidate(
        identity: VerifiedConversationIdentity,
        subscriptionId: AuroraSubscriptionId,
    ): Boolean {
        val found = conversations.loadConversation(identity.providerThreadId)
            as? ConversationLookupResult.Found ?: return false
        if (found.verifiedIdentity != identity || identity.participants.size != 1) return false
        val scope = runCatching {
            ConversationSubscriptionScope(
                ConversationSubscriptionParticipantSetKey.fromParticipants(identity.participants),
                identity.providerThreadId,
            )
        }.getOrNull() ?: return false
        val authoritative = when (val preference = subscriptionPreferences.read(scope)) {
            is ConversationSubscriptionRepositoryResult.Success -> preference.value.subscriptionId
            ConversationSubscriptionRepositoryResult.NotFound -> found.summary.latestSubscriptionId
            else -> null
        }
        return authoritative == subscriptionId &&
            subscriptions.findActive(subscriptionId)?.smsCapable == true &&
            roleState.isRoleHeld()
    }

    private fun eligible(generation: Long): Boolean =
        fenceGeneration.get() == generation && roleState.isRoleHeld()

    private fun nextTimestamp(operation: SendDelayOperation): Long =
        clock.wallMillis().coerceAtLeast(operation.updatedTimestampMillis + 1L)

    private fun hasClockDrift(operation: SendDelayOperation): Boolean {
        val elapsed = clock.elapsedMillis()
        val elapsedDelta = elapsed - operation.armedElapsedRealtimeMillis
        if (
            elapsedDelta < 0L ||
            operation.armedWallTimestampMillis > Long.MAX_VALUE - elapsedDelta
        ) {
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

    private fun SendDelayOperation.toObservation(): SendDelayObservation = when (phase) {
        SendDelayPhase.PENDING -> SendDelayObservation.Pending(dueTimestampMillis)
        SendDelayPhase.DISPATCHING -> SendDelayObservation.Dispatching(dueTimestampMillis)
        SendDelayPhase.REVIEW_REQUIRED -> SendDelayObservation.ReviewRequired(dueTimestampMillis)
    }

    companion object {
        internal const val MAXIMUM_CLOCK_DRIFT_MILLIS = 2_000L
        internal const val MAXIMUM_DISPATCH_LATENESS_MILLIS = 30_000L
    }
}
