// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.telephony.internal.IncomingProviderRecoveryPolicy
import org.aurorasms.core.telephony.receiver.SmsDeliveryFingerprintFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingReplayPolicyTest {
    @Test
    fun `fingerprint is stable for replay and scoped by ordered PDU and subscription`() {
        val firstPdu = byteArrayOf(1, 2, 3)
        val secondPdu = byteArrayOf(4, 5, 6)
        val subscription = AuroraSubscriptionId(4)

        val first = SmsDeliveryFingerprintFactory.create(
            listOf(firstPdu, secondPdu),
            "3gpp",
            subscription,
        )
        val replay = SmsDeliveryFingerprintFactory.create(
            listOf(firstPdu.copyOf(), secondPdu.copyOf()),
            "3gpp",
            subscription,
        )
        val reordered = SmsDeliveryFingerprintFactory.create(
            listOf(secondPdu, firstPdu),
            "3gpp",
            subscription,
        )
        val otherSubscription = SmsDeliveryFingerprintFactory.create(
            listOf(firstPdu, secondPdu),
            "3gpp",
            AuroraSubscriptionId(5),
        )

        assertEquals(first, replay)
        assertNotEquals(first, reordered)
        assertNotEquals(first, otherSubscription)
        assertTrue(first.toString().contains("REDACTED"))
    }

    @Test
    fun `recovery selects only one unclaimed exact row`() {
        val first = stored(1L)
        val second = stored(2L)

        assertTrue(
            IncomingProviderRecoveryPolicy.select(listOf(first, second), emptySet()) is
                IncomingProviderRecoveryPolicy.Result.Ambiguous,
        )
        val recovered = IncomingProviderRecoveryPolicy.select(
            candidates = listOf(first, second),
            claimedProviderIds = setOf(first.providerId.value),
        )
        assertEquals(second, (recovered as IncomingProviderRecoveryPolicy.Result.Found).message)
        assertTrue(
            IncomingProviderRecoveryPolicy.select(listOf(first), setOf(first.providerId.value)) is
                IncomingProviderRecoveryPolicy.Result.None,
        )
    }

    @Test
    fun `fingerprint rejects malformed format and oversized PDU input`() {
        assertNull(
            SmsDeliveryFingerprintFactory.create(
                pdus = listOf(byteArrayOf(1)),
                format = "3gpp\u2603",
                subscriptionId = null,
            ),
        )
        assertNull(
            SmsDeliveryFingerprintFactory.create(
                pdus = listOf(ByteArray(256 * 1_024 + 1)),
                format = "3gpp",
                subscriptionId = null,
            ),
        )
    }

    private fun stored(value: Long): ProviderStoredMessage = ProviderStoredMessage(
        providerId = ProviderMessageId(ProviderKind.SMS, value),
        conversationId = ConversationId(100L + value),
    )
}
