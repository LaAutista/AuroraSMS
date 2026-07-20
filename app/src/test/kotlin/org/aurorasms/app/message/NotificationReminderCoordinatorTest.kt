// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.MessageSyncFingerprint
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.notifications.MessageNotifier
import org.aurorasms.core.notifications.NotificationPostResult
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderStoredMessage
import org.aurorasms.core.telephony.SmsProviderDataSource
import org.aurorasms.core.telephony.SmsProviderMessage
import org.aurorasms.core.testing.FakeMessageNotifier
import org.aurorasms.core.testing.FakeRoleState
import org.aurorasms.core.testing.FakeSmsProviderDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationReminderCoordinatorTest {
    @Test
    fun defaultOffCreatesNoDurableOwnerOrAlarm() = runTest {
        val fixture = Fixture(delayMinutes = 0)

        assertFalse(fixture.coordinator.schedule(STORED))

        assertTrue(fixture.store.owners.isEmpty())
        assertTrue(fixture.alarms.armed.isEmpty())
    }

    @Test
    fun newMessageInSameConversationReplacesSingleOneShotOwner() = runTest {
        val fixture = Fixture(delayMinutes = 15)
        assertTrue(fixture.coordinator.schedule(STORED))
        val first = fixture.store.owners.values.single()

        assertTrue(
            fixture.coordinator.schedule(
                ProviderStoredMessage(ProviderMessageId(ProviderKind.SMS, 12L), CONVERSATION),
            ),
        )

        assertEquals(1, fixture.store.owners.size)
        assertTrue(first.id in fixture.alarms.cancelled)
        assertEquals(2, fixture.alarms.armCalls)
    }

    @Test
    fun generationCancellationCannotRemoveNewerReminderOwner() = runTest {
        val fixture = Fixture(delayMinutes = 15)
        assertTrue(fixture.coordinator.schedule(STORED))
        val newer = ProviderStoredMessage(
            ProviderMessageId(ProviderKind.SMS, 12L),
            CONVERSATION,
        )
        assertTrue(fixture.coordinator.schedule(newer))

        fixture.coordinator.cancelGenerationOwner(CONVERSATION, STORED.providerId)

        assertEquals(newer.providerId, fixture.store.owners.values.single().messageId)
    }

    @Test
    fun generationCancellationRemovesOnlyExactReminderOwner() = runTest {
        val fixture = Fixture(delayMinutes = 15)
        assertTrue(fixture.coordinator.schedule(STORED))
        val owner = fixture.store.owners.values.single()

        fixture.coordinator.cancelGenerationOwner(CONVERSATION, STORED.providerId)

        assertTrue(fixture.store.owners.isEmpty())
        assertTrue(owner.id in fixture.alarms.cancelled)
    }

    @Test
    fun dueUnreadExactRowPostsGenericReminderAtMostOnce() = runTest {
        val fixture = Fixture(delayMinutes = 15)
        fixture.provider.row = smsRow(read = false)
        assertTrue(fixture.coordinator.schedule(STORED))
        val owner = fixture.store.owners.values.single()
        fixture.now = owner.dueTimestampMillis

        fixture.coordinator.handleAlarm(owner.id)
        fixture.coordinator.handleAlarm(owner.id)

        assertEquals(listOf(CONVERSATION to STORED.providerId.asMessageId()), fixture.reminders)
        assertTrue(fixture.store.owners.isEmpty())
    }

    @Test
    fun readOrMismatchedProviderRowFailsClosedAndCancelsExactGeneration() = runTest {
        val fixture = Fixture(delayMinutes = 15)
        fixture.provider.row = smsRow(read = true)
        assertTrue(fixture.coordinator.schedule(STORED))
        val owner = fixture.store.owners.values.single()
        fixture.now = owner.dueTimestampMillis

        fixture.coordinator.handleAlarm(owner.id)

        assertTrue(fixture.reminders.isEmpty())
        assertEquals(CONVERSATION, fixture.notifier.cancelledConversations.single())
        assertTrue(fixture.store.owners.isEmpty())
    }

    @Test
    fun providerFailureConsumesReminderWithoutCancellingIncomingGeneration() = runTest {
        val fixture = Fixture(delayMinutes = 15)
        fixture.provider.result = ProviderAccessResult.Unavailable("synthetic provider outage")
        assertTrue(fixture.coordinator.schedule(STORED))
        val owner = fixture.store.owners.values.single()
        fixture.now = owner.dueTimestampMillis

        fixture.coordinator.handleAlarm(owner.id)

        assertTrue(fixture.reminders.isEmpty())
        assertTrue(fixture.notifier.cancelledConversations.isEmpty())
        assertTrue(fixture.store.owners.isEmpty())
        assertTrue(owner.id in fixture.alarms.cancelled)
    }

    @Test
    fun preferenceChangeAndBootRecoveryRemovePendingAlarms() = runTest {
        val fixture = Fixture(delayMinutes = 15)
        assertTrue(fixture.coordinator.schedule(STORED))
        val first = fixture.store.owners.values.single().id

        assertTrue(fixture.coordinator.setDelayMinutes(60))
        assertTrue(first in fixture.alarms.cancelled)
        assertTrue(fixture.store.owners.isEmpty())
        assertTrue(fixture.coordinator.schedule(STORED))
        val second = fixture.store.owners.values.single().id

        fixture.coordinator.recover(NotificationReminderRecoveryReason.BOOT_COMPLETED)

        assertTrue(second in fixture.alarms.cancelled)
        assertTrue(fixture.store.owners.isEmpty())
    }

    @Test
    fun roleLossFenceRemovesOwnershipWithoutPosting() = runTest {
        val fixture = Fixture(delayMinutes = 15)
        assertTrue(fixture.coordinator.schedule(STORED))

        fixture.role.held = false
        fixture.coordinator.fence()

        assertTrue(fixture.store.owners.isEmpty())
        assertTrue(fixture.reminders.isEmpty())
    }

    private class Fixture(delayMinutes: Int) {
        var now: Long = NOW
        val role = FakeRoleState(held = true)
        val preferences = FakePreferences(delayMinutes)
        val store = FakeStore()
        val alarms = FakeAlarms()
        val provider = ExactProvider()
        val notifier = FakeMessageNotifier()
        val reminders = mutableListOf<Pair<ConversationId, MessageId>>()
        private val recordingNotifier = object : MessageNotifier by notifier {
            override fun notifyUnreadReminder(
                conversationId: ConversationId,
                expectedMessageId: MessageId,
            ): NotificationPostResult {
                reminders += conversationId to expectedMessageId
                return NotificationPostResult.Posted(1)
            }
        }
        val coordinator = NotificationReminderCoordinator(
            roleState = role,
            smsProvider = provider,
            notifier = recordingNotifier,
            preferences = preferences,
            store = store,
            alarms = alarms,
            nowMillis = { now },
        )
    }

    private class FakePreferences(initial: Int) : NotificationReminderPreferenceStore {
        private val state = MutableStateFlow(initial)
        override val delayMinutes: StateFlow<Int> = state
        override fun setDelayMinutes(value: Int): Boolean {
            state.value = value
            return true
        }
    }

    private class FakeStore : NotificationReminderStore {
        val owners = linkedMapOf<NotificationReminderId, NotificationReminderOwner>()
        private var nextId = 1L
        override fun create(
            messageId: ProviderMessageId,
            conversationId: ConversationId,
            dueTimestampMillis: Long,
            createdTimestampMillis: Long,
        ): NotificationReminderCreation {
            val displaced = owners.values.filter { it.conversationId == conversationId }
            displaced.forEach { owners.remove(it.id) }
            val owner = NotificationReminderOwner(
                NotificationReminderId(nextId++),
                messageId,
                conversationId,
                dueTimestampMillis,
                createdTimestampMillis,
            )
            owners[owner.id] = owner
            return NotificationReminderCreation(owner, displaced)
        }
        override fun read(id: NotificationReminderId) = owners[id]
        override fun all() = owners.values.toList()
        override fun remove(id: NotificationReminderId) = owners.remove(id) != null
        override fun removeConversation(conversationId: ConversationId): List<NotificationReminderOwner> {
            val removed = owners.values.filter { it.conversationId == conversationId }
            removed.forEach { owners.remove(it.id) }
            return removed
        }
        override fun clear(): List<NotificationReminderOwner> = owners.values.toList().also {
            owners.clear()
        }
    }

    private class FakeAlarms : NotificationReminderAlarmDriver {
        val armed = linkedMapOf<NotificationReminderId, Long>()
        val cancelled = mutableListOf<NotificationReminderId>()
        var armCalls = 0
        override fun arm(id: NotificationReminderId, dueTimestampMillis: Long): Boolean {
            armCalls += 1
            armed[id] = dueTimestampMillis
            return true
        }
        override fun cancel(id: NotificationReminderId) {
            cancelled += id
            armed.remove(id)
        }
    }

    private class ExactProvider : SmsProviderDataSource by FakeSmsProviderDataSource() {
        var row: SmsProviderMessage? = null
        var result: ProviderAccessResult<SmsProviderMessage?>? = null
        override suspend fun readExact(
            id: ProviderMessageId,
        ): ProviderAccessResult<SmsProviderMessage?> = result ?: ProviderAccessResult.Success(
            row?.takeIf { it.id == id },
        )
    }

    private companion object {
        const val NOW = 1_704_067_200_000L
        val CONVERSATION = ConversationId(9L)
        val STORED = ProviderStoredMessage(ProviderMessageId(ProviderKind.SMS, 11L), CONVERSATION)
        fun smsRow(read: Boolean) = SmsProviderMessage(
            id = STORED.providerId,
            providerThreadId = ProviderThreadId(CONVERSATION.value),
            sender = null,
            body = "private test body",
            direction = MessageDirection.INCOMING,
            box = MessageBox.INBOX,
            status = MessageStatus.NONE,
            rawStatus = null,
            rawErrorCode = null,
            timestampMillis = NOW,
            sentTimestampMillis = null,
            subscriptionId = null,
            read = read,
            seen = false,
            locked = false,
            syncFingerprint = MessageSyncFingerprint.fromSha256(ByteArray(32) { 7 }),
        )
    }
}
