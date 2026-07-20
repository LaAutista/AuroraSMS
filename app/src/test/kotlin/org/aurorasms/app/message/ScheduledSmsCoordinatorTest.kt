// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

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
import org.aurorasms.core.state.MessageSignature
import org.aurorasms.core.state.ScheduledSms
import org.aurorasms.core.state.ScheduledSmsDispatchReconciliation
import org.aurorasms.core.state.ScheduledSmsId
import org.aurorasms.core.state.ScheduledSmsPhase
import org.aurorasms.core.state.ScheduledSmsPrecision
import org.aurorasms.core.state.ScheduledSmsRepository
import org.aurorasms.core.state.ScheduledSmsRequest
import org.aurorasms.core.state.ScheduledSmsReservation
import org.aurorasms.core.state.ScheduledSmsResult
import org.aurorasms.core.state.ScheduledSmsReviewReason
import org.aurorasms.core.state.ScheduledSmsRevision
import org.aurorasms.core.telephony.ActiveSubscription
import org.aurorasms.core.telephony.SubscriptionSnapshot
import org.aurorasms.core.testing.FakeRoleState
import org.aurorasms.core.testing.FakeSubscriptionRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledSmsCoordinatorTest {
    @Test
    fun exactAccessDenialStoresHonestInexactSchedule() = runTest {
        val fixture = fixture(armResult = ScheduledAlarmArmResult.INEXACT)

        assertEquals(ScheduledSmsAttempt.ACCEPTED, fixture.coordinator.schedule(command()))

        assertEquals(ScheduledSmsPrecision.INEXACT, fixture.repository.schedule?.precision)
        assertEquals(listOf(DUE), fixture.alarms.armedDueTimes)
        assertEquals(0, fixture.sender.sendCount)
    }

    @Test
    fun duplicateAlarmAfterCompletedDispatchNeverSendsTwice() = runTest {
        val fixture = fixture()
        assertEquals(ScheduledSmsAttempt.ACCEPTED, fixture.coordinator.schedule(command()))
        fixture.clock.wall = DUE
        fixture.clock.elapsed += DUE - START

        fixture.coordinator.handleAlarm(ScheduledSmsId(1L))
        fixture.coordinator.handleAlarm(ScheduledSmsId(1L))

        assertEquals(1, fixture.sender.sendCount)
        assertEquals(null, fixture.repository.schedule)
    }

    @Test
    fun dueAlarmAfterProcessRestartUsesOnlyDurableScheduleState() = runTest {
        val original = fixture()
        assertEquals(ScheduledSmsAttempt.ACCEPTED, original.coordinator.schedule(command()))
        original.clock.wall = DUE
        original.clock.elapsed += DUE - START
        val restartedSender = RecordingSender()
        val restarted = ScheduledSmsCoordinator(
            roleState = FakeRoleState(),
            conversations = FixedConversationRepository,
            subscriptions = original.subscriptions,
            subscriptionPreferences = MissingPreferenceRepository,
            repository = original.repository,
            alarms = original.alarms,
            sender = restartedSender,
            segmentCounter = SmsSegmentCounter { 1 },
            clock = original.clock,
        )

        restarted.handleAlarm(ScheduledSmsId(1L))

        assertEquals(1, restartedSender.sendCount)
        assertEquals(null, original.repository.schedule)
    }

    @Test
    fun frozenSignatureSurvivesScheduleRestartAndReachesDurableSender() = runTest {
        val signature = checkNotNull(MessageSignature.fromUserInput("Scheduled"))
        val original = fixture()
        assertEquals(
            ScheduledSmsAttempt.ACCEPTED,
            original.coordinator.schedule(command().copy(frozenSignature = signature)),
        )
        original.clock.wall = DUE
        original.clock.elapsed += DUE - START
        val restartedSender = RecordingSender()
        val restarted = ScheduledSmsCoordinator(
            roleState = FakeRoleState(),
            conversations = FixedConversationRepository,
            subscriptions = original.subscriptions,
            subscriptionPreferences = MissingPreferenceRepository,
            repository = original.repository,
            alarms = original.alarms,
            sender = restartedSender,
            segmentCounter = SmsSegmentCounter { 1 },
            clock = original.clock,
        )

        restarted.handleAlarm(ScheduledSmsId(1L))

        assertEquals(signature, restartedSender.commands.single().frozenSignature)
    }

    @Test
    fun wallClockDriftPausesForReviewWithoutSending() = runTest {
        val fixture = fixture()
        assertEquals(ScheduledSmsAttempt.ACCEPTED, fixture.coordinator.schedule(command()))
        fixture.clock.wall = DUE

        fixture.coordinator.handleAlarm(ScheduledSmsId(1L))

        assertEquals(ScheduledSmsPhase.REVIEW_REQUIRED, fixture.repository.schedule?.phase)
        assertEquals(ScheduledSmsReviewReason.CLOCK_CHANGED, fixture.repository.schedule?.reviewReason)
        assertEquals(0, fixture.sender.sendCount)
    }

    @Test
    fun rebootPastDueAndRemovedSimBothFailClosed() = runTest {
        val reboot = fixture()
        assertEquals(ScheduledSmsAttempt.ACCEPTED, reboot.coordinator.schedule(command()))
        reboot.clock.wall = DUE + 1L
        reboot.coordinator.recover(ScheduledSmsRecoveryReason.BOOT_COMPLETED)
        assertEquals(
            ScheduledSmsReviewReason.MISSED_AFTER_RESTART,
            reboot.repository.schedule?.reviewReason,
        )
        assertEquals(0, reboot.sender.sendCount)

        val removedSim = fixture()
        assertEquals(ScheduledSmsAttempt.ACCEPTED, removedSim.coordinator.schedule(command()))
        removedSim.subscriptions.snapshot = SubscriptionSnapshot.Available(emptyList())
        removedSim.clock.wall = DUE
        removedSim.clock.elapsed += DUE - START
        removedSim.coordinator.handleAlarm(ScheduledSmsId(1L))
        assertEquals(
            ScheduledSmsReviewReason.PRECONDITION_FAILED,
            removedSim.repository.schedule?.reviewReason,
        )
        assertEquals(0, removedSim.sender.sendCount)
    }

    @Test
    fun dispatchingScheduleCannotBeCancelledAfterSendHandoffBegins() = runTest {
        val fixture = fixture()
        assertEquals(ScheduledSmsAttempt.ACCEPTED, fixture.coordinator.schedule(command()))
        val pending = checkNotNull(fixture.repository.schedule)
        fixture.repository.markDispatching(
            pending.id,
            pending.revision,
            pending.updatedTimestampMillis + 1L,
        )

        assertEquals(false, fixture.coordinator.cancel(THREAD))
        assertEquals(ScheduledSmsPhase.DISPATCHING, fixture.repository.schedule?.phase)
    }

    @Test
    fun groupIdentityCannotCreateScheduleOrReachSmsSender() = runTest {
        val fixture = fixture()
        val group = IDENTITY.copy(participants = listOf(ADDRESS, ParticipantAddress("+15550000001")))

        assertEquals(ScheduledSmsAttempt.REFUSED, fixture.coordinator.schedule(command().copy(identity = group)))

        assertEquals(null, fixture.repository.schedule)
        assertTrue(fixture.alarms.armedDueTimes.isEmpty())
        assertEquals(0, fixture.sender.sendCount)
    }

    private fun fixture(
        armResult: ScheduledAlarmArmResult = ScheduledAlarmArmResult.EXACT,
    ): Fixture {
        val repository = RecordingScheduleRepository()
        val alarms = RecordingAlarmDriver(armResult)
        val sender = RecordingSender()
        val subscriptions = FakeSubscriptionRepository(
            SubscriptionSnapshot.Available(
                listOf(ActiveSubscription(SUBSCRIPTION, 0, "Synthetic SIM", true)),
            ),
        )
        val clock = MutableClock(START, 500L)
        return Fixture(
            coordinator = ScheduledSmsCoordinator(
                roleState = FakeRoleState(),
                conversations = FixedConversationRepository,
                subscriptions = subscriptions,
                subscriptionPreferences = MissingPreferenceRepository,
                repository = repository,
                alarms = alarms,
                sender = sender,
                segmentCounter = SmsSegmentCounter { 1 },
                clock = clock,
            ),
            repository = repository,
            alarms = alarms,
            sender = sender,
            subscriptions = subscriptions,
            clock = clock,
        )
    }

    private fun command() = ScheduledSmsCommand(
        identity = IDENTITY,
        subscriptionId = SUBSCRIPTION,
        draftId = DraftId(3L),
        draftRevision = DraftRevision(4L),
        dueTimestampMillis = DUE,
    )

    private data class Fixture(
        val coordinator: ScheduledSmsCoordinator,
        val repository: RecordingScheduleRepository,
        val alarms: RecordingAlarmDriver,
        val sender: RecordingSender,
        val subscriptions: FakeSubscriptionRepository,
        val clock: MutableClock,
    )

    companion object {
        internal const val START = 1_000L
        internal const val DUE = START + 5L * 60L * 1_000L
        internal val THREAD = ProviderThreadId(2L)
        internal val SUBSCRIPTION = AuroraSubscriptionId(1)
        internal val ADDRESS = ParticipantAddress("+15550000000")
        internal val IDENTITY = VerifiedConversationIdentity(THREAD, 1L, listOf(ADDRESS))
    }
}

private class MutableClock(var wall: Long, var elapsed: Long) : ScheduledSmsClock {
    override fun wallMillis() = wall
    override fun elapsedMillis() = elapsed
}

private class RecordingAlarmDriver(private val result: ScheduledAlarmArmResult) : ScheduledSmsAlarmDriver {
    val armedDueTimes = mutableListOf<Long>()
    val cancelledIds = mutableListOf<ScheduledSmsId>()
    override fun arm(id: ScheduledSmsId, dueTimestampMillis: Long): ScheduledAlarmArmResult {
        armedDueTimes += dueTimestampMillis
        return result
    }
    override fun cancel(id: ScheduledSmsId) { cancelledIds += id }
}

private class RecordingSender : ThreadSmsSendController {
    var sendCount = 0
    val commands = mutableListOf<ThreadSmsSendCommand>()
    override fun observe(providerThreadId: ProviderThreadId) =
        MutableStateFlow(ThreadSmsSendObservation(ThreadSmsSendPhase.IDLE))
    override suspend fun send(command: ThreadSmsSendCommand): ThreadSmsSendAttempt {
        sendCount += 1
        commands += command
        return ThreadSmsSendAttempt.STARTED
    }
    override suspend fun acknowledgeSubmissionUnknown(providerThreadId: ProviderThreadId) = false
    override suspend fun recover() = ThreadSmsRecoveryResult.READY
    override fun fence() = Unit
    override suspend fun handleTransportResult(result: TransportResult) = false
}

private class RecordingScheduleRepository : ScheduledSmsRepository {
    private val observed = MutableStateFlow<ScheduledSms?>(null)
    var schedule: ScheduledSms? = null
        private set

    override suspend fun create(request: ScheduledSmsRequest): ScheduledSmsResult<ScheduledSmsReservation> {
        if (schedule != null) return ScheduledSmsResult.StaleWrite
        val created = ScheduledSms(
            id = ScheduledSmsId(1L),
            participantSetKey = request.participantSetKey,
            providerThreadId = request.providerThreadId,
            draftId = request.draftId,
            draftRevision = request.expectedDraftRevision,
            subscriptionId = request.subscriptionId,
            dueTimestampMillis = request.dueTimestampMillis,
            phase = ScheduledSmsPhase.PENDING,
            precision = ScheduledSmsPrecision.INEXACT,
            reviewReason = null,
            armedWallTimestampMillis = request.createdTimestampMillis,
            armedElapsedRealtimeMillis = request.armedElapsedRealtimeMillis,
            createdTimestampMillis = request.createdTimestampMillis,
            updatedTimestampMillis = request.createdTimestampMillis,
            frozenSignature = request.frozenSignature,
        )
        publish(created)
        return ScheduledSmsResult.Success(ScheduledSmsReservation(created, "synthetic body"))
    }

    override suspend fun read(id: ScheduledSmsId) = result(schedule?.takeIf { it.id == id })
    override suspend fun readByThread(providerThreadId: ProviderThreadId) =
        result(schedule?.takeIf { it.providerThreadId == providerThreadId })
    override fun observeByThread(providerThreadId: ProviderThreadId): Flow<ScheduledSmsResult<ScheduledSms?>> =
        observed.map { ScheduledSmsResult.Success(it?.takeIf { item -> item.providerThreadId == providerThreadId }) }
    override suspend fun recoverySnapshot() = ScheduledSmsResult.Success(listOfNotNull(schedule))

    override suspend fun markArmed(
        id: ScheduledSmsId,
        expectedRevision: ScheduledSmsRevision,
        precision: ScheduledSmsPrecision,
        armedWallTimestampMillis: Long,
        armedElapsedRealtimeMillis: Long,
        updatedTimestampMillis: Long,
    ) = update(id, expectedRevision) {
        it.copy(
            precision = precision,
            armedWallTimestampMillis = armedWallTimestampMillis,
            armedElapsedRealtimeMillis = armedElapsedRealtimeMillis,
            updatedTimestampMillis = updatedTimestampMillis,
        )
    }

    override suspend fun markDispatching(
        id: ScheduledSmsId,
        expectedRevision: ScheduledSmsRevision,
        updatedTimestampMillis: Long,
    ) = update(id, expectedRevision) {
        it.copy(phase = ScheduledSmsPhase.DISPATCHING, updatedTimestampMillis = updatedTimestampMillis)
    }

    override suspend fun markReviewRequired(
        id: ScheduledSmsId,
        expectedRevision: ScheduledSmsRevision,
        reason: ScheduledSmsReviewReason,
        updatedTimestampMillis: Long,
    ) = update(id, expectedRevision) {
        it.copy(
            phase = ScheduledSmsPhase.REVIEW_REQUIRED,
            reviewReason = reason,
            updatedTimestampMillis = updatedTimestampMillis,
        )
    }

    override suspend fun remove(id: ScheduledSmsId, expectedRevision: ScheduledSmsRevision): ScheduledSmsResult<Unit> {
        val current = schedule ?: return ScheduledSmsResult.NotFound
        if (current.id != id || current.revision != expectedRevision) return ScheduledSmsResult.StaleWrite
        publish(null)
        return ScheduledSmsResult.Success(Unit)
    }

    override suspend fun reconcileDispatch(
        id: ScheduledSmsId,
        updatedTimestampMillis: Long,
    ): ScheduledSmsResult<ScheduledSmsDispatchReconciliation> {
        val current = schedule ?: return ScheduledSmsResult.NotFound
        if (current.id != id || current.phase != ScheduledSmsPhase.DISPATCHING) {
            return ScheduledSmsResult.PhaseMismatch
        }
        publish(null)
        return ScheduledSmsResult.Success(ScheduledSmsDispatchReconciliation.COMPLETED_AND_REMOVED)
    }

    private fun update(
        id: ScheduledSmsId,
        revision: ScheduledSmsRevision,
        transform: (ScheduledSms) -> ScheduledSms,
    ): ScheduledSmsResult<ScheduledSms> {
        val current = schedule ?: return ScheduledSmsResult.NotFound
        if (current.id != id || current.revision != revision) return ScheduledSmsResult.StaleWrite
        val updated = transform(current)
        publish(updated)
        return ScheduledSmsResult.Success(updated)
    }

    private fun result(value: ScheduledSms?): ScheduledSmsResult<ScheduledSms> =
        value?.let { ScheduledSmsResult.Success(it) } ?: ScheduledSmsResult.NotFound

    private fun publish(value: ScheduledSms?) {
        schedule = value
        observed.value = value
    }
}

private object MissingPreferenceRepository : ConversationSubscriptionPreferenceRepository {
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

private object FixedConversationRepository : ConversationRepository {
    override val invalidations: Flow<ConversationInvalidation> = emptyFlow()
    override suspend fun loadInbox(request: ConversationPageRequest): ConversationPageResult =
        ConversationPageResult.StorageUnavailable(IndexCoverage.NOT_STARTED)
    override suspend fun loadConversation(providerThreadId: ProviderThreadId): ConversationLookupResult =
        ConversationLookupResult.Found(
            summary = ConversationSummary(
                providerThreadId = ScheduledSmsCoordinatorTest.THREAD,
                latestLocalRowId = 1L,
                latestProviderMessageId = ProviderMessageId(ProviderKind.SMS, 1L),
                latestTimestampMillis = 1L,
                latestSentTimestampMillis = null,
                latestDirection = MessageDirection.INCOMING,
                latestBox = MessageBox.INBOX,
                latestStatus = MessageStatus.COMPLETE,
                latestSubscriptionId = ScheduledSmsCoordinatorTest.SUBSCRIPTION,
                latestSenderAddress = ScheduledSmsCoordinatorTest.ADDRESS,
                latestSnippet = null,
                latestAttachmentCount = 0,
                latestAttachmentTypeSummary = "",
                latestRead = true,
                indexedMessageCount = 1L,
                indexedUnreadCount = 0L,
                participants = listOf(ScheduledSmsCoordinatorTest.ADDRESS),
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
            verifiedIdentity = ScheduledSmsCoordinatorTest.IDENTITY,
        )
}
