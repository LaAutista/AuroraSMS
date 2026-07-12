// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.notifications.InlineReplyDisposition
import org.aurorasms.core.notifications.InlineReplyRequest
import org.aurorasms.core.testing.FakeMessageNotifier
import org.aurorasms.core.testing.FakeMessageTransport
import org.aurorasms.core.testing.FakeRoleState
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InlineReplyOrchestratorTest {
    @Test
    fun validReplyIsOwnedOnceAndUsesExactTarget() = runTest {
        val conversationId = ConversationId(41)
        val recipient = ParticipantAddress("+12025550131")
        val targets = ReplyTargetRegistry(clockMillis = { NOW }).apply {
            remember("SMS:501", conversationId, recipient, AuroraSubscriptionId(2))
        }
        val transport = FakeMessageTransport()
        val notifier = FakeMessageNotifier()
        val orchestrator = InlineReplyOrchestrator(
            roleState = FakeRoleState(held = true),
            replyTargets = targets,
            replayGuard = inMemoryReplayGuard(),
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
        assertEquals(listOf(conversationId), notifier.cancelledConversations)
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
        val transport = FakeMessageTransport()
        val orchestrator = InlineReplyOrchestrator(
            roleState = FakeRoleState(held = true),
            replyTargets = targets,
            replayGuard = inMemoryReplayGuard(),
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
        val firstTransport = FakeMessageTransport()
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

        val recreatedTransport = FakeMessageTransport()
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
        val transport = FakeMessageTransport()
        val orchestrator = InlineReplyOrchestrator(
            roleState = FakeRoleState(held = true),
            replyTargets = targets,
            replayGuard = inMemoryReplayGuard(claims),
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
        val transport = FakeMessageTransport()
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
                )
            }
        }
        val notifier = FakeMessageNotifier()
        val orchestrator = InlineReplyOrchestrator(
            roleState = FakeRoleState(held = true),
            replyTargets = replyTargets(conversationId),
            replayGuard = inMemoryReplayGuard(),
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
        messageTransport = transport,
        messageNotifier = FakeMessageNotifier(),
        clockMillis = { NOW },
    )

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
