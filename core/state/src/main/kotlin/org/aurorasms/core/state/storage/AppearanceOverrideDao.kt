// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.aurorasms.core.state.APPEARANCE_WALLPAPER_MEDIA_ENUMERATION_LIMIT

@Dao
internal interface AppearanceOverrideDao {
    @Query(
        "SELECT * FROM appearance_override_revision_sequence " +
            "WHERE singleton_id = $APPEARANCE_OVERRIDE_SEQUENCE_SINGLETON_ID LIMIT 1",
    )
    suspend fun loadRevisionSequence(): AppearanceOverrideSequenceEntity?

    @Query("SELECT COUNT(*) FROM appearance_override_revision_sequence")
    suspend fun revisionSequenceCount(): Int

    @Query(
        "SELECT MAX(revision) FROM (" +
            "SELECT revision FROM appearance_screen_overrides " +
            "UNION ALL " +
            "SELECT revision FROM appearance_conversation_overrides " +
            "UNION ALL " +
            "SELECT revision FROM appearance_screen_wallpapers " +
            "UNION ALL " +
            "SELECT revision FROM appearance_conversation_wallpapers" +
            ")",
    )
    suspend fun maximumStoredOverrideRevision(): Long?

    @Query(
        """
        UPDATE appearance_override_revision_sequence
        SET last_allocated_revision = :newRevision
        WHERE singleton_id = $APPEARANCE_OVERRIDE_SEQUENCE_SINGLETON_ID
          AND last_allocated_revision = :expectedRevision
        """,
    )
    suspend fun updateRevisionSequenceIfUnchanged(
        expectedRevision: Long,
        newRevision: Long,
    ): Int

    @Query(
        "SELECT * FROM appearance_screen_overrides " +
            "WHERE screen_code = :screenCode LIMIT 1",
    )
    fun observeScreenOverride(screenCode: String): Flow<AppearanceScreenOverrideEntity?>

    @Query(
        "SELECT * FROM appearance_conversation_overrides " +
            "WHERE participant_set_key = :participantSetKey LIMIT 1",
    )
    fun observeConversationOverride(
        participantSetKey: String,
    ): Flow<AppearanceConversationOverrideEntity?>

    @Query(
        "SELECT * FROM appearance_screen_overrides " +
            "WHERE screen_code = :screenCode LIMIT 1",
    )
    suspend fun findScreenOverride(screenCode: String): AppearanceScreenOverrideEntity?

    @Query(
        "SELECT * FROM appearance_conversation_overrides " +
            "WHERE participant_set_key = :participantSetKey LIMIT 1",
    )
    suspend fun findConversationOverride(
        participantSetKey: String,
    ): AppearanceConversationOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertScreenOverride(entity: AppearanceScreenOverrideEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConversationOverride(entity: AppearanceConversationOverrideEntity)

    @Query(
        """
        UPDATE appearance_screen_overrides
        SET profile_id = :profileId,
            revision = :newRevision
        WHERE screen_code = :screenCode
          AND revision = :expectedRevision
        """,
    )
    suspend fun updateScreenOverrideIfRevision(
        screenCode: String,
        profileId: Long,
        newRevision: Long,
        expectedRevision: Long,
    ): Int

    @Query(
        """
        UPDATE appearance_conversation_overrides
        SET provider_thread_id = :providerThreadId,
            profile_id = :profileId,
            revision = :newRevision
        WHERE participant_set_key = :participantSetKey
          AND revision = :expectedRevision
        """,
    )
    suspend fun updateConversationOverrideIfRevision(
        participantSetKey: String,
        providerThreadId: Long,
        profileId: Long,
        newRevision: Long,
        expectedRevision: Long,
    ): Int

    @Query(
        "DELETE FROM appearance_screen_overrides " +
            "WHERE screen_code = :screenCode AND revision = :expectedRevision",
    )
    suspend fun deleteScreenOverrideIfRevision(
        screenCode: String,
        expectedRevision: Long,
    ): Int

    @Query(
        "DELETE FROM appearance_conversation_overrides " +
            "WHERE participant_set_key = :participantSetKey AND revision = :expectedRevision",
    )
    suspend fun deleteConversationOverrideIfRevision(
        participantSetKey: String,
        expectedRevision: Long,
    ): Int

    @Query(
        "SELECT * FROM appearance_screen_wallpapers " +
            "WHERE screen_code = :screenCode LIMIT 1",
    )
    fun observeScreenWallpaper(screenCode: String): Flow<AppearanceScreenWallpaperEntity?>

    @Query(
        "SELECT * FROM appearance_conversation_wallpapers " +
            "WHERE participant_set_key = :participantSetKey LIMIT 1",
    )
    fun observeConversationWallpaper(
        participantSetKey: String,
    ): Flow<AppearanceConversationWallpaperEntity?>

    @Query(
        "SELECT * FROM appearance_screen_wallpapers " +
            "WHERE screen_code = :screenCode LIMIT 1",
    )
    suspend fun findScreenWallpaper(screenCode: String): AppearanceScreenWallpaperEntity?

    @Query(
        "SELECT * FROM appearance_conversation_wallpapers " +
            "WHERE participant_set_key = :participantSetKey LIMIT 1",
    )
    suspend fun findConversationWallpaper(
        participantSetKey: String,
    ): AppearanceConversationWallpaperEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertScreenWallpaper(entity: AppearanceScreenWallpaperEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConversationWallpaper(entity: AppearanceConversationWallpaperEntity)

    @Query(
        """
        UPDATE appearance_screen_wallpapers
        SET media_kind_code = :mediaKindCode,
            media_id = :mediaId,
            dim_permill = :dimPermill,
            focal_x_permill = :focalXPermill,
            focal_y_permill = :focalYPermill,
            revision = :newRevision
        WHERE screen_code = :screenCode
          AND revision = :expectedRevision
        """,
    )
    suspend fun updateScreenWallpaperIfRevision(
        screenCode: String,
        mediaKindCode: String,
        mediaId: String,
        dimPermill: Int,
        focalXPermill: Int,
        focalYPermill: Int,
        newRevision: Long,
        expectedRevision: Long,
    ): Int

    @Query(
        """
        UPDATE appearance_conversation_wallpapers
        SET provider_thread_id = :providerThreadId,
            media_kind_code = :mediaKindCode,
            media_id = :mediaId,
            dim_permill = :dimPermill,
            focal_x_permill = :focalXPermill,
            focal_y_permill = :focalYPermill,
            revision = :newRevision
        WHERE participant_set_key = :participantSetKey
          AND revision = :expectedRevision
        """,
    )
    suspend fun updateConversationWallpaperIfRevision(
        participantSetKey: String,
        providerThreadId: Long,
        mediaKindCode: String,
        mediaId: String,
        dimPermill: Int,
        focalXPermill: Int,
        focalYPermill: Int,
        newRevision: Long,
        expectedRevision: Long,
    ): Int

    @Query(
        "DELETE FROM appearance_screen_wallpapers " +
            "WHERE screen_code = :screenCode AND revision = :expectedRevision",
    )
    suspend fun deleteScreenWallpaperIfRevision(
        screenCode: String,
        expectedRevision: Long,
    ): Int

    @Query(
        "DELETE FROM appearance_conversation_wallpapers " +
            "WHERE participant_set_key = :participantSetKey AND revision = :expectedRevision",
    )
    suspend fun deleteConversationWallpaperIfRevision(
        participantSetKey: String,
        expectedRevision: Long,
    ): Int

    @Query(
        "SELECT (" +
            "SELECT COUNT(*) FROM appearance_screen_wallpapers " +
            "WHERE media_kind_code = :mediaKindCode AND media_id = :mediaId" +
            ") + (" +
            "SELECT COUNT(*) FROM appearance_conversation_wallpapers " +
            "WHERE media_kind_code = :mediaKindCode AND media_id = :mediaId" +
            ")",
    )
    suspend fun wallpaperMediaReferenceCount(
        mediaKindCode: String,
        mediaId: String,
    ): Int

    @Query(
        "SELECT media_kind_code, media_id FROM (" +
            "SELECT media_kind_code, media_id FROM appearance_screen_wallpapers " +
            "UNION " +
            "SELECT media_kind_code, media_id FROM appearance_conversation_wallpapers" +
            ") ORDER BY media_kind_code, media_id " +
            "LIMIT $APPEARANCE_WALLPAPER_MEDIA_ENUMERATION_LIMIT",
    )
    suspend fun loadReferencedWallpaperMedia(): List<AppearanceWallpaperMediaRecord>

    @Query(
        """
        SELECT
            EXISTS(
                SELECT 1
                FROM appearance_screen_wallpapers
                WHERE typeof(screen_code) != 'text'
                   OR screen_code != 'global_thread'
                   OR typeof(media_kind_code) != 'text'
                   OR media_kind_code != 'static_raster_v1'
                   OR typeof(media_id) != 'text'
                   OR length(media_id) != 74
                   OR substr(media_id, 1, 10) != 'sha256-v1:'
                   OR substr(media_id, 11) GLOB '*[^0-9a-f]*'
                   OR typeof(dim_permill) != 'integer'
                   OR dim_permill < 350
                   OR dim_permill > 900
                   OR typeof(focal_x_permill) != 'integer'
                   OR focal_x_permill < 0
                   OR focal_x_permill > 1000
                   OR typeof(focal_y_permill) != 'integer'
                   OR focal_y_permill < 0
                   OR focal_y_permill > 1000
                   OR typeof(revision) != 'integer'
                   OR revision <= 0
            )
            OR EXISTS(
                SELECT 1
                FROM appearance_conversation_wallpapers
                WHERE typeof(participant_set_key) != 'text'
                   OR length(participant_set_key) != 74
                   OR substr(participant_set_key, 1, 10) != 'sha256-v1:'
                   OR substr(participant_set_key, 11) GLOB '*[^0-9a-f]*'
                   OR typeof(provider_thread_id) != 'integer'
                   OR provider_thread_id <= 0
                   OR typeof(media_kind_code) != 'text'
                   OR media_kind_code != 'static_raster_v1'
                   OR typeof(media_id) != 'text'
                   OR length(media_id) != 74
                   OR substr(media_id, 1, 10) != 'sha256-v1:'
                   OR substr(media_id, 11) GLOB '*[^0-9a-f]*'
                   OR typeof(dim_permill) != 'integer'
                   OR dim_permill < 350
                   OR dim_permill > 900
                   OR typeof(focal_x_permill) != 'integer'
                   OR focal_x_permill < 0
                   OR focal_x_permill > 1000
                   OR typeof(focal_y_permill) != 'integer'
                   OR focal_y_permill < 0
                   OR focal_y_permill > 1000
                   OR typeof(revision) != 'integer'
                   OR revision <= 0
            )
        """,
    )
    suspend fun invalidWallpaperAssignmentExists(): Boolean
}
