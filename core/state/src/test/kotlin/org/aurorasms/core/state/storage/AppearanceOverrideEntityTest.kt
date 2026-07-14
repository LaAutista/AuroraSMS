// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.AppearanceParticipantSetKey
import org.aurorasms.core.state.AppearanceScope
import org.aurorasms.core.state.AppearanceScreenScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppearanceOverrideEntityTest {
    @Test
    fun screenAndConversationEntitiesRoundTripStableScopeValues() {
        val screen = AppearanceScreenOverrideEntity(
            screenCode = AppearanceScreenScope.INBOX.storageCode,
            profileId = 4L,
            revision = 2L,
        ).toDomain()
        val key = AppearanceParticipantSetKey.fromParticipants(
            listOf(ParticipantAddress("synthetic@example.invalid")),
        )
        val requestedConversation = AppearanceScope.Conversation(key, ProviderThreadId(9L))
        val conversation = AppearanceConversationOverrideEntity(
            participantSetKey = key.toStorageValue(),
            providerThreadId = 7L,
            profileId = 5L,
            revision = 3L,
        ).toDomain(requestedConversation)

        assertEquals(AppearanceScope.Screen(AppearanceScreenScope.INBOX), screen.scope)
        assertEquals(4L, screen.profileId.value)
        assertEquals(2L, screen.revision.value)
        assertEquals(requestedConversation, conversation.scope)
        assertEquals(5L, conversation.profileId.value)
        assertEquals(3L, conversation.revision.value)
    }

    @Test
    fun malformedStorageFieldsFailClosedAndEntityStringsAreRedacted() {
        assertThrows(IllegalArgumentException::class.java) {
            AppearanceScreenOverrideEntity("future", 1L, 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppearanceConversationOverrideEntity("sha256-v1:${"A".repeat(64)}", 1L, 1L, 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppearanceScreenOverrideEntity("inbox", 0L, 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppearanceScreenOverrideEntity("inbox", 1L, 0L)
        }
        assertEquals(
            "AppearanceScreenOverrideEntity(REDACTED)",
            AppearanceScreenOverrideEntity("inbox", 1L, 1L).toString(),
        )
        assertEquals(
            "AppearanceOverrideSequenceEntity(REDACTED)",
            AppearanceOverrideSequenceEntity(1, 0L).toString(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            AppearanceOverrideSequenceEntity(2, 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppearanceOverrideSequenceEntity(1, -1L)
        }
    }
}
