// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.aurorasms.core.index.IndexCoverage
import org.aurorasms.core.index.IndexRunState
import org.aurorasms.core.index.conversation.ConversationInvalidation
import org.aurorasms.core.index.conversation.ConversationLookupResult
import org.aurorasms.core.index.conversation.ConversationPageRequest
import org.aurorasms.core.index.conversation.ConversationPageResult
import org.aurorasms.core.index.conversation.ConversationRepository
import org.aurorasms.core.index.conversation.ConversationSummary
import org.aurorasms.core.index.conversation.VerifiedConversationIdentity
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.state.ConversationSubscriptionPreference
import org.aurorasms.core.state.ConversationSubscriptionPreferenceRepository
import org.aurorasms.core.state.ConversationSubscriptionRepositoryResult
import org.aurorasms.core.state.ConversationSubscriptionRevision
import org.aurorasms.core.state.ConversationSubscriptionScope
import org.aurorasms.core.state.DraftId
import org.aurorasms.core.state.DraftRevision
import org.aurorasms.core.state.SendDelayDispatchReconciliation
import org.aurorasms.core.state.SendDelayId
import org.aurorasms.core.state.SendDelayOperation
import org.aurorasms.core.state.SendDelayPhase
import org.aurorasms.core.state.SendDelayRepository
import org.aurorasms.core.state.SendDelayRequest
import org.aurorasms.core.state.SendDelayReservation
import org.aurorasms.core.state.SendDelayResult
import org.aurorasms.core.state.SendDelayReviewReason
import org.aurorasms.core.state.SendDelayRevision
import org.aurorasms.core.telephony.ActiveSubscription
import org.aurorasms.core.telephony.SubscriptionSnapshot
import org.aurorasms.core.testing.FakeRoleState
import org.aurorasms.core.testing.FakeSubscriptionRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class SendDelayCoordinatorTest {
    @Test
    fun enqueueArmsDurableShortDelayAndUndoPreventsSend() = runTest {
        val fixture = fixture(backgroundScope)

        assertEquals(SendDelayAttempt.ACCEPTED, fixture.coordinator.enqueue(command()))
        assertEquals(listOf(DUE), fixture.alarms.armedDueTimes)
        assertEquals(true, fixture.coordinator.undo(THREAD))

        fixture.clock.wall = DUE
        fixture.clock.elapsed += DELAY
        fixture.coordinator.handleAlarm(SendDelayId(1L))
        assertEquals(0, fixture.sender.sendCount)
        assertEquals(null, fixture.repository.operation)
    }

    @Test
    fun duplicateAlarmAfterCompletedDispatchNeverSendsTwice() = runTest {
        val fixture = fixture(backgroundScope)
        assertEquals(SendDelayAttempt.ACCEPTED, fixture.coordinator.enqueue(command()))
        fixture.clock.wall = DUE
        fixture.clock.elapsed += DELAY

        fixture.coordinator.handleAlarm(SendDelayId(1L))
        fixture.coordinator.handleAlarm(SendDelayId(1L))

        assertEquals(1, fixture.sender.sendCount)
        assertEquals(null, fixture.repository.operation)
    }

    @Test
    fun dueAlarmAfterProcessRestartUsesDurableOperation() = runTest {
        val original = fixture(backgroundScope)
        assertEquals(SendDelayAttempt.ACCEPTED, original.coordinator.enqueue(command()))
        original.clock.wall = DUE
        original.clock.elapsed += DELAY
        val restartedSender = DelayRecordingSender()
        val restarted = coordinator(
            scope = backgroundScope,
            repository = original.repository,
            alarms = original.alarms,
            sender = restartedSender,
            subscriptions = original.subscriptions,
            role = original.role,
            clock = original.clock,
        )

        restarted.handleAlarm(SendDelayId(1L))

        assertEquals(1, restartedSender.sendCount)
        assertEquals(null, original.repository.operation)
    }

    @Test
    fun clockChangeAndLateRestartFailClosedForReview() = runTest {
        val drifted = fixture(backgroundScope)
        assertEquals(SendDelayAttempt.ACCEPTED, drifted.coordinator.enqueue(command()))
        drifted.clock.wall = DUE
        drifted.coordinator.handleAlarm(SendDelayId(1L))
        assertEquals(SendDelayReviewReason.CLOCK_CHANGED, drifted.repository.operation?.reviewReason)
        assertEquals(0, drifted.sender.sendCount)

        val late = fixture(backgroundScope)
        assertEquals(SendDelayAttempt.ACCEPTED, late.coordinator.enqueue(command()))
        late.clock.wall = DUE + SendDelayCoordinator.MAXIMUM_DISPATCH_LATENESS_MILLIS + 1L
        late.clock.elapsed += DELAY + SendDelayCoordinator.MAXIMUM_DISPATCH_LATENESS_MILLIS + 1L
        late.coordinator.recover(SendDelayRecoveryReason.APP_STARTUP)
        assertEquals(
            SendDelayReviewReason.MISSED_AFTER_RESTART,
            late.repository.operation?.reviewReason,
        )
        assertEquals(0, late.sender.sendCount)
    }

    @Test
    fun removedSubscriptionAndLostRoleNeverSend() = runTest {
        val removedSim = fixture(backgroundScope)
        assertEquals(SendDelayAttempt.ACCEPTED, removedSim.coordinator.enqueue(command()))
        removedSim.subscriptions.snapshot = SubscriptionSnapshot.Available(emptyList())
        removedSim.clock.wall = DUE
        removedSim.clock.elapsed += DELAY
        removedSim.coordinator.handleAlarm(SendDelayId(1L))
        assertEquals(
            SendDelayReviewReason.PRECONDITION_FAILED,
            removedSim.repository.operation?.reviewReason,
        )
        assertEquals(0, removedSim.sender.sendCount)

        val lostRole = fixture(backgroundScope)
        assertEquals(SendDelayAttempt.ACCEPTED, lostRole.coordinator.enqueue(command()))
        lostRole.role.held = false
        lostRole.coordinator.recover(SendDelayRecoveryReason.ROLE_CHANGED)
        assertEquals(
            SendDelayReviewReason.PRECONDITION_FAILED,
            lostRole.repository.operation?.reviewReason,
        )
        assertEquals(0, lostRole.sender.sendCount)
    }

    @Test
    fun dispatchingCannotBeUndoneAndAlarmFailureLeavesReviewableDraft() = runTest {
        val dispatching = fixture(backgroundScope)
        assertEquals(SendDelayAttempt.ACCEPTED, dispatching.coordinator.enqueue(command()))
        val pending = checkNotNull(dispatching.repository.operation)
        dispatching.repository.markDispatching(pending.id, pending.revision, START + 1L)
        assertEquals(false, dispatching.coordinator.undo(THREAD))
        assertEquals(SendDelayPhase.DISPATCHING, dispatching.repository.operation?.phase)

        val armFailure = fixture(backgroundScope, armResult = false)
        assertEquals(SendDelayAttempt.ACCEPTED, armFailure.coordinator.enqueue(command()))
        assertEquals(
            SendDelayReviewReason.ARMING_FAILED,
            armFailure.repository.operation?.reviewReason,
        )
        assertEquals(0, armFailure.sender.sendCount)
    }

    @Test
    fun multiSegmentBodyIsRefusedAndDurableReservationRemoved() = runTest {
        val fixture = fixture(backgroundScope, segmentCount = 2)

        assertEquals(SendDelayAttempt.REFUSED, fixture.coordinator.enqueue(command()))

        assertEquals(null, fixture.repository.operation)
        assertEquals(emptyList<Long>(), fixture.alarms.armedDueTimes)
        assertEquals(0, fixture.sender.sendCount)
    }

    private fun fixture(
        scope: CoroutineScope,
        armResult: Boolean = true,
        segmentCount: Int = 1,
    ): DelayFixture {
        val repository = DelayRecordingRepository()
        val alarms = DelayRecordingAlarmDriver(armResult)
        val sender = DelayRecordingSender()
        val subscriptions = FakeSubscriptionRepository(
            SubscriptionSnapshot.Available(
                listOf(ActiveSubscription(SUBSCRIPTION, 0, "Synthetic SIM", true)),
            ),
        )
        val role = FakeRoleState()
        val clock = DelayMutableClock(START, 500L)
        return DelayFixture(
            coordinator = coordinator(
                scope,
                repository,
                alarms,
                sender,
                subscriptions,
                role,
                clock,
                segmentCount,
            ),
            repository = repository,
            alarms = alarms,
            sender = sender,
            subscriptions = subscriptions,
            role = role,
            clock = clock,
        )
    }

    private fun coordinator(
        scope: CoroutineScope,
        repository: DelayRecordingRepository,
        alarms: DelayRecordingAlarmDriver,
        sender: DelayRecordingSender,
        subscriptions: FakeSubscriptionRepository,
        role: FakeRoleState,
        clock: DelayMutableClock,
        segmentCount: Int = 1,
    ) = SendDelayCoordinator(
        applicationScope = scope,
        roleState = role,
        conversations = DelayConversationRepository,
        subscriptions = subscriptions,
        subscriptionPreferences = DelayMissingPreferenceRepository,
        repository = repository,
        alarms = alarms,
        sender = sender,
        segmentCounter = SmsSegmentCounter { segmentCount },
        clock = clock,
    )

    private fun command() = SendDelayCommand(
        identity = IDENTITY,
        subscriptionId = SUBSCRIPTION,
        draftId = DraftId(3L),
        draftRevision = DraftRevision(4L),
        delayMillis = DELAY,
    )

    private data class DelayFixture(
        val coordinator: SendDelayCoordinator,
        val repository: DelayRecordingRepository,
        val alarms: DelayRecordingAlarmDriver,
        val sender: DelayRecordingSender,
        val subscriptions: FakeSubscriptionRepository,
        val role: FakeRoleState,
        val clock: DelayMutableClock,
    )

    companion object {
        private const val START = 1_000L
        private const val DELAY = 5_000L
        private const val DUE = START + DELAY
        private val THREAD = ProviderThreadId(92L)
        internal val SUBSCRIPTION = AuroraSubscriptionId(1)
        internal val ADDRESS = ParticipantAddress("+15550000000")
        internal val IDENTITY = VerifiedConversationIdentity(THREAD, 1L, listOf(ADDRESS))
    }
}

private class DelayMutableClock(var wall: Long, var elapsed: Long) : SendDelayClock {
    override fun wallMillis() = wall
    override fun elapsedMillis() = elapsed
}

private class DelayRecordingAlarmDriver(private val result: Boolean) : SendDelayAlarmDriver {
    val armedDueTimes = mutableListOf<Long>()
    val cancelledIds = mutableListOf<SendDelayId>()
    override fun arm(id: SendDelayId, dueTimestampMillis: Long): Boolean {
        armedDueTimes += dueTimestampMillis
        return result
    }
    override fun cancel(id: SendDelayId) { cancelledIds += id }
}

private class DelayRecordingSender : ThreadSmsSendController {
    var sendCount = 0
    override fun observe(providerThreadId: ProviderThreadId) =
        MutableStateFlow(ThreadSmsSendObservation(ThreadSmsSendPhase.IDLE))
    override suspend fun send(command: ThreadSmsSendCommand): ThreadSmsSendAttempt {
        sendCount += 1
        return ThreadSmsSendAttempt.STARTED
    }
    override suspend fun acknowledgeSubmissionUnknown(providerThreadId: ProviderThreadId) = false
    override suspend fun recover() = ThreadSmsRecoveryResult.READY
    override fun fence() = Unit
    override suspend fun handleTransportResult(result: TransportResult) = false
}

private class DelayRecordingRepository : SendDelayRepository {
    private val observed = MutableStateFlow<SendDelayOperation?>(null)
    var operation: SendDelayOperation? = null
        private set

    override suspend fun create(request: SendDelayRequest): SendDelayResult<SendDelayReservation> {
        if (operation != null) return SendDelayResult.StaleWrite
        val created = SendDelayOperation(
            id = SendDelayId(1L),
            participantSetKey = request.participantSetKey,
            providerThreadId = request.providerThreadId,
            draftId = request.draftId,
            draftRevision = request.expectedDraftRevision,
            subscriptionId = request.subscriptionId,
            dueTimestampMillis = request.dueTimestampMillis,
            phase = SendDelayPhase.PENDING,
            reviewReason = null,
            armedWallTimestampMillis = request.createdTimestampMillis,
            armedElapsedRealtimeMillis = request.armedElapsedRealtimeMillis,
            createdTimestampMillis = request.createdTimestampMillis,
            updatedTimestampMillis = request.createdTimestampMillis,
        )
        publish(created)
        return SendDelayResult.Success(SendDelayReservation(created, "synthetic body"))
    }

    override suspend fun read(id: SendDelayId) = result(operation?.takeIf { it.id == id })
    override suspend fun readByThread(providerThreadId: ProviderThreadId) =
        result(operation?.takeIf { it.providerThreadId == providerThreadId })
    override fun observeByThread(providerThreadId: ProviderThreadId): Flow<SendDelayResult<SendDelayOperation?>> =
        observed.map { SendDelayResult.Success(it?.takeIf { item -> item.providerThreadId == providerThreadId }) }
    override suspend fun recoverySnapshot() = SendDelayResult.Success(listOfNotNull(operation))

    override suspend fun markDispatching(
        id: SendDelayId,
        expectedRevision: SendDelayRevision,
        updatedTimestampMillis: Long,
    ) = update(id, expectedRevision) {
        it.copy(phase = SendDelayPhase.DISPATCHING, updatedTimestampMillis = updatedTimestampMillis)
    }

    override suspend fun markReviewRequired(
        id: SendDelayId,
        expectedRevision: SendDelayRevision,
        reason: SendDelayReviewReason,
        updatedTimestampMillis: Long,
    ) = update(id, expectedRevision) {
        it.copy(
            phase = SendDelayPhase.REVIEW_REQUIRED,
            reviewReason = reason,
            updatedTimestampMillis = updatedTimestampMillis,
        )
    }

    override suspend fun remove(
        id: SendDelayId,
        expectedRevision: SendDelayRevision,
    ): SendDelayResult<Unit> {
        val current = operation ?: return SendDelayResult.NotFound
        if (current.id != id || current.revision != expectedRevision) return SendDelayResult.StaleWrite
        if (current.phase == SendDelayPhase.DISPATCHING) return SendDelayResult.PhaseMismatch
        publish(null)
        return SendDelayResult.Success(Unit)
    }

    override suspend fun reconcileDispatch(
        id: SendDelayId,
        updatedTimestampMillis: Long,
    ): SendDelayResult<SendDelayDispatchReconciliation> {
        val current = operation ?: return SendDelayResult.NotFound
        if (current.id != id || current.phase != SendDelayPhase.DISPATCHING) {
            return SendDelayResult.PhaseMismatch
        }
        publish(null)
        return SendDelayResult.Success(SendDelayDispatchReconciliation.COMPLETED_AND_REMOVED)
    }

    private fun update(
        id: SendDelayId,
        revision: SendDelayRevision,
        transform: (SendDelayOperation) -> SendDelayOperation,
    ): SendDelayResult<SendDelayOperation> {
        val current = operation ?: return SendDelayResult.NotFound
        if (current.id != id || current.revision != revision) return SendDelayResult.StaleWrite
        val updated = transform(current)
        publish(updated)
        return SendDelayResult.Success(updated)
    }

    private fun result(value: SendDelayOperation?): SendDelayResult<SendDelayOperation> =
        value?.let { SendDelayResult.Success(it) } ?: SendDelayResult.NotFound

    private fun publish(value: SendDelayOperation?) {
        operation = value
        observed.value = value
    }
}

private object DelayMissingPreferenceRepository : ConversationSubscriptionPreferenceRepository {
    override suspend fun read(scope: ConversationSubscriptionScope) =
        ConversationSubscriptionRepositoryResult.NotFound
    override suspend fun set(
        scope: ConversationSubscriptionScope,
        subscriptionId: AuroraSubscriptionId,
        expectedRevision: ConversationSubscriptionRevision?,
        updatedTimestampMillis: Long,
    ): ConversationSubscriptionRepositoryResult<ConversationSubscriptionPreference> =
        ConversationSubscriptionRepositoryResult.CorruptData
}

private object DelayConversationRepository : ConversationRepository {
    override val invalidations: Flow<ConversationInvalidation> = emptyFlow()
    override suspend fun loadInbox(request: ConversationPageRequest): ConversationPageResult =
        ConversationPageResult.StorageUnavailable(IndexCoverage.NOT_STARTED)
    override suspend fun loadConversation(providerThreadId: ProviderThreadId): ConversationLookupResult =
        ConversationLookupResult.Found(
            summary = ConversationSummary(
                providerThreadId = providerThreadId,
                latestLocalRowId = 1L,
                latestProviderMessageId = ProviderMessageId(ProviderKind.SMS, 1L),
                latestTimestampMillis = 1L,
                latestSentTimestampMillis = null,
                latestDirection = MessageDirection.INCOMING,
                latestBox = MessageBox.INBOX,
                latestStatus = MessageStatus.COMPLETE,
                latestSubscriptionId = SendDelayCoordinatorTest.SUBSCRIPTION,
                latestSenderAddress = SendDelayCoordinatorTest.ADDRESS,
                latestSnippet = null,
                latestAttachmentCount = 0,
                latestAttachmentTypeSummary = "",
                latestRead = true,
                indexedMessageCount = 1L,
                indexedUnreadCount = 0L,
                participants = listOf(SendDelayCoordinatorTest.ADDRESS),
                indexedParticipantCount = 1,
                participantsTruncated = false,
            ),
            coverage = IndexCoverage(
                generationId = 1L,
                state = IndexRunState.COMPLETE,
                indexedMessageCount = 1L,
                smsExhausted = true,
                mmsExhausted = true,
                pendingChanges = false,
            ),
            verifiedIdentity = SendDelayCoordinatorTest.IDENTITY,
        )
}
