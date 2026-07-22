// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.model

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticipantAddressEquivalenceKeyTest {
    @Test
    fun `phone punctuation is ignored while a leading plus remains significant`() {
        assertEquals(key("+1 (555) 010-2020"), key("+15550102020"))
        assertNotEquals(key("+15550102020"), key("15550102020"))
    }

    @Test
    fun `email domain is case insensitive but local part remains exact`() {
        assertEquals(key("Local@Example.COM"), key("Local@example.com"))
        assertNotEquals(key("Local@example.com"), key("local@example.com"))
    }

    @Test
    fun `opaque addresses remain exact and cannot collide with a tagged domain`() {
        assertEquals(key("Carrier-Code"), key("Carrier-Code"))
        assertNotEquals(key("Carrier-Code"), key("carrier-code"))
        assertNotEquals(key("phone:+15550102020"), key("+15550102020"))
        assertNotEquals(key("email:Local@example.com"), key("Local@example.com"))
    }

    @Test
    fun `phone-like input without digits is invalid`() {
        assertNull(ParticipantAddressEquivalenceKey.from(ParticipantAddress("+")))
        assertNull(ParticipantAddressEquivalenceKey.from(ParticipantAddress("( )-")))
    }

    @Test
    fun `digest framing is deterministic and domain sensitive`() {
        val first = digest(key("+1 (555) 010-2020"))
        val equivalent = digest(key("+15550102020"))
        val opaque = digest(key("phone:+15550102020"))

        assertArrayEquals(first, equivalent)
        assertTrue(!first.contentEquals(opaque))
    }

    @Test
    fun `diagnostic rendering never contains the address`() {
        val rendered = key("secret@example.invalid").toString()

        assertEquals("ParticipantAddressEquivalenceKey(REDACTED)", rendered)
        assertTrue(!rendered.contains("secret"))
    }

    private fun key(value: String): ParticipantAddressEquivalenceKey =
        requireNotNull(ParticipantAddressEquivalenceKey.from(ParticipantAddress(value)))

    private fun digest(key: ParticipantAddressEquivalenceKey): ByteArray =
        MessageDigest.getInstance("SHA-256").also(key::updateDigest).digest()
}
