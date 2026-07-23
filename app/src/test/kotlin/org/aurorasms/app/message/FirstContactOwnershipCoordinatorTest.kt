// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import kotlin.coroutines.cancellation.CancellationException
import java.util.ArrayDeque
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.ComposerSmsFirstContactAuthority
import org.aurorasms.core.state.Draft
import org.aurorasms.core.state.DraftAttachment
import org.aurorasms.core.state.DraftId
import org.aurorasms.core.state.DraftIdentity
import org.aurorasms.core.state.DraftParticipantSetKey
import org.aurorasms.core.state.DraftRevision
import org.aurorasms.core.state.FirstContactAttachmentSetEvidence
import org.aurorasms.core.state.FirstContactBridgeSnapshot
import org.aurorasms.core.state.FirstContactKnownUnsentProof
import org.aurorasms.core.state.FirstContactOperation
import org.aurorasms.core.state.FirstContactOperationId
import org.aurorasms.core.state.FirstContactOperationPhase
import org.aurorasms.core.state.FirstContactOperationRepository
import org.aurorasms.core.state.FirstContactOperationResult
import org.aurorasms.core.state.FirstContactOperationRevision
import org.aurorasms.core.state.FirstContactParticipantSetKey
import org.aurorasms.core.state.FirstContactReservationRequest
import org.aurorasms.core.state.FirstContactResolutionSnapshot
import org.aurorasms.core.telephony.ActiveSubscription
import org.aurorasms.core.telephony.ProviderThreadResolution
import org.aurorasms.core.telephony.ProviderThreadResolver
import org.aurorasms.core.telephony.RecipientSet
import org.aurorasms.core.telephony.SubscriptionRepository
import org.aurorasms.core.telephony.SubscriptionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstContactOwnershipCoordinatorTest {
    @Test
    fun `success preserves the authority order and stops at handoff reserved`() = runTest {
        val fixture = fixture()

        val result = fixture.coordinator.reserveAndBind(COMMAND)

        val handoff = result as FirstContactOwnershipResult.HandoffReserved
        val persisted = checkNotNull(fixture.repository.operation)
        assertEquals(
            ComposerSmsFirstContactAuthority(
                operationId = persisted.id,
                expectedRevision = persisted.revision,
                participantSetKey = persisted.participantSetKey,
                attachmentSetEvidence = persisted.attachmentSetEvidence,
            ),
            handoff.authority,
        )
        assertFalse(
            handoff.authority.expectedRevision.updatedTimestampMillis ==
                handoff.boundDraftRevision.updatedTimestampMillis,
        )
        assertEquals(FirstContactOperationPhase.HANDOFF_RESERVED, fixture.repository.operation?.phase)
        assertEquals(
            listOf(
                "subscription",
                "reserve",
                "preflight",
                "subscription",
                "resolution_started",
                "resolve",
                "bind",
                "preflight",
                "subscription",
                "bridge",
            ),
            fixture.events,
        )
        assertEquals(1, fixture.resolver.callCount)
        assertEquals(THREAD, fixture.repository.operation?.providerThreadId)
    }

    @Test
    fun `subscription denial before reserve cannot create durable or provider ownership`() = runTest {
        val fixture = fixture(
            subscriptionSnapshots = listOf(availableSubscriptions(emptyList())),
        )

        val result = fixture.coordinator.reserveAndBind(COMMAND)

        assertEquals(
            FirstContactOwnershipResult.Denied(
                FirstContactOwnershipDenialReason.SUBSCRIPTION_UNAVAILABLE,
            ),
            result,
        )
        assertEquals(listOf("subscription"), fixture.events)
        assertEquals(null, fixture.repository.operation)
        assertEquals(0, fixture.resolver.callCount)
    }

    @Test
    fun `subscription churn before resolution becomes known unsent without provider access`() =
        runTest {
            val fixture = fixture(
                subscriptionSnapshots = listOf(
                    activeSubscriptionSnapshot(),
                    availableSubscriptions(emptyList()),
                ),
            )

            val result = fixture.coordinator.reserveAndBind(COMMAND)

            assertTrue(result is FirstContactOwnershipResult.Denied)
            assertEquals(FirstContactOperationPhase.KNOWN_UNSENT, fixture.repository.operation?.phase)
            assertEquals(0, fixture.resolver.callCount)
            assertEquals(
                listOf("subscription", "reserve", "preflight", "subscription", "known_unsent"),
                fixture.events,
            )
        }

    @Test
    fun `role loss before resolution becomes known unsent without provider access`() = runTest {
        val fixture = fixture(
            preflights = listOf(FirstContactAuthorityPreflight.ROLE_REQUIRED),
        )

        val result = fixture.coordinator.reserveAndBind(COMMAND)

        assertEquals(
            FirstContactOwnershipDenialReason.ROLE_REQUIRED,
            (result as FirstContactOwnershipResult.Denied).reason,
        )
        assertEquals(FirstContactOperationPhase.KNOWN_UNSENT, fixture.repository.operation?.phase)
        assertEquals(0, fixture.resolver.callCount)
    }

    @Test
    fun `subscription churn after verified bind stops at thread bound`() = runTest {
        val fixture = fixture(
            subscriptionSnapshots = listOf(
                activeSubscriptionSnapshot(),
                activeSubscriptionSnapshot(),
                availableSubscriptions(emptyList()),
            ),
        )

        val result = fixture.coordinator.reserveAndBind(COMMAND)

        assertEquals(
            FirstContactOwnershipDenialReason.SUBSCRIPTION_UNAVAILABLE,
            (result as FirstContactOwnershipResult.Denied).reason,
        )
        assertEquals(FirstContactOperationPhase.THREAD_BOUND, fixture.repository.operation?.phase)
        assertEquals(THREAD, fixture.repository.operation?.providerThreadId)
        assertFalse("bridge" in fixture.events)
    }

    @Test
    fun `role loss after verified bind stops at thread bound`() = runTest {
        val fixture = fixture(
            preflights = listOf(
                FirstContactAuthorityPreflight.READY,
                FirstContactAuthorityPreflight.PERMISSION_DENIED,
            ),
        )

        val result = fixture.coordinator.reserveAndBind(COMMAND)

        assertEquals(
            FirstContactOwnershipDenialReason.PERMISSION_DENIED,
            (result as FirstContactOwnershipResult.Denied).reason,
        )
        assertEquals(FirstContactOperationPhase.THREAD_BOUND, fixture.repository.operation?.phase)
        assertFalse("bridge" in fixture.events)
    }

    @Test
    fun `resolver ambiguity and participant conflict are terminal unknown and never bind`() = runTest {
        val unknown = fixture(resolution = ProviderThreadResolution.MutationOutcomeUnknown)
        assertTrue(
            unknown.coordinator.reserveAndBind(COMMAND) is
                FirstContactOwnershipResult.ResolutionUnknown,
        )
        assertEquals(FirstContactOperationPhase.RESOLUTION_UNKNOWN, unknown.repository.operation?.phase)
        assertFalse("bind" in unknown.events)

        val mismatch = fixture(resolution = ProviderThreadResolution.ExactParticipantsUnverified)
        val mismatchResult = mismatch.coordinator.reserveAndBind(COMMAND)
        assertEquals(
            FirstContactOwnershipConflictReason.EXACT_PARTICIPANTS_UNVERIFIED,
            (mismatchResult as FirstContactOwnershipResult.Conflict).reason,
        )
        assertEquals(FirstContactOperationPhase.RESOLUTION_UNKNOWN, mismatch.repository.operation?.phase)
        assertFalse("bind" in mismatch.events)

        val countMismatch = fixture(
            resolution = ProviderThreadResolution.Verified(THREAD, participantCount = 2),
        )
        val countResult = countMismatch.coordinator.reserveAndBind(COMMAND)
        assertEquals(
            FirstContactOwnershipConflictReason.EXACT_PARTICIPANTS_UNVERIFIED,
            (countResult as FirstContactOwnershipResult.Conflict).reason,
        )
        assertEquals(
            FirstContactOperationPhase.RESOLUTION_UNKNOWN,
            countMismatch.repository.operation?.phase,
        )
        assertFalse("bind" in countMismatch.events)
        assertFalse("bridge" in countMismatch.events)
    }

    @Test
    fun `resolver cancellation after durable fence becomes unknown and returns typed cancellation`() =
        runTest {
            val fixture = fixture(
                resolverBlock = { throw CancellationException("synthetic pre-entry cancellation") },
            )

            val result = fixture.coordinator.reserveAndBind(COMMAND)

            assertEquals(
                FirstContactOwnershipResult.Cancelled(OPERATION_ID, resolutionUnknown = true),
                result,
            )
            assertEquals(
                FirstContactOperationPhase.RESOLUTION_UNKNOWN,
                fixture.repository.operation?.phase,
            )
            assertFalse("bind" in fixture.events)
        }

    @Test
    fun `typed resolver preflight outcomes after durable fence remain terminal unknown`() = runTest {
        listOf(
            ProviderThreadResolution.RoleRequired,
            ProviderThreadResolution.PermissionDenied,
            ProviderThreadResolution.PlatformUnavailable,
        ).forEach { resolution ->
            val fixture = fixture(resolution = resolution)

            val result = fixture.coordinator.reserveAndBind(COMMAND)

            assertTrue(result is FirstContactOwnershipResult.ResolutionUnknown)
            assertEquals(
                FirstContactOperationPhase.RESOLUTION_UNKNOWN,
                fixture.repository.operation?.phase,
            )
            assertFalse("bind" in fixture.events)
            assertFalse("bridge" in fixture.events)
        }
    }

    @Test
    fun `cancellation after verified return cannot discard the durable thread binding`() = runTest {
        val fixture = fixture(
            subscriptionCancellationCall = 3,
        )

        val result = fixture.coordinator.reserveAndBind(COMMAND)

        assertEquals(
            FirstContactOwnershipResult.Cancelled(OPERATION_ID, resolutionUnknown = false),
            result,
        )
        assertEquals(FirstContactOperationPhase.THREAD_BOUND, fixture.repository.operation?.phase)
        assertEquals(THREAD, fixture.repository.operation?.providerThreadId)
        assertTrue("bind" in fixture.events)
        assertFalse("bridge" in fixture.events)
    }

    @Test
    fun `commit then cancellation at resolution start becomes unknown and never resolves`() = runTest {
        val fixture = fixture()
        fixture.repository.cancelAfterResolutionStartCommit = true

        val result = fixture.coordinator.reserveAndBind(COMMAND)

        assertEquals(
            FirstContactOwnershipResult.Cancelled(OPERATION_ID, resolutionUnknown = true),
            result,
        )
        assertEquals(FirstContactOperationPhase.RESOLUTION_UNKNOWN, fixture.repository.operation?.phase)
        assertEquals(0, fixture.resolver.callCount)
    }

    @Test
    fun `commit then cancellation at bind is reconciled and reaches handoff`() = runTest {
        val fixture = fixture()
        fixture.repository.cancelAfterBindCommit = true

        val result = fixture.coordinator.reserveAndBind(COMMAND)

        assertTrue(result is FirstContactOwnershipResult.HandoffReserved)
        assertEquals(FirstContactOperationPhase.HANDOFF_RESERVED, fixture.repository.operation?.phase)
        assertEquals(1, fixture.resolver.callCount)
    }

    @Test
    fun `recreation is idempotent at unknown thread-bound and handoff checkpoints`() = runTest {
        val unknown = fixture()
        unknown.repository.seed(operation(FirstContactOperationPhase.RESOLUTION_STARTED))
        val unknownResult = unknown.coordinator.reserveAndBind(COMMAND)
        assertTrue(unknownResult is FirstContactOwnershipResult.ResolutionUnknown)
        assertEquals(0, unknown.resolver.callCount)
        assertEquals(FirstContactOperationPhase.RESOLUTION_UNKNOWN, unknown.repository.operation?.phase)

        val threadBound = fixture()
        threadBound.repository.seed(operation(FirstContactOperationPhase.THREAD_BOUND))
        assertTrue(
            threadBound.coordinator.reserveAndBind(COMMAND) is
                FirstContactOwnershipResult.HandoffReserved,
        )
        assertEquals(0, threadBound.resolver.callCount)

        val complete = fixture()
        val first = complete.coordinator.reserveAndBind(COMMAND)
            as FirstContactOwnershipResult.HandoffReserved
        complete.events.clear()
        val recreated = complete.newCoordinator()
        val resumed = recreated.reserveAndBind(COMMAND)
            as FirstContactOwnershipResult.HandoffReserved
        assertEquals(first, resumed)
        assertEquals(listOf("subscription", "reserve", "read_by_draft"), complete.events)
        assertEquals(1, complete.resolver.callCount)

        complete.events.clear()
        val mismatch = recreated.reserveAndBind(
            COMMAND.copy(
                expectedDraftRevision = DraftRevision(
                    SOURCE_REVISION.updatedTimestampMillis + 1L,
                ),
            ),
        )
        assertEquals(
            FirstContactOwnershipConflictReason.ACTIVE_OPERATION,
            (mismatch as FirstContactOwnershipResult.Conflict).reason,
        )
        assertEquals(listOf("subscription", "reserve", "read_by_draft"), complete.events)
        assertEquals(1, complete.resolver.callCount)
    }

    @Test
    fun `concurrent rebind conflict preserves the other exact owner and never bridges`() = runTest {
        val fixture = fixture()
        fixture.repository.bindRaceThread = OTHER_THREAD

        val result = fixture.coordinator.reserveAndBind(COMMAND)

        assertEquals(
            FirstContactOwnershipConflictReason.THREAD_BINDING,
            (result as FirstContactOwnershipResult.Conflict).reason,
        )
        assertEquals(OTHER_THREAD, fixture.repository.operation?.providerThreadId)
        assertEquals(FirstContactOperationPhase.THREAD_BOUND, fixture.repository.operation?.phase)
        assertFalse("bridge" in fixture.events)
    }

    @Test
    fun `target-draft bridge conflict leaves thread-bound ownership intact`() = runTest {
        val fixture = fixture()
        fixture.repository.bridgeConflict = true

        val result = fixture.coordinator.reserveAndBind(COMMAND)

        assertEquals(
            FirstContactOwnershipConflictReason.TARGET_DRAFT,
            (result as FirstContactOwnershipResult.Conflict).reason,
        )
        assertEquals(FirstContactOperationPhase.THREAD_BOUND, fixture.repository.operation?.phase)
        assertEquals(THREAD, fixture.repository.operation?.providerThreadId)
    }

    @Test
    fun `coordinator has no transport sender provider staging or callback dependency`() {
        val forbidden = setOf(
            "org.aurorasms.core.telephony.MessageTransport",
            "org.aurorasms.app.message.ThreadSmsSendController",
            "org.aurorasms.core.telephony.SmsProviderDataSource",
            "org.aurorasms.core.telephony.MmsProviderDataSource",
        )
        val parameterTypes = FirstContactOwnershipCoordinator::class.java.declaredConstructors
            .flatMap { constructor -> constructor.parameterTypes.asList() }
            .map { type -> type.name }

        assertTrue(parameterTypes.none { it in forbidden })
    }

    @Test
    fun `commands and outcomes redact all identifiers and recipient content`() {
        val outcome = FirstContactOwnershipResult.HandoffReserved(
            authority = ComposerSmsFirstContactAuthority(
                operationId = FirstContactOperationId(7_654_321L),
                expectedRevision = FirstContactOperationRevision(9_876_543L),
                participantSetKey = FirstContactParticipantSetKey.fromParticipants(
                    RECIPIENTS.addresses,
                ),
                attachmentSetEvidence =
                    FirstContactAttachmentSetEvidence.fromAttachments(emptyList()),
            ),
            providerThreadId = ProviderThreadId(8_675_309L),
            draftId = DRAFT_ID,
            boundDraftRevision = DraftRevision(123_456L),
        )

        assertFalse(COMMAND.toString().contains(RECIPIENT.value))
        assertFalse(COMMAND.toString().contains(DRAFT_ID.value.toString()))
        assertFalse(outcome.toString().contains("7654321"))
        assertFalse(outcome.toString().contains("9876543"))
        assertFalse(outcome.toString().contains("8675309"))
        assertFalse(outcome.toString().contains("123456"))
    }

    private fun fixture(
        subscriptionSnapshots: List<SubscriptionSnapshot> = listOf(activeSubscriptionSnapshot()),
        preflights: List<FirstContactAuthorityPreflight> =
            listOf(FirstContactAuthorityPreflight.READY),
        resolution: ProviderThreadResolution =
            ProviderThreadResolution.Verified(THREAD, RECIPIENTS.size),
        resolverBlock: (suspend (RecipientSet) -> ProviderThreadResolution)? = null,
        subscriptionCancellationCall: Int? = null,
    ): Fixture {
        val events = mutableListOf<String>()
        val repository = RecordingFirstContactRepository(events)
        val subscriptions = RecordingSubscriptions(
            events,
            subscriptionSnapshots,
            subscriptionCancellationCall,
        )
        val preflight = RecordingPreflight(events, preflights)
        val resolver = RecordingResolver(events, resolverBlock ?: { resolution })
        val clock = IncrementingClock()
        val coordinator = FirstContactOwnershipCoordinator(
            authorityPreflight = preflight,
            subscriptions = subscriptions,
            operations = repository,
            threadResolver = resolver,
            clock = clock,
        )
        return Fixture(
            coordinator,
            repository,
            subscriptions,
            preflight,
            resolver,
            clock,
            events,
        )
    }

    private data class Fixture(
        val coordinator: FirstContactOwnershipCoordinator,
        val repository: RecordingFirstContactRepository,
        val subscriptions: RecordingSubscriptions,
        val preflight: RecordingPreflight,
        val resolver: RecordingResolver,
        val clock: IncrementingClock,
        val events: MutableList<String>,
    ) {
        fun newCoordinator() = FirstContactOwnershipCoordinator(
            authorityPreflight = preflight,
            subscriptions = subscriptions,
            operations = repository,
            threadResolver = resolver,
            clock = clock,
        )
    }

    companion object {
        private val RECIPIENT = ParticipantAddress("+15550102020")
        private val RECIPIENTS = validRecipients(RECIPIENT.value)
        private val DRAFT_ID = DraftId(7L)
        private val SOURCE_REVISION = DraftRevision(100L)
        private val SUBSCRIPTION_ID = AuroraSubscriptionId(3)
        private val OPERATION_ID = FirstContactOperationId(1L)
        private val THREAD = ProviderThreadId(41L)
        private val OTHER_THREAD = ProviderThreadId(42L)
        private val COMMAND = FirstContactOwnershipCommand(
            recipients = RECIPIENTS,
            participantDraftIdentity = DraftIdentity.ParticipantSet(
                DraftParticipantSetKey.fromParticipants(RECIPIENTS.addresses),
            ),
            draftId = DRAFT_ID,
            expectedDraftRevision = SOURCE_REVISION,
            subscriptionId = SUBSCRIPTION_ID,
            transport = MessageTransportKind.SMS,
        )

        private fun activeSubscriptionSnapshot(): SubscriptionSnapshot = availableSubscriptions(
            listOf(ActiveSubscription(SUBSCRIPTION_ID, 0, "Synthetic SIM", smsCapable = true)),
        )

        private fun availableSubscriptions(
            values: List<ActiveSubscription>,
        ): SubscriptionSnapshot = SubscriptionSnapshot.Available(values)

        private fun validRecipients(vararg values: String): RecipientSet =
            (RecipientSet.parse(values.asList()) as RecipientSet.CreationResult.Valid).recipients

        private fun operation(phase: FirstContactOperationPhase): FirstContactOperation {
            val thread = when (phase) {
                FirstContactOperationPhase.THREAD_BOUND,
                FirstContactOperationPhase.HANDOFF_RESERVED,
                -> THREAD
                else -> null
            }
            val handoff = DraftRevision(1_104L)
                .takeIf { phase == FirstContactOperationPhase.HANDOFF_RESERVED }
            val updated = when (phase) {
                FirstContactOperationPhase.RESERVED -> 1_000L
                FirstContactOperationPhase.RESOLUTION_STARTED -> 1_001L
                FirstContactOperationPhase.THREAD_BOUND -> 1_002L
                FirstContactOperationPhase.HANDOFF_RESERVED -> 1_004L
                FirstContactOperationPhase.RESOLUTION_UNKNOWN,
                FirstContactOperationPhase.KNOWN_UNSENT,
                -> 1_002L
            }
            return FirstContactOperation(
                id = OPERATION_ID,
                participantSetKey = FirstContactParticipantSetKey.fromParticipants(
                    RECIPIENTS.addresses,
                ),
                draftId = DRAFT_ID,
                sourceDraftRevision = SOURCE_REVISION,
                attachmentSetEvidence = FirstContactAttachmentSetEvidence.fromAttachments(emptyList()),
                subscriptionId = SUBSCRIPTION_ID,
                transport = MessageTransportKind.SMS,
                phase = phase,
                providerThreadId = thread,
                handoffDraftRevision = handoff,
                createdTimestampMillis = 1_000L,
                updatedTimestampMillis = updated,
            )
        }
    }
}

private class RecordingPreflight(
    private val events: MutableList<String>,
    values: List<FirstContactAuthorityPreflight>,
) : FirstContactAuthorityPreflightGate {
    private val queue = ArrayDeque(values)

    override fun evaluate(): FirstContactAuthorityPreflight {
        events += "preflight"
        return queue.takeNextOrLast(FirstContactAuthorityPreflight.PLATFORM_UNAVAILABLE)
    }
}

private class RecordingSubscriptions(
    private val events: MutableList<String>,
    values: List<SubscriptionSnapshot>,
    private val cancellationCall: Int?,
) : SubscriptionRepository {
    private val queue = ArrayDeque(values)
    private var callCount = 0

    override suspend fun activeSubscriptions(): SubscriptionSnapshot {
        events += "subscription"
        callCount += 1
        if (callCount == cancellationCall) {
            throw CancellationException("synthetic subscription cancellation")
        }
        return queue.takeNextOrLast(SubscriptionSnapshot.PlatformUnavailable)
    }
}

private class RecordingResolver(
    private val events: MutableList<String>,
    private val block: suspend (RecipientSet) -> ProviderThreadResolution,
) : ProviderThreadResolver {
    var callCount: Int = 0
        private set

    override suspend fun resolveExact(recipients: RecipientSet): ProviderThreadResolution {
        events += "resolve"
        callCount += 1
        return block(recipients)
    }
}

private class IncrementingClock : FirstContactClock {
    private var next = 1_000L

    override fun nowMillis(): Long = next++
}

private fun <T> ArrayDeque<T>.takeNextOrLast(fallback: T): T = when {
    isEmpty() -> fallback
    size == 1 -> first()
    else -> removeFirst()
}

private class RecordingFirstContactRepository(
    private val events: MutableList<String>,
) : FirstContactOperationRepository {
    var operation: FirstContactOperation? = null
        private set
    private var participants: List<ParticipantAddress> = emptyList()

    var cancelAfterResolutionStartCommit: Boolean = false
    var cancelAfterBindCommit: Boolean = false
    var bindRaceThread: ProviderThreadId? = null
    var bridgeConflict: Boolean = false

    fun seed(value: FirstContactOperation) {
        operation = value
        participants = TEST_PARTICIPANTS
    }

    override suspend fun reserve(
        request: FirstContactReservationRequest,
    ): FirstContactOperationResult<FirstContactOperation> {
        events += "reserve"
        if (operation != null) return FirstContactOperationResult.Conflict
        participants = request.participants
        val created = FirstContactOperation(
            id = TEST_OPERATION_ID,
            participantSetKey = FirstContactParticipantSetKey.fromParticipants(participants),
            draftId = request.draftId,
            sourceDraftRevision = request.expectedDraftRevision,
            attachmentSetEvidence = FirstContactAttachmentSetEvidence.fromAttachments(emptyList()),
            subscriptionId = request.subscriptionId,
            transport = request.transport,
            phase = FirstContactOperationPhase.RESERVED,
            providerThreadId = null,
            handoffDraftRevision = null,
            createdTimestampMillis = request.createdTimestampMillis,
            updatedTimestampMillis = request.createdTimestampMillis,
            frozenSignature = request.frozenSignature,
        )
        operation = created
        return FirstContactOperationResult.Success(created)
    }

    override suspend fun read(
        id: FirstContactOperationId,
    ): FirstContactOperationResult<FirstContactOperation> {
        events += "read"
        return operation?.takeIf { it.id == id }.toResult()
    }

    override suspend fun readByDraft(
        draftId: DraftId,
    ): FirstContactOperationResult<FirstContactOperation> {
        events += "read_by_draft"
        return operation?.takeIf { it.draftId == draftId }.toResult()
    }

    override fun observeByDraft(
        draftId: DraftId,
    ): Flow<FirstContactOperationResult<FirstContactOperation?>> = flowOf(
        FirstContactOperationResult.Success(operation?.takeIf { it.draftId == draftId }),
    )

    override suspend fun recoverySnapshot():
        FirstContactOperationResult<List<FirstContactOperation>> =
        FirstContactOperationResult.Success(listOfNotNull(operation))

    override suspend fun markResolutionStarted(
        id: FirstContactOperationId,
        expectedRevision: FirstContactOperationRevision,
        updatedTimestampMillis: Long,
    ): FirstContactOperationResult<FirstContactResolutionSnapshot> {
        events += "resolution_started"
        val current = current(id, expectedRevision, FirstContactOperationPhase.RESERVED)
            ?: return FirstContactOperationResult.StaleWrite
        val updated = current.copy(
            phase = FirstContactOperationPhase.RESOLUTION_STARTED,
            updatedTimestampMillis = updatedTimestampMillis,
        )
        operation = updated
        if (cancelAfterResolutionStartCommit) {
            cancelAfterResolutionStartCommit = false
            throw CancellationException("synthetic committed resolution-start")
        }
        return FirstContactOperationResult.Success(
            FirstContactResolutionSnapshot(updated, participants),
        )
    }

    override suspend fun bindThread(
        id: FirstContactOperationId,
        expectedRevision: FirstContactOperationRevision,
        providerThreadId: ProviderThreadId,
        updatedTimestampMillis: Long,
    ): FirstContactOperationResult<FirstContactOperation> {
        events += "bind"
        val current = current(id, expectedRevision, FirstContactOperationPhase.RESOLUTION_STARTED)
            ?: return FirstContactOperationResult.StaleWrite
        bindRaceThread?.let { other ->
            operation = current.copy(
                phase = FirstContactOperationPhase.THREAD_BOUND,
                providerThreadId = other,
                updatedTimestampMillis = updatedTimestampMillis,
            )
            return FirstContactOperationResult.Conflict
        }
        val updated = current.copy(
            phase = FirstContactOperationPhase.THREAD_BOUND,
            providerThreadId = providerThreadId,
            updatedTimestampMillis = updatedTimestampMillis,
        )
        operation = updated
        if (cancelAfterBindCommit) {
            cancelAfterBindCommit = false
            throw CancellationException("synthetic committed bind")
        }
        return FirstContactOperationResult.Success(updated)
    }

    override suspend fun markResolutionUnknown(
        id: FirstContactOperationId,
        expectedRevision: FirstContactOperationRevision,
        updatedTimestampMillis: Long,
    ): FirstContactOperationResult<FirstContactOperation> {
        events += "resolution_unknown"
        return transition(
            id,
            expectedRevision,
            setOf(FirstContactOperationPhase.RESOLUTION_STARTED),
            FirstContactOperationPhase.RESOLUTION_UNKNOWN,
            updatedTimestampMillis,
        )
    }

    override suspend fun bridgeToProviderThread(
        id: FirstContactOperationId,
        expectedRevision: FirstContactOperationRevision,
        updatedTimestampMillis: Long,
    ): FirstContactOperationResult<FirstContactBridgeSnapshot> {
        events += "bridge"
        if (bridgeConflict) return FirstContactOperationResult.Conflict
        val current = current(id, expectedRevision, FirstContactOperationPhase.THREAD_BOUND)
            ?: return FirstContactOperationResult.StaleWrite
        val thread = current.providerThreadId ?: return FirstContactOperationResult.CorruptData
        val handoffRevision = DraftRevision(
            maxOf(
                updatedTimestampMillis + 100L,
                current.sourceDraftRevision.updatedTimestampMillis + 1L,
            ),
        )
        val updated = current.copy(
            phase = FirstContactOperationPhase.HANDOFF_RESERVED,
            handoffDraftRevision = handoffRevision,
            updatedTimestampMillis = updatedTimestampMillis,
        )
        operation = updated
        return FirstContactOperationResult.Success(
            FirstContactBridgeSnapshot(
                operation = updated,
                providerDraft = Draft(
                    id = updated.draftId,
                    identity = DraftIdentity.ProviderThread(thread),
                    body = "Synthetic durable body",
                    subject = null,
                    createdTimestampMillis = 100L,
                    updatedTimestampMillis = handoffRevision.updatedTimestampMillis,
                ),
                participants = participants,
                attachments = emptyList(),
            ),
        )
    }

    override suspend fun markKnownUnsent(
        id: FirstContactOperationId,
        expectedRevision: FirstContactOperationRevision,
        proof: FirstContactKnownUnsentProof,
        updatedTimestampMillis: Long,
    ): FirstContactOperationResult<FirstContactOperation> {
        events += "known_unsent"
        assertEquals(
            FirstContactKnownUnsentProof.PROVIDER_AUTHORITY_NOT_ENTERED,
            proof,
        )
        return transition(
            id,
            expectedRevision,
            setOf(FirstContactOperationPhase.RESERVED),
            FirstContactOperationPhase.KNOWN_UNSENT,
            updatedTimestampMillis,
        )
    }

    override suspend fun release(
        id: FirstContactOperationId,
        expectedRevision: FirstContactOperationRevision,
    ): FirstContactOperationResult<Unit> {
        val current = current(id, expectedRevision, FirstContactOperationPhase.KNOWN_UNSENT)
            ?: return FirstContactOperationResult.StaleWrite
        check(current.id == id)
        operation = null
        return FirstContactOperationResult.Success(Unit)
    }

    private fun transition(
        id: FirstContactOperationId,
        expectedRevision: FirstContactOperationRevision,
        expectedPhases: Set<FirstContactOperationPhase>,
        phase: FirstContactOperationPhase,
        updatedTimestampMillis: Long,
    ): FirstContactOperationResult<FirstContactOperation> {
        val current = operation
            ?.takeIf { it.id == id && it.revision == expectedRevision && it.phase in expectedPhases }
            ?: return FirstContactOperationResult.StaleWrite
        val updated = current.copy(phase = phase, updatedTimestampMillis = updatedTimestampMillis)
        operation = updated
        return FirstContactOperationResult.Success(updated)
    }

    private fun current(
        id: FirstContactOperationId,
        expectedRevision: FirstContactOperationRevision,
        phase: FirstContactOperationPhase,
    ): FirstContactOperation? = operation?.takeIf {
        it.id == id && it.revision == expectedRevision && it.phase == phase
    }

    private fun FirstContactOperation?.toResult(): FirstContactOperationResult<FirstContactOperation> =
        this?.let { value -> FirstContactOperationResult.Success(value) }
            ?: FirstContactOperationResult.NotFound

    companion object {
        private val TEST_OPERATION_ID = FirstContactOperationId(1L)
        private val TEST_PARTICIPANTS = listOf(ParticipantAddress("+15550102020"))
    }
}
