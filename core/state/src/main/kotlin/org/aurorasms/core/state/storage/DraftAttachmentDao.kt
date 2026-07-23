// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface DraftAttachmentDao {
    @Query(
        "SELECT * FROM draft_attachments WHERE draft_id = :draftId " +
            "ORDER BY attachment_index ASC",
    )
    suspend fun findByDraftId(draftId: Long): List<DraftAttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(attachments: List<DraftAttachmentEntity>)

    @Query("DELETE FROM draft_attachments WHERE draft_id = :draftId")
    suspend fun deleteByDraftId(draftId: Long): Int
}
