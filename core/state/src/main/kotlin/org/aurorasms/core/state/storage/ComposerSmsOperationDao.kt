// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ComposerSmsOperationDao {
    @Query("SELECT COUNT(*) FROM composer_sms_operations")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM acknowledged_composer_sms_receipts")
    suspend fun acknowledgedCount(): Int

    @Query("SELECT * FROM composer_sms_operations WHERE local_operation_id = :localOperationId LIMIT 1")
    suspend fun findByLocalId(localOperationId: Long): ComposerSmsOperationEntity?

    @Query(
        "SELECT * FROM acknowledged_composer_sms_receipts " +
            "WHERE local_operation_id = :localOperationId LIMIT 1",
    )
    suspend fun findAcknowledgedByLocalId(localOperationId: Long): AcknowledgedComposerSmsEntity?

    @Query("SELECT * FROM composer_sms_operations WHERE provider_thread_id = :providerThreadId LIMIT 1")
    suspend fun findByProviderThreadId(providerThreadId: Long): ComposerSmsOperationEntity?

    @Query("SELECT * FROM drafts WHERE draft_id = :draftId LIMIT 1")
    suspend fun findDraftById(draftId: Long): DraftEntity?

    @Query("SELECT * FROM composer_sms_operations WHERE provider_thread_id = :providerThreadId LIMIT 1")
    fun observeByProviderThreadId(providerThreadId: Long): Flow<ComposerSmsOperationEntity?>

    @Query(
        "SELECT * FROM composer_sms_operations " +
            "ORDER BY created_timestamp_ms ASC, local_operation_id ASC LIMIT :limit",
    )
    suspend fun recoverySnapshot(limit: Int): List<ComposerSmsOperationEntity>

    @Query(
        "SELECT * FROM acknowledged_composer_sms_receipts " +
            "ORDER BY acknowledged_timestamp_ms ASC, local_operation_id ASC LIMIT :limit",
    )
    suspend fun acknowledgedRecoverySnapshot(limit: Int): List<AcknowledgedComposerSmsEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ComposerSmsOperationEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAcknowledged(entity: AcknowledgedComposerSmsEntity)

    @Query(
        """
        UPDATE composer_sms_operations
        SET phase_code = :preparedPhase,
            provider_message_id = :providerMessageId,
            provider_conversation_id = :providerConversationId,
            unit_count = :unitCount,
            updated_timestamp_ms = :updatedTimestampMillis
        WHERE local_operation_id = :localOperationId
          AND phase_code = :reservedPhase
          AND updated_timestamp_ms = :expectedUpdatedTimestampMillis
          AND provider_message_id IS NULL
          AND provider_conversation_id IS NULL
          AND unit_count IS NULL
        """,
    )
    suspend fun markPreparedIfCurrent(
        localOperationId: Long,
        expectedUpdatedTimestampMillis: Long,
        providerMessageId: Long,
        providerConversationId: Long,
        unitCount: Int,
        updatedTimestampMillis: Long,
        reservedPhase: String,
        preparedPhase: String,
    ): Int

    @Query(
        """
        UPDATE composer_sms_operations
        SET phase_code = :targetPhase,
            updated_timestamp_ms = :updatedTimestampMillis
        WHERE local_operation_id = :localOperationId
          AND phase_code = :expectedPhase
          AND updated_timestamp_ms = :expectedUpdatedTimestampMillis
          AND provider_message_id = :providerMessageId
          AND provider_conversation_id = :providerConversationId
          AND unit_count = :unitCount
        """,
    )
    suspend fun transitionBoundIfCurrent(
        localOperationId: Long,
        expectedUpdatedTimestampMillis: Long,
        expectedPhase: String,
        targetPhase: String,
        providerMessageId: Long,
        providerConversationId: Long,
        unitCount: Int,
        updatedTimestampMillis: Long,
    ): Int

    @Query(
        """
        UPDATE composer_sms_operations
        SET phase_code = :knownUnsentPhase,
            updated_timestamp_ms = :updatedTimestampMillis
        WHERE local_operation_id = :localOperationId
          AND phase_code = :expectedPhase
          AND updated_timestamp_ms = :expectedUpdatedTimestampMillis
        """,
    )
    suspend fun markKnownUnsentIfCurrent(
        localOperationId: Long,
        expectedUpdatedTimestampMillis: Long,
        expectedPhase: String,
        knownUnsentPhase: String,
        updatedTimestampMillis: Long,
    ): Int

    @Query(
        """
        DELETE FROM drafts
        WHERE draft_id = :draftId
          AND provider_thread_id = :providerThreadId
          AND participant_set_key IS NULL
          AND updated_timestamp_ms = :expectedDraftRevisionMillis
        """,
    )
    suspend fun deleteExactProviderThreadDraft(
        draftId: Long,
        providerThreadId: Long,
        expectedDraftRevisionMillis: Long,
    ): Int

    @Query(
        """
        DELETE FROM composer_sms_operations
        WHERE local_operation_id = :localOperationId
          AND phase_code = :expectedPhase
          AND updated_timestamp_ms = :expectedUpdatedTimestampMillis
          AND provider_message_id = :providerMessageId
          AND provider_conversation_id = :providerConversationId
          AND unit_count = :unitCount
        """,
    )
    suspend fun deleteBoundIfCurrent(
        localOperationId: Long,
        expectedUpdatedTimestampMillis: Long,
        expectedPhase: String,
        providerMessageId: Long,
        providerConversationId: Long,
        unitCount: Int,
    ): Int

    @Query(
        """
        DELETE FROM composer_sms_operations
        WHERE local_operation_id = :localOperationId
          AND phase_code = :expectedPhase
          AND updated_timestamp_ms = :expectedUpdatedTimestampMillis
        """,
    )
    suspend fun deleteTerminalIfCurrent(
        localOperationId: Long,
        expectedUpdatedTimestampMillis: Long,
        expectedPhase: String,
    ): Int

    @Query(
        """
        UPDATE acknowledged_composer_sms_receipts
        SET callback_proof_code = :targetCallbackProof,
            updated_timestamp_ms = :updatedTimestampMillis
        WHERE local_operation_id = :localOperationId
          AND callback_proof_code = :awaitingCallbackProof
          AND updated_timestamp_ms = :expectedUpdatedTimestampMillis
          AND provider_message_id = :providerMessageId
          AND provider_conversation_id = :providerConversationId
          AND unit_count = :unitCount
        """,
    )
    suspend fun markAcknowledgedCallbackIfCurrent(
        localOperationId: Long,
        expectedUpdatedTimestampMillis: Long,
        awaitingCallbackProof: String,
        targetCallbackProof: String,
        providerMessageId: Long,
        providerConversationId: Long,
        unitCount: Int,
        updatedTimestampMillis: Long,
    ): Int

    @Query(
        """
        DELETE FROM acknowledged_composer_sms_receipts
        WHERE local_operation_id = :localOperationId
          AND callback_proof_code = :expectedCallbackProof
          AND updated_timestamp_ms = :expectedUpdatedTimestampMillis
          AND provider_message_id = :providerMessageId
          AND provider_conversation_id = :providerConversationId
          AND unit_count = :unitCount
        """,
    )
    suspend fun deleteAcknowledgedIfCurrent(
        localOperationId: Long,
        expectedUpdatedTimestampMillis: Long,
        expectedCallbackProof: String,
        providerMessageId: Long,
        providerConversationId: Long,
        unitCount: Int,
    ): Int
}
