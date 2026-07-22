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
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ParticipantAddressEquivalenceKey
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.Draft
import org.aurorasms.core.state.DraftAttachment
import org.aurorasms.core.state.DraftIdentity
import org.aurorasms.core.state.DraftParticipantSetKey
import org.aurorasms.core.state.FirstContactAttachmentSetEvidence
import org.aurorasms.core.state.FirstContactBridgeSnapshot
import org.aurorasms.core.state.FirstContactKnownUnsentProof
import org.aurorasms.core.state.FirstContactOperation
import org.aurorasms.core.state.FirstContactOperationId
import org.aurorasms.core.state.FirstContactOperationPhase
import org.aurorasms.core.state.FirstContactOperationRepository
import org.aurorasms.core.state.FirstContactOperationResult
import org.aurorasms.core.state.FirstContactOperationRevision
import org.aurorasms.core.state.FirstContactParticipantSetKey
import org.aurorasms.core.state.FirstContactReservationRequest
import org.aurorasms.core.state.FirstContactResolutionSnapshot
import org.aurorasms.core.state.FirstContactStorageOperation
import org.aurorasms.core.state.MAXIMUM_FIRST_CONTACT_OPERATIONS

class RoomFirstContactOperationRepository(
    private val database: AuroraStateDatabase,
) : FirstContactOperationRepository {
    private val dao = database.firstContactOperationDao()

    override suspend fun reserve(
        request: FirstContactReservationRequest,
    ): FirstContactOperationResult<FirstContactOperation> =
        store(FirstContactStorageOperation.RESERVE, constraintIsConflict = true) {
            val participants = request.participants.toList()
            val exactParticipantKey = DraftParticipantSetKey.fromParticipants(participants)
            val semanticParticipantKey =
                FirstContactParticipantSetKey.fromParticipants(participants)
            database.withTransaction {
                dao.findByParticipantSetKey(semanticParticipantKey.toStorageValue())?.let {
                    return@withTransaction if (it.toDomainOrNull() == null) {
                        FirstContactOperationResult.CorruptData
                    } else {
                        FirstContactOperationResult.Conflict
                    }
                }
                dao.findByDraft(request.draftId.value)?.let {
                    return@withTransaction if (it.toDomainOrNull() == null) {
                        FirstContactOperationResult.CorruptData
                    } else {
                        FirstContactOperationResult.Conflict
                    }
                }
                val count = dao.count()
                if (count < 0 || count > MAXIMUM_FIRST_CONTACT_OPERATIONS) {
                    return@withTransaction FirstContactOperationResult.CorruptData
                }
                if (count == MAXIMUM_FIRST_CONTACT_OPERATIONS) {
                    return@withTransaction FirstContactOperationResult.LimitExceeded
                }
                val draftEntity = dao.findDraft(request.draftId.value)
                    ?: return@withTransaction FirstContactOperationResult.NotFound
                val draft = draftEntity.toDomainOrNull()
                    ?: return@withTransaction FirstContactOperationResult.CorruptData
                if (
                    draft.identity != DraftIdentity.ParticipantSet(exactParticipantKey) ||
                    draft.revision != request.expectedDraftRevision
                ) {
                    return@withTransaction FirstContactOperationResult.StaleWrite
                }
                val attachments = dao.findAttachments(draft.id.value).toDomainListOrNull()
                    ?: return@withTransaction FirstContactOperationResult.CorruptData
                if (!isEligible(draft, participants, attachments, request.transport)) {
                    return@withTransaction FirstContactOperationResult.IneligibleDraft
                }
                val attachmentEvidence =
                    FirstContactAttachmentSetEvidence.fromAttachments(attachments)
                val id = dao.insert(
                    FirstContactOperationEntity(
                        participantSetKey = semanticParticipantKey.toStorageValue(),
                        draftId = draft.id.value,
                        sourceDraftRevisionMillis = draft.revision.updatedTimestampMillis,
                        attachmentSetEvidence = attachmentEvidence.toStorageValue(),
                        subscriptionId = request.subscriptionId.value,
                        transportCode = request.transport.storageCode,
                        phaseCode = FirstContactOperationPhase.RESERVED.storageCode,
                        providerThreadId = null,
                        handoffDraftRevisionMillis = null,
                        createdTimestampMillis = request.createdTimestampMillis,
                        updatedTimestampMillis = request.createdTimestampMillis,
                        signatureText = request.frozenSignature?.value,
                    ),
                )
                if (id <= 0L) abort(FirstContactOperationResult.CorruptData)
                dao.findById(id).toOperationResultOrAbort()
            }
        }

    override suspend fun read(
        id: FirstContactOperationId,
    ): FirstContactOperationResult<FirstContactOperation> =
        store(FirstContactStorageOperation.READ) { dao.findById(id.value).toOperationResult() }

    override suspend fun readByDraft(
        draftId: org.aurorasms.core.state.DraftId,
    ): FirstContactOperationResult<FirstContactOperation> =
        store(FirstContactStorageOperation.READ) {
            dao.findByDraft(draftId.value).toOperationResult()
        }

    override fun observeByDraft(
        draftId: org.aurorasms.core.state.DraftId,
    ): Flow<FirstContactOperationResult<FirstContactOperation?>> =
        dao.observeByDraft(draftId.value)
            .map { entity ->
                if (entity == null) {
                    FirstContactOperationResult.Success(null)
                } else {
                    entity.toDomainOrNull()?.let { FirstContactOperationResult.Success(it) }
                        ?: FirstContactOperationResult.CorruptData
                }
            }
            .catch { failure ->
                if (failure is CancellationException) throw failure
                emit(
                    FirstContactOperationResult.StorageFailure(
                        FirstContactStorageOperation.OBSERVE,
                    ),
                )
            }
            .distinctUntilChanged()

    override suspend fun recoverySnapshot():
        FirstContactOperationResult<List<FirstContactOperation>> =
        store(FirstContactStorageOperation.RECOVER) {
            val entities = dao.recoverySnapshot(MAXIMUM_FIRST_CONTACT_OPERATIONS + 1)
            if (entities.size > MAXIMUM_FIRST_CONTACT_OPERATIONS) {
                return@store FirstContactOperationResult.CorruptData
            }
            val operations = entities.map { entity ->
                entity.toDomainOrNull() ?: return@store FirstContactOperationResult.CorruptData
            }
            if (
                operations.distinctBy { it.participantSetKey }.size != operations.size ||
                operations.distinctBy { it.draftId }.size != operations.size ||
                operations.mapNotNull { it.providerThreadId }.distinct().size !=
                operations.count { it.providerThreadId != null }
            ) {
                FirstContactOperationResult.CorruptData
            } else {
                FirstContactOperationResult.Success(operations)
            }
        }

    override suspend fun markResolutionStarted(
        id: FirstContactOperationId,
        expectedRevision: FirstContactOperationRevision,
        updatedTimestampMillis: Long,
    ): FirstContactOperationResult<FirstContactResolutionSnapshot> =
        store(FirstContactStorageOperation.TRANSITION) {
            validateUpdatedTimestamp(expectedRevision, updatedTimestampMillis)?.let {
                return@store it
            }
            database.withTransaction {
                val current = currentForTransition(
                    id,
                    expectedRevision,
                    setOf(FirstContactOperationPhase.RESERVED),
                )
                if (current !is FirstContactOperationResult.Success) {
                    return@withTransaction current.castFailure()
                }
                val operation = current.value
                val source = exactSourceSnapshot(operation)
                if (source !is SourceSnapshotResult.Success) {
                    return@withTransaction source.toOperationFailure()
                }
                val changed = dao.transitionIfCurrent(
                    id = id.value,
                    expectedUpdatedTimestampMillis = expectedRevision.updatedTimestampMillis,
                    expectedPhase = FirstContactOperationPhase.RESERVED.storageCode,
                    targetPhase = FirstContactOperationPhase.RESOLUTION_STARTED.storageCode,
                    updatedTimestampMillis = updatedTimestampMillis,
                )
                if (changed != 1) abort(FirstContactOperationResult.StaleWrite)
                val updated = dao.findById(id.value).toOperationResultOrAbort()
                when (updated) {
                    is FirstContactOperationResult.Success ->
                        FirstContactOperationResult.Success(
                            FirstContactResolutionSnapshot(
                                operation = updated.value,
                                participants = source.snapshot.participants,
                            ),
                        )
                    else -> abort(FirstContactOperationResult.CorruptData)
                }
            }
        }

    override suspend fun bindThread(
        id: FirstContactOperationId,
        expectedRevision: FirstContactOperationRevision,
        providerThreadId: ProviderThreadId,
        updatedTimestampMillis: Long,
    ): FirstContactOperationResult<FirstContactOperation> =
        store(FirstContactStorageOperation.TRANSITION, constraintIsConflict = true) {
            validateUpdatedTimestamp(expectedRevision, updatedTimestampMillis)?.let {
                return@store it
            }
            database.withTransaction {
                val current = currentForTransition(
                    id,
                    expectedRevision,
                    setOf(FirstContactOperationPhase.RESOLUTION_STARTED),
                )
                if (current !is FirstContactOperationResult.Success) {
                    return@withTransaction current.castFailure()
                }
                dao.findByProviderThread(providerThreadId.value)?.let { conflicting ->
                    if (conflicting.firstContactId != id.value) {
                        return@withTransaction FirstContactOperationResult.Conflict
                    }
                }
                val changed = dao.bindThreadIfCurrent(
                    id = id.value,
                    expectedUpdatedTimestampMillis = expectedRevision.updatedTimestampMillis,
                    providerThreadId = providerThreadId.value,
                    updatedTimestampMillis = updatedTimestampMillis,
                    resolutionStartedPhase =
                        FirstContactOperationPhase.RESOLUTION_STARTED.storageCode,
                    threadBoundPhase = FirstContactOperationPhase.THREAD_BOUND.storageCode,
                )
                if (changed != 1) abort(FirstContactOperationResult.StaleWrite)
                dao.findById(id.value).toOperationResultOrAbort()
            }
        }

    override suspend fun markResolutionUnknown(
        id: FirstContactOperationId,
        expectedRevision: FirstContactOperationRevision,
        updatedTimestampMillis: Long,
    ): FirstContactOperationResult<FirstContactOperation> = transitionPhase(
        id = id,
        expectedRevision = expectedRevision,
        expectedPhases = setOf(FirstContactOperationPhase.RESOLUTION_STARTED),
        targetPhase = FirstContactOperationPhase.RESOLUTION_UNKNOWN,
        updatedTimestampMillis = updatedTimestampMillis,
    )

    override suspend fun bridgeToProviderThread(
        id: FirstContactOperationId,
        expectedRevision: FirstContactOperationRevision,
        updatedTimestampMillis: Long,
    ): FirstContactOperationResult<FirstContactBridgeSnapshot> =
        store(FirstContactStorageOperation.BRIDGE, constraintIsConflict = true) {
            validateUpdatedTimestamp(expectedRevision, updatedTimestampMillis)?.let {
                return@store it
            }
            database.withTransaction {
                val current = currentForTransition(
                    id,
                    expectedRevision,
                    setOf(FirstContactOperationPhase.THREAD_BOUND),
                )
                if (current !is FirstContactOperationResult.Success) {
                    return@withTransaction current.castFailure()
                }
                val operation = current.value
                val threadId = checkNotNull(operation.providerThreadId)
                val source = exactSourceSnapshot(operation)
                if (source !is SourceSnapshotResult.Success) {
                    return@withTransaction source.toOperationFailure()
                }
                if (dao.findDraftByProviderThread(threadId.value) != null) {
                    return@withTransaction FirstContactOperationResult.Conflict
                }
                if (dao.hasConflictingThreadAction(threadId.value)) {
                    return@withTransaction FirstContactOperationResult.Conflict
                }
                val sourceRevision = operation.sourceDraftRevision.updatedTimestampMillis
                if (sourceRevision >= Long.MAX_VALUE - 1L) {
                    return@withTransaction FirstContactOperationResult.InvalidTimestamp
                }
                val handoffRevision = maxOf(updatedTimestampMillis, sourceRevision + 1L)
                val promoted = dao.promoteExactParticipantDraft(
                    draftId = operation.draftId.value,
                    participantSetKey = source.snapshot.participantSetStorageKey,
                    sourceDraftRevisionMillis = sourceRevision,
                    providerThreadId = threadId.value,
                    handoffDraftRevisionMillis = handoffRevision,
                )
                if (promoted != 1) abort(FirstContactOperationResult.StaleWrite)
                val reserved = dao.reserveHandoffIfCurrent(
                    id = id.value,
                    expectedUpdatedTimestampMillis = expectedRevision.updatedTimestampMillis,
                    providerThreadId = threadId.value,
                    handoffDraftRevisionMillis = handoffRevision,
                    updatedTimestampMillis = updatedTimestampMillis,
                    threadBoundPhase = FirstContactOperationPhase.THREAD_BOUND.storageCode,
                    handoffReservedPhase =
                        FirstContactOperationPhase.HANDOFF_RESERVED.storageCode,
                )
                if (reserved != 1) abort(FirstContactOperationResult.StaleWrite)
                val updatedOperation = dao.findById(id.value)?.toDomainOrNull()
                    ?: abort(FirstContactOperationResult.CorruptData)
                val providerDraft = dao.findDraft(operation.draftId.value)?.toDomainOrNull()
                    ?: abort(FirstContactOperationResult.CorruptData)
                FirstContactOperationResult.Success(
                    FirstContactBridgeSnapshot(
                        operation = updatedOperation,
                        providerDraft = providerDraft,
                        participants = source.snapshot.participants,
                        attachments = source.snapshot.attachments,
                    ),
                )
            }
        }

    override suspend fun markKnownUnsent(
        id: FirstContactOperationId,
        expectedRevision: FirstContactOperationRevision,
        proof: FirstContactKnownUnsentProof,
        updatedTimestampMillis: Long,
    ): FirstContactOperationResult<FirstContactOperation> {
        @Suppress("UNUSED_VARIABLE")
        val requiredProof = proof
        return transitionPhase(
            id = id,
            expectedRevision = expectedRevision,
            expectedPhases = setOf(FirstContactOperationPhase.RESERVED),
            targetPhase = FirstContactOperationPhase.KNOWN_UNSENT,
            updatedTimestampMillis = updatedTimestampMillis,
        )
    }

    override suspend fun release(
        id: FirstContactOperationId,
        expectedRevision: FirstContactOperationRevision,
    ): FirstContactOperationResult<Unit> = store(FirstContactStorageOperation.RELEASE) {
        database.withTransaction {
            val current = currentForTransition(
                id,
                expectedRevision,
                setOf(FirstContactOperationPhase.KNOWN_UNSENT),
            )
            if (current !is FirstContactOperationResult.Success) {
                return@withTransaction current.castFailure()
            }
            when (
                dao.releaseKnownUnsentIfCurrent(
                    id = id.value,
                    expectedUpdatedTimestampMillis = expectedRevision.updatedTimestampMillis,
                    knownUnsentPhase = FirstContactOperationPhase.KNOWN_UNSENT.storageCode,
                )
            ) {
                1 -> FirstContactOperationResult.Success(Unit)
                0 -> abort(FirstContactOperationResult.StaleWrite)
                else -> abort(FirstContactOperationResult.CorruptData)
            }
        }
    }

    private suspend fun transitionPhase(
        id: FirstContactOperationId,
        expectedRevision: FirstContactOperationRevision,
        expectedPhases: Set<FirstContactOperationPhase>,
        targetPhase: FirstContactOperationPhase,
        updatedTimestampMillis: Long,
    ): FirstContactOperationResult<FirstContactOperation> =
        store(FirstContactStorageOperation.TRANSITION) {
            validateUpdatedTimestamp(expectedRevision, updatedTimestampMillis)?.let {
                return@store it
            }
            database.withTransaction {
                val current = currentForTransition(id, expectedRevision, expectedPhases)
                if (current !is FirstContactOperationResult.Success) {
                    return@withTransaction current.castFailure()
                }
                val changed = dao.transitionIfCurrent(
                    id = id.value,
                    expectedUpdatedTimestampMillis = expectedRevision.updatedTimestampMillis,
                    expectedPhase = current.value.phase.storageCode,
                    targetPhase = targetPhase.storageCode,
                    updatedTimestampMillis = updatedTimestampMillis,
                )
                if (changed != 1) abort(FirstContactOperationResult.StaleWrite)
                dao.findById(id.value).toOperationResultOrAbort()
            }
        }

    private suspend fun currentForTransition(
        id: FirstContactOperationId,
        expectedRevision: FirstContactOperationRevision,
        expectedPhases: Set<FirstContactOperationPhase>,
    ): FirstContactOperationResult<FirstContactOperation> {
        val entity = dao.findById(id.value) ?: return FirstContactOperationResult.NotFound
        val operation = entity.toDomainOrNull() ?: return FirstContactOperationResult.CorruptData
        if (operation.revision != expectedRevision) return FirstContactOperationResult.StaleWrite
        if (operation.phase !in expectedPhases) return FirstContactOperationResult.PhaseMismatch
        return FirstContactOperationResult.Success(operation)
    }

    private suspend fun exactSourceSnapshot(
        operation: FirstContactOperation,
    ): SourceSnapshotResult {
        val draftEntity = dao.findDraft(operation.draftId.value)
            ?: return SourceSnapshotResult.NotFound
        val draft = draftEntity.toDomainOrNull() ?: return SourceSnapshotResult.CorruptData
        val participantIdentity = draft.identity as? DraftIdentity.ParticipantSet
            ?: return SourceSnapshotResult.Stale
        if (draft.revision != operation.sourceDraftRevision) return SourceSnapshotResult.Stale
        val participants = draftEntity.participantSetKey?.toParticipantsOrNull()
            ?: return SourceSnapshotResult.CorruptData
        val semanticKey = runCatching {
            FirstContactParticipantSetKey.fromParticipants(participants)
        }.getOrNull() ?: return SourceSnapshotResult.CorruptData
        if (
            semanticKey != operation.participantSetKey ||
            DraftParticipantSetKey.fromParticipants(participants) != participantIdentity.key
        ) {
            return SourceSnapshotResult.Stale
        }
        val attachments = dao.findAttachments(operation.draftId.value).toDomainListOrNull()
            ?: return SourceSnapshotResult.CorruptData
        if (
            FirstContactAttachmentSetEvidence.fromAttachments(attachments) !=
            operation.attachmentSetEvidence
        ) {
            return SourceSnapshotResult.Stale
        }
        if (!isEligible(draft, participants, attachments, operation.transport)) {
            return SourceSnapshotResult.Stale
        }
        return SourceSnapshotResult.Success(
            SourceSnapshot(
                draft = draft,
                participants = participants,
                attachments = attachments,
                participantSetStorageKey = checkNotNull(draftEntity.participantSetKey),
            ),
        )
    }

    private fun validateUpdatedTimestamp(
        expectedRevision: FirstContactOperationRevision,
        updatedTimestampMillis: Long,
    ): FirstContactOperationResult<Nothing>? =
        FirstContactOperationResult.InvalidTimestamp.takeIf {
            updatedTimestampMillis <= expectedRevision.updatedTimestampMillis ||
                updatedTimestampMillis >= Long.MAX_VALUE
        }

    private suspend fun <T> store(
        operation: FirstContactStorageOperation,
        constraintIsConflict: Boolean = false,
        block: suspend () -> FirstContactOperationResult<T>,
    ): FirstContactOperationResult<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (abort: AbortFirstContactTransaction) {
        abort.result
    } catch (_: SQLiteConstraintException) {
        if (constraintIsConflict) {
            FirstContactOperationResult.Conflict
        } else {
            FirstContactOperationResult.CorruptData
        }
    } catch (_: SQLiteException) {
        FirstContactOperationResult.StorageFailure(operation)
    } catch (_: IllegalArgumentException) {
        FirstContactOperationResult.CorruptData
    } catch (_: IllegalStateException) {
        FirstContactOperationResult.CorruptData
    }
}

private data class SourceSnapshot(
    val draft: Draft,
    val participants: List<ParticipantAddress>,
    val attachments: List<DraftAttachment>,
    val participantSetStorageKey: String,
)

private sealed interface SourceSnapshotResult {
    data class Success(val snapshot: SourceSnapshot) : SourceSnapshotResult
    data object NotFound : SourceSnapshotResult
    data object Stale : SourceSnapshotResult
    data object CorruptData : SourceSnapshotResult
}

private fun SourceSnapshotResult.toOperationFailure(): FirstContactOperationResult<Nothing> =
    when (this) {
        is SourceSnapshotResult.Success -> error("A successful source snapshot is not a failure")
        SourceSnapshotResult.NotFound -> FirstContactOperationResult.NotFound
        SourceSnapshotResult.Stale -> FirstContactOperationResult.StaleWrite
        SourceSnapshotResult.CorruptData -> FirstContactOperationResult.CorruptData
    }

private fun isEligible(
    draft: Draft,
    participants: List<ParticipantAddress>,
    attachments: List<DraftAttachment>,
    transport: MessageTransportKind,
): Boolean {
    val semanticParticipantCount = participants.map { participant ->
        ParticipantAddressEquivalenceKey.from(participant) ?: return false
    }.distinct().size
    if (semanticParticipantCount !in 1..FirstContactParticipantSetKey.MAX_PARTICIPANTS) return false
    val body = draft.body?.takeIf(String::isNotBlank)
    val subject = draft.subject
    if (subject != null && subject.isBlank()) return false
    return when (transport) {
        MessageTransportKind.SMS ->
            semanticParticipantCount == 1 && body != null && subject == null && attachments.isEmpty()
        MessageTransportKind.MMS ->
            body != null || subject != null || attachments.isNotEmpty()
    }
}

private fun String.toParticipantsOrNull(): List<ParticipantAddress>? = try {
    split('\u001f').map(::ParticipantAddress)
} catch (_: IllegalArgumentException) {
    null
}

internal fun List<DraftAttachmentEntity>.toDomainListOrNull(): List<DraftAttachment>? = try {
    if (size > DraftAttachment.MAX_ATTACHMENTS) return null
    mapIndexed { index, entity -> entity.toDomain(index) }
        .takeIf(DraftAttachment::isValidSet)
} catch (_: IllegalArgumentException) {
    null
} catch (_: IllegalStateException) {
    null
}

private fun DraftEntity.toDomainOrNull(): Draft? = try {
    toDomain()
} catch (_: IllegalArgumentException) {
    null
} catch (_: IllegalStateException) {
    null
}

internal fun FirstContactOperationEntity.toDomainOrNull(): FirstContactOperation? = try {
    toDomain()
} catch (_: IllegalArgumentException) {
    null
} catch (_: IllegalStateException) {
    null
}

private fun FirstContactOperationEntity?.toOperationResult():
    FirstContactOperationResult<FirstContactOperation> {
    if (this == null) return FirstContactOperationResult.NotFound
    return toDomainOrNull()?.let { FirstContactOperationResult.Success(it) }
        ?: FirstContactOperationResult.CorruptData
}

private fun FirstContactOperationEntity?.toOperationResultOrAbort():
    FirstContactOperationResult<FirstContactOperation> =
    when (val result = toOperationResult()) {
        is FirstContactOperationResult.Success -> result
        else -> abort(FirstContactOperationResult.CorruptData)
    }

private fun <T> FirstContactOperationResult<T>.castFailure(): FirstContactOperationResult<Nothing> =
    when (this) {
        is FirstContactOperationResult.Success -> error("A success cannot be cast to failure")
        FirstContactOperationResult.NotFound -> FirstContactOperationResult.NotFound
        FirstContactOperationResult.Conflict -> FirstContactOperationResult.Conflict
        FirstContactOperationResult.LimitExceeded -> FirstContactOperationResult.LimitExceeded
        FirstContactOperationResult.StaleWrite -> FirstContactOperationResult.StaleWrite
        FirstContactOperationResult.IneligibleDraft -> FirstContactOperationResult.IneligibleDraft
        FirstContactOperationResult.PhaseMismatch -> FirstContactOperationResult.PhaseMismatch
        FirstContactOperationResult.InvalidTimestamp -> FirstContactOperationResult.InvalidTimestamp
        FirstContactOperationResult.CorruptData -> FirstContactOperationResult.CorruptData
        is FirstContactOperationResult.StorageFailure -> this
    }

private class AbortFirstContactTransaction(
    val result: FirstContactOperationResult<Nothing>,
) : RuntimeException(null, null, false, false)

private fun abort(result: FirstContactOperationResult<Nothing>): Nothing =
    throw AbortFirstContactTransaction(result)
