// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface DraftDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: DraftEntity): Long

    @Query("SELECT * FROM drafts WHERE draft_id = :draftId LIMIT 1")
    suspend fun findById(draftId: Long): DraftEntity?

    @Query(
        """
        UPDATE drafts
        SET provider_thread_id = :providerThreadId,
            participant_set_key = :participantSetKey,
            body = :body,
            subject = :subject,
            updated_timestamp_ms = :updatedTimestampMillis
        WHERE draft_id = :draftId
          AND created_timestamp_ms = :createdTimestampMillis
          AND updated_timestamp_ms = :expectedUpdatedTimestampMillis
        """,
    )
    suspend fun updateIfUnchanged(
        draftId: Long,
        providerThreadId: Long?,
        participantSetKey: String?,
        body: String?,
        subject: String?,
        createdTimestampMillis: Long,
        updatedTimestampMillis: Long,
        expectedUpdatedTimestampMillis: Long,
    ): Int

    @Query("DELETE FROM drafts WHERE draft_id = :draftId")
    suspend fun deleteById(draftId: Long): Int
}
