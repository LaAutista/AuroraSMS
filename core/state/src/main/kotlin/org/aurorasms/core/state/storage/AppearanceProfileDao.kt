// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface AppearanceProfileDao {
    @Query(
        "SELECT * FROM appearance_profiles " +
            "ORDER BY normalized_name ASC, profile_id ASC",
    )
    suspend fun loadProfiles(): List<AppearanceProfileEntity>

    @Query("SELECT * FROM appearance_profiles WHERE profile_id = :profileId LIMIT 1")
    suspend fun findProfile(profileId: Long): AppearanceProfileEntity?

    @Query("SELECT COUNT(*) FROM appearance_profiles")
    suspend fun profileCount(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProfile(entity: AppearanceProfileEntity): Long

    @Query(
        """
        UPDATE appearance_profiles
        SET name = :name,
            normalized_name = :normalizedName,
            profile_schema_version = :profileSchemaVersion,
            palette_code = :paletteCode,
            hue_degrees = :hueDegrees,
            row_density_code = :rowDensityCode,
            avatar_mask_code = :avatarMaskCode,
            navigation_style_code = :navigationStyleCode,
            bubble_geometry_code = :bubbleGeometryCode,
            reduced_motion = :reducedMotion,
            high_contrast = :highContrast,
            wallpaper_dim_permill = :wallpaperDimPermill,
            focal_x_permill = :focalXPermill,
            focal_y_permill = :focalYPermill,
            revision = :newRevision,
            updated_timestamp_ms = :updatedTimestampMillis
        WHERE profile_id = :profileId
          AND revision = :expectedRevision
        """,
    )
    suspend fun updateProfileIfRevision(
        profileId: Long,
        name: String,
        normalizedName: String,
        profileSchemaVersion: Int,
        paletteCode: String,
        hueDegrees: Int,
        rowDensityCode: String,
        avatarMaskCode: String,
        navigationStyleCode: String,
        bubbleGeometryCode: String,
        reducedMotion: Int,
        highContrast: Int,
        wallpaperDimPermill: Int,
        focalXPermill: Int,
        focalYPermill: Int,
        newRevision: Long,
        updatedTimestampMillis: Long,
        expectedRevision: Long,
    ): Int

    @Query(
        "DELETE FROM appearance_profiles " +
            "WHERE profile_id = :profileId AND revision = :expectedRevision",
    )
    suspend fun deleteProfile(profileId: Long, expectedRevision: Long): Int

    @Query(
        "SELECT * FROM appearance_selection WHERE singleton_id = " +
            "$APPEARANCE_SELECTION_SINGLETON_ID LIMIT 1",
    )
    fun observeSelection(): Flow<AppearanceSelectionEntity?>

    @Query(
        "SELECT * FROM appearance_selection WHERE singleton_id = " +
            "$APPEARANCE_SELECTION_SINGLETON_ID LIMIT 1",
    )
    suspend fun loadSelection(): AppearanceSelectionEntity?

    @Query("SELECT COUNT(*) FROM appearance_selection")
    suspend fun selectionCount(): Int

    @Query(
        """
        UPDATE appearance_selection
        SET active_profile_id = :profileId,
            snapshot_revision = snapshot_revision + 1
        WHERE singleton_id = $APPEARANCE_SELECTION_SINGLETON_ID
        """,
    )
    suspend fun setActiveProfile(profileId: Long?): Int

    @Query(
        """
        UPDATE appearance_selection
        SET snapshot_revision = snapshot_revision + 1
        WHERE singleton_id = $APPEARANCE_SELECTION_SINGLETON_ID
        """,
    )
    suspend fun bumpSnapshotRevision(): Int
}
