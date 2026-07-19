// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ScheduledSmsDao {
    @Query("SELECT COUNT(*) FROM scheduled_sms_operations")
    suspend fun count(): Int

    @Query("SELECT * FROM scheduled_sms_operations WHERE schedule_id = :id LIMIT 1")
    suspend fun findById(id: Long): ScheduledSmsEntity?

    @Query("SELECT * FROM scheduled_sms_operations WHERE provider_thread_id = :threadId LIMIT 1")
    suspend fun findByThread(threadId: Long): ScheduledSmsEntity?

    @Query("SELECT * FROM scheduled_sms_operations WHERE provider_thread_id = :threadId LIMIT 1")
    fun observeByThread(threadId: Long): Flow<ScheduledSmsEntity?>

    @Query("SELECT * FROM scheduled_sms_operations ORDER BY due_timestamp_ms, schedule_id LIMIT :limit")
    suspend fun recoverySnapshot(limit: Int): List<ScheduledSmsEntity>

    @Query("SELECT * FROM drafts WHERE draft_id = :draftId LIMIT 1")
    suspend fun findDraft(draftId: Long): DraftEntity?

    @Query("SELECT * FROM composer_sms_operations WHERE provider_thread_id = :threadId LIMIT 1")
    suspend fun findComposerOperation(threadId: Long): ComposerSmsOperationEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ScheduledSmsEntity): Long

    @Query(
        """
        UPDATE scheduled_sms_operations SET
            precision_code = :precisionCode,
            armed_wall_timestamp_ms = :armedWall,
            armed_elapsed_realtime_ms = :armedElapsed,
            updated_timestamp_ms = :updated
        WHERE schedule_id = :id AND phase_code = :pendingPhase
          AND updated_timestamp_ms = :expectedRevision
        """,
    )
    suspend fun updateArmed(
        id: Long,
        expectedRevision: Long,
        precisionCode: String,
        armedWall: Long,
        armedElapsed: Long,
        updated: Long,
        pendingPhase: String,
    ): Int

    @Query(
        """
        UPDATE scheduled_sms_operations SET phase_code = :dispatchingPhase,
            updated_timestamp_ms = :updated
        WHERE schedule_id = :id AND phase_code = :pendingPhase
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
        UPDATE scheduled_sms_operations SET phase_code = :reviewPhase,
            review_reason_code = :reasonCode,
            updated_timestamp_ms = :updated
        WHERE schedule_id = :id AND updated_timestamp_ms = :expectedRevision
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

    @Query("DELETE FROM scheduled_sms_operations WHERE schedule_id = :id AND updated_timestamp_ms = :revision")
    suspend fun deleteIfCurrent(id: Long, revision: Long): Int
}
