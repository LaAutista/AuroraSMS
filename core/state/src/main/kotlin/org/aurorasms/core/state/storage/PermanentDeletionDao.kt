// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface PermanentDeletionDao {
    @Query("SELECT COUNT(*) FROM permanent_deletion_operations")
    suspend fun count(): Int

    @Query("SELECT * FROM permanent_deletion_operations WHERE deletion_id = :id LIMIT 1")
    suspend fun findById(id: Long): PermanentDeletionEntity?

    @Query("SELECT * FROM permanent_deletion_operations WHERE provider_thread_id = :threadId LIMIT 1")
    suspend fun findByThread(threadId: Long): PermanentDeletionEntity?

    @Query("SELECT * FROM permanent_deletion_operations WHERE provider_thread_id = :threadId LIMIT 1")
    fun observeByThread(threadId: Long): Flow<PermanentDeletionEntity?>

    @Query("SELECT * FROM permanent_deletion_operations ORDER BY due_timestamp_ms, deletion_id LIMIT :limit")
    suspend fun recoverySnapshot(limit: Int): List<PermanentDeletionEntity>

    @Query("SELECT * FROM drafts WHERE provider_thread_id = :threadId LIMIT 1")
    suspend fun findDraftByThread(threadId: Long): DraftEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM composer_sms_operations WHERE provider_thread_id = :threadId) OR EXISTS(SELECT 1 FROM scheduled_sms_operations WHERE provider_thread_id = :threadId) OR EXISTS(SELECT 1 FROM send_delay_operations WHERE provider_thread_id = :threadId)")
    suspend fun hasConflictingAction(threadId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: PermanentDeletionEntity): Long

    @Query(
        """
        UPDATE permanent_deletion_operations SET phase_code = :committingPhase,
            updated_timestamp_ms = :updated
        WHERE deletion_id = :id AND phase_code = :pendingPhase
          AND updated_timestamp_ms = :revision
        """,
    )
    suspend fun markCommitting(
        id: Long,
        revision: Long,
        updated: Long,
        pendingPhase: String,
        committingPhase: String,
    ): Int

    @Query(
        """
        UPDATE permanent_deletion_operations SET phase_code = :reviewPhase,
            review_reason_code = :reason,
            updated_timestamp_ms = :updated
        WHERE deletion_id = :id AND phase_code IN (:pendingPhase, :committingPhase)
          AND updated_timestamp_ms = :revision
        """,
    )
    suspend fun markReview(
        id: Long,
        revision: Long,
        reason: String,
        updated: Long,
        pendingPhase: String,
        committingPhase: String,
        reviewPhase: String,
    ): Int

    @Query(
        """
        DELETE FROM permanent_deletion_operations
        WHERE deletion_id = :id AND updated_timestamp_ms = :revision
          AND phase_code IN (:pendingPhase, :reviewPhase)
        """,
    )
    suspend fun deleteUndoable(
        id: Long,
        revision: Long,
        pendingPhase: String,
        reviewPhase: String,
    ): Int

    @Query(
        """
        DELETE FROM permanent_deletion_operations
        WHERE deletion_id = :id AND updated_timestamp_ms = :revision AND (
            phase_code = :committingPhase OR
            (phase_code = :reviewPhase AND review_reason_code = :interruptedReason)
          )
        """,
    )
    suspend fun deleteCommitted(
        id: Long,
        revision: Long,
        committingPhase: String,
        reviewPhase: String,
        interruptedReason: String,
    ): Int

    @Query(
        """
        DELETE FROM drafts
        WHERE draft_id = :draftId AND provider_thread_id = :threadId
          AND updated_timestamp_ms = :revision
        """,
    )
    suspend fun deleteExactDraft(draftId: Long, threadId: Long, revision: Long): Int
}
