// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AppearanceWallpaperContractTest {
    @Test
    fun staticMediaIdIsExactVersionedLowercaseSha256AndRedacted() {
        val mediaId = mediaId('a')

        assertEquals("sha256-v1:${"a".repeat(64)}", mediaId.toPrivateStorageToken())
        assertEquals(AppearanceWallpaperMediaId.STORAGE_CHARACTERS, mediaId.toPrivateStorageToken().length)
        assertEquals("AppearanceWallpaperMediaId(REDACTED)", mediaId.toString())
        assertNotEquals(mediaId, mediaId('b'))
        assertThrows(IllegalArgumentException::class.java) {
            AppearanceWallpaperMediaId.fromPrivateStorageToken("sha256-v1:${"A".repeat(64)}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppearanceWallpaperMediaId.fromPrivateStorageToken("sha256-v2:${"a".repeat(64)}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppearanceWallpaperMediaId.fromPrivateStorageToken("content://synthetic.invalid/image")
        }
    }

    @Test
    fun mediaKindUsesOneStrictStableCode() {
        assertEquals(
            AppearanceWallpaperMediaKind.STATIC_RASTER_V1,
            AppearanceWallpaperMediaKind.fromStorageCode("static_raster_v1"),
        )
        assertNull(AppearanceWallpaperMediaKind.fromStorageCode("gif"))
        assertNull(AppearanceWallpaperMediaKind.fromStorageCode("STATIC_RASTER_V1"))
    }

    @Test
    fun assignmentTreatmentIsBoundedAndObjectsAreRedacted() {
        val assignment = assignment()
        val mutation = AppearanceWallpaperMutation(
            assignment = assignment,
            mediaIdNowUnreferenced = mediaId('b'),
        )

        assertEquals("AppearanceWallpaperAssignment(REDACTED)", assignment.toString())
        assertEquals("AppearanceWallpaperRevision(REDACTED)", assignment.revision.toString())
        assertEquals("AppearanceWallpaperMutation(REDACTED)", mutation.toString())
        assertThrows(IllegalArgumentException::class.java) { assignment(dimPermill = 349) }
        assertThrows(IllegalArgumentException::class.java) { assignment(dimPermill = 901) }
        assertThrows(IllegalArgumentException::class.java) { assignment(focalXPermill = -1) }
        assertThrows(IllegalArgumentException::class.java) { assignment(focalYPermill = 1_001) }
        assertThrows(IllegalArgumentException::class.java) { AppearanceWallpaperRevision(0L) }
        assertThrows(IllegalArgumentException::class.java) {
            assignment().copy(scope = AppearanceScope.Screen(AppearanceScreenScope.INBOX))
        }
    }

    @Test
    fun referencedMediaBoundHasOneOverflowSentinel() {
        assertEquals(128, MAX_APPEARANCE_WALLPAPER_MEDIA_IDS)
        assertEquals(129, APPEARANCE_WALLPAPER_MEDIA_ENUMERATION_LIMIT)
    }

    private fun assignment(
        dimPermill: Int = 520,
        focalXPermill: Int = 500,
        focalYPermill: Int = 500,
    ): AppearanceWallpaperAssignment = AppearanceWallpaperAssignment(
        scope = AppearanceScope.Screen(AppearanceScreenScope.GLOBAL_THREAD),
        mediaKind = AppearanceWallpaperMediaKind.STATIC_RASTER_V1,
        mediaId = mediaId('a'),
        dimPermill = dimPermill,
        focalXPermill = focalXPermill,
        focalYPermill = focalYPermill,
        revision = AppearanceWallpaperRevision(1L),
    )

    private fun mediaId(character: Char): AppearanceWallpaperMediaId =
        AppearanceWallpaperMediaId.fromPrivateStorageToken("sha256-v1:${character.toString().repeat(64)}")
}
