// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AuroraMaterialProfileTest {
    @Test
    fun defaultProfilePreservesThePhaseThreeAppearanceAndAccessibilityFloor() {
        val profile = AuroraMaterialProfile.Default
        val scheme = staticColorScheme(profile.palette, systemDark = true)
        val tokens = tokensFor(profile)

        assertEquals(AuroraPalette.AURORA_DARK, profile.palette)
        assertEquals(Color(0xFF78D6C6), scheme.primary)
        assertEquals(Color(0xFF101419), scheme.background)
        assertEquals(48.dp, tokens.minimumTouchTarget)
        assertEquals(64.dp, tokens.rowMinimumHeight)
        assertEquals(1f, tokens.motionScale)
    }

    @Test
    fun everyDensityKeepsRowsAboveTheMinimumTouchTarget() {
        AuroraRowDensity.entries.forEach { density ->
            val tokens = tokensFor(AuroraMaterialProfile(rowDensity = density))
            check(tokens.rowMinimumHeight >= tokens.minimumTouchTarget)
        }
    }

    @Test
    fun reducedMotionDisablesDecorativeMotionAtTheTokenBoundary() {
        val tokens = tokensFor(AuroraMaterialProfile(reducedMotion = true))
        assertEquals(0f, tokens.motionScale)
    }

    @Test
    fun hostileProfileRangesAreRejectedBeforeRendering() {
        assertThrows(IllegalArgumentException::class.java) {
            AuroraMaterialProfile(hueDegrees = 360)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuroraMaterialProfile(wallpaperDim = 0.1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuroraMaterialProfile(schemaVersion = 2)
        }
    }

    @Test
    fun everyRequiredAvatarMaskHasAConcreteShape() {
        AuroraAvatarMask.entries.forEach { mask -> assertNotNull(mask.toShape()) }
    }
}
