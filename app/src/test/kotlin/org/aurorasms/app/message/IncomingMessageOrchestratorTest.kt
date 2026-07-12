// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageDeliveryFingerprint
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.telephony.IncomingMessage
import org.aurorasms.core.telephony.IncomingPersistResult
import org.aurorasms.core.telephony.IncomingSmsRecord
import org.aurorasms.core.telephony.ResolvedContact
import org.aurorasms.core.testing.FakeContactResolver
import org.aurorasms.core.testing.FakeMessageNotifier
import org.aurorasms.core.testing.FakeRoleState
import org.aurorasms.core.testing.FakeSmsProviderDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        val orchestrator = IncomingMessageOrchestrator(
            roleState = FakeRoleState(held = true),
            smsProvider = provider,
            contactResolver = contacts,
            messageNotifier = notifier,
            replyTargets = targets,
        )

        val result = orchestrator.persist(incomingSms(address))

        val persisted = result as IncomingPersistResult.Persisted
        assertEquals(1, provider.insertedIncoming.size)
        assertEquals(1, notifier.incoming.size)
        assertEquals("Aster Vale", notifier.incoming.single().message.senderDisplayName)
        assertFalse(notifier.incoming.single().message.senderPersonKey.contains(address.value))
        assertNotNull(targets.resolve(persisted.conversationId, "SMS:1", NOW))
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
    }
}
