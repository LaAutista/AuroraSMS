// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface ConversationSubscriptionPreferenceDao {
    @Query(
        "SELECT * FROM conversation_subscription_preferences " +
            "WHERE participant_set_key = :participantSetKey LIMIT 1",
    )
    suspend fun find(participantSetKey: String): ConversationSubscriptionPreferenceEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ConversationSubscriptionPreferenceEntity)

    @Query(
        """
        UPDATE conversation_subscription_preferences
        SET provider_thread_id = :providerThreadId,
            subscription_id = :subscriptionId,
            revision = :newRevision,
            updated_timestamp_ms = :updatedTimestampMillis
        WHERE participant_set_key = :participantSetKey
          AND revision = :expectedRevision
        """,
    )
    suspend fun updateIfRevision(
        participantSetKey: String,
        providerThreadId: Long,
        subscriptionId: Int,
        newRevision: Long,
        updatedTimestampMillis: Long,
        expectedRevision: Long,
    ): Int
}
