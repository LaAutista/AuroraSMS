// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Aurora-specific visual roles that sit beside, rather than replace, Material color roles.
 *
 * Feature UI should consume these semantic roles instead of embedding wallpaper-derived colors.
 * This keeps the code-rendered identity usable with custom hues, light palettes, and high contrast.
 */
@Immutable
data class AuroraVisualTokens(
    val nearBlack: Color,
    val deepNight: Color,
    val elevatedSurface: Color,
    val menuSurface: Color,
    val violet: Color,
    val magenta: Color,
    val cyan: Color,
    val incomingFill: Color,
    val incomingOutline: Color,
    val outgoingGradientStart: Color,
    val outgoingGradientEnd: Color,
    val lilacSecondary: Color,
    val avatarRing: Color,
    val wallpaperScrim: Color,
    val onIncoming: Color,
    val onOutgoing: Color,
)

val LocalAuroraVisualTokens = staticCompositionLocalOf {
    canonicalDarkVisualTokens(wallpaperDim = DEFAULT_WALLPAPER_DIM)
}

internal fun visualTokensFor(
    profile: AuroraMaterialProfile,
    colorScheme: ColorScheme,
): AuroraVisualTokens {
    val isDark = colorScheme.background.luminance() < DARK_PALETTE_LUMINANCE_BOUNDARY
    if (profile.highContrast) {
        return highContrastVisualTokens(
            dark = isDark,
            wallpaperDim = profile.wallpaperDim,
        )
    }

    val usesCanonicalDarkIdentity = isDark &&
        profile.hueDegrees == CANONICAL_AURORA_HUE_DEGREES &&
        profile.palette != AuroraPalette.SYSTEM_DYNAMIC
    if (usesCanonicalDarkIdentity) {
        return canonicalDarkVisualTokens(
            wallpaperDim = profile.wallpaperDim,
            amoled = profile.palette == AuroraPalette.AMOLED_BLACK,
        )
    }

    return AuroraVisualTokens(
        nearBlack = if (isDark) colorScheme.background else colorScheme.surfaceContainerLowest,
        deepNight = if (isDark) colorScheme.surface else colorScheme.surfaceContainerLow,
        elevatedSurface = colorScheme.surfaceContainerHigh,
        menuSurface = colorScheme.surfaceContainerHighest,
        violet = colorScheme.primary,
        magenta = colorScheme.secondary,
        cyan = colorScheme.tertiary,
        incomingFill = colorScheme.surfaceContainer,
        incomingOutline = colorScheme.outline,
        outgoingGradientStart = colorScheme.primary,
        outgoingGradientEnd = colorScheme.primary,
        lilacSecondary = colorScheme.secondary,
        avatarRing = colorScheme.primary,
        wallpaperScrim = (if (isDark) Color.Black else Color.White).copy(
            alpha = profile.wallpaperDim,
        ),
        onIncoming = colorScheme.onSurface,
        onOutgoing = colorScheme.onPrimary,
    )
}

private fun canonicalDarkVisualTokens(
    wallpaperDim: Float,
    amoled: Boolean = false,
): AuroraVisualTokens = AuroraVisualTokens(
    nearBlack = if (amoled) Color.Black else AURORA_DARK_BACKGROUND,
    deepNight = if (amoled) Color.Black else AURORA_DARK_SURFACE,
    elevatedSurface = if (amoled) AMOLED_SURFACE_CONTAINER_HIGH else AURORA_SURFACE_CONTAINER_HIGH,
    menuSurface = if (amoled) AMOLED_SURFACE_CONTAINER_HIGHEST else AURORA_MENU_SURFACE,
    violet = AURORA_PRIMARY,
    magenta = AURORA_MAGENTA,
    cyan = AURORA_CYAN,
    incomingFill = AURORA_INCOMING_FILL,
    incomingOutline = AURORA_INCOMING_OUTLINE,
    outgoingGradientStart = AURORA_OUTGOING_START,
    outgoingGradientEnd = AURORA_OUTGOING_END,
    lilacSecondary = AURORA_SECONDARY,
    avatarRing = AURORA_AVATAR_RING,
    wallpaperScrim = Color.Black.copy(alpha = wallpaperDim),
    onIncoming = AURORA_ON_SURFACE,
    onOutgoing = Color.White,
)

private fun highContrastVisualTokens(
    dark: Boolean,
    wallpaperDim: Float,
): AuroraVisualTokens {
    val foreground = if (dark) Color.White else Color.Black
    val background = if (dark) Color.Black else Color.White
    return AuroraVisualTokens(
        nearBlack = background,
        deepNight = background,
        elevatedSurface = background,
        menuSurface = background,
        violet = foreground,
        magenta = foreground,
        cyan = foreground,
        incomingFill = background,
        incomingOutline = foreground,
        outgoingGradientStart = foreground,
        outgoingGradientEnd = foreground,
        lilacSecondary = foreground,
        avatarRing = foreground,
        wallpaperScrim = background.copy(alpha = maxOf(wallpaperDim, HIGH_CONTRAST_SCRIM_ALPHA)),
        onIncoming = foreground,
        onOutgoing = background,
    )
}

internal val AURORA_PRIMARY = Color(0xFFB965FF)
internal val AURORA_SECONDARY = Color(0xFFCDA5FC)
internal val AURORA_MAGENTA = Color(0xFFE779FF)
internal val AURORA_CYAN = Color(0xFF31E8F2)
internal val AURORA_DARK_BACKGROUND = Color(0xFF05040F)
internal val AURORA_DARK_SURFACE = Color(0xFF0F0F1A)
internal val AURORA_ON_SURFACE = Color(0xFFF9F8FB)
internal val AURORA_SURFACE_VARIANT = Color(0xFF1D1C2E)
internal val AURORA_SURFACE_CONTAINER_HIGH = Color(0xFF191725)
internal val AURORA_MENU_SURFACE = Color(0xFF283141)
internal val AURORA_INCOMING_FILL = Color(0xF2050410)
internal val AURORA_INCOMING_OUTLINE = Color(0xFF683994)
internal val AURORA_OUTGOING_START = Color(0xFF702EFF)
internal val AURORA_OUTGOING_END = Color(0xFF994CE5)
internal val AURORA_AVATAR_RING = Color(0xFFB965FF)
internal val AMOLED_SURFACE_VARIANT = Color(0xFF111018)
internal val AMOLED_SURFACE_CONTAINER_HIGH = Color(0xFF15121C)
internal val AMOLED_SURFACE_CONTAINER_HIGHEST = Color(0xFF1B1724)
internal val AURORA_LIGHT_PRIMARY = Color(0xFF7020A0)
internal val AURORA_LIGHT_SECONDARY = Color(0xFF675078)
internal val AURORA_LIGHT_TERTIARY = Color(0xFF006874)
internal val AURORA_LIGHT_BACKGROUND = Color(0xFFFFF7FF)
internal val AURORA_LIGHT_SURFACE = Color(0xFFFFF7FF)

private const val DARK_PALETTE_LUMINANCE_BOUNDARY: Float = 0.5f
private const val HIGH_CONTRAST_SCRIM_ALPHA: Float = 0.90f
