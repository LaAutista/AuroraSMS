// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.notifications.MarkConversationReadDisposition
import org.aurorasms.core.notifications.MarkConversationReadRequest
import org.aurorasms.core.telephony.ConversationReadThroughOutcome
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderStoredMessage
import org.aurorasms.core.telephony.SmsProviderDataSource
import org.aurorasms.core.testing.FakeMessageNotifier
import org.aurorasms.core.testing.FakeRoleState
import org.aurorasms.core.testing.FakeSmsProviderDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationMarkReadCoordinatorTest {
    @Test
    fun appliedProviderTransitionCleansOnlyExactOwnersAndSignalsIndex() = runTest {
        val fixture = Fixture()

        val result = fixture.coordinator.handle(REQUEST)

        assertEquals(MarkConversationReadDisposition.APPLIED, result)
        assertEquals(listOf(CONVERSATION to PROVIDER_ID), fixture.provider.calls)
        assertEquals(listOf(CONVERSATION to PROVIDER_ID), fixture.reminders.generations)
        assertEquals(
            listOf(FakeMessageNotifier.IncomingCancellationCall(CONVERSATION, MESSAGE_ID)),
            fixture.notifier.incomingCancellations,
        )
        assertEquals(1, fixture.providerSignals)
    }

    @Test
    fun staleOrMismatchedSourceCannotCleanNotificationOrReminder() = runTest {
        val fixture = Fixture()
        fixture.provider.result = ProviderAccessResult.Success(
            ConversationReadThroughOutcome.SOURCE_ABSENT_OR_MISMATCH,
        )

        val result = fixture.coordinator.handle(REQUEST)

        assertEquals(MarkConversationReadDisposition.REJECTED, result)
        assertTrue(fixture.reminders.generations.isEmpty())
        assertTrue(fixture.notifier.incomingCancellations.isEmpty())
        assertEquals(0, fixture.providerSignals)
    }

    @Test
    fun roleLossRejectsBeforeProviderMutation() = runTest {
        val fixture = Fixture(roleHeld = false)

        val result = fixture.coordinator.handle(REQUEST)

        assertEquals(MarkConversationReadDisposition.REJECTED, result)
        assertTrue(fixture.provider.calls.isEmpty())
        assertTrue(fixture.reminders.generations.isEmpty())
        assertTrue(fixture.notifier.incomingCancellations.isEmpty())
    }

    private class Fixture(roleHeld: Boolean = true) {
        val role = FakeRoleState(held = roleHeld)
        val provider = MarkReadProvider()
        val notifier = FakeMessageNotifier()
        val reminders = RecordingReminderController()
        var providerSignals = 0
        val coordinator = NotificationMarkReadCoordinator(
            roleState = role,
            smsProvider = provider,
            notifier = notifier,
            reminders = reminders,
            onProviderChanged = { providerSignals += 1 },
        )
    }

    private class MarkReadProvider : SmsProviderDataSource by FakeSmsProviderDataSource() {
        val calls = mutableListOf<Pair<ConversationId, ProviderMessageId>>()
        var result: ProviderAccessResult<ConversationReadThroughOutcome> =
            ProviderAccessResult.Success(ConversationReadThroughOutcome.APPLIED_OR_ALREADY_READ)

        override suspend fun markConversationReadThrough(
            conversationId: ConversationId,
            throughMessageId: ProviderMessageId,
        ): ProviderAccessResult<ConversationReadThroughOutcome> {
            calls += conversationId to throughMessageId
            return result
        }
    }

    private class RecordingReminderController : NotificationReminderController {
        override val delayMinutes: StateFlow<Int> = MutableStateFlow(0)
        val generations = mutableListOf<Pair<ConversationId, ProviderMessageId>>()

        override suspend fun setDelayMinutes(value: Int): Boolean = true
        override suspend fun schedule(stored: ProviderStoredMessage): Boolean = false
        override suspend fun handleAlarm(id: NotificationReminderId) = Unit
        override suspend fun cancelConversation(conversationId: ConversationId) = Unit
        override suspend fun cancelGenerationOwner(
            conversationId: ConversationId,
            messageId: ProviderMessageId,
        ) {
            generations += conversationId to messageId
        }
        override suspend fun recover(reason: NotificationReminderRecoveryReason) = Unit
        override suspend fun fence() = Unit
    }

    private companion object {
        val CONVERSATION = ConversationId(91L)
        val MESSAGE_ID = MessageId(ProviderKind.SMS, 701L)
        val PROVIDER_ID = ProviderMessageId(ProviderKind.SMS, 701L)
        val REQUEST = MarkConversationReadRequest(CONVERSATION, MESSAGE_ID)
    }
}
