// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface SpamSafetyDecisionDao {
    @Query("SELECT COUNT(*) FROM spam_safety_decisions")
    suspend fun count(): Int

    @Query("SELECT * FROM spam_safety_decisions ORDER BY updated_timestamp_ms DESC LIMIT 256")
    fun observeAll(): Flow<List<SpamSafetyDecisionEntity>>

    @Query("SELECT * FROM spam_safety_decisions WHERE participant_set_key = :key LIMIT 1")
    suspend fun find(key: String): SpamSafetyDecisionEntity?

    @Query(
        "SELECT EXISTS(SELECT 1 FROM spam_safety_decisions " +
            "WHERE single_sender_key = :senderKey AND blocked = 1 LIMIT 1)",
    )
    suspend fun isSenderBlocked(senderKey: String): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SpamSafetyDecisionEntity)

    @Query(
        """
        UPDATE spam_safety_decisions
        SET provider_thread_id = :threadId,
            single_sender_key = :senderKey,
            classification_code = :classificationCode,
            blocked = :blocked,
            revision = :newRevision,
            updated_timestamp_ms = :updated
        WHERE participant_set_key = :key AND revision = :expectedRevision
        """,
    )
    suspend fun updateIfRevision(
        key: String,
        threadId: Long,
        senderKey: String?,
        classificationCode: String,
        blocked: Boolean,
        newRevision: Long,
        updated: Long,
        expectedRevision: Long,
    ): Int

    @Query(
        "DELETE FROM spam_safety_decisions " +
            "WHERE participant_set_key = :key AND revision = :expectedRevision",
    )
    suspend fun deleteIfRevision(key: String, expectedRevision: Long): Int
}
