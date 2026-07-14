// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "appearance_override_revision_sequence")
internal data class AppearanceOverrideSequenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int,
    @ColumnInfo(name = "last_allocated_revision")
    val lastAllocatedRevision: Long,
) {
    init {
        require(singletonId == APPEARANCE_OVERRIDE_SEQUENCE_SINGLETON_ID) {
            "Invalid appearance override sequence singleton"
        }
        require(lastAllocatedRevision >= 0L) {
            "An appearance override sequence cannot be negative"
        }
    }

    override fun toString(): String = "AppearanceOverrideSequenceEntity(REDACTED)"
}

internal const val APPEARANCE_OVERRIDE_SEQUENCE_SINGLETON_ID: Int = 1
internal const val INITIAL_APPEARANCE_OVERRIDE_SEQUENCE: Long = 0L
