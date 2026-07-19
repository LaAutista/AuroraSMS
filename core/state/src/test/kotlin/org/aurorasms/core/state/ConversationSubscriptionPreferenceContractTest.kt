// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConversationSubscriptionPreferenceContractTest {
    @Test
    fun participantIdentityIsCanonicalPurposeSeparatedAndRedacted() {
        val first = ConversationSubscriptionParticipantSetKey.fromParticipants(
            listOf(ParticipantAddress("+15550000002"), ParticipantAddress("+15550000001")),
        )
        val reordered = ConversationSubscriptionParticipantSetKey.fromParticipants(
            listOf(
                ParticipantAddress("+15550000001"),
                ParticipantAddress("+15550000002"),
                ParticipantAddress("+15550000001"),
            ),
        )
        val appearance = AppearanceParticipantSetKey.fromParticipants(
            listOf(ParticipantAddress("+15550000001"), ParticipantAddress("+15550000002")),
        )

        assertEquals(first, reordered)
        assertNotEquals(appearance.toPrivateStorageToken(), first.toStorageValue())
        assertFalse(first.toString().contains("1555"))
        assertFalse(first.toString().contains(first.toStorageValue()))
    }

    @Test
    fun preferenceRequiresValidRevisionAndTimestampAndRedactsIdentity() {
        val scope = ConversationSubscriptionScope(
            participantSetKey = ConversationSubscriptionParticipantSetKey.fromParticipants(
                listOf(ParticipantAddress("synthetic@example.invalid")),
            ),
            providerThreadId = ProviderThreadId(5L),
        )
        val preference = ConversationSubscriptionPreference(
            scope = scope,
            subscriptionId = AuroraSubscriptionId(2),
            revision = ConversationSubscriptionRevision(1L),
            updatedTimestampMillis = 10L,
        )

        assertEquals(AuroraSubscriptionId(2), preference.subscriptionId)
        assertEquals("ConversationSubscriptionPreference(REDACTED)", preference.toString())
        assertThrows(IllegalArgumentException::class.java) {
            ConversationSubscriptionRevision(0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            preference.copy(updatedTimestampMillis = -1L)
        }
    }
}
