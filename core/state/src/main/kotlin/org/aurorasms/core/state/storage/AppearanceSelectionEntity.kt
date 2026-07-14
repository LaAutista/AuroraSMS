// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "appearance_selection",
    foreignKeys = [
        ForeignKey(
            entity = AppearanceProfileEntity::class,
            parentColumns = ["profile_id"],
            childColumns = ["active_profile_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["active_profile_id"])],
)
internal data class AppearanceSelectionEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int,
    @ColumnInfo(name = "active_profile_id")
    val activeProfileId: Long?,
    @ColumnInfo(name = "snapshot_revision")
    val snapshotRevision: Long,
) {
    override fun toString(): String = "AppearanceSelectionEntity(REDACTED)"
}

internal const val APPEARANCE_SELECTION_SINGLETON_ID: Int = 1
internal const val INITIAL_APPEARANCE_SNAPSHOT_REVISION: Long = 1L
