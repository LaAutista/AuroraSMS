// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageDeliveryFingerprint
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.notifications.NotificationPostResult
import org.aurorasms.core.telephony.IncomingMessage
import org.aurorasms.core.telephony.IncomingPersistResult
import org.aurorasms.core.telephony.IncomingSmsNotificationReplay
import org.aurorasms.core.telephony.IncomingSmsNotificationReplayRequest
import org.aurorasms.core.telephony.IncomingSmsRecord
import org.aurorasms.core.telephony.ContactResolver
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ResolvedContact
import org.aurorasms.core.telephony.SmsProviderDataSource
import org.aurorasms.core.testing.FakeContactResolver
import org.aurorasms.core.testing.FakeMessageNotifier
import org.aurorasms.core.testing.FakeRoleState
import org.aurorasms.core.testing.FakeSmsProviderDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingMessageOrchestratorTest {
    @Test
    fun persistedSmsPostsOneNotificationAndRegistersReplyTarget() = runTest {
        val address = ParticipantAddress("+12025550121")
        val contacts = FakeContactResolver().apply {
            put(ResolvedContact(address, "Aster Vale", null))
        }
        val provider = FakeSmsProviderDataSource()
        val notifier = FakeMessageNotifier()
        val targets = ReplyTargetRegistry(clockMillis = { NOW })
        var providerInsertSignals = 0
        val reminderCandidates = mutableListOf<org.aurorasms.core.telephony.ProviderStoredMessage>()
        val orchestrator = IncomingMessageOrchestrator(
            roleState = FakeRoleState(held = true),
            smsProvider = provider,
            contactResolver = contacts,
            messageNotifier = notifier,
            replyTargets = targets,
            onProviderInsertComplete = { providerInsertSignals += 1 },
            onIncomingNotificationCommitted = reminderCandidates::add,
        )

        val result = orchestrator.persist(incomingSms(address))

        val persisted = result as IncomingPersistResult.Persisted
        assertEquals(1, provider.insertedIncoming.size)
        assertEquals(1, notifier.incoming.size)
        assertEquals(1, providerInsertSignals)
        assertEquals(listOf(persisted.providerId), reminderCandidates.map { it.providerId })
        assertEquals("Aster Vale", notifier.incoming.single().message.senderDisplayName)
        assertFalse(notifier.incoming.single().message.senderPersonKey.contains(address.value))
        assertNotNull(targets.resolve(persisted.conversationId, "SMS:1", NOW))
    }

    @Test
    fun notificationsDisabledStillMarksDeliveryHandledWithoutDuplicateRetry() = runTest {
        val address = ParticipantAddress("+12025550125")
        val backingProvider = FakeSmsProviderDataSource()
        var markHandledCalls = 0
        val provider = object : SmsProviderDataSource by backingProvider {
            override suspend fun markIncomingHandled(
                deliveryFingerprint: MessageDeliveryFingerprint,
                providerId: ProviderMessageId,
                conversationId: ConversationId,
            ): ProviderAccessResult<Unit> {
                markHandledCalls += 1
                return backingProvider.markIncomingHandled(
                    deliveryFingerprint = deliveryFingerprint,
                    providerId = providerId,
                    conversationId = conversationId,
                )
            }
        }
        val notifier = FakeMessageNotifier().apply {
            incomingResponder = { _, _ -> NotificationPostResult.NotificationsDisabled }
        }
        val targets = ReplyTargetRegistry(clockMillis = { NOW })
        val orchestrator = IncomingMessageOrchestrator(
            roleState = FakeRoleState(held = true),
            smsProvider = provider,
            contactResolver = FakeContactResolver(),
            messageNotifier = notifier,
            replyTargets = targets,
        )
        val incoming = incomingSms(address)

        val first = orchestrator.persist(incoming)
        val replay = orchestrator.persist(incoming)

        val persisted = first as IncomingPersistResult.Persisted
        assertEquals(
            IncomingPersistResult.Duplicate(persisted.providerId, persisted.conversationId),
            replay,
        )
        assertEquals(1, backingProvider.insertedIncoming.size)
        assertEquals(
            incoming.deliveryFingerprint,
            backingProvider.insertedIncoming.single().deliveryFingerprint,
        )
        assertEquals(1, markHandledCalls)
        assertEquals(1, notifier.incoming.size)
        assertEquals(incoming.body, notifier.incoming.single().message.body)
        assertNull(targets.resolve(persisted.conversationId, "SMS:1", NOW))
    }

    @Test
    fun rejectedNotificationRemainsUnacknowledgedAndReplayRetriesExactlyOnce() = runTest {
        val address = ParticipantAddress("+12025550127")
        val provider = FakeSmsProviderDataSource()
        var postAttempt = 0
        val notifier = FakeMessageNotifier().apply {
            incomingResponder = { message, _ ->
                postAttempt += 1
                if (postAttempt == 1) {
                    NotificationPostResult.Rejected(
                        NotificationPostResult.RejectionReason.GENERATION_STATE_UNAVAILABLE,
                    )
                } else {
                    NotificationPostResult.Posted(message.conversationId.value.toInt())
                }
            }
        }
        val targets = ReplyTargetRegistry(clockMillis = { NOW })
        val orchestrator = IncomingMessageOrchestrator(
            roleState = FakeRoleState(held = true),
            smsProvider = provider,
            contactResolver = FakeContactResolver(),
            messageNotifier = notifier,
            replyTargets = targets,
        )
        val incoming = incomingSms(address)

        val rejected = orchestrator.persist(incoming)
        assertEquals(
            IncomingPersistResult.Rejected(IncomingPersistResult.Reason.NOTIFICATION_UNAVAILABLE),
            rejected,
        )
        assertNull(targets.resolve(ConversationId(10_000L), "SMS:1", NOW))
        val recovered = orchestrator.persist(incoming) as IncomingPersistResult.Persisted
        assertEquals(
            IncomingPersistResult.Duplicate(recovered.providerId, recovered.conversationId),
            orchestrator.persist(incoming),
        )

        assertEquals(1, provider.insertedIncoming.size)
        assertEquals(2, notifier.incoming.size)
        assertEquals(2, postAttempt)
        assertNotNull(targets.resolve(recovered.conversationId, "SMS:1", NOW))
    }

    @Test
    fun supersededReplayIsTerminalAndCannotLeaveAnObsoleteReplyTarget() = runTest {
        val address = ParticipantAddress("+12025550129")
        val provider = FakeSmsProviderDataSource()
        val notifier = FakeMessageNotifier().apply {
            incomingResponder = { _, _ -> NotificationPostResult.SupersededByNewer }
        }
        val targets = ReplyTargetRegistry(clockMillis = { NOW })
        val orchestrator = IncomingMessageOrchestrator(
            roleState = FakeRoleState(held = true),
            smsProvider = provider,
            contactResolver = FakeContactResolver(),
            messageNotifier = notifier,
            replyTargets = targets,
        )

        val first = orchestrator.persist(incomingSms(address)) as IncomingPersistResult.Persisted
        assertEquals(
            IncomingPersistResult.Duplicate(first.providerId, first.conversationId),
            orchestrator.persist(incomingSms(address)),
        )
        assertEquals(1, notifier.incoming.size)
        assertNull(targets.resolve(first.conversationId, "SMS:1", NOW))
    }

    @Test
    fun roleLossAfterProviderInsertDefersNotificationAndAcknowledgementUntilRoleReturns() = runTest {
        val address = ParticipantAddress("+12025550130")
        val role = FakeRoleState(held = true)
        val provider = FakeSmsProviderDataSource()
        val notifier = FakeMessageNotifier()
        val contacts = object : ContactResolver {
            override suspend fun resolve(addresses: List<ParticipantAddress>): List<ResolvedContact> {
                role.held = false
                return addresses.map { ResolvedContact(it, displayName = null, photoUri = null) }
            }
        }
        val targets = ReplyTargetRegistry(clockMillis = { NOW })
        val orchestrator = IncomingMessageOrchestrator(
            roleState = role,
            smsProvider = provider,
            contactResolver = contacts,
            messageNotifier = notifier,
            replyTargets = targets,
        )

        assertEquals(
            IncomingPersistResult.Rejected(IncomingPersistResult.Reason.ROLE_NOT_HELD),
            orchestrator.persist(incomingSms(address)),
        )
        assertEquals(1, provider.insertedIncoming.size)
        assertTrue(notifier.incoming.isEmpty())
        assertNull(targets.resolve(ConversationId(10_000L), "SMS:1", NOW))

        role.held = true
        val recoveredContacts = FakeContactResolver()
        val recoveredOrchestrator = IncomingMessageOrchestrator(
            roleState = role,
            smsProvider = provider,
            contactResolver = recoveredContacts,
            messageNotifier = notifier,
            replyTargets = targets,
        )
        assertEquals(
            IncomingNotificationRecoveryResult.Complete(recoveredCount = 1),
            recoveredOrchestrator.recoverPendingNotifications(),
        )
        assertEquals(1, notifier.incoming.size)
    }

    @Test
    fun roleLossAfterNotificationPostExactCancelsGenerationAndDefersAcknowledgement() = runTest {
        val role = FakeRoleState(held = true)
        val provider = FakeSmsProviderDataSource()
        val notifier = FakeMessageNotifier().apply {
            incomingResponder = { message, _ ->
                role.held = false
                NotificationPostResult.Posted(message.conversationId.value.toInt())
            }
        }
        val targets = ReplyTargetRegistry(clockMillis = { NOW })
        val orchestrator = IncomingMessageOrchestrator(
            roleState = role,
            smsProvider = provider,
            contactResolver = FakeContactResolver(),
            messageNotifier = notifier,
            replyTargets = targets,
        )

        assertEquals(
            IncomingPersistResult.Rejected(IncomingPersistResult.Reason.ROLE_NOT_HELD),
            orchestrator.persist(incomingSms(ParticipantAddress("+12025550132"))),
        )
        assertEquals(
            FakeMessageNotifier.IncomingCancellationCall(
                conversationId = ConversationId(10_000L),
                expectedMessageId = MessageId(ProviderKind.SMS, 1L),
            ),
            notifier.incomingCancellations.single(),
        )
        assertNull(targets.resolve(ConversationId(10_000L), "SMS:1", NOW))
        assertEquals(
            1,
            (
                provider.readPendingIncomingNotifications(
                    IncomingSmsNotificationReplayRequest(1),
                ) as ProviderAccessResult.Success
            ).value.size,
        )
    }

    @Test
    fun providerRoleRequiredAfterNotificationPostExactCancelsGeneration() = runTest {
        val backingProvider = FakeSmsProviderDataSource()
        val provider = object : SmsProviderDataSource by backingProvider {
            override suspend fun markIncomingHandled(
                deliveryFingerprint: MessageDeliveryFingerprint,
                providerId: ProviderMessageId,
                conversationId: ConversationId,
            ): ProviderAccessResult<Unit> = ProviderAccessResult.RoleRequired
        }
        val notifier = FakeMessageNotifier()
        val targets = ReplyTargetRegistry(clockMillis = { NOW })
        val orchestrator = IncomingMessageOrchestrator(
            roleState = FakeRoleState(held = true),
            smsProvider = provider,
            contactResolver = FakeContactResolver(),
            messageNotifier = notifier,
            replyTargets = targets,
        )

        assertEquals(
            IncomingPersistResult.Rejected(IncomingPersistResult.Reason.ROLE_NOT_HELD),
            orchestrator.persist(incomingSms(ParticipantAddress("+12025550133"))),
        )
        assertEquals(
            FakeMessageNotifier.IncomingCancellationCall(
                conversationId = ConversationId(10_000L),
                expectedMessageId = MessageId(ProviderKind.SMS, 1L),
            ),
            notifier.incomingCancellations.single(),
        )
        assertNull(targets.resolve(ConversationId(10_000L), "SMS:1", NOW))
    }

    @Test
    fun roleLossFenceExactCancelsGenerationPostedImmediatelyBeforeRoleFlip() = runTest {
        val role = FakeRoleState(held = true)
        var cancellationAttempt = 0
        val notifier = FakeMessageNotifier().apply {
            cancelAllIncomingResponder = {
                cancellationAttempt += 1
                if (cancellationAttempt == 1) {
                    org.aurorasms.core.notifications.NotificationCancelResult.RetryableFailure
                } else {
                    org.aurorasms.core.notifications.NotificationCancelResult.Cancelled
                }
            }
        }
        val targets = ReplyTargetRegistry(clockMillis = { NOW })
        val orchestrator = IncomingMessageOrchestrator(
            roleState = role,
            smsProvider = FakeSmsProviderDataSource(),
            contactResolver = FakeContactResolver(),
            messageNotifier = notifier,
            replyTargets = targets,
        )

        assertTrue(
            orchestrator.persist(incomingSms(ParticipantAddress("+12025550135")))
                is IncomingPersistResult.Persisted,
        )
        assertEquals(0, notifier.cancelAllIncomingCalls)

        role.held = false
        orchestrator.onRoleLost()

        assertEquals(2, notifier.cancelAllIncomingCalls)
        assertNull(targets.resolve(ConversationId(10_000L), "SMS:1", NOW))

        orchestrator.onRoleLost()
        assertEquals(3, notifier.cancelAllIncomingCalls)
    }

    @Test
    fun terminalPostOutcomeCannotAcknowledgeWhenExactTargetCleanupFails() = runTest {
        val provider = FakeSmsProviderDataSource()
        val removals = mutableListOf<Pair<String, ConversationId>>()
        val targetStore = object : ReplyTargetStore {
            override fun put(target: ReplyTarget, nowMillis: Long): Boolean = false

            override fun get(requestId: String, nowMillis: Long): ReplyTarget? = null

            override fun remove(requestId: String, conversationId: ConversationId): Boolean {
                removals += requestId to conversationId
                return false
            }

            override fun clear(): Boolean = true
        }
        val notifier = FakeMessageNotifier().apply {
            incomingResponder = { _, _ -> NotificationPostResult.NotificationsDisabled }
        }
        val orchestrator = IncomingMessageOrchestrator(
            roleState = FakeRoleState(held = true),
            smsProvider = provider,
            contactResolver = FakeContactResolver(),
            messageNotifier = notifier,
            replyTargets = ReplyTargetRegistry(
                clockMillis = { NOW },
                targetStore = targetStore,
            ),
        )

        assertEquals(
            IncomingPersistResult.Rejected(
                IncomingPersistResult.Reason.NOTIFICATION_UNAVAILABLE,
            ),
            orchestrator.persist(incomingSms(ParticipantAddress("+12025550134"))),
        )
        assertEquals(listOf("SMS:1" to ConversationId(10_000L)), removals)
        assertEquals(
            1,
            (
                provider.readPendingIncomingNotifications(
                    IncomingSmsNotificationReplayRequest(1),
                ) as ProviderAccessResult.Success
            ).value.size,
        )
    }

    @Test
    fun startupRecoveryRepostsStoredNotificationWithoutReceivingPduAgain() = runTest {
        val address = ParticipantAddress("+12025550128")
        val provider = FakeSmsProviderDataSource()
        val rejectingNotifier = FakeMessageNotifier().apply {
            incomingResponder = { _, _ ->
                NotificationPostResult.Rejected(
                    NotificationPostResult.RejectionReason.GENERATION_STATE_UNAVAILABLE,
                )
            }
        }
        val firstProcess = IncomingMessageOrchestrator(
            roleState = FakeRoleState(held = true),
            smsProvider = provider,
            contactResolver = FakeContactResolver(),
            messageNotifier = rejectingNotifier,
            replyTargets = ReplyTargetRegistry(clockMillis = { NOW }),
        )
        val incoming = incomingSms(address)
        assertEquals(
            IncomingPersistResult.Rejected(IncomingPersistResult.Reason.NOTIFICATION_UNAVAILABLE),
            firstProcess.persist(incoming),
        )

        val recoveredNotifier = FakeMessageNotifier()
        val recreatedProcess = IncomingMessageOrchestrator(
            roleState = FakeRoleState(held = true),
            smsProvider = provider,
            contactResolver = FakeContactResolver(),
            messageNotifier = recoveredNotifier,
            replyTargets = ReplyTargetRegistry(clockMillis = { NOW }),
        )

        assertEquals(
            IncomingNotificationRecoveryResult.Complete(recoveredCount = 1),
            recreatedProcess.recoverPendingNotifications(),
        )
        assertEquals(1, provider.insertedIncoming.size)
        assertEquals(incoming.body, recoveredNotifier.incoming.single().message.body)

        val thirdProcessNotifier = FakeMessageNotifier()
        val thirdProcess = IncomingMessageOrchestrator(
            roleState = FakeRoleState(held = true),
            smsProvider = provider,
            contactResolver = FakeContactResolver(),
            messageNotifier = thirdProcessNotifier,
            replyTargets = ReplyTargetRegistry(clockMillis = { NOW }),
        )
        assertEquals(
            IncomingNotificationRecoveryResult.Complete(recoveredCount = 0),
            thirdProcess.recoverPendingNotifications(),
        )
        assertTrue(thirdProcessNotifier.incoming.isEmpty())
    }

    @Test
    fun recoveryDrainsStoredWorkThenDefersForAnUnresolvedPendingEntry() = runTest {
        val backingProvider = FakeSmsProviderDataSource()
        val incoming = incomingSms(ParticipantAddress("+12025550131"))
        backingProvider.insertIncoming(
            IncomingSmsRecord(
                deliveryFingerprint = incoming.deliveryFingerprint,
                sender = incoming.sender,
                body = incoming.body,
                sentTimestampMillis = incoming.sentTimestampMillis,
                receivedTimestampMillis = incoming.receivedTimestampMillis,
                subscriptionId = incoming.subscriptionId,
            ),
        )
        var replayReads = 0
        val provider = object : SmsProviderDataSource by backingProvider {
            override suspend fun readPendingIncomingNotifications(
                request: IncomingSmsNotificationReplayRequest,
            ): ProviderAccessResult<List<IncomingSmsNotificationReplay>> {
                replayReads += 1
                return if (replayReads == 1) {
                    backingProvider.readPendingIncomingNotifications(request)
                } else {
                    ProviderAccessResult.Unavailable(
                        "resolve pending incoming SMS provider row",
                    )
                }
            }
        }
        val notifier = FakeMessageNotifier()
        val orchestrator = IncomingMessageOrchestrator(
            roleState = FakeRoleState(held = true),
            smsProvider = provider,
            contactResolver = FakeContactResolver(),
            messageNotifier = notifier,
            replyTargets = ReplyTargetRegistry(clockMillis = { NOW }),
        )

        assertEquals(
            IncomingNotificationRecoveryResult.Deferred(recoveredCount = 1),
            orchestrator.recoverPendingNotifications(),
        )
        assertEquals(2, replayReads)
        assertEquals(1, notifier.incoming.size)
        assertEquals(
            emptyList<IncomingSmsNotificationReplay>(),
            (
                backingProvider.readPendingIncomingNotifications(
                    IncomingSmsNotificationReplayRequest(1),
                ) as ProviderAccessResult.Success
            ).value,
        )
    }

    @Test
    fun missingRoleDoesNotWriteOrNotify() = runTest {
        val provider = FakeSmsProviderDataSource()
        val notifier = FakeMessageNotifier()
        val orchestrator = IncomingMessageOrchestrator(
            roleState = FakeRoleState(held = false),
            smsProvider = provider,
            contactResolver = FakeContactResolver(),
            messageNotifier = notifier,
            replyTargets = ReplyTargetRegistry(clockMillis = { NOW }),
        )

        val result = orchestrator.persist(incomingSms(ParticipantAddress("+12025550122")))

        assertEquals(
            IncomingPersistResult.Rejected(IncomingPersistResult.Reason.ROLE_NOT_HELD),
            result,
        )
        assertEquals(0, provider.insertedIncoming.size)
        assertEquals(0, notifier.incoming.size)
    }

    @Test
    fun rawMmsIsRejectedUntilCodecExists() = runTest {
        val notifier = FakeMessageNotifier()
        val orchestrator = IncomingMessageOrchestrator(
            roleState = FakeRoleState(held = true),
            smsProvider = FakeSmsProviderDataSource(),
            contactResolver = FakeContactResolver(),
            messageNotifier = notifier,
            replyTargets = ReplyTargetRegistry(clockMillis = { NOW }),
        )

        val result = orchestrator.persist(
            IncomingMessage.MmsWapPush(
                pdu = byteArrayOf(1, 2, 3),
                mimeType = IncomingMessage.MmsWapPush.MMS_MIME_TYPE,
                receivedTimestampMillis = NOW,
                subscriptionId = AuroraSubscriptionId(1),
            ),
        )

        assertEquals(
            IncomingPersistResult.Rejected(IncomingPersistResult.Reason.CODEC_UNAVAILABLE),
            result,
        )
        assertEquals(0, notifier.incoming.size)
    }

    @Test
    fun completedDeliveryReplayDoesNotInsertOrNotifyTwice() = runTest {
        val address = ParticipantAddress("+12025550123")
        val provider = FakeSmsProviderDataSource()
        val notifier = FakeMessageNotifier()
        val orchestrator = IncomingMessageOrchestrator(
            roleState = FakeRoleState(held = true),
            smsProvider = provider,
            contactResolver = FakeContactResolver(),
            messageNotifier = notifier,
            replyTargets = ReplyTargetRegistry(clockMillis = { NOW }),
        )
        val incoming = incomingSms(address)

        val first = orchestrator.persist(incoming)
        val replay = orchestrator.persist(incoming)

        assertTrue(first is IncomingPersistResult.Persisted)
        assertTrue(replay is IncomingPersistResult.Duplicate)
        assertEquals(1, provider.insertedIncoming.size)
        assertEquals(1, notifier.incoming.size)
    }

    @Test
    fun twoDistinctSameSenderDeliveriesPersistAndNotifyOnceEachAcrossReplay() = runTest {
        val address = ParticipantAddress("+12025550126")
        val provider = FakeSmsProviderDataSource()
        val notifier = FakeMessageNotifier()
        val orchestrator = IncomingMessageOrchestrator(
            roleState = FakeRoleState(held = true),
            smsProvider = provider,
            contactResolver = FakeContactResolver(),
            messageNotifier = notifier,
            replyTargets = ReplyTargetRegistry(clockMillis = { NOW }),
        )
        val firstIncoming = incomingSms(address)
        val secondIncoming = incomingSms(address).copy(
            deliveryFingerprint = SECOND_DELIVERY_FINGERPRINT,
            body = "Distinct synthetic message.",
            sentTimestampMillis = NOW,
            receivedTimestampMillis = NOW + 100,
        )

        val first = orchestrator.persist(firstIncoming) as IncomingPersistResult.Persisted
        val second = orchestrator.persist(secondIncoming) as IncomingPersistResult.Persisted

        assertEquals(2, setOf(first.providerId, second.providerId).size)
        assertEquals(first.conversationId, second.conversationId)
        assertEquals(2, provider.insertedIncoming.size)
        assertEquals(2, notifier.incoming.size)

        val firstReplay = orchestrator.persist(firstIncoming)
        val secondReplay = orchestrator.persist(secondIncoming)

        assertEquals(
            IncomingPersistResult.Duplicate(first.providerId, first.conversationId),
            firstReplay,
        )
        assertEquals(
            IncomingPersistResult.Duplicate(second.providerId, second.conversationId),
            secondReplay,
        )
        assertEquals(2, provider.insertedIncoming.size)
        assertEquals(2, notifier.incoming.size)
    }

    @Test
    fun unacknowledgedStoredDeliveryIsRecoveredAndNotifiedOnce() = runTest {
        val address = ParticipantAddress("+12025550124")
        val provider = FakeSmsProviderDataSource()
        val incoming = incomingSms(address)
        provider.insertIncoming(
            IncomingSmsRecord(
                deliveryFingerprint = incoming.deliveryFingerprint,
                sender = incoming.sender,
                body = incoming.body,
                sentTimestampMillis = incoming.sentTimestampMillis,
                receivedTimestampMillis = incoming.receivedTimestampMillis,
                subscriptionId = incoming.subscriptionId,
            ),
        )
        val notifier = FakeMessageNotifier()
        val orchestrator = IncomingMessageOrchestrator(
            roleState = FakeRoleState(held = true),
            smsProvider = provider,
            contactResolver = FakeContactResolver(),
            messageNotifier = notifier,
            replyTargets = ReplyTargetRegistry(clockMillis = { NOW }),
        )

        val recovered = orchestrator.persist(incoming)
        val replay = orchestrator.persist(incoming)

        assertTrue(recovered is IncomingPersistResult.Persisted)
        assertTrue(replay is IncomingPersistResult.Duplicate)
        assertEquals(1, provider.insertedIncoming.size)
        assertEquals(1, notifier.incoming.size)
    }

    private fun incomingSms(address: ParticipantAddress) = IncomingMessage.Sms(
        deliveryFingerprint = DELIVERY_FINGERPRINT,
        sender = address,
        body = "Synthetic phase-one message.",
        sentTimestampMillis = NOW - 100,
        receivedTimestampMillis = NOW,
        subscriptionId = AuroraSubscriptionId(1),
        sourcePduCount = 1,
    )

    private companion object {
        const val NOW = 1_704_067_200_000L
        val DELIVERY_FINGERPRINT = MessageDeliveryFingerprint.fromSha256(
            ByteArray(MessageDeliveryFingerprint.SHA_256_BYTES) { (it + 9).toByte() },
        )
        val SECOND_DELIVERY_FINGERPRINT = MessageDeliveryFingerprint.fromSha256(
            ByteArray(MessageDeliveryFingerprint.SHA_256_BYTES) { (it + 10).toByte() },
        )
    }
}
