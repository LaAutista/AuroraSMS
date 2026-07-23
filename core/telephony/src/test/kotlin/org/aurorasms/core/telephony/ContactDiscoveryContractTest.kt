// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.ParticipantAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ContactDiscoveryContractTest {
    @Test
    fun `unavailable discovery rejects invalid requests without querying`() = runTest {
        assertEquals(
            ContactDiscoveryResult.InvalidRequest,
            UnavailableContactDiscovery.discover("   ", DEFAULT_CONTACT_DISCOVERY_RESULT_LIMIT),
        )
        assertEquals(
            ContactDiscoveryResult.InvalidRequest,
            UnavailableContactDiscovery.discover(
                "a".repeat(MAXIMUM_CONTACT_DISCOVERY_QUERY_CHARACTERS + 1),
                DEFAULT_CONTACT_DISCOVERY_RESULT_LIMIT,
            ),
        )
        assertEquals(
            ContactDiscoveryResult.InvalidRequest,
            UnavailableContactDiscovery.discover("Ada", MAXIMUM_CONTACT_DISCOVERY_RESULTS + 1),
        )
        assertEquals(
            ContactDiscoveryResult.Unavailable,
            UnavailableContactDiscovery.discover("Ada", DEFAULT_CONTACT_DISCOVERY_RESULT_LIMIT),
        )
    }

    @Test
    fun `discovered contact metadata is bounded and redacted`() {
        val contact = DiscoveredContact(
            address = ParticipantAddress("+15550102020"),
            displayName = "Private name",
            photoUri = "content://private/photo",
        )
        val available = ContactDiscoveryResult.Available(listOf(contact), truncated = false)

        assertEquals("Private name", contact.displayNameOrAddress)
        assertEquals("DiscoveredContact(REDACTED)", contact.toString())
        assertEquals(
            "ContactDiscoveryResult.Available(contactCount=1, truncated=false)",
            available.toString(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            DiscoveredContact(contact.address, " untrimmed", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiscoveredContact(
                contact.address,
                "a".repeat(MAXIMUM_CONTACT_DISPLAY_NAME_CHARACTERS + 1),
                null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiscoveredContact(
                contact.address,
                null,
                "p".repeat(MAXIMUM_CONTACT_PHOTO_URI_CHARACTERS + 1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiscoveredContact(ParticipantAddress("+"), null, null)
        }
    }

    @Test
    fun `available result rejects duplicate addresses and oversized output`() {
        val contact = DiscoveredContact(ParticipantAddress("+15550102020"), null, null)

        assertThrows(IllegalArgumentException::class.java) {
            ContactDiscoveryResult.Available(listOf(contact, contact), truncated = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContactDiscoveryResult.Available(
                listOf(
                    contact,
                    DiscoveredContact(ParticipantAddress("+1 (555) 010-2020"), null, null),
                ),
                truncated = false,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContactDiscoveryResult.Available(
                contacts = (1..MAXIMUM_CONTACT_DISCOVERY_RESULTS + 1).map { index ->
                    DiscoveredContact(ParticipantAddress("+1555${index.toString().padStart(7, '0')}"), null, null)
                },
                truncated = true,
            )
        }
    }
}
