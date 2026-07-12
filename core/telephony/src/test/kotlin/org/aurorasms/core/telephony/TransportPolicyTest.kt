// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.telephony.internal.TransportPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportPolicyTest {
    @Test
    fun `role loss wins before any platform transport attempt`() {
        assertEquals(
            TransportResult.FailureReason.ROLE_NOT_HELD,
            TransportPolicy.smsRejection(
                roleHeld = false,
                featureAvailable = true,
                permissionGranted = true,
                subscriptionActive = true,
                singleRecipient = true,
                contentPresent = true,
                emergencyRecipient = false,
            ),
        )
    }

    @Test
    fun `stale subscription is rejected rather than silently replaced`() {
        assertEquals(
            TransportResult.FailureReason.SUBSCRIPTION_UNAVAILABLE,
            TransportPolicy.mmsRejection(
                roleHeld = true,
                featureAvailable = true,
                permissionGranted = true,
                subscriptionActive = false,
            ),
        )
    }

    @Test
    fun `valid SMS prerequisites have no rejection`() {
        assertNull(
            TransportPolicy.smsRejection(
                roleHeld = true,
                featureAvailable = true,
                permissionGranted = true,
                subscriptionActive = true,
                singleRecipient = true,
                contentPresent = true,
                emergencyRecipient = false,
            ),
        )
    }

    @Test
    fun `group cannot cross the single SMS destination boundary`() {
        assertEquals(
            TransportResult.FailureReason.INVALID_RECIPIENT,
            TransportPolicy.smsRejection(
                roleHeld = true,
                featureAvailable = true,
                permissionGranted = true,
                subscriptionActive = true,
                singleRecipient = false,
                contentPresent = true,
                emergencyRecipient = false,
            ),
        )
    }

    @Test
    fun `encoded PDU takes defensive copies`() {
        val source = byteArrayOf(1, 2, 3, 4)
        val valid = EncodedMmsPdu.create(source) as EncodedMmsPdu.CreationResult.Valid
        source[0] = 99
        val firstCopy = valid.pdu.copyBytes()
        firstCopy[1] = 88

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), valid.pdu.copyBytes())
        assertTrue(EncodedMmsPdu.create(byteArrayOf()) is EncodedMmsPdu.CreationResult.Rejected)
    }
}
