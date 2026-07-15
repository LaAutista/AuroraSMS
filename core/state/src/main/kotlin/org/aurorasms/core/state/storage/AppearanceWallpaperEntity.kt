// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.AppearanceParticipantSetKey
import org.aurorasms.core.state.AppearanceScope
import org.aurorasms.core.state.AppearanceScreenScope
import org.aurorasms.core.state.AppearanceWallpaperAssignment
import org.aurorasms.core.state.AppearanceWallpaperMediaId
import org.aurorasms.core.state.AppearanceWallpaperMediaKind
import org.aurorasms.core.state.AppearanceWallpaperRevision

@Entity(
    tableName = "appearance_screen_wallpapers",
    indices = [Index(value = ["media_kind_code", "media_id"])],
)
internal data class AppearanceScreenWallpaperEntity(
    @PrimaryKey
    @ColumnInfo(name = "screen_code")
    val screenCode: String,
    @ColumnInfo(name = "media_kind_code")
    val mediaKindCode: String,
    @ColumnInfo(name = "media_id")
    val mediaId: String,
    @ColumnInfo(name = "dim_permill")
    val dimPermill: Int,
    @ColumnInfo(name = "focal_x_permill")
    val focalXPermill: Int,
    @ColumnInfo(name = "focal_y_permill")
    val focalYPermill: Int,
    @ColumnInfo(name = "revision")
    val revision: Long,
) {
    init {
        require(AppearanceScreenScope.fromStorageCode(screenCode) == AppearanceScreenScope.GLOBAL_THREAD) {
            "Unknown stored appearance wallpaper screen scope"
        }
        validateStoredWallpaper(
            mediaKindCode = mediaKindCode,
            mediaId = mediaId,
            dimPermill = dimPermill,
            focalXPermill = focalXPermill,
            focalYPermill = focalYPermill,
            revision = revision,
        )
    }

    override fun toString(): String = "AppearanceScreenWallpaperEntity(REDACTED)"
}

@Entity(
    tableName = "appearance_conversation_wallpapers",
    indices = [
        Index(value = ["provider_thread_id"]),
        Index(value = ["media_kind_code", "media_id"]),
    ],
)
internal data class AppearanceConversationWallpaperEntity(
    @PrimaryKey
    @ColumnInfo(name = "participant_set_key")
    val participantSetKey: String,
    @ColumnInfo(name = "provider_thread_id")
    val providerThreadId: Long,
    @ColumnInfo(name = "media_kind_code")
    val mediaKindCode: String,
    @ColumnInfo(name = "media_id")
    val mediaId: String,
    @ColumnInfo(name = "dim_permill")
    val dimPermill: Int,
    @ColumnInfo(name = "focal_x_permill")
    val focalXPermill: Int,
    @ColumnInfo(name = "focal_y_permill")
    val focalYPermill: Int,
    @ColumnInfo(name = "revision")
    val revision: Long,
) {
    init {
        AppearanceParticipantSetKey.fromStorageValue(participantSetKey)
        ProviderThreadId(providerThreadId)
        validateStoredWallpaper(
            mediaKindCode = mediaKindCode,
            mediaId = mediaId,
            dimPermill = dimPermill,
            focalXPermill = focalXPermill,
            focalYPermill = focalYPermill,
            revision = revision,
        )
    }

    override fun toString(): String = "AppearanceConversationWallpaperEntity(REDACTED)"
}

internal data class AppearanceWallpaperMediaRecord(
    @ColumnInfo(name = "media_kind_code")
    val mediaKindCode: String,
    @ColumnInfo(name = "media_id")
    val mediaId: String,
) {
    override fun toString(): String = "AppearanceWallpaperMediaRecord(REDACTED)"
}

internal fun AppearanceScreenWallpaperEntity.toDomain(): AppearanceWallpaperAssignment =
    toWallpaperAssignment(
        scope = AppearanceScope.Screen(
            checkNotNull(AppearanceScreenScope.fromStorageCode(screenCode)),
        ),
    )

internal fun AppearanceConversationWallpaperEntity.toDomain(
    requestedScope: AppearanceScope.Conversation? = null,
): AppearanceWallpaperAssignment {
    val storedKey = AppearanceParticipantSetKey.fromStorageValue(participantSetKey)
    require(requestedScope == null || requestedScope.participantSetKey == storedKey) {
        "A requested wallpaper conversation scope does not match its stored participant key"
    }
    return toWallpaperAssignment(
        scope = requestedScope ?: AppearanceScope.Conversation(
            participantSetKey = storedKey,
            providerThreadId = ProviderThreadId(providerThreadId),
        ),
    )
}

internal fun AppearanceWallpaperMediaRecord.toDomain(): AppearanceWallpaperMediaId {
    require(
        AppearanceWallpaperMediaKind.fromStorageCode(mediaKindCode) ==
            AppearanceWallpaperMediaKind.STATIC_RASTER_V1,
    ) { "Unknown stored appearance wallpaper media kind" }
    return AppearanceWallpaperMediaId.fromStorageValue(mediaId)
}

private fun AppearanceScreenWallpaperEntity.toWallpaperAssignment(
    scope: AppearanceScope,
): AppearanceWallpaperAssignment = AppearanceWallpaperAssignment(
    scope = scope,
    mediaKind = requireNotNull(AppearanceWallpaperMediaKind.fromStorageCode(mediaKindCode)) {
        "Unknown stored appearance wallpaper media kind"
    },
    mediaId = AppearanceWallpaperMediaId.fromStorageValue(mediaId),
    dimPermill = dimPermill,
    focalXPermill = focalXPermill,
    focalYPermill = focalYPermill,
    revision = AppearanceWallpaperRevision(revision),
)

private fun AppearanceConversationWallpaperEntity.toWallpaperAssignment(
    scope: AppearanceScope,
): AppearanceWallpaperAssignment = AppearanceWallpaperAssignment(
    scope = scope,
    mediaKind = requireNotNull(AppearanceWallpaperMediaKind.fromStorageCode(mediaKindCode)) {
        "Unknown stored appearance wallpaper media kind"
    },
    mediaId = AppearanceWallpaperMediaId.fromStorageValue(mediaId),
    dimPermill = dimPermill,
    focalXPermill = focalXPermill,
    focalYPermill = focalYPermill,
    revision = AppearanceWallpaperRevision(revision),
)

private fun validateStoredWallpaper(
    mediaKindCode: String,
    mediaId: String,
    dimPermill: Int,
    focalXPermill: Int,
    focalYPermill: Int,
    revision: Long,
) {
    val kind = requireNotNull(AppearanceWallpaperMediaKind.fromStorageCode(mediaKindCode)) {
        "Unknown stored appearance wallpaper media kind"
    }
    AppearanceWallpaperAssignment(
        scope = AppearanceScope.Screen(AppearanceScreenScope.GLOBAL_THREAD),
        mediaKind = kind,
        mediaId = AppearanceWallpaperMediaId.fromStorageValue(mediaId),
        dimPermill = dimPermill,
        focalXPermill = focalXPermill,
        focalYPermill = focalYPermill,
        revision = AppearanceWallpaperRevision(revision),
    )
}
