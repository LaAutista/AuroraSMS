// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AuroraMaterialProfileTest {
    @Test
    fun defaultProfileUsesTheAuroraVisualIdentityAndAccessibilityFloor() {
        val profile = AuroraMaterialProfile.Default
        val scheme = staticColorScheme(profile.palette, systemDark = true)
        val tokens = tokensFor(profile)
        val visualTokens = visualTokensFor(profile, scheme)

        assertEquals(AuroraPalette.AURORA_DARK, profile.palette)
        assertEquals(273, profile.hueDegrees)
        assertEquals(Color(0xFFB965FF), scheme.primary)
        assertEquals(Color(0xFF05040F), scheme.background)
        assertEquals(Color(0xFF31E8F2), scheme.tertiary)
        assertEquals(Color(0xFFB965FF), visualTokens.violet)
        assertEquals(Color(0xFF702EFF), visualTokens.outgoingGradientStart)
        assertEquals(Color(0xFF994CE5), visualTokens.outgoingGradientEnd)
        assertEquals(Color(0xFF683994), visualTokens.incomingOutline)
        assertEquals(Color(0xFFB965FF), visualTokens.avatarRing)
        assertEquals(48.dp, tokens.minimumTouchTarget)
        assertEquals(64.dp, tokens.rowMinimumHeight)
        assertEquals(1f, tokens.motionScale)
        assertEquals(24.dp, tokens.bubbleCornerRadius)
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
    fun everyBubbleGeometryHasOneDistinctProductionRadius() {
        assertEquals(
            listOf(8.dp, 24.dp, 32.dp),
            AuroraBubbleGeometry.entries.map { geometry ->
                tokensFor(AuroraMaterialProfile(bubbleGeometry = geometry)).bubbleCornerRadius
            },
        )
    }

    @Test
    fun customHueChangesStaticPaletteWithoutChangingCanonicalDefaults() {
        val canonical = staticColorScheme(
            palette = AuroraPalette.AURORA_DARK,
            systemDark = true,
        )
        val shifted = staticColorScheme(
            palette = AuroraPalette.AURORA_DARK,
            systemDark = true,
            hueDegrees = 24,
        )

        assertEquals(Color(0xFFB965FF), canonical.primary)
        assertNotEquals(canonical.primary, shifted.primary)
        assertNotEquals(canonical.secondary, shifted.secondary)
        assertNotEquals(canonical.primaryContainer, shifted.primaryContainer)
        assertNotEquals(canonical.secondaryContainer, shifted.secondaryContainer)
    }

    @Test
    fun everyCustomHueKeepsProminentRolePairsAtBodyTextContrast() {
        for (hue in 0..359) {
            listOf(
                staticColorScheme(AuroraPalette.AURORA_DARK, systemDark = true, hueDegrees = hue),
                staticColorScheme(AuroraPalette.LIGHT, systemDark = false, hueDegrees = hue),
            ).forEach { scheme ->
                assertContrastAtLeast(scheme.primary, scheme.onPrimary, 4.5f, hue)
                assertContrastAtLeast(
                    scheme.primaryContainer,
                    scheme.onPrimaryContainer,
                    4.5f,
                    hue,
                )
                assertContrastAtLeast(scheme.secondary, scheme.onSecondary, 4.5f, hue)
                assertContrastAtLeast(
                    scheme.secondaryContainer,
                    scheme.onSecondaryContainer,
                    4.5f,
                    hue,
                )
            }
        }
    }

    @Test
    fun everyOutgoingBubbleEndpointKeepsBodyTextContrast() {
        for (hue in 0..359) {
            listOf(
                AuroraMaterialProfile(
                    palette = AuroraPalette.AURORA_DARK,
                    hueDegrees = hue,
                ) to true,
                AuroraMaterialProfile(
                    palette = AuroraPalette.AMOLED_BLACK,
                    hueDegrees = hue,
                ) to true,
                AuroraMaterialProfile(
                    palette = AuroraPalette.LIGHT,
                    hueDegrees = hue,
                ) to false,
                AuroraMaterialProfile(
                    palette = AuroraPalette.AURORA_DARK,
                    hueDegrees = hue,
                    highContrast = true,
                ) to true,
                AuroraMaterialProfile(
                    palette = AuroraPalette.LIGHT,
                    hueDegrees = hue,
                    highContrast = true,
                ) to false,
            ).forEach { (profile, systemDark) ->
                val scheme = staticColorScheme(
                    palette = profile.palette,
                    systemDark = systemDark,
                    hueDegrees = profile.hueDegrees,
                    highContrast = profile.highContrast,
                )
                val visualTokens = visualTokensFor(profile, scheme)

                assertContrastAtLeast(
                    visualTokens.outgoingGradientStart,
                    visualTokens.onOutgoing,
                    4.5f,
                    hue,
                )
                assertContrastAtLeast(
                    visualTokens.outgoingGradientEnd,
                    visualTokens.onOutgoing,
                    4.5f,
                    hue,
                )
            }
        }
    }

    @Test
    fun highContrastUsesUnambiguousOpaqueForegroundAndBackgroundColors() {
        val dark = staticColorScheme(
            palette = AuroraPalette.AURORA_DARK,
            systemDark = true,
            highContrast = true,
        )
        val light = staticColorScheme(
            palette = AuroraPalette.LIGHT,
            systemDark = false,
            highContrast = true,
        )

        assertEquals(Color.Black, dark.background)
        assertEquals(Color.White, dark.onBackground)
        assertEquals(Color.White, dark.primaryContainer)
        assertEquals(Color.Black, dark.onPrimaryContainer)
        assertEquals(Color.White, dark.secondaryContainer)
        assertEquals(Color.Black, dark.onSecondaryContainer)
        assertEquals(Color.Black, dark.surfaceContainerHigh)
        assertEquals(Color.White, dark.onSurface)
        assertEquals(Color.White, light.background)
        assertEquals(Color.Black, light.onBackground)
        assertEquals(Color.Black, light.primaryContainer)
        assertEquals(Color.White, light.onPrimaryContainer)
        assertEquals(Color.Black, light.secondaryContainer)
        assertEquals(Color.White, light.onSecondaryContainer)
        assertEquals(Color.White, light.surfaceContainerHigh)
        assertEquals(Color.Black, light.onSurface)

        val darkVisuals = visualTokensFor(
            AuroraMaterialProfile(highContrast = true),
            dark,
        )
        val lightVisuals = visualTokensFor(
            AuroraMaterialProfile(
                palette = AuroraPalette.LIGHT,
                highContrast = true,
            ),
            light,
        )
        assertEquals(Color.White, darkVisuals.avatarRing)
        assertEquals(Color.Black, darkVisuals.onOutgoing)
        assertEquals(Color.Black, lightVisuals.avatarRing)
        assertEquals(Color.White, lightVisuals.onOutgoing)
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

private fun assertContrastAtLeast(
    background: Color,
    foreground: Color,
    minimum: Float,
    hue: Int,
) {
    val lighter = max(background.luminance(), foreground.luminance())
    val darker = min(background.luminance(), foreground.luminance())
    val contrast = (lighter + 0.05f) / (darker + 0.05f)
    check(contrast >= minimum) {
        "Hue $hue produced contrast $contrast below $minimum"
    }
}
