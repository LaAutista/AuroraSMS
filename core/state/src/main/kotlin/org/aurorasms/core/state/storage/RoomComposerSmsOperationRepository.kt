// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import org.aurorasms.core.model.COMPOSER_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.AcknowledgedComposerSmsCallbackProof
import org.aurorasms.core.state.AcknowledgedComposerSmsReceipt
import org.aurorasms.core.state.ComposerSmsDraftClearance
import org.aurorasms.core.state.ComposerSmsOperation
import org.aurorasms.core.state.ComposerSmsOperationPhase
import org.aurorasms.core.state.ComposerSmsOperationRepository
import org.aurorasms.core.state.ComposerSmsOperationResult
import org.aurorasms.core.state.ComposerSmsOperationRevision
import org.aurorasms.core.state.ComposerSmsProviderBinding
import org.aurorasms.core.state.ComposerSmsReservation
import org.aurorasms.core.state.ComposerSmsReservationRequest
import org.aurorasms.core.state.ComposerSmsSentCompletion
import org.aurorasms.core.state.ComposerSmsStorageOperation
import org.aurorasms.core.state.DraftIdentity
import org.aurorasms.core.state.FirstContactAttachmentSetEvidence
import org.aurorasms.core.state.FirstContactOperation
import org.aurorasms.core.state.FirstContactOperationPhase
import org.aurorasms.core.state.MAXIMUM_COMPOSER_SMS_OPERATIONS
import org.aurorasms.core.state.MAXIMUM_ACKNOWLEDGED_COMPOSER_SMS_RECEIPTS
import org.aurorasms.core.state.isComposerSmsOperationId
import org.aurorasms.core.state.providerKind

class RoomComposerSmsOperationRepository(
    private val database: AuroraStateDatabase,
) : ComposerSmsOperationRepository {
    private val dao = database.composerSmsOperationDao()
    private val firstContactDao = database.firstContactOperationDao()

    override suspend fun reserve(
        request: ComposerSmsReservationRequest,
    ): ComposerSmsOperationResult<ComposerSmsReservation> = store(ComposerSmsStorageOperation.RESERVE) {
        database.withTransaction<ComposerSmsOperationResult<ComposerSmsReservation>> {
            dao.findByProviderThreadId(request.providerThreadId.value)?.let { existing ->
                return@withTransaction if (existing.toDomainOrNull() == null) {
                    ComposerSmsOperationResult.CorruptData
                } else {
                    ComposerSmsOperationResult.Conflict
                }
            }
            val draftOwner = firstContactDao.findByDraft(request.draftId.value)
            val threadOwner = firstContactDao.findByProviderThread(request.providerThreadId.value)
            val authority = request.firstContactAuthority
            val handoff: FirstContactOperation? = if (authority == null) {
                if (draftOwner != null || threadOwner != null) {
                    return@withTransaction ComposerSmsOperationResult.Conflict
                }
                null
            } else {
                val entity = firstContactDao.findById(authority.operationId.value)
                    ?: return@withTransaction ComposerSmsOperationResult.StaleWrite
                val operation = entity.toDomainOrNull()
                    ?: return@withTransaction ComposerSmsOperationResult.CorruptData
                if (operation.revision != authority.expectedRevision) {
                    return@withTransaction ComposerSmsOperationResult.StaleWrite
                }
                if (
                    operation.participantSetKey != authority.participantSetKey ||
                    operation.attachmentSetEvidence != authority.attachmentSetEvidence ||
                    draftOwner?.firstContactId != operation.id.value ||
                    threadOwner?.firstContactId != operation.id.value ||
                    operation.phase != FirstContactOperationPhase.HANDOFF_RESERVED ||
                    operation.providerThreadId != request.providerThreadId ||
                    operation.draftId != request.draftId ||
                    operation.subscriptionId != request.subscriptionId ||
                    operation.transport != request.transport ||
                    operation.frozenSignature != request.frozenSignature
                ) {
                    return@withTransaction ComposerSmsOperationResult.Conflict
                }
                if (operation.handoffDraftRevision != request.expectedDraftRevision) {
                    return@withTransaction ComposerSmsOperationResult.StaleWrite
                }
                if (firstContactDao.hasConflictingThreadAction(request.providerThreadId.value)) {
                    return@withTransaction ComposerSmsOperationResult.Conflict
                }
                operation
            }
            val count = dao.count()
            if (count < 0 || count > MAXIMUM_COMPOSER_SMS_OPERATIONS) {
                return@withTransaction ComposerSmsOperationResult.CorruptData
            }
            if (count == MAXIMUM_COMPOSER_SMS_OPERATIONS) {
                return@withTransaction ComposerSmsOperationResult.LimitExceeded
            }
            val draftEntity = dao.findDraftById(request.draftId.value)
                ?: return@withTransaction ComposerSmsOperationResult.NotFound
            val draft = try {
                draftEntity.toDomain()
            } catch (_: IllegalArgumentException) {
                return@withTransaction ComposerSmsOperationResult.CorruptData
            } catch (_: IllegalStateException) {
                return@withTransaction ComposerSmsOperationResult.CorruptData
            }
            if (
                draft.identity != DraftIdentity.ProviderThread(request.providerThreadId) ||
                draft.revision != request.expectedDraftRevision
            ) {
                return@withTransaction ComposerSmsOperationResult.StaleWrite
            }
            if (handoff != null) {
                val attachments = firstContactDao.findAttachments(draft.id.value).toDomainListOrNull()
                    ?: return@withTransaction ComposerSmsOperationResult.CorruptData
                if (
                    FirstContactAttachmentSetEvidence.fromAttachments(attachments) !=
                    authority?.attachmentSetEvidence ||
                    request.hasAttachments != attachments.isNotEmpty()
                ) {
                    return@withTransaction ComposerSmsOperationResult.StaleWrite
                }
            }
            val body = draft.body?.takeIf(String::isNotBlank)
            if (
                body == null &&
                !(request.transport == org.aurorasms.core.model.MessageTransportKind.MMS &&
                    request.hasAttachments)
            ) {
                return@withTransaction ComposerSmsOperationResult.IneligibleDraft
            }
            if (request.transport == org.aurorasms.core.model.MessageTransportKind.SMS && draft.subject != null) {
                return@withTransaction ComposerSmsOperationResult.IneligibleDraft
            }
            val localId = dao.insert(
                ComposerSmsOperationEntity(
                    providerThreadId = request.providerThreadId.value,
                    draftId = request.draftId.value,
                    draftRevisionMillis = request.expectedDraftRevision.updatedTimestampMillis,
                    subscriptionId = request.subscriptionId.value,
                    transportCode = request.transport.storageCode,
                    phaseCode = ComposerSmsOperationPhase.RESERVED.storageCode,
                    providerMessageId = null,
                    providerConversationId = null,
                    unitCount = null,
                    createdTimestampMillis = request.createdTimestampMillis,
                    updatedTimestampMillis = request.createdTimestampMillis,
                    signatureText = request.frozenSignature?.value,
                ),
            )
            if (localId <= 0L || localId >= COMPOSER_SMS_LOCAL_ID_LIMIT_EXCLUSIVE) {
                throw AbortTransaction(ComposerSmsOperationResult.CorruptData)
            }
            val operation = dao.findByLocalId(localId)?.toDomainOrNull()
                ?: throw AbortTransaction(ComposerSmsOperationResult.CorruptData)
            if (
                handoff != null &&
                firstContactDao.deleteExactHandoffIfComposerReserved(
                    id = handoff.id.value,
                    participantSetKey = handoff.participantSetKey.toStorageValue(),
                    draftId = handoff.draftId.value,
                    subscriptionId = handoff.subscriptionId.value,
                    transportCode = handoff.transport.storageCode,
                    providerThreadId = request.providerThreadId.value,
                    handoffDraftRevisionMillis = request.expectedDraftRevision.updatedTimestampMillis,
                    expectedUpdatedTimestampMillis = handoff.revision.updatedTimestampMillis,
                    signatureText = handoff.frozenSignature?.value,
                    handoffReservedPhase = FirstContactOperationPhase.HANDOFF_RESERVED.storageCode,
                ) != 1
            ) {
                throw AbortTransaction(ComposerSmsOperationResult.StaleWrite)
            }
            ComposerSmsOperationResult.Success(
                ComposerSmsReservation(
                    operation = operation,
                    authoritativeBody = body,
                    authoritativeSubject = draft.subject,
                ),
            )
        }
    }

    override suspend fun read(
        operationId: MessageId,
    ): ComposerSmsOperationResult<ComposerSmsOperation> = store(ComposerSmsStorageOperation.READ) {
        val localId = operationId.localComposerIdOrNull()
            ?: return@store ComposerSmsOperationResult.NotFound
        val entity = dao.findByLocalId(localId) ?: return@store ComposerSmsOperationResult.NotFound
        entity.toDomainResult()
    }

    override suspend fun readAcknowledged(
        operationId: MessageId,
    ): ComposerSmsOperationResult<AcknowledgedComposerSmsReceipt> =
        store(ComposerSmsStorageOperation.READ) {
            val localId = operationId.localComposerIdOrNull()
                ?: return@store ComposerSmsOperationResult.NotFound
            val entity = dao.findAcknowledgedByLocalId(localId)
                ?: return@store ComposerSmsOperationResult.NotFound
            entity.toAcknowledgedDomainResult()
        }

    override fun observeByThread(
        providerThreadId: ProviderThreadId,
    ): Flow<ComposerSmsOperationResult<ComposerSmsOperation?>> =
        dao.observeByProviderThreadId(providerThreadId.value)
            .map { entity ->
                if (entity == null) {
                    ComposerSmsOperationResult.Success(null)
                } else {
                    when (val mapped = entity.toDomainResult()) {
                        is ComposerSmsOperationResult.Success ->
                            ComposerSmsOperationResult.Success(mapped.value)
                        else -> mapped
                    }
                }
            }
            .retryComposerSmsObservationFailures()
            .distinctUntilChanged()

    override suspend fun recoverySnapshot(): ComposerSmsOperationResult<List<ComposerSmsOperation>> =
        store(ComposerSmsStorageOperation.RECOVER) {
            val entities = dao.recoverySnapshot(MAXIMUM_COMPOSER_SMS_OPERATIONS + 1)
            if (entities.size > MAXIMUM_COMPOSER_SMS_OPERATIONS) {
                return@store ComposerSmsOperationResult.CorruptData
            }
            val operations = ArrayList<ComposerSmsOperation>(entities.size)
            val threads = HashSet<ProviderThreadId>()
            entities.forEach { entity ->
                val operation = entity.toDomainOrNull()
                    ?: return@store ComposerSmsOperationResult.CorruptData
                if (!threads.add(operation.providerThreadId)) {
                    return@store ComposerSmsOperationResult.CorruptData
                }
                operations += operation
            }
            ComposerSmsOperationResult.Success(operations)
        }

    override suspend fun acknowledgedRecoverySnapshot():
        ComposerSmsOperationResult<List<AcknowledgedComposerSmsReceipt>> =
        store(ComposerSmsStorageOperation.RECOVER) {
            val entities = dao.acknowledgedRecoverySnapshot(
                MAXIMUM_ACKNOWLEDGED_COMPOSER_SMS_RECEIPTS + 1,
            )
            if (entities.size > MAXIMUM_ACKNOWLEDGED_COMPOSER_SMS_RECEIPTS) {
                return@store ComposerSmsOperationResult.CorruptData
            }
            val receipts = ArrayList<AcknowledgedComposerSmsReceipt>(entities.size)
            entities.forEach { entity ->
                receipts += entity.toAcknowledgedDomainOrNull()
                    ?: return@store ComposerSmsOperationResult.CorruptData
            }
            ComposerSmsOperationResult.Success(receipts)
        }

    override suspend fun markPrepared(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation> =
        store(ComposerSmsStorageOperation.TRANSITION) {
            transitionPrepared(
                operationId,
                expectedRevision,
                providerBinding,
                updatedTimestampMillis,
            )
        }

    override suspend fun markSubmitting(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation> = transitionBound(
        operationId = operationId,
        expectedRevision = expectedRevision,
        providerBinding = providerBinding,
        expectedPhase = ComposerSmsOperationPhase.PREPARED,
        targetPhase = ComposerSmsOperationPhase.SUBMITTING,
        updatedTimestampMillis = updatedTimestampMillis,
    )

    override suspend fun markPlatformAccepted(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation> = transitionBound(
        operationId = operationId,
        expectedRevision = expectedRevision,
        providerBinding = providerBinding,
        expectedPhase = ComposerSmsOperationPhase.SUBMITTING,
        targetPhase = ComposerSmsOperationPhase.PLATFORM_ACCEPTED,
        updatedTimestampMillis = updatedTimestampMillis,
    )

    override suspend fun markSubmissionUnknown(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation> = store(ComposerSmsStorageOperation.TRANSITION) {
        val localId = operationId.localComposerIdOrNull()
            ?: return@store ComposerSmsOperationResult.NotFound
        if (updatedTimestampMillis <= expectedRevision.updatedTimestampMillis) {
            return@store ComposerSmsOperationResult.InvalidTimestamp
        }
        database.withTransaction<ComposerSmsOperationResult<ComposerSmsOperation>> {
            val current = dao.findByLocalId(localId)?.toDomainOrNull()
                ?: return@withTransaction missingOrCorrupt(localId)
            val mismatch = current.casMismatch(expectedRevision, providerBinding)
            if (mismatch != null) return@withTransaction mismatch
            if (current.phase == ComposerSmsOperationPhase.SUBMISSION_UNKNOWN) {
                return@withTransaction ComposerSmsOperationResult.Success(current)
            }
            if (
                current.phase != ComposerSmsOperationPhase.SUBMITTING &&
                current.phase != ComposerSmsOperationPhase.PLATFORM_ACCEPTED
            ) {
                return@withTransaction ComposerSmsOperationResult.PhaseMismatch
            }
            val changed = dao.transitionBoundIfCurrent(
                localOperationId = localId,
                expectedUpdatedTimestampMillis = expectedRevision.updatedTimestampMillis,
                expectedPhase = current.phase.storageCode,
                targetPhase = ComposerSmsOperationPhase.SUBMISSION_UNKNOWN.storageCode,
                providerMessageId = providerBinding.providerMessageId.value,
                providerConversationId = providerBinding.providerConversationId.value,
                unitCount = providerBinding.unitCount,
                updatedTimestampMillis = updatedTimestampMillis,
            )
            updatedOperation(changed, localId)
        }
    }

    override suspend fun markKnownUnsent(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation> = store(ComposerSmsStorageOperation.TRANSITION) {
        val localId = operationId.localComposerIdOrNull()
            ?: return@store ComposerSmsOperationResult.NotFound
        if (updatedTimestampMillis <= expectedRevision.updatedTimestampMillis) {
            return@store ComposerSmsOperationResult.InvalidTimestamp
        }
        database.withTransaction {
            val current = dao.findByLocalId(localId)?.toDomainOrNull()
                ?: return@withTransaction missingOrCorrupt(localId)
            if (current.revision != expectedRevision) {
                return@withTransaction ComposerSmsOperationResult.StaleWrite
            }
            if (
                current.phase != ComposerSmsOperationPhase.RESERVED &&
                current.phase != ComposerSmsOperationPhase.PREPARED
            ) {
                return@withTransaction ComposerSmsOperationResult.PhaseMismatch
            }
            val changed = dao.markKnownUnsentIfCurrent(
                localOperationId = localId,
                expectedUpdatedTimestampMillis = expectedRevision.updatedTimestampMillis,
                expectedPhase = current.phase.storageCode,
                knownUnsentPhase = ComposerSmsOperationPhase.KNOWN_UNSENT.storageCode,
                updatedTimestampMillis = updatedTimestampMillis,
            )
            updatedOperation(changed, localId)
        }
    }

    override suspend fun markSentCallbackSucceeded(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation> = transitionExactCallback(
        operationId = operationId,
        expectedRevision = expectedRevision,
        providerBinding = providerBinding,
        sourcePhases = SENT_CALLBACK_SUCCESS_SOURCE_PHASES,
        targetPhase = ComposerSmsOperationPhase.SENT_CALLBACK_SUCCEEDED,
        updatedTimestampMillis = updatedTimestampMillis,
    )

    override suspend fun markSentCallbackFailed(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation> = transitionExactCallback(
        operationId = operationId,
        expectedRevision = expectedRevision,
        providerBinding = providerBinding,
        sourcePhases = SENT_CALLBACK_FAILURE_SOURCE_PHASES,
        targetPhase = ComposerSmsOperationPhase.KNOWN_UNSENT,
        updatedTimestampMillis = updatedTimestampMillis,
    )

    private suspend fun transitionExactCallback(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        sourcePhases: Set<ComposerSmsOperationPhase>,
        targetPhase: ComposerSmsOperationPhase,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation> = store(ComposerSmsStorageOperation.TRANSITION) {
        val localId = operationId.localComposerIdOrNull()
            ?: return@store ComposerSmsOperationResult.NotFound
        if (updatedTimestampMillis <= expectedRevision.updatedTimestampMillis) {
            return@store ComposerSmsOperationResult.InvalidTimestamp
        }
        database.withTransaction {
            val current = dao.findByLocalId(localId)?.toDomainOrNull()
                ?: return@withTransaction missingOrCorrupt(localId)
            val mismatch = current.casMismatch(expectedRevision, providerBinding)
            if (mismatch != null) return@withTransaction mismatch
            if (current.phase !in sourcePhases) {
                return@withTransaction ComposerSmsOperationResult.PhaseMismatch
            }
            val changed = dao.transitionBoundIfCurrent(
                localOperationId = localId,
                expectedUpdatedTimestampMillis = expectedRevision.updatedTimestampMillis,
                expectedPhase = current.phase.storageCode,
                targetPhase = targetPhase.storageCode,
                providerMessageId = providerBinding.providerMessageId.value,
                providerConversationId = providerBinding.providerConversationId.value,
                unitCount = providerBinding.unitCount,
                updatedTimestampMillis = updatedTimestampMillis,
            )
            updatedOperation(changed, localId)
        }
    }

    override suspend fun completeSentAndRemove(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
    ): ComposerSmsOperationResult<ComposerSmsSentCompletion> = store(ComposerSmsStorageOperation.COMPLETE_SENT) {
        val localId = operationId.localComposerIdOrNull()
            ?: return@store ComposerSmsOperationResult.NotFound
        database.withTransaction<ComposerSmsOperationResult<ComposerSmsSentCompletion>> {
            val current = dao.findByLocalId(localId)?.toDomainOrNull()
                ?: return@withTransaction missingOrCorrupt(localId)
            val mismatch = current.casMismatch(expectedRevision, providerBinding)
            if (mismatch != null) return@withTransaction mismatch
            if (current.phase != ComposerSmsOperationPhase.SENT_CALLBACK_SUCCEEDED) {
                return@withTransaction ComposerSmsOperationResult.PhaseMismatch
            }
            val draftClearance = clearExactDraft(current)
            val deleted = dao.deleteBoundIfCurrent(
                localOperationId = localId,
                expectedUpdatedTimestampMillis = expectedRevision.updatedTimestampMillis,
                expectedPhase = current.phase.storageCode,
                providerMessageId = providerBinding.providerMessageId.value,
                providerConversationId = providerBinding.providerConversationId.value,
                unitCount = providerBinding.unitCount,
            )
            if (deleted != 1) {
                throw AbortTransaction(
                    ComposerSmsOperationResult.StorageFailure(
                        ComposerSmsStorageOperation.COMPLETE_SENT,
                    ),
                )
            }
            ComposerSmsOperationResult.Success(ComposerSmsSentCompletion(draftClearance))
        }
    }

    override suspend fun acknowledgeAndRemove(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        acknowledgedTimestampMillis: Long,
    ): ComposerSmsOperationResult<Unit> = store(ComposerSmsStorageOperation.ACKNOWLEDGE) {
        val localId = operationId.localComposerIdOrNull()
            ?: return@store ComposerSmsOperationResult.NotFound
        if (acknowledgedTimestampMillis <= expectedRevision.updatedTimestampMillis) {
            return@store ComposerSmsOperationResult.InvalidTimestamp
        }
        database.withTransaction<ComposerSmsOperationResult<Unit>> {
            val current = dao.findByLocalId(localId)?.toDomainOrNull()
                ?: return@withTransaction missingOrCorrupt(localId)
            if (current.revision != expectedRevision) {
                return@withTransaction ComposerSmsOperationResult.StaleWrite
            }
            if (
                current.phase != ComposerSmsOperationPhase.KNOWN_UNSENT &&
                current.phase != ComposerSmsOperationPhase.SUBMISSION_UNKNOWN
            ) {
                return@withTransaction ComposerSmsOperationResult.PhaseMismatch
            }
            if (current.phase == ComposerSmsOperationPhase.SUBMISSION_UNKNOWN) {
                val binding = current.providerBinding
                    ?: return@withTransaction ComposerSmsOperationResult.CorruptData
                if (dao.findAcknowledgedByLocalId(localId) != null) {
                    return@withTransaction ComposerSmsOperationResult.CorruptData
                }
                val count = dao.acknowledgedCount()
                if (count < 0 || count > MAXIMUM_ACKNOWLEDGED_COMPOSER_SMS_RECEIPTS) {
                    return@withTransaction ComposerSmsOperationResult.CorruptData
                }
                if (count == MAXIMUM_ACKNOWLEDGED_COMPOSER_SMS_RECEIPTS) {
                    return@withTransaction ComposerSmsOperationResult.LimitExceeded
                }
                dao.insertAcknowledged(
                    AcknowledgedComposerSmsEntity(
                        localOperationId = localId,
                        providerMessageId = binding.providerMessageId.value,
                        providerKindCode = binding.providerMessageId.kind.storageCode,
                        providerConversationId = binding.providerConversationId.value,
                        unitCount = binding.unitCount,
                        callbackProofCode =
                            AcknowledgedComposerSmsCallbackProof.AWAITING_CALLBACK.storageCode,
                        acknowledgedTimestampMillis = acknowledgedTimestampMillis,
                        updatedTimestampMillis = acknowledgedTimestampMillis,
                    ),
                )
            }
            val deleted = dao.deleteTerminalIfCurrent(
                localOperationId = localId,
                expectedUpdatedTimestampMillis = expectedRevision.updatedTimestampMillis,
                expectedPhase = current.phase.storageCode,
            )
            if (deleted != 1) {
                throw AbortTransaction(
                    if (deleted == 0) {
                        classifyCasFailure(localId, expectedRevision, current.phase, current.providerBinding)
                    } else {
                        ComposerSmsOperationResult.CorruptData
                    },
                )
            }
            ComposerSmsOperationResult.Success(Unit)
        }
    }

    override suspend fun markAcknowledgedSent(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<AcknowledgedComposerSmsReceipt> =
        markAcknowledgedCallback(
            operationId = operationId,
            expectedRevision = expectedRevision,
            providerBinding = providerBinding,
            targetProof = AcknowledgedComposerSmsCallbackProof.SENT,
            updatedTimestampMillis = updatedTimestampMillis,
        )

    override suspend fun markAcknowledgedFailed(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<AcknowledgedComposerSmsReceipt> =
        markAcknowledgedCallback(
            operationId = operationId,
            expectedRevision = expectedRevision,
            providerBinding = providerBinding,
            targetProof = AcknowledgedComposerSmsCallbackProof.FAILED,
            updatedTimestampMillis = updatedTimestampMillis,
        )

    private suspend fun markAcknowledgedCallback(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        targetProof: AcknowledgedComposerSmsCallbackProof,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<AcknowledgedComposerSmsReceipt> =
        store(ComposerSmsStorageOperation.TRANSITION) {
            val localId = operationId.localComposerIdOrNull()
                ?: return@store ComposerSmsOperationResult.NotFound
            if (updatedTimestampMillis <= expectedRevision.updatedTimestampMillis) {
                return@store ComposerSmsOperationResult.InvalidTimestamp
            }
            database.withTransaction<ComposerSmsOperationResult<AcknowledgedComposerSmsReceipt>> {
                val current = dao.findAcknowledgedByLocalId(localId)?.toAcknowledgedDomainOrNull()
                    ?: return@withTransaction missingOrCorruptAcknowledged(localId)
                when {
                    current.revision != expectedRevision ->
                        return@withTransaction ComposerSmsOperationResult.StaleWrite
                    current.providerBinding != providerBinding ->
                        return@withTransaction ComposerSmsOperationResult.ProviderMismatch
                    current.callbackProof == targetProof ->
                        return@withTransaction ComposerSmsOperationResult.Success(current)
                    current.callbackProof != AcknowledgedComposerSmsCallbackProof.AWAITING_CALLBACK ->
                        return@withTransaction ComposerSmsOperationResult.PhaseMismatch
                }
                val changed = dao.markAcknowledgedCallbackIfCurrent(
                    localOperationId = localId,
                    expectedUpdatedTimestampMillis = expectedRevision.updatedTimestampMillis,
                    awaitingCallbackProof =
                        AcknowledgedComposerSmsCallbackProof.AWAITING_CALLBACK.storageCode,
                    targetCallbackProof = targetProof.storageCode,
                    providerMessageId = providerBinding.providerMessageId.value,
                    providerConversationId = providerBinding.providerConversationId.value,
                    unitCount = providerBinding.unitCount,
                    updatedTimestampMillis = updatedTimestampMillis,
                )
                when (changed) {
                    1 -> dao.findAcknowledgedByLocalId(localId)?.toAcknowledgedDomainResult()
                        ?: ComposerSmsOperationResult.CorruptData
                    0 -> ComposerSmsOperationResult.StaleWrite
                    else -> ComposerSmsOperationResult.CorruptData
                }
            }
        }

    override suspend fun completeAcknowledged(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        callbackProof: AcknowledgedComposerSmsCallbackProof,
    ): ComposerSmsOperationResult<Unit> = store(ComposerSmsStorageOperation.ACKNOWLEDGE) {
        val localId = operationId.localComposerIdOrNull()
            ?: return@store ComposerSmsOperationResult.NotFound
        if (callbackProof == AcknowledgedComposerSmsCallbackProof.AWAITING_CALLBACK) {
            return@store ComposerSmsOperationResult.PhaseMismatch
        }
        database.withTransaction<ComposerSmsOperationResult<Unit>> {
            val current = dao.findAcknowledgedByLocalId(localId)?.toAcknowledgedDomainOrNull()
                ?: return@withTransaction missingOrCorruptAcknowledged(localId)
            when {
                current.revision != expectedRevision ->
                    return@withTransaction ComposerSmsOperationResult.StaleWrite
                current.providerBinding != providerBinding ->
                    return@withTransaction ComposerSmsOperationResult.ProviderMismatch
                current.callbackProof != callbackProof ->
                    return@withTransaction ComposerSmsOperationResult.PhaseMismatch
            }
            when (
                dao.deleteAcknowledgedIfCurrent(
                    localOperationId = localId,
                    expectedUpdatedTimestampMillis = expectedRevision.updatedTimestampMillis,
                    expectedCallbackProof = callbackProof.storageCode,
                    providerMessageId = providerBinding.providerMessageId.value,
                    providerConversationId = providerBinding.providerConversationId.value,
                    unitCount = providerBinding.unitCount,
                )
            ) {
                1 -> ComposerSmsOperationResult.Success(Unit)
                0 -> ComposerSmsOperationResult.StaleWrite
                else -> ComposerSmsOperationResult.CorruptData
            }
        }
    }

    private suspend fun transitionPrepared(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation> {
        val localId = operationId.localComposerIdOrNull()
            ?: return ComposerSmsOperationResult.NotFound
        if (updatedTimestampMillis <= expectedRevision.updatedTimestampMillis) {
            return ComposerSmsOperationResult.InvalidTimestamp
        }
        return database.withTransaction {
            val current = dao.findByLocalId(localId)?.toDomainOrNull()
                ?: return@withTransaction missingOrCorrupt(localId)
            when {
                current.revision != expectedRevision ->
                    return@withTransaction ComposerSmsOperationResult.StaleWrite
                current.phase != ComposerSmsOperationPhase.RESERVED ->
                    return@withTransaction ComposerSmsOperationResult.PhaseMismatch
                current.providerBinding != null ->
                    return@withTransaction ComposerSmsOperationResult.CorruptData
                current.transport.providerKind != providerBinding.providerMessageId.kind ->
                    return@withTransaction ComposerSmsOperationResult.ProviderMismatch
            }
            val changed = dao.markPreparedIfCurrent(
                localOperationId = localId,
                expectedUpdatedTimestampMillis = expectedRevision.updatedTimestampMillis,
                providerMessageId = providerBinding.providerMessageId.value,
                providerConversationId = providerBinding.providerConversationId.value,
                unitCount = providerBinding.unitCount,
                updatedTimestampMillis = updatedTimestampMillis,
                reservedPhase = ComposerSmsOperationPhase.RESERVED.storageCode,
                preparedPhase = ComposerSmsOperationPhase.PREPARED.storageCode,
            )
            if (changed == 1) {
                updatedOperation(changed, localId)
            } else {
                classifyCasFailure(
                    localId,
                    expectedRevision,
                    ComposerSmsOperationPhase.RESERVED,
                    expectedBinding = null,
                )
            }
        }
    }

    private suspend fun transitionBound(
        operationId: MessageId,
        expectedRevision: ComposerSmsOperationRevision,
        providerBinding: ComposerSmsProviderBinding,
        expectedPhase: ComposerSmsOperationPhase,
        targetPhase: ComposerSmsOperationPhase,
        updatedTimestampMillis: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation> = store(ComposerSmsStorageOperation.TRANSITION) {
        val localId = operationId.localComposerIdOrNull()
            ?: return@store ComposerSmsOperationResult.NotFound
        if (updatedTimestampMillis <= expectedRevision.updatedTimestampMillis) {
            return@store ComposerSmsOperationResult.InvalidTimestamp
        }
        database.withTransaction {
            val changed = dao.transitionBoundIfCurrent(
                localOperationId = localId,
                expectedUpdatedTimestampMillis = expectedRevision.updatedTimestampMillis,
                expectedPhase = expectedPhase.storageCode,
                targetPhase = targetPhase.storageCode,
                providerMessageId = providerBinding.providerMessageId.value,
                providerConversationId = providerBinding.providerConversationId.value,
                unitCount = providerBinding.unitCount,
                updatedTimestampMillis = updatedTimestampMillis,
            )
            if (changed == 1) {
                updatedOperation(changed, localId)
            } else {
                classifyCasFailure(localId, expectedRevision, expectedPhase, providerBinding)
            }
        }
    }

    private suspend fun clearExactDraft(operation: ComposerSmsOperation): ComposerSmsDraftClearance {
        val entity = dao.findDraftById(operation.draftId.value)
            ?: return ComposerSmsDraftClearance.ALREADY_ABSENT
        val draft = try {
            entity.toDomain()
        } catch (_: IllegalArgumentException) {
            throw AbortTransaction(ComposerSmsOperationResult.CorruptData)
        } catch (_: IllegalStateException) {
            throw AbortTransaction(ComposerSmsOperationResult.CorruptData)
        }
        if (
            draft.identity != DraftIdentity.ProviderThread(operation.providerThreadId) ||
            draft.revision != operation.draftRevision
        ) {
            return ComposerSmsDraftClearance.NEWER_REVISION_PRESERVED
        }
        if (
            dao.deleteExactProviderThreadDraft(
                draftId = operation.draftId.value,
                providerThreadId = operation.providerThreadId.value,
                expectedDraftRevisionMillis = operation.draftRevision.updatedTimestampMillis,
            ) != 1
        ) {
            throw AbortTransaction(
                ComposerSmsOperationResult.StorageFailure(
                    ComposerSmsStorageOperation.COMPLETE_SENT,
                ),
            )
        }
        return ComposerSmsDraftClearance.CLEARED
    }

    private suspend fun updatedOperation(
        changed: Int,
        localId: Long,
    ): ComposerSmsOperationResult<ComposerSmsOperation> = when (changed) {
        1 -> dao.findByLocalId(localId)?.toDomainResult() ?: ComposerSmsOperationResult.CorruptData
        0 -> ComposerSmsOperationResult.StaleWrite
        else -> ComposerSmsOperationResult.CorruptData
    }

    private suspend fun <T> classifyCasFailure(
        localId: Long,
        expectedRevision: ComposerSmsOperationRevision,
        expectedPhase: ComposerSmsOperationPhase,
        expectedBinding: ComposerSmsProviderBinding?,
    ): ComposerSmsOperationResult<T> {
        val currentEntity = dao.findByLocalId(localId) ?: return ComposerSmsOperationResult.NotFound
        val current = currentEntity.toDomainOrNull() ?: return ComposerSmsOperationResult.CorruptData
        return when {
            current.revision != expectedRevision -> ComposerSmsOperationResult.StaleWrite
            current.phase != expectedPhase -> ComposerSmsOperationResult.PhaseMismatch
            current.providerBinding != expectedBinding -> ComposerSmsOperationResult.ProviderMismatch
            else -> ComposerSmsOperationResult.CorruptData
        }
    }

    private suspend fun <T> missingOrCorrupt(
        localId: Long,
    ): ComposerSmsOperationResult<T> =
        if (dao.findByLocalId(localId) == null) {
            ComposerSmsOperationResult.NotFound
        } else {
            ComposerSmsOperationResult.CorruptData
        }

    private suspend fun <T> missingOrCorruptAcknowledged(
        localId: Long,
    ): ComposerSmsOperationResult<T> =
        if (dao.findAcknowledgedByLocalId(localId) == null) {
            ComposerSmsOperationResult.NotFound
        } else {
            ComposerSmsOperationResult.CorruptData
        }

    private fun ComposerSmsOperation.casMismatch(
        expectedRevision: ComposerSmsOperationRevision,
        expectedBinding: ComposerSmsProviderBinding,
    ): ComposerSmsOperationResult<Nothing>? = when {
        revision != expectedRevision -> ComposerSmsOperationResult.StaleWrite
        providerBinding != expectedBinding -> ComposerSmsOperationResult.ProviderMismatch
        else -> null
    }

    private suspend fun <T> store(
        operation: ComposerSmsStorageOperation,
        block: suspend () -> ComposerSmsOperationResult<T>,
    ): ComposerSmsOperationResult<T> = try {
        block()
    } catch (abort: AbortTransaction) {
        @Suppress("UNCHECKED_CAST")
        (abort.result as ComposerSmsOperationResult<T>)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SQLiteConstraintException) {
        ComposerSmsOperationResult.StorageFailure(operation)
    } catch (_: SQLiteException) {
        ComposerSmsOperationResult.StorageFailure(operation)
    } catch (_: IllegalArgumentException) {
        ComposerSmsOperationResult.CorruptData
    } catch (_: IllegalStateException) {
        ComposerSmsOperationResult.StorageFailure(operation)
    }

    private fun ComposerSmsOperationEntity.toDomainOrNull(): ComposerSmsOperation? = try {
        toDomain()
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private fun ComposerSmsOperationEntity.toDomainResult(): ComposerSmsOperationResult<ComposerSmsOperation> =
        toDomainOrNull()?.let { ComposerSmsOperationResult.Success(it) }
            ?: ComposerSmsOperationResult.CorruptData

    private fun AcknowledgedComposerSmsEntity.toAcknowledgedDomainOrNull():
        AcknowledgedComposerSmsReceipt? = try {
        toDomain()
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private fun AcknowledgedComposerSmsEntity.toAcknowledgedDomainResult():
        ComposerSmsOperationResult<AcknowledgedComposerSmsReceipt> =
        toAcknowledgedDomainOrNull()?.let { ComposerSmsOperationResult.Success(it) }
            ?: ComposerSmsOperationResult.CorruptData

    private fun MessageId.localComposerIdOrNull(): Long? =
        takeIf(MessageId::isComposerSmsOperationId)
            ?.let { it.value - COMPOSER_OPERATION_ID_BOUNDARY }

    private class AbortTransaction(
        val result: ComposerSmsOperationResult<*>,
    ) : RuntimeException(null, null, false, false)

    private companion object {
        val SENT_CALLBACK_SUCCESS_SOURCE_PHASES: Set<ComposerSmsOperationPhase> = setOf(
            ComposerSmsOperationPhase.SUBMITTING,
            ComposerSmsOperationPhase.PLATFORM_ACCEPTED,
            ComposerSmsOperationPhase.SUBMISSION_UNKNOWN,
        )
        val SENT_CALLBACK_FAILURE_SOURCE_PHASES: Set<ComposerSmsOperationPhase> = setOf(
            ComposerSmsOperationPhase.SUBMITTING,
            ComposerSmsOperationPhase.PLATFORM_ACCEPTED,
            ComposerSmsOperationPhase.SUBMISSION_UNKNOWN,
        )
    }
}

/**
 * A Flow `catch` emission completes its upstream. Room observations need to
 * re-subscribe after a transient query/storage failure so an already-open
 * Thread can leave RECOVERY_PENDING when storage becomes readable again.
 */
internal fun <T> Flow<ComposerSmsOperationResult<T>>.retryComposerSmsObservationFailures(
    retryDelayMillis: (Long) -> Long = ::composerSmsObservationRetryDelayMillis,
): Flow<ComposerSmsOperationResult<T>> =
    retryWhen { failure, attempt ->
        when {
            failure is CancellationException -> false
            failure.isComposerSmsObservationCorruption() -> false
            failure is SQLiteException || failure is IllegalStateException -> {
                emit(ComposerSmsOperationResult.StorageFailure(ComposerSmsStorageOperation.OBSERVE))
                delay(retryDelayMillis(attempt).coerceAtLeast(0L))
                true
            }
            else -> false
        }
    }
        .catch { failure ->
            if (failure is CancellationException) throw failure
            emit(
                if (failure.isComposerSmsObservationCorruption()) {
                    ComposerSmsOperationResult.CorruptData
                } else {
                    ComposerSmsOperationResult.StorageFailure(ComposerSmsStorageOperation.OBSERVE)
                },
            )
        }

private fun Throwable.isComposerSmsObservationCorruption(): Boolean =
    this is IllegalArgumentException || this is SQLiteDatabaseCorruptException

private fun composerSmsObservationRetryDelayMillis(attempt: Long): Long {
    val shift = attempt.coerceAtMost(COMPOSER_SMS_OBSERVATION_MAXIMUM_RETRY_SHIFT).toInt()
    return (COMPOSER_SMS_OBSERVATION_INITIAL_RETRY_MILLIS shl shift)
        .coerceAtMost(COMPOSER_SMS_OBSERVATION_MAXIMUM_RETRY_MILLIS)
}

private const val COMPOSER_SMS_OBSERVATION_INITIAL_RETRY_MILLIS: Long = 250L
private const val COMPOSER_SMS_OBSERVATION_MAXIMUM_RETRY_MILLIS: Long = 4_000L
private const val COMPOSER_SMS_OBSERVATION_MAXIMUM_RETRY_SHIFT: Long = 4L
