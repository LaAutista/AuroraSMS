// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.notifications.InlineReplyDisposition
import org.aurorasms.core.notifications.InlineReplyRequest
import org.aurorasms.core.testing.FakeMessageNotifier
import org.aurorasms.core.testing.FakeMessageTransport
import org.aurorasms.core.testing.FakeRoleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InlineReplyOrchestratorTest {
    @Test
    fun submittedReplyIsOwnedOnceUsesExactTargetAndWaitsForSentCallback() = runTest {
        val conversationId = ConversationId(41)
        val recipient = ParticipantAddress("+12025550131")
        val targets = ReplyTargetRegistry(clockMillis = { NOW }).apply {
            remember("SMS:501", conversationId, recipient, AuroraSubscriptionId(2))
        }
        val observedPhases = mutableListOf<String>()
        val transport = successfulObservedTransport(conversationId, observedPhases)
        val notifier = FakeMessageNotifier()
        val operations = replyOperations()
        val orchestrator = InlineReplyOrchestrator(
            roleState = FakeRoleState(held = true),
            replyTargets = targets,
            replayGuard = inMemoryReplayGuard(),
            replyOperations = operations,
            messageTransport = transport,
            messageNotifier = notifier,
            clockMillis = { NOW },
        )
        val request = replyRequest(conversationId)

        val first = orchestrator.handle(request)
        val duplicate = orchestrator.handle(request)
        advanceUntilIdle()

        assertEquals(InlineReplyDisposition.ACCEPTED, first)
        assertEquals(InlineReplyDisposition.REJECTED, duplicate)
        assertEquals(1, transport.smsRequests.size)
        assertEquals(recipient, transport.smsRequests.single().recipients.singleSmsRecipientOrNull())
        assertEquals(AuroraSubscriptionId(2), transport.smsRequests.single().subscriptionId)
        assertEquals(
            TransportResult.OperationOrigin.INLINE_REPLY,
            transport.smsRequests.single().operationOrigin,
        )
        assertEquals(listOf("prepared", "submitting"), observedPhases)
        assertEquals(
            ReplyOperationPendingFailuresResult.Available(emptyList()),
            operations.pendingFailures(),
        )
        assertEquals(emptyList<ConversationId>(), notifier.replyFailures)
        assertEquals(emptyList<ConversationId>(), notifier.cancelledConversations)
    }

    @Test
    fun providerConversationMismatchRefusesPreparedCheckpoint() = runTest {
        val conversationId = ConversationId(416)
        val operations = replyOperations()
        val notifier = FakeMessageNotifier()
        val providerId = ProviderMessageId(ProviderKind.SMS, 9_416L)
        val transport = FakeMessageTransport().apply {
            smsResponderWithObserver = { request, observer ->
                assertEquals(
                    false,
                    observer.onPrepared(
                        providerId,
                        ConversationId(conversationId.value + 1L),
                        unitCount = 1,
                    ),
                )
                TransportResult.Failed(
                    operationId = request.operationId,
                    transport = MessageTransportKind.SMS,
                    reason = TransportResult.FailureReason.INTERNAL_ERROR,
                    retryable = false,
                    providerMessageId = providerId,
                    providerConversationId = conversationId,
                    operationOrigin = request.operationOrigin,
                )
            }
        }
        val orchestrator = InlineReplyOrchestrator(
            roleState = FakeRoleState(held = true),
            replyTargets = replyTargets(conversationId),
            replayGuard = inMemoryReplayGuard(),
            replyOperations = operations,
            messageTransport = transport,
            messageNotifier = notifier,
            clockMillis = { NOW },
        )

        assertEquals(
            InlineReplyDisposition.ACCEPTED,
            orchestrator.handle(replyRequest(conversationId)),
        )
        advanceUntilIdle()

        assertEquals(listOf(conversationId), notifier.replyFailures)
        assertNotifiedFailure(
            operations = operations,
            operationId = transport.smsRequests.single().operationId,
            conversationId = conversationId,
            failureKind = ReplyOperationFailureKind.KNOWN_UNSENT,
        )
    }

    @Test
    fun submittingCheckpointRefusalFailsClosedAsKnownUnsent() = runTest {
        val conversationId = ConversationId(412)
        val operations = replyOperations()
        val notifier = FakeMessageNotifier()
        val providerId = ProviderMessageId(ProviderKind.SMS, 9_412L)
        var platformSubmissionCount = 0
        val transport = FakeMessageTransport().apply {
            smsResponderWithObserver = { request, observer ->
                assertTrue(observer.onPrepared(providerId, conversationId, unitCount = 1))
                // A changed unit count proves that durable ownership refuses an
                // inconsistent irreversible-submission checkpoint.
                val submissionAllowed = observer.onSubmitting(
                    providerId,
                    conversationId,
                    unitCount = 2,
                )
                if (submissionAllowed) platformSubmissionCount += 1
                assertEquals(false, submissionAllowed)
                TransportResult.Failed(
                    operationId = request.operationId,
                    transport = MessageTransportKind.SMS,
                    reason = TransportResult.FailureReason.INTERNAL_ERROR,
                    retryable = true,
                    providerMessageId = providerId,
                    providerConversationId = conversationId,
                    stage = TransportResult.FailureStage.SUBMISSION,
                    operationOrigin = request.operationOrigin,
                )
            }
        }
        val orchestrator = InlineReplyOrchestrator(
            roleState = FakeRoleState(held = true),
            replyTargets = replyTargets(conversationId),
            replayGuard = inMemoryReplayGuard(),
            replyOperations = operations,
            messageTransport = transport,
            messageNotifier = notifier,
            clockMillis = { NOW },
        )
        val request = replyRequest(conversationId)

        assertEquals(InlineReplyDisposition.ACCEPTED, orchestrator.handle(request))
        assertEquals(InlineReplyDisposition.REJECTED, orchestrator.handle(request))
        advanceUntilIdle()

        assertEquals(0, platformSubmissionCount)
        assertEquals(1, transport.smsRequests.size)
        assertEquals(listOf(conversationId), notifier.replyFailures)
        assertEquals(emptyList<ConversationId>(), notifier.cancelledConversations)
        assertNotifiedFailure(
            operations = operations,
            operationId = transport.smsRequests.single().operationId,
            conversationId = conversationId,
            failureKind = ReplyOperationFailureKind.KNOWN_UNSENT,
        )
    }

    @Test
    fun runtimeFailureAfterSubmittingRecoversAsUnknownWithoutRetryOrCancellation() = runTest {
        val conversationId = ConversationId(413)
        val operations = replyOperations()
        val notifier = FakeMessageNotifier()
        val providerId = ProviderMessageId(ProviderKind.SMS, 9_413L)
        val transport = FakeMessageTransport().apply {
            smsResponderWithObserver = { _, observer ->
                assertTrue(observer.onPrepared(providerId, conversationId, unitCount = 1))
                assertTrue(observer.onSubmitting(providerId, conversationId, unitCount = 1))
                throw IllegalStateException("synthetic failure after submitting checkpoint")
            }
        }
        val orchestrator = InlineReplyOrchestrator(
            roleState = FakeRoleState(held = true),
            replyTargets = replyTargets(conversationId),
            replayGuard = inMemoryReplayGuard(),
            replyOperations = operations,
            messageTransport = transport,
            messageNotifier = notifier,
            clockMillis = { NOW },
        )
        val request = replyRequest(conversationId)

        assertEquals(InlineReplyDisposition.ACCEPTED, orchestrator.handle(request))
        assertEquals(InlineReplyDisposition.REJECTED, orchestrator.handle(request))
        advanceUntilIdle()

        assertEquals(1, transport.smsRequests.size)
        assertEquals(listOf(conversationId), notifier.replyFailures)
        assertEquals(emptyList<ConversationId>(), notifier.cancelledConversations)
        assertNotifiedFailure(
            operations = operations,
            operationId = transport.smsRequests.single().operationId,
            conversationId = conversationId,
            failureKind = ReplyOperationFailureKind.SUBMISSION_UNKNOWN,
        )
    }

    @Test
    fun cancellationAfterSubmittingIsRethrownAndDurablyRecoveredAsUnknown() = runTest {
        val conversationId = ConversationId(414)
        val operations = replyOperations()
        val notifier = FakeMessageNotifier()
        val providerId = ProviderMessageId(ProviderKind.SMS, 9_414L)
        val transport = FakeMessageTransport().apply {
            smsResponderWithObserver = { _, observer ->
                assertTrue(observer.onPrepared(providerId, conversationId, unitCount = 1))
                assertTrue(observer.onSubmitting(providerId, conversationId, unitCount = 1))
                throw CancellationException("synthetic caller cancellation")
            }
        }
        val orchestrator = InlineReplyOrchestrator(
            roleState = FakeRoleState(held = true),
            replyTargets = replyTargets(conversationId),
            replayGuard = inMemoryReplayGuard(),
            replyOperations = operations,
            messageTransport = transport,
            messageNotifier = notifier,
            clockMillis = { NOW },
        )
        val request = replyRequest(conversationId)

        var cancellationWasRethrown = false
        try {
            orchestrator.handle(request)
        } catch (_: CancellationException) {
            cancellationWasRethrown = true
        }
        assertTrue(cancellationWasRethrown)
        assertEquals(InlineReplyDisposition.REJECTED, orchestrator.handle(request))
        advanceUntilIdle()

        assertEquals(1, transport.smsRequests.size)
        assertEquals(listOf(conversationId), notifier.replyFailures)
        assertEquals(emptyList<ConversationId>(), notifier.cancelledConversations)
        assertNotifiedFailure(
            operations = operations,
            operationId = transport.smsRequests.single().operationId,
            conversationId = conversationId,
            failureKind = ReplyOperationFailureKind.SUBMISSION_UNKNOWN,
        )
    }

    @Test
    fun failureCallbackBeforeSubmittedReturnCannotBeHiddenByLaterCancellation() = runTest {
        val conversationId = ConversationId(410)
        val operations = replyOperations()
        val notifier = FakeMessageNotifier()
        val callbackHandler = InlineReplyTransportResultHandler(operations, notifier)
        val transport = FakeMessageTransport().apply {
            smsResponderWithObserver = { request, observer ->
                val providerId = ProviderMessageId(ProviderKind.SMS, 9_410L)
                assertTrue(observer.onPrepared(providerId, conversationId, unitCount = 1))
                assertTrue(observer.onSubmitting(providerId, conversationId, unitCount = 1))
                callbackHandler.handle(
                    TransportResult.Failed(
                        operationId = request.operationId,
                        transport = MessageTransportKind.SMS,
                        reason = TransportResult.FailureReason.PLATFORM_REJECTED,
                        retryable = true,
                        stage = TransportResult.FailureStage.SENT_CALLBACK,
                        providerMessageId = providerId,
                        providerConversationId = conversationId,
                        operationOrigin = request.operationOrigin,
                    ),
                )
                TransportResult.Submitted(
                    operationId = request.operationId,
                    transport = MessageTransportKind.SMS,
                    unitCount = 1,
                    providerMessageId = providerId,
                    providerConversationId = conversationId,
                    operationOrigin = request.operationOrigin,
                )
            }
        }
        val orchestrator = InlineReplyOrchestrator(
            roleState = FakeRoleState(held = true),
            replyTargets = replyTargets(conversationId),
            replayGuard = inMemoryReplayGuard(),
            replyOperations = operations,
            messageTransport = transport,
            messageNotifier = notifier,
            clockMillis = { NOW },
        )

        assertEquals(InlineReplyDisposition.ACCEPTED, orchestrator.handle(replyRequest(conversationId)))
        advanceUntilIdle()

        assertEquals(listOf(conversationId), notifier.replyFailures)
        assertEquals(emptyList<ConversationId>(), notifier.cancelledConversations)
    }

    @Test
    fun successCallbackBeforeSubmittedReturnCancelsSourceNotificationExactlyOnce() = runTest {
        val conversationId = ConversationId(415)
        val operations = replyOperations()
        val notifier = FakeMessageNotifier()
        val callbackHandler = InlineReplyTransportResultHandler(operations, notifier)
        val transport = FakeMessageTransport().apply {
            smsResponderWithObserver = { request, observer ->
                val providerId = ProviderMessageId(ProviderKind.SMS, 9_415L)
                assertTrue(observer.onPrepared(providerId, conversationId, unitCount = 1))
                assertTrue(observer.onSubmitting(providerId, conversationId, unitCount = 1))
                callbackHandler.handle(
                    TransportResult.Sent(
                        operationId = request.operationId,
                        transport = MessageTransportKind.SMS,
                        platformResultCode = 0,
                        unitIndex = 0,
                        unitCount = 1,
                        providerMessageId = providerId,
                        providerConversationId = conversationId,
                        operationOrigin = request.operationOrigin,
                    ),
                )
                TransportResult.Submitted(
                    operationId = request.operationId,
                    transport = MessageTransportKind.SMS,
                    unitCount = 1,
                    providerMessageId = providerId,
                    providerConversationId = conversationId,
                    operationOrigin = request.operationOrigin,
                )
            }
        }
        val orchestrator = InlineReplyOrchestrator(
            roleState = FakeRoleState(held = true),
            replyTargets = replyTargets(conversationId),
            replayGuard = inMemoryReplayGuard(),
            replyOperations = operations,
            transportResultHandler = callbackHandler,
            messageTransport = transport,
            messageNotifier = notifier,
            clockMillis = { NOW },
        )

        assertEquals(InlineReplyDisposition.ACCEPTED, orchestrator.handle(replyRequest(conversationId)))
        advanceUntilIdle()

        assertEquals(listOf(conversationId), notifier.cancelledConversations)
        assertEquals(emptyList<ConversationId>(), notifier.replyFailures)
    }

    @Test
    fun oldTokenRemainsBoundToItsOriginalRecipientAndSubscription() = runTest {
        val conversationId = ConversationId(411)
        val originalRecipient = ParticipantAddress("+12025550141")
        val targets = ReplyTargetRegistry(clockMillis = { NOW }).apply {
            remember(
                "SMS:501",
                conversationId,
                originalRecipient,
                AuroraSubscriptionId(1),
            )
            remember(
                "SMS:502",
                conversationId,
                ParticipantAddress("+12025550142"),
                AuroraSubscriptionId(2),
            )
        }
        val transport = successfulObservedTransport(conversationId)
        val orchestrator = InlineReplyOrchestrator(
            roleState = FakeRoleState(held = true),
            replyTargets = targets,
            replayGuard = inMemoryReplayGuard(),
            replyOperations = replyOperations(),
            messageTransport = transport,
            messageNotifier = FakeMessageNotifier(),
            clockMillis = { NOW },
        )

        assertEquals(
            InlineReplyDisposition.ACCEPTED,
            orchestrator.handle(replyRequest(conversationId, "SMS:501")),
        )
        advanceUntilIdle()

        val sent = transport.smsRequests.single()
        assertEquals(originalRecipient, sent.recipients.singleSmsRecipientOrNull())
        assertEquals(AuroraSubscriptionId(1), sent.subscriptionId)
    }

    @Test
    fun roleLossRejectsWithoutLaunchingTransport() = runTest {
        val conversationId = ConversationId(42)
        val targets = ReplyTargetRegistry(clockMillis = { NOW }).apply {
            remember(
                "SMS:501",
                conversationId,
                ParticipantAddress("+12025550132"),
                AuroraSubscriptionId(1),
            )
        }
        val transport = FakeMessageTransport()
        val orchestrator = InlineReplyOrchestrator(
            roleState = FakeRoleState(held = false),
            replyTargets = targets,
            replayGuard = inMemoryReplayGuard(),
            replyOperations = replyOperations(),
            messageTransport = transport,
            messageNotifier = FakeMessageNotifier(),
            clockMillis = { NOW },
        )

        val result = orchestrator.handle(replyRequest(conversationId))
        advanceUntilIdle()

        assertEquals(InlineReplyDisposition.REJECTED, result)
        assertEquals(0, transport.smsRequests.size)
    }

    @Test
    fun operationJournalSaturationFailsBeforeClaimOrTransport() = runTest {
        val conversationId = ConversationId(421)
        val nextIdentifier = AtomicLong(20_000L)
        val operations = ReplyOperationRegistry(
            store = InMemoryReplyOperationStore(maximumEntries = 1),
            clockMillis = { NOW },
            identifierGenerator = ReplyOperationIdentifierGenerator {
                nextIdentifier.incrementAndGet()
            },
        )
        assertTrue(
            operations.reserve(
                ConversationId(999L),
                MessageId(ProviderKind.SMS, 999L),
            ) is ReplyOperationReservationResult.Reserved,
        )
        val claims = mutableSetOf<String>()
        val transport = FakeMessageTransport()
        val notifier = FakeMessageNotifier()
        val orchestrator = InlineReplyOrchestrator(
            roleState = FakeRoleState(held = true),
            replyTargets = replyTargets(conversationId),
            replayGuard = inMemoryReplayGuard(claims),
            replyOperations = operations,
            messageTransport = transport,
            messageNotifier = notifier,
            clockMillis = { NOW },
        )

        assertEquals(InlineReplyDisposition.REJECTED, orchestrator.handle(replyRequest(conversationId)))
        advanceUntilIdle()

        assertEquals(emptySet<String>(), claims)
        assertEquals(0, transport.smsRequests.size)
        assertEquals(emptyList<ConversationId>(), notifier.replyFailures)
    }

    @Test
    fun persistedTargetSurvivesRecreationAndDurableClaimStillRejectsReplay() = runTest {
        val conversationId = ConversationId(43)
        val persistedStore = InMemoryReplyTargetStore(maximumEntries = 256)
        ReplyTargetRegistry(
            clockMillis = { NOW },
            targetStore = persistedStore,
        ).remember(
            "SMS:501",
            conversationId,
            ParticipantAddress("+12025550143"),
            AuroraSubscriptionId(2),
        )
        val durableClaims = mutableSetOf<String>()
        val replayGuard = inMemoryReplayGuard(durableClaims)
        val firstTransport = successfulObservedTransport(conversationId)
        val first = orchestrator(
            targets = ReplyTargetRegistry(
                clockMillis = { NOW },
                targetStore = persistedStore,
            ),
            replayGuard = replayGuard,
            transport = firstTransport,
        )
        val request = replyRequest(conversationId)

        assertEquals(InlineReplyDisposition.ACCEPTED, first.handle(request))
        advanceUntilIdle()

        val recreatedTransport = successfulObservedTransport(conversationId)
        val recreated = orchestrator(
            targets = ReplyTargetRegistry(
                clockMillis = { NOW },
                targetStore = persistedStore,
            ),
            replayGuard = replayGuard,
            transport = recreatedTransport,
        )
        assertEquals(InlineReplyDisposition.REJECTED, recreated.handle(request))
        advanceUntilIdle()

        assertEquals(1, firstTransport.smsRequests.size)
        assertEquals(0, recreatedTransport.smsRequests.size)
    }

    @Test
    fun targetExpiresAtTheBoundaryWithoutClaimingOrSending() = runTest {
        var now = NOW
        val conversationId = ConversationId(44)
        val targets = ReplyTargetRegistry(
            timeToLiveMillis = 100L,
            clockMillis = { now },
        ).apply {
            remember(
                "SMS:501",
                conversationId,
                ParticipantAddress("+12025550144"),
                AuroraSubscriptionId(1),
            )
        }
        val claims = mutableSetOf<String>()
        val transport = successfulObservedTransport(conversationId)
        val orchestrator = InlineReplyOrchestrator(
            roleState = FakeRoleState(held = true),
            replyTargets = targets,
            replayGuard = inMemoryReplayGuard(claims),
            replyOperations = replyOperations(clockMillis = { now }),
            messageTransport = transport,
            messageNotifier = FakeMessageNotifier(),
            clockMillis = { now },
        )
        now += 100L

        assertEquals(InlineReplyDisposition.REJECTED, orchestrator.handle(replyRequest(conversationId)))
        advanceUntilIdle()

        assertEquals(0, claims.size)
        assertEquals(0, transport.smsRequests.size)
    }

    @Test
    fun moreThanFormerMemoryBoundDoesNotMakeOldClaimReplayable() = runTest {
        val conversationId = ConversationId(45)
        val claims = mutableSetOf<String>()
        val transport = successfulObservedTransport(conversationId)
        val targets = ReplyTargetRegistry(
            maximumEntries = 512,
            clockMillis = { NOW },
        )
        val orchestrator = orchestrator(
            targets = targets,
            replayGuard = inMemoryReplayGuard(claims),
            transport = transport,
        )

        repeat(300) { index ->
            val requestId = "SMS:${index + 1}"
            targets.remember(
                requestId,
                conversationId,
                ParticipantAddress("+12025550145"),
                AuroraSubscriptionId(2),
            )
            assertEquals(
                InlineReplyDisposition.ACCEPTED,
                orchestrator.handle(replyRequest(conversationId, requestId)),
            )
        }
        assertEquals(
            InlineReplyDisposition.REJECTED,
            orchestrator.handle(replyRequest(conversationId, "SMS:1")),
        )
        advanceUntilIdle()

        assertEquals(300, transport.smsRequests.size)
    }

    @Test
    fun failedTransportPostsSafeFailureAndDoesNotCancelConversation() = runTest {
        val conversationId = ConversationId(46)
        val transport = FakeMessageTransport().apply {
            smsResponder = { request ->
                TransportResult.Rejected(
                    operationId = request.operationId,
                    transport = MessageTransportKind.SMS,
                    reason = TransportResult.FailureReason.PLATFORM_REJECTED,
                    operationOrigin = request.operationOrigin,
                )
            }
        }
        val notifier = FakeMessageNotifier()
        val orchestrator = InlineReplyOrchestrator(
            roleState = FakeRoleState(held = true),
            replyTargets = replyTargets(conversationId),
            replayGuard = inMemoryReplayGuard(),
            replyOperations = replyOperations(),
            messageTransport = transport,
            messageNotifier = notifier,
            clockMillis = { NOW },
        )

        assertEquals(InlineReplyDisposition.ACCEPTED, orchestrator.handle(replyRequest(conversationId)))
        advanceUntilIdle()

        assertEquals(listOf(conversationId), notifier.replyFailures)
        assertEquals(0, notifier.cancelledConversations.size)
    }

    private fun orchestrator(
        targets: ReplyTargetRegistry,
        replayGuard: ReplyReplayGuard,
        transport: FakeMessageTransport,
    ) = InlineReplyOrchestrator(
        roleState = FakeRoleState(held = true),
        replyTargets = targets,
        replayGuard = replayGuard,
        replyOperations = replyOperations(),
        messageTransport = transport,
        messageNotifier = FakeMessageNotifier(),
        clockMillis = { NOW },
    )

    private fun successfulObservedTransport(
        providerConversationId: ConversationId,
        observedPhases: MutableList<String> = mutableListOf(),
    ) = FakeMessageTransport().apply {
        smsResponderWithObserver = { request, observer ->
            val providerId = ProviderMessageId(ProviderKind.SMS, request.operationId.value)
            observedPhases += "prepared"
            check(observer.onPrepared(providerId, providerConversationId, unitCount = 1))
            observedPhases += "submitting"
            check(observer.onSubmitting(providerId, providerConversationId, unitCount = 1))
            TransportResult.Submitted(
                operationId = request.operationId,
                transport = MessageTransportKind.SMS,
                unitCount = 1,
                providerMessageId = providerId,
                providerConversationId = providerConversationId,
                operationOrigin = request.operationOrigin,
            )
        }
    }

    private fun assertNotifiedFailure(
        operations: ReplyOperationRegistry,
        operationId: MessageId,
        conversationId: ConversationId,
        failureKind: ReplyOperationFailureKind,
    ) {
        assertEquals(
            ReplyOperationInterruptedRecoveryResult.Notified(
                ReplyOperationPendingFailure(
                    operationId = operationId,
                    conversationId = conversationId,
                    sourceMessageId = MessageId(ProviderKind.SMS, 501L),
                    failureKind = failureKind,
                ),
            ),
            operations.recoverInterruptedOperation(operationId),
        )
    }

    private fun replyTargets(conversationId: ConversationId) =
        ReplyTargetRegistry(clockMillis = { NOW }).apply {
            remember(
                "SMS:501",
                conversationId,
                ParticipantAddress("+12025550143"),
                AuroraSubscriptionId(2),
            )
        }

    private fun inMemoryReplayGuard(
        claims: MutableSet<String> = mutableSetOf(),
    ) = ReplyReplayGuard { claim, _ -> claims.add(claim.requestId) }

    private fun replyOperations(
        clockMillis: () -> Long = { NOW },
    ): ReplyOperationRegistry {
        val nextIdentifier = AtomicLong(10_000L)
        return ReplyOperationRegistry(
            store = InMemoryReplyOperationStore(maximumEntries = 4_096),
            clockMillis = clockMillis,
            identifierGenerator = ReplyOperationIdentifierGenerator {
                nextIdentifier.incrementAndGet()
            },
        )
    }

    private fun replyRequest(
        conversationId: ConversationId,
        requestId: String = "SMS:501",
    ) = InlineReplyRequest(
        conversationId = conversationId,
        notificationId = 7,
        replyRequestId = requestId,
        text = "Synthetic reply.",
    )

    private companion object {
        const val NOW = 1_704_067_200_000L
    }
}
