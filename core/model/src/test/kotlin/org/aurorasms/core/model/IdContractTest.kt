// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IdContractTest {
    @Test
    fun `numeric IDs remain distinct across namespaces`() {
        val sms = MessageId(ProviderKind.SMS, 41L)
        val mms = MessageId(ProviderKind.MMS, 41L)
        val draft = MessageId(ProviderKind.DRAFT, 41L)
        val schedule = MessageId(ProviderKind.SCHEDULED, 41L)
        val operation = MessageId(ProviderKind.PENDING_OPERATION, 41L)

        assertEquals(5, setOf(sms, mms, draft, schedule, operation).size)
        assertNotEquals(sms, mms)
    }

    @Test
    fun `provider key accepts only telephony provider namespaces`() {
        val sms = ProviderMessageId(ProviderKind.SMS, 9L)
        val mms = ProviderMessageId(ProviderKind.MMS, 9L)

        assertEquals(MessageId(ProviderKind.SMS, 9L), sms.asMessageId())
        assertNotEquals(sms, mms)
        assertThrows(IllegalArgumentException::class.java) {
            ProviderMessageId(ProviderKind.DRAFT, 9L)
        }
    }

    @Test
    fun `invalid scalar IDs and addresses are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConversationId(0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuroraSubscriptionId(-1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ParticipantAddress("   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ParticipantAddress(" +15550102020 ")
        }
    }

    @Test
    fun `submitted result requires a real unit`() {
        assertThrows(IllegalArgumentException::class.java) {
            TransportResult.Submitted(
                operationId = MessageId(ProviderKind.PENDING_OPERATION, 1L),
                transport = MessageTransportKind.SMS,
                unitCount = 0,
            )
        }
    }

    @Test
    fun `delivery fingerprint is immutable and redacted from string output`() {
        val digest = ByteArray(MessageDeliveryFingerprint.SHA_256_BYTES) { it.toByte() }
        val fingerprint = MessageDeliveryFingerprint.fromSha256(digest)
        digest.fill(0)

        assertEquals(64, fingerprint.toStorageToken().length)
        assertEquals(
            fingerprint,
            MessageDeliveryFingerprint.fromStorageToken(fingerprint.toStorageToken()),
        )
        assertEquals("MessageDeliveryFingerprint(REDACTED)", fingerprint.toString())
        assertThrows(IllegalArgumentException::class.java) {
            MessageDeliveryFingerprint.fromStorageToken("not-a-fingerprint")
        }
    }
}
