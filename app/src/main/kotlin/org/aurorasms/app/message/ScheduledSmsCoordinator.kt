// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.aurorasms.core.index.conversation.ConversationLookupResult
import org.aurorasms.core.index.conversation.ConversationRepository
import org.aurorasms.core.index.conversation.VerifiedConversationIdentity
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.ConversationSubscriptionParticipantSetKey
import org.aurorasms.core.state.ConversationSubscriptionPreferenceRepository
import org.aurorasms.core.state.ConversationSubscriptionRepositoryResult
import org.aurorasms.core.state.ConversationSubscriptionScope
import org.aurorasms.core.state.ScheduledSms
import org.aurorasms.core.state.ScheduledSmsDispatchReconciliation
import org.aurorasms.core.state.ScheduledSmsId
import org.aurorasms.core.state.ScheduledSmsParticipantSetKey
import org.aurorasms.core.state.ScheduledSmsPhase
import org.aurorasms.core.state.ScheduledSmsPrecision
import org.aurorasms.core.state.ScheduledSmsRepository
import org.aurorasms.core.state.ScheduledSmsRequest
import org.aurorasms.core.state.ScheduledSmsResult
import org.aurorasms.core.state.ScheduledSmsReviewReason
import org.aurorasms.core.state.resolveOutgoingBody
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.SubscriptionRepository

internal interface ScheduledSmsClock {
    fun wallMillis(): Long
    fun elapsedMillis(): Long
}

internal object AndroidScheduledSmsClock : ScheduledSmsClock {
    override fun wallMillis(): Long = System.currentTimeMillis()
    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
}

internal class ScheduledSmsCoordinator(
    private val roleState: DefaultSmsRoleState,
    private val conversations: ConversationRepository,
    private val subscriptions: SubscriptionRepository,
    private val subscriptionPreferences: ConversationSubscriptionPreferenceRepository,
    private val repository: ScheduledSmsRepository,
    private val alarms: ScheduledSmsAlarmDriver,
    private val sender: ThreadSmsSendController,
    private val segmentCounter: SmsSegmentCounter = AndroidSmsSegmentCounter,
    private val clock: ScheduledSmsClock = AndroidScheduledSmsClock,
) : ScheduledSmsController {
    private val mutex = Mutex()
    private val fenceGeneration = AtomicLong(0L)

    override fun observe(providerThreadId: ProviderThreadId): Flow<ScheduledSmsObservation> =
        repository.observeByThread(providerThreadId).map { result ->
            when (result) {
                is ScheduledSmsResult.Success -> result.value?.toObservation()
                    ?: ScheduledSmsObservation.None
                else -> ScheduledSmsObservation.Loading
            }
        }.distinctUntilChanged()

    override suspend fun schedule(command: ScheduledSmsCommand): ScheduledSmsAttempt =
        mutex.withLock {
            val generation = fenceGeneration.get()
            val now = clock.wallMillis()
            if (
                command.dueTimestampMillis < now + MINIMUM_LEAD_MILLIS ||
                command.dueTimestampMillis > now + MAXIMUM_LEAD_MILLIS ||
                !eligible(generation) ||
                !revalidate(command.identity, command.subscriptionId)
            ) {
                return@withLock ScheduledSmsAttempt.REFUSED
            }
            val key = runCatching {
                ScheduledSmsParticipantSetKey.fromParticipants(command.identity.participants)
            }.getOrNull() ?: return@withLock ScheduledSmsAttempt.REFUSED
            val createResult = repository.create(
                ScheduledSmsRequest(
                    participantSetKey = key,
                    providerThreadId = command.identity.providerThreadId,
                    draftId = command.draftId,
                    expectedDraftRevision = command.draftRevision,
                    subscriptionId = command.subscriptionId,
                    dueTimestampMillis = command.dueTimestampMillis,
                    createdTimestampMillis = now,
                    armedElapsedRealtimeMillis = clock.elapsedMillis(),
                    frozenSignature = command.frozenSignature,
                ),
            )
            val reservation = (createResult as? ScheduledSmsResult.Success)?.value
            val schedule = reservation?.schedule ?: when (createResult) {
                is ScheduledSmsResult.StorageFailure,
                ScheduledSmsResult.CorruptData,
                -> when (val read = repository.readByThread(command.identity.providerThreadId)) {
                    is ScheduledSmsResult.Success -> read.value.takeIf { existing ->
                        existing.draftId == command.draftId &&
                            existing.draftRevision == command.draftRevision &&
                            existing.subscriptionId == command.subscriptionId &&
                            existing.dueTimestampMillis == command.dueTimestampMillis &&
                            existing.participantSetKey == key &&
                            existing.frozenSignature == command.frozenSignature
                    } ?: return@withLock ScheduledSmsAttempt.REFUSED
                    ScheduledSmsResult.NotFound -> return@withLock ScheduledSmsAttempt.REFUSED
                    else -> return@withLock ScheduledSmsAttempt.ACCEPTED
                }
                else -> return@withLock ScheduledSmsAttempt.REFUSED
            }
            if (
                reservation != null &&
                resolveOutgoingBody(
                    reservation.authoritativeBody,
                    schedule.frozenSignature,
                )?.let(segmentCounter::count) != 1
            ) {
                repository.remove(schedule.id, schedule.revision)
                return@withLock ScheduledSmsAttempt.REFUSED
            }
            val armResult = alarms.arm(schedule.id, schedule.dueTimestampMillis)
            val updated = nextTimestamp(schedule)
            if (armResult == ScheduledAlarmArmResult.FAILED) {
                repository.markReviewRequired(
                    schedule.id,
                    schedule.revision,
                    ScheduledSmsReviewReason.ARMING_FAILED,
                    updated,
                )
                return@withLock ScheduledSmsAttempt.ACCEPTED
            }
            val precision = if (armResult == ScheduledAlarmArmResult.EXACT) {
                ScheduledSmsPrecision.EXACT
            } else {
                ScheduledSmsPrecision.INEXACT
            }
            when (
                repository.markArmed(
                    id = schedule.id,
                    expectedRevision = schedule.revision,
                    precision = precision,
                    armedWallTimestampMillis = clock.wallMillis(),
                    armedElapsedRealtimeMillis = clock.elapsedMillis(),
                    updatedTimestampMillis = updated,
                )
            ) {
                is ScheduledSmsResult.Success -> ScheduledSmsAttempt.ACCEPTED
                else -> {
                    alarms.cancel(schedule.id)
                    ScheduledSmsAttempt.ACCEPTED
                }
            }
        }

    override suspend fun cancel(providerThreadId: ProviderThreadId): Boolean = mutex.withLock {
        val schedule = (repository.readByThread(providerThreadId) as? ScheduledSmsResult.Success)
            ?.value ?: return@withLock false
        if (schedule.phase == ScheduledSmsPhase.DISPATCHING) return@withLock false
        val removed = repository.remove(schedule.id, schedule.revision) is ScheduledSmsResult.Success
        if (removed) alarms.cancel(schedule.id)
        removed
    }

    override suspend fun handleAlarm(id: ScheduledSmsId) = mutex.withLock {
        handleAlarmLocked(id)
    }

    private suspend fun handleAlarmLocked(id: ScheduledSmsId) {
        var schedule = (repository.read(id) as? ScheduledSmsResult.Success)?.value ?: return
        if (schedule.phase != ScheduledSmsPhase.PENDING) return
        val now = clock.wallMillis()
        val elapsed = clock.elapsedMillis()
        val elapsedDelta = elapsed - schedule.armedElapsedRealtimeMillis
        val expectedWall = expectedWallTimestamp(schedule, elapsedDelta)
        if (
            expectedWall == null ||
            absoluteDifferenceExceeds(now, expectedWall, MAXIMUM_CLOCK_DRIFT_MILLIS)
        ) {
            review(schedule, ScheduledSmsReviewReason.CLOCK_CHANGED)
            return
        }
        if (now < schedule.dueTimestampMillis) {
            rearm(schedule)
            return
        }
        val maximumLateness = if (schedule.precision == ScheduledSmsPrecision.EXACT) {
            MAXIMUM_EXACT_LATENESS_MILLIS
        } else {
            MAXIMUM_INEXACT_LATENESS_MILLIS
        }
        if (now - schedule.dueTimestampMillis > maximumLateness) {
            review(schedule, ScheduledSmsReviewReason.MISSED_AFTER_RESTART)
            return
        }
        val identity = revalidateSchedule(schedule) ?: run {
            review(schedule, ScheduledSmsReviewReason.PRECONDITION_FAILED)
            return
        }
        when (sender.recover()) {
            ThreadSmsRecoveryResult.READY,
            ThreadSmsRecoveryResult.READY_WITH_DEFERRED_OPERATIONS,
            -> Unit
            ThreadSmsRecoveryResult.DEFERRED,
            ThreadSmsRecoveryResult.STORAGE_BLOCKED,
            -> {
                review(schedule, ScheduledSmsReviewReason.PRECONDITION_FAILED)
                return
            }
        }
        schedule = (repository.markDispatching(
            schedule.id,
            schedule.revision,
            nextTimestamp(schedule),
        ) as? ScheduledSmsResult.Success)?.value ?: return
        alarms.cancel(schedule.id)
        val attempt = sender.send(
            ThreadSmsSendCommand(
                identity = identity,
                subscriptionId = schedule.subscriptionId,
                draftId = schedule.draftId,
                draftRevision = schedule.draftRevision,
                frozenSignature = schedule.frozenSignature,
            ),
        )
        if (attempt == ThreadSmsSendAttempt.REFUSED) {
            review(schedule, ScheduledSmsReviewReason.PRECONDITION_FAILED)
        } else {
            reconcileOne(schedule.id)
        }
    }

    override suspend fun recover(reason: ScheduledSmsRecoveryReason): Unit = mutex.withLock {
        val schedules = (repository.recoverySnapshot() as? ScheduledSmsResult.Success)?.value
            ?: return@withLock
        schedules.forEach { schedule ->
            when (schedule.phase) {
                ScheduledSmsPhase.REVIEW_REQUIRED -> alarms.cancel(schedule.id)
                ScheduledSmsPhase.DISPATCHING -> reconcileOne(schedule.id)
                ScheduledSmsPhase.PENDING -> when {
                    reason == ScheduledSmsRecoveryReason.APP_STARTUP &&
                        (
                            schedule.dueTimestampMillis <= clock.wallMillis() ||
                                clock.elapsedMillis() < schedule.armedElapsedRealtimeMillis
                            ) -> Unit
                    reason == ScheduledSmsRecoveryReason.BOOT_COMPLETED &&
                        schedule.dueTimestampMillis <= clock.wallMillis() ->
                        review(schedule, ScheduledSmsReviewReason.MISSED_AFTER_RESTART)
                    reason == ScheduledSmsRecoveryReason.WALL_CLOCK_CHANGED ->
                        review(schedule, ScheduledSmsReviewReason.CLOCK_CHANGED)
                    hasClockDrift(schedule) ->
                        review(schedule, ScheduledSmsReviewReason.CLOCK_CHANGED)
                    schedule.dueTimestampMillis <= clock.wallMillis() ->
                        review(schedule, ScheduledSmsReviewReason.MISSED_AFTER_RESTART)
                    else -> rearm(schedule)
                }
            }
        }
    }

    override suspend fun reconcileDispatches(): Unit = mutex.withLock {
        val schedules = (repository.recoverySnapshot() as? ScheduledSmsResult.Success)?.value
            ?: return@withLock
        schedules.filter { it.phase == ScheduledSmsPhase.DISPATCHING }
            .forEach { reconcileOne(it.id) }
    }

    override fun fence() {
        fenceGeneration.incrementAndGet()
    }

    private suspend fun reconcileOne(id: ScheduledSmsId) {
        when (
            val result = repository.reconcileDispatch(id, clock.wallMillis().coerceAtLeast(1L))
        ) {
            is ScheduledSmsResult.Success -> if (
                result.value != ScheduledSmsDispatchReconciliation.IN_PROGRESS
            ) {
                alarms.cancel(id)
            }
            else -> Unit
        }
    }

    private suspend fun rearm(schedule: ScheduledSms) {
        val armResult = alarms.arm(schedule.id, schedule.dueTimestampMillis)
        if (armResult == ScheduledAlarmArmResult.FAILED) {
            review(schedule, ScheduledSmsReviewReason.ARMING_FAILED)
            return
        }
        val precision = if (armResult == ScheduledAlarmArmResult.EXACT) {
            ScheduledSmsPrecision.EXACT
        } else {
            ScheduledSmsPrecision.INEXACT
        }
        repository.markArmed(
            id = schedule.id,
            expectedRevision = schedule.revision,
            precision = precision,
            armedWallTimestampMillis = clock.wallMillis(),
            armedElapsedRealtimeMillis = clock.elapsedMillis(),
            updatedTimestampMillis = nextTimestamp(schedule),
        )
    }

    private suspend fun review(schedule: ScheduledSms, reason: ScheduledSmsReviewReason) {
        val reviewed = repository.markReviewRequired(
            schedule.id,
            schedule.revision,
            reason,
            nextTimestamp(schedule),
        )
        if (reviewed is ScheduledSmsResult.Success) alarms.cancel(schedule.id)
    }

    private suspend fun revalidateSchedule(schedule: ScheduledSms): VerifiedConversationIdentity? {
        val found = conversations.loadConversation(schedule.providerThreadId)
            as? ConversationLookupResult.Found ?: return null
        val identity = found.verifiedIdentity ?: return null
        if (
            identity.participants.size != 1 ||
            ScheduledSmsParticipantSetKey.fromParticipants(identity.participants) !=
            schedule.participantSetKey ||
            !revalidate(identity, schedule.subscriptionId)
        ) {
            return null
        }
        return identity
    }

    private suspend fun revalidate(
        identity: VerifiedConversationIdentity,
        subscriptionId: org.aurorasms.core.model.AuroraSubscriptionId,
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
            subscriptions.findActive(subscriptionId)?.smsCapable == true && roleState.isRoleHeld()
    }

    private fun eligible(generation: Long): Boolean =
        fenceGeneration.get() == generation && roleState.isRoleHeld()

    private fun nextTimestamp(schedule: ScheduledSms): Long =
        clock.wallMillis().coerceAtLeast(schedule.updatedTimestampMillis + 1L)

    private fun hasClockDrift(schedule: ScheduledSms): Boolean {
        val elapsedDelta = clock.elapsedMillis() - schedule.armedElapsedRealtimeMillis
        val expectedWall = expectedWallTimestamp(schedule, elapsedDelta) ?: return true
        return absoluteDifferenceExceeds(
            clock.wallMillis(),
            expectedWall,
            MAXIMUM_CLOCK_DRIFT_MILLIS,
        )
    }

    private fun expectedWallTimestamp(schedule: ScheduledSms, elapsedDelta: Long): Long? {
        if (
            elapsedDelta < 0L ||
            schedule.armedWallTimestampMillis > Long.MAX_VALUE - elapsedDelta
        ) {
            return null
        }
        return schedule.armedWallTimestampMillis + elapsedDelta
    }

    private fun absoluteDifferenceExceeds(first: Long, second: Long, limit: Long): Boolean =
        if (first >= second) first - second > limit else second - first > limit

    private fun ScheduledSms.toObservation(): ScheduledSmsObservation = when (phase) {
        ScheduledSmsPhase.PENDING -> ScheduledSmsObservation.Pending(dueTimestampMillis, precision)
        ScheduledSmsPhase.DISPATCHING -> ScheduledSmsObservation.Dispatching(dueTimestampMillis)
        ScheduledSmsPhase.REVIEW_REQUIRED -> ScheduledSmsObservation.ReviewRequired(
            dueTimestampMillis,
            checkNotNull(reviewReason),
        )
    }

    companion object {
        internal const val MINIMUM_LEAD_MILLIS = 2L * 60L * 1_000L
        internal const val MAXIMUM_LEAD_MILLIS = 365L * 24L * 60L * 60L * 1_000L
        internal const val MAXIMUM_CLOCK_DRIFT_MILLIS = 2L * 60L * 1_000L
        internal const val MAXIMUM_EXACT_LATENESS_MILLIS = 10L * 60L * 1_000L
        internal const val MAXIMUM_INEXACT_LATENESS_MILLIS = 75L * 60L * 1_000L
    }
}
