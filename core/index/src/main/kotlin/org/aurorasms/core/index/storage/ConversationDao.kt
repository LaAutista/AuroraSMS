// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.storage

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import org.aurorasms.core.index.MAXIMUM_ANCHOR_HALF_WINDOW
import org.aurorasms.core.index.conversation.MAXIMUM_CONVERSATION_PAGE_SIZE
import org.aurorasms.core.index.conversation.MAXIMUM_VERIFIED_CONVERSATION_PARTICIPANTS
import org.aurorasms.core.index.timeline.MAXIMUM_TIMELINE_BODY_PREVIEW_CHARACTERS
import org.aurorasms.core.index.timeline.MAXIMUM_TIMELINE_FULL_BODY_CHARACTERS
import org.aurorasms.core.index.timeline.MAXIMUM_TIMELINE_FULL_SUBJECT_CHARACTERS
import org.aurorasms.core.index.timeline.MAXIMUM_TIMELINE_PAGE_SIZE

data class StoredTimelineMessage(
    @ColumnInfo(name = "row_id") val rowId: Long,
    @ColumnInfo(name = "provider_kind") val providerKind: Int,
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "provider_thread_id") val providerThreadId: Long,
    @ColumnInfo(name = "timestamp_ms") val timestampMillis: Long,
    @ColumnInfo(name = "sent_timestamp_ms") val sentTimestampMillis: Long?,
    val direction: Int,
    @ColumnInfo(name = "message_box") val messageBox: String,
    @ColumnInfo(name = "message_status") val messageStatus: String,
    @ColumnInfo(name = "subscription_id") val subscriptionId: Int?,
    @ColumnInfo(name = "sender_address") val senderAddress: String?,
    @ColumnInfo(name = "body_preview") val bodyPreview: String?,
    @ColumnInfo(name = "body_truncated") val bodyTruncated: Boolean,
    val subject: String?,
    @ColumnInfo(name = "attachment_count") val attachmentCount: Int,
    @ColumnInfo(name = "attachment_type_summary") val attachmentTypeSummary: String,
    @ColumnInfo(name = "is_read") val isRead: Boolean,
    @ColumnInfo(name = "is_seen") val isSeen: Boolean,
    @ColumnInfo(name = "is_locked") val isLocked: Boolean,
) {
    override fun toString(): String = "StoredTimelineMessage(REDACTED)"
}

data class StoredTimelineContent(
    val body: String?,
    val subject: String?,
    @ColumnInfo(name = "source_truncated") val sourceTruncated: Boolean,
) {
    override fun toString(): String = "StoredTimelineContent(sourceTruncated=$sourceTruncated, REDACTED)"
}

@Dao
abstract class ConversationDao {
    @Query(
        """
        SELECT * FROM indexed_conversations
        INDEXED BY index_indexed_conversations_last_seen_generation_latest_timestamp_ms_latest_row_id
        WHERE last_seen_generation = :generationId
        ORDER BY latest_timestamp_ms DESC, latest_row_id DESC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun inboxFirstQuery(generationId: Long, limit: Int): List<IndexedConversationEntity>

    @Query(
        """
        SELECT * FROM indexed_conversations
        INDEXED BY index_indexed_conversations_last_seen_generation_latest_timestamp_ms_latest_row_id
        WHERE last_seen_generation = :generationId
          AND (
            latest_timestamp_ms < :timestampMillis OR
            (latest_timestamp_ms = :timestampMillis AND latest_row_id < :rowId)
          )
        ORDER BY latest_timestamp_ms DESC, latest_row_id DESC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun inboxOlderQuery(
        generationId: Long,
        timestampMillis: Long,
        rowId: Long,
        limit: Int,
    ): List<IndexedConversationEntity>

    @Query(
        """
        SELECT * FROM indexed_conversations
        INDEXED BY index_indexed_conversations_last_seen_generation_latest_timestamp_ms_latest_row_id
        WHERE last_seen_generation = :generationId
          AND (
            latest_timestamp_ms > :timestampMillis OR
            (latest_timestamp_ms = :timestampMillis AND latest_row_id > :rowId)
          )
        ORDER BY latest_timestamp_ms ASC, latest_row_id ASC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun inboxNewerQuery(
        generationId: Long,
        timestampMillis: Long,
        rowId: Long,
        limit: Int,
    ): List<IndexedConversationEntity>

    suspend fun inboxFirst(generationId: Long, limit: Int): List<IndexedConversationEntity> {
        require(generationId > 0L)
        require(limit in 1..MAXIMUM_CONVERSATION_PAGE_SIZE + 1)
        return inboxFirstQuery(generationId, limit)
    }

    suspend fun inboxOlder(
        generationId: Long,
        timestampMillis: Long,
        rowId: Long,
        limit: Int,
    ): List<IndexedConversationEntity> {
        require(generationId > 0L && timestampMillis >= 0L && rowId > 0L)
        require(limit in 1..MAXIMUM_CONVERSATION_PAGE_SIZE + 1)
        return inboxOlderQuery(generationId, timestampMillis, rowId, limit)
    }

    suspend fun inboxNewer(
        generationId: Long,
        timestampMillis: Long,
        rowId: Long,
        limit: Int,
    ): List<IndexedConversationEntity> {
        require(generationId > 0L && timestampMillis >= 0L && rowId > 0L)
        require(limit in 1..MAXIMUM_CONVERSATION_PAGE_SIZE + 1)
        return inboxNewerQuery(generationId, timestampMillis, rowId, limit)
    }

    @Query(
        """
        SELECT p.* FROM indexed_conversation_participants AS p
        WHERE p.last_seen_generation = :generationId
          AND p.provider_thread_id IN (:providerThreadIds)
          AND (
            SELECT COUNT(*)
            FROM indexed_conversation_participants AS ranked
            WHERE ranked.last_seen_generation = p.last_seen_generation
              AND ranked.provider_thread_id = p.provider_thread_id
              AND ranked.address <= p.address
          ) <= :perThreadLimit
        ORDER BY p.provider_thread_id ASC, p.address ASC
        """,
    )
    protected abstract suspend fun participantPreviewsQuery(
        generationId: Long,
        providerThreadIds: List<Long>,
        perThreadLimit: Int,
    ): List<IndexedConversationParticipantEntity>

    suspend fun participantPreviews(
        generationId: Long,
        providerThreadIds: List<Long>,
        perThreadLimit: Int,
    ): List<IndexedConversationParticipantEntity> {
        require(generationId > 0L)
        require(providerThreadIds.size in 1..MAXIMUM_CONVERSATION_PAGE_SIZE)
        require(providerThreadIds.all { it > 0L })
        require(perThreadLimit in 1..8)
        return participantPreviewsQuery(generationId, providerThreadIds.distinct(), perThreadLimit)
    }

    @Query(
        """
        SELECT * FROM indexed_conversation_participants
        INDEXED BY index_indexed_conversation_participants_last_seen_generation_provider_thread_id_address
        WHERE last_seen_generation = :generationId
          AND provider_thread_id = :providerThreadId
        ORDER BY address ASC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun verifiedIdentityParticipantsQuery(
        generationId: Long,
        providerThreadId: Long,
        limit: Int,
    ): List<IndexedConversationParticipantEntity>

    /** Reads one exact thread, plus one sentinel row used to fail closed above the identity bound. */
    suspend fun verifiedIdentityParticipants(
        generationId: Long,
        providerThreadId: Long,
    ): List<IndexedConversationParticipantEntity> {
        require(generationId > 0L)
        require(providerThreadId > 0L)
        return verifiedIdentityParticipantsQuery(
            generationId = generationId,
            providerThreadId = providerThreadId,
            limit = MAXIMUM_VERIFIED_CONVERSATION_PARTICIPANTS + 1,
        )
    }

    @Query("SELECT * FROM indexed_conversations WHERE provider_thread_id = :providerThreadId AND last_seen_generation = :generationId LIMIT 1")
    abstract suspend fun conversation(
        providerThreadId: Long,
        generationId: Long,
    ): IndexedConversationEntity?

    @Query(
        """
        SELECT row_id, provider_kind, provider_id, provider_thread_id,
               timestamp_ms, sent_timestamp_ms, direction, message_box,
               message_status, subscription_id, sender_address,
               substr(body, 1, :bodyLimit) AS body_preview,
               CASE WHEN body IS NOT NULL AND length(body) > :bodyLimit THEN 1 ELSE 0 END AS body_truncated,
               subject, attachment_count, attachment_type_summary,
               is_read, is_seen, is_locked
        FROM indexed_messages
        INDEXED BY index_indexed_messages_provider_thread_id_timestamp_ms_row_id
        WHERE provider_thread_id = :providerThreadId
          AND last_seen_generation = :generationId
        ORDER BY timestamp_ms DESC, row_id DESC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun timelineLatestQuery(
        providerThreadId: Long,
        generationId: Long,
        limit: Int,
        bodyLimit: Int,
    ): List<StoredTimelineMessage>

    @Query(
        """
        SELECT row_id, provider_kind, provider_id, provider_thread_id,
               timestamp_ms, sent_timestamp_ms, direction, message_box,
               message_status, subscription_id, sender_address,
               substr(body, 1, :bodyLimit) AS body_preview,
               CASE WHEN body IS NOT NULL AND length(body) > :bodyLimit THEN 1 ELSE 0 END AS body_truncated,
               subject, attachment_count, attachment_type_summary,
               is_read, is_seen, is_locked
        FROM indexed_messages
        INDEXED BY index_indexed_messages_provider_thread_id_timestamp_ms_row_id
        WHERE provider_thread_id = :providerThreadId
          AND last_seen_generation = :generationId
          AND (timestamp_ms < :timestampMillis OR (timestamp_ms = :timestampMillis AND row_id < :rowId))
        ORDER BY timestamp_ms DESC, row_id DESC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun timelineOlderQuery(
        providerThreadId: Long,
        generationId: Long,
        timestampMillis: Long,
        rowId: Long,
        limit: Int,
        bodyLimit: Int,
    ): List<StoredTimelineMessage>

    @Query(
        """
        SELECT row_id, provider_kind, provider_id, provider_thread_id,
               timestamp_ms, sent_timestamp_ms, direction, message_box,
               message_status, subscription_id, sender_address,
               substr(body, 1, :bodyLimit) AS body_preview,
               CASE WHEN body IS NOT NULL AND length(body) > :bodyLimit THEN 1 ELSE 0 END AS body_truncated,
               subject, attachment_count, attachment_type_summary,
               is_read, is_seen, is_locked
        FROM indexed_messages
        INDEXED BY index_indexed_messages_provider_thread_id_timestamp_ms_row_id
        WHERE provider_thread_id = :providerThreadId
          AND last_seen_generation = :generationId
          AND (timestamp_ms > :timestampMillis OR (timestamp_ms = :timestampMillis AND row_id > :rowId))
        ORDER BY timestamp_ms ASC, row_id ASC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun timelineNewerQuery(
        providerThreadId: Long,
        generationId: Long,
        timestampMillis: Long,
        rowId: Long,
        limit: Int,
        bodyLimit: Int,
    ): List<StoredTimelineMessage>

    suspend fun timelineLatest(
        providerThreadId: Long,
        generationId: Long,
        limit: Int,
    ): List<StoredTimelineMessage> {
        require(providerThreadId > 0L && generationId > 0L)
        require(limit in 1..MAXIMUM_TIMELINE_PAGE_SIZE + 1)
        return timelineLatestQuery(
            providerThreadId,
            generationId,
            limit,
            MAXIMUM_TIMELINE_BODY_PREVIEW_CHARACTERS,
        )
    }

    suspend fun timelineOlder(
        providerThreadId: Long,
        generationId: Long,
        timestampMillis: Long,
        rowId: Long,
        limit: Int,
    ): List<StoredTimelineMessage> {
        require(providerThreadId > 0L && generationId > 0L)
        require(timestampMillis >= 0L && rowId > 0L)
        require(limit in 1..MAXIMUM_TIMELINE_PAGE_SIZE + 1)
        return timelineOlderQuery(
            providerThreadId,
            generationId,
            timestampMillis,
            rowId,
            limit,
            MAXIMUM_TIMELINE_BODY_PREVIEW_CHARACTERS,
        )
    }

    suspend fun timelineNewer(
        providerThreadId: Long,
        generationId: Long,
        timestampMillis: Long,
        rowId: Long,
        limit: Int,
    ): List<StoredTimelineMessage> {
        require(providerThreadId > 0L && generationId > 0L)
        require(timestampMillis >= 0L && rowId > 0L)
        require(limit in 1..MAXIMUM_TIMELINE_PAGE_SIZE + 1)
        return timelineNewerQuery(
            providerThreadId,
            generationId,
            timestampMillis,
            rowId,
            limit,
            MAXIMUM_TIMELINE_BODY_PREVIEW_CHARACTERS,
        )
    }

    @Query(
        """
        SELECT substr(body, 1, :bodyLimit) AS body,
               substr(subject, 1, :subjectLimit) AS subject,
               CASE
                 WHEN (body IS NOT NULL AND length(body) > :bodyLimit)
                   OR (subject IS NOT NULL AND length(subject) > :subjectLimit)
                 THEN 1 ELSE 0
               END AS source_truncated
        FROM indexed_messages
        WHERE provider_kind = :providerKind
          AND provider_id = :providerId
          AND provider_thread_id = :providerThreadId
          AND last_seen_generation = :generationId
        LIMIT 1
        """,
    )
    protected abstract suspend fun timelineContentQuery(
        providerKind: Int,
        providerId: Long,
        providerThreadId: Long,
        generationId: Long,
        bodyLimit: Int,
        subjectLimit: Int,
    ): StoredTimelineContent?

    suspend fun timelineContent(
        providerKind: Int,
        providerId: Long,
        providerThreadId: Long,
        generationId: Long,
    ): StoredTimelineContent? {
        require(providerKind in 1..2 && providerId > 0L && providerThreadId > 0L && generationId > 0L)
        return timelineContentQuery(
            providerKind = providerKind,
            providerId = providerId,
            providerThreadId = providerThreadId,
            generationId = generationId,
            bodyLimit = MAXIMUM_TIMELINE_FULL_BODY_CHARACTERS,
            subjectLimit = MAXIMUM_TIMELINE_FULL_SUBJECT_CHARACTERS,
        )
    }
}
