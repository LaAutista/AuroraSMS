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
}

enum class AppearanceLimit {
    PROFILE_COUNT,
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
}
