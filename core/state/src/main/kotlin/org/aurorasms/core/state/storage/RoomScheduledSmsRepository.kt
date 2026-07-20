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
import org.aurorasms.core.state.DraftIdentity
import org.aurorasms.core.state.MAXIMUM_SCHEDULED_SMS_OPERATIONS
import org.aurorasms.core.state.ScheduledSms
import org.aurorasms.core.state.ScheduledSmsDispatchReconciliation
import org.aurorasms.core.state.ScheduledSmsId
import org.aurorasms.core.state.ScheduledSmsPhase
import org.aurorasms.core.state.ScheduledSmsPrecision
import org.aurorasms.core.state.ScheduledSmsRepository
import org.aurorasms.core.state.ScheduledSmsReservation
import org.aurorasms.core.state.ScheduledSmsRequest
import org.aurorasms.core.state.ScheduledSmsResult
import org.aurorasms.core.state.ScheduledSmsReviewReason
import org.aurorasms.core.state.ScheduledSmsRevision
import org.aurorasms.core.state.ScheduledSmsStorageOperation

class RoomScheduledSmsRepository(
    private val database: AuroraStateDatabase,
) : ScheduledSmsRepository {
    private val dao: ScheduledSmsDao = database.scheduledSmsDao()
    override suspend fun create(
        request: ScheduledSmsRequest,
    ): ScheduledSmsResult<ScheduledSmsReservation> =
        store(ScheduledSmsStorageOperation.CREATE) {
            database.withTransaction {
                if (dao.count() >= MAXIMUM_SCHEDULED_SMS_OPERATIONS) {
                    return@withTransaction ScheduledSmsResult.LimitExceeded
                }
                if (dao.findByThread(request.providerThreadId.value) != null) {
                    return@withTransaction ScheduledSmsResult.StaleWrite
                }
                val draft = dao.findDraft(request.draftId.value)?.toDomainOrNull()
                    ?: return@withTransaction ScheduledSmsResult.NotFound
                if (
                    draft.identity != DraftIdentity.ProviderThread(request.providerThreadId) ||
                    draft.revision != request.expectedDraftRevision
                ) {
                    return@withTransaction ScheduledSmsResult.StaleWrite
                }
                if (draft.body.isNullOrBlank() || draft.subject != null) {
                    return@withTransaction ScheduledSmsResult.IneligibleDraft
                }
                val body = checkNotNull(draft.body)
                val id = dao.insert(
                    ScheduledSmsEntity(
                        participantSetKey = request.participantSetKey.toStorageValue(),
                        providerThreadId = request.providerThreadId.value,
                        draftId = request.draftId.value,
                        draftRevisionMillis = request.expectedDraftRevision.updatedTimestampMillis,
                        subscriptionId = request.subscriptionId.value,
                        dueTimestampMillis = request.dueTimestampMillis,
                        phaseCode = ScheduledSmsPhase.PENDING.storageCode,
                        precisionCode = ScheduledSmsPrecision.INEXACT.storageCode,
                        reviewReasonCode = null,
                        armedWallTimestampMillis = request.createdTimestampMillis,
                        armedElapsedRealtimeMillis = request.armedElapsedRealtimeMillis,
                        createdTimestampMillis = request.createdTimestampMillis,
                        updatedTimestampMillis = request.createdTimestampMillis,
                        signatureText = request.frozenSignature?.value,
                    ),
                )
                when (val stored = dao.findById(id).toScheduledResult()) {
                    is ScheduledSmsResult.Success -> ScheduledSmsResult.Success(
                        ScheduledSmsReservation(stored.value, body),
                    )
                    else -> ScheduledSmsResult.CorruptData
                }
            }
        }

    override suspend fun read(id: ScheduledSmsId): ScheduledSmsResult<ScheduledSms> =
        store(ScheduledSmsStorageOperation.READ) {
            dao.findById(id.value).toScheduledResult()
        }

    override suspend fun readByThread(
        providerThreadId: org.aurorasms.core.model.ProviderThreadId,
    ): ScheduledSmsResult<ScheduledSms> = store(ScheduledSmsStorageOperation.READ) {
        dao.findByThread(providerThreadId.value).toScheduledResult()
    }

    override fun observeByThread(
        providerThreadId: org.aurorasms.core.model.ProviderThreadId,
    ): Flow<ScheduledSmsResult<ScheduledSms?>> = dao.observeByThread(providerThreadId.value)
        .map { entity ->
            if (entity == null) ScheduledSmsResult.Success(null) else entity.toScheduledResult()
        }
        .catch { failure ->
            if (failure is CancellationException) throw failure
            emit(ScheduledSmsResult.StorageFailure(ScheduledSmsStorageOperation.READ))
        }
        .distinctUntilChanged()

    override suspend fun recoverySnapshot(): ScheduledSmsResult<List<ScheduledSms>> =
        store(ScheduledSmsStorageOperation.RECOVER) {
            val entities = dao.recoverySnapshot(MAXIMUM_SCHEDULED_SMS_OPERATIONS + 1)
            if (entities.size > MAXIMUM_SCHEDULED_SMS_OPERATIONS) {
                return@store ScheduledSmsResult.CorruptData
            }
            val schedules = entities.map { it.toDomainOrNull() ?: return@store ScheduledSmsResult.CorruptData }
            if (
                schedules.distinctBy { it.providerThreadId }.size != schedules.size ||
                schedules.distinctBy { it.draftId }.size != schedules.size
            ) {
                ScheduledSmsResult.CorruptData
            } else {
                ScheduledSmsResult.Success(schedules)
            }
        }

    override suspend fun markArmed(
        id: ScheduledSmsId,
        expectedRevision: ScheduledSmsRevision,
        precision: ScheduledSmsPrecision,
        armedWallTimestampMillis: Long,
        armedElapsedRealtimeMillis: Long,
        updatedTimestampMillis: Long,
    ): ScheduledSmsResult<ScheduledSms> = transition(id, expectedRevision, updatedTimestampMillis) {
        dao.updateArmed(
            id = id.value,
            expectedRevision = expectedRevision.updatedTimestampMillis,
            precisionCode = precision.storageCode,
            armedWall = armedWallTimestampMillis,
            armedElapsed = armedElapsedRealtimeMillis,
            updated = updatedTimestampMillis,
            pendingPhase = ScheduledSmsPhase.PENDING.storageCode,
        )
    }

    override suspend fun markDispatching(
        id: ScheduledSmsId,
        expectedRevision: ScheduledSmsRevision,
        updatedTimestampMillis: Long,
    ): ScheduledSmsResult<ScheduledSms> = transition(id, expectedRevision, updatedTimestampMillis) {
        dao.markDispatching(
            id = id.value,
            expectedRevision = expectedRevision.updatedTimestampMillis,
            updated = updatedTimestampMillis,
            pendingPhase = ScheduledSmsPhase.PENDING.storageCode,
            dispatchingPhase = ScheduledSmsPhase.DISPATCHING.storageCode,
        )
    }

    override suspend fun markReviewRequired(
        id: ScheduledSmsId,
        expectedRevision: ScheduledSmsRevision,
        reason: ScheduledSmsReviewReason,
        updatedTimestampMillis: Long,
    ): ScheduledSmsResult<ScheduledSms> = transition(id, expectedRevision, updatedTimestampMillis) {
        dao.markReview(
            id = id.value,
            expectedRevision = expectedRevision.updatedTimestampMillis,
            reasonCode = reason.storageCode,
            updated = updatedTimestampMillis,
            pendingPhase = ScheduledSmsPhase.PENDING.storageCode,
            dispatchingPhase = ScheduledSmsPhase.DISPATCHING.storageCode,
            reviewPhase = ScheduledSmsPhase.REVIEW_REQUIRED.storageCode,
        )
    }

    override suspend fun remove(
        id: ScheduledSmsId,
        expectedRevision: ScheduledSmsRevision,
    ): ScheduledSmsResult<Unit> = store(ScheduledSmsStorageOperation.REMOVE) {
        when (dao.deleteIfCurrent(id.value, expectedRevision.updatedTimestampMillis)) {
            1 -> ScheduledSmsResult.Success(Unit)
            0 -> if (dao.findById(id.value) == null) ScheduledSmsResult.NotFound else ScheduledSmsResult.StaleWrite
            else -> ScheduledSmsResult.CorruptData
        }
    }

    override suspend fun reconcileDispatch(
        id: ScheduledSmsId,
        updatedTimestampMillis: Long,
    ): ScheduledSmsResult<ScheduledSmsDispatchReconciliation> =
        store(ScheduledSmsStorageOperation.RECOVER) {
            database.withTransaction {
                val schedule = dao.findById(id.value)?.toDomainOrNull()
                    ?: return@withTransaction ScheduledSmsResult.NotFound
                if (schedule.phase != ScheduledSmsPhase.DISPATCHING) {
                    return@withTransaction ScheduledSmsResult.PhaseMismatch
                }
                val composer = dao.findComposerOperation(schedule.providerThreadId.value)
                if (composer != null) {
                    val composerOperation = try {
                        composer.toDomain()
                    } catch (_: IllegalArgumentException) {
                        null
                    } catch (_: IllegalStateException) {
                        null
                    }
                    if (
                        composerOperation == null ||
                        composerOperation.draftId != schedule.draftId ||
                        composerOperation.draftRevision != schedule.draftRevision
                    ) {
                        val changed = dao.markReview(
                            id = id.value,
                            expectedRevision = schedule.updatedTimestampMillis,
                            reasonCode = ScheduledSmsReviewReason.INTERRUPTED_BEFORE_RESERVATION.storageCode,
                            updated = updatedTimestampMillis
                                .coerceAtLeast(schedule.updatedTimestampMillis + 1L),
                            pendingPhase = ScheduledSmsPhase.PENDING.storageCode,
                            dispatchingPhase = ScheduledSmsPhase.DISPATCHING.storageCode,
                            reviewPhase = ScheduledSmsPhase.REVIEW_REQUIRED.storageCode,
                        )
                        return@withTransaction if (changed == 1) {
                            ScheduledSmsResult.Success(
                                ScheduledSmsDispatchReconciliation.REVIEW_REQUIRED,
                            )
                        } else {
                            ScheduledSmsResult.StaleWrite
                        }
                    }
                    return@withTransaction ScheduledSmsResult.Success(
                        ScheduledSmsDispatchReconciliation.IN_PROGRESS,
                    )
                }
                val draft = dao.findDraft(schedule.draftId.value)
                if (draft == null) {
                    val removed = dao.deleteIfCurrent(id.value, schedule.updatedTimestampMillis)
                    return@withTransaction if (removed == 1) {
                        ScheduledSmsResult.Success(
                            ScheduledSmsDispatchReconciliation.COMPLETED_AND_REMOVED,
                        )
                    } else {
                        ScheduledSmsResult.StaleWrite
                    }
                }
                val effectiveTimestamp = updatedTimestampMillis
                    .coerceAtLeast(schedule.updatedTimestampMillis + 1L)
                val changed = dao.markReview(
                    id = id.value,
                    expectedRevision = schedule.updatedTimestampMillis,
                    reasonCode = ScheduledSmsReviewReason.INTERRUPTED_BEFORE_RESERVATION.storageCode,
                    updated = effectiveTimestamp,
                    pendingPhase = ScheduledSmsPhase.PENDING.storageCode,
                    dispatchingPhase = ScheduledSmsPhase.DISPATCHING.storageCode,
                    reviewPhase = ScheduledSmsPhase.REVIEW_REQUIRED.storageCode,
                )
                if (changed == 1) {
                    ScheduledSmsResult.Success(ScheduledSmsDispatchReconciliation.REVIEW_REQUIRED)
                } else {
                    ScheduledSmsResult.StaleWrite
                }
            }
        }

    private suspend fun transition(
        id: ScheduledSmsId,
        expectedRevision: ScheduledSmsRevision,
        updatedTimestampMillis: Long,
        update: suspend () -> Int,
    ): ScheduledSmsResult<ScheduledSms> = store(ScheduledSmsStorageOperation.TRANSITION) {
        if (updatedTimestampMillis <= expectedRevision.updatedTimestampMillis) {
            return@store ScheduledSmsResult.InvalidTimestamp
        }
        when (update()) {
            1 -> dao.findById(id.value).toScheduledResult()
            0 -> if (dao.findById(id.value) == null) ScheduledSmsResult.NotFound else ScheduledSmsResult.StaleWrite
            else -> ScheduledSmsResult.CorruptData
        }
    }

    private suspend fun <T> store(
        operation: ScheduledSmsStorageOperation,
        block: suspend () -> ScheduledSmsResult<T>,
    ): ScheduledSmsResult<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SQLiteConstraintException) {
        ScheduledSmsResult.CorruptData
    } catch (_: SQLiteException) {
        ScheduledSmsResult.StorageFailure(operation)
    } catch (_: IllegalArgumentException) {
        ScheduledSmsResult.CorruptData
    } catch (_: IllegalStateException) {
        ScheduledSmsResult.CorruptData
    }
}

private fun DraftEntity.toDomainOrNull() = try {
    toDomain()
} catch (_: IllegalArgumentException) {
    null
} catch (_: IllegalStateException) {
    null
}

private fun ScheduledSmsEntity?.toScheduledResult(): ScheduledSmsResult<ScheduledSms> {
    if (this == null) return ScheduledSmsResult.NotFound
    return toDomainOrNull()?.let { ScheduledSmsResult.Success(it) }
        ?: ScheduledSmsResult.CorruptData
}

private fun ScheduledSmsEntity.toDomainOrNull() = try {
    toDomain()
} catch (_: IllegalArgumentException) {
    null
} catch (_: IllegalStateException) {
    null
}
