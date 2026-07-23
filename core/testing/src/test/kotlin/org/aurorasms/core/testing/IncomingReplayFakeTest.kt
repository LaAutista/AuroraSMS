// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.testing

import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.MessageDeliveryFingerprint
import org.aurorasms.core.telephony.IncomingDeliveryDisposition
import org.aurorasms.core.telephony.IncomingSmsNotificationReplay
import org.aurorasms.core.telephony.IncomingSmsNotificationReplayRequest
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderStoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingReplayFakeTest {
    @Test
    fun `unacknowledged replay recovers one row and completed replay stays quiet`() = runTest {
        val provider = FakeSmsProviderDataSource()
        val record = SyntheticMessages.incomingSmsRecord()

        val inserted = (
            provider.insertIncoming(record) as ProviderAccessResult.Success<ProviderStoredMessage>
        ).value
        val recovered = (
            provider.insertIncoming(record) as ProviderAccessResult.Success<ProviderStoredMessage>
        ).value
        provider.markIncomingHandled(
            record.deliveryFingerprint,
            inserted.providerId,
            inserted.conversationId,
        )
        val completed = (
            provider.insertIncoming(record) as ProviderAccessResult.Success<ProviderStoredMessage>
        ).value

        assertEquals(IncomingDeliveryDisposition.NEWLY_INSERTED, inserted.incomingDisposition)
        assertEquals(IncomingDeliveryDisposition.RECOVERED_UNACKNOWLEDGED, recovered.incomingDisposition)
        assertTrue(recovered.notificationRequired)
        assertEquals(IncomingDeliveryDisposition.COMPLETED_REPLAY, completed.incomingDisposition)
        assertFalse(completed.notificationRequired)
        assertEquals(1, provider.snapshot().size)
    }

    @Test
    fun `different PDU fingerprint remains a distinct delivery`() = runTest {
        val provider = FakeSmsProviderDataSource()
        val first = SyntheticMessages.incomingSmsRecord()
        val second = first.copy(
            deliveryFingerprint = MessageDeliveryFingerprint.fromSha256(
                ByteArray(MessageDeliveryFingerprint.SHA_256_BYTES) { (it + 2).toByte() },
            ),
        )

        provider.insertIncoming(first)
        provider.insertIncoming(second)

        assertEquals(2, provider.snapshot().size)
    }

    @Test
    fun `pending notification replays are bounded oldest first and disappear after acknowledgement`() = runTest {
        val provider = FakeSmsProviderDataSource()
        val first = SyntheticMessages.incomingSmsRecord().copy(
            deliveryFingerprint = fingerprint(11),
            body = "first",
            receivedTimestampMillis = 1_000L,
            sentTimestampMillis = 900L,
        )
        val second = first.copy(
            deliveryFingerprint = fingerprint(12),
            body = "second",
            receivedTimestampMillis = 2_000L,
            sentTimestampMillis = 1_900L,
        )
        val third = first.copy(
            deliveryFingerprint = fingerprint(13),
            body = "third",
            receivedTimestampMillis = 3_000L,
            sentTimestampMillis = 2_900L,
        )
        val firstStored = (provider.insertIncoming(first) as ProviderAccessResult.Success).value
        provider.insertIncoming(second)
        provider.insertIncoming(third)

        val bounded = (
            provider.readPendingIncomingNotifications(IncomingSmsNotificationReplayRequest(2))
                as ProviderAccessResult.Success<List<IncomingSmsNotificationReplay>>
        ).value
        assertEquals(listOf("first", "second"), bounded.map { it.body })
        assertEquals(first.deliveryFingerprint, bounded.first().deliveryFingerprint)
        assertEquals(firstStored.providerId, bounded.first().providerId)
        assertEquals(firstStored.conversationId, bounded.first().conversationId)
        assertEquals(first.sender, bounded.first().sender)
        assertEquals(first.receivedTimestampMillis, bounded.first().receivedTimestampMillis)
        assertEquals(first.sentTimestampMillis, bounded.first().sentTimestampMillis)
        assertEquals(first.subscriptionId, bounded.first().subscriptionId)

        provider.markIncomingHandled(
            first.deliveryFingerprint,
            firstStored.providerId,
            firstStored.conversationId,
        )
        val afterAcknowledgement = (
            provider.readPendingIncomingNotifications(IncomingSmsNotificationReplayRequest(2))
                as ProviderAccessResult.Success<List<IncomingSmsNotificationReplay>>
        ).value
        assertEquals(listOf("second", "third"), afterAcknowledgement.map { it.body })
    }

    @Test
    fun `pending notification replay propagates typed provider failure`() = runTest {
        val provider = FakeSmsProviderDataSource().apply {
            failure = ProviderAccessResult.RoleRequired
        }

        assertTrue(
            provider.readPendingIncomingNotifications(IncomingSmsNotificationReplayRequest(1)) ===
                ProviderAccessResult.RoleRequired,
        )
    }

    private fun fingerprint(seed: Int): MessageDeliveryFingerprint =
        MessageDeliveryFingerprint.fromSha256(
            ByteArray(MessageDeliveryFingerprint.SHA_256_BYTES) { (it + seed).toByte() },
        )
}
