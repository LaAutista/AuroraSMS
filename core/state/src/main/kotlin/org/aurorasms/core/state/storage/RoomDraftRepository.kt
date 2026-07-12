// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import kotlinx.coroutines.CancellationException
import org.aurorasms.core.state.Draft
import org.aurorasms.core.state.DraftId
import org.aurorasms.core.state.DraftRepository
import org.aurorasms.core.state.DraftRepositoryResult
import org.aurorasms.core.state.DraftRevision
import org.aurorasms.core.state.DraftStorageOperation
import org.aurorasms.core.state.NewDraft

class RoomDraftRepository internal constructor(
    private val dao: DraftDao,
) : DraftRepository {
    constructor(database: AuroraStateDatabase) : this(database.draftDao())

    override suspend fun create(draft: NewDraft): DraftRepositoryResult<Draft> =
        try {
            val id = dao.insert(draft.toEntity())
            if (id <= 0L) {
                DraftRepositoryResult.CorruptData
            } else {
                DraftRepositoryResult.Success(
                    Draft(
                        id = DraftId(id),
                        identity = draft.identity,
                        body = draft.body,
                        subject = draft.subject,
                        createdTimestampMillis = draft.createdTimestampMillis,
                        updatedTimestampMillis = draft.updatedTimestampMillis,
                    ),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: SQLiteConstraintException) {
            DraftRepositoryResult.Conflict
        } catch (_: SQLiteException) {
            DraftRepositoryResult.StorageFailure(DraftStorageOperation.CREATE)
        } catch (_: IllegalStateException) {
            DraftRepositoryResult.StorageFailure(DraftStorageOperation.CREATE)
        }

    override suspend fun read(id: DraftId): DraftRepositoryResult<Draft> {
        val entity = try {
            dao.findById(id.value)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: SQLiteException) {
            return DraftRepositoryResult.StorageFailure(DraftStorageOperation.READ)
        } catch (_: IllegalStateException) {
            return DraftRepositoryResult.StorageFailure(DraftStorageOperation.READ)
        } catch (_: IllegalArgumentException) {
            return DraftRepositoryResult.CorruptData
        } ?: return DraftRepositoryResult.NotFound

        return try {
            DraftRepositoryResult.Success(entity.toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IllegalArgumentException) {
            DraftRepositoryResult.CorruptData
        } catch (_: IllegalStateException) {
            DraftRepositoryResult.CorruptData
        }
    }

    override suspend fun update(
        draft: Draft,
        expectedRevision: DraftRevision,
    ): DraftRepositoryResult<Draft> {
        if (draft.updatedTimestampMillis <= expectedRevision.updatedTimestampMillis) {
            return DraftRepositoryResult.InvalidRevision
        }
        return try {
            val entity = draft.toEntity()
            when (
                dao.updateIfUnchanged(
                    draftId = entity.draftId,
                    providerThreadId = entity.providerThreadId,
                    participantSetKey = entity.participantSetKey,
                    body = entity.body,
                    subject = entity.subject,
                    createdTimestampMillis = entity.createdTimestampMillis,
                    updatedTimestampMillis = entity.updatedTimestampMillis,
                    expectedUpdatedTimestampMillis = expectedRevision.updatedTimestampMillis,
                )
            ) {
                0 -> missingOrStale(draft.id)
                1 -> DraftRepositoryResult.Success(draft)
                else -> DraftRepositoryResult.CorruptData
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: SQLiteConstraintException) {
            DraftRepositoryResult.Conflict
        } catch (_: SQLiteException) {
            DraftRepositoryResult.StorageFailure(DraftStorageOperation.UPDATE)
        } catch (_: IllegalStateException) {
            DraftRepositoryResult.StorageFailure(DraftStorageOperation.UPDATE)
        }
    }

    override suspend fun delete(id: DraftId): DraftRepositoryResult<Unit> =
        try {
            when (dao.deleteById(id.value)) {
                0 -> DraftRepositoryResult.NotFound
                1 -> DraftRepositoryResult.Success(Unit)
                else -> DraftRepositoryResult.CorruptData
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: SQLiteException) {
            DraftRepositoryResult.StorageFailure(DraftStorageOperation.DELETE)
        } catch (_: IllegalStateException) {
            DraftRepositoryResult.StorageFailure(DraftStorageOperation.DELETE)
        }

    private suspend fun missingOrStale(id: DraftId): DraftRepositoryResult<Draft> =
        if (dao.findById(id.value) == null) {
            DraftRepositoryResult.NotFound
        } else {
            DraftRepositoryResult.StaleWrite
        }
}
