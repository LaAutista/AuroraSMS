// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopedWallpaperDraftStateTest {
    @Test
    fun restoredNumericDraftIsAcceptedOnlyForItsPrivateTarget() {
        val restored = draft(
            target = "synthetic-target-a",
            revision = 7L,
            dim = 650,
            focalX = 250,
            focalY = 750,
        )
        val current = draft(
            target = "synthetic-target-b",
            revision = 9L,
            dim = 520,
            focalX = 500,
            focalY = 500,
        )

        assertEquals(current, restored.forTargetOr(current))
        assertTrue(restored.matchesTarget("synthetic-target-a"))
        assertFalse(restored.matchesTarget("synthetic-target-b"))
    }

    @Test
    fun dirtyComparisonIncludesOnlyTheThreeBoundedNumericControls() {
        val baseline = draft(target = "target", revision = 3L)

        assertFalse(baseline.hasNumericChangesFrom(baseline.copy(expectedRevision = 4L)))
        assertTrue(baseline.hasNumericChangesFrom(baseline.copy(dimPermill = 521)))
        assertTrue(baseline.hasNumericChangesFrom(baseline.copy(focalXPermill = 501)))
        assertTrue(baseline.hasNumericChangesFrom(baseline.copy(focalYPermill = 499)))
    }

    @Test
    fun stateRejectsOutOfRangeValuesAndRedactsTarget() {
        assertThrows(IllegalArgumentException::class.java) {
            draft(target = "target", dim = 349)
        }
        assertThrows(IllegalArgumentException::class.java) {
            draft(target = "target", dim = 901)
        }
        assertThrows(IllegalArgumentException::class.java) {
            draft(target = "target", focalX = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            draft(target = "target", focalY = 1_001)
        }

        val privateTarget = "private-target-must-not-leak"
        val state = draft(target = privateTarget)
        assertFalse(state.toString().contains(privateTarget))
        assertTrue(state.toString().contains("target=REDACTED"))
    }

    private fun draft(
        target: String,
        revision: Long? = 3L,
        dim: Int = 520,
        focalX: Int = 500,
        focalY: Int = 500,
    ): ScopedWallpaperDraftState = ScopedWallpaperDraftState(
        privateRestorationKey = target,
        expectedRevision = revision,
        dimPermill = dim,
        focalXPermill = focalX,
        focalYPermill = focalY,
    )
}
