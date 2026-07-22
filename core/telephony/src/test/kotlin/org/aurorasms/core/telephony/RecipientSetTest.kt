// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ParticipantAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipientSetTest {
    @Test
    fun `one ordinary recipient can select SMS`() {
        val recipients = valid("+15550102020")

        assertEquals(MessageTransportKind.SMS, recipients.requiredTransport())
        assertEquals(ParticipantAddress("+15550102020"), recipients.singleSmsRecipientOrNull())
    }

    @Test
    fun `two recipients always form one MMS operation`() {
        val recipients = valid("+15550102020", "+15550102021")

        assertTrue(recipients.isGroup)
        assertEquals(MessageTransportKind.MMS, recipients.requiredTransport())
        assertNull(recipients.singleSmsRecipientOrNull())
    }

    @Test
    fun `three recipients remain MMS without an SMS fanout view`() {
        val recipients = valid("+15550102020", "+15550102021", "+15550102022")

        assertEquals(3, recipients.size)
        assertEquals(MessageTransportKind.MMS, recipients.requiredTransport())
        assertNull(recipients.singleSmsRecipientOrNull())
    }

    @Test
    fun `equivalent formatting is deduplicated conservatively`() {
        val recipients = valid("+1 (555) 010-2020", "+15550102020")

        assertEquals(1, recipients.size)
        assertEquals(MessageTransportKind.SMS, recipients.requiredTransport())
    }

    @Test
    fun `email domains deduplicate case insensitively while local parts remain exact`() {
        val recipients = valid(
            "Local@Example.COM",
            "Local@example.com",
            "local@example.com",
        )

        assertEquals(2, recipients.size)
        assertEquals(
            listOf(ParticipantAddress("Local@Example.COM"), ParticipantAddress("local@example.com")),
            recipients.addresses,
        )
    }

    @Test
    fun `domain tags prevent crafted opaque addresses from colliding`() {
        val recipients = valid("+15550102020", "phone:+15550102020")

        assertEquals(2, recipients.size)
    }

    @Test
    fun `attachment forces MMS even for one recipient`() {
        val recipients = valid("synthetic@example.invalid")

        assertEquals(MessageTransportKind.MMS, recipients.requiredTransport(hasAttachments = true))
    }

    @Test
    fun `empty and oversized inputs are rejected without throwing`() {
        assertTrue(RecipientSet.parse(emptyList()) is RecipientSet.CreationResult.Rejected)
        assertTrue(RecipientSet.parse(listOf("+")) is RecipientSet.CreationResult.Rejected)
        assertTrue(
            RecipientSet.parse(List(RecipientSet.MAX_RECIPIENTS + 1) { "+1555${it.toString().padStart(7, '0')}" })
                is RecipientSet.CreationResult.Rejected,
        )
    }

    private fun valid(vararg addresses: String): RecipientSet =
        (RecipientSet.parse(addresses.asList()) as RecipientSet.CreationResult.Valid).recipients
}
