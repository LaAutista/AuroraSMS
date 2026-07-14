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
    val colorScheme = when {
        profile.palette == AuroraPalette.SYSTEM_DYNAMIC && Build.VERSION.SDK_INT >= 31 -> {
            if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> staticColorScheme(profile.palette, systemDark)
    }
    val tokens = tokensFor(profile)
    CompositionLocalProvider(
        LocalAuroraMaterialProfile provides profile,
        LocalAuroraMaterialTokens provides tokens,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = shapesFor(profile, tokens),
            content = content,
        )
    }
}

internal fun staticColorScheme(
    palette: AuroraPalette,
    systemDark: Boolean,
): ColorScheme = when (palette) {
    AuroraPalette.AURORA_DARK -> darkColorScheme(
        primary = AURORA_PRIMARY,
        secondary = AURORA_SECONDARY,
        background = AURORA_DARK_BACKGROUND,
        surface = AURORA_DARK_SURFACE,
    )
    AuroraPalette.AMOLED_BLACK -> darkColorScheme(
        primary = AURORA_PRIMARY,
        secondary = AURORA_SECONDARY,
        background = Color.Black,
        surface = Color.Black,
        surfaceVariant = AMOLED_SURFACE_VARIANT,
    )
    AuroraPalette.LIGHT -> lightColorScheme(
        primary = AURORA_LIGHT_PRIMARY,
        secondary = AURORA_LIGHT_SECONDARY,
        background = AURORA_LIGHT_BACKGROUND,
        surface = AURORA_LIGHT_SURFACE,
    )
    AuroraPalette.SYSTEM_DYNAMIC -> if (systemDark) {
        staticColorScheme(AuroraPalette.AURORA_DARK, systemDark = true)
    } else {
        staticColorScheme(AuroraPalette.LIGHT, systemDark = false)
    }
}

internal fun tokensFor(profile: AuroraMaterialProfile): AuroraMaterialTokens {
    val dimensions = when (profile.rowDensity) {
        AuroraRowDensity.COMPACT -> DensityDimensions(56.dp, 8.dp, 40.dp)
        AuroraRowDensity.COMFORTABLE -> DensityDimensions(64.dp, 12.dp, 44.dp)
        AuroraRowDensity.SPACIOUS -> DensityDimensions(72.dp, 16.dp, 48.dp)
    }
    val bubbleRadius = when (profile.bubbleGeometry) {
        AuroraBubbleGeometry.COMPACT -> 8.dp
        AuroraBubbleGeometry.ROUNDED -> 18.dp
        AuroraBubbleGeometry.EXPRESSIVE -> 26.dp
    }
    return AuroraMaterialTokens(
        minimumTouchTarget = MINIMUM_TOUCH_TARGET,
        rowMinimumHeight = dimensions.rowMinimumHeight,
        contentSpacing = dimensions.contentSpacing,
        avatarSize = dimensions.avatarSize,
        bubbleCornerRadius = bubbleRadius,
        motionScale = if (profile.reducedMotion) 0f else 1f,
    )
}

private fun shapesFor(profile: AuroraMaterialProfile, tokens: AuroraMaterialTokens): Shapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(tokens.bubbleCornerRadius / 2),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(tokens.bubbleCornerRadius),
    large = androidx.compose.foundation.shape.RoundedCornerShape(
        if (profile.bubbleGeometry == AuroraBubbleGeometry.EXPRESSIVE) 32.dp else 24.dp,
    ),
)

@Immutable
private data class DensityDimensions(
    val rowMinimumHeight: Dp,
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
