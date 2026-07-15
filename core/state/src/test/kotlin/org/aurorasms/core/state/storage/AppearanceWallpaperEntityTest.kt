// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.AppearanceParticipantSetKey
import org.aurorasms.core.state.AppearanceScope
import org.aurorasms.core.state.AppearanceScreenScope
import org.aurorasms.core.state.AppearanceWallpaperMediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppearanceWallpaperEntityTest {
    @Test
    fun screenAndConversationRowsRoundTripWithoutExposingStorageIdentity() {
        val screenEntity = screenEntity()
        val screen = screenEntity.toDomain()
        val key = AppearanceParticipantSetKey.fromParticipants(
            listOf(ParticipantAddress("synthetic@example.invalid")),
        )
        val requested = AppearanceScope.Conversation(key, ProviderThreadId(99L))
        val conversationEntity = conversationEntity(
            participantSetKey = key.toStorageValue(),
            providerThreadId = 41L,
        )
        val conversation = conversationEntity.toDomain(requested)

        assertEquals(AppearanceScope.Screen(AppearanceScreenScope.GLOBAL_THREAD), screen.scope)
        assertEquals(requested, conversation.scope)
        assertEquals(mediaId(), screen.mediaId)
        assertEquals(mediaId(), conversation.mediaId)
        assertEquals(520, conversation.dimPermill)
        assertEquals(125, conversation.focalXPermill)
        assertEquals(875, conversation.focalYPermill)
        assertEquals("AppearanceScreenWallpaperEntity(REDACTED)", screenEntity.toString())
        assertEquals("AppearanceConversationWallpaperEntity(REDACTED)", conversationEntity.toString())
        assertEquals(
            "AppearanceWallpaperMediaRecord(REDACTED)",
            AppearanceWallpaperMediaRecord("static_raster_v1", mediaId().toStorageValue()).toString(),
        )
    }

    @Test
    fun malformedWallpaperRowsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { screenEntity(screenCode = "future") }
        assertThrows(IllegalArgumentException::class.java) { screenEntity(screenCode = "inbox") }
        assertThrows(IllegalArgumentException::class.java) { screenEntity(mediaKindCode = "gif") }
        assertThrows(IllegalArgumentException::class.java) {
            screenEntity(mediaId = "sha256-v1:${"A".repeat(64)}")
        }
        assertThrows(IllegalArgumentException::class.java) { screenEntity(dimPermill = 349) }
        assertThrows(IllegalArgumentException::class.java) { screenEntity(focalXPermill = -1) }
        assertThrows(IllegalArgumentException::class.java) { screenEntity(focalYPermill = 1_001) }
        assertThrows(IllegalArgumentException::class.java) { screenEntity(revision = 0L) }
        assertThrows(IllegalArgumentException::class.java) {
            conversationEntity(participantSetKey = "not-a-private-fingerprint")
        }
        assertThrows(IllegalArgumentException::class.java) {
            conversationEntity(providerThreadId = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppearanceWallpaperMediaRecord("future", mediaId().toStorageValue()).toDomain()
        }
    }

    private fun screenEntity(
        screenCode: String = "global_thread",
        mediaKindCode: String = "static_raster_v1",
        mediaId: String = mediaId().toStorageValue(),
        dimPermill: Int = 520,
        focalXPermill: Int = 125,
        focalYPermill: Int = 875,
        revision: Long = 1L,
    ): AppearanceScreenWallpaperEntity = AppearanceScreenWallpaperEntity(
        screenCode = screenCode,
        mediaKindCode = mediaKindCode,
        mediaId = mediaId,
        dimPermill = dimPermill,
        focalXPermill = focalXPermill,
        focalYPermill = focalYPermill,
        revision = revision,
    )

    private fun conversationEntity(
        participantSetKey: String = AppearanceParticipantSetKey.fromParticipants(
            listOf(ParticipantAddress("synthetic@example.invalid")),
        ).toStorageValue(),
        providerThreadId: Long = 41L,
    ): AppearanceConversationWallpaperEntity = AppearanceConversationWallpaperEntity(
        participantSetKey = participantSetKey,
        providerThreadId = providerThreadId,
        mediaKindCode = "static_raster_v1",
        mediaId = mediaId().toStorageValue(),
        dimPermill = 520,
        focalXPermill = 125,
        focalYPermill = 875,
        revision = 2L,
    )

    private fun mediaId(): AppearanceWallpaperMediaId =
        AppearanceWallpaperMediaId.fromPrivateStorageToken("sha256-v1:${"a".repeat(64)}")
}
