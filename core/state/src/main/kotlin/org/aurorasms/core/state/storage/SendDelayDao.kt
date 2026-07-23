// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface SendDelayDao {
    @Query("SELECT COUNT(*) FROM send_delay_operations")
    suspend fun count(): Int

    @Query("SELECT * FROM send_delay_operations WHERE send_delay_id = :id LIMIT 1")
    suspend fun findById(id: Long): SendDelayEntity?

    @Query("SELECT * FROM send_delay_operations WHERE provider_thread_id = :threadId LIMIT 1")
    suspend fun findByThread(threadId: Long): SendDelayEntity?

    @Query("SELECT * FROM send_delay_operations WHERE provider_thread_id = :threadId LIMIT 1")
    fun observeByThread(threadId: Long): Flow<SendDelayEntity?>

    @Query("SELECT * FROM send_delay_operations ORDER BY due_timestamp_ms, send_delay_id LIMIT :limit")
    suspend fun recoverySnapshot(limit: Int): List<SendDelayEntity>

    @Query("SELECT * FROM drafts WHERE draft_id = :draftId LIMIT 1")
    suspend fun findDraft(draftId: Long): DraftEntity?

    @Query("SELECT * FROM composer_sms_operations WHERE provider_thread_id = :threadId LIMIT 1")
    suspend fun findComposerOperation(threadId: Long): ComposerSmsOperationEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SendDelayEntity): Long

    @Query(
        """
        UPDATE send_delay_operations SET phase_code = :dispatchingPhase,
            updated_timestamp_ms = :updated
        WHERE send_delay_id = :id AND phase_code = :pendingPhase
          AND updated_timestamp_ms = :expectedRevision
        """,
    )
    suspend fun markDispatching(
        id: Long,
        expectedRevision: Long,
        updated: Long,
        pendingPhase: String,
        dispatchingPhase: String,
    ): Int

    @Query(
        """
        UPDATE send_delay_operations SET phase_code = :reviewPhase,
            review_reason_code = :reasonCode,
            updated_timestamp_ms = :updated
        WHERE send_delay_id = :id AND updated_timestamp_ms = :expectedRevision
          AND phase_code IN (:pendingPhase, :dispatchingPhase)
        """,
    )
    suspend fun markReview(
        id: Long,
        expectedRevision: Long,
        reasonCode: String,
        updated: Long,
        pendingPhase: String,
        dispatchingPhase: String,
        reviewPhase: String,
    ): Int

    @Query(
        """
        DELETE FROM send_delay_operations
        WHERE send_delay_id = :id AND updated_timestamp_ms = :revision
          AND phase_code IN (:pendingPhase, :reviewPhase)
        """,
    )
    suspend fun deleteUndoableIfCurrent(
        id: Long,
        revision: Long,
        pendingPhase: String,
        reviewPhase: String,
    ): Int

    @Query("DELETE FROM send_delay_operations WHERE send_delay_id = :id AND updated_timestamp_ms = :revision")
    suspend fun deleteIfCurrent(id: Long, revision: Long): Int
}
