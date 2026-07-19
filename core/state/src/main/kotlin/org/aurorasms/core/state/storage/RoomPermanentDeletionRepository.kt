// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.MAXIMUM_PERMANENT_DELETION_OPERATIONS
import org.aurorasms.core.state.PermanentDeletionId
import org.aurorasms.core.state.PermanentDeletionOperation
import org.aurorasms.core.state.PermanentDeletionPhase
import org.aurorasms.core.state.PermanentDeletionRepository
import org.aurorasms.core.state.PermanentDeletionRequest
import org.aurorasms.core.state.PermanentDeletionResult
import org.aurorasms.core.state.PermanentDeletionReviewReason
import org.aurorasms.core.state.PermanentDeletionRevision
import org.aurorasms.core.state.PermanentDeletionStorageOperation
import org.aurorasms.core.state.PermanentDeletionTarget

class RoomPermanentDeletionRepository(
    private val database: AuroraStateDatabase,
) : PermanentDeletionRepository {
    private val dao = database.permanentDeletionDao()

    override suspend fun create(
        request: PermanentDeletionRequest,
    ): PermanentDeletionResult<PermanentDeletionOperation> =
        store(PermanentDeletionStorageOperation.CREATE) {
            database.withTransaction {
                val count = dao.count()
                if (count < 0 || count > MAXIMUM_PERMANENT_DELETION_OPERATIONS) {
                    return@withTransaction PermanentDeletionResult.CorruptData
                }
                if (count == MAXIMUM_PERMANENT_DELETION_OPERATIONS) {
                    return@withTransaction PermanentDeletionResult.LimitExceeded
                }
                if (dao.findByThread(request.target.providerThreadId.value) != null) {
                    return@withTransaction PermanentDeletionResult.StaleWrite
                }
                if (dao.hasConflictingAction(request.target.providerThreadId.value)) {
                    return@withTransaction PermanentDeletionResult.ConflictingAction
                }
                val draftEntity = if (request.target is PermanentDeletionTarget.Thread) {
                    dao.findDraftByThread(request.target.providerThreadId.value)
                } else null
                val draft = draftEntity?.toDomainOrNull()
                if (draftEntity != null && draft == null) {
                    return@withTransaction PermanentDeletionResult.CorruptData
                }
                val target = when (val requested = request.target) {
                    is PermanentDeletionTarget.Message -> requested
                    is PermanentDeletionTarget.Thread -> requested.copy(
                        draftId = draft?.id,
                        draftRevision = draft?.revision,
                    )
                }
                val id = dao.insert(request.toEntity(target))
                dao.findById(id).toPermanentDeletionResult()
            }
        }

    override suspend fun read(
        id: PermanentDeletionId,
    ): PermanentDeletionResult<PermanentDeletionOperation> =
        store(PermanentDeletionStorageOperation.READ) { dao.findById(id.value).toPermanentDeletionResult() }

    override suspend fun readByThread(
        providerThreadId: ProviderThreadId,
    ): PermanentDeletionResult<PermanentDeletionOperation> =
        store(PermanentDeletionStorageOperation.READ) {
            dao.findByThread(providerThreadId.value).toPermanentDeletionResult()
        }

    override fun observeByThread(
        providerThreadId: ProviderThreadId,
    ): Flow<PermanentDeletionResult<PermanentDeletionOperation?>> =
        dao.observeByThread(providerThreadId.value)
            .map { entity ->
                if (entity == null) {
                    PermanentDeletionResult.Success(null)
                } else {
                    entity.toPermanentDeletionResult()
                }
            }
            .catch { failure ->
                if (failure is CancellationException) throw failure
                emit(
                    PermanentDeletionResult.StorageFailure(
                        PermanentDeletionStorageOperation.READ,
                    ),
                )
            }
            .distinctUntilChanged()

    override suspend fun recoverySnapshot(): PermanentDeletionResult<List<PermanentDeletionOperation>> =
        store(PermanentDeletionStorageOperation.RECOVER) {
            val entities = dao.recoverySnapshot(MAXIMUM_PERMANENT_DELETION_OPERATIONS + 1)
            if (entities.size > MAXIMUM_PERMANENT_DELETION_OPERATIONS) {
                return@store PermanentDeletionResult.CorruptData
            }
            val operations = entities.map {
                it.toDomainOrNull() ?: return@store PermanentDeletionResult.CorruptData
            }
            if (operations.distinctBy { it.target.providerThreadId }.size != operations.size) {
                PermanentDeletionResult.CorruptData
            } else {
                PermanentDeletionResult.Success(operations)
            }
        }

    override suspend fun validateLocalPreconditions(
        operation: PermanentDeletionOperation,
    ): PermanentDeletionResult<Boolean> = store(PermanentDeletionStorageOperation.READ) {
        database.withTransaction {
            if (dao.hasConflictingAction(operation.target.providerThreadId.value)) {
                return@withTransaction PermanentDeletionResult.Success(false)
            }
            val thread = operation.target as? PermanentDeletionTarget.Thread
                ?: return@withTransaction PermanentDeletionResult.Success(true)
            val current = dao.findDraftByThread(thread.providerThreadId.value)
            val matches = when {
                thread.draftId == null -> current == null
                current == null -> false
                else -> current.draftId == thread.draftId.value &&
                    current.updatedTimestampMillis == thread.draftRevision?.updatedTimestampMillis
            }
            PermanentDeletionResult.Success(matches)
        }
    }

    override suspend fun markCommitting(
        id: PermanentDeletionId,
        expectedRevision: PermanentDeletionRevision,
        updatedTimestampMillis: Long,
    ): PermanentDeletionResult<PermanentDeletionOperation> = transition(
        id,
        expectedRevision,
        updatedTimestampMillis,
    ) {
        dao.markCommitting(
            id = id.value,
            revision = expectedRevision.updatedTimestampMillis,
            updated = updatedTimestampMillis,
            pendingPhase = PermanentDeletionPhase.PENDING.storageCode,
            committingPhase = PermanentDeletionPhase.COMMITTING.storageCode,
        )
    }

    override suspend fun markReviewRequired(
        id: PermanentDeletionId,
        expectedRevision: PermanentDeletionRevision,
        reason: PermanentDeletionReviewReason,
        updatedTimestampMillis: Long,
    ): PermanentDeletionResult<PermanentDeletionOperation> = transition(
        id,
        expectedRevision,
        updatedTimestampMillis,
    ) {
        dao.markReview(
            id = id.value,
            revision = expectedRevision.updatedTimestampMillis,
            reason = reason.storageCode,
            updated = updatedTimestampMillis,
            pendingPhase = PermanentDeletionPhase.PENDING.storageCode,
            committingPhase = PermanentDeletionPhase.COMMITTING.storageCode,
            reviewPhase = PermanentDeletionPhase.REVIEW_REQUIRED.storageCode,
        )
    }

    override suspend fun removeUndoable(
        id: PermanentDeletionId,
        expectedRevision: PermanentDeletionRevision,
    ): PermanentDeletionResult<Unit> = store(PermanentDeletionStorageOperation.REMOVE) {
        classifyDelete(
            dao.deleteUndoable(
                id.value,
                expectedRevision.updatedTimestampMillis,
                PermanentDeletionPhase.PENDING.storageCode,
                PermanentDeletionPhase.REVIEW_REQUIRED.storageCode,
            ),
            id,
        )
    }

    override suspend fun removeCommitted(
        id: PermanentDeletionId,
        expectedRevision: PermanentDeletionRevision,
    ): PermanentDeletionResult<Unit> = store(PermanentDeletionStorageOperation.REMOVE) {
        database.withTransaction {
            val operation = dao.findById(id.value)?.toDomainOrNull()
                ?: return@withTransaction PermanentDeletionResult.NotFound
            if (
                operation.revision != expectedRevision ||
                !operation.mayBeRemovedAfterCommit()
            ) {
                return@withTransaction PermanentDeletionResult.StaleWrite
            }
            val thread = operation.target as? PermanentDeletionTarget.Thread
            if (thread?.draftId != null && thread.draftRevision != null) {
                dao.deleteExactDraft(
                    draftId = thread.draftId.value,
                    threadId = thread.providerThreadId.value,
                    revision = thread.draftRevision.updatedTimestampMillis,
                )
            }
            classifyDelete(
                dao.deleteCommitted(
                    id.value,
                    expectedRevision.updatedTimestampMillis,
                    PermanentDeletionPhase.COMMITTING.storageCode,
                    PermanentDeletionPhase.REVIEW_REQUIRED.storageCode,
                    PermanentDeletionReviewReason.INTERRUPTED_DURING_COMMIT.storageCode,
                ),
                id,
            )
        }
    }

    private suspend fun classifyDelete(
        changed: Int,
        id: PermanentDeletionId,
    ): PermanentDeletionResult<Unit> = when (changed) {
        1 -> PermanentDeletionResult.Success(Unit)
        0 -> if (dao.findById(id.value) == null) {
            PermanentDeletionResult.NotFound
        } else {
            PermanentDeletionResult.StaleWrite
        }
        else -> PermanentDeletionResult.CorruptData
    }

    private suspend fun transition(
        id: PermanentDeletionId,
        revision: PermanentDeletionRevision,
        updatedTimestampMillis: Long,
        update: suspend () -> Int,
    ): PermanentDeletionResult<PermanentDeletionOperation> =
        store(PermanentDeletionStorageOperation.TRANSITION) {
            if (updatedTimestampMillis <= revision.updatedTimestampMillis) {
                return@store PermanentDeletionResult.StaleWrite
            }
            when (update()) {
                1 -> dao.findById(id.value).toPermanentDeletionResult()
                0 -> if (dao.findById(id.value) == null) {
                    PermanentDeletionResult.NotFound
                } else {
                    PermanentDeletionResult.StaleWrite
                }
                else -> PermanentDeletionResult.CorruptData
            }
        }

    private suspend fun <T> store(
        operation: PermanentDeletionStorageOperation,
        block: suspend () -> PermanentDeletionResult<T>,
    ): PermanentDeletionResult<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SQLiteConstraintException) {
        PermanentDeletionResult.CorruptData
    } catch (_: SQLiteException) {
        PermanentDeletionResult.StorageFailure(operation)
    } catch (_: IllegalArgumentException) {
        PermanentDeletionResult.CorruptData
    } catch (_: IllegalStateException) {
        PermanentDeletionResult.CorruptData
    }
}

private fun PermanentDeletionOperation.mayBeRemovedAfterCommit(): Boolean =
    phase == PermanentDeletionPhase.COMMITTING ||
        (phase == PermanentDeletionPhase.REVIEW_REQUIRED &&
            reviewReason == PermanentDeletionReviewReason.INTERRUPTED_DURING_COMMIT)

private fun PermanentDeletionRequest.toEntity(
    authoritativeTarget: PermanentDeletionTarget = target,
): PermanentDeletionEntity {
    val message = authoritativeTarget as? PermanentDeletionTarget.Message
    val thread = authoritativeTarget as? PermanentDeletionTarget.Thread
    return PermanentDeletionEntity(
        targetKindCode = if (message != null) "message_v1" else "thread_v1",
        providerThreadId = authoritativeTarget.providerThreadId.value,
        providerKind = message?.providerMessageId?.kind?.toPermanentDeletionProviderCode(),
        providerMessageId = message?.providerMessageId?.value,
        syncFingerprint = message?.syncFingerprint?.toStorageToken(),
        smsCount = thread?.smsCount,
        latestSmsId = thread?.latestSmsId?.value,
        mmsCount = thread?.mmsCount,
        latestMmsId = thread?.latestMmsId?.value,
        draftId = thread?.draftId?.value,
        draftRevisionMillis = thread?.draftRevision?.updatedTimestampMillis,
        dueTimestampMillis = dueTimestampMillis,
        phaseCode = PermanentDeletionPhase.PENDING.storageCode,
        reviewReasonCode = null,
        armedWallTimestampMillis = createdTimestampMillis,
        armedElapsedRealtimeMillis = armedElapsedRealtimeMillis,
        createdTimestampMillis = createdTimestampMillis,
        updatedTimestampMillis = createdTimestampMillis,
    )
}

private fun ProviderKind.toPermanentDeletionProviderCode(): Int = when (this) {
    ProviderKind.SMS -> 1
    ProviderKind.MMS -> 2
    ProviderKind.DRAFT,
    ProviderKind.SCHEDULED,
    ProviderKind.PENDING_OPERATION,
    -> error("Only SMS/MMS can be permanently deleted")
}

private fun PermanentDeletionEntity?.toPermanentDeletionResult():
    PermanentDeletionResult<PermanentDeletionOperation> {
    if (this == null) return PermanentDeletionResult.NotFound
    return toDomainOrNull()?.let { PermanentDeletionResult.Success(it) }
        ?: PermanentDeletionResult.CorruptData
}

private fun PermanentDeletionEntity.toDomainOrNull(): PermanentDeletionOperation? = try {
    toDomain()
} catch (_: IllegalArgumentException) {
    null
} catch (_: IllegalStateException) {
    null
}

private fun DraftEntity.toDomainOrNull() = try {
    toDomain()
} catch (_: IllegalArgumentException) {
    null
} catch (_: IllegalStateException) {
    null
}
