// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface AppearanceOverrideDao {
    @Query(
        "SELECT * FROM appearance_override_revision_sequence " +
            "WHERE singleton_id = $APPEARANCE_OVERRIDE_SEQUENCE_SINGLETON_ID LIMIT 1",
    )
    suspend fun loadRevisionSequence(): AppearanceOverrideSequenceEntity?

    @Query("SELECT COUNT(*) FROM appearance_override_revision_sequence")
    suspend fun revisionSequenceCount(): Int

    @Query(
        "SELECT MAX(revision) FROM (" +
            "SELECT revision FROM appearance_screen_overrides " +
            "UNION ALL " +
            "SELECT revision FROM appearance_conversation_overrides" +
            ")",
    )
    suspend fun maximumStoredOverrideRevision(): Long?

    @Query(
        """
        UPDATE appearance_override_revision_sequence
        SET last_allocated_revision = :newRevision
        WHERE singleton_id = $APPEARANCE_OVERRIDE_SEQUENCE_SINGLETON_ID
          AND last_allocated_revision = :expectedRevision
        """,
    )
    suspend fun updateRevisionSequenceIfUnchanged(
        expectedRevision: Long,
        newRevision: Long,
    ): Int

    @Query(
        "SELECT * FROM appearance_screen_overrides " +
            "WHERE screen_code = :screenCode LIMIT 1",
    )
    fun observeScreenOverride(screenCode: String): Flow<AppearanceScreenOverrideEntity?>

    @Query(
        "SELECT * FROM appearance_conversation_overrides " +
            "WHERE participant_set_key = :participantSetKey LIMIT 1",
    )
    fun observeConversationOverride(
        participantSetKey: String,
    ): Flow<AppearanceConversationOverrideEntity?>

    @Query(
        "SELECT * FROM appearance_screen_overrides " +
            "WHERE screen_code = :screenCode LIMIT 1",
    )
    suspend fun findScreenOverride(screenCode: String): AppearanceScreenOverrideEntity?

    @Query(
        "SELECT * FROM appearance_conversation_overrides " +
            "WHERE participant_set_key = :participantSetKey LIMIT 1",
    )
    suspend fun findConversationOverride(
        participantSetKey: String,
    ): AppearanceConversationOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertScreenOverride(entity: AppearanceScreenOverrideEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConversationOverride(entity: AppearanceConversationOverrideEntity)

    @Query(
        """
        UPDATE appearance_screen_overrides
        SET profile_id = :profileId,
            revision = :newRevision
        WHERE screen_code = :screenCode
          AND revision = :expectedRevision
        """,
    )
    suspend fun updateScreenOverrideIfRevision(
        screenCode: String,
        profileId: Long,
        newRevision: Long,
        expectedRevision: Long,
    ): Int

    @Query(
        """
        UPDATE appearance_conversation_overrides
        SET provider_thread_id = :providerThreadId,
            profile_id = :profileId,
            revision = :newRevision
        WHERE participant_set_key = :participantSetKey
          AND revision = :expectedRevision
        """,
    )
    suspend fun updateConversationOverrideIfRevision(
        participantSetKey: String,
        providerThreadId: Long,
        profileId: Long,
        newRevision: Long,
        expectedRevision: Long,
    ): Int

    @Query(
        "DELETE FROM appearance_screen_overrides " +
            "WHERE screen_code = :screenCode AND revision = :expectedRevision",
    )
    suspend fun deleteScreenOverrideIfRevision(
        screenCode: String,
        expectedRevision: Long,
    ): Int

    @Query(
        "DELETE FROM appearance_conversation_overrides " +
            "WHERE participant_set_key = :participantSetKey AND revision = :expectedRevision",
    )
    suspend fun deleteConversationOverrideIfRevision(
        participantSetKey: String,
        expectedRevision: Long,
    ): Int
}
