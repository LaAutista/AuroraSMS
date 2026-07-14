// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.aurorasms.core.state.AppearanceAvatarMask
import org.aurorasms.core.state.AppearanceBubbleGeometry
import org.aurorasms.core.state.AppearanceNavigationStyle
import org.aurorasms.core.state.AppearancePalette
import org.aurorasms.core.state.AppearanceProfile
import org.aurorasms.core.state.AppearanceProfileEdit
import org.aurorasms.core.state.AppearanceProfileId
import org.aurorasms.core.state.AppearanceProfileName
import org.aurorasms.core.state.AppearanceProfileValues
import org.aurorasms.core.state.AppearanceRevision
import org.aurorasms.core.state.AppearanceRowDensity
import org.aurorasms.core.state.NewAppearanceProfile

@Entity(
    tableName = "appearance_profiles",
    indices = [Index(value = ["normalized_name"], unique = true)],
)
internal data class AppearanceProfileEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "profile_id")
    val profileId: Long = 0L,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "normalized_name")
    val normalizedName: String,
    @ColumnInfo(name = "profile_schema_version")
    val profileSchemaVersion: Int,
    @ColumnInfo(name = "palette_code")
    val paletteCode: String,
    @ColumnInfo(name = "hue_degrees")
    val hueDegrees: Int,
    @ColumnInfo(name = "row_density_code")
    val rowDensityCode: String,
    @ColumnInfo(name = "avatar_mask_code")
    val avatarMaskCode: String,
    @ColumnInfo(name = "navigation_style_code")
    val navigationStyleCode: String,
    @ColumnInfo(name = "bubble_geometry_code")
    val bubbleGeometryCode: String,
    @ColumnInfo(name = "reduced_motion")
    val reducedMotion: Int,
    @ColumnInfo(name = "high_contrast")
    val highContrast: Int,
    @ColumnInfo(name = "wallpaper_dim_permill")
    val wallpaperDimPermill: Int,
    @ColumnInfo(name = "focal_x_permill")
    val focalXPermill: Int,
    @ColumnInfo(name = "focal_y_permill")
    val focalYPermill: Int,
    @ColumnInfo(name = "revision")
    val revision: Long,
    @ColumnInfo(name = "created_timestamp_ms")
    val createdTimestampMillis: Long,
    @ColumnInfo(name = "updated_timestamp_ms")
    val updatedTimestampMillis: Long,
) {
    override fun toString(): String = "AppearanceProfileEntity(REDACTED)"
}

internal fun NewAppearanceProfile.toEntity(): AppearanceProfileEntity = AppearanceProfileEntity(
    name = name.value,
    normalizedName = name.normalizedKey,
    profileSchemaVersion = values.schemaVersion,
    paletteCode = values.palette.storageCode,
    hueDegrees = values.hueDegrees,
    rowDensityCode = values.rowDensity.storageCode,
    avatarMaskCode = values.avatarMask.storageCode,
    navigationStyleCode = values.navigationStyle.storageCode,
    bubbleGeometryCode = values.bubbleGeometry.storageCode,
    reducedMotion = values.reducedMotion.toStorageInt(),
    highContrast = values.highContrast.toStorageInt(),
    wallpaperDimPermill = values.wallpaperDimPermill,
    focalXPermill = values.focalXPermill,
    focalYPermill = values.focalYPermill,
    revision = INITIAL_APPEARANCE_PROFILE_REVISION,
    createdTimestampMillis = createdTimestampMillis,
    updatedTimestampMillis = updatedTimestampMillis,
)

internal fun AppearanceProfileEdit.toUpdatedEntity(
    current: AppearanceProfile,
): AppearanceProfileEntity {
    require(id == current.id) { "An appearance edit must preserve its profile ID" }
    require(current.revision.value < Long.MAX_VALUE) { "An appearance revision cannot overflow" }
    return AppearanceProfileEntity(
        profileId = id.value,
        name = name.value,
        normalizedName = name.normalizedKey,
        profileSchemaVersion = values.schemaVersion,
        paletteCode = values.palette.storageCode,
        hueDegrees = values.hueDegrees,
        rowDensityCode = values.rowDensity.storageCode,
        avatarMaskCode = values.avatarMask.storageCode,
        navigationStyleCode = values.navigationStyle.storageCode,
        bubbleGeometryCode = values.bubbleGeometry.storageCode,
        reducedMotion = values.reducedMotion.toStorageInt(),
        highContrast = values.highContrast.toStorageInt(),
        wallpaperDimPermill = values.wallpaperDimPermill,
        focalXPermill = values.focalXPermill,
        focalYPermill = values.focalYPermill,
        revision = current.revision.value + 1L,
        createdTimestampMillis = current.createdTimestampMillis,
        updatedTimestampMillis = updatedTimestampMillis,
    )
}

internal fun AppearanceProfileEntity.toDomain(): AppearanceProfile {
    val values = AppearanceProfileValues(
        schemaVersion = profileSchemaVersion,
        palette = requireNotNull(AppearancePalette.fromStorageCode(paletteCode)) {
            "Unknown stored appearance palette"
        },
        hueDegrees = hueDegrees,
        rowDensity = requireNotNull(AppearanceRowDensity.fromStorageCode(rowDensityCode)) {
            "Unknown stored appearance row density"
        },
        avatarMask = requireNotNull(AppearanceAvatarMask.fromStorageCode(avatarMaskCode)) {
            "Unknown stored appearance avatar mask"
        },
        navigationStyle = requireNotNull(
            AppearanceNavigationStyle.fromStorageCode(navigationStyleCode),
        ) { "Unknown stored appearance navigation style" },
        bubbleGeometry = requireNotNull(AppearanceBubbleGeometry.fromStorageCode(bubbleGeometryCode)) {
            "Unknown stored appearance bubble geometry"
        },
        reducedMotion = reducedMotion.toStorageBoolean(),
        highContrast = highContrast.toStorageBoolean(),
        wallpaperDimPermill = wallpaperDimPermill,
        focalXPermill = focalXPermill,
        focalYPermill = focalYPermill,
    )
    return AppearanceProfile(
        id = AppearanceProfileId(profileId),
        name = AppearanceProfileName.fromStored(name, normalizedName),
        values = values,
        revision = AppearanceRevision(revision),
        createdTimestampMillis = createdTimestampMillis,
        updatedTimestampMillis = updatedTimestampMillis,
    )
}

private fun Boolean.toStorageInt(): Int = if (this) 1 else 0

private fun Int.toStorageBoolean(): Boolean = when (this) {
    0 -> false
    1 -> true
    else -> throw IllegalArgumentException("A stored appearance boolean must be zero or one")
}

internal const val INITIAL_APPEARANCE_PROFILE_REVISION: Long = 1L
