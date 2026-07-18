// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.telephony.OutgoingSmsRecord
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.SmsProviderStatus
import org.aurorasms.core.testing.FakeSmsProviderDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineReplyProviderUpdateCoordinatorTest {
    @Test
    fun reconcileAppliesExactProviderStatusAndAcknowledgesOnlyThatUpdate() = runTest {
        val provider = FakeSmsProviderDataSource()
        val providerId = provider.insertOutgoing("first")
        val store = InMemoryReplyOperationStore(maximumEntries = 4)
        val registry = registry(store, identifier = 801L)
        val operationId = registry.reserveOperation(SOURCE_MESSAGE)
        prepareSubmitting(registry, operationId, providerId)
        assertSame(
            ReplyOperationSubmittedResult.Tracked,
            registry.recordSubmitted(operationId, unitCount = 1, providerMessageId = providerId),
        )
        assertTrue(registry.recordSent(operationId, 0, 1, providerId) is ReplyOperationSentResult.SuccessPending)

        val changed = InlineReplyProviderUpdateCoordinator(registry, provider).reconcile(operationId)

        assertTrue(changed)
        assertEquals(SmsProviderStatus.COMPLETE, provider.updatedStatuses[providerId])
        assertEquals(
            ReplyOperationPendingProviderUpdateResult.Available(null),
            registry.pendingProviderUpdate(operationId),
        )
        assertFalse(InlineReplyProviderUpdateCoordinator(registry, provider).reconcile(operationId))
    }

    @Test
    fun providerFailureRetainsOutboxForRecreatedCoordinator() = runTest {
        val provider = FakeSmsProviderDataSource()
        val providerId = provider.insertOutgoing("retry")
        val store = InMemoryReplyOperationStore(maximumEntries = 4)
        val firstRegistry = registry(store, identifier = 811L)
        val operationId = firstRegistry.reserveOperation(SOURCE_MESSAGE)
        assertSame(
            ReplyOperationPhaseTransitionResult.Transitioned,
            firstRegistry.markClaimed(operationId),
        )
        assertTrue(firstRegistry.markFailurePending(operationId, providerId) is ReplyOperationFailureResult.Pending)
        val expectedUpdate = ReplyOperationProviderUpdate(
            operationId = operationId,
            providerMessageId = providerId,
            status = SmsProviderStatus.FAILED,
        )
        provider.failure = ProviderAccessResult.PermissionDenied

        assertFalse(
            InlineReplyProviderUpdateCoordinator(firstRegistry, provider).reconcile(operationId),
        )
        assertEquals(
            ReplyOperationPendingProviderUpdateResult.Available(expectedUpdate),
            firstRegistry.pendingProviderUpdate(operationId),
        )
        assertTrue(provider.updatedStatuses.isEmpty())

        provider.failure = null
        val recreatedRegistry = registry(store, identifier = 812L)
        assertTrue(
            InlineReplyProviderUpdateCoordinator(recreatedRegistry, provider).reconcile(operationId),
        )
        assertEquals(SmsProviderStatus.FAILED, provider.updatedStatuses[providerId])
        assertEquals(
            ReplyOperationPendingProviderUpdateResult.Available(null),
            recreatedRegistry.pendingProviderUpdate(operationId),
        )
    }

    @Test
    fun deliveryFailureThenFinalSentPreservesDeliveryFailedStatus() = runTest {
        val provider = FakeSmsProviderDataSource()
        val providerId = provider.insertOutgoing("delivery")
        val store = InMemoryReplyOperationStore(maximumEntries = 4)
        val registry = registry(store, identifier = 821L)
        val operationId = registry.reserveOperation(SOURCE_MESSAGE)
        prepareSubmitting(registry, operationId, providerId)
        assertSame(
            ReplyOperationSubmittedResult.Tracked,
            registry.recordSubmitted(operationId, unitCount = 1, providerMessageId = providerId),
        )

        assertSame(
            ReplyOperationProviderStatusResult.SuccessPending,
            registry.recordDeliveryFailure(operationId, 0, 1, providerId),
        )
        assertTrue(registry.recordSent(operationId, 0, 1, providerId) is ReplyOperationSentResult.SuccessPending)
        assertEquals(
            SmsProviderStatus.DELIVERY_FAILED,
            (registry.pendingProviderUpdate(operationId) as
                ReplyOperationPendingProviderUpdateResult.Available).update?.status,
        )

        assertTrue(InlineReplyProviderUpdateCoordinator(registry, provider).reconcile(operationId))
        assertEquals(SmsProviderStatus.DELIVERY_FAILED, provider.updatedStatuses[providerId])
        assertEquals(
            ReplyOperationPendingProviderUpdateResult.Available(null),
            registry.pendingProviderUpdate(operationId),
        )
    }

    @Test
    fun reconcilePendingAppliesAndAcknowledgesEveryOperation() = runTest {
        val provider = FakeSmsProviderDataSource()
        val completeProviderId = provider.insertOutgoing("complete")
        val failedProviderId = provider.insertOutgoing("failed")
        val store = InMemoryReplyOperationStore(maximumEntries = 4)

        val completeRegistry = registry(store, identifier = 831L)
        val completeOperation = completeRegistry.reserveOperation(SOURCE_MESSAGE)
        prepareSubmitting(completeRegistry, completeOperation, completeProviderId)
        assertSame(
            ReplyOperationSubmittedResult.Tracked,
            completeRegistry.recordSubmitted(completeOperation, 1, completeProviderId),
        )
        assertTrue(
            completeRegistry.recordSent(completeOperation, 0, 1, completeProviderId) is
                ReplyOperationSentResult.SuccessPending,
        )

        val failedRegistry = registry(store, identifier = 832L)
        val failedOperation = failedRegistry.reserveOperation(OTHER_SOURCE_MESSAGE)
        assertSame(
            ReplyOperationPhaseTransitionResult.Transitioned,
            failedRegistry.markClaimed(failedOperation),
        )
        assertTrue(
            failedRegistry.markFailurePending(failedOperation, failedProviderId) is
                ReplyOperationFailureResult.Pending,
        )

        val recreatedRegistry = registry(store, identifier = 833L)
        val coordinator = InlineReplyProviderUpdateCoordinator(recreatedRegistry, provider)
        assertTrue(coordinator.reconcilePending())

        assertEquals(SmsProviderStatus.COMPLETE, provider.updatedStatuses[completeProviderId])
        assertEquals(SmsProviderStatus.FAILED, provider.updatedStatuses[failedProviderId])
        assertEquals(
            ReplyOperationPendingProviderUpdatesResult.Available(emptyList()),
            recreatedRegistry.pendingProviderUpdates(),
        )
        assertFalse(coordinator.reconcilePending())
    }

    private fun registry(
        store: InMemoryReplyOperationStore,
        identifier: Long,
    ) = ReplyOperationRegistry(
        store = store,
        clockMillis = { NOW_MILLIS },
        identifierGenerator = ReplyOperationIdentifierGenerator { identifier },
    )

    private fun ReplyOperationRegistry.reserveOperation(sourceMessageId: MessageId): MessageId =
        (reserve(CONVERSATION, sourceMessageId) as ReplyOperationReservationResult.Reserved)
            .operationId

    private fun prepareSubmitting(
        registry: ReplyOperationRegistry,
        operationId: MessageId,
        providerMessageId: ProviderMessageId,
    ) {
        assertSame(
            ReplyOperationPhaseTransitionResult.Transitioned,
            registry.markClaimed(operationId),
        )
        assertSame(
            ReplyOperationPhaseTransitionResult.Transitioned,
            registry.recordPrepared(operationId, providerMessageId, unitCount = 1),
        )
        assertSame(
            ReplyOperationPhaseTransitionResult.Transitioned,
            registry.recordSubmitting(operationId, providerMessageId, unitCount = 1),
        )
    }

    private suspend fun FakeSmsProviderDataSource.insertOutgoing(body: String): ProviderMessageId {
        val providerId = (insertOutgoing(
            OutgoingSmsRecord(
                recipient = RECIPIENT,
                body = body,
                timestampMillis = NOW_MILLIS,
                subscriptionId = SUBSCRIPTION_ID,
            ),
        ) as ProviderAccessResult.Success).value.providerId
        assertTrue(armOutgoing(providerId) is ProviderAccessResult.Success)
        return providerId
    }

    private companion object {
        const val NOW_MILLIS = 10_000L
        val CONVERSATION = ConversationId(901L)
        val SOURCE_MESSAGE = MessageId(ProviderKind.SMS, 9_001L)
        val OTHER_SOURCE_MESSAGE = MessageId(ProviderKind.MMS, 9_002L)
        val RECIPIENT = ParticipantAddress("+15550009001")
        val SUBSCRIPTION_ID = AuroraSubscriptionId(1)
    }
}
