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
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.DraftIdentity
import org.aurorasms.core.state.MAXIMUM_SEND_DELAY_OPERATIONS
import org.aurorasms.core.state.SendDelayDispatchReconciliation
import org.aurorasms.core.state.SendDelayId
import org.aurorasms.core.state.SendDelayOperation
import org.aurorasms.core.state.SendDelayPhase
import org.aurorasms.core.state.SendDelayRepository
import org.aurorasms.core.state.SendDelayRequest
import org.aurorasms.core.state.SendDelayReservation
import org.aurorasms.core.state.SendDelayResult
import org.aurorasms.core.state.SendDelayReviewReason
import org.aurorasms.core.state.SendDelayRevision
import org.aurorasms.core.state.SendDelayStorageOperation

class RoomSendDelayRepository(
    private val database: AuroraStateDatabase,
) : SendDelayRepository {
    private val dao = database.sendDelayDao()

    override suspend fun create(
        request: SendDelayRequest,
    ): SendDelayResult<SendDelayReservation> = store(SendDelayStorageOperation.CREATE) {
        database.withTransaction {
            val count = dao.count()
            if (count < 0 || count > MAXIMUM_SEND_DELAY_OPERATIONS) {
                return@withTransaction SendDelayResult.CorruptData
            }
            if (count == MAXIMUM_SEND_DELAY_OPERATIONS) {
                return@withTransaction SendDelayResult.LimitExceeded
            }
            if (dao.findByThread(request.providerThreadId.value) != null) {
                return@withTransaction SendDelayResult.StaleWrite
            }
            val draft = dao.findDraft(request.draftId.value)?.toDomainOrNull()
                ?: return@withTransaction SendDelayResult.NotFound
            if (
                draft.identity != DraftIdentity.ProviderThread(request.providerThreadId) ||
                draft.revision != request.expectedDraftRevision
            ) {
                return@withTransaction SendDelayResult.StaleWrite
            }
            if (draft.body.isNullOrBlank() || draft.subject != null) {
                return@withTransaction SendDelayResult.IneligibleDraft
            }
            val body = checkNotNull(draft.body)
            val id = dao.insert(
                SendDelayEntity(
                    participantSetKey = request.participantSetKey.toStorageValue(),
                    providerThreadId = request.providerThreadId.value,
                    draftId = request.draftId.value,
                    draftRevisionMillis = request.expectedDraftRevision.updatedTimestampMillis,
                    subscriptionId = request.subscriptionId.value,
                    dueTimestampMillis = request.dueTimestampMillis,
                    phaseCode = SendDelayPhase.PENDING.storageCode,
                    reviewReasonCode = null,
                    armedWallTimestampMillis = request.createdTimestampMillis,
                    armedElapsedRealtimeMillis = request.armedElapsedRealtimeMillis,
                    createdTimestampMillis = request.createdTimestampMillis,
                    updatedTimestampMillis = request.createdTimestampMillis,
                ),
            )
            when (val stored = dao.findById(id).toSendDelayResult()) {
                is SendDelayResult.Success -> SendDelayResult.Success(
                    SendDelayReservation(stored.value, body),
                )
                else -> SendDelayResult.CorruptData
            }
        }
    }

    override suspend fun read(id: SendDelayId): SendDelayResult<SendDelayOperation> =
        store(SendDelayStorageOperation.READ) { dao.findById(id.value).toSendDelayResult() }

    override suspend fun readByThread(
        providerThreadId: ProviderThreadId,
    ): SendDelayResult<SendDelayOperation> = store(SendDelayStorageOperation.READ) {
        dao.findByThread(providerThreadId.value).toSendDelayResult()
    }

    override fun observeByThread(
        providerThreadId: ProviderThreadId,
    ): Flow<SendDelayResult<SendDelayOperation?>> = dao.observeByThread(providerThreadId.value)
        .map { entity ->
            if (entity == null) SendDelayResult.Success(null) else entity.toSendDelayResult()
        }
        .catch { failure ->
            if (failure is CancellationException) throw failure
            emit(SendDelayResult.StorageFailure(SendDelayStorageOperation.READ))
        }
        .distinctUntilChanged()

    override suspend fun recoverySnapshot(): SendDelayResult<List<SendDelayOperation>> =
        store(SendDelayStorageOperation.RECOVER) {
            val entities = dao.recoverySnapshot(MAXIMUM_SEND_DELAY_OPERATIONS + 1)
            if (entities.size > MAXIMUM_SEND_DELAY_OPERATIONS) {
                return@store SendDelayResult.CorruptData
            }
            val operations = entities.map {
                it.toDomainOrNull() ?: return@store SendDelayResult.CorruptData
            }
            if (
                operations.distinctBy { it.providerThreadId }.size != operations.size ||
                operations.distinctBy { it.draftId }.size != operations.size
            ) {
                SendDelayResult.CorruptData
            } else {
                SendDelayResult.Success(operations)
            }
        }

    override suspend fun markDispatching(
        id: SendDelayId,
        expectedRevision: SendDelayRevision,
        updatedTimestampMillis: Long,
    ): SendDelayResult<SendDelayOperation> = transition(id, expectedRevision, updatedTimestampMillis) {
        dao.markDispatching(
            id = id.value,
            expectedRevision = expectedRevision.updatedTimestampMillis,
            updated = updatedTimestampMillis,
            pendingPhase = SendDelayPhase.PENDING.storageCode,
            dispatchingPhase = SendDelayPhase.DISPATCHING.storageCode,
        )
    }

    override suspend fun markReviewRequired(
        id: SendDelayId,
        expectedRevision: SendDelayRevision,
        reason: SendDelayReviewReason,
        updatedTimestampMillis: Long,
    ): SendDelayResult<SendDelayOperation> = transition(id, expectedRevision, updatedTimestampMillis) {
        dao.markReview(
            id = id.value,
            expectedRevision = expectedRevision.updatedTimestampMillis,
            reasonCode = reason.storageCode,
            updated = updatedTimestampMillis,
            pendingPhase = SendDelayPhase.PENDING.storageCode,
            dispatchingPhase = SendDelayPhase.DISPATCHING.storageCode,
            reviewPhase = SendDelayPhase.REVIEW_REQUIRED.storageCode,
        )
    }

    override suspend fun remove(
        id: SendDelayId,
        expectedRevision: SendDelayRevision,
    ): SendDelayResult<Unit> = store(SendDelayStorageOperation.REMOVE) {
        when (
            dao.deleteUndoableIfCurrent(
                id = id.value,
                revision = expectedRevision.updatedTimestampMillis,
                pendingPhase = SendDelayPhase.PENDING.storageCode,
                reviewPhase = SendDelayPhase.REVIEW_REQUIRED.storageCode,
            )
        ) {
            1 -> SendDelayResult.Success(Unit)
            0 -> when (val current = dao.findById(id.value)?.toDomainOrNull()) {
                null -> SendDelayResult.NotFound
                else -> if (current.phase == SendDelayPhase.DISPATCHING) {
                    SendDelayResult.PhaseMismatch
                } else {
                    SendDelayResult.StaleWrite
                }
            }
            else -> SendDelayResult.CorruptData
        }
    }

    override suspend fun reconcileDispatch(
        id: SendDelayId,
        updatedTimestampMillis: Long,
    ): SendDelayResult<SendDelayDispatchReconciliation> =
        store(SendDelayStorageOperation.RECOVER) {
            database.withTransaction {
                val delay = dao.findById(id.value)?.toDomainOrNull()
                    ?: return@withTransaction SendDelayResult.NotFound
                if (delay.phase != SendDelayPhase.DISPATCHING) {
                    return@withTransaction SendDelayResult.PhaseMismatch
                }
                val composer = dao.findComposerOperation(delay.providerThreadId.value)
                if (composer != null) {
                    val composerOperation = try {
                        composer.toDomain()
                    } catch (_: IllegalArgumentException) {
                        null
                    } catch (_: IllegalStateException) {
                        null
                    }
                    if (
                        composerOperation != null &&
                        composerOperation.draftId == delay.draftId &&
                        composerOperation.draftRevision == delay.draftRevision &&
                        composerOperation.subscriptionId == delay.subscriptionId
                    ) {
                        return@withTransaction SendDelayResult.Success(
                            SendDelayDispatchReconciliation.IN_PROGRESS,
                        )
                    }
                    return@withTransaction markInterruptedForReview(delay, updatedTimestampMillis)
                }
                val draft = dao.findDraft(delay.draftId.value)
                if (draft == null) {
                    return@withTransaction if (
                        dao.deleteIfCurrent(delay.id.value, delay.updatedTimestampMillis) == 1
                    ) {
                        SendDelayResult.Success(
                            SendDelayDispatchReconciliation.COMPLETED_AND_REMOVED,
                        )
                    } else {
                        SendDelayResult.StaleWrite
                    }
                }
                markInterruptedForReview(delay, updatedTimestampMillis)
            }
        }

    private suspend fun markInterruptedForReview(
        delay: SendDelayOperation,
        updatedTimestampMillis: Long,
    ): SendDelayResult<SendDelayDispatchReconciliation> {
        val changed = dao.markReview(
            id = delay.id.value,
            expectedRevision = delay.updatedTimestampMillis,
            reasonCode = SendDelayReviewReason.INTERRUPTED_BEFORE_RESERVATION.storageCode,
            updated = updatedTimestampMillis.coerceAtLeast(delay.updatedTimestampMillis + 1L),
            pendingPhase = SendDelayPhase.PENDING.storageCode,
            dispatchingPhase = SendDelayPhase.DISPATCHING.storageCode,
            reviewPhase = SendDelayPhase.REVIEW_REQUIRED.storageCode,
        )
        return if (changed == 1) {
            SendDelayResult.Success(SendDelayDispatchReconciliation.REVIEW_REQUIRED)
        } else {
            SendDelayResult.StaleWrite
        }
    }

    private suspend fun transition(
        id: SendDelayId,
        expectedRevision: SendDelayRevision,
        updatedTimestampMillis: Long,
        update: suspend () -> Int,
    ): SendDelayResult<SendDelayOperation> = store(SendDelayStorageOperation.TRANSITION) {
        if (updatedTimestampMillis <= expectedRevision.updatedTimestampMillis) {
            return@store SendDelayResult.InvalidTimestamp
        }
        when (update()) {
            1 -> dao.findById(id.value).toSendDelayResult()
            0 -> if (dao.findById(id.value) == null) {
                SendDelayResult.NotFound
            } else {
                SendDelayResult.StaleWrite
            }
            else -> SendDelayResult.CorruptData
        }
    }

    private suspend fun <T> store(
        operation: SendDelayStorageOperation,
        block: suspend () -> SendDelayResult<T>,
    ): SendDelayResult<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SQLiteConstraintException) {
        SendDelayResult.CorruptData
    } catch (_: SQLiteException) {
        SendDelayResult.StorageFailure(operation)
    } catch (_: IllegalArgumentException) {
        SendDelayResult.CorruptData
    } catch (_: IllegalStateException) {
        SendDelayResult.CorruptData
    }
}

private fun DraftEntity.toDomainOrNull() = try {
    toDomain()
} catch (_: IllegalArgumentException) {
    null
} catch (_: IllegalStateException) {
    null
}

private fun SendDelayEntity?.toSendDelayResult(): SendDelayResult<SendDelayOperation> {
    if (this == null) return SendDelayResult.NotFound
    return toDomainOrNull()?.let { SendDelayResult.Success(it) }
        ?: SendDelayResult.CorruptData
}

private fun SendDelayEntity.toDomainOrNull() = try {
    toDomain()
} catch (_: IllegalArgumentException) {
    null
} catch (_: IllegalStateException) {
    null
}
