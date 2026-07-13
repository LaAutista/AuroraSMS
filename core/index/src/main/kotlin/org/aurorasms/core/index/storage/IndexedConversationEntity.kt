// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.aurorasms.core.index.conversation.MAXIMUM_CONVERSATION_SNIPPET_CHARACTERS

@Entity(
    tableName = "indexed_conversations",
    indices = [
        Index(
            value = ["last_seen_generation", "latest_timestamp_ms", "latest_row_id"],
            orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.DESC],
        ),
        Index(value = ["latest_provider_kind", "latest_provider_id"]),
    ],
)
data class IndexedConversationEntity(
    @PrimaryKey
    @ColumnInfo(name = "provider_thread_id")
    val providerThreadId: Long,
    @ColumnInfo(name = "latest_row_id")
    val latestRowId: Long,
    @ColumnInfo(name = "latest_provider_kind")
    val latestProviderKind: Int,
    @ColumnInfo(name = "latest_provider_id")
    val latestProviderId: Long,
    @ColumnInfo(name = "latest_timestamp_ms")
    val latestTimestampMillis: Long,
    @ColumnInfo(name = "latest_sent_timestamp_ms")
    val latestSentTimestampMillis: Long?,
    @ColumnInfo(name = "latest_direction")
    val latestDirection: Int,
    @ColumnInfo(name = "latest_message_box")
    val latestMessageBox: String,
    @ColumnInfo(name = "latest_message_status")
    val latestMessageStatus: String,
    @ColumnInfo(name = "latest_subscription_id")
    val latestSubscriptionId: Int?,
    @ColumnInfo(name = "latest_sender_address")
    val latestSenderAddress: String?,
    @ColumnInfo(name = "latest_snippet")
    val latestSnippet: String?,
    @ColumnInfo(name = "latest_attachment_count")
    val latestAttachmentCount: Int,
    @ColumnInfo(name = "latest_attachment_type_summary")
    val latestAttachmentTypeSummary: String,
    @ColumnInfo(name = "latest_is_read")
    val latestIsRead: Boolean,
    @ColumnInfo(name = "indexed_message_count")
    val indexedMessageCount: Long,
    @ColumnInfo(name = "indexed_unread_count")
    val indexedUnreadCount: Long,
    @ColumnInfo(name = "indexed_participant_count")
    val indexedParticipantCount: Int,
    @ColumnInfo(name = "participants_truncated")
    val participantsTruncated: Boolean,
    @ColumnInfo(name = "last_seen_generation")
    val lastSeenGeneration: Long,
) {
    init {
        require(providerThreadId > 0L) { "Indexed conversation threads must be positive" }
        require(latestRowId > 0L) { "Indexed conversations require a latest row" }
        require(latestProviderKind in ProviderKindCode.SMS..ProviderKindCode.MMS) {
            "Indexed conversations require SMS or MMS latest identity"
        }
        require(latestProviderId > 0L) { "Latest provider message IDs must be positive" }
        require(latestTimestampMillis >= 0L) { "Latest timestamps cannot be negative" }
        require(latestSentTimestampMillis == null || latestSentTimestampMillis >= 0L) {
            "Latest sent timestamps cannot be negative"
        }
        require(latestSnippet == null || latestSnippet.length <= MAXIMUM_CONVERSATION_SNIPPET_CHARACTERS) {
            "Indexed conversation snippets must remain bounded"
        }
        require(latestAttachmentCount >= 0) { "Latest attachment counts cannot be negative" }
        require(indexedMessageCount > 0L) { "Indexed conversations require at least one message" }
        require(indexedUnreadCount in 0L..indexedMessageCount) { "Indexed unread counts are invalid" }
        require(indexedParticipantCount >= 0) { "Indexed participant counts cannot be negative" }
        require(lastSeenGeneration > 0L) { "Indexed conversations require a generation" }
    }

    override fun toString(): String = "IndexedConversationEntity(REDACTED)"
}
