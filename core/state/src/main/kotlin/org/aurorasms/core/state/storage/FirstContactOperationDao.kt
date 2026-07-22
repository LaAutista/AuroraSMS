// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface FirstContactOperationDao {
    @Query("SELECT COUNT(*) FROM first_contact_operations")
    suspend fun count(): Int

    @Query("SELECT * FROM first_contact_operations WHERE first_contact_id = :id LIMIT 1")
    suspend fun findById(id: Long): FirstContactOperationEntity?

    @Query("SELECT * FROM first_contact_operations WHERE draft_id = :draftId LIMIT 1")
    suspend fun findByDraft(draftId: Long): FirstContactOperationEntity?

    @Query(
        "SELECT * FROM first_contact_operations " +
            "WHERE participant_set_key = :participantSetKey LIMIT 1",
    )
    suspend fun findByParticipantSetKey(participantSetKey: String): FirstContactOperationEntity?

    @Query(
        "SELECT * FROM first_contact_operations " +
            "WHERE provider_thread_id = :providerThreadId LIMIT 1",
    )
    suspend fun findByProviderThread(providerThreadId: Long): FirstContactOperationEntity?

    @Query("SELECT * FROM first_contact_operations WHERE draft_id = :draftId LIMIT 1")
    fun observeByDraft(draftId: Long): Flow<FirstContactOperationEntity?>

    @Query(
        "SELECT * FROM first_contact_operations " +
            "ORDER BY updated_timestamp_ms, first_contact_id LIMIT :limit",
    )
    suspend fun recoverySnapshot(limit: Int): List<FirstContactOperationEntity>

    @Query("SELECT * FROM drafts WHERE draft_id = :draftId LIMIT 1")
    suspend fun findDraft(draftId: Long): DraftEntity?

    @Query("SELECT * FROM drafts WHERE provider_thread_id = :providerThreadId LIMIT 1")
    suspend fun findDraftByProviderThread(providerThreadId: Long): DraftEntity?

    @Query(
        "SELECT * FROM draft_attachments WHERE draft_id = :draftId " +
            "ORDER BY attachment_index ASC",
    )
    suspend fun findAttachments(draftId: Long): List<DraftAttachmentEntity>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM composer_sms_operations " +
            "WHERE provider_thread_id = :providerThreadId) OR " +
            "EXISTS(SELECT 1 FROM scheduled_sms_operations " +
            "WHERE provider_thread_id = :providerThreadId) OR " +
            "EXISTS(SELECT 1 FROM send_delay_operations " +
            "WHERE provider_thread_id = :providerThreadId) OR " +
            "EXISTS(SELECT 1 FROM permanent_deletion_operations " +
            "WHERE provider_thread_id = :providerThreadId)",
    )
    suspend fun hasConflictingThreadAction(providerThreadId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: FirstContactOperationEntity): Long

    @Query(
        """
        UPDATE first_contact_operations
        SET phase_code = :targetPhase,
            updated_timestamp_ms = :updatedTimestampMillis
        WHERE first_contact_id = :id
          AND phase_code = :expectedPhase
          AND updated_timestamp_ms = :expectedUpdatedTimestampMillis
        """,
    )
    suspend fun transitionIfCurrent(
        id: Long,
        expectedUpdatedTimestampMillis: Long,
        expectedPhase: String,
        targetPhase: String,
        updatedTimestampMillis: Long,
    ): Int

    @Query(
        """
        UPDATE first_contact_operations
        SET phase_code = :threadBoundPhase,
            provider_thread_id = :providerThreadId,
            updated_timestamp_ms = :updatedTimestampMillis
        WHERE first_contact_id = :id
          AND phase_code = :resolutionStartedPhase
          AND updated_timestamp_ms = :expectedUpdatedTimestampMillis
          AND provider_thread_id IS NULL
          AND handoff_draft_revision_ms IS NULL
        """,
    )
    suspend fun bindThreadIfCurrent(
        id: Long,
        expectedUpdatedTimestampMillis: Long,
        providerThreadId: Long,
        updatedTimestampMillis: Long,
        resolutionStartedPhase: String,
        threadBoundPhase: String,
    ): Int

    @Query(
        """
        UPDATE drafts
        SET provider_thread_id = :providerThreadId,
            participant_set_key = NULL,
            updated_timestamp_ms = :handoffDraftRevisionMillis
        WHERE draft_id = :draftId
          AND provider_thread_id IS NULL
          AND participant_set_key = :participantSetKey
          AND updated_timestamp_ms = :sourceDraftRevisionMillis
        """,
    )
    suspend fun promoteExactParticipantDraft(
        draftId: Long,
        participantSetKey: String,
        sourceDraftRevisionMillis: Long,
        providerThreadId: Long,
        handoffDraftRevisionMillis: Long,
    ): Int

    @Query(
        """
        UPDATE first_contact_operations
        SET phase_code = :handoffReservedPhase,
            handoff_draft_revision_ms = :handoffDraftRevisionMillis,
            updated_timestamp_ms = :updatedTimestampMillis
        WHERE first_contact_id = :id
          AND phase_code = :threadBoundPhase
          AND updated_timestamp_ms = :expectedUpdatedTimestampMillis
          AND provider_thread_id = :providerThreadId
          AND handoff_draft_revision_ms IS NULL
        """,
    )
    suspend fun reserveHandoffIfCurrent(
        id: Long,
        expectedUpdatedTimestampMillis: Long,
        providerThreadId: Long,
        handoffDraftRevisionMillis: Long,
        updatedTimestampMillis: Long,
        threadBoundPhase: String,
        handoffReservedPhase: String,
    ): Int

    @Query(
        """
        DELETE FROM first_contact_operations
        WHERE first_contact_id = :id
          AND phase_code = :knownUnsentPhase
          AND updated_timestamp_ms = :expectedUpdatedTimestampMillis
        """,
    )
    suspend fun releaseKnownUnsentIfCurrent(
        id: Long,
        expectedUpdatedTimestampMillis: Long,
        knownUnsentPhase: String,
    ): Int
}
