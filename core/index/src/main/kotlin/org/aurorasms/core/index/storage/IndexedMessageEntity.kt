// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "indexed_messages",
    indices = [
        Index(value = ["provider_kind", "provider_id"], unique = true),
        Index(
            value = ["provider_thread_id", "timestamp_ms", "row_id"],
            orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.DESC],
        ),
        Index(
            value = ["timestamp_ms", "row_id"],
            orders = [Index.Order.DESC, Index.Order.DESC],
        ),
        Index(
            value = ["sender_address", "timestamp_ms", "row_id"],
            orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.DESC],
        ),
        Index(
            value = ["subscription_id", "timestamp_ms", "row_id"],
            orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.DESC],
        ),
        Index(
            value = ["is_read", "timestamp_ms", "row_id"],
            orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.DESC],
        ),
        Index(value = ["provider_kind", "last_seen_generation"]),
    ],
)
data class IndexedMessageEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id")
    val rowId: Long = 0L,
    @ColumnInfo(name = "provider_kind")
    val providerKind: Int,
    @ColumnInfo(name = "provider_id")
    val providerId: Long,
    @ColumnInfo(name = "provider_thread_id")
    val providerThreadId: Long,
    @ColumnInfo(name = "timestamp_ms")
    val timestampMillis: Long,
    @ColumnInfo(name = "sent_timestamp_ms")
    val sentTimestampMillis: Long?,
    @ColumnInfo(name = "direction")
    val direction: Int,
    @ColumnInfo(name = "message_box")
    val messageBox: String,
    @ColumnInfo(name = "message_status")
    val messageStatus: String,
    @ColumnInfo(name = "subscription_id")
    val subscriptionId: Int?,
    @ColumnInfo(name = "sender_address")
    val senderAddress: String?,
    val body: String?,
    val subject: String?,
    @ColumnInfo(name = "attachment_count")
    val attachmentCount: Int,
    @ColumnInfo(name = "attachment_type_summary")
    val attachmentTypeSummary: String,
    @ColumnInfo(name = "attachment_total_bytes")
    val attachmentTotalBytes: Long?,
    @ColumnInfo(name = "is_read")
    val isRead: Boolean,
    @ColumnInfo(name = "is_seen")
    val isSeen: Boolean,
    @ColumnInfo(name = "is_locked")
    val isLocked: Boolean,
    @ColumnInfo(name = "sync_fingerprint")
    val syncFingerprint: String,
    @ColumnInfo(name = "searchable_text")
    val searchableText: String,
    @ColumnInfo(name = "last_seen_generation")
    val lastSeenGeneration: Long,
) {
    init {
        require(rowId >= 0L) { "Local row IDs cannot be negative" }
        require(providerKind in 1..2) { "Only SMS and MMS provider kinds are indexable" }
        require(providerId > 0L) { "Provider message IDs must be positive" }
        require(providerThreadId > 0L) { "Provider thread IDs must be positive" }
        require(timestampMillis >= 0L) { "Indexed timestamps cannot be negative" }
        require(sentTimestampMillis == null || sentTimestampMillis >= 0L) {
            "Indexed sent timestamps cannot be negative"
        }
        require(attachmentCount >= 0) { "Attachment counts cannot be negative" }
        require(attachmentTotalBytes == null || attachmentTotalBytes >= 0L) {
            "Attachment byte-count metadata cannot be negative"
        }
        require(syncFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Sync fingerprints require fixed lowercase SHA-256"
        }
        require(lastSeenGeneration > 0L) { "Indexed rows require a positive scan generation" }
    }

    override fun toString(): String = "IndexedMessageEntity(REDACTED)"
}
