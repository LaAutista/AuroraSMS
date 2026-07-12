// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.testing

import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.MessageDeliveryFingerprint
import org.aurorasms.core.telephony.IncomingDeliveryDisposition
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
}
