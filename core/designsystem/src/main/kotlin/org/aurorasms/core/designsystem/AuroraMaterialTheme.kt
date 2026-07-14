// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class AuroraMaterialTokens(
    val minimumTouchTarget: Dp,
    val rowMinimumHeight: Dp,
    val rowVerticalPadding: Dp,
    val contentSpacing: Dp,
    val avatarSize: Dp,
    val bubbleCornerRadius: Dp,
    val motionScale: Float,
)

val LocalAuroraMaterialProfile = staticCompositionLocalOf { AuroraMaterialProfile.Default }

val LocalAuroraMaterialTokens = staticCompositionLocalOf {
    tokensFor(AuroraMaterialProfile.Default)
}

@Composable
fun AuroraMaterialTheme(
    profile: AuroraMaterialProfile = AuroraMaterialProfile.Default,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val baseColorScheme = when {
        profile.palette == AuroraPalette.SYSTEM_DYNAMIC && Build.VERSION.SDK_INT >= 31 -> {
            if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> staticColorScheme(
            palette = profile.palette,
            systemDark = systemDark,
            hueDegrees = profile.hueDegrees,
        )
    }
    val colorScheme = baseColorScheme.withHighContrast(
        enabled = profile.highContrast,
        dark = profile.palette != AuroraPalette.LIGHT &&
            (profile.palette != AuroraPalette.SYSTEM_DYNAMIC || systemDark),
    )
    val tokens = tokensFor(profile)
    CompositionLocalProvider(
        LocalAuroraMaterialProfile provides profile,
        LocalAuroraMaterialTokens provides tokens,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = shapesFor(profile),
            content = content,
        )
    }
}

internal fun staticColorScheme(
    palette: AuroraPalette,
    systemDark: Boolean,
    hueDegrees: Int = CANONICAL_AURORA_HUE_DEGREES,
    highContrast: Boolean = false,
): ColorScheme = when (palette) {
    AuroraPalette.AURORA_DARK -> darkColorScheme(
        primary = auroraPrimary(palette, hueDegrees),
        secondary = auroraSecondary(palette, hueDegrees),
        background = AURORA_DARK_BACKGROUND,
        surface = AURORA_DARK_SURFACE,
    )
    AuroraPalette.AMOLED_BLACK -> darkColorScheme(
        primary = auroraPrimary(palette, hueDegrees),
        secondary = auroraSecondary(palette, hueDegrees),
        background = Color.Black,
        surface = Color.Black,
        surfaceVariant = AMOLED_SURFACE_VARIANT,
    )
    AuroraPalette.LIGHT -> lightColorScheme(
        primary = auroraPrimary(palette, hueDegrees),
        secondary = auroraSecondary(palette, hueDegrees),
        background = AURORA_LIGHT_BACKGROUND,
        surface = AURORA_LIGHT_SURFACE,
    )
    AuroraPalette.SYSTEM_DYNAMIC -> if (systemDark) {
        staticColorScheme(
            AuroraPalette.AURORA_DARK,
            systemDark = true,
            hueDegrees = hueDegrees,
        )
    } else {
        staticColorScheme(
            AuroraPalette.LIGHT,
            systemDark = false,
            hueDegrees = hueDegrees,
        )
    }
}.withCustomHueRoles(
    enabled = hueDegrees != CANONICAL_AURORA_HUE_DEGREES,
    hueDegrees = hueDegrees,
    dark = palette != AuroraPalette.LIGHT &&
        (palette != AuroraPalette.SYSTEM_DYNAMIC || systemDark),
).withHighContrast(
    enabled = highContrast,
    dark = palette != AuroraPalette.LIGHT &&
        (palette != AuroraPalette.SYSTEM_DYNAMIC || systemDark),
)

internal fun tokensFor(profile: AuroraMaterialProfile): AuroraMaterialTokens {
    val dimensions = when (profile.rowDensity) {
        AuroraRowDensity.COMPACT -> DensityDimensions(56.dp, 8.dp, 8.dp, 40.dp)
        AuroraRowDensity.COMFORTABLE -> DensityDimensions(64.dp, 14.dp, 12.dp, 44.dp)
        AuroraRowDensity.SPACIOUS -> DensityDimensions(72.dp, 18.dp, 16.dp, 48.dp)
    }
    val bubbleRadius = when (profile.bubbleGeometry) {
        AuroraBubbleGeometry.COMPACT -> 8.dp
        AuroraBubbleGeometry.ROUNDED -> 24.dp
        AuroraBubbleGeometry.EXPRESSIVE -> 32.dp
    }
    return AuroraMaterialTokens(
        minimumTouchTarget = MINIMUM_TOUCH_TARGET,
        rowMinimumHeight = dimensions.rowMinimumHeight,
        rowVerticalPadding = dimensions.rowVerticalPadding,
        contentSpacing = dimensions.contentSpacing,
        avatarSize = dimensions.avatarSize,
        bubbleCornerRadius = bubbleRadius,
        motionScale = if (profile.reducedMotion) 0f else 1f,
    )
}

private fun shapesFor(profile: AuroraMaterialProfile): Shapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(
        componentShapeRadius(profile) / 2,
    ),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(componentShapeRadius(profile)),
    large = androidx.compose.foundation.shape.RoundedCornerShape(
        if (profile.bubbleGeometry == AuroraBubbleGeometry.EXPRESSIVE) 32.dp else 24.dp,
    ),
)

private fun componentShapeRadius(profile: AuroraMaterialProfile): Dp = when (profile.bubbleGeometry) {
    AuroraBubbleGeometry.COMPACT -> 8.dp
    AuroraBubbleGeometry.ROUNDED -> 18.dp
    AuroraBubbleGeometry.EXPRESSIVE -> 26.dp
}

private fun auroraPrimary(palette: AuroraPalette, hueDegrees: Int): Color {
    if (hueDegrees == CANONICAL_AURORA_HUE_DEGREES) {
        return if (palette == AuroraPalette.LIGHT) AURORA_LIGHT_PRIMARY else AURORA_PRIMARY
    }
    return if (palette == AuroraPalette.LIGHT) {
        Color.hsv(hueDegrees.toFloat(), saturation = 1f, value = 0.42f)
    } else {
        Color.hsv(hueDegrees.toFloat(), saturation = 0.44f, value = 0.84f)
    }
}

private fun auroraSecondary(palette: AuroraPalette, hueDegrees: Int): Color {
    if (hueDegrees == CANONICAL_AURORA_HUE_DEGREES) {
        return if (palette == AuroraPalette.LIGHT) AURORA_LIGHT_SECONDARY else AURORA_SECONDARY
    }
    val secondaryHue = ((hueDegrees + SECONDARY_HUE_OFFSET_DEGREES) % 360).toFloat()
    return if (palette == AuroraPalette.LIGHT) {
        Color.hsv(secondaryHue, saturation = 0.70f, value = 0.38f)
    } else {
        Color.hsv(secondaryHue, saturation = 0.39f, value = 1f)
    }
}

private fun ColorScheme.withCustomHueRoles(
    enabled: Boolean,
    hueDegrees: Int,
    dark: Boolean,
): ColorScheme {
    if (!enabled) return this
    val secondaryHue = ((hueDegrees + SECONDARY_HUE_OFFSET_DEGREES) % 360).toFloat()
    return if (dark) {
        copy(
            onPrimary = Color.Black,
            primaryContainer = Color.hsv(
                hue = hueDegrees.toFloat(),
                saturation = 0.65f,
                value = 0.32f,
            ),
            onPrimaryContainer = Color.White,
            onSecondary = Color.Black,
            secondaryContainer = Color.hsv(
                hue = secondaryHue,
                saturation = 0.55f,
                value = 0.30f,
            ),
            onSecondaryContainer = Color.White,
        )
    } else {
        copy(
            onPrimary = Color.White,
            primaryContainer = Color.hsv(
                hue = hueDegrees.toFloat(),
                saturation = 0.20f,
                value = 0.96f,
            ),
            onPrimaryContainer = Color.Black,
            onSecondary = Color.White,
            secondaryContainer = Color.hsv(
                hue = secondaryHue,
                saturation = 0.22f,
                value = 0.94f,
            ),
            onSecondaryContainer = Color.Black,
        )
    }
}

private fun ColorScheme.withHighContrast(enabled: Boolean, dark: Boolean): ColorScheme {
    if (!enabled) return this
    return if (dark) {
        copy(
            primary = Color.White,
            onPrimary = Color.Black,
            primaryContainer = Color.White,
            onPrimaryContainer = Color.Black,
            secondary = Color.White,
            onSecondary = Color.Black,
            secondaryContainer = Color.White,
            onSecondaryContainer = Color.Black,
            tertiary = Color.White,
            onTertiary = Color.Black,
            tertiaryContainer = Color.White,
            onTertiaryContainer = Color.Black,
            background = Color.Black,
            surface = Color.Black,
            surfaceVariant = HIGH_CONTRAST_DARK_SURFACE,
            onBackground = Color.White,
            onSurface = Color.White,
            onSurfaceVariant = Color.White,
            outline = Color.White,
            outlineVariant = HIGH_CONTRAST_DARK_OUTLINE_VARIANT,
        )
    } else {
        copy(
            primary = Color.Black,
            onPrimary = Color.White,
            primaryContainer = Color.Black,
            onPrimaryContainer = Color.White,
            secondary = Color.Black,
            onSecondary = Color.White,
            secondaryContainer = Color.Black,
            onSecondaryContainer = Color.White,
            tertiary = Color.Black,
            onTertiary = Color.White,
            tertiaryContainer = Color.Black,
            onTertiaryContainer = Color.White,
            background = Color.White,
            surface = Color.White,
            surfaceVariant = HIGH_CONTRAST_LIGHT_SURFACE,
            onBackground = Color.Black,
            onSurface = Color.Black,
            onSurfaceVariant = Color.Black,
            outline = Color.Black,
            outlineVariant = HIGH_CONTRAST_LIGHT_OUTLINE_VARIANT,
        )
    }
}

@Immutable
private data class DensityDimensions(
    val rowMinimumHeight: Dp,
    val rowVerticalPadding: Dp,
    val contentSpacing: Dp,
    val avatarSize: Dp,
)

private val MINIMUM_TOUCH_TARGET: Dp = 48.dp
private val AURORA_PRIMARY = Color(0xFF78D6C6)
private val AURORA_SECONDARY = Color(0xFF9CB8FF)
private val AURORA_DARK_BACKGROUND = Color(0xFF101419)
private val AURORA_DARK_SURFACE = Color(0xFF171C22)
private val AMOLED_SURFACE_VARIANT = Color(0xFF111418)
private val AURORA_LIGHT_PRIMARY = Color(0xFF006A60)
private val AURORA_LIGHT_SECONDARY = Color(0xFF405E91)
private val AURORA_LIGHT_BACKGROUND = Color(0xFFF6FAF8)
private val AURORA_LIGHT_SURFACE = Color(0xFFF6FAF8)
private val HIGH_CONTRAST_DARK_SURFACE = Color(0xFF0E1211)
private val HIGH_CONTRAST_DARK_OUTLINE_VARIANT = Color(0xFFBFC9C7)
private val HIGH_CONTRAST_LIGHT_SURFACE = Color(0xFFF2F7F5)
private val HIGH_CONTRAST_LIGHT_OUTLINE_VARIANT = Color(0xFF3F4947)
private const val SECONDARY_HUE_OFFSET_DEGREES: Int = 48
