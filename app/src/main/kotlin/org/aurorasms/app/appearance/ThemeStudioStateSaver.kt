// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance

import android.os.Bundle
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import org.aurorasms.core.designsystem.AuroraAvatarMask
import org.aurorasms.core.designsystem.AuroraBubbleGeometry
import org.aurorasms.core.designsystem.AuroraMaterialProfile
import org.aurorasms.core.designsystem.AuroraNavigationStyle
import org.aurorasms.core.designsystem.AuroraPalette
import org.aurorasms.core.designsystem.AuroraRowDensity

/** Primitive, bounded representation used to test restoration without Android state APIs. */
@Immutable
internal data class ThemeStudioRestorableState(
    val schemaVersion: Int,
    val profiles: List<ThemeStudioRestorableSavedProfile>,
    val activeProfileId: Long,
    val selectedProfileId: Long,
    val editedName: String,
    val draftProfile: ThemeStudioRestorableProfile,
    val newCopy: Boolean,
    val resetStaged: Boolean,
) {
    override fun toString(): String =
        "ThemeStudioRestorableState(schemaVersion=$schemaVersion, profileCount=${profiles.size}, " +
            "activeProfileId=$activeProfileId, selectedProfileId=$selectedProfileId, " +
            "newCopy=$newCopy, resetStaged=$resetStaged, editedName=REDACTED)"
}

@Immutable
internal data class ThemeStudioRestorableSavedProfile(
    val id: Long,
    val revision: Long,
    val name: String,
    val profile: ThemeStudioRestorableProfile,
    val deletable: Boolean,
) {
    override fun toString(): String =
        "ThemeStudioRestorableSavedProfile(id=$id, revision=$revision, " +
            "deletable=$deletable, name=REDACTED)"
}

@Immutable
internal data class ThemeStudioRestorableProfile(
    val schemaVersion: Int,
    val paletteCode: String,
    val hueDegrees: Int,
    val densityCode: String,
    val avatarMaskCode: String,
    val navigationCode: String,
    val bubbleGeometryCode: String,
    val reducedMotion: Boolean,
    val highContrast: Boolean,
    val wallpaperDim: Float,
)

val ThemeStudioEditorStateSaver: Saver<ThemeStudioEditorState, Bundle> = Saver(
    save = { state -> state.toRestorableState().toBundle() },
    restore = { bundle -> bundle.toRestorableState()?.toEditorStateOrNull() },
)

internal fun ThemeStudioEditorState.toRestorableState(): ThemeStudioRestorableState =
    ThemeStudioRestorableState(
        schemaVersion = THEME_STUDIO_RESTORATION_SCHEMA,
        profiles = savedProfiles.map { saved ->
            ThemeStudioRestorableSavedProfile(
                id = saved.id,
                revision = saved.revision,
                name = saved.name,
                profile = saved.profile.toRestorableProfile(),
                deletable = saved.deletable,
            )
        },
        activeProfileId = activeProfileId,
        selectedProfileId = selectedProfileId,
        editedName = name,
        draftProfile = draftProfile.toRestorableProfile(),
        newCopy = newCopy,
        resetStaged = resetStaged,
    )

internal fun ThemeStudioRestorableState.toEditorStateOrNull(): ThemeStudioEditorState? {
    if (schemaVersion != THEME_STUDIO_RESTORATION_SCHEMA) return null
    if (profiles.isEmpty() || profiles.size > MAXIMUM_THEME_STUDIO_SAVED_PROFILES) return null
    if (!isBoundedThemeStudioProfileNameInput(editedName)) return null
    return try {
        ThemeStudioEditorState(
            savedProfiles = profiles.map { saved ->
                ThemeStudioSavedProfile(
                    id = saved.id,
                    revision = saved.revision,
                    name = saved.name,
                    profile = saved.profile.toMaterialProfileOrNull() ?: return null,
                    deletable = saved.deletable,
                )
            },
            activeProfileId = activeProfileId,
            selectedProfileId = selectedProfileId,
            name = editedName,
            draftProfile = draftProfile.toMaterialProfileOrNull() ?: return null,
            newCopy = newCopy,
            resetStaged = resetStaged,
            // An in-flight local operation or dialog never survives process recreation.
            operation = ThemeStudioOperation.IDLE,
            error = null,
            deleteConfirmationProfileId = null,
        )
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }
}

internal fun AuroraMaterialProfile.toRestorableProfile(): ThemeStudioRestorableProfile =
    ThemeStudioRestorableProfile(
        schemaVersion = schemaVersion,
        paletteCode = palette.toStableCode(),
        hueDegrees = hueDegrees,
        densityCode = rowDensity.toStableCode(),
        avatarMaskCode = avatarMask.toStableCode(),
        navigationCode = navigationStyle.toStableCode(),
        bubbleGeometryCode = bubbleGeometry.toStableCode(),
        reducedMotion = reducedMotion,
        highContrast = highContrast,
        wallpaperDim = wallpaperDim,
    )

internal fun ThemeStudioRestorableProfile.toMaterialProfileOrNull(): AuroraMaterialProfile? {
    return try {
        AuroraMaterialProfile(
            schemaVersion = schemaVersion,
            palette = paletteFromStableCode(paletteCode) ?: return null,
            hueDegrees = hueDegrees,
            rowDensity = densityFromStableCode(densityCode) ?: return null,
            avatarMask = avatarMaskFromStableCode(avatarMaskCode) ?: return null,
            navigationStyle = navigationFromStableCode(navigationCode) ?: return null,
            bubbleGeometry = bubbleGeometryFromStableCode(bubbleGeometryCode) ?: return null,
            reducedMotion = reducedMotion,
            highContrast = highContrast,
            wallpaperDim = wallpaperDim,
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal fun AuroraPalette.toStableCode(): String = when (this) {
    AuroraPalette.AURORA_DARK -> "aurora_dark"
    AuroraPalette.AMOLED_BLACK -> "amoled_black"
    AuroraPalette.LIGHT -> "light"
    AuroraPalette.SYSTEM_DYNAMIC -> "system_dynamic"
}

internal fun paletteFromStableCode(code: String): AuroraPalette? = when (code) {
    "aurora_dark" -> AuroraPalette.AURORA_DARK
    "amoled_black" -> AuroraPalette.AMOLED_BLACK
    "light" -> AuroraPalette.LIGHT
    "system_dynamic" -> AuroraPalette.SYSTEM_DYNAMIC
    else -> null
}

internal fun AuroraRowDensity.toStableCode(): String = when (this) {
    AuroraRowDensity.COMPACT -> "compact"
    AuroraRowDensity.COMFORTABLE -> "comfortable"
    AuroraRowDensity.SPACIOUS -> "spacious"
}

internal fun densityFromStableCode(code: String): AuroraRowDensity? = when (code) {
    "compact" -> AuroraRowDensity.COMPACT
    "comfortable" -> AuroraRowDensity.COMFORTABLE
    "spacious" -> AuroraRowDensity.SPACIOUS
    else -> null
}

internal fun AuroraAvatarMask.toStableCode(): String = when (this) {
    AuroraAvatarMask.CIRCLE -> "circle"
    AuroraAvatarMask.ROUNDED_SQUARE -> "rounded_square"
    AuroraAvatarMask.SQUIRCLE -> "squircle"
    AuroraAvatarMask.HEXAGON -> "hexagon"
}

internal fun avatarMaskFromStableCode(code: String): AuroraAvatarMask? = when (code) {
    "circle" -> AuroraAvatarMask.CIRCLE
    "rounded_square" -> AuroraAvatarMask.ROUNDED_SQUARE
    "squircle" -> AuroraAvatarMask.SQUIRCLE
    "hexagon" -> AuroraAvatarMask.HEXAGON
    else -> null
}

internal fun AuroraNavigationStyle.toStableCode(): String = when (this) {
    AuroraNavigationStyle.CLASSIC -> "classic"
    AuroraNavigationStyle.BOTTOM_BAR -> "bottom_bar"
    AuroraNavigationStyle.ADAPTIVE_RAIL -> "adaptive_rail"
}

internal fun navigationFromStableCode(code: String): AuroraNavigationStyle? = when (code) {
    "classic" -> AuroraNavigationStyle.CLASSIC
    "bottom_bar" -> AuroraNavigationStyle.BOTTOM_BAR
    "adaptive_rail" -> AuroraNavigationStyle.ADAPTIVE_RAIL
    else -> null
}

internal fun AuroraBubbleGeometry.toStableCode(): String = when (this) {
    AuroraBubbleGeometry.COMPACT -> "compact"
    AuroraBubbleGeometry.ROUNDED -> "rounded"
    AuroraBubbleGeometry.EXPRESSIVE -> "expressive"
}

internal fun bubbleGeometryFromStableCode(code: String): AuroraBubbleGeometry? = when (code) {
    "compact" -> AuroraBubbleGeometry.COMPACT
    "rounded" -> AuroraBubbleGeometry.ROUNDED
    "expressive" -> AuroraBubbleGeometry.EXPRESSIVE
    else -> null
}

private fun ThemeStudioRestorableState.toBundle(): Bundle = Bundle().apply {
    putInt(KEY_RESTORATION_SCHEMA, schemaVersion)
    putParcelableArrayList(KEY_SAVED_PROFILES, ArrayList(profiles.map { it.toBundle() }))
    putLong(KEY_ACTIVE_PROFILE_ID, activeProfileId)
    putLong(KEY_SELECTED_PROFILE_ID, selectedProfileId)
    putString(KEY_EDITED_NAME, editedName)
    putBundle(KEY_DRAFT_PROFILE, draftProfile.toBundle())
    putBoolean(KEY_NEW_COPY, newCopy)
    putBoolean(KEY_RESET_STAGED, resetStaged)
}

@Suppress("DEPRECATION")
private fun Bundle.toRestorableState(): ThemeStudioRestorableState? {
    val profiles = getParcelableArrayList<Bundle>(KEY_SAVED_PROFILES)
        ?.takeIf { it.size <= MAXIMUM_THEME_STUDIO_SAVED_PROFILES }
        ?.map { it.toRestorableSavedProfile() ?: return null }
        ?: return null
    return ThemeStudioRestorableState(
        schemaVersion = getInt(KEY_RESTORATION_SCHEMA, -1),
        profiles = profiles,
        activeProfileId = getLong(KEY_ACTIVE_PROFILE_ID, INVALID_PROFILE_ID),
        selectedProfileId = getLong(KEY_SELECTED_PROFILE_ID, INVALID_PROFILE_ID),
        editedName = getString(KEY_EDITED_NAME) ?: return null,
        draftProfile = getBundle(KEY_DRAFT_PROFILE)?.toRestorableProfile() ?: return null,
        newCopy = getBoolean(KEY_NEW_COPY),
        resetStaged = getBoolean(KEY_RESET_STAGED),
    )
}

private fun ThemeStudioRestorableSavedProfile.toBundle(): Bundle = Bundle().apply {
    putLong(KEY_PROFILE_ID, id)
    putLong(KEY_PROFILE_REVISION, revision)
    putString(KEY_PROFILE_NAME, name)
    putBundle(KEY_PROFILE_VALUE, profile.toBundle())
    putBoolean(KEY_PROFILE_DELETABLE, deletable)
}

private fun Bundle.toRestorableSavedProfile(): ThemeStudioRestorableSavedProfile? {
    val name = getString(KEY_PROFILE_NAME) ?: return null
    val profile = getBundle(KEY_PROFILE_VALUE)?.toRestorableProfile() ?: return null
    return ThemeStudioRestorableSavedProfile(
        id = getLong(KEY_PROFILE_ID, INVALID_PROFILE_ID),
        revision = getLong(KEY_PROFILE_REVISION, INVALID_PROFILE_REVISION),
        name = name,
        profile = profile,
        deletable = getBoolean(KEY_PROFILE_DELETABLE),
    )
}

private fun ThemeStudioRestorableProfile.toBundle(): Bundle = Bundle().apply {
    putInt(KEY_MATERIAL_SCHEMA, schemaVersion)
    putString(KEY_PALETTE, paletteCode)
    putInt(KEY_HUE, hueDegrees)
    putString(KEY_DENSITY, densityCode)
    putString(KEY_AVATAR_MASK, avatarMaskCode)
    putString(KEY_NAVIGATION, navigationCode)
    putString(KEY_BUBBLE, bubbleGeometryCode)
    putBoolean(KEY_REDUCED_MOTION, reducedMotion)
    putBoolean(KEY_HIGH_CONTRAST, highContrast)
    putFloat(KEY_WALLPAPER_DIM, wallpaperDim)
}

private fun Bundle.toRestorableProfile(): ThemeStudioRestorableProfile? {
    val palette = getString(KEY_PALETTE) ?: return null
    val density = getString(KEY_DENSITY) ?: return null
    val avatarMask = getString(KEY_AVATAR_MASK) ?: return null
    val navigation = getString(KEY_NAVIGATION) ?: return null
    val bubble = getString(KEY_BUBBLE) ?: return null
    return ThemeStudioRestorableProfile(
        schemaVersion = getInt(KEY_MATERIAL_SCHEMA, -1),
        paletteCode = palette,
        hueDegrees = getInt(KEY_HUE, -1),
        densityCode = density,
        avatarMaskCode = avatarMask,
        navigationCode = navigation,
        bubbleGeometryCode = bubble,
        reducedMotion = getBoolean(KEY_REDUCED_MOTION),
        highContrast = getBoolean(KEY_HIGH_CONTRAST),
        wallpaperDim = getFloat(KEY_WALLPAPER_DIM, Float.NaN),
    )
}

private const val THEME_STUDIO_RESTORATION_SCHEMA: Int = 1
private const val INVALID_PROFILE_ID: Long = -1L
private const val INVALID_PROFILE_REVISION: Long = -1L
private const val KEY_RESTORATION_SCHEMA: String = "restoration_schema"
private const val KEY_SAVED_PROFILES: String = "saved_profiles"
private const val KEY_ACTIVE_PROFILE_ID: String = "active_profile_id"
private const val KEY_SELECTED_PROFILE_ID: String = "selected_profile_id"
private const val KEY_EDITED_NAME: String = "edited_name"
private const val KEY_DRAFT_PROFILE: String = "draft_profile"
private const val KEY_NEW_COPY: String = "new_copy"
private const val KEY_RESET_STAGED: String = "reset_staged"
private const val KEY_PROFILE_ID: String = "profile_id"
private const val KEY_PROFILE_REVISION: String = "profile_revision"
private const val KEY_PROFILE_NAME: String = "profile_name"
private const val KEY_PROFILE_VALUE: String = "profile_value"
private const val KEY_PROFILE_DELETABLE: String = "profile_deletable"
private const val KEY_MATERIAL_SCHEMA: String = "material_schema"
private const val KEY_PALETTE: String = "palette"
private const val KEY_HUE: String = "hue"
private const val KEY_DENSITY: String = "density"
private const val KEY_AVATAR_MASK: String = "avatar_mask"
private const val KEY_NAVIGATION: String = "navigation"
private const val KEY_BUBBLE: String = "bubble"
private const val KEY_REDUCED_MOTION: String = "reduced_motion"
private const val KEY_HIGH_CONTRAST: String = "high_contrast"
private const val KEY_WALLPAPER_DIM: String = "wallpaper_dim"
