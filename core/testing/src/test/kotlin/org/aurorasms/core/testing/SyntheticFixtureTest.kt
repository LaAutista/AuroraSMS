// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.testing

import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.notifications.NotificationCancelResult
import org.aurorasms.core.notifications.NotificationConfig
import org.aurorasms.core.notifications.NotificationPostResult
import org.aurorasms.core.telephony.OutgoingSmsRecord
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.SmsSendRequest
import org.aurorasms.core.telephony.SmsProviderStatus
import org.aurorasms.core.telephony.SmsSubmissionOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticFixtureTest {
    @Test
    fun identities_useOnlyReservedSyntheticAddressSpace() {
        assertTrue(
            SyntheticPeople.all.all { person ->
                person.address.value.startsWith("+120255501") ||
                    person.address.value.endsWith("@example.invalid")
            },
        )
    }

    @Test
    fun fakeSmsProvider_recordsOutgoingAndPagesDeterministically() = runTest {
        val fake = FakeSmsProviderDataSource(listOf(SyntheticMessages.smsProviderMessage()))
        val outgoing = OutgoingSmsRecord(
            recipient = SyntheticPeople.MILO.address,
            body = SyntheticMessages.SECOND_BODY,
            timestampMillis = SyntheticMessages.FIXED_TIMESTAMP_MILLIS + 1_000,
            subscriptionId = SyntheticMessages.subscriptionId,
        )

        val result = fake.insertOutgoing(outgoing)

        assertTrue(result is ProviderAccessResult.Success)
        assertEquals(1, fake.insertedOutgoing.size)
        assertEquals(2, fake.snapshot().size)
        val stored = (result as ProviderAccessResult.Success).value
        assertEquals(ProviderKind.SMS, stored.providerId.kind)
        with(fake.snapshot().first { it.id == stored.providerId }) {
            assertEquals(MessageBox.FAILED, box)
            assertEquals(MessageStatus.FAILED, status)
            assertEquals(Int.MIN_VALUE, rawErrorCode)
        }
    }

    @Test
    fun fakeSmsProvider_retainsSentBoxWhenDeliveryReportFails() = runTest {
        val fake = FakeSmsProviderDataSource()
        val outgoing = OutgoingSmsRecord(
            recipient = SyntheticPeople.MILO.address,
            body = SyntheticMessages.SECOND_BODY,
            timestampMillis = SyntheticMessages.FIXED_TIMESTAMP_MILLIS,
            subscriptionId = SyntheticMessages.subscriptionId,
        )
        val stored = (fake.insertOutgoing(outgoing) as ProviderAccessResult.Success).value
        assertTrue(fake.armOutgoing(stored.providerId) is ProviderAccessResult.Success)

        val result = fake.updateStatus(stored.providerId, SmsProviderStatus.DELIVERY_FAILED)

        assertTrue(result is ProviderAccessResult.Success)
        assertEquals(SmsProviderStatus.DELIVERY_FAILED, fake.updatedStatuses[stored.providerId])
        with(fake.snapshot().single()) {
            assertEquals(MessageBox.SENT, box)
            assertEquals(MessageStatus.FAILED, status)
            assertEquals(64, rawStatus)
        }
    }

    @Test
    fun fakeTransport_capturesTypedSmsRequest() = runTest {
        val fake = FakeMessageTransport()
        val request = SmsSendRequest(
            operationId = SyntheticMessages.pendingOperationId,
            recipients = SyntheticMessages.oneToOneRecipients(),
            body = SyntheticMessages.FIRST_BODY,
            subscriptionId = SyntheticMessages.subscriptionId,
        )

        val result = fake.sendSms(request, SmsSubmissionOwnership.TransportOwned)

        assertEquals(listOf(request), fake.smsRequests)
        assertEquals(
            listOf(SmsSubmissionOwnership.TransportOwned),
            fake.smsSubmissionOwnership,
        )
        assertTrue(result is TransportResult.Submitted)
        assertEquals(MessageTransportKind.SMS, result.transport)
    }

    @Test
    fun fakeNotifier_recordsPrivacyConfiguration() {
        val fake = FakeMessageNotifier()
        val message = SyntheticMessages.incomingNotification()
        val config = NotificationConfig()

        val result = fake.notifyIncoming(message, config)

        assertEquals(1, fake.incoming.size)
        assertEquals(message, fake.incoming.single().message)
        assertEquals(config, fake.incoming.single().config)
        assertTrue(result is NotificationPostResult.Posted)
    }

    @Test
    fun fakeNotifier_recordsGenerationBoundCancellation() {
        val fake = FakeMessageNotifier()
        val message = SyntheticMessages.incomingNotification()

        val result = fake.cancelIncomingConversation(
            message.conversationId,
            message.messageId,
        )

        assertEquals(NotificationCancelResult.Cancelled, result)
        assertEquals(
            listOf(
                FakeMessageNotifier.IncomingCancellationCall(
                    message.conversationId,
                    message.messageId,
                ),
            ),
            fake.incomingCancellations,
        )
    }
}
