// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

/** Operation label that does not expose draft content or database details. */
enum class DraftStorageOperation {
    CREATE,
    READ,
    UPDATE,
    DELETE,
}

/** Explicit repository result; storage failures never masquerade as missing data. */
sealed interface DraftRepositoryResult<out T> {
    data class Success<T>(val value: T) : DraftRepositoryResult<T> {
        override fun toString(): String = "DraftRepositoryResult.Success(REDACTED)"
    }

    data object NotFound : DraftRepositoryResult<Nothing>

    data object Conflict : DraftRepositoryResult<Nothing>

    data object StaleWrite : DraftRepositoryResult<Nothing>

    data object InvalidRevision : DraftRepositoryResult<Nothing>

    data object CorruptData : DraftRepositoryResult<Nothing>

    data class StorageFailure(
        val operation: DraftStorageOperation,
    ) : DraftRepositoryResult<Nothing>
}

interface DraftRepository {
    suspend fun create(draft: NewDraft): DraftRepositoryResult<Draft>

    suspend fun read(id: DraftId): DraftRepositoryResult<Draft>

    suspend fun update(
        draft: Draft,
        expectedRevision: DraftRevision,
    ): DraftRepositoryResult<Draft>

    suspend fun delete(id: DraftId): DraftRepositoryResult<Unit>
}
