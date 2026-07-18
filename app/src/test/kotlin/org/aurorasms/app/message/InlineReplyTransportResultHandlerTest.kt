// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import java.util.concurrent.atomic.AtomicLong
import org.aurorasms.core.model.INLINE_REPLY_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.notifications.NotificationPostResult
import org.aurorasms.core.telephony.SmsProviderStatus
import org.aurorasms.core.testing.FakeMessageNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class InlineReplyTransportResultHandlerTest {
    @Test
    fun multipartSuccessSurvivesRecreationCancelsOnceAndAbsorbsDuplicate() {
        val store = InMemoryReplyOperationStore(maximumEntries = 4)
        val firstRegistry = registry(store, 401L)
        val operationId = reserveClaimed(firstRegistry, CONVERSATION, 301L)
        val preparedProviderId = providerMessage(30_001L)
        assertSame(
            ReplyOperationPhaseTransitionResult.Transitioned,
            firstRegistry.recordPrepared(operationId, preparedProviderId, unitCount = 2),
        )
        assertSame(
            ReplyOperationPhaseTransitionResult.Transitioned,
            firstRegistry.recordSubmitting(operationId, preparedProviderId, unitCount = 2),
        )
        assertSame(ReplyOperationSubmittedResult.Tracked, firstRegistry.recordSubmitted(operationId, 2))
        val notifier = FakeMessageNotifier()

        assertSame(
            InlineReplyTransportDisposition.TrackedPending,
            InlineReplyTransportResultHandler(firstRegistry, notifier).handle(
                sent(operationId, unitIndex = 1, unitCount = 2),
            ),
        )

        val recreated = registry(store, 402L)
        val handler = InlineReplyTransportResultHandler(recreated, notifier)
        assertSame(
            InlineReplyTransportDisposition.TrackedComplete,
            handler.handle(sent(operationId, unitIndex = 0, unitCount = 2)),
        )
        assertSame(
            InlineReplyTransportDisposition.TrackedTerminal,
            handler.handle(sent(operationId, unitIndex = 0, unitCount = 2)),
        )
        assertEquals(listOf(CONVERSATION), notifier.cancelledConversations)
        assertEquals(
            listOf(
                FakeMessageNotifier.IncomingCancellationCall(
                    CONVERSATION,
                    sourceMessage(301L),
                ),
            ),
            notifier.incomingCancellations,
        )
        assertEquals(emptyList<ConversationId>(), notifier.replyFailures)
    }

    @Test
    fun sentCallbackFailurePostsOnceAndNotifiedTombstoneOwnsDuplicate() {
        val store = InMemoryReplyOperationStore(maximumEntries = 4)
        val operationRegistry = registry(store, 502L)
        val operationId = reserveClaimed(operationRegistry, CONVERSATION, 501L)
        val notifier = FakeMessageNotifier()
        val handler = InlineReplyTransportResultHandler(operationRegistry, notifier)
        val failure = failed(operationId, TransportResult.FailureStage.SENT_CALLBACK)

        assertSame(InlineReplyTransportDisposition.TrackedFailure, handler.handle(failure))
        assertSame(InlineReplyTransportDisposition.TrackedFailure, handler.handle(failure))
        assertEquals(listOf(CONVERSATION), notifier.replyFailures)
        assertEquals(
            ReplyOperationSentResult.FailureNotified(CONVERSATION),
            registry(store, 503L).recordSent(operationId, 0, 1),
        )
    }

    @Test
    fun deliveryFailureBeforeSentCompletionPreservesOwnershipAndProviderFailure() {
        val store = InMemoryReplyOperationStore(maximumEntries = 4)
        val operationRegistry = registry(store, 602L)
        val operationId = reserveClaimed(operationRegistry, OTHER_CONVERSATION, 601L)
        val providerMessageId = providerMessage(91L)
        val notifier = FakeMessageNotifier()
        val handler = InlineReplyTransportResultHandler(operationRegistry, notifier)

        assertSame(
            InlineReplyTransportDisposition.TrackedComplete,
            handler.handle(
                failed(
                    operationId = operationId,
                    stage = TransportResult.FailureStage.DELIVERY_CALLBACK,
                    providerMessageId = providerMessageId,
                ),
            ),
        )
        assertEquals(emptyList<ConversationId>(), notifier.replyFailures)
        assertEquals(
            ReplyOperationPendingProviderUpdateResult.Available(
                ReplyOperationProviderUpdate(
                    operationId,
                    providerMessageId,
                    SmsProviderStatus.DELIVERY_FAILED,
                ),
            ),
            operationRegistry.pendingProviderUpdate(operationId),
        )
        assertSame(
            InlineReplyTransportDisposition.TrackedTerminal,
            handler.handle(
                sent(
                    operationId,
                    unitIndex = 0,
                    unitCount = 1,
                    providerMessageId = providerMessageId,
                ),
            ),
        )
        assertEquals(
            listOf(
                FakeMessageNotifier.IncomingCancellationCall(
                    OTHER_CONVERSATION,
                    sourceMessage(601L),
                ),
            ),
            notifier.incomingCancellations,
        )
        assertEquals(
            ReplyOperationPendingProviderUpdateResult.Available(
                ReplyOperationProviderUpdate(
                    operationId,
                    providerMessageId,
                    SmsProviderStatus.DELIVERY_FAILED,
                ),
            ),
            operationRegistry.pendingProviderUpdate(operationId),
        )
    }

    @Test
    fun deliveredPartThenSentFailureRemainsARealSendFailure() {
        val store = InMemoryReplyOperationStore(maximumEntries = 4)
        val operationRegistry = registry(store, 622L)
        val operationId = reserveClaimed(operationRegistry, CONVERSATION, 621L)
        val providerMessageId = providerMessage(92L)
        val notifier = FakeMessageNotifier()
        val handler = InlineReplyTransportResultHandler(operationRegistry, notifier)

        assertSame(
            InlineReplyTransportDisposition.TrackedPending,
            handler.handle(delivered(operationId, 0, 2, providerMessageId)),
        )
        assertSame(
            InlineReplyTransportDisposition.TrackedFailure,
            handler.handle(
                failed(
                    operationId = operationId,
                    stage = TransportResult.FailureStage.SENT_CALLBACK,
                    unitIndex = 1,
                    unitCount = 2,
                    providerMessageId = providerMessageId,
                ),
            ),
        )

        assertEquals(listOf(CONVERSATION), notifier.replyFailures)
        assertEquals(emptyList<ConversationId>(), notifier.cancelledConversations)
        assertEquals(
            ReplyOperationPendingProviderUpdateResult.Available(
                ReplyOperationProviderUpdate(
                    operationId,
                    providerMessageId,
                    SmsProviderStatus.FAILED,
                ),
            ),
            operationRegistry.pendingProviderUpdate(operationId),
        )
    }

    @Test
    fun deliveryFailureAfterSentSuccessCannotBeRegressedByDuplicateSentCallback() {
        val store = InMemoryReplyOperationStore(maximumEntries = 4)
        val operationRegistry = registry(store, 632L)
        val operationId = reserveClaimed(operationRegistry, CONVERSATION, 631L)
        val providerMessageId = providerMessage(93L)
        val handler = InlineReplyTransportResultHandler(
            operationRegistry,
            FakeMessageNotifier(),
        )

        assertSame(
            InlineReplyTransportDisposition.TrackedComplete,
            handler.handle(sent(operationId, 0, 1, providerMessageId)),
        )
        assertSame(
            InlineReplyTransportDisposition.TrackedTerminal,
            handler.handle(
                failed(
                    operationId = operationId,
                    stage = TransportResult.FailureStage.DELIVERY_CALLBACK,
                    providerMessageId = providerMessageId,
                ),
            ),
        )
        assertSame(
            InlineReplyTransportDisposition.TrackedTerminal,
            handler.handle(sent(operationId, 0, 1, providerMessageId)),
        )
        assertEquals(
            ReplyOperationPendingProviderUpdateResult.Available(
                ReplyOperationProviderUpdate(
                    operationId,
                    providerMessageId,
                    SmsProviderStatus.DELIVERY_FAILED,
                ),
            ),
            operationRegistry.pendingProviderUpdate(operationId),
        )
    }

    @Test
    fun disabledFailureNotificationSurvivesRecreationAndRetryBecomesTerminal() {
        val store = InMemoryReplyOperationStore(maximumEntries = 4)
        val operationRegistry = registry(store, 652L)
        val operationId = reserveClaimed(operationRegistry, CONVERSATION, 651L)
        val disabledNotifier = FakeMessageNotifier().apply {
            replyFailureResponder = { NotificationPostResult.NotificationsDisabled }
        }
        val failure = failed(operationId, TransportResult.FailureStage.SENT_CALLBACK)

        assertSame(
            InlineReplyTransportDisposition.TrackedFailure,
            InlineReplyTransportResultHandler(operationRegistry, disabledNotifier)
                .handle(failure),
        )
        assertEquals(
            ReplyOperationPendingFailuresResult.Available(
                listOf(
                    ReplyOperationPendingFailure(
                        operationId = operationId,
                        conversationId = CONVERSATION,
                        sourceMessageId = sourceMessage(651L),
                        failureKind = ReplyOperationFailureKind.KNOWN_UNSENT,
                    ),
                ),
            ),
            registry(store, 653L).pendingFailures(),
        )

        val recreatedNotifier = FakeMessageNotifier()
        val recreatedRegistry = registry(store, 654L)
        InlineReplyTransportResultHandler(recreatedRegistry, recreatedNotifier)
            .reconcilePendingOperations()

        assertEquals(listOf(CONVERSATION), recreatedNotifier.replyFailures)
        assertEquals(
            ReplyOperationPendingFailuresResult.Available(emptyList()),
            recreatedRegistry.pendingFailures(),
        )
        assertEquals(
            ReplyOperationFailureResult.Notified(CONVERSATION),
            recreatedRegistry.markFailurePending(operationId),
        )
    }

    @Test
    fun cancellationExceptionLeavesSuccessPendingForRecreatedRetry() {
        val store = InMemoryReplyOperationStore(maximumEntries = 4)
        val operationRegistry = registry(store, 672L)
        val operationId = reserveClaimed(operationRegistry, CONVERSATION, 671L)
        val throwingNotifier = FakeMessageNotifier().apply {
            cancelIncomingResponder = { _, _ -> throw SecurityException("synthetic") }
        }

        assertSame(
            InlineReplyTransportDisposition.TrackedUnresolved,
            InlineReplyTransportResultHandler(operationRegistry, throwingNotifier)
                .handle(sent(operationId, 0, 1)),
        )
        assertEquals(
            ReplyOperationPendingSuccessesResult.Available(
                listOf(
                    ReplyOperationPending(
                        operationId,
                        CONVERSATION,
                        sourceMessage(671L),
                    ),
                ),
            ),
            registry(store, 673L).pendingSuccesses(),
        )

        val recreatedNotifier = FakeMessageNotifier()
        val recreatedRegistry = registry(store, 674L)
        InlineReplyTransportResultHandler(recreatedRegistry, recreatedNotifier)
            .reconcilePendingOperations()

        assertEquals(listOf(CONVERSATION), recreatedNotifier.cancelledConversations)
        assertEquals(
            ReplyOperationPendingSuccessesResult.Available(emptyList()),
            recreatedRegistry.pendingSuccesses(),
        )
    }

    @Test
    fun laterSuccessInSameConversationDoesNotCancelEarlierFailureAlert() {
        val store = InMemoryReplyOperationStore(maximumEntries = 4)
        val operationRegistry = registry(store, 683L)
        val failedOperation = reserveClaimed(operationRegistry, CONVERSATION, 681L)
        val successfulOperation = reserveClaimed(operationRegistry, CONVERSATION, 682L)
        val notifier = FakeMessageNotifier()
        val handler = InlineReplyTransportResultHandler(operationRegistry, notifier)

        assertSame(
            InlineReplyTransportDisposition.TrackedFailure,
            handler.handle(failed(failedOperation, TransportResult.FailureStage.SENT_CALLBACK)),
        )
        assertSame(
            InlineReplyTransportDisposition.TrackedComplete,
            handler.handle(sent(successfulOperation, 0, 1)),
        )

        assertEquals(listOf(CONVERSATION), notifier.replyFailures)
        assertEquals(listOf(CONVERSATION), notifier.cancelledConversations)
        assertEquals(emptyList<ConversationId>(), notifier.cancelledReplyFailures)
    }

    @Test
    fun roleLossDefersSuccessAndFailureSideEffectsUntilRoleReturns() {
        val store = InMemoryReplyOperationStore(maximumEntries = 4)
        val operationRegistry = registry(store, 693L)
        val successfulOperation = reserveClaimed(operationRegistry, CONVERSATION, 691L)
        val failedOperation = reserveClaimed(operationRegistry, OTHER_CONVERSATION, 692L)
        val roleLostNotifier = FakeMessageNotifier()
        val roleLostHandler = InlineReplyTransportResultHandler(
            replyOperations = operationRegistry,
            messageNotifier = roleLostNotifier,
            userVisibleEffectsAllowed = { false },
        )

        assertSame(
            InlineReplyTransportDisposition.TrackedUnresolved,
            roleLostHandler.handle(sent(successfulOperation, 0, 1)),
        )
        assertSame(
            InlineReplyTransportDisposition.TrackedFailure,
            roleLostHandler.handle(
                failed(failedOperation, TransportResult.FailureStage.SENT_CALLBACK),
            ),
        )
        assertEquals(emptyList<ConversationId>(), roleLostNotifier.cancelledConversations)
        assertEquals(emptyList<ConversationId>(), roleLostNotifier.replyFailures)

        val roleReturnedNotifier = FakeMessageNotifier()
        val recreatedRegistry = registry(store, 694L)
        InlineReplyTransportResultHandler(recreatedRegistry, roleReturnedNotifier)
            .reconcilePendingOperations()

        assertEquals(listOf(CONVERSATION), roleReturnedNotifier.cancelledConversations)
        assertEquals(listOf(OTHER_CONVERSATION), roleReturnedNotifier.replyFailures)
        assertEquals(
            ReplyOperationPendingSuccessesResult.Available(emptyList()),
            recreatedRegistry.pendingSuccesses(),
        )
        assertEquals(
            ReplyOperationPendingFailuresResult.Available(emptyList()),
            recreatedRegistry.pendingFailures(),
        )
    }

    @Test
    fun unknownOrdinarySmsCallbackRemainsAvailableToGeneralTracker() {
        val handler = InlineReplyTransportResultHandler(
            replyOperations = registry(InMemoryReplyOperationStore(maximumEntries = 4), 701L),
            messageNotifier = FakeMessageNotifier(),
        )

        assertSame(
            InlineReplyTransportDisposition.Untracked,
            handler.handle(sent(pendingOperation(799L), unitIndex = 0, unitCount = 1)),
        )
    }

    @Test
    fun markerlessPreUpgradeHighIdRemainsOrdinaryButExplicitInlineMarkerFailsClosed() {
        val handler = InlineReplyTransportResultHandler(
            replyOperations = registry(InMemoryReplyOperationStore(maximumEntries = 4), 711L),
            messageNotifier = FakeMessageNotifier(),
        )
        val legacyHighOperation = pendingOperation(INLINE_REPLY_OPERATION_ID_BOUNDARY + 17L)

        assertSame(
            InlineReplyTransportDisposition.Untracked,
            handler.handle(sent(legacyHighOperation, unitIndex = 0, unitCount = 1)),
        )
        assertSame(
            InlineReplyTransportDisposition.TrackedUnresolved,
            handler.handle(
                sent(
                    operationId = legacyHighOperation,
                    unitIndex = 0,
                    unitCount = 1,
                    operationOrigin = TransportResult.OperationOrigin.INLINE_REPLY,
                ),
            ),
        )
    }

    private fun reserveClaimed(
        registry: ReplyOperationRegistry,
        conversationId: ConversationId,
        sourceIdentifier: Long,
    ): MessageId {
        val operationId = (
            registry.reserve(conversationId, sourceMessage(sourceIdentifier)) as
                ReplyOperationReservationResult.Reserved
            ).operationId
        assertSame(
            ReplyOperationPhaseTransitionResult.Transitioned,
            registry.markClaimed(operationId),
        )
        return operationId
    }

    private fun registry(
        store: InMemoryReplyOperationStore,
        identifier: Long,
    ): ReplyOperationRegistry {
        val nextIdentifier = AtomicLong(identifier)
        return ReplyOperationRegistry(
            store = store,
            clockMillis = { NOW },
            identifierGenerator = ReplyOperationIdentifierGenerator {
                nextIdentifier.getAndIncrement()
            },
        )
    }

    private fun sent(
        operationId: MessageId,
        unitIndex: Int,
        unitCount: Int,
        providerMessageId: ProviderMessageId? = null,
        operationOrigin: TransportResult.OperationOrigin = TransportResult.OperationOrigin.UNMARKED,
    ) = TransportResult.Sent(
        operationId = operationId,
        transport = MessageTransportKind.SMS,
        platformResultCode = 0,
        unitIndex = unitIndex,
        unitCount = unitCount,
        providerMessageId = providerMessageId,
        operationOrigin = operationOrigin,
    )

    private fun delivered(
        operationId: MessageId,
        unitIndex: Int,
        unitCount: Int,
        providerMessageId: ProviderMessageId? = null,
    ) = TransportResult.Delivered(
        operationId = operationId,
        transport = MessageTransportKind.SMS,
        platformResultCode = 0,
        unitIndex = unitIndex,
        unitCount = unitCount,
        providerMessageId = providerMessageId,
    )

    private fun failed(
        operationId: MessageId,
        stage: TransportResult.FailureStage,
        unitIndex: Int = 0,
        unitCount: Int = 1,
        providerMessageId: ProviderMessageId? = null,
    ) = TransportResult.Failed(
        operationId = operationId,
        transport = MessageTransportKind.SMS,
        reason = TransportResult.FailureReason.PLATFORM_REJECTED,
        retryable = false,
        stage = stage,
        unitIndex = unitIndex,
        unitCount = unitCount,
        providerMessageId = providerMessageId,
    )

    private fun sourceMessage(value: Long) = MessageId(ProviderKind.SMS, value + 1_000L)

    private fun providerMessage(value: Long) = ProviderMessageId(ProviderKind.SMS, value)

    private fun pendingOperation(value: Long) =
        MessageId(ProviderKind.PENDING_OPERATION, value)

    private companion object {
        const val NOW = 1_704_067_200_000L
        val CONVERSATION = ConversationId(71L)
        val OTHER_CONVERSATION = ConversationId(72L)
    }
}
