// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import org.aurorasms.core.model.ParticipantAddress

@Entity(
    tableName = "indexed_conversation_participants",
    primaryKeys = ["provider_thread_id", "address"],
    indices = [Index(value = ["last_seen_generation", "provider_thread_id", "address"])],
)
data class IndexedConversationParticipantEntity(
    @ColumnInfo(name = "provider_thread_id")
    val providerThreadId: Long,
    val address: String,
    @ColumnInfo(name = "last_seen_generation")
    val lastSeenGeneration: Long,
) {
    init {
        require(providerThreadId > 0L) { "Participant threads must be positive" }
        ParticipantAddress(address)
        require(lastSeenGeneration > 0L) { "Participant rows require a generation" }
    }

    override fun toString(): String = "IndexedConversationParticipantEntity(REDACTED)"
}
