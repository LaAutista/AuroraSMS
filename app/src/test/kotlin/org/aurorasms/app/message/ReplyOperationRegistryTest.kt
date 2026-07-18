// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.aurorasms.core.model.INLINE_REPLY_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.telephony.SmsProviderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyOperationRegistryTest {
    @Test
    fun collisionRetriesWithFreshIdentifierAndPersistsExactLifetime() {
        val store = ControllableStore(maximumEntries = 4).apply {
            reservationOverrides += ReplyOperationStoreReservationResult.Collision
        }
        val identifiers = ArrayDeque(listOf(41L, 42L))
        val registry = registry(
            store = store,
            clockMillis = { 10_000L },
            retentionMillis = 2_000L,
            identifierGenerator = { identifiers.removeFirst() },
        )

        assertEquals(
            ReplyOperationReservationResult.Reserved(pendingOperation(42L)),
            registry.reserve(CONVERSATION, SOURCE_MESSAGE),
        )
        assertEquals(
            listOf(inlineOperationValue(41L), inlineOperationValue(42L)),
            store.reservationAttempts.map { it.operationId },
        )
        assertEquals(SOURCE_MESSAGE, store.reservationAttempts.last().sourceMessageId)
        assertEquals(10_000L, store.reservationAttempts.last().createdAtMillis)
        assertEquals(12_000L, store.reservationAttempts.last().expiresAtMillis)
    }

    @Test
    fun reserveDistinguishesCollisionFullAndPersistenceFailure() {
        val collisions = ControllableStore(maximumEntries = 4).apply {
            repeat(3) { reservationOverrides += ReplyOperationStoreReservationResult.Collision }
        }
        assertSame(
            ReplyOperationReservationResult.IdentifierCollision,
            registry(
                store = collisions,
                maximumIdentifierAttempts = 3,
                identifierGenerator = { 51L },
            ).reserve(CONVERSATION, SOURCE_MESSAGE),
        )

        val full = ControllableStore(maximumEntries = 1)
        val fullRegistry = registry(store = full, identifierGenerator = SequenceGenerator(61L, 62L))
        assertTrue(
            fullRegistry.reserve(CONVERSATION, SOURCE_MESSAGE) is
                ReplyOperationReservationResult.Reserved,
        )
        assertSame(
            ReplyOperationReservationResult.Full,
            fullRegistry.reserve(OTHER_CONVERSATION, OTHER_SOURCE_MESSAGE),
        )

        val failed = ControllableStore(maximumEntries = 4).apply {
            reservationOverrides += ReplyOperationStoreReservationResult.PersistenceFailure
        }
        assertSame(
            ReplyOperationReservationResult.PersistenceFailure,
            registry(store = failed, identifierGenerator = { 71L })
                .reserve(CONVERSATION, SOURCE_MESSAGE),
        )
    }

    @Test
    fun invalidGeneratedValuesAndMaximumClockFailClosed() {
        val store = ControllableStore(maximumEntries = 4)
        assertSame(
            ReplyOperationReservationResult.IdentifierCollision,
            registry(
                store = store,
                maximumIdentifierAttempts = 3,
                identifierGenerator = SequenceGenerator(0L, -1L, Long.MIN_VALUE),
            ).reserve(CONVERSATION, SOURCE_MESSAGE),
        )
        assertTrue(store.reservationAttempts.isEmpty())

        assertSame(
            ReplyOperationReservationResult.PersistenceFailure,
            registry(
                store = store,
                clockMillis = { Long.MAX_VALUE },
                identifierGenerator = { 81L },
            ).reserve(CONVERSATION, SOURCE_MESSAGE),
        )
    }

    @Test
    fun callbackMayBindCountAndSuccessfulTerminalTombstoneAbsorbsDuplicates() {
        val store = ControllableStore(maximumEntries = 4)
        val registry = registry(store = store, identifierGenerator = { 91L })
        val operationId = registry.reserveOperation(CONVERSATION)
        assertSame(ReplyOperationPhaseTransitionResult.Transitioned, registry.markClaimed(operationId))

        assertEquals(
            ReplyOperationSentResult.SuccessPending(CONVERSATION, SOURCE_MESSAGE),
            registry.recordSent(operationId, unitIndex = 0, unitCount = 1),
        )
        assertEquals(
            ReplyOperationPendingSuccessesResult.Available(
                listOf(ReplyOperationPending(operationId, CONVERSATION, SOURCE_MESSAGE)),
            ),
            registry.pendingSuccesses(),
        )
        assertSame(
            ReplyOperationAcknowledgementResult.Acknowledged,
            registry.acknowledgeSuccessCancellation(operationId),
        )
        assertSame(ReplyOperationSubmittedResult.SuccessComplete, registry.recordSubmitted(operationId, 1))
        assertEquals(
            ReplyOperationSentResult.SuccessComplete(CONVERSATION, SOURCE_MESSAGE),
            registry.recordSent(operationId, unitIndex = 0, unitCount = 1),
        )
        assertEquals(
            ReplyOperationFailureResult.SuccessTerminal(CONVERSATION),
            registry.markFailurePending(operationId),
        )
    }

    @Test
    fun multipartProgressIsOrderIndependentDuplicateSafeAndCountBound() {
        val registry = registry(
            store = ControllableStore(maximumEntries = 4),
            identifierGenerator = { 101L },
        )
        val operationId = registry.reserveOperation(CONVERSATION)
        prepareSubmitting(registry, operationId, unitCount = 3)

        assertSame(ReplyOperationSubmittedResult.Tracked, registry.recordSubmitted(operationId, 3))
        assertEquals(
            ReplyOperationSentResult.Pending(CONVERSATION, duplicate = false),
            registry.recordSent(operationId, unitIndex = 2, unitCount = 3),
        )
        assertEquals(
            ReplyOperationSentResult.Pending(CONVERSATION, duplicate = true),
            registry.recordSent(operationId, unitIndex = 2, unitCount = 3),
        )
        assertSame(
            ReplyOperationSentResult.UnitCountMismatch,
            registry.recordSent(operationId, unitIndex = 0, unitCount = 2),
        )
        assertTrue(
            registry.recordSent(operationId, unitIndex = 0, unitCount = 3) is
                ReplyOperationSentResult.Pending,
        )
        assertEquals(
            ReplyOperationSentResult.SuccessPending(CONVERSATION, SOURCE_MESSAGE),
            registry.recordSent(operationId, unitIndex = 1, unitCount = 3),
        )
    }

    @Test
    fun notifiedFailureTombstoneAbsorbsLateSuccessAndDuplicateFailure() {
        val registry = registry(
            store = ControllableStore(maximumEntries = 4),
            identifierGenerator = { 111L },
        )
        val operationId = registry.reserveOperation(CONVERSATION)
        assertSame(ReplyOperationPhaseTransitionResult.Transitioned, registry.markClaimed(operationId))

        assertEquals(
            ReplyOperationFailureResult.Pending(CONVERSATION, duplicate = false),
            registry.markFailurePending(operationId),
        )
        assertSame(ReplyOperationSubmittedResult.FailurePending, registry.recordSubmitted(operationId, 1))
        assertSame(
            ReplyOperationAcknowledgementResult.Acknowledged,
            registry.acknowledgeFailureNotification(operationId),
        )
        assertEquals(
            ReplyOperationFailureResult.Notified(CONVERSATION),
            registry.markFailurePending(operationId),
        )
        assertEquals(
            ReplyOperationSentResult.FailureNotified(CONVERSATION),
            registry.recordSent(operationId, unitIndex = 0, unitCount = 1),
        )
        assertEquals(
            ReplyOperationSuccessResult.FailureNotified(CONVERSATION),
            registry.markSuccessPending(operationId),
        )
        assertEquals(
            ReplyOperationPendingFailuresResult.Available(emptyList()),
            registry.pendingFailures(),
        )
    }

    @Test
    fun concurrentFailureTransitionHasExactlyOneFirstWriter() {
        val registry = registry(
            store = ControllableStore(maximumEntries = 4),
            identifierGenerator = { 115L },
        )
        val operationId = registry.reserveOperation(CONVERSATION)
        assertSame(ReplyOperationPhaseTransitionResult.Transitioned, registry.markClaimed(operationId))
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val results = Collections.synchronizedList(mutableListOf<ReplyOperationFailureResult>())
        repeat(8) {
            Thread {
                start.await()
                results += registry.markFailurePending(operationId)
                done.countDown()
            }.start()
        }

        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, results.count { it == ReplyOperationFailureResult.Pending(CONVERSATION, false) })
        assertEquals(7, results.count { it == ReplyOperationFailureResult.Pending(CONVERSATION, true) })
    }

    @Test
    fun wallClockRollbackNeverShortensTerminalExpiry() {
        var nowMillis = 10_000L
        val registry = registry(
            store = ControllableStore(maximumEntries = 1),
            clockMillis = { nowMillis },
            retentionMillis = 1_000L,
            identifierGenerator = { 117L },
        )
        val operationId = registry.reserveOperation(CONVERSATION)
        assertSame(ReplyOperationPhaseTransitionResult.Transitioned, registry.markClaimed(operationId))

        nowMillis = 500L
        assertTrue(registry.markFailurePending(operationId) is ReplyOperationFailureResult.Pending)
        nowMillis = 10_999L
        assertEquals(ReplyOperationCleanupResult.Success(0), registry.cleanupExpired())
        nowMillis = 11_000L
        assertEquals(ReplyOperationCleanupResult.Success(1), registry.cleanupExpired())
    }

    @Test
    fun providerOutboxIsMonotonicAndExactAcknowledgementCannotClearSupersedingStatus() {
        val registry = registry(
            store = ControllableStore(maximumEntries = 4),
            identifierGenerator = SequenceGenerator(118L, 119L, 120L),
        )
        val operationId = registry.reserveOperation(CONVERSATION)
        val providerId = ProviderMessageId(ProviderKind.SMS, 8_001L)
        prepareSubmitting(registry, operationId, providerId, unitCount = 1)

        assertSame(
            ReplyOperationSubmittedResult.Tracked,
            registry.recordSubmitted(operationId, unitCount = 1, providerMessageId = providerId),
        )
        assertEquals(
            ReplyOperationSentResult.SuccessPending(CONVERSATION, SOURCE_MESSAGE),
            registry.recordSent(operationId, 0, 1, providerId),
        )
        val complete = ReplyOperationProviderUpdate(
            operationId,
            CONVERSATION,
            providerId,
            SmsProviderStatus.COMPLETE,
        )
        assertEquals(
            ReplyOperationPendingProviderUpdateResult.Available(complete),
            registry.pendingProviderUpdate(operationId),
        )

        assertSame(
            ReplyOperationProviderStatusResult.SuccessPending,
            registry.recordDeliveryFailure(operationId, 0, 1, providerId),
        )
        val deliveryFailed = complete.copy(status = SmsProviderStatus.DELIVERY_FAILED)
        assertSame(
            ReplyOperationProviderAcknowledgementResult.Stale,
            registry.acknowledgeProviderUpdate(complete),
        )
        assertEquals(
            ReplyOperationPendingProviderUpdatesResult.Available(listOf(deliveryFailed)),
            registry.pendingProviderUpdates(),
        )
        assertSame(
            ReplyOperationProviderAcknowledgementResult.Acknowledged,
            registry.acknowledgeProviderUpdate(deliveryFailed),
        )
        assertEquals(
            ReplyOperationPendingProviderUpdateResult.Available(null),
            registry.pendingProviderUpdate(operationId),
        )

        val failedOperation = registry.reserveOperation(OTHER_CONVERSATION)
        val failedProviderId = ProviderMessageId(ProviderKind.SMS, 8_002L)
        assertSame(
            ReplyOperationPhaseTransitionResult.Transitioned,
            registry.markClaimed(failedOperation),
        )
        assertTrue(
            registry.markFailurePending(failedOperation, failedProviderId) is
                ReplyOperationFailureResult.Pending,
        )
        assertSame(
            ReplyOperationProviderStatusResult.Unchanged,
            registry.recordDeliveryFailure(failedOperation, 0, 1, failedProviderId),
        )
        assertEquals(
            SmsProviderStatus.FAILED,
            (registry.pendingProviderUpdate(failedOperation) as
                ReplyOperationPendingProviderUpdateResult.Available).update?.status,
        )

        val deliveredFirst = registry.reserveOperation(CONVERSATION)
        val deliveredFirstProvider = ProviderMessageId(ProviderKind.SMS, 8_003L)
        assertSame(
            ReplyOperationPhaseTransitionResult.Transitioned,
            registry.markClaimed(deliveredFirst),
        )
        assertSame(
            ReplyOperationProviderStatusResult.SuccessPending,
            registry.recordDeliveryFailure(deliveredFirst, 0, 1, deliveredFirstProvider),
        )
        assertEquals(
            ReplyOperationSentResult.SuccessPending(CONVERSATION, SOURCE_MESSAGE),
            registry.recordSent(deliveredFirst, 0, 1, deliveredFirstProvider),
        )
        assertEquals(
            SmsProviderStatus.DELIVERY_FAILED,
            (registry.pendingProviderUpdate(deliveredFirst) as
                ReplyOperationPendingProviderUpdateResult.Available).update?.status,
        )
    }

    @Test
    fun providerBindingIsDurableOptionalAndMismatchesFailClosed() {
        val store = ControllableStore(maximumEntries = 4)
        val firstRegistry = registry(store = store, identifierGenerator = { 119L })
        val operationId = firstRegistry.reserveOperation(CONVERSATION)
        val firstProvider = ProviderMessageId(ProviderKind.SMS, 8_101L)
        val wrongProvider = ProviderMessageId(ProviderKind.SMS, 8_102L)

        assertSame(
            ReplyOperationPhaseTransitionResult.Transitioned,
            firstRegistry.markClaimed(operationId),
        )
        assertTrue(
            firstRegistry.markFailurePending(operationId) is ReplyOperationFailureResult.Pending,
        )
        assertEquals(
            ReplyOperationPendingProviderUpdateResult.Available(null),
            firstRegistry.pendingProviderUpdate(operationId),
        )
        assertSame(
            ReplyOperationSubmittedResult.FailurePending,
            firstRegistry.recordSubmitted(operationId, 1, firstProvider),
        )
        val recreated = registry(store = store, identifierGenerator = { 120L })
        assertSame(
            ReplyOperationSentResult.ProviderMismatch,
            recreated.recordSent(operationId, 0, 1, wrongProvider),
        )
        assertEquals(
            firstProvider,
            (recreated.pendingProviderUpdate(operationId) as
                ReplyOperationPendingProviderUpdateResult.Available).update?.providerMessageId,
        )
    }

    @Test
    fun deliveryCallbacksBindMultipartAndCountAsPositiveSentEvidence() {
        val registry = registry(
            store = ControllableStore(maximumEntries = 4),
            identifierGenerator = { 120L },
        )
        val operationId = registry.reserveOperation(CONVERSATION)
        val providerId = ProviderMessageId(ProviderKind.SMS, 8_201L)
        assertSame(ReplyOperationPhaseTransitionResult.Transitioned, registry.markClaimed(operationId))

        assertSame(
            ReplyOperationProviderStatusResult.Tracked,
            registry.recordDeliverySuccess(operationId, 0, 2, providerId),
        )
        assertEquals(
            ReplyOperationSentResult.Pending(CONVERSATION, duplicate = true),
            registry.recordSent(operationId, 0, 2, providerId),
        )
        assertSame(
            ReplyOperationProviderStatusResult.UnitCountMismatch,
            registry.recordDeliveryFailure(operationId, 0, 1, providerId),
        )
        assertTrue(
            registry.markFailurePending(
                operationId,
                providerId,
                unitIndex = 1,
                unitCount = 2,
            ) is ReplyOperationFailureResult.Pending,
        )
        assertEquals(
            SmsProviderStatus.FAILED,
            (registry.pendingProviderUpdate(operationId) as
                ReplyOperationPendingProviderUpdateResult.Available).update?.status,
        )
    }

    @Test
    fun allDeliveryCallbacksResolveMultipartWhenSentCallbacksAreLost() {
        val registry = registry(
            store = ControllableStore(maximumEntries = 4),
            identifierGenerator = { 124L },
        )
        val operationId = registry.reserveOperation(CONVERSATION)
        val providerId = ProviderMessageId(ProviderKind.SMS, 8_241L)
        assertSame(ReplyOperationPhaseTransitionResult.Transitioned, registry.markClaimed(operationId))

        assertSame(
            ReplyOperationProviderStatusResult.Tracked,
            registry.recordDeliverySuccess(operationId, 1, 2, providerId),
        )
        assertSame(
            ReplyOperationProviderStatusResult.SuccessPending,
            registry.recordDeliverySuccess(operationId, 0, 2, providerId),
        )
        assertEquals(
            ReplyOperationPendingSuccessesResult.Available(
                listOf(ReplyOperationPending(operationId, CONVERSATION, SOURCE_MESSAGE)),
            ),
            registry.pendingSuccesses(),
        )
        assertEquals(
            SmsProviderStatus.COMPLETE,
            (registry.pendingProviderUpdate(operationId) as
                ReplyOperationPendingProviderUpdateResult.Available).update?.status,
        )
    }

    @Test
    fun durableTransportPhasesEnforceOrderAndRemainIdempotent() {
        val registry = registry(
            store = ControllableStore(maximumEntries = 4),
            identifierGenerator = { 125L },
        )
        val operationId = registry.reserveOperation(CONVERSATION)
        val providerId = ProviderMessageId(ProviderKind.SMS, 8_251L)

        assertSame(
            ReplyOperationSubmittedResult.PhaseMismatch,
            registry.recordSubmitted(operationId, 2, providerId),
        )
        assertSame(
            ReplyOperationSentResult.PhaseMismatch,
            registry.recordSent(operationId, 0, 2, providerId),
        )
        assertSame(ReplyOperationPhaseTransitionResult.Transitioned, registry.markClaimed(operationId))
        assertSame(ReplyOperationPhaseTransitionResult.AlreadyInPhase, registry.markClaimed(operationId))
        assertSame(
            ReplyOperationPhaseTransitionResult.PhaseMismatch,
            registry.recordSubmitting(operationId, providerId, 2),
        )
        assertSame(
            ReplyOperationPhaseTransitionResult.Transitioned,
            registry.recordPrepared(operationId, providerId, 2),
        )
        assertSame(
            ReplyOperationPhaseTransitionResult.AlreadyInPhase,
            registry.recordPrepared(operationId, providerId, 2),
        )
        assertSame(
            ReplyOperationPhaseTransitionResult.Transitioned,
            registry.recordSubmitting(operationId, providerId, 2),
        )
        assertSame(
            ReplyOperationPhaseTransitionResult.AlreadyAdvanced,
            registry.recordPrepared(operationId, providerId, 2),
        )
        assertSame(
            ReplyOperationSubmittedResult.Tracked,
            registry.recordSubmitted(operationId, 2, providerId),
        )
    }

    @Test
    fun explicitInterruptionRecoveryIsIdempotentAtEveryDurableBoundary() {
        val registry = registry(
            store = ControllableStore(maximumEntries = 8),
            identifierGenerator = SequenceGenerator(201L, 202L, 203L, 204L, 205L),
        )

        val reserved = registry.reserveOperation(CONVERSATION)
        assertInterruptedPending(
            registry = registry,
            operationId = reserved,
            failureKind = ReplyOperationFailureKind.KNOWN_UNSENT,
        )

        val claimed = registry.reserveOperation(OTHER_CONVERSATION)
        assertSame(ReplyOperationPhaseTransitionResult.Transitioned, registry.markClaimed(claimed))
        assertInterruptedPending(
            registry = registry,
            operationId = claimed,
            conversationId = OTHER_CONVERSATION,
            failureKind = ReplyOperationFailureKind.KNOWN_UNSENT,
        )

        val prepared = registry.reserveOperation(CONVERSATION)
        val preparedProvider = ProviderMessageId(ProviderKind.SMS, 9_203L)
        assertSame(ReplyOperationPhaseTransitionResult.Transitioned, registry.markClaimed(prepared))
        assertSame(
            ReplyOperationPhaseTransitionResult.Transitioned,
            registry.recordPrepared(prepared, preparedProvider, 1),
        )
        assertInterruptedPending(
            registry = registry,
            operationId = prepared,
            failureKind = ReplyOperationFailureKind.KNOWN_UNSENT,
        )
        assertEquals(
            SmsProviderStatus.FAILED,
            (registry.pendingProviderUpdate(prepared) as
                ReplyOperationPendingProviderUpdateResult.Available).update?.status,
        )

        val submitting = registry.reserveOperation(CONVERSATION)
        val submittingProvider = ProviderMessageId(ProviderKind.SMS, 9_204L)
        prepareSubmitting(registry, submitting, submittingProvider, 2)
        assertInterruptedPending(
            registry = registry,
            operationId = submitting,
            failureKind = ReplyOperationFailureKind.SUBMISSION_UNKNOWN,
        )
        assertEquals(
            ReplyOperationPendingProviderUpdateResult.Available(null),
            registry.pendingProviderUpdate(submitting),
        )

        val submitted = registry.reserveOperation(CONVERSATION)
        val submittedProvider = ProviderMessageId(ProviderKind.SMS, 9_205L)
        prepareSubmitting(registry, submitted, submittedProvider, 1)
        assertSame(
            ReplyOperationSubmittedResult.Tracked,
            registry.recordSubmitted(submitted, 1, submittedProvider),
        )
        assertInterruptedPending(
            registry = registry,
            operationId = submitted,
            failureKind = ReplyOperationFailureKind.SUBMISSION_UNKNOWN,
        )
        assertSame(
            ReplyOperationAcknowledgementResult.Acknowledged,
            registry.acknowledgeFailureNotification(submitted),
        )
        assertEquals(
            ReplyOperationInterruptedRecoveryResult.Notified(
                ReplyOperationPendingFailure(
                    submitted,
                    CONVERSATION,
                    SOURCE_MESSAGE,
                    ReplyOperationFailureKind.SUBMISSION_UNKNOWN,
                ),
            ),
            registry.recoverInterruptedOperation(submitted),
        )
    }

    @Test
    fun inheritedRecoveryRunsOnceAndLateUnknownCallbacksConverge() {
        val store = ControllableStore(maximumEntries = 8)
        val first = registry(
            store = store,
            identifierGenerator = SequenceGenerator(211L, 212L, 213L, 214L),
        )
        val claimed = first.reserveOperation(CONVERSATION).also {
            assertSame(ReplyOperationPhaseTransitionResult.Transitioned, first.markClaimed(it))
        }
        val prepared = first.reserveOperation(CONVERSATION)
        val preparedProvider = ProviderMessageId(ProviderKind.SMS, 9_212L)
        assertSame(ReplyOperationPhaseTransitionResult.Transitioned, first.markClaimed(prepared))
        assertSame(
            ReplyOperationPhaseTransitionResult.Transitioned,
            first.recordPrepared(prepared, preparedProvider, 1),
        )
        val submitting = first.reserveOperation(CONVERSATION)
        val submittingProvider = ProviderMessageId(ProviderKind.SMS, 9_213L)
        prepareSubmitting(first, submitting, submittingProvider, 2)
        val submitted = first.reserveOperation(CONVERSATION)
        val submittedProvider = ProviderMessageId(ProviderKind.SMS, 9_214L)
        prepareSubmitting(first, submitted, submittedProvider, 1)
        assertSame(
            ReplyOperationSubmittedResult.Tracked,
            first.recordSubmitted(submitted, 1, submittedProvider),
        )

        val recreated = registry(store = store, identifierGenerator = { 215L })
        val expectedRecovery = ReplyOperationRecoveryResult.Recovered(
            knownUnsentCount = 1,
            preparedFailureCount = 1,
            submissionUnknownCount = 2,
            corruptCount = 0,
        )
        assertEquals(expectedRecovery, recreated.recoverInheritedOperations())
        assertEquals(expectedRecovery, recreated.recoverInheritedOperations())
        assertEquals(
            setOf(
                claimed to ReplyOperationFailureKind.KNOWN_UNSENT,
                prepared to ReplyOperationFailureKind.KNOWN_UNSENT,
                submitting to ReplyOperationFailureKind.SUBMISSION_UNKNOWN,
                submitted to ReplyOperationFailureKind.SUBMISSION_UNKNOWN,
            ),
            (recreated.pendingFailures() as ReplyOperationPendingFailuresResult.Available)
                .operations
                .map { it.operationId to it.failureKind }
                .toSet(),
        )
        assertEquals(
            SmsProviderStatus.FAILED,
            (recreated.pendingProviderUpdate(prepared) as
                ReplyOperationPendingProviderUpdateResult.Available).update?.status,
        )

        assertSame(
            ReplyOperationAcknowledgementResult.Acknowledged,
            recreated.acknowledgeFailureNotification(submitting),
        )
        assertEquals(
            ReplyOperationSentResult.Pending(CONVERSATION, duplicate = false),
            recreated.recordSent(submitting, 0, 2, submittingProvider),
        )
        assertEquals(
            ReplyOperationSentResult.SuccessPending(CONVERSATION, SOURCE_MESSAGE),
            recreated.recordSent(submitting, 1, 2, submittingProvider),
        )
        assertEquals(
            ReplyOperationFailureResult.Pending(CONVERSATION, duplicate = false),
            recreated.markFailurePending(submitted, submittedProvider),
        )
        assertEquals(
            SmsProviderStatus.FAILED,
            (recreated.pendingProviderUpdate(submitted) as
                ReplyOperationPendingProviderUpdateResult.Available).update?.status,
        )
    }

    @Test
    fun inheritedRecoveryRetriesTransientPersistenceFailureThenCachesSuccess() {
        val backing = InMemoryReplyOperationStore(maximumEntries = 4)
        val first = registry(store = backing, identifierGenerator = { 216L })
        val operationId = first.reserveOperation(CONVERSATION)
        val providerId = ProviderMessageId(ProviderKind.SMS, 9_216L)
        prepareSubmitting(first, operationId, providerId, unitCount = 1)

        var recoveryAttempts = 0
        val flaky = object : ReplyOperationStore by backing {
            override fun recoverInheritedOperations(
                nowMillis: Long,
                terminalExpiresAtMillis: Long,
            ): ReplyOperationRecoveryResult {
                recoveryAttempts += 1
                return if (recoveryAttempts == 1) {
                    ReplyOperationRecoveryResult.PersistenceFailure
                } else {
                    backing.recoverInheritedOperations(nowMillis, terminalExpiresAtMillis)
                }
            }
        }
        val recreated = registry(store = flaky, identifierGenerator = { 217L })

        val recovered = ReplyOperationRecoveryResult.Recovered(
            knownUnsentCount = 0,
            preparedFailureCount = 0,
            submissionUnknownCount = 1,
            corruptCount = 0,
        )
        assertEquals(recovered, recreated.recoverInheritedOperations())
        assertEquals(recovered, recreated.recoverInheritedOperations())
        assertEquals(2, recoveryAttempts)
    }

    @Test
    fun reserveWaitsForInheritedRecoveryAndLiveOperationIsNeverRescanned() {
        val backing = InMemoryReplyOperationStore(maximumEntries = 4)
        var recoveryAttempts = 0
        val flaky = object : ReplyOperationStore by backing {
            override fun recoverInheritedOperations(
                nowMillis: Long,
                terminalExpiresAtMillis: Long,
            ): ReplyOperationRecoveryResult {
                recoveryAttempts += 1
                return if (recoveryAttempts <= 2) {
                    ReplyOperationRecoveryResult.PersistenceFailure
                } else {
                    backing.recoverInheritedOperations(nowMillis, terminalExpiresAtMillis)
                }
            }
        }
        val registry = registry(store = flaky, identifierGenerator = { 218L })

        assertSame(
            ReplyOperationReservationResult.PersistenceFailure,
            registry.reserve(CONVERSATION, SOURCE_MESSAGE),
        )
        val reserved = registry.reserve(CONVERSATION, SOURCE_MESSAGE)
        assertEquals(
            ReplyOperationReservationResult.Reserved(pendingOperation(218L)),
            reserved,
        )
        assertEquals(3, recoveryAttempts)

        val operationId = (reserved as ReplyOperationReservationResult.Reserved).operationId
        assertSame(ReplyOperationPhaseTransitionResult.Transitioned, registry.markClaimed(operationId))
        assertEquals(
            ReplyOperationRecoveryResult.Recovered(0, 0, 0, 0),
            registry.recoverInheritedOperations(),
        )
        assertEquals(3, recoveryAttempts)
        assertEquals(
            ReplyOperationPendingFailuresResult.Available(emptyList()),
            registry.pendingFailures(),
        )
    }

    @Test
    fun invalidProviderAndUnitBoundsNeverReachStore() {
        val store = ControllableStore(maximumEntries = 4)
        val registry = registry(store = store, identifierGenerator = { 121L })
        val providerId = MessageId(ProviderKind.SMS, 121L)
        val pendingId = pendingOperation(121L)

        assertSame(
            ReplyOperationReservationResult.InvalidSource,
            registry.reserve(CONVERSATION, MessageId(ProviderKind.DRAFT, 121L)),
        )

        assertSame(ReplyOperationSubmittedResult.Invalid, registry.recordSubmitted(providerId, 1))
        assertSame(ReplyOperationSubmittedResult.Invalid, registry.recordSubmitted(pendingId, 0))
        assertSame(ReplyOperationSubmittedResult.Invalid, registry.recordSubmitted(pendingId, 256))
        assertSame(
            ReplyOperationSubmittedResult.Invalid,
            registry.recordSubmitted(
                pendingId,
                1,
                ProviderMessageId(ProviderKind.MMS, 122L),
            ),
        )
        assertSame(ReplyOperationSentResult.Invalid, registry.recordSent(pendingId, -1, 1))
        assertSame(ReplyOperationSentResult.Invalid, registry.recordSent(pendingId, 1, 1))
        assertSame(ReplyOperationSentResult.Invalid, registry.recordSent(pendingId, 0, 256))
        assertSame(ReplyOperationFailureResult.Invalid, registry.markFailurePending(providerId))
        assertSame(
            ReplyOperationInterruptedRecoveryResult.Invalid,
            registry.recoverInterruptedOperation(providerId),
        )
        assertSame(ReplyOperationSuccessResult.Invalid, registry.markSuccessPending(providerId))
        assertSame(
            ReplyOperationAcknowledgementResult.Invalid,
            registry.acknowledgeFailureNotification(providerId),
        )
        assertSame(ReplyOperationRemovalResult.Invalid, registry.discard(providerId))
        assertEquals(0, store.nonReservationCalls)
    }

    @Test
    fun cleanupUsesRegistryClockAndClearDropsAllReservations() {
        var nowMillis = 1_000L
        val store = ControllableStore(maximumEntries = 4)
        val registry = registry(
            store = store,
            clockMillis = { nowMillis },
            retentionMillis = 10L,
            identifierGenerator = SequenceGenerator(131L, 132L),
        )
        registry.reserve(CONVERSATION, SOURCE_MESSAGE)
        registry.reserve(OTHER_CONVERSATION, OTHER_SOURCE_MESSAGE)

        nowMillis = 1_010L
        assertEquals(ReplyOperationCleanupResult.Success(2), registry.cleanupExpired())
        assertTrue(registry.clear())
    }

    @Test
    fun defaultGeneratorReturnsPositivePendingOperationIdentifier() {
        val result = ReplyOperationRegistry(
            store = InMemoryReplyOperationStore(maximumEntries = 4),
            clockMillis = { 1_000L },
        ).reserve(CONVERSATION, SOURCE_MESSAGE)

        assertTrue(result is ReplyOperationReservationResult.Reserved)
        val operationId = (result as ReplyOperationReservationResult.Reserved).operationId
        assertEquals(ProviderKind.PENDING_OPERATION, operationId.kind)
        assertTrue(operationId.isInlineReplyOperationId())
    }

    private fun registry(
        store: ReplyOperationStore,
        clockMillis: () -> Long = { 1_000L },
        retentionMillis: Long = 1_000L,
        maximumIdentifierAttempts: Int = 8,
        identifierGenerator: ReplyOperationIdentifierGenerator,
    ) = ReplyOperationRegistry(
        store = store,
        retentionMillis = retentionMillis,
        maximumIdentifierAttempts = maximumIdentifierAttempts,
        clockMillis = clockMillis,
        identifierGenerator = identifierGenerator,
    )

    private fun ReplyOperationRegistry.reserveOperation(conversationId: ConversationId) =
        (reserve(conversationId, SOURCE_MESSAGE) as ReplyOperationReservationResult.Reserved)
            .operationId

    private fun prepareSubmitting(
        registry: ReplyOperationRegistry,
        operationId: MessageId,
        providerMessageId: ProviderMessageId = DEFAULT_PROVIDER_MESSAGE,
        unitCount: Int,
    ) {
        assertSame(ReplyOperationPhaseTransitionResult.Transitioned, registry.markClaimed(operationId))
        assertSame(
            ReplyOperationPhaseTransitionResult.Transitioned,
            registry.recordPrepared(operationId, providerMessageId, unitCount),
        )
        assertSame(
            ReplyOperationPhaseTransitionResult.Transitioned,
            registry.recordSubmitting(operationId, providerMessageId, unitCount),
        )
    }

    private fun assertInterruptedPending(
        registry: ReplyOperationRegistry,
        operationId: MessageId,
        conversationId: ConversationId = CONVERSATION,
        failureKind: ReplyOperationFailureKind,
    ) {
        val pending = ReplyOperationPendingFailure(
            operationId = operationId,
            conversationId = conversationId,
            sourceMessageId = SOURCE_MESSAGE,
            failureKind = failureKind,
        )
        assertEquals(
            ReplyOperationInterruptedRecoveryResult.Pending(pending, transitioned = true),
            registry.recoverInterruptedOperation(operationId),
        )
        assertEquals(
            ReplyOperationInterruptedRecoveryResult.Pending(pending, transitioned = false),
            registry.recoverInterruptedOperation(operationId),
        )
    }

    private class SequenceGenerator(vararg values: Long) : ReplyOperationIdentifierGenerator {
        private val values = ArrayDeque(values.toList())

        override fun nextPositiveLong(): Long = values.removeFirst()
    }

    private class ControllableStore(maximumEntries: Int) : ReplyOperationStore {
        private val delegate = InMemoryReplyOperationStore(maximumEntries)
        val reservationAttempts = mutableListOf<ReplyOperationRecord>()
        val reservationOverrides = ArrayDeque<ReplyOperationStoreReservationResult>()
        var nonReservationCalls = 0

        override fun reserve(record: ReplyOperationRecord, nowMillis: Long): ReplyOperationStoreReservationResult {
            reservationAttempts += record
            reservationOverrides.pollFirst()?.let { return it }
            return delegate.reserve(record, nowMillis)
        }

        override fun recordSubmitted(
            operationId: Long,
            unitCount: Int,
            providerMessageId: ProviderMessageId?,
            nowMillis: Long,
        ) = counted {
            delegate.recordSubmitted(operationId, unitCount, providerMessageId, nowMillis)
        }

        override fun markClaimed(operationId: Long, nowMillis: Long) = counted {
            delegate.markClaimed(operationId, nowMillis)
        }

        override fun recordTransportPhase(
            operationId: Long,
            providerMessageId: ProviderMessageId,
            unitCount: Int,
            phase: ReplyOperationTransportPhase,
            nowMillis: Long,
        ) = counted {
            delegate.recordTransportPhase(
                operationId,
                providerMessageId,
                unitCount,
                phase,
                nowMillis,
            )
        }

        override fun recordSent(
            operationId: Long,
            unitIndex: Int,
            unitCount: Int,
            providerMessageId: ProviderMessageId?,
            nowMillis: Long,
            terminalExpiresAtMillis: Long,
        ) = counted {
            delegate.recordSent(
                operationId,
                unitIndex,
                unitCount,
                providerMessageId,
                nowMillis,
                terminalExpiresAtMillis,
            )
        }

        override fun markFailurePending(
            operationId: Long,
            providerMessageId: ProviderMessageId?,
            unitIndex: Int,
            unitCount: Int,
            nowMillis: Long,
            terminalExpiresAtMillis: Long,
        ) = counted {
            delegate.markFailurePending(
                operationId,
                providerMessageId,
                unitIndex,
                unitCount,
                nowMillis,
                terminalExpiresAtMillis,
            )
        }

        override fun acknowledgeFailureNotification(operationId: Long, nowMillis: Long) =
            counted { delegate.acknowledgeFailureNotification(operationId, nowMillis) }

        override fun markSuccessPending(
            operationId: Long,
            nowMillis: Long,
            terminalExpiresAtMillis: Long,
        ) = counted {
            delegate.markSuccessPending(operationId, nowMillis, terminalExpiresAtMillis)
        }

        override fun acknowledgeSuccessCancellation(operationId: Long, nowMillis: Long) =
            counted { delegate.acknowledgeSuccessCancellation(operationId, nowMillis) }

        override fun pendingFailures(nowMillis: Long) =
            counted { delegate.pendingFailures(nowMillis) }

        override fun pendingSuccesses(nowMillis: Long) =
            counted { delegate.pendingSuccesses(nowMillis) }

        override fun recoverInheritedOperations(
            nowMillis: Long,
            terminalExpiresAtMillis: Long,
        ) = delegate.recoverInheritedOperations(nowMillis, terminalExpiresAtMillis)

        override fun recoverInterruptedOperation(
            operationId: Long,
            nowMillis: Long,
            terminalExpiresAtMillis: Long,
        ) = counted {
            delegate.recoverInterruptedOperation(operationId, nowMillis, terminalExpiresAtMillis)
        }

        override fun recordDeliveryFailure(
            operationId: Long,
            unitIndex: Int,
            unitCount: Int,
            providerMessageId: ProviderMessageId?,
            nowMillis: Long,
            terminalExpiresAtMillis: Long,
        ) = counted {
            delegate.recordDeliveryFailure(
                operationId,
                unitIndex,
                unitCount,
                providerMessageId,
                nowMillis,
                terminalExpiresAtMillis,
            )
        }

        override fun recordDeliverySuccess(
            operationId: Long,
            unitIndex: Int,
            unitCount: Int,
            providerMessageId: ProviderMessageId?,
            nowMillis: Long,
            terminalExpiresAtMillis: Long,
        ) = counted {
            delegate.recordDeliverySuccess(
                operationId,
                unitIndex,
                unitCount,
                providerMessageId,
                nowMillis,
                terminalExpiresAtMillis,
            )
        }

        override fun pendingProviderUpdates(nowMillis: Long) =
            counted { delegate.pendingProviderUpdates(nowMillis) }

        override fun pendingProviderUpdate(operationId: Long, nowMillis: Long) =
            counted { delegate.pendingProviderUpdate(operationId, nowMillis) }

        override fun acknowledgeProviderUpdate(
            operationId: Long,
            providerMessageId: ProviderMessageId,
            status: SmsProviderStatus,
            nowMillis: Long,
        ) = counted {
            delegate.acknowledgeProviderUpdate(
                operationId,
                providerMessageId,
                status,
                nowMillis,
            )
        }

        override fun remove(operationId: Long, nowMillis: Long) =
            counted { delegate.remove(operationId, nowMillis) }

        override fun cleanupExpired(nowMillis: Long) =
            counted { delegate.cleanupExpired(nowMillis) }

        override fun clear() = counted { delegate.clear() }

        private inline fun <T> counted(block: () -> T): T {
            nonReservationCalls += 1
            return block()
        }
    }

    private companion object {
        val CONVERSATION = ConversationId(501L)
        val OTHER_CONVERSATION = ConversationId(502L)
        val SOURCE_MESSAGE = MessageId(ProviderKind.SMS, 5_001L)
        val OTHER_SOURCE_MESSAGE = MessageId(ProviderKind.MMS, 5_002L)
        val DEFAULT_PROVIDER_MESSAGE = ProviderMessageId(ProviderKind.SMS, 8_999L)

        fun inlineOperationValue(value: Long) = value or INLINE_REPLY_OPERATION_ID_BOUNDARY

        fun pendingOperation(value: Long) =
            MessageId(ProviderKind.PENDING_OPERATION, inlineOperationValue(value))
    }
}
