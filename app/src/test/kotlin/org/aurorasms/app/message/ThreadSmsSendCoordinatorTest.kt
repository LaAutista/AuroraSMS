// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
import org.aurorasms.core.model.COMPOSER_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDeliveryFingerprint
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.state.AcknowledgedComposerSmsCallbackProof
import org.aurorasms.core.state.AcknowledgedComposerSmsReceipt
import org.aurorasms.core.state.ComposerSmsDraftClearance
import org.aurorasms.core.state.ComposerSmsOperation
import org.aurorasms.core.state.ComposerSmsOperationPhase
import org.aurorasms.core.state.ComposerSmsOperationRepository
import org.aurorasms.core.state.ComposerSmsOperationResult
import org.aurorasms.core.state.ComposerSmsOperationRevision
import org.aurorasms.core.state.ComposerSmsProviderBinding
import org.aurorasms.core.state.ComposerSmsReservation
import org.aurorasms.core.state.ComposerSmsReservationRequest
import org.aurorasms.core.state.ComposerSmsSentCompletion
import org.aurorasms.core.state.DraftId
import org.aurorasms.core.state.DraftRevision
import org.aurorasms.core.state.MessageSignature
import org.aurorasms.core.state.ConversationSubscriptionParticipantSetKey
import org.aurorasms.core.state.ConversationSubscriptionPreference
import org.aurorasms.core.state.ConversationSubscriptionPreferenceRepository
import org.aurorasms.core.state.ConversationSubscriptionRepositoryResult
import org.aurorasms.core.state.ConversationSubscriptionRevision
import org.aurorasms.core.state.ConversationSubscriptionScope
import org.aurorasms.core.telephony.ActiveSubscription
import org.aurorasms.core.telephony.IncomingSmsRecord
import org.aurorasms.core.telephony.OutgoingSmsRecord
import org.aurorasms.core.telephony.OutgoingMmsPayload
import org.aurorasms.core.telephony.OutgoingMmsProviderStatus
import org.aurorasms.core.telephony.OutgoingSmsRollbackOutcome
import org.aurorasms.core.telephony.OutgoingSmsStatusUpdateOutcome
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderPage
import org.aurorasms.core.telephony.ProviderPageRequest
import org.aurorasms.core.telephony.ProviderStoredMessage
import org.aurorasms.core.telephony.SmsProviderDataSource
import org.aurorasms.core.telephony.SmsProviderMessage
import org.aurorasms.core.telephony.SmsProviderStatus
import org.aurorasms.core.telephony.SmsSendRequest
import org.aurorasms.core.telephony.SubscriptionSnapshot
import org.aurorasms.core.testing.FakeMessageTransport
import org.aurorasms.core.testing.FakeMmsProviderDataSource
import org.aurorasms.core.testing.FakeRoleState
import org.aurorasms.core.testing.FakeSubscriptionRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadSmsSendCoordinatorTest {
    @Test
    fun sendRefusesUntilDurableRecoveryHasCompleted() = runTest {
        val fixture = fixture()

        assertEquals(ThreadSmsSendAttempt.REFUSED, fixture.coordinator.send(COMMAND))

        assertEquals(0, fixture.operations.reserveCount)
        assertEquals(0, fixture.conversations.loadCount)
        assertTrue(fixture.transport.smsRequests.isEmpty())
        assertTrue(fixture.operations.draftPreserved)
    }

    @Test
    fun sendRefusesWhenVerifiedThreadIsAssociatedWithAnotherSubscription() = runTest {
        val fixture = fixture(conversationSubscriptionId = AuroraSubscriptionId(9))
        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())

        assertEquals(ThreadSmsSendAttempt.REFUSED, fixture.coordinator.send(COMMAND))

        assertEquals(1, fixture.conversations.loadCount)
        assertEquals(0, fixture.operations.reserveCount)
        assertTrue(fixture.transport.smsRequests.isEmpty())
        assertTrue(fixture.operations.draftPreserved)
    }

    @Test
    fun exactGroupConversationNeverReservesOrSubmitsSms() = runTest {
        listOf(2, 3).forEach { participantCount ->
            val groupIdentity = IDENTITY.copy(
                participants = (1..participantCount).map { index ->
                    ParticipantAddress("+12025550${index.toString().padStart(3, '0')}")
                },
            )
            val fixture = fixture(verifiedIdentity = groupIdentity)
            assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())

            assertEquals(
                ThreadSmsSendAttempt.REFUSED,
                fixture.coordinator.send(COMMAND.copy(identity = groupIdentity)),
            )

            assertEquals(0, fixture.operations.reserveCount)
            assertTrue(fixture.transport.smsRequests.isEmpty())
            assertTrue(fixture.transport.mmsRequests.isEmpty())
            assertTrue(fixture.operations.draftPreserved)
        }
    }

    @Test
    fun exactGroupConversationSubmitsOneDurablyOwnedMmsAndCompletesExactDraft() = runTest {
        val groupIdentity = IDENTITY.copy(
            participants = listOf(
                ParticipantAddress("+12025550001"),
                ParticipantAddress("+12025550002"),
            ),
        )
        val fixture = fixture(verifiedIdentity = groupIdentity)
        val mmsProviderId = ProviderMessageId(ProviderKind.MMS, PROVIDER_ID.value)
        fixture.transport.mmsResponderWithObserver = { request, observer ->
            assertTrue(observer.onPrepared(mmsProviderId, CONVERSATION_ID, unitCount = 1))
            assertTrue(observer.onSubmitting(mmsProviderId, CONVERSATION_ID, unitCount = 1))
            TransportResult.Submitted(
                operationId = request.operationId,
                transport = MessageTransportKind.MMS,
                unitCount = 1,
                providerMessageId = mmsProviderId,
                providerConversationId = CONVERSATION_ID,
                operationOrigin = request.operationOrigin,
            )
        }

        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())
        assertEquals(
            ThreadSmsSendAttempt.STARTED,
            fixture.coordinator.send(
                COMMAND.copy(identity = groupIdentity, transport = MessageTransportKind.MMS),
            ),
        )

        val request = fixture.transport.mmsRequests.single()
        assertEquals(2, request.recipients.size)
        assertEquals(TransportResult.OperationOrigin.COMPOSER, request.operationOrigin)
        assertEquals(BODY, (request.payload as OutgoingMmsPayload.Message).text)
        assertEquals(ComposerSmsOperationPhase.PLATFORM_ACCEPTED, fixture.operations.operation?.phase)
        assertTrue(
            fixture.coordinator.handleTransportResult(
                TransportResult.Sent(
                    operationId = checkNotNull(fixture.operations.operation).operationId,
                    transport = MessageTransportKind.MMS,
                    platformResultCode = 0,
                    providerMessageId = mmsProviderId,
                    providerConversationId = CONVERSATION_ID,
                    operationOrigin = TransportResult.OperationOrigin.COMPOSER,
                ),
            ),
        )
        assertNull(fixture.operations.operation)
        assertFalse(fixture.operations.draftPreserved)
        assertEquals(
            OutgoingMmsProviderStatus.SENT,
            fixture.mmsProvider.outgoingStatusUpdates.single().third,
        )
    }

    @Test
    fun subjectUsesMmsAndRemainsBoundToAuthoritativeReservedDraft() = runTest {
        val fixture = fixture()
        fixture.operations.authoritativeSubject = "Synthetic subject"
        val mmsProviderId = ProviderMessageId(ProviderKind.MMS, PROVIDER_ID.value)
        fixture.transport.mmsResponderWithObserver = { request, observer ->
            assertTrue(observer.onPrepared(mmsProviderId, CONVERSATION_ID, 1))
            assertTrue(observer.onSubmitting(mmsProviderId, CONVERSATION_ID, 1))
            TransportResult.Submitted(
                operationId = request.operationId,
                transport = MessageTransportKind.MMS,
                unitCount = 1,
                providerMessageId = mmsProviderId,
                providerConversationId = CONVERSATION_ID,
                operationOrigin = request.operationOrigin,
            )
        }

        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())
        assertEquals(
            ThreadSmsSendAttempt.STARTED,
            fixture.coordinator.send(COMMAND.copy(transport = MessageTransportKind.MMS)),
        )

        val payload = fixture.transport.mmsRequests.single().payload as OutgoingMmsPayload.Message
        assertEquals("Synthetic subject", payload.subject)
        assertEquals(BODY, payload.text)
    }

    @Test
    fun durablePreferenceAuthorizesExplicitActiveSubscriptionInsteadOfLatestThreadSim() = runTest {
        val fixture = fixture(
            conversationSubscriptionId = AuroraSubscriptionId(9),
            subscriptionPreference = preferenceResult(SUBSCRIPTION_ID),
        )
        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())

        assertEquals(ThreadSmsSendAttempt.STARTED, fixture.coordinator.send(COMMAND))

        assertEquals(1, fixture.operations.reserveCount)
        assertEquals(SUBSCRIPTION_ID, fixture.operations.operation?.subscriptionId)
        assertEquals(SUBSCRIPTION_ID, fixture.transport.smsRequests.single().subscriptionId)
    }

    @Test
    fun missingRememberedSubscriptionRefusesWithoutReservationOrFallback() = runTest {
        val fixture = fixture(
            subscriptionPreference = preferenceResult(AuroraSubscriptionId(9)),
        )
        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())

        assertEquals(ThreadSmsSendAttempt.REFUSED, fixture.coordinator.send(COMMAND))

        assertEquals(0, fixture.operations.reserveCount)
        assertTrue(fixture.transport.smsRequests.isEmpty())
    }

    @Test
    fun onePartSubmissionAwaitsBothDurableCheckpointsAndUsesComposerOrigin() = runTest {
        val fixture = fixture()
        val observedPhases = mutableListOf<ComposerSmsOperationPhase?>()
        fixture.transport.smsResponderWithObserver = { request, observer ->
            observedPhases += fixture.operations.operation?.phase
            assertTrue(observer.onPrepared(PROVIDER_ID, CONVERSATION_ID, unitCount = 1))
            observedPhases += fixture.operations.operation?.phase
            assertTrue(observer.onSubmitting(PROVIDER_ID, CONVERSATION_ID, unitCount = 1))
            observedPhases += fixture.operations.operation?.phase
            TransportResult.Submitted(
                operationId = request.operationId,
                transport = MessageTransportKind.SMS,
                unitCount = 1,
                providerMessageId = PROVIDER_ID,
                providerConversationId = CONVERSATION_ID,
                operationOrigin = request.operationOrigin,
            )
        }

        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())
        assertEquals(ThreadSmsSendAttempt.STARTED, fixture.coordinator.send(COMMAND))

        assertEquals(
            listOf(
                ComposerSmsOperationPhase.RESERVED,
                ComposerSmsOperationPhase.PREPARED,
                ComposerSmsOperationPhase.SUBMITTING,
            ),
            observedPhases,
        )
        assertEquals(
            listOf(
                ComposerSmsOperationPhase.PREPARED,
                ComposerSmsOperationPhase.SUBMITTING,
                ComposerSmsOperationPhase.PLATFORM_ACCEPTED,
            ),
            fixture.operations.transitions,
        )
        assertEquals(TransportResult.OperationOrigin.COMPOSER, fixture.transport.smsRequests.single().operationOrigin)
        assertEquals(BODY, fixture.transport.smsRequests.single().body)
        assertEquals(ComposerSmsOperationPhase.PLATFORM_ACCEPTED, fixture.operations.operation?.phase)
        assertTrue(fixture.operations.draftPreserved)
    }

    @Test
    fun acceptedSignatureIsFrozenWithOwnerAndAppendedOnlyAtTransportBoundary() = runTest {
        val signature = checkNotNull(MessageSignature.fromUserInput("Aurora"))
        val fixture = fixture()
        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())

        assertEquals(
            ThreadSmsSendAttempt.STARTED,
            fixture.coordinator.send(COMMAND.copy(frozenSignature = signature)),
        )

        assertEquals(signature, fixture.operations.operation?.frozenSignature)
        assertEquals("$BODY\n-- \nAurora", fixture.transport.smsRequests.single().body)
        assertTrue(fixture.operations.draftPreserved)
    }

    @Test
    fun signatureThatChangesPartCountIsKnownUnsentBeforeTransport() = runTest {
        val signature = checkNotNull(MessageSignature.fromUserInput("Aurora"))
        var countedBody: String? = null
        val fixture = fixture(
            segmentCounter = SmsSegmentCounter { body ->
                countedBody = body
                if (body.endsWith("\n-- \nAurora")) 2 else 1
            },
        )
        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())

        assertEquals(
            ThreadSmsSendAttempt.STARTED,
            fixture.coordinator.send(COMMAND.copy(frozenSignature = signature)),
        )

        assertEquals("$BODY\n-- \nAurora", countedBody)
        assertEquals(ComposerSmsOperationPhase.KNOWN_UNSENT, fixture.operations.operation?.phase)
        assertTrue(fixture.transport.smsRequests.isEmpty())
        assertTrue(fixture.operations.draftPreserved)
    }

    @Test
    fun cancellationAfterSubmittingPersistsUnknownAndPreservesDraft() = runTest {
        val fixture = fixture()
        fixture.transport.smsResponderWithObserver = { _, observer ->
            assertTrue(observer.onPrepared(PROVIDER_ID, CONVERSATION_ID, unitCount = 1))
            assertTrue(observer.onSubmitting(PROVIDER_ID, CONVERSATION_ID, unitCount = 1))
            throw CancellationException("synthetic cancellation after SUBMITTING")
        }

        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())
        var cancelled = false
        try {
            fixture.coordinator.send(COMMAND)
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(
            ComposerSmsOperationPhase.SUBMISSION_UNKNOWN,
            fixture.operations.operation?.phase,
        )
        assertTrue(fixture.operations.draftPreserved)
    }

    @Test
    fun cancellationAfterReservationClassifiesKnownUnsentBeforeRethrow() = runTest {
        val fixture = fixture(
            segmentCounter = SmsSegmentCounter {
                throw CancellationException("synthetic cancellation after reservation")
            },
        )

        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())
        var cancelled = false
        try {
            fixture.coordinator.send(COMMAND)
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(ComposerSmsOperationPhase.KNOWN_UNSENT, fixture.operations.operation?.phase)
        assertTrue(fixture.transport.smsRequests.isEmpty())
        assertTrue(fixture.operations.draftPreserved)
    }

    @Test
    fun cancellationBeforeReservationReturnsExplicitRefusal() = runTest {
        val fixture = fixture()
        fixture.operations.beforeReserve = {
            throw CancellationException("synthetic cancellation before reservation")
        }

        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())
        assertEquals(ThreadSmsSendAttempt.REFUSED, fixture.coordinator.send(COMMAND))

        assertNull(fixture.operations.operation)
        assertTrue(fixture.transport.smsRequests.isEmpty())
        assertTrue(fixture.operations.draftPreserved)
    }

    @Test
    fun cancellationAfterReserveCommitReturnsStartedAndSchedulesKnownUnsentRecovery() = runTest {
        val fixture = fixture()
        fixture.operations.afterReserve = {
            throw CancellationException("synthetic cancellation after reserve commit")
        }

        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())
        assertEquals(ThreadSmsSendAttempt.STARTED, fixture.coordinator.send(COMMAND))

        assertEquals(ComposerSmsOperationPhase.RESERVED, fixture.operations.operation?.phase)
        assertTrue(fixture.transport.smsRequests.isEmpty())
        assertTrue(fixture.operations.draftPreserved)

        advanceTimeBy(250L)
        runCurrent()

        assertEquals(ComposerSmsOperationPhase.KNOWN_UNSENT, fixture.operations.operation?.phase)
        assertTrue(fixture.transport.smsRequests.isEmpty())
        assertTrue(fixture.operations.draftPreserved)
    }

    @Test
    fun typedFailureAfterReserveCommitReturnsStartedAndSchedulesKnownUnsentRecovery() = runTest {
        val fixture = fixture()
        fixture.operations.reserveResultAfterCommit = ComposerSmsOperationResult.StorageFailure(
            org.aurorasms.core.state.ComposerSmsStorageOperation.RESERVE,
        )

        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())
        assertEquals(ThreadSmsSendAttempt.STARTED, fixture.coordinator.send(COMMAND))

        assertEquals(ComposerSmsOperationPhase.RESERVED, fixture.operations.operation?.phase)
        assertTrue(fixture.transport.smsRequests.isEmpty())

        advanceTimeBy(250L)
        runCurrent()

        assertEquals(ComposerSmsOperationPhase.KNOWN_UNSENT, fixture.operations.operation?.phase)
        assertTrue(fixture.transport.smsRequests.isEmpty())
        assertTrue(fixture.operations.draftPreserved)
    }

    @Test
    fun cancellationDuringSubmittedClassificationPersistsUnknownBeforeRethrow() = runTest {
        val fixture = fixture()
        fixture.transport.smsResponderWithObserver = { request, observer ->
            assertTrue(observer.onPrepared(PROVIDER_ID, CONVERSATION_ID, unitCount = 1))
            assertTrue(observer.onSubmitting(PROVIDER_ID, CONVERSATION_ID, unitCount = 1))
            TransportResult.Submitted(
                operationId = request.operationId,
                transport = MessageTransportKind.SMS,
                unitCount = 1,
                providerMessageId = PROVIDER_ID,
                providerConversationId = CONVERSATION_ID,
                operationOrigin = request.operationOrigin,
            )
        }
        var cancelNextRead = true
        fixture.operations.beforeRead = {
            if (cancelNextRead) {
                cancelNextRead = false
                throw CancellationException("synthetic cancellation during result classification")
            }
        }

        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())
        var cancelled = false
        try {
            fixture.coordinator.send(COMMAND)
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(
            ComposerSmsOperationPhase.SUBMISSION_UNKNOWN,
            fixture.operations.operation?.phase,
        )
        assertTrue(fixture.operations.draftPreserved)
    }

    @Test
    fun typedClassificationFailureSchedulesBoundedNonSendingRecovery() = runTest {
        val fixture = fixture(segmentCounter = SmsSegmentCounter { 2 })
        fixture.operations.markKnownUnsentFailuresRemaining = 1

        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())
        assertEquals(ThreadSmsSendAttempt.STARTED, fixture.coordinator.send(COMMAND))
        assertEquals(ComposerSmsOperationPhase.RESERVED, fixture.operations.operation?.phase)

        advanceTimeBy(250L)
        runCurrent()

        assertEquals(ComposerSmsOperationPhase.KNOWN_UNSENT, fixture.operations.operation?.phase)
        assertTrue(fixture.transport.smsRequests.isEmpty())
        assertTrue(fixture.operations.draftPreserved)
    }

    @Test
    fun callbackTimeoutReadFailureSchedulesBoundedUnknownRecovery() = runTest {
        val fixture = submittedFixture()
        fixture.operations.readFailuresRemaining = 1

        advanceTimeBy(120_000L)
        runCurrent()

        assertEquals(ComposerSmsOperationPhase.PLATFORM_ACCEPTED, fixture.operations.operation?.phase)

        advanceTimeBy(250L)
        runCurrent()

        assertEquals(
            ComposerSmsOperationPhase.SUBMISSION_UNKNOWN,
            fixture.operations.operation?.phase,
        )
        assertEquals(1, fixture.transport.smsRequests.size)
        assertTrue(fixture.operations.draftPreserved)
    }

    @Test
    fun exactSentCallbackClearsOnlyAfterAppliedProviderOutcome() = runTest {
        val fixture = submittedFixture()
        fixture.provider.statusResponder = { _, _, _ ->
            assertEquals(
                ComposerSmsOperationPhase.SENT_CALLBACK_SUCCEEDED,
                fixture.operations.operation?.phase,
            )
            ProviderAccessResult.Success(OutgoingSmsStatusUpdateOutcome.APPLIED)
        }

        assertTrue(fixture.coordinator.handleTransportResult(fixture.exactSentCallback()))

        assertEquals(1, fixture.provider.statusCalls.size)
        assertEquals(SmsProviderStatus.COMPLETE, fixture.provider.statusCalls.single().status)
        assertEquals(1, fixture.operations.completeCount)
        assertNull(fixture.operations.operation)
        assertFalse(fixture.operations.draftPreserved)
    }

    @Test
    fun sentCompletionNotFoundPublishesExactlyOneCompletionEpoch() = runTest {
        val fixture = submittedFixture()
        val callback = fixture.exactSentCallback()
        fixture.operations.completeResultAfterCommit = ComposerSmsOperationResult.NotFound

        assertTrue(fixture.coordinator.handleTransportResult(callback))

        assertNull(fixture.operations.operation)
        assertFalse(fixture.operations.draftPreserved)
        assertEquals(1L, fixture.coordinator.observe(THREAD_ID).first().completionEpoch)

        assertFalse(fixture.coordinator.handleTransportResult(callback))
        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())
        assertEquals(1L, fixture.coordinator.observe(THREAD_ID).first().completionEpoch)
    }

    @Test
    fun unreadableSentCompletionHandoffPublishesOneEpochAfterExactVerification() = runTest {
        val fixture = submittedFixture()
        fixture.operations.completeResultAfterCommit = ComposerSmsOperationResult.StorageFailure(
            org.aurorasms.core.state.ComposerSmsStorageOperation.COMPLETE_SENT,
        )
        fixture.operations.readFailuresAfterCompleteCommit = 1

        assertTrue(fixture.coordinator.handleTransportResult(fixture.exactSentCallback()))

        assertNull(fixture.operations.operation)
        assertFalse(fixture.operations.draftPreserved)
        assertEquals(0L, fixture.coordinator.observe(THREAD_ID).first().completionEpoch)

        advanceTimeBy(250L)
        runCurrent()

        assertEquals(1L, fixture.coordinator.observe(THREAD_ID).first().completionEpoch)
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(1L, fixture.coordinator.observe(THREAD_ID).first().completionEpoch)
    }

    @Test
    fun unknownAcknowledgementNotFoundIsTreatedAsCommittedRemoval() = runTest {
        val fixture = fixture(
            initialOperation = operationIn(ComposerSmsOperationPhase.SUBMISSION_UNKNOWN),
        )
        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())
        fixture.operations.acknowledgeResultAfterCommit = ComposerSmsOperationResult.NotFound

        assertTrue(fixture.coordinator.acknowledgeSubmissionUnknown(THREAD_ID))

        assertNull(fixture.operations.operation)
        assertTrue(fixture.operations.draftPreserved)
    }

    @Test
    fun unreadableUnknownAcknowledgementPublishesReleaseEpochAfterExactVerification() = runTest {
        val fixture = fixture(
            initialOperation = operationIn(ComposerSmsOperationPhase.SUBMISSION_UNKNOWN),
        )
        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())
        fixture.operations.acknowledgeResultAfterCommit = ComposerSmsOperationResult.StorageFailure(
            org.aurorasms.core.state.ComposerSmsStorageOperation.ACKNOWLEDGE,
        )
        fixture.operations.readFailuresAfterAcknowledgeCommit = 1

        assertFalse(fixture.coordinator.acknowledgeSubmissionUnknown(THREAD_ID))
        assertNull(fixture.operations.operation)
        assertEquals(
            0L,
            fixture.coordinator.observe(THREAD_ID).first().unknownAcknowledgementEpoch,
        )

        advanceTimeBy(250L)
        runCurrent()

        assertEquals(
            1L,
            fixture.coordinator.observe(THREAD_ID).first().unknownAcknowledgementEpoch,
        )
        assertTrue(fixture.operations.draftPreserved)
    }

    @Test
    fun lateExactSuccessAfterUnknownAcknowledgementReconcilesOnlyProviderAndKeepsDraft() = runTest {
        val fixture = fixture(
            initialOperation = operationIn(ComposerSmsOperationPhase.SUBMISSION_UNKNOWN),
        )
        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())
        val callback = fixture.exactSentCallback()

        assertTrue(fixture.coordinator.acknowledgeSubmissionUnknown(THREAD_ID))
        assertNull(fixture.operations.operation)
        assertEquals(
            AcknowledgedComposerSmsCallbackProof.AWAITING_CALLBACK,
            fixture.operations.acknowledgedReceipt?.callbackProof,
        )

        assertTrue(
            fixture.coordinator.handleTransportResult(
                callback.copy(
                    providerMessageId = ProviderMessageId(ProviderKind.SMS, PROVIDER_ID.value + 1L),
                ),
            ),
        )
        assertTrue(fixture.provider.statusCalls.isEmpty())
        assertEquals(
            AcknowledgedComposerSmsCallbackProof.AWAITING_CALLBACK,
            fixture.operations.acknowledgedReceipt?.callbackProof,
        )

        assertTrue(fixture.coordinator.handleTransportResult(callback))

        assertNull(fixture.operations.acknowledgedReceipt)
        assertEquals(SmsProviderStatus.COMPLETE, fixture.provider.statusCalls.single().status)
        assertTrue(fixture.operations.draftPreserved)
    }

    @Test
    fun checkpointedLateSuccessRetriesProviderReconciliationDuringRecovery() = runTest {
        val fixture = fixture(
            initialOperation = operationIn(ComposerSmsOperationPhase.SUBMISSION_UNKNOWN),
        )
        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())
        val callback = fixture.exactSentCallback()
        assertTrue(fixture.coordinator.acknowledgeSubmissionUnknown(THREAD_ID))
        fixture.provider.statusResponder = { _, _, _ ->
            ProviderAccessResult.Unavailable("synthetic provider outage")
        }

        assertTrue(fixture.coordinator.handleTransportResult(callback))
        assertEquals(
            AcknowledgedComposerSmsCallbackProof.SENT,
            fixture.operations.acknowledgedReceipt?.callbackProof,
        )
        assertTrue(fixture.operations.draftPreserved)

        fixture.provider.statusResponder = { _, _, _ ->
            ProviderAccessResult.Success(OutgoingSmsStatusUpdateOutcome.APPLIED)
        }
        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())

        assertNull(fixture.operations.acknowledgedReceipt)
        assertEquals(listOf(SmsProviderStatus.COMPLETE, SmsProviderStatus.COMPLETE),
            fixture.provider.statusCalls.map { it.status })
        assertTrue(fixture.operations.draftPreserved)
    }

    @Test
    fun lateExactFailureAfterUnknownAcknowledgementMarksProviderFailedAndKeepsDraft() = runTest {
        val fixture = fixture(
            initialOperation = operationIn(ComposerSmsOperationPhase.SUBMISSION_UNKNOWN),
        )
        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())
        val operation = checkNotNull(fixture.operations.operation)
        val callback = TransportResult.Failed(
            operationId = operation.operationId,
            transport = MessageTransportKind.SMS,
            reason = TransportResult.FailureReason.PLATFORM_REJECTED,
            retryable = true,
            providerMessageId = PROVIDER_ID,
            providerConversationId = CONVERSATION_ID,
            stage = TransportResult.FailureStage.SENT_CALLBACK,
            operationOrigin = TransportResult.OperationOrigin.COMPOSER,
        )
        assertTrue(fixture.coordinator.acknowledgeSubmissionUnknown(THREAD_ID))

        assertTrue(fixture.coordinator.handleTransportResult(callback))

        assertNull(fixture.operations.acknowledgedReceipt)
        assertEquals(SmsProviderStatus.FAILED, fixture.provider.statusCalls.single().status)
        assertTrue(fixture.operations.draftPreserved)
    }

    @Test
    fun foreignAndMismatchedCallbacksAreConsumedOnlyByTheirExactDurableOwner() = runTest {
        val fixture = submittedFixture()
        val operation = checkNotNull(fixture.operations.operation)
        val mismatched = fixture.exactSentCallback().copy(
            providerMessageId = ProviderMessageId(ProviderKind.SMS, PROVIDER_ID.value + 1L),
        )
        val foreign = fixture.exactSentCallback().copy(
            operationId = MessageId(ProviderKind.PENDING_OPERATION, operation.operationId.value + 1L),
        )
        val wrongOrigin = fixture.exactSentCallback().copy(
            operationOrigin = TransportResult.OperationOrigin.INLINE_REPLY,
        )

        assertTrue(fixture.coordinator.handleTransportResult(mismatched))
        assertFalse(fixture.coordinator.handleTransportResult(foreign))
        assertFalse(fixture.coordinator.handleTransportResult(wrongOrigin))

        assertEquals(ComposerSmsOperationPhase.PLATFORM_ACCEPTED, fixture.operations.operation?.phase)
        assertEquals(0, fixture.operations.completeCount)
        assertTrue(fixture.provider.statusCalls.isEmpty())
        assertTrue(fixture.operations.draftPreserved)
    }

    @Test
    fun sentCallbackProviderFailureRemainsDurableAndRecoveryCompletesItLater() = runTest {
        val fixture = submittedFixture()
        fixture.provider.statusResponder = { _, _, _ ->
            ProviderAccessResult.Unavailable("synthetic provider outage")
        }

        assertTrue(fixture.coordinator.handleTransportResult(fixture.exactSentCallback()))

        assertEquals(ComposerSmsOperationPhase.SENT_CALLBACK_SUCCEEDED, fixture.operations.operation?.phase)
        assertEquals(0, fixture.operations.completeCount)
        assertTrue(fixture.operations.draftPreserved)

        fixture.provider.statusResponder = { _, _, _ ->
            ProviderAccessResult.Success(OutgoingSmsStatusUpdateOutcome.APPLIED)
        }
        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())

        assertNull(fixture.operations.operation)
        assertEquals(1, fixture.operations.completeCount)
        assertFalse(fixture.operations.draftPreserved)
        assertEquals(2, fixture.provider.statusCalls.size)
    }

    @Test
    fun exactSentProofSurvivesTransientRoomReadFailure() = runTest {
        val fixture = submittedFixture()
        fixture.operations.readFailuresRemaining = 1

        assertFalse(fixture.coordinator.handleTransportResult(fixture.exactSentCallback()))
        assertEquals(ComposerSmsOperationPhase.PLATFORM_ACCEPTED, fixture.operations.operation?.phase)
        assertTrue(fixture.operations.draftPreserved)

        advanceTimeBy(250L)
        runCurrent()

        assertNull(fixture.operations.operation)
        assertEquals(1, fixture.operations.completeCount)
        assertFalse(fixture.operations.draftPreserved)
    }

    @Test
    fun exactSentProofSurvivesTransientCheckpointFailure() = runTest {
        val fixture = submittedFixture()
        fixture.operations.markSentCallbackSucceededFailuresRemaining = 1

        assertTrue(fixture.coordinator.handleTransportResult(fixture.exactSentCallback()))
        assertEquals(ComposerSmsOperationPhase.PLATFORM_ACCEPTED, fixture.operations.operation?.phase)
        assertTrue(fixture.operations.draftPreserved)

        advanceTimeBy(250L)
        runCurrent()

        assertNull(fixture.operations.operation)
        assertEquals(1, fixture.operations.completeCount)
        assertFalse(fixture.operations.draftPreserved)
    }

    @Test
    fun duplicateExactSuccessRetriesCheckpointedSettlementAfterProviderOutage() = runTest {
        val fixture = submittedFixture()
        val callback = fixture.exactSentCallback()
        fixture.provider.statusResponder = { _, _, _ ->
            ProviderAccessResult.Unavailable("synthetic provider outage")
        }

        assertTrue(fixture.coordinator.handleTransportResult(callback))
        assertEquals(ComposerSmsOperationPhase.SENT_CALLBACK_SUCCEEDED, fixture.operations.operation?.phase)
        assertEquals(0, fixture.operations.completeCount)

        fixture.provider.statusResponder = { _, _, _ ->
            ProviderAccessResult.Success(OutgoingSmsStatusUpdateOutcome.APPLIED)
        }
        assertTrue(fixture.coordinator.handleTransportResult(callback))

        assertNull(fixture.operations.operation)
        assertEquals(1, fixture.operations.completeCount)
        assertEquals(2, fixture.provider.statusCalls.size)
        assertFalse(fixture.operations.draftPreserved)
    }

    @Test
    fun exactSuccessTreatsEveryNonMutatingProviderDispositionAsTerminal() = runTest {
        listOf(
            OutgoingSmsStatusUpdateOutcome.ROW_ABSENT,
            OutgoingSmsStatusUpdateOutcome.OWNERSHIP_CONFLICT,
        ).forEach { disposition ->
            val fixture = submittedFixture()
            fixture.provider.statusResponder = { _, _, _ -> ProviderAccessResult.Success(disposition) }

            assertTrue(fixture.coordinator.handleTransportResult(fixture.exactSentCallback()))

            assertNull(fixture.operations.operation)
            assertEquals(1, fixture.operations.completeCount)
            assertFalse(fixture.operations.draftPreserved)
        }
    }

    @Test
    fun knownUnsentTreatsEveryNonMutatingProviderDispositionAsTerminalForExplicitRetry() = runTest {
        listOf(
            OutgoingSmsStatusUpdateOutcome.ROW_ABSENT,
            OutgoingSmsStatusUpdateOutcome.OWNERSHIP_CONFLICT,
        ).forEach { disposition ->
            val fixture = fixture(initialOperation = operationIn(ComposerSmsOperationPhase.KNOWN_UNSENT))
            fixture.provider.statusResponder = { _, _, _ -> ProviderAccessResult.Success(disposition) }
            fixture.transport.smsResponderWithObserver = { request, _ ->
                TransportResult.Rejected(
                    operationId = request.operationId,
                    transport = MessageTransportKind.SMS,
                    reason = TransportResult.FailureReason.PLATFORM_REJECTED,
                    operationOrigin = request.operationOrigin,
                )
            }
            assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())

            assertEquals(ThreadSmsSendAttempt.STARTED, fixture.coordinator.send(COMMAND))

            assertEquals(1, fixture.operations.acknowledgeCount)
            assertEquals(1, fixture.operations.reserveCount)
            assertEquals(1, fixture.transport.smsRequests.size)
        }
    }

    @Test
    fun deferredCleanupDoesNotBlockAnUnrelatedThreadAfterValidSnapshot() = runTest {
        val orphan = operationIn(
            phase = ComposerSmsOperationPhase.SENT_CALLBACK_SUCCEEDED,
            providerThreadId = ProviderThreadId(404L),
        )
        val fixture = fixture(initialOperation = orphan)
        fixture.provider.statusResponder = { _, _, _ ->
            ProviderAccessResult.Unavailable("synthetic orphan cleanup outage")
        }
        fixture.transport.smsResponderWithObserver = { request, _ ->
            TransportResult.Rejected(
                operationId = request.operationId,
                transport = MessageTransportKind.SMS,
                reason = TransportResult.FailureReason.PLATFORM_REJECTED,
                operationOrigin = request.operationOrigin,
            )
        }

        assertEquals(
            ThreadSmsRecoveryResult.READY_WITH_DEFERRED_OPERATIONS,
            fixture.coordinator.recover(),
        )
        assertEquals(
            ThreadSmsSendPhase.IDLE,
            fixture.coordinator.observe(THREAD_ID).first().phase,
        )
        assertEquals(ThreadSmsSendAttempt.STARTED, fixture.coordinator.send(COMMAND))
        assertEquals(1, fixture.transport.smsRequests.size)
    }

    @Test
    fun deferredCleanupStillBlocksItsExactThread() = runTest {
        val fixture = submittedFixture()
        fixture.provider.statusResponder = { _, _, _ ->
            ProviderAccessResult.Unavailable("synthetic exact-thread cleanup outage")
        }
        assertTrue(fixture.coordinator.handleTransportResult(fixture.exactSentCallback()))

        assertEquals(
            ThreadSmsRecoveryResult.READY_WITH_DEFERRED_OPERATIONS,
            fixture.coordinator.recover(),
        )
        assertEquals(ThreadSmsSendAttempt.REFUSED, fixture.coordinator.send(COMMAND))
        assertEquals(1, fixture.transport.smsRequests.size)
        assertEquals(ComposerSmsOperationPhase.SENT_CALLBACK_SUCCEEDED, fixture.operations.operation?.phase)
    }

    @Test
    fun fenceDuringRecoveryCannotPublishReadyFromTheOlderGeneration() = runTest {
        val fixture = fixture()
        val snapshotEntered = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        fixture.operations.beforeRecoverySnapshot = {
            snapshotEntered.complete(Unit)
            releaseSnapshot.await()
        }

        val recovery = async { fixture.coordinator.recover() }
        snapshotEntered.await()
        fixture.coordinator.fence()
        releaseSnapshot.complete(Unit)

        assertEquals(ThreadSmsRecoveryResult.DEFERRED, recovery.await())
        assertEquals(
            ThreadSmsSendPhase.RECOVERY_PENDING,
            fixture.coordinator.observe(THREAD_ID).first().phase,
        )
        assertEquals(ThreadSmsSendAttempt.REFUSED, fixture.coordinator.send(COMMAND))
    }

    @Test
    fun fenceDuringPreparedCheckpointFailsClosedAndRollsBackKnownUnsent() = runTest {
        val fixture = fixture()
        fixture.operations.afterMarkPrepared = fixture.coordinator::fence
        fixture.transport.smsResponderWithObserver = { request, observer ->
            assertFalse(observer.onPrepared(PROVIDER_ID, CONVERSATION_ID, unitCount = 1))
            request.preBoundaryFailure()
        }
        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())

        assertEquals(ThreadSmsSendAttempt.STARTED, fixture.coordinator.send(COMMAND))

        assertEquals(ComposerSmsOperationPhase.KNOWN_UNSENT, fixture.operations.operation?.phase)
        assertEquals(1, fixture.provider.rollbackCalls.size)
        assertTrue(fixture.operations.draftPreserved)
    }

    @Test
    fun roleLossDuringSubmittingCheckpointFailsClosedAndRollsBackKnownUnsent() = runTest {
        val fixture = fixture()
        fixture.operations.afterMarkSubmitting = { fixture.role.held = false }
        fixture.transport.smsResponderWithObserver = { request, observer ->
            assertTrue(observer.onPrepared(PROVIDER_ID, CONVERSATION_ID, unitCount = 1))
            assertFalse(observer.onSubmitting(PROVIDER_ID, CONVERSATION_ID, unitCount = 1))
            request.preBoundaryFailure()
        }
        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())

        assertEquals(ThreadSmsSendAttempt.STARTED, fixture.coordinator.send(COMMAND))

        assertEquals(ComposerSmsOperationPhase.KNOWN_UNSENT, fixture.operations.operation?.phase)
        assertEquals(1, fixture.provider.rollbackCalls.size)
        assertTrue(fixture.operations.draftPreserved)
    }

    @Test
    fun preBoundaryProofRemainsKnownUnsentWhenProviderCleanupIsUnavailable() = runTest {
        val fixture = fixture()
        fixture.operations.afterMarkSubmitting = { fixture.role.held = false }
        fixture.provider.rollbackResponder = { _, _ ->
            ProviderAccessResult.Unavailable("synthetic cleanup outage")
        }
        fixture.transport.smsResponderWithObserver = { request, observer ->
            assertTrue(observer.onPrepared(PROVIDER_ID, CONVERSATION_ID, unitCount = 1))
            assertFalse(observer.onSubmitting(PROVIDER_ID, CONVERSATION_ID, unitCount = 1))
            request.preBoundaryFailure()
        }
        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())

        assertEquals(ThreadSmsSendAttempt.STARTED, fixture.coordinator.send(COMMAND))

        assertEquals(ComposerSmsOperationPhase.KNOWN_UNSENT, fixture.operations.operation?.phase)
        assertEquals(1, fixture.provider.rollbackCalls.size)
        assertTrue(fixture.operations.draftPreserved)
    }

    @Test
    fun completionEpochMapRetainsOnlyTheMostRecentThreads() {
        var epochs: Map<Long, Long> = emptyMap()
        repeat(MAXIMUM_COMPLETION_EPOCH_THREADS + 1) { index ->
            epochs = boundedCompletionEpochsAfterRecord(
                current = epochs,
                providerThreadId = ProviderThreadId(index.toLong() + 1L),
                completionEpoch = index.toLong() + 1L,
            )
        }

        assertEquals(MAXIMUM_COMPLETION_EPOCH_THREADS, epochs.size)
        assertFalse(epochs.containsKey(1L))
        assertEquals(
            MAXIMUM_COMPLETION_EPOCH_THREADS.toLong() + 1L,
            epochs[MAXIMUM_COMPLETION_EPOCH_THREADS.toLong() + 1L],
        )
    }

    @Test
    fun failureAfterSubmittingBecomesUnknownWithoutClearingOrAutomaticRetry() = runTest {
        val fixture = fixture()
        fixture.transport.smsResponderWithObserver = { _, observer ->
            assertTrue(observer.onPrepared(PROVIDER_ID, CONVERSATION_ID, unitCount = 1))
            assertTrue(observer.onSubmitting(PROVIDER_ID, CONVERSATION_ID, unitCount = 1))
            error("synthetic failure beyond the irreversible checkpoint")
        }
        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())

        assertEquals(ThreadSmsSendAttempt.STARTED, fixture.coordinator.send(COMMAND))
        assertEquals(ComposerSmsOperationPhase.SUBMISSION_UNKNOWN, fixture.operations.operation?.phase)
        assertTrue(fixture.operations.draftPreserved)
        assertEquals(1, fixture.transport.smsRequests.size)

        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())
        assertEquals(1, fixture.transport.smsRequests.size)
        assertEquals(ComposerSmsOperationPhase.SUBMISSION_UNKNOWN, fixture.operations.operation?.phase)
        assertTrue(fixture.operations.draftPreserved)
    }

    @Test
    fun knownUnsentFailurePreservesDraftAndCanBeExplicitlyRetried() = runTest {
        val fixture = fixture()
        fixture.transport.smsResponderWithObserver = { request, _ ->
            TransportResult.Rejected(
                operationId = request.operationId,
                transport = MessageTransportKind.SMS,
                reason = TransportResult.FailureReason.PLATFORM_REJECTED,
                operationOrigin = request.operationOrigin,
            )
        }
        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())

        assertEquals(ThreadSmsSendAttempt.STARTED, fixture.coordinator.send(COMMAND))
        assertEquals(ComposerSmsOperationPhase.KNOWN_UNSENT, fixture.operations.operation?.phase)
        assertTrue(fixture.operations.draftPreserved)

        assertEquals(ThreadSmsSendAttempt.STARTED, fixture.coordinator.send(COMMAND))

        assertEquals(2, fixture.transport.smsRequests.size)
        assertEquals(2, fixture.operations.reserveCount)
        assertEquals(1, fixture.operations.acknowledgeCount)
        assertEquals(ComposerSmsOperationPhase.KNOWN_UNSENT, fixture.operations.operation?.phase)
        assertTrue(fixture.operations.draftPreserved)
    }

    @Test
    fun exactFailedSentCallbackPreservesDraftAndAllowsAnExplicitRetry() = runTest {
        val fixture = submittedFixture()
        val operation = checkNotNull(fixture.operations.operation)
        val failedCallback = TransportResult.Failed(
            operationId = operation.operationId,
            transport = MessageTransportKind.SMS,
            reason = TransportResult.FailureReason.PLATFORM_REJECTED,
            retryable = true,
            providerMessageId = PROVIDER_ID,
            providerConversationId = CONVERSATION_ID,
            stage = TransportResult.FailureStage.SENT_CALLBACK,
            operationOrigin = TransportResult.OperationOrigin.COMPOSER,
        )

        assertTrue(fixture.coordinator.handleTransportResult(failedCallback))
        assertEquals(ComposerSmsOperationPhase.KNOWN_UNSENT, fixture.operations.operation?.phase)
        assertEquals(SmsProviderStatus.FAILED, fixture.provider.statusCalls.single().status)
        assertTrue(fixture.operations.draftPreserved)

        assertEquals(ThreadSmsSendAttempt.STARTED, fixture.coordinator.send(COMMAND))

        assertEquals(2, fixture.transport.smsRequests.size)
        assertEquals(2, fixture.operations.reserveCount)
        assertEquals(1, fixture.operations.acknowledgeCount)
        assertEquals(ComposerSmsOperationPhase.PLATFORM_ACCEPTED, fixture.operations.operation?.phase)
        assertTrue(fixture.operations.draftPreserved)
    }

    @Test
    fun recoveryMapsEveryDurablePhaseWithoutSubmittingSms() = runTest {
        val cases = listOf(
            RecoveryCase(ComposerSmsOperationPhase.RESERVED, ComposerSmsOperationPhase.KNOWN_UNSENT),
            RecoveryCase(ComposerSmsOperationPhase.PREPARED, ComposerSmsOperationPhase.KNOWN_UNSENT),
            RecoveryCase(ComposerSmsOperationPhase.SUBMITTING, ComposerSmsOperationPhase.SUBMISSION_UNKNOWN),
            RecoveryCase(ComposerSmsOperationPhase.PLATFORM_ACCEPTED, ComposerSmsOperationPhase.SUBMISSION_UNKNOWN),
            RecoveryCase(ComposerSmsOperationPhase.SENT_CALLBACK_SUCCEEDED, expectedPhase = null),
            RecoveryCase(ComposerSmsOperationPhase.SUBMISSION_UNKNOWN, ComposerSmsOperationPhase.SUBMISSION_UNKNOWN),
            RecoveryCase(ComposerSmsOperationPhase.KNOWN_UNSENT, ComposerSmsOperationPhase.KNOWN_UNSENT),
        )

        cases.forEach { case ->
            val operation = operationIn(case.startingPhase)
            val fixture = fixture(initialOperation = operation)

            assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())
            assertEquals(case.expectedPhase, fixture.operations.operation?.phase)
            assertTrue(fixture.transport.smsRequests.isEmpty())

            when (case.startingPhase) {
                ComposerSmsOperationPhase.PREPARED ->
                    assertEquals(1, fixture.provider.rollbackCalls.size)
                ComposerSmsOperationPhase.SENT_CALLBACK_SUCCEEDED -> {
                    assertEquals(SmsProviderStatus.COMPLETE, fixture.provider.statusCalls.single().status)
                    assertEquals(1, fixture.operations.completeCount)
                }
                ComposerSmsOperationPhase.KNOWN_UNSENT ->
                    assertEquals(SmsProviderStatus.FAILED, fixture.provider.statusCalls.single().status)
                else -> Unit
            }
        }
    }

    private suspend fun TestScope.submittedFixture(): Fixture {
        val fixture = fixture()
        fixture.transport.smsResponderWithObserver = { request, observer ->
            assertTrue(observer.onPrepared(PROVIDER_ID, CONVERSATION_ID, unitCount = 1))
            assertTrue(observer.onSubmitting(PROVIDER_ID, CONVERSATION_ID, unitCount = 1))
            TransportResult.Submitted(
                operationId = request.operationId,
                transport = MessageTransportKind.SMS,
                unitCount = 1,
                providerMessageId = PROVIDER_ID,
                providerConversationId = CONVERSATION_ID,
                operationOrigin = request.operationOrigin,
            )
        }
        assertEquals(ThreadSmsRecoveryResult.READY, fixture.coordinator.recover())
        assertEquals(ThreadSmsSendAttempt.STARTED, fixture.coordinator.send(COMMAND))
        assertEquals(ComposerSmsOperationPhase.PLATFORM_ACCEPTED, fixture.operations.operation?.phase)
        return fixture
    }

    private fun TestScope.fixture(
        initialOperation: ComposerSmsOperation? = null,
        conversationSubscriptionId: AuroraSubscriptionId? = SUBSCRIPTION_ID,
        verifiedIdentity: VerifiedConversationIdentity = IDENTITY,
        segmentCounter: SmsSegmentCounter = SmsSegmentCounter { 1 },
        subscriptionPreference:
            ConversationSubscriptionRepositoryResult<ConversationSubscriptionPreference> =
            ConversationSubscriptionRepositoryResult.NotFound,
    ): Fixture {
        val operations = RecordingComposerRepository(initialOperation = initialOperation)
        val provider = RecordingSmsProvider()
        val transport = FakeMessageTransport()
        val conversations = ExactConversationRepository(conversationSubscriptionId, verifiedIdentity)
        val role = FakeRoleState(held = true)
        val mmsProvider = FakeMmsProviderDataSource()
        val coordinator = ThreadSmsSendCoordinator(
            applicationScope = backgroundScope,
            roleState = role,
            conversations = conversations,
            subscriptions = FakeSubscriptionRepository(
                SubscriptionSnapshot.Available(
                    listOf(
                        ActiveSubscription(
                            id = SUBSCRIPTION_ID,
                            slotIndex = 0,
                            displayLabel = "Synthetic SIM",
                            smsCapable = true,
                        ),
                    ),
                ),
            ),
            operations = operations,
            transport = transport,
            smsProvider = provider,
            mmsProvider = mmsProvider,
            subscriptionPreferences = FixedConversationSubscriptionPreferenceRepository(
                subscriptionPreference,
            ),
            segmentCounter = segmentCounter,
            nowMillis = { operations.nextClockValue() },
        )
        return Fixture(coordinator, operations, provider, mmsProvider, transport, conversations, role)
    }

    private fun Fixture.exactSentCallback(): TransportResult.Sent {
        val operation = checkNotNull(operations.operation)
        return TransportResult.Sent(
            operationId = operation.operationId,
            transport = MessageTransportKind.SMS,
            platformResultCode = 0,
            providerMessageId = PROVIDER_ID,
            providerConversationId = CONVERSATION_ID,
            operationOrigin = TransportResult.OperationOrigin.COMPOSER,
        )
    }

    private data class Fixture(
        val coordinator: ThreadSmsSendCoordinator,
        val operations: RecordingComposerRepository,
        val provider: RecordingSmsProvider,
        val mmsProvider: FakeMmsProviderDataSource,
        val transport: FakeMessageTransport,
        val conversations: ExactConversationRepository,
        val role: FakeRoleState,
    )

    private data class RecoveryCase(
        val startingPhase: ComposerSmsOperationPhase,
        val expectedPhase: ComposerSmsOperationPhase?,
    )

    internal companion object {
        val THREAD_ID = ProviderThreadId(41L)
        val CONVERSATION_ID = ConversationId(THREAD_ID.value)
        val RECIPIENT = ParticipantAddress("+12025550141")
        val SUBSCRIPTION_ID = AuroraSubscriptionId(2)
        val DRAFT_ID = DraftId(7L)
        val DRAFT_REVISION = DraftRevision(20L)
        val PROVIDER_ID = ProviderMessageId(ProviderKind.SMS, 901L)
        const val BODY = "One safe SMS unit"
        val IDENTITY = VerifiedConversationIdentity(
            providerThreadId = THREAD_ID,
            generationId = 3L,
            participants = listOf(RECIPIENT),
        )
        val COMMAND = ThreadSmsSendCommand(
            identity = IDENTITY,
            subscriptionId = SUBSCRIPTION_ID,
            draftId = DRAFT_ID,
            draftRevision = DRAFT_REVISION,
        )

        fun preferenceResult(
            subscriptionId: AuroraSubscriptionId,
        ): ConversationSubscriptionRepositoryResult<ConversationSubscriptionPreference> =
            ConversationSubscriptionRepositoryResult.Success(
                ConversationSubscriptionPreference(
                    scope = ConversationSubscriptionScope(
                        participantSetKey =
                            ConversationSubscriptionParticipantSetKey.fromParticipants(
                                IDENTITY.participants,
                            ),
                        providerThreadId = THREAD_ID,
                    ),
                    subscriptionId = subscriptionId,
                    revision = ConversationSubscriptionRevision(1L),
                    updatedTimestampMillis = 1L,
                ),
            )

        fun SmsSendRequest.preBoundaryFailure(): TransportResult.Failed = TransportResult.Failed(
            operationId = operationId,
            transport = MessageTransportKind.SMS,
            reason = TransportResult.FailureReason.ROLE_NOT_HELD,
            retryable = true,
            providerMessageId = PROVIDER_ID,
            providerConversationId = CONVERSATION_ID,
            stage = TransportResult.FailureStage.SUBMISSION,
            operationOrigin = operationOrigin,
        )

        fun operationIn(
            phase: ComposerSmsOperationPhase,
            providerThreadId: ProviderThreadId = THREAD_ID,
        ): ComposerSmsOperation =
            ComposerSmsOperation(
                operationId = MessageId(
                    ProviderKind.PENDING_OPERATION,
                    COMPOSER_OPERATION_ID_BOUNDARY + 91L,
                ),
                providerThreadId = providerThreadId,
                draftId = DRAFT_ID,
                draftRevision = DRAFT_REVISION,
                subscriptionId = SUBSCRIPTION_ID,
                phase = phase,
                providerBinding = if (phase == ComposerSmsOperationPhase.RESERVED) {
                    null
                } else {
                    ComposerSmsProviderBinding(
                        PROVIDER_ID,
                        ConversationId(providerThreadId.value),
                        1,
                    )
                },
                createdTimestampMillis = 30L,
                updatedTimestampMillis = 31L,
            )
    }
}

private class FixedConversationSubscriptionPreferenceRepository(
    private val result:
        ConversationSubscriptionRepositoryResult<ConversationSubscriptionPreference>,
) : ConversationSubscriptionPreferenceRepository {
    override suspend fun read(
        scope: ConversationSubscriptionScope,
    ): ConversationSubscriptionRepositoryResult<ConversationSubscriptionPreference> = result

    override suspend fun set(
        scope: ConversationSubscriptionScope,
        subscriptionId: AuroraSubscriptionId,
        expectedRevision: ConversationSubscriptionRevision?,
        updatedTimestampMillis: Long,
    ): ConversationSubscriptionRepositoryResult<ConversationSubscriptionPreference> =
        ConversationSubscriptionRepositoryResult.CorruptData
}

private class ExactConversationRepository(
    private val associatedSubscriptionId: AuroraSubscriptionId?,
    private val verifiedIdentity: VerifiedConversationIdentity,
) : ConversationRepository {
    override val invalidations: Flow<ConversationInvalidation> = emptyFlow()
    var loadCount: Int = 0
        private set

    override suspend fun loadInbox(request: ConversationPageRequest): ConversationPageResult =
        throw AssertionError("Composer send must not enumerate the inbox")

    override suspend fun loadConversation(providerThreadId: ProviderThreadId): ConversationLookupResult {
        loadCount += 1
        check(providerThreadId == ThreadSmsSendCoordinatorTest.THREAD_ID)
        return ConversationLookupResult.Found(
            summary = ConversationSummary(
                providerThreadId = providerThreadId,
                latestLocalRowId = 1L,
                latestProviderMessageId = ProviderMessageId(ProviderKind.SMS, 1L),
                latestTimestampMillis = 1L,
                latestSentTimestampMillis = null,
                latestDirection = MessageDirection.INCOMING,
                latestBox = MessageBox.INBOX,
                latestStatus = MessageStatus.COMPLETE,
                latestSubscriptionId = associatedSubscriptionId,
                latestSenderAddress = verifiedIdentity.participants.first(),
                latestSnippet = null,
                latestAttachmentCount = 0,
                latestAttachmentTypeSummary = "",
                latestRead = true,
                indexedMessageCount = 1L,
                indexedUnreadCount = 0L,
                participants = verifiedIdentity.participants,
                indexedParticipantCount = verifiedIdentity.participants.size,
                participantsTruncated = false,
            ),
            coverage = IndexCoverage(
                generationId = 3L,
                state = IndexRunState.COMPLETE,
                indexedMessageCount = 1L,
                smsExhausted = true,
                mmsExhausted = true,
                pendingChanges = false,
            ),
            verifiedIdentity = verifiedIdentity,
        )
    }
}

private class RecordingComposerRepository(
    initialOperation: ComposerSmsOperation?,
) : ComposerSmsOperationRepository {
    private val observed = MutableStateFlow<ComposerSmsOperationResult<ComposerSmsOperation?>>(
        ComposerSmsOperationResult.Success(initialOperation),
    )
    private var idSequence = 100L
    private var clock = 100L

    var operation: ComposerSmsOperation? = initialOperation
        private set
    var acknowledgedReceipt: AcknowledgedComposerSmsReceipt? = null
        private set
    var reserveCount: Int = 0
        private set
    var completeCount: Int = 0
        private set
    var acknowledgeCount: Int = 0
        private set
    var draftPreserved: Boolean = true
        private set
    val transitions = mutableListOf<ComposerSmsOperationPhase>()
    var beforeRecoverySnapshot: suspend () -> Unit = {}
    var beforeReserve: suspend () -> Unit = {}
    var afterReserve: suspend () -> Unit = {}
    var reserveResultAfterCommit: ComposerSmsOperationResult<ComposerSmsReservation>? = null
    var beforeRead: suspend () -> Unit = {}
    var afterMarkPrepared: () -> Unit = {}
    var afterMarkSubmitting: () -> Unit = {}
    var readFailuresRemaining: Int = 0
    var markSentCallbackSucceededFailuresRemaining: Int = 0
    var markKnownUnsentFailuresRemaining: Int = 0
    var completeResultAfterCommit: ComposerSmsOperationResult<ComposerSmsSentCompletion>? = null
    var readFailuresAfterCompleteCommit: Int = 0
    var acknowledgeResultAfterCommit: ComposerSmsOperationResult<Unit>? = null
    var readFailuresAfterAcknowledgeCommit: Int = 0

    fun nextClockValue(): Long = clock++

    override suspend fun reserve(
        request: ComposerSmsReservationRequest,
    ): ComposerSmsOperationResult<ComposerSmsReservation> {
        beforeReserve()
        reserveCount += 1
        if (operation?.providerThreadId == request.providerThreadId) {
            return ComposerSmsOperationResult.Conflict
        }
        if (
            request.providerThreadId != ThreadSmsSendCoordinatorTest.THREAD_ID ||
            request.draftId != ThreadSmsSendCoordinatorTest.DRAFT_ID ||
            request.expectedDraftRevision != ThreadSmsSendCoordinatorTest.DRAFT_REVISION
        ) {
            return ComposerSmsOperationResult.IneligibleDraft
        }
        val created = ComposerSmsOperation(
            operationId = MessageId(
                ProviderKind.PENDING_OPERATION,
                COMPOSER_OPERATION_ID_BOUNDARY + idSequence++,
            ),
            providerThreadId = request.providerThreadId,
            draftId = request.draftId,
            draftRevision = request.expectedDraftRevision,
            subscriptionId = request.subscriptionId,
            phase = ComposerSmsOperationPhase.RESERVED,
            providerBinding = null,
            createdTimestampMillis = request.createdTimestampMillis,
            updatedTimestampMillis = request.createdTimestampMillis,
            frozenSignature = request.frozenSignature,
            transport = request.transport,
        )
        publish(created)
        afterReserve()
        reserveResultAfterCommit?.let { return it }
        return ComposerSmsOperationResult.Success(
            ComposerSmsReservation(
                created,
                ThreadSmsSendCoordinatorTest.BODY,
                authoritativeSubject,
            ),
        )
    }

    var authoritativeSubject: String? = null

    override suspend fun read(
        operationId: MessageId,
    ): ComposerSmsOperationResult<ComposerSmsOperation> {
        beforeRead()
        if (readFailuresRemaining > 0) {
            readFailuresRemaining -= 1
            return ComposerSmsOperationResult.StorageFailure(
                org.aurorasms.core.state.ComposerSmsStorageOperation.READ,
            )
        }
        return operation?.takeIf { it.operationId == operationId }
            ?.let { ComposerSmsOperationResult.Success(it) }
            ?: ComposerSmsOperationResult.NotFound
    }

    override fun observeByThread(
        providerThreadId: ProviderThreadId,
    ): Flow<ComposerSmsOperationResult<ComposerSmsOperation?>> = observed.map { result ->
        when (result) {
            is ComposerSmsOperationResult.Success -> ComposerSmsOperationResult.Success(
                result.value?.takeIf { it.providerThreadId == providerThreadId },
            )
            else -> result
        }
    }

    override suspend fun recoverySnapshot(): ComposerSmsOperationResult<List<ComposerSmsOperation>> {
        beforeRecoverySnapshot()
        return ComposerSmsOperationResult.Success(listOfNotNull(operation))
    }

    override suspend fun acknowledgedRecoverySnapshot():
        ComposerSmsOperationResult<List<AcknowledgedComposerSmsReceipt>> =
        ComposerSmsOperationResult.Success(listOfNotNull(acknowledgedReceipt))

    override suspend fun readAcknowledged(
        operationId: MessageId,
    ): ComposerSmsOperationResult<AcknowledgedComposerSmsReceipt> =
        acknowledgedReceipt?.takeIf { it.operationId == operationId }
            ?.let { ComposerSmsOperationResult.Success(it) }
            ?: ComposerSmsOperationResult.NotFound

    override suspend fun markPrepared(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation> = transition(
        operationId,
        expectedRevision,
        setOf(ComposerSmsOperationPhase.RESERVED),
        ComposerSmsOperationPhase.PREPARED,
        providerBinding,
        updatedTimestampMillis,
    ).also { if (it is ComposerSmsOperationResult.Success) afterMarkPrepared() }

    override suspend fun markSubmitting(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation> = transition(
        operationId,
        expectedRevision,
        setOf(ComposerSmsOperationPhase.PREPARED),
        ComposerSmsOperationPhase.SUBMITTING,
        providerBinding,
        updatedTimestampMillis,
    ).also { if (it is ComposerSmsOperationResult.Success) afterMarkSubmitting() }

    override suspend fun markPlatformAccepted(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation> = transition(
        operationId,
        expectedRevision,
        setOf(ComposerSmsOperationPhase.SUBMITTING),
        ComposerSmsOperationPhase.PLATFORM_ACCEPTED,
        providerBinding,
        updatedTimestampMillis,
    )

    override suspend fun markSubmissionUnknown(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation> = transition(
        operationId,
        expectedRevision,
        setOf(
            ComposerSmsOperationPhase.SUBMITTING,
            ComposerSmsOperationPhase.PLATFORM_ACCEPTED,
        ),
        ComposerSmsOperationPhase.SUBMISSION_UNKNOWN,
        providerBinding,
        updatedTimestampMillis,
    )

    override suspend fun markSentCallbackSucceeded(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation> {
        if (markSentCallbackSucceededFailuresRemaining > 0) {
            markSentCallbackSucceededFailuresRemaining -= 1
            return ComposerSmsOperationResult.StorageFailure(
                org.aurorasms.core.state.ComposerSmsStorageOperation.TRANSITION,
            )
        }
        return transition(
            operationId,
            expectedRevision,
            setOf(
                ComposerSmsOperationPhase.SUBMITTING,
                ComposerSmsOperationPhase.PLATFORM_ACCEPTED,
                ComposerSmsOperationPhase.SUBMISSION_UNKNOWN,
            ),
            ComposerSmsOperationPhase.SENT_CALLBACK_SUCCEEDED,
            providerBinding,
            updatedTimestampMillis,
        )
    }

    override suspend fun markKnownUnsent(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation> {
        if (markKnownUnsentFailuresRemaining > 0) {
            markKnownUnsentFailuresRemaining -= 1
            return ComposerSmsOperationResult.StorageFailure(
                org.aurorasms.core.state.ComposerSmsStorageOperation.TRANSITION,
            )
        }
        val current = current(operationId, expectedRevision) ?: return ComposerSmsOperationResult.StaleWrite
        if (current.phase !in setOf(ComposerSmsOperationPhase.RESERVED, ComposerSmsOperationPhase.PREPARED)) {
            return ComposerSmsOperationResult.PhaseMismatch
        }
        return publishTransition(
            current.copy(
                phase = ComposerSmsOperationPhase.KNOWN_UNSENT,
                updatedTimestampMillis = updatedTimestampMillis,
            ),
        )
    }

    override suspend fun markSentCallbackFailed(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation> = transition(
        operationId,
        expectedRevision,
        setOf(
            ComposerSmsOperationPhase.SUBMITTING,
            ComposerSmsOperationPhase.PLATFORM_ACCEPTED,
            ComposerSmsOperationPhase.SUBMISSION_UNKNOWN,
        ),
        ComposerSmsOperationPhase.KNOWN_UNSENT,
        providerBinding,
        updatedTimestampMillis,
    )

    override suspend fun completeSentAndRemove(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
    ): ComposerSmsOperationResult<ComposerSmsSentCompletion> {
        val current = current(operationId, expectedRevision) ?: return ComposerSmsOperationResult.StaleWrite
        if (
            current.phase != ComposerSmsOperationPhase.SENT_CALLBACK_SUCCEEDED ||
            current.providerBinding != providerBinding
        ) {
            return ComposerSmsOperationResult.PhaseMismatch
        }
        completeCount += 1
        draftPreserved = false
        publish(null)
        completeResultAfterCommit?.let { result ->
            readFailuresRemaining += readFailuresAfterCompleteCommit
            return result
        }
        return ComposerSmsOperationResult.Success(
            ComposerSmsSentCompletion(ComposerSmsDraftClearance.CLEARED),
        )
    }

    override suspend fun acknowledgeAndRemove(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        acknowledgedTimestampMillis: Long,
    ): ComposerSmsOperationResult<Unit> {
        val current = current(operationId, expectedRevision) ?: return ComposerSmsOperationResult.StaleWrite
        if (current.phase !in setOf(
                ComposerSmsOperationPhase.KNOWN_UNSENT,
                ComposerSmsOperationPhase.SUBMISSION_UNKNOWN,
            )
        ) {
            return ComposerSmsOperationResult.PhaseMismatch
        }
        acknowledgeCount += 1
        if (current.phase == ComposerSmsOperationPhase.SUBMISSION_UNKNOWN) {
            acknowledgedReceipt = AcknowledgedComposerSmsReceipt(
                operationId = current.operationId,
                providerBinding = checkNotNull(current.providerBinding),
                callbackProof = AcknowledgedComposerSmsCallbackProof.AWAITING_CALLBACK,
                acknowledgedTimestampMillis = acknowledgedTimestampMillis,
                updatedTimestampMillis = acknowledgedTimestampMillis,
            )
        }
        publish(null)
        acknowledgeResultAfterCommit?.let { result ->
            readFailuresRemaining += readFailuresAfterAcknowledgeCommit
            return result
        }
        return ComposerSmsOperationResult.Success(Unit)
    }

    override suspend fun markAcknowledgedSent(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<AcknowledgedComposerSmsReceipt> =
        markAcknowledgedCallback(
            operationId,
            expectedRevision,
            providerBinding,
            AcknowledgedComposerSmsCallbackProof.SENT,
            updatedTimestampMillis,
        )

    override suspend fun markAcknowledgedFailed(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<AcknowledgedComposerSmsReceipt> =
        markAcknowledgedCallback(
            operationId,
            expectedRevision,
            providerBinding,
            AcknowledgedComposerSmsCallbackProof.FAILED,
            updatedTimestampMillis,
        )

    private fun markAcknowledgedCallback(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        callbackProof: AcknowledgedComposerSmsCallbackProof,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<AcknowledgedComposerSmsReceipt> {
        val current = acknowledgedReceipt?.takeIf {
            it.operationId == operationId && it.revision == expectedRevision
        } ?: return ComposerSmsOperationResult.StaleWrite
        if (current.providerBinding != providerBinding) {
            return ComposerSmsOperationResult.ProviderMismatch
        }
        if (current.callbackProof != AcknowledgedComposerSmsCallbackProof.AWAITING_CALLBACK) {
            return if (current.callbackProof == callbackProof) {
                ComposerSmsOperationResult.Success(current)
            } else {
                ComposerSmsOperationResult.PhaseMismatch
            }
        }
        return ComposerSmsOperationResult.Success(
            current.copy(
                callbackProof = callbackProof,
                updatedTimestampMillis = updatedTimestampMillis,
            ).also { acknowledgedReceipt = it },
        )
    }

    override suspend fun completeAcknowledged(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        callbackProof: AcknowledgedComposerSmsCallbackProof,
    ): ComposerSmsOperationResult<Unit> {
        val current = acknowledgedReceipt?.takeIf {
            it.operationId == operationId && it.revision == expectedRevision
        } ?: return ComposerSmsOperationResult.NotFound
        if (current.providerBinding != providerBinding) {
            return ComposerSmsOperationResult.ProviderMismatch
        }
        if (
            callbackProof == AcknowledgedComposerSmsCallbackProof.AWAITING_CALLBACK ||
            current.callbackProof != callbackProof
        ) {
            return ComposerSmsOperationResult.PhaseMismatch
        }
        acknowledgedReceipt = null
        return ComposerSmsOperationResult.Success(Unit)
    }

    private fun transition(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        allowed: Set<ComposerSmsOperationPhase>,
        target: ComposerSmsOperationPhase,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation> {
        val current = current(operationId, expectedRevision) ?: return ComposerSmsOperationResult.StaleWrite
        if (current.phase !in allowed) return ComposerSmsOperationResult.PhaseMismatch
        if (current.providerBinding != null && current.providerBinding != providerBinding) {
            return ComposerSmsOperationResult.ProviderMismatch
        }
        return publishTransition(
            current.copy(
                phase = target,
                providerBinding = providerBinding,
                updatedTimestampMillis = updatedTimestampMillis,
            ),
        )
    }

    private fun current(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
    ): ComposerSmsOperation? = operation?.takeIf {
        it.operationId == operationId && it.revision == expectedRevision
    }

    private fun publishTransition(
        updated: ComposerSmsOperation,
    ): ComposerSmsOperationResult<ComposerSmsOperation> {
        transitions += updated.phase
        publish(updated)
        return ComposerSmsOperationResult.Success(updated)
    }

    private fun publish(updated: ComposerSmsOperation?) {
        operation = updated
        observed.value = ComposerSmsOperationResult.Success(updated)
    }
}

private class RecordingSmsProvider : SmsProviderDataSource {
    data class StatusCall(
        val id: ProviderMessageId,
        val conversationId: ConversationId,
        val status: SmsProviderStatus,
    )

    data class RollbackCall(
        val id: ProviderMessageId,
        val conversationId: ConversationId,
    )

    val statusCalls = mutableListOf<StatusCall>()
    val rollbackCalls = mutableListOf<RollbackCall>()
    var statusResponder:
        (ProviderMessageId, ConversationId, SmsProviderStatus) ->
            ProviderAccessResult<OutgoingSmsStatusUpdateOutcome> = { _, _, _ ->
        ProviderAccessResult.Success(OutgoingSmsStatusUpdateOutcome.APPLIED)
    }
    var rollbackResponder:
        (ProviderMessageId, ConversationId) -> ProviderAccessResult<OutgoingSmsRollbackOutcome> = { _, _ ->
        ProviderAccessResult.Success(OutgoingSmsRollbackOutcome.TERMINALIZED)
    }

    override suspend fun count(): ProviderAccessResult<Long> = ProviderAccessResult.Success(0L)

    override suspend fun readPage(
        request: ProviderPageRequest,
    ): ProviderAccessResult<ProviderPage<SmsProviderMessage>> =
        ProviderAccessResult.Success(ProviderPage(emptyList(), null, exhausted = true))

    override suspend fun insertIncoming(
        message: IncomingSmsRecord,
    ): ProviderAccessResult<ProviderStoredMessage> =
        ProviderAccessResult.Unsupported("unused synthetic incoming insert")

    override suspend fun markIncomingHandled(
        deliveryFingerprint: MessageDeliveryFingerprint,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
    ): ProviderAccessResult<Unit> = ProviderAccessResult.Unsupported("unused synthetic incoming update")

    override suspend fun insertOutgoing(
        message: OutgoingSmsRecord,
    ): ProviderAccessResult<ProviderStoredMessage> =
        ProviderAccessResult.Unsupported("transport owns synthetic provider insertion")

    override suspend fun rollbackOutgoing(
        id: ProviderMessageId,
        conversationId: ConversationId,
    ): ProviderAccessResult<OutgoingSmsRollbackOutcome> {
        rollbackCalls += RollbackCall(id, conversationId)
        return rollbackResponder(id, conversationId)
    }

    override suspend fun updateOutgoingStatus(
        id: ProviderMessageId,
        conversationId: ConversationId,
        status: SmsProviderStatus,
    ): ProviderAccessResult<OutgoingSmsStatusUpdateOutcome> {
        statusCalls += StatusCall(id, conversationId, status)
        return statusResponder(id, conversationId, status)
    }

    override suspend fun updateStatus(
        id: ProviderMessageId,
        status: SmsProviderStatus,
    ): ProviderAccessResult<Unit> = ProviderAccessResult.Unsupported("legacy status path must not be used")
}
