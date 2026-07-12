// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

@Fts4(
    contentEntity = IndexedMessageEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
)
@Entity(tableName = "indexed_messages_fts")
data class IndexedMessageFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,
    val body: String?,
    val subject: String?,
    @ColumnInfo(name = "searchable_text")
    val searchableText: String,
) {
    override fun toString(): String = "IndexedMessageFtsEntity(REDACTED)"
}
