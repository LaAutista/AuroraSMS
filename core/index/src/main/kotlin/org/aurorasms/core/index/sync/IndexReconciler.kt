// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.sync

import java.util.concurrent.CancellationException
import org.aurorasms.core.index.storage.AuroraIndexDatabase
import org.aurorasms.core.index.storage.GenerationStateCode
import org.aurorasms.core.index.storage.IndexCheckpointEntity
import org.aurorasms.core.index.storage.IndexGenerationEntity
import org.aurorasms.core.index.storage.IndexReconciliationSummary
import org.aurorasms.core.index.storage.ProviderKindCode
import org.aurorasms.core.telephony.MmsProviderDataSource
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderPageRequest
import org.aurorasms.core.telephony.SmsProviderDataSource

/** Performs one bounded verification head pass before stale index rows may be removed. */
internal class IndexReconciler(
    database: AuroraIndexDatabase,
    private val smsSource: SmsProviderDataSource,
    private val mmsSource: MmsProviderDataSource,
) {
    private val messageDao = database.indexedMessageDao()
    private val syncDao = database.indexSyncDao()

    suspend fun verifyAndComplete(
        generationId: Long,
        nowMillis: Long,
    ): IndexReconcileResult {
        val generation = syncDao.generationById(generationId)
            ?: return IndexReconcileResult.Dirty
        if (generation.state != GenerationStateCode.VERIFYING || generation.pendingChanges) {
            return IndexReconcileResult.Dirty
        }
        val checkpoints = syncDao.checkpoints(generationId)
        val smsCheckpoint = checkpoints.firstOrNull { it.providerKind == ProviderKindCode.SMS }
            ?: return IndexReconcileResult.Dirty
        val mmsCheckpoint = checkpoints.firstOrNull { it.providerKind == ProviderKindCode.MMS }
            ?: return IndexReconcileResult.Dirty
        if (!smsCheckpoint.exhausted || !mmsCheckpoint.exhausted) return IndexReconcileResult.Dirty

        val smsCount = when (val result = providerCall(smsSource::count)) {
            is ProviderCall.Success -> result.value
            is ProviderCall.Failure -> return IndexReconcileResult.Failure(result.reason)
        }
        val mmsCount = when (val result = providerCall(mmsSource::count)) {
            is ProviderCall.Success -> result.value
            is ProviderCall.Failure -> return IndexReconcileResult.Failure(result.reason)
        }
        // A lower provider count proves deletion occurred during the scan. A
        // higher count may include bounded rows rejected by projection, so the
        // head/fingerprint pass below decides whether it is actionable.
        if (smsCount < smsCheckpoint.committedCount || mmsCount < mmsCheckpoint.committedCount) {
            return IndexReconcileResult.Dirty
        }

        val smsHead = when (
            val result = providerCall {
                smsSource.readPage(ProviderPageRequest(VERIFY_HEAD_LIMIT))
            }
        ) {
            is ProviderCall.Success -> result.value
            is ProviderCall.Failure -> return IndexReconcileResult.Failure(result.reason)
        }
        val mmsHead = when (
            val result = providerCall {
                mmsSource.readPage(ProviderPageRequest(VERIFY_HEAD_LIMIT))
            }
        ) {
            is ProviderCall.Success -> result.value
            is ProviderCall.Failure -> return IndexReconcileResult.Failure(result.reason)
        }
        if (!headMatches(generationId, ProviderKindCode.SMS, smsHead.items) { it.id.value to it.syncFingerprint.toStorageToken() }) {
            return IndexReconcileResult.Dirty
        }
        if (!headMatches(generationId, ProviderKindCode.MMS, mmsHead.items) { it.id.value to it.syncFingerprint.toStorageToken() }) {
            return IndexReconcileResult.Dirty
        }

        val summary = syncDao.finishVerifiedGeneration(
            generationId = generationId,
            nowMillis = nowMillis,
            smsProviderCount = smsCount,
            mmsProviderCount = mmsCount,
        )
            ?: return IndexReconcileResult.Dirty
        return IndexReconcileResult.Verified(summary)
    }

    /**
     * Checks a complete generation with at most one provider head page per source.
     * Only a proven leading owned insert is applied incrementally; every gap,
     * deletion, mutation, or missing overlap is sent to a new full generation.
     */
    suspend fun reconcileComplete(
        generation: IndexGenerationEntity,
        allowOwnedHeadInsert: Boolean,
        nowMillis: Long,
    ): SteadyStateResult {
        if (generation.state != GenerationStateCode.COMPLETE) return SteadyStateResult.Ambiguous
        val checkpoints = syncDao.checkpoints(generation.generationId)
        val smsCheckpoint = checkpoints.singleOrNull { it.providerKind == ProviderKindCode.SMS }
            ?: return SteadyStateResult.Ambiguous
        val mmsCheckpoint = checkpoints.singleOrNull { it.providerKind == ProviderKindCode.MMS }
            ?: return SteadyStateResult.Ambiguous
        val previousSmsCount = smsCheckpoint.verifiedProviderCount
            ?: return SteadyStateResult.Ambiguous
        val previousMmsCount = mmsCheckpoint.verifiedProviderCount
            ?: return SteadyStateResult.Ambiguous

        val smsCount = when (val result = providerCall(smsSource::count)) {
            is ProviderCall.Success -> result.value
            is ProviderCall.Failure -> return SteadyStateResult.Failure(result.reason)
        }
        val mmsCount = when (val result = providerCall(mmsSource::count)) {
            is ProviderCall.Success -> result.value
            is ProviderCall.Failure -> return SteadyStateResult.Failure(result.reason)
        }
        val smsHead = when (
            val result = providerCall { smsSource.readPage(ProviderPageRequest(VERIFY_HEAD_LIMIT)) }
        ) {
            is ProviderCall.Success -> result.value
            is ProviderCall.Failure -> return SteadyStateResult.Failure(result.reason)
        }
        val mmsHead = when (
            val result = providerCall { mmsSource.readPage(ProviderPageRequest(VERIFY_HEAD_LIMIT)) }
        ) {
            is ProviderCall.Success -> result.value
            is ProviderCall.Failure -> return SteadyStateResult.Failure(result.reason)
        }

        val smsPlan = sourcePlan(
            generationId = generation.generationId,
            providerKind = ProviderKindCode.SMS,
            previousProviderCount = previousSmsCount,
            currentProviderCount = smsCount,
            headItems = smsHead.items,
            headExhausted = smsHead.exhausted,
            allowOwnedHeadInsert = allowOwnedHeadInsert,
        ) { message -> message.id.value to message.syncFingerprint.toStorageToken() }
        if (smsPlan == null) return SteadyStateResult.Ambiguous
        val mmsPlan = sourcePlan(
            generationId = generation.generationId,
            providerKind = ProviderKindCode.MMS,
            previousProviderCount = previousMmsCount,
            currentProviderCount = mmsCount,
            headItems = mmsHead.items,
            headExhausted = mmsHead.exhausted,
            allowOwnedHeadInsert = allowOwnedHeadInsert,
        ) { message -> message.id.value to message.syncFingerprint.toStorageToken() }
        if (mmsPlan == null) return SteadyStateResult.Ambiguous

        // A durable dirty mark proves that something may have changed. Equal
        // counts and an unchanged bounded head cannot rule out a deep edit,
        // deletion plus insertion, or status mutation. Only the incoming
        // persistence path can identify a leading insert as app-owned; its
        // stable replay is also safe to acknowledge as a no-op.
        if (
            generation.pendingChanges &&
            !allowOwnedHeadInsert &&
            smsPlan.insertedItems.isEmpty() &&
            mmsPlan.insertedItems.isEmpty()
        ) {
            return SteadyStateResult.Ambiguous
        }

        val entities = buildList {
            smsPlan.insertedItems.forEach { message ->
                add(IndexProjectionMapper.fromSms(message, generation.generationId))
            }
            mmsPlan.insertedItems.forEach { message ->
                add(IndexProjectionMapper.fromMms(message, generation.generationId))
            }
        }
        return try {
            messageDao.commitSteadyStateBatch(
                generationId = generation.generationId,
                expectedSignalSequence = generation.signalSequence,
                entities = entities,
                smsCheckpoint = smsCheckpoint.copy(
                    committedCount = smsCheckpoint.committedCount + smsPlan.insertedItems.size,
                    updatedAtMillis = nowMillis,
                    verifiedProviderCount = smsCount,
                ),
                mmsCheckpoint = mmsCheckpoint.copy(
                    committedCount = mmsCheckpoint.committedCount + mmsPlan.insertedItems.size,
                    updatedAtMillis = nowMillis,
                    verifiedProviderCount = mmsCount,
                ),
                nowMillis = nowMillis,
            )
            SteadyStateResult.Complete(insertedRows = entities.size)
        } catch (_: IllegalStateException) {
            SteadyStateResult.Superseded
        }
    }

    private suspend fun <T> sourcePlan(
        generationId: Long,
        providerKind: Int,
        previousProviderCount: Long,
        currentProviderCount: Long,
        headItems: List<T>,
        headExhausted: Boolean,
        allowOwnedHeadInsert: Boolean,
        identity: (T) -> Pair<Long, String>,
    ): SourcePlan<T>? {
        if (currentProviderCount < previousProviderCount || headItems.size > VERIFY_HEAD_LIMIT) return null
        val expected = headItems.map(identity)
        if (expected.map { it.first }.distinct().size != expected.size) return null
        val stored = if (expected.isEmpty()) {
            emptyMap()
        } else {
            messageDao.syncStates(providerKind, expected.map { it.first })
                .associateBy { it.providerId }
        }
        val inserted = mutableListOf<T>()
        var knownOverlap = false
        expected.zip(headItems).forEach { (identified, item) ->
            val (providerId, fingerprint) = identified
            val existing = stored[providerId]
            if (existing == null) {
                if (knownOverlap) return null
                inserted += item
            } else {
                if (
                    existing.syncFingerprint != fingerprint ||
                    existing.lastSeenGeneration != generationId
                ) {
                    return null
                }
                knownOverlap = true
            }
        }

        val countDelta = currentProviderCount - previousProviderCount
        if (countDelta == 0L) return SourcePlan<T>(emptyList()).takeIf { inserted.isEmpty() }
        if (!allowOwnedHeadInsert || countDelta != inserted.size.toLong()) return null
        if (previousProviderCount > 0L && !knownOverlap) return null
        if (previousProviderCount == 0L && (!headExhausted || currentProviderCount != inserted.size.toLong())) {
            return null
        }
        return SourcePlan(inserted)
    }

    private suspend fun <T> headMatches(
        generationId: Long,
        providerKind: Int,
        items: List<T>,
        identity: (T) -> Pair<Long, String>,
    ): Boolean {
        if (items.isEmpty()) return true
        if (items.size > VERIFY_HEAD_LIMIT) return false
        val expected = items.associate(identity)
        if (expected.size != items.size) return false
        val stored = messageDao.syncStates(providerKind, expected.keys.toList())
        if (stored.size != expected.size) return false
        return stored.all { row ->
            row.lastSeenGeneration == generationId && expected[row.providerId] == row.syncFingerprint
        }
    }

    private suspend fun <T> providerCall(
        call: suspend () -> ProviderAccessResult<T>,
    ): ProviderCall<T> = try {
        when (val result = call()) {
            is ProviderAccessResult.Success -> ProviderCall.Success(result.value)
            ProviderAccessResult.RoleRequired -> ProviderCall.Failure(IndexProviderFailure.ROLE_REQUIRED)
            ProviderAccessResult.PermissionDenied -> ProviderCall.Failure(IndexProviderFailure.PERMISSION_DENIED)
            is ProviderAccessResult.Unsupported -> ProviderCall.Failure(IndexProviderFailure.PROVIDER_UNAVAILABLE)
            is ProviderAccessResult.Unavailable -> ProviderCall.Failure(IndexProviderFailure.PROVIDER_UNAVAILABLE)
            is ProviderAccessResult.InvalidInput -> ProviderCall.Failure(IndexProviderFailure.PROVIDER_INVALID)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ProviderCall.Failure(IndexProviderFailure.PROVIDER_UNAVAILABLE)
    }

    private sealed interface ProviderCall<out T> {
        class Success<T>(val value: T) : ProviderCall<T>
        class Failure(val reason: IndexProviderFailure) : ProviderCall<Nothing>
    }

    companion object {
        private const val VERIFY_HEAD_LIMIT: Int = ProviderPageRequest.MAX_PROVIDER_PAGE_SIZE
    }

    private class SourcePlan<T>(val insertedItems: List<T>)
}

internal sealed interface IndexReconcileResult {
    class Verified(val summary: IndexReconciliationSummary) : IndexReconcileResult
    data object Dirty : IndexReconcileResult
    class Failure(val reason: IndexProviderFailure) : IndexReconcileResult
}

internal enum class IndexProviderFailure {
    ROLE_REQUIRED,
    PERMISSION_DENIED,
    PROVIDER_UNAVAILABLE,
    PROVIDER_INVALID,
    NON_ADVANCING_CURSOR,
}

internal sealed interface SteadyStateResult {
    class Complete(val insertedRows: Int) : SteadyStateResult
    data object Ambiguous : SteadyStateResult
    data object Superseded : SteadyStateResult
    class Failure(val reason: IndexProviderFailure) : SteadyStateResult
}
