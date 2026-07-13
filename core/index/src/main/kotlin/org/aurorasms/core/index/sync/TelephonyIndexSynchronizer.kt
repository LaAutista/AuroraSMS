// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.sync

import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteException
import java.util.concurrent.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.aurorasms.core.index.IndexCoverage
import org.aurorasms.core.index.IndexFailureCode
import org.aurorasms.core.index.search.RoomMessageIndex
import org.aurorasms.core.index.storage.AuroraIndexDatabase
import org.aurorasms.core.index.storage.GenerationStateCode
import org.aurorasms.core.index.storage.IndexCheckpointEntity
import org.aurorasms.core.index.storage.IndexGenerationEntity
import org.aurorasms.core.index.storage.IndexFailureCodeValue
import org.aurorasms.core.index.storage.MAXIMUM_INDEX_BATCH_SIZE
import org.aurorasms.core.index.storage.MINIMUM_INDEX_BATCH_SIZE
import org.aurorasms.core.index.storage.ProviderKindCode
import org.aurorasms.core.index.storage.toIndexStorageCode
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.MmsProviderDataSource
import org.aurorasms.core.telephony.ProviderPageCursor
import org.aurorasms.core.telephony.SmsProviderDataSource

/** Owns one serialized, resumable Telephony-to-index synchronization protocol. */
class TelephonyIndexSynchronizer(
    private val database: AuroraIndexDatabase,
    private val smsSource: SmsProviderDataSource,
    private val mmsSource: MmsProviderDataSource,
    private val roleState: DefaultSmsRoleState,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val mutex = Mutex()
    private val syncDao = database.indexSyncDao()
    private val messageDao = database.indexedMessageDao()
    private val messageIndex = RoomMessageIndex(database)
    private val reconciler = IndexReconciler(database, smsSource, mmsSource)

    suspend fun synchronize(): IndexSyncOutcome = reconcile(setOf(IndexSignal.STARTUP))

    suspend fun reconcile(signals: Set<IndexSignal>): IndexSyncOutcome = try {
        require(signals.isNotEmpty()) { "Index reconciliation requires at least one signal" }
        withContext(ioDispatcher) {
            mutex.withLock { synchronizeLocked(signals) }
        }
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable + ioDispatcher) {
            pauseCurrentGeneration()
        }
        throw cancelled
    } catch (_: SQLiteFullException) {
        IndexSyncOutcome.Failed(IndexFailureCode.STORAGE_FULL)
    } catch (_: SQLiteDatabaseCorruptException) {
        IndexSyncOutcome.Failed(IndexFailureCode.STORAGE_UNAVAILABLE)
    } catch (_: SQLiteException) {
        IndexSyncOutcome.Failed(IndexFailureCode.STORAGE_UNAVAILABLE)
    }

    /** Persists a dirty signal before coalesced follow-up work is enqueued. */
    suspend fun markPendingChanges() = withContext(ioDispatcher) {
        val generation = syncDao.activeGeneration() ?: syncDao.latestGeneration() ?: return@withContext
        syncDao.markPendingChanges(generation.generationId, safeNow())
    }

    suspend fun pauseForRoleLoss() = withContext(ioDispatcher) {
        mutex.withLock { pauseCurrentGeneration() }
    }

    private suspend fun synchronizeLocked(signals: Set<IndexSignal>): IndexSyncOutcome {
        if (!roleState.isRoleHeld()) {
            pauseCurrentGeneration()
            return IndexSyncOutcome.Paused(IndexFailureCode.ROLE_REQUIRED)
        }

        val active = syncDao.activeGeneration()
        val latest = syncDao.latestGeneration()
        if (active == null && latest?.state == GenerationStateCode.COMPLETE) {
            val ownedIncomingInsert = signals == setOf(IndexSignal.INCOMING_INSERT)
            when (
                val steadyState = reconciler.reconcileComplete(
                    generation = latest,
                    allowOwnedHeadInsert = ownedIncomingInsert,
                    nowMillis = safeNow(),
                )
            ) {
                is SteadyStateResult.Complete -> {
                    return IndexSyncOutcome.Complete(messageIndex.coverage(), deletedStaleRows = 0)
                }
                SteadyStateResult.Superseded -> {
                    return IndexSyncOutcome.Pending(messageIndex.coverage())
                }
                is SteadyStateResult.Failure -> {
                    return IndexSyncOutcome.Failed(steadyState.reason.toIndexFailure())
                }
                SteadyStateResult.Ambiguous -> Unit
            }
        }

        repeat(MAX_GENERATIONS_PER_RUN) { attempt ->
            val generation = generationForRun()
            when (val result = runGeneration(generation)) {
                is GenerationResult.Complete -> {
                    return IndexSyncOutcome.Complete(messageIndex.coverage(), result.deletedRows)
                }
                GenerationResult.Dirty -> {
                    stopGeneration(generation.generationId, terminalState = GenerationStateCode.PAUSED)
                    if (attempt == MAX_GENERATIONS_PER_RUN - 1) {
                        return IndexSyncOutcome.Pending(messageIndex.coverage())
                    }
                }
                is GenerationResult.Failed -> {
                    recordFailure(generation.generationId, result.code)
                    return if (result.code == IndexFailureCode.ROLE_REQUIRED) {
                        IndexSyncOutcome.Paused(result.code)
                    } else {
                        IndexSyncOutcome.Failed(result.code)
                    }
                }
            }
        }
        return IndexSyncOutcome.Pending(messageIndex.coverage())
    }

    private suspend fun startGeneration(): IndexGenerationEntity {
        val id = syncDao.startGeneration(safeNow())
        return requireNotNull(syncDao.generationById(id)) { "Started index generation was not retained" }
    }

    private suspend fun generationForRun(): IndexGenerationEntity {
        syncDao.activeGeneration()?.let { return it }
        val latest = syncDao.latestGeneration()
        val canResume = latest?.state == GenerationStateCode.PAUSED ||
            (latest?.state == GenerationStateCode.FAILED && latest.failureCode in RECOVERABLE_FAILURE_CODES)
        if (latest != null && canResume && !latest.pendingChanges &&
            syncDao.resumeTerminalGeneration(latest.generationId, safeNow())) {
            return requireNotNull(syncDao.generationById(latest.generationId))
        }
        return startGeneration()
    }

    private suspend fun runGeneration(initial: IndexGenerationEntity): GenerationResult {
        var generation = initial
        if (generation.state == GenerationStateCode.SCANNING) {
            while (true) {
                if (!roleState.isRoleHeld()) {
                    return GenerationResult.Failed(IndexFailureCode.ROLE_REQUIRED)
                }
                val checkpoints = syncDao.checkpoints(generation.generationId)
                val smsCheckpoint = checkpoints.singleOrNull { it.providerKind == ProviderKindCode.SMS }
                    ?: return GenerationResult.Failed(IndexFailureCode.UNKNOWN)
                val mmsCheckpoint = checkpoints.singleOrNull { it.providerKind == ProviderKindCode.MMS }
                    ?: return GenerationResult.Failed(IndexFailureCode.UNKNOWN)
                if (smsCheckpoint.exhausted && mmsCheckpoint.exhausted) {
                    check(syncDao.markVerifying(generation.generationId, safeNow()) == 1) {
                        "Exhausted scanning generation could not enter verification"
                    }
                    generation = requireNotNull(syncDao.generationById(generation.generationId))
                    break
                }
                val merge = ProviderMergeCursor(
                    smsSource = smsSource,
                    mmsSource = mmsSource,
                    maxBatchSize = generation.targetBatchSize,
                )
                val staged = when (
                    val result = merge.stageBatch(
                        smsCursor = smsCheckpoint.toProviderCursor(),
                        mmsCursor = mmsCheckpoint.toProviderCursor(),
                        smsExhausted = smsCheckpoint.exhausted,
                        mmsExhausted = mmsCheckpoint.exhausted,
                    )
                ) {
                    is ProviderMergeResult.Ready -> result.batch
                    is ProviderMergeResult.Failure -> {
                        return GenerationResult.Failed(result.failure.toIndexFailure())
                    }
                }
                if (!staged.advances(smsCheckpoint, mmsCheckpoint)) {
                    return GenerationResult.Failed(IndexFailureCode.NON_ADVANCING_CURSOR)
                }

                val projections = staged.messages.map { item ->
                    when (item) {
                        is ProviderMergeItem.Sms -> IndexProjectionMapper.projectionFromSms(
                            item.message,
                            generation.generationId,
                        )
                        is ProviderMergeItem.Mms -> IndexProjectionMapper.projectionFromMms(
                            item.message,
                            generation.generationId,
                        )
                    }
                }
                val nowMillis = safeNow()
                val smsNext = staged.smsProgress.toCheckpoint(
                    generationId = generation.generationId,
                    providerKind = ProviderKindCode.SMS,
                    previousCommittedCount = smsCheckpoint.committedCount,
                    nowMillis = nowMillis,
                )
                val mmsNext = staged.mmsProgress.toCheckpoint(
                    generationId = generation.generationId,
                    providerKind = ProviderKindCode.MMS,
                    previousCommittedCount = mmsCheckpoint.committedCount,
                    nowMillis = nowMillis,
                )
                val startedAt = monotonicMillis()
                try {
                    messageDao.commitScanningProjectionBatch(
                        generationId = generation.generationId,
                        projections = projections,
                        smsCheckpoint = smsNext,
                        mmsCheckpoint = mmsNext,
                        nowMillis = nowMillis,
                        targetBatchSize = generation.targetBatchSize,
                    )
                } catch (_: SQLiteFullException) {
                    return GenerationResult.Failed(IndexFailureCode.STORAGE_FULL)
                } catch (_: SQLiteDatabaseCorruptException) {
                    return GenerationResult.Failed(IndexFailureCode.STORAGE_UNAVAILABLE)
                } catch (_: SQLiteException) {
                    return GenerationResult.Failed(IndexFailureCode.STORAGE_UNAVAILABLE)
                }
                val elapsedMillis = max(0L, monotonicMillis() - startedAt)
                val adaptedBatchSize = adaptBatchSize(generation.targetBatchSize, elapsedMillis)
                generation = requireNotNull(syncDao.generationById(generation.generationId)).copy(
                    targetBatchSize = adaptedBatchSize,
                )
                if (smsNext.exhausted && mmsNext.exhausted) {
                    check(syncDao.markVerifying(generation.generationId, safeNow()) == 1) {
                        "Scanning generation could not enter verification"
                    }
                    generation = requireNotNull(syncDao.generationById(generation.generationId))
                    break
                }
            }
        }

        if (generation.state != GenerationStateCode.VERIFYING) {
            return GenerationResult.Failed(IndexFailureCode.UNKNOWN)
        }
        return when (val result = reconciler.verifyAndComplete(generation.generationId, safeNow())) {
            is IndexReconcileResult.Verified -> GenerationResult.Complete(result.summary.deletedRows)
            IndexReconcileResult.Dirty -> GenerationResult.Dirty
            is IndexReconcileResult.Failure -> GenerationResult.Failed(result.reason.toIndexFailure())
        }
    }

    private suspend fun pauseCurrentGeneration() {
        syncDao.activeGeneration()?.let { generation ->
            runCatching {
                stopGeneration(generation.generationId, terminalState = GenerationStateCode.PAUSED)
            }
        }
    }

    private suspend fun recordFailure(generationId: Long, code: IndexFailureCode) {
        try {
            if (code == IndexFailureCode.ROLE_REQUIRED) {
                stopGeneration(generationId, terminalState = GenerationStateCode.PAUSED)
            } else {
                stopGeneration(
                    generationId = generationId,
                    terminalState = GenerationStateCode.FAILED,
                    failureCode = code,
                )
            }
        } catch (_: SQLiteException) {
            // The failed content transaction already preserved its previous
            // checkpoint. A full/unavailable database may also reject this
            // best-effort terminal marker; the caller still receives failure.
        }
    }

    private suspend fun stopGeneration(
        generationId: Long,
        terminalState: Int,
        failureCode: IndexFailureCode? = null,
    ) {
        syncDao.stopActiveGeneration(
            generationId = generationId,
            terminalState = terminalState,
            failureCode = failureCode?.toIndexStorageCode(),
            nowMillis = safeNow(),
        )
    }

    private fun safeNow(): Long = max(0L, wallClockMillis())

    private fun adaptBatchSize(current: Int, elapsedMillis: Long): Int = when {
        elapsedMillis > SLOW_TRANSACTION_MILLIS -> max(MINIMUM_INDEX_BATCH_SIZE, current / 2)
        elapsedMillis < FAST_TRANSACTION_MILLIS -> min(MAXIMUM_INDEX_BATCH_SIZE, current + 50)
        else -> current
    }

    private sealed interface GenerationResult {
        class Complete(val deletedRows: Int) : GenerationResult
        data object Dirty : GenerationResult
        class Failed(val code: IndexFailureCode) : GenerationResult
    }

    companion object {
        private const val MAX_GENERATIONS_PER_RUN: Int = 2
        private const val SLOW_TRANSACTION_MILLIS: Long = 75L
        private const val FAST_TRANSACTION_MILLIS: Long = 25L
        private val RECOVERABLE_FAILURE_CODES: Set<Int> = setOf(
            IndexFailureCodeValue.ROLE_REQUIRED,
            IndexFailureCodeValue.PERMISSION_DENIED,
            IndexFailureCodeValue.PROVIDER_UNAVAILABLE,
            IndexFailureCodeValue.STORAGE_FULL,
            IndexFailureCodeValue.STORAGE_UNAVAILABLE,
        )
    }
}

sealed interface IndexSyncOutcome {
    data class Complete(
        val coverage: IndexCoverage,
        val deletedStaleRows: Int,
    ) : IndexSyncOutcome

    data class Pending(val coverage: IndexCoverage) : IndexSyncOutcome
    data class Paused(val reason: IndexFailureCode) : IndexSyncOutcome
    data class Failed(val reason: IndexFailureCode) : IndexSyncOutcome
}

private fun IndexCheckpointEntity.toProviderCursor(): ProviderPageCursor? =
    cursorTimestampMillis?.let { timestamp ->
        ProviderPageCursor(timestamp, requireNotNull(cursorProviderId))
    }

private fun ProviderMergeProgress.toCheckpoint(
    generationId: Long,
    providerKind: Int,
    previousCommittedCount: Long,
    nowMillis: Long,
): IndexCheckpointEntity = IndexCheckpointEntity(
    generationId = generationId,
    providerKind = providerKind,
    cursorTimestampMillis = stagedCursor?.timestampMillis,
    cursorProviderId = stagedCursor?.providerRowId,
    exhausted = exhausted,
    committedCount = previousCommittedCount + consumedItemCount,
    updatedAtMillis = nowMillis,
)

private fun ProviderMergeBatch.advances(
    smsCheckpoint: IndexCheckpointEntity,
    mmsCheckpoint: IndexCheckpointEntity,
): Boolean = messages.isNotEmpty() ||
    smsProgress.differsFrom(smsCheckpoint) ||
    mmsProgress.differsFrom(mmsCheckpoint)

private fun ProviderMergeProgress.differsFrom(checkpoint: IndexCheckpointEntity): Boolean =
    exhausted != checkpoint.exhausted ||
        stagedCursor?.timestampMillis != checkpoint.cursorTimestampMillis ||
        stagedCursor?.providerRowId != checkpoint.cursorProviderId

private fun ProviderMergeFailure.toIndexFailure(): IndexFailureCode = when (reason) {
    ProviderMergeFailureReason.ROLE_REQUIRED -> IndexFailureCode.ROLE_REQUIRED
    ProviderMergeFailureReason.PERMISSION_DENIED -> IndexFailureCode.PERMISSION_DENIED
    ProviderMergeFailureReason.NON_ADVANCING_CURSOR,
    ProviderMergeFailureReason.ITEM_OUTSIDE_REQUEST_CURSOR,
    ProviderMergeFailureReason.OUT_OF_ORDER_PAGE,
    ProviderMergeFailureReason.INCONSISTENT_PAGE_CURSOR,
    -> IndexFailureCode.NON_ADVANCING_CURSOR
    else -> IndexFailureCode.PROVIDER_UNAVAILABLE
}

private fun IndexProviderFailure.toIndexFailure(): IndexFailureCode = when (this) {
    IndexProviderFailure.ROLE_REQUIRED -> IndexFailureCode.ROLE_REQUIRED
    IndexProviderFailure.PERMISSION_DENIED -> IndexFailureCode.PERMISSION_DENIED
    IndexProviderFailure.NON_ADVANCING_CURSOR -> IndexFailureCode.NON_ADVANCING_CURSOR
    IndexProviderFailure.PROVIDER_UNAVAILABLE,
    IndexProviderFailure.PROVIDER_INVALID,
    -> IndexFailureCode.PROVIDER_UNAVAILABLE
}
