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
    val visualTokens = visualTokensFor(profile, colorScheme)
    CompositionLocalProvider(
        LocalAuroraMaterialProfile provides profile,
        LocalAuroraMaterialTokens provides tokens,
        LocalAuroraVisualTokens provides visualTokens,
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
        onPrimary = AURORA_DARK_ON_PRIMARY,
        primaryContainer = AURORA_DARK_PRIMARY_CONTAINER,
        onPrimaryContainer = AURORA_DARK_ON_PRIMARY_CONTAINER,
        inversePrimary = AURORA_DARK_INVERSE_PRIMARY,
        secondary = auroraSecondary(palette, hueDegrees),
        onSecondary = AURORA_DARK_ON_SECONDARY,
        secondaryContainer = AURORA_DARK_SECONDARY_CONTAINER,
        onSecondaryContainer = AURORA_DARK_ON_SECONDARY_CONTAINER,
        tertiary = AURORA_CYAN,
        onTertiary = AURORA_DARK_ON_TERTIARY,
        tertiaryContainer = AURORA_DARK_TERTIARY_CONTAINER,
        onTertiaryContainer = AURORA_DARK_ON_TERTIARY_CONTAINER,
        background = AURORA_DARK_BACKGROUND,
        onBackground = AURORA_ON_SURFACE,
        surface = AURORA_DARK_SURFACE,
        onSurface = AURORA_ON_SURFACE,
        surfaceVariant = AURORA_SURFACE_VARIANT,
        onSurfaceVariant = AURORA_DARK_ON_SURFACE_VARIANT,
        surfaceTint = auroraPrimary(palette, hueDegrees),
        inverseSurface = AURORA_DARK_INVERSE_SURFACE,
        inverseOnSurface = AURORA_DARK_INVERSE_ON_SURFACE,
        outline = AURORA_DARK_OUTLINE,
        outlineVariant = AURORA_DARK_OUTLINE_VARIANT,
        scrim = Color.Black,
        surfaceBright = AURORA_DARK_SURFACE_BRIGHT,
        surfaceDim = AURORA_DARK_BACKGROUND,
        surfaceContainerLowest = AURORA_DARK_SURFACE_LOWEST,
        surfaceContainerLow = AURORA_DARK_SURFACE_LOW,
        surfaceContainer = AURORA_DARK_SURFACE_CONTAINER,
        surfaceContainerHigh = AURORA_SURFACE_CONTAINER_HIGH,
        surfaceContainerHighest = AURORA_MENU_SURFACE,
    )
    AuroraPalette.AMOLED_BLACK -> darkColorScheme(
        primary = auroraPrimary(palette, hueDegrees),
        onPrimary = AURORA_DARK_ON_PRIMARY,
        primaryContainer = AURORA_DARK_PRIMARY_CONTAINER,
        onPrimaryContainer = AURORA_DARK_ON_PRIMARY_CONTAINER,
        inversePrimary = AURORA_DARK_INVERSE_PRIMARY,
        secondary = auroraSecondary(palette, hueDegrees),
        onSecondary = AURORA_DARK_ON_SECONDARY,
        secondaryContainer = AURORA_DARK_SECONDARY_CONTAINER,
        onSecondaryContainer = AURORA_DARK_ON_SECONDARY_CONTAINER,
        tertiary = AURORA_CYAN,
        onTertiary = AURORA_DARK_ON_TERTIARY,
        tertiaryContainer = AURORA_DARK_TERTIARY_CONTAINER,
        onTertiaryContainer = AURORA_DARK_ON_TERTIARY_CONTAINER,
        background = Color.Black,
        onBackground = AURORA_ON_SURFACE,
        surface = Color.Black,
        onSurface = AURORA_ON_SURFACE,
        surfaceVariant = AMOLED_SURFACE_VARIANT,
        onSurfaceVariant = AURORA_DARK_ON_SURFACE_VARIANT,
        surfaceTint = auroraPrimary(palette, hueDegrees),
        inverseSurface = AURORA_DARK_INVERSE_SURFACE,
        inverseOnSurface = AURORA_DARK_INVERSE_ON_SURFACE,
        outline = AURORA_DARK_OUTLINE,
        outlineVariant = AURORA_DARK_OUTLINE_VARIANT,
        scrim = Color.Black,
        surfaceBright = AMOLED_SURFACE_CONTAINER_HIGHEST,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color.Black,
        surfaceContainer = AMOLED_SURFACE_VARIANT,
        surfaceContainerHigh = AMOLED_SURFACE_CONTAINER_HIGH,
        surfaceContainerHighest = AMOLED_SURFACE_CONTAINER_HIGHEST,
    )
    AuroraPalette.LIGHT -> lightColorScheme(
        primary = auroraPrimary(palette, hueDegrees),
        onPrimary = Color.White,
        primaryContainer = AURORA_LIGHT_PRIMARY_CONTAINER,
        onPrimaryContainer = AURORA_LIGHT_ON_PRIMARY_CONTAINER,
        inversePrimary = AURORA_PRIMARY,
        secondary = auroraSecondary(palette, hueDegrees),
        onSecondary = Color.White,
        secondaryContainer = AURORA_LIGHT_SECONDARY_CONTAINER,
        onSecondaryContainer = AURORA_LIGHT_ON_SECONDARY_CONTAINER,
        tertiary = AURORA_LIGHT_TERTIARY,
        onTertiary = Color.White,
        tertiaryContainer = AURORA_LIGHT_TERTIARY_CONTAINER,
        onTertiaryContainer = AURORA_LIGHT_ON_TERTIARY_CONTAINER,
        background = AURORA_LIGHT_BACKGROUND,
        onBackground = AURORA_LIGHT_ON_SURFACE,
        surface = AURORA_LIGHT_SURFACE,
        onSurface = AURORA_LIGHT_ON_SURFACE,
        surfaceVariant = AURORA_LIGHT_SURFACE_VARIANT,
        onSurfaceVariant = AURORA_LIGHT_ON_SURFACE_VARIANT,
        surfaceTint = auroraPrimary(palette, hueDegrees),
        inverseSurface = AURORA_LIGHT_INVERSE_SURFACE,
        inverseOnSurface = AURORA_LIGHT_INVERSE_ON_SURFACE,
        outline = AURORA_LIGHT_OUTLINE,
        outlineVariant = AURORA_LIGHT_OUTLINE_VARIANT,
        scrim = Color.Black,
        surfaceBright = AURORA_LIGHT_SURFACE,
        surfaceDim = AURORA_LIGHT_SURFACE_DIM,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = AURORA_LIGHT_SURFACE_LOW,
        surfaceContainer = AURORA_LIGHT_SURFACE_CONTAINER,
        surfaceContainerHigh = AURORA_LIGHT_SURFACE_HIGH,
        surfaceContainerHighest = AURORA_LIGHT_SURFACE_HIGHEST,
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
            surfaceDim = Color.Black,
            surfaceBright = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color.Black,
            surfaceContainer = Color.Black,
            surfaceContainerHigh = Color.Black,
            surfaceContainerHighest = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White,
            onSurfaceVariant = Color.White,
            inverseSurface = Color.White,
            inverseOnSurface = Color.Black,
            surfaceTint = Color.White,
            outline = Color.White,
            outlineVariant = HIGH_CONTRAST_DARK_OUTLINE_VARIANT,
            scrim = Color.Black,
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
            surfaceDim = Color.White,
            surfaceBright = Color.White,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color.White,
            surfaceContainer = Color.White,
            surfaceContainerHigh = Color.White,
            surfaceContainerHighest = Color.White,
            onBackground = Color.Black,
            onSurface = Color.Black,
            onSurfaceVariant = Color.Black,
            inverseSurface = Color.Black,
            inverseOnSurface = Color.White,
            surfaceTint = Color.Black,
            outline = Color.Black,
            outlineVariant = HIGH_CONTRAST_LIGHT_OUTLINE_VARIANT,
            scrim = Color.Black,
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
private val AURORA_DARK_ON_PRIMARY = Color(0xFF210033)
private val AURORA_DARK_PRIMARY_CONTAINER = Color(0xFF4D1378)
private val AURORA_DARK_ON_PRIMARY_CONTAINER = Color(0xFFF1DCFF)
private val AURORA_DARK_INVERSE_PRIMARY = Color(0xFF7A20B4)
private val AURORA_DARK_ON_SECONDARY = Color(0xFF301743)
private val AURORA_DARK_SECONDARY_CONTAINER = Color(0xFF49315D)
private val AURORA_DARK_ON_SECONDARY_CONTAINER = Color(0xFFF0DCFF)
private val AURORA_DARK_ON_TERTIARY = Color(0xFF00363A)
private val AURORA_DARK_TERTIARY_CONTAINER = Color(0xFF004F55)
private val AURORA_DARK_ON_TERTIARY_CONTAINER = Color(0xFF8FF5FC)
private val AURORA_DARK_ON_SURFACE_VARIANT = Color(0xFFD1C6D7)
private val AURORA_DARK_INVERSE_SURFACE = Color(0xFFF1ECF4)
private val AURORA_DARK_INVERSE_ON_SURFACE = Color(0xFF302D33)
private val AURORA_DARK_OUTLINE = Color(0xFF998DA0)
private val AURORA_DARK_OUTLINE_VARIANT = Color(0xFF4C4352)
private val AURORA_DARK_SURFACE_BRIGHT = Color(0xFF332E39)
private val AURORA_DARK_SURFACE_LOWEST = Color(0xFF03020A)
private val AURORA_DARK_SURFACE_LOW = Color(0xFF0A0815)
private val AURORA_DARK_SURFACE_CONTAINER = Color(0xFF11101D)
private val AURORA_LIGHT_PRIMARY_CONTAINER = Color(0xFFF0DBFF)
private val AURORA_LIGHT_ON_PRIMARY_CONTAINER = Color(0xFF2D0047)
private val AURORA_LIGHT_SECONDARY_CONTAINER = Color(0xFFEFDCFF)
private val AURORA_LIGHT_ON_SECONDARY_CONTAINER = Color(0xFF251532)
private val AURORA_LIGHT_TERTIARY_CONTAINER = Color(0xFF9CF1FB)
private val AURORA_LIGHT_ON_TERTIARY_CONTAINER = Color(0xFF001F23)
private val AURORA_LIGHT_ON_SURFACE = Color(0xFF211A23)
private val AURORA_LIGHT_SURFACE_VARIANT = Color(0xFFEBDDEA)
private val AURORA_LIGHT_ON_SURFACE_VARIANT = Color(0xFF4C444D)
private val AURORA_LIGHT_INVERSE_SURFACE = Color(0xFF362F37)
private val AURORA_LIGHT_INVERSE_ON_SURFACE = Color(0xFFFAECF8)
private val AURORA_LIGHT_OUTLINE = Color(0xFF7D747E)
private val AURORA_LIGHT_OUTLINE_VARIANT = Color(0xFFCFC3CF)
private val AURORA_LIGHT_SURFACE_DIM = Color(0xFFE4D7E3)
private val AURORA_LIGHT_SURFACE_LOW = Color(0xFFFFF0FC)
private val AURORA_LIGHT_SURFACE_CONTAINER = Color(0xFFF9EAF6)
private val AURORA_LIGHT_SURFACE_HIGH = Color(0xFFF3E4F0)
private val AURORA_LIGHT_SURFACE_HIGHEST = Color(0xFFEDDFEA)
private val HIGH_CONTRAST_DARK_SURFACE = Color(0xFF0E1211)
private val HIGH_CONTRAST_DARK_OUTLINE_VARIANT = Color(0xFFBFC9C7)
private val HIGH_CONTRAST_LIGHT_SURFACE = Color(0xFFF2F7F5)
private val HIGH_CONTRAST_LIGHT_OUTLINE_VARIANT = Color(0xFF3F4947)
private const val SECONDARY_HUE_OFFSET_DEGREES: Int = 48
