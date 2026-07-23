// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class IndexSyncDao {
    @Insert
    protected abstract suspend fun insertGeneration(entity: IndexGenerationEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCheckpoints(entities: List<IndexCheckpointEntity>)

    @Transaction
    open suspend fun startGeneration(
        nowMillis: Long,
    ): Long {
        require(nowMillis >= 0L) { "Generation time cannot be negative" }
        require(activeGeneration() == null) { "An index generation is already active" }
        val generationId = insertGeneration(
            IndexGenerationEntity(
                state = GenerationStateCode.SCANNING,
                startedAtMillis = nowMillis,
                updatedAtMillis = nowMillis,
                completedAtMillis = null,
                committedCount = 0L,
                pendingChanges = false,
                failureCode = null,
                targetBatchSize = INITIAL_INDEX_BATCH_SIZE,
            ),
        )
        insertCheckpoints(
            listOf(
                emptyCheckpoint(generationId, ProviderKindCode.SMS, nowMillis),
                emptyCheckpoint(generationId, ProviderKindCode.MMS, nowMillis),
            ),
        )
        return generationId
    }

    @Query(
        """
        SELECT * FROM index_generations
        WHERE state IN (:scanningState, :verifyingState)
        ORDER BY generation_id DESC
        LIMIT 1
        """,
    )
    abstract suspend fun activeGeneration(
        scanningState: Int = GenerationStateCode.SCANNING,
        verifyingState: Int = GenerationStateCode.VERIFYING,
    ): IndexGenerationEntity?

    @Query("SELECT * FROM index_generations ORDER BY generation_id DESC LIMIT 1")
    abstract suspend fun latestGeneration(): IndexGenerationEntity?

    @Query(
        """
        SELECT * FROM index_checkpoints
        WHERE generation_id = :generationId
        ORDER BY provider_kind ASC
        """,
    )
    abstract suspend fun checkpoints(generationId: Long): List<IndexCheckpointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putCheckpoint(entity: IndexCheckpointEntity)

    @Query(
        """
        UPDATE index_generations
        SET state = :state,
            updated_at_ms = :nowMillis,
            completed_at_ms = :completedAtMillis,
            committed_count = :committedCount,
            pending_changes = :pendingChanges,
            failure_code = :failureCode,
            target_batch_size = :targetBatchSize
        WHERE generation_id = :generationId
        """,
    )
    abstract suspend fun updateGeneration(
        generationId: Long,
        state: Int,
        nowMillis: Long,
        completedAtMillis: Long?,
        committedCount: Long,
        pendingChanges: Boolean,
        failureCode: Int?,
        targetBatchSize: Int,
    ): Int

    @Query(
        """
        UPDATE index_generations
        SET pending_changes = 1,
            updated_at_ms = :nowMillis,
            signal_sequence = signal_sequence + 1
        WHERE generation_id = :generationId
        """,
    )
    abstract suspend fun markPendingChanges(generationId: Long, nowMillis: Long): Int

    @Query("SELECT * FROM index_generations WHERE generation_id = :generationId LIMIT 1")
    abstract suspend fun generationById(generationId: Long): IndexGenerationEntity?

    @Transaction
    open suspend fun resumeTerminalGeneration(
        generationId: Long,
        nowMillis: Long,
    ): Boolean {
        val generation = generationById(generationId) ?: return false
        if (
            generation.pendingChanges ||
            generation.state !in setOf(GenerationStateCode.PAUSED, GenerationStateCode.FAILED)
        ) {
            return false
        }
        return updateGeneration(
            generationId = generationId,
            state = GenerationStateCode.SCANNING,
            nowMillis = nowMillis,
            completedAtMillis = null,
            committedCount = generation.committedCount,
            pendingChanges = false,
            failureCode = null,
            targetBatchSize = generation.targetBatchSize,
        ) == 1
    }

    @Query(
        """
        UPDATE index_generations
        SET state = :verifyingState, updated_at_ms = :nowMillis
        WHERE generation_id = :generationId AND state = :scanningState
        """,
    )
    abstract suspend fun markVerifying(
        generationId: Long,
        nowMillis: Long,
        verifyingState: Int = GenerationStateCode.VERIFYING,
        scanningState: Int = GenerationStateCode.SCANNING,
    ): Int

    @Query(
        """
        UPDATE index_generations
        SET state = :terminalState,
            updated_at_ms = :nowMillis,
            completed_at_ms = NULL,
            failure_code = :failureCode
        WHERE generation_id = :generationId
          AND state IN (:scanningState, :verifyingState)
        """,
    )
    abstract suspend fun stopActiveGeneration(
        generationId: Long,
        terminalState: Int,
        failureCode: Int?,
        nowMillis: Long,
        scanningState: Int = GenerationStateCode.SCANNING,
        verifyingState: Int = GenerationStateCode.VERIFYING,
    ): Int

    @Query("DELETE FROM indexed_messages WHERE last_seen_generation != :generationId")
    protected abstract suspend fun deleteRowsOutsideGeneration(generationId: Long): Int

    @Query("DELETE FROM indexed_conversations WHERE last_seen_generation != :generationId")
    protected abstract suspend fun deleteConversationsOutsideGeneration(generationId: Long): Int

    @Query("DELETE FROM indexed_conversation_participants WHERE last_seen_generation != :generationId")
    protected abstract suspend fun deleteParticipantsOutsideGeneration(generationId: Long): Int

    @Query("SELECT COUNT(*) FROM indexed_messages")
    protected abstract suspend fun indexedMessageCount(): Long

    @Query("SELECT COUNT(*) FROM indexed_messages WHERE last_seen_generation = :generationId")
    protected abstract suspend fun indexedMessageCount(generationId: Long): Long

    @Query("SELECT COUNT(DISTINCT provider_thread_id) FROM indexed_messages WHERE last_seen_generation = :generationId")
    protected abstract suspend fun indexedThreadCount(generationId: Long): Long

    @Query("SELECT COUNT(*) FROM indexed_conversations WHERE last_seen_generation = :generationId")
    protected abstract suspend fun indexedConversationCount(generationId: Long): Long

    @Query("SELECT COALESCE(SUM(indexed_message_count), 0) FROM indexed_conversations WHERE last_seen_generation = :generationId")
    protected abstract suspend fun summarizedMessageCount(generationId: Long): Long

    @Query("SELECT COUNT(*) FROM indexed_messages WHERE last_seen_generation = :generationId AND is_read = 0")
    protected abstract suspend fun indexedUnreadCount(generationId: Long): Long

    @Query("SELECT COALESCE(SUM(indexed_unread_count), 0) FROM indexed_conversations WHERE last_seen_generation = :generationId")
    protected abstract suspend fun summarizedUnreadCount(generationId: Long): Long

    @Query(
        """
        SELECT COUNT(*) FROM indexed_conversations AS c
        LEFT JOIN indexed_messages AS m ON m.row_id = c.latest_row_id
        WHERE c.last_seen_generation = :generationId
          AND (
            m.row_id IS NULL OR
            m.last_seen_generation != :generationId OR
            m.provider_thread_id != c.provider_thread_id OR
            m.provider_kind != c.latest_provider_kind OR
            m.provider_id != c.latest_provider_id OR
            m.timestamp_ms != c.latest_timestamp_ms
          )
        """,
    )
    protected abstract suspend fun invalidLatestConversationCount(generationId: Long): Long

    @Query(
        """
        SELECT COUNT(*) FROM indexed_conversations AS c
        WHERE c.last_seen_generation = :generationId
          AND c.indexed_participant_count != (
            SELECT COUNT(*) FROM indexed_conversation_participants AS p
            WHERE p.provider_thread_id = c.provider_thread_id
              AND p.last_seen_generation = :generationId
          )
        """,
    )
    protected abstract suspend fun invalidParticipantCount(generationId: Long): Long

    @Query(
        """
        UPDATE index_generations
        SET state = :completeState,
            updated_at_ms = :nowMillis,
            completed_at_ms = :nowMillis,
            committed_count = :indexedCount,
            pending_changes = 0,
            failure_code = NULL
        WHERE generation_id = :generationId
          AND state = :verifyingState
          AND pending_changes = 0
        """,
    )
    protected abstract suspend fun markComplete(
        generationId: Long,
        nowMillis: Long,
        indexedCount: Long,
        completeState: Int = GenerationStateCode.COMPLETE,
        verifyingState: Int = GenerationStateCode.VERIFYING,
    ): Int

    @Transaction
    open suspend fun finishVerifiedGeneration(
        generationId: Long,
        nowMillis: Long,
        smsProviderCount: Long,
        mmsProviderCount: Long,
    ): IndexReconciliationSummary? {
        require(generationId > 0L) { "Index generations must be positive" }
        require(nowMillis >= 0L) { "Completion time cannot be negative" }
        val generation = generationById(generationId) ?: return null
        val providerCheckpoints = checkpoints(generationId)
        val sourcesExhausted = providerCheckpoints.size == 2 &&
            providerCheckpoints.any { it.providerKind == ProviderKindCode.SMS && it.exhausted } &&
            providerCheckpoints.any { it.providerKind == ProviderKindCode.MMS && it.exhausted }
        if (
            generation.state != GenerationStateCode.VERIFYING ||
            generation.pendingChanges ||
            !sourcesExhausted
        ) {
            return null
        }
        require(smsProviderCount == smsCheckpoint(providerCheckpoints).committedCount) {
            "Verified SMS count must equal committed projections"
        }
        require(mmsProviderCount == mmsCheckpoint(providerCheckpoints).committedCount) {
            "Verified MMS count must equal committed projections"
        }
        val expectedRetained = smsProviderCount + mmsProviderCount
        if (
            expectedRetained < smsProviderCount ||
            generation.committedCount != expectedRetained ||
            indexedMessageCount(generationId) != expectedRetained
        ) {
            // Projection/checkpoint counts track consumed provider rows. A
            // duplicate or moving identity can consume twice while upserting
            // only one unique index row; never delete stale rows or complete
            // such a generation.
            return null
        }
        putCheckpoint(
            smsCheckpoint(providerCheckpoints).copy(
                verifiedProviderCount = smsProviderCount,
                updatedAtMillis = nowMillis,
            ),
        )
        putCheckpoint(
            mmsCheckpoint(providerCheckpoints).copy(
                verifiedProviderCount = mmsProviderCount,
                updatedAtMillis = nowMillis,
            ),
        )
        val deleted = deleteRowsOutsideGeneration(generationId)
        deleteConversationsOutsideGeneration(generationId)
        deleteParticipantsOutsideGeneration(generationId)
        val retained = indexedMessageCount()
        check(retained == expectedRetained) {
            "Verified retained rows changed during transactional completion"
        }
        check(indexedConversationCount(generationId) == indexedThreadCount(generationId)) {
            "Verified conversation projection did not cover every indexed thread"
        }
        check(summarizedMessageCount(generationId) == retained) {
            "Verified conversation counts did not match indexed messages"
        }
        check(summarizedUnreadCount(generationId) == indexedUnreadCount(generationId)) {
            "Verified unread counts did not match indexed messages"
        }
        check(invalidLatestConversationCount(generationId) == 0L) {
            "Verified conversation latest identities were inconsistent"
        }
        check(invalidParticipantCount(generationId) == 0L) {
            "Verified conversation participant counts were inconsistent"
        }
        check(markComplete(generationId, nowMillis, retained) == 1) {
            "Verified generation changed during completion"
        }
        return IndexReconciliationSummary(deletedRows = deleted, retainedRows = retained)
    }

    @Query("DELETE FROM index_checkpoints WHERE generation_id = :generationId")
    abstract suspend fun deleteCheckpoints(generationId: Long): Int

    private fun emptyCheckpoint(
        generationId: Long,
        providerKind: Int,
        nowMillis: Long,
    ) = IndexCheckpointEntity(
        generationId = generationId,
        providerKind = providerKind,
        cursorTimestampMillis = null,
        cursorProviderId = null,
        exhausted = false,
        committedCount = 0L,
        updatedAtMillis = nowMillis,
    )

    private fun smsCheckpoint(checkpoints: List<IndexCheckpointEntity>): IndexCheckpointEntity =
        requireNotNull(checkpoints.singleOrNull { it.providerKind == ProviderKindCode.SMS })

    private fun mmsCheckpoint(checkpoints: List<IndexCheckpointEntity>): IndexCheckpointEntity =
        requireNotNull(checkpoints.singleOrNull { it.providerKind == ProviderKindCode.MMS })
}

data class IndexReconciliationSummary(
    val deletedRows: Int,
    val retainedRows: Long,
) {
    init {
        require(deletedRows >= 0) { "Deleted row counts cannot be negative" }
        require(retainedRows >= 0L) { "Retained row counts cannot be negative" }
    }
}
