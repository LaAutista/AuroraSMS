// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.ConversationSubscriptionParticipantSetKey
import org.aurorasms.core.state.ConversationSubscriptionPreference
import org.aurorasms.core.state.ConversationSubscriptionRevision
import org.aurorasms.core.state.ConversationSubscriptionScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ConversationSubscriptionPreferenceEntityTest {
    @Test
    fun roundTripPreservesContentFreePreferenceAndRedactsStrings() {
        val preference = preference()
        val entity = preference.toEntity()

        assertEquals(preference, entity.toDomain(preference.scope))
        assertFalse(entity.toString().contains("synthetic"))
        assertFalse(entity.toString().contains(entity.participantSetKey))
    }

    @Test
    fun malformedOrMismatchedIdentityFailsClosed() {
        val valid = preference()
        val entity = valid.toEntity()
        assertThrows(IllegalArgumentException::class.java) {
            entity.copy(participantSetKey = "sha256-v1:" + "G".repeat(64)).toDomain()
        }
        val otherScope = ConversationSubscriptionScope(
            participantSetKey = ConversationSubscriptionParticipantSetKey.fromParticipants(
                listOf(ParticipantAddress("other@example.invalid")),
            ),
            providerThreadId = ProviderThreadId(8L),
        )
        assertThrows(IllegalArgumentException::class.java) {
            entity.toDomain(otherScope)
        }
    }

    private fun preference(): ConversationSubscriptionPreference =
        ConversationSubscriptionPreference(
            scope = ConversationSubscriptionScope(
                participantSetKey = ConversationSubscriptionParticipantSetKey.fromParticipants(
                    listOf(ParticipantAddress("synthetic@example.invalid")),
                ),
                providerThreadId = ProviderThreadId(7L),
            ),
            subscriptionId = AuroraSubscriptionId(3),
            revision = ConversationSubscriptionRevision(1L),
            updatedTimestampMillis = 100L,
        )
}
