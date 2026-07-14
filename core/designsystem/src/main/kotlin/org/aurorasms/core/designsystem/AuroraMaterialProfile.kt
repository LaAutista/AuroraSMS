// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.designsystem

import androidx.compose.runtime.Immutable

/**
 * Versioned, declarative appearance input. It contains no executable imports,
 * message data, contacts, provider identifiers, or media bytes.
 */
@Immutable
data class AuroraMaterialProfile(
    val schemaVersion: Int = CURRENT_AURORA_PROFILE_SCHEMA,
    val palette: AuroraPalette = AuroraPalette.AURORA_DARK,
    val hueDegrees: Int = CANONICAL_AURORA_HUE_DEGREES,
    val rowDensity: AuroraRowDensity = AuroraRowDensity.COMFORTABLE,
    val avatarMask: AuroraAvatarMask = AuroraAvatarMask.CIRCLE,
    val navigationStyle: AuroraNavigationStyle = AuroraNavigationStyle.CLASSIC,
    val bubbleGeometry: AuroraBubbleGeometry = AuroraBubbleGeometry.ROUNDED,
    val reducedMotion: Boolean = false,
    val highContrast: Boolean = false,
    val wallpaperDim: Float = DEFAULT_WALLPAPER_DIM,
) {
    init {
        require(schemaVersion == CURRENT_AURORA_PROFILE_SCHEMA) {
            "Unsupported AuroraMaterial profile schema"
        }
        require(hueDegrees in MINIMUM_HUE_DEGREES..MAXIMUM_HUE_DEGREES) {
            "Hue must use the closed 0..359 degree range"
        }
        require(wallpaperDim in MINIMUM_ACCESSIBLE_WALLPAPER_DIM..MAXIMUM_WALLPAPER_DIM) {
            "Wallpaper dim must remain inside the accessible range"
        }
    }

    companion object {
        val Default: AuroraMaterialProfile = AuroraMaterialProfile()
    }
}

enum class AuroraPalette {
    AURORA_DARK,
    AMOLED_BLACK,
    LIGHT,
    SYSTEM_DYNAMIC,
}

enum class AuroraRowDensity {
    COMPACT,
    COMFORTABLE,
    SPACIOUS,
}

enum class AuroraAvatarMask {
    CIRCLE,
    ROUNDED_SQUARE,
    SQUIRCLE,
    HEXAGON,
}

enum class AuroraNavigationStyle {
    CLASSIC,
    BOTTOM_BAR,
    ADAPTIVE_RAIL,
}

enum class AuroraBubbleGeometry {
    COMPACT,
    ROUNDED,
    EXPRESSIVE,
}

const val CURRENT_AURORA_PROFILE_SCHEMA: Int = 1
const val CANONICAL_AURORA_HUE_DEGREES: Int = 174
const val MINIMUM_HUE_DEGREES: Int = 0
const val MAXIMUM_HUE_DEGREES: Int = 359
const val MINIMUM_ACCESSIBLE_WALLPAPER_DIM: Float = 0.35f
const val MAXIMUM_WALLPAPER_DIM: Float = 0.90f
const val DEFAULT_WALLPAPER_DIM: Float = 0.52f
