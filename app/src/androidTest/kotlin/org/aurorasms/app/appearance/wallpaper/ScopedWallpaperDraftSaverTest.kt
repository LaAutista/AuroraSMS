// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import android.os.Bundle
import androidx.compose.runtime.saveable.SaverScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScopedWallpaperDraftSaverTest {
    @Test
    fun saverRoundTripContainsOnlyTargetRevisionAndBoundedNumericControls() {
        val original = ScopedWallpaperDraftState(
            privateRestorationKey = "synthetic-private-target",
            expectedRevision = 17L,
            dimPermill = 650,
            focalXPermill = 250,
            focalYPermill = 750,
        )

        val saved = with(ScopedWallpaperDraftStateSaver) {
            SaverScope { value -> value is Bundle }.save(original)
        } as Bundle

        assertEquals(
            setOf("schema", "target", "revision", "dim", "focal_x", "focal_y"),
            saved.keySet(),
        )
        saved.keySet().forEach { key ->
            assertTrue(saved.get(key) is String || saved.get(key) is Int || saved.get(key) is Long)
        }
        assertFalse(saved.toString().contains("content://"))
        assertFalse(saved.toString().contains("sha256-v1:"))
        assertEquals(original, ScopedWallpaperDraftStateSaver.restore(saved))
    }

    @Test
    fun absentRevisionIsNotMaterializedIntoSavedState() {
        val original = ScopedWallpaperDraftState(
            privateRestorationKey = "synthetic-new-target",
            expectedRevision = null,
            dimPermill = 500,
            focalXPermill = 500,
            focalYPermill = 500,
        )

        val saved = with(ScopedWallpaperDraftStateSaver) {
            SaverScope { value -> value is Bundle }.save(original)
        } as Bundle

        assertFalse(saved.containsKey("revision"))
        assertEquals(original, ScopedWallpaperDraftStateSaver.restore(saved))
    }
}
