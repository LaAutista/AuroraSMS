// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import kotlinx.coroutines.flow.Flow

enum class AppearanceStorageOperation {
    SNAPSHOT,
    CREATE,
    UPDATE,
    ACTIVATE,
    RESET_ACTIVE,
    DELETE,
    SET_OVERRIDE,
    RESET_OVERRIDE,
    SET_WALLPAPER,
    RESET_WALLPAPER,
    WALLPAPER_MEDIA_REFERENCES,
}

enum class AppearanceLimit {
    PROFILE_COUNT,
    WALLPAPER_MEDIA_COUNT,
}

sealed interface AppearanceRepositoryResult<out T> {
    data class Success<T>(val value: T) : AppearanceRepositoryResult<T> {
        override fun toString(): String = "AppearanceRepositoryResult.Success(REDACTED)"
    }

    data object NotFound : AppearanceRepositoryResult<Nothing>

    data object Conflict : AppearanceRepositoryResult<Nothing>

    data object StaleWrite : AppearanceRepositoryResult<Nothing>

    data object InvalidTimestamp : AppearanceRepositoryResult<Nothing>

    data object CorruptData : AppearanceRepositoryResult<Nothing>

    data class LimitExceeded(val limit: AppearanceLimit) : AppearanceRepositoryResult<Nothing>

    data class StorageFailure(
        val operation: AppearanceStorageOperation,
    ) : AppearanceRepositoryResult<Nothing>
}

interface AppearanceProfileRepository {
    /** Immediately begins with [AppearanceSnapshot.Empty], then emits validated durable snapshots. */
    val snapshots: Flow<AppearanceSnapshot>

    /** Creates a profile and optionally activates it in the same database transaction. */
    suspend fun create(
        profile: NewAppearanceProfile,
        activate: Boolean = false,
    ): AppearanceRepositoryResult<AppearanceProfile>

    /** Optimistically updates a profile and optionally activates it in the same transaction. */
    suspend fun update(
        edit: AppearanceProfileEdit,
        expectedRevision: AppearanceRevision,
        activate: Boolean = false,
    ): AppearanceRepositoryResult<AppearanceProfile>

    suspend fun activate(id: AppearanceProfileId): AppearanceRepositoryResult<Unit>

    /** Selects the code-owned canonical default without deleting any named profile. */
    suspend fun resetActive(): AppearanceRepositoryResult<Unit>

    /**
     * Optimistically deletes a named profile; deleting the active profile atomically falls back
     * to canonical.
     */
    suspend fun delete(
        id: AppearanceProfileId,
        expectedRevision: AppearanceRevision,
    ): AppearanceRepositoryResult<Unit>

    /** Observes only the requested durable scope; its first value is authoritative for that scope. */
    fun observeOverride(scope: AppearanceScope): Flow<AppearanceOverride?>

    /**
     * Selects a named profile for one scope. A null [expectedRevision] means the caller observed
     * inherited state; an existing assignment requires its exact optimistic revision.
     */
    suspend fun setOverride(
        scope: AppearanceScope,
        profileId: AppearanceProfileId,
        expectedRevision: AppearanceOverrideRevision?,
    ): AppearanceRepositoryResult<AppearanceOverride>

    /** Resets one scope to inherited state without changing any other appearance selection. */
    suspend fun resetOverride(
        scope: AppearanceScope,
        expectedRevision: AppearanceOverrideRevision?,
    ): AppearanceRepositoryResult<Unit>
}

/** Durable target-specific static-wallpaper assignments, independent from profile references. */
interface AppearanceWallpaperRepository {
    /** Observes only the requested scope; the first row-or-null is authoritative for that scope. */
    fun observeWallpaper(scope: AppearanceScope): Flow<AppearanceWallpaperAssignment?>

    /**
     * Creates or updates one static private-media assignment using optimistic target revision.
     * A null [expectedRevision] means the caller observed inherited state and requires no row.
     */
    suspend fun setWallpaper(
        scope: AppearanceScope,
        mediaId: AppearanceWallpaperMediaId,
        dimPermill: Int,
        focalXPermill: Int,
        focalYPermill: Int,
        expectedRevision: AppearanceWallpaperRevision?,
    ): AppearanceRepositoryResult<AppearanceWallpaperMutation>

    /** Revision-checks and removes only this wallpaper assignment. */
    suspend fun resetWallpaper(
        scope: AppearanceScope,
        expectedRevision: AppearanceWallpaperRevision?,
    ): AppearanceRepositoryResult<AppearanceWallpaperMutation>

    /**
     * Returns the exact bounded media-ID set that would remain after a successful set, without
     * changing durable state. The target's current revision is checked in the same validated read
     * transaction as the full assignment snapshot.
     */
    suspend fun prospectiveMediaIdsForSet(
        scope: AppearanceScope,
        mediaId: AppearanceWallpaperMediaId,
        expectedRevision: AppearanceWallpaperRevision?,
    ): AppearanceRepositoryResult<Set<AppearanceWallpaperMediaId>>

    /** Returns at most 128 validated distinct IDs for conservative app-private media GC. */
    suspend fun referencedMediaIds(): AppearanceRepositoryResult<Set<AppearanceWallpaperMediaId>>
}
