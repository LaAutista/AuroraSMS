// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.storage

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "index_checkpoints",
    primaryKeys = ["generation_id", "provider_kind"],
)
data class IndexCheckpointEntity(
    @ColumnInfo(name = "generation_id")
    val generationId: Long,
    @ColumnInfo(name = "provider_kind")
    val providerKind: Int,
    @ColumnInfo(name = "cursor_timestamp_ms")
    val cursorTimestampMillis: Long?,
    @ColumnInfo(name = "cursor_provider_id")
    val cursorProviderId: Long?,
    val exhausted: Boolean,
    @ColumnInfo(name = "committed_count")
    val committedCount: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMillis: Long,
    @ColumnInfo(name = "verified_provider_count")
    val verifiedProviderCount: Long? = null,
) {
    init {
        require(generationId > 0L) { "Checkpoint generation IDs must be positive" }
        require(providerKind in 1..2) { "Only SMS and MMS checkpoints are supported" }
        require((cursorTimestampMillis == null) == (cursorProviderId == null)) {
            "Checkpoint cursor components must both be present or absent"
        }
        require(cursorTimestampMillis == null || cursorTimestampMillis >= 0L) {
            "Checkpoint timestamps cannot be negative"
        }
        require(cursorProviderId == null || cursorProviderId > 0L) {
            "Checkpoint provider IDs must be positive"
        }
        require(committedCount >= 0L) { "Checkpoint counts cannot be negative" }
        require(updatedAtMillis >= 0L) { "Checkpoint update times cannot be negative" }
        require(verifiedProviderCount == null || verifiedProviderCount >= 0L) {
            "Verified provider counts cannot be negative"
        }
    }

    override fun toString(): String =
        "IndexCheckpointEntity(providerKind=$providerKind, exhausted=$exhausted, " +
            "committedCount=$committedCount, cursor=REDACTED)"
}
