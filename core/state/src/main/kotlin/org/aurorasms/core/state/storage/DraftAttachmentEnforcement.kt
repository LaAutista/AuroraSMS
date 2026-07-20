// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

internal object DraftAttachmentEnforcement {
    const val INSERT_TRIGGER_NAME: String = "draft_attachments_enforce_insert"
    const val UPDATE_TRIGGER_NAME: String = "draft_attachments_enforce_update"

    val CREATE_INSERT_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $INSERT_TRIGGER_NAME BEFORE INSERT ON draft_attachments " +
            "WHEN ${invalidRow("NEW", excludeExisting = false)} " +
            "BEGIN SELECT RAISE(ABORT, 'invalid draft attachment'); END"

    val CREATE_UPDATE_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $UPDATE_TRIGGER_NAME BEFORE UPDATE ON draft_attachments " +
            "WHEN ${invalidRow("NEW", excludeExisting = true)} " +
            "BEGIN SELECT RAISE(ABORT, 'invalid draft attachment'); END"

    val callback: RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) = install(db)
        override fun onOpen(db: SupportSQLiteDatabase) = install(db)
    }

    fun install(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_INSERT_TRIGGER)
        db.execSQL(CREATE_UPDATE_TRIGGER)
    }

    private fun invalidRow(alias: String, excludeExisting: Boolean): String {
        val exclusion = if (excludeExisting) {
            " AND NOT (draft_id = OLD.draft_id AND attachment_index = OLD.attachment_index)"
        } else {
            ""
        }
        return "$alias.attachment_index < 0 OR $alias.attachment_index >= 10 OR " +
            "$alias.content_type NOT IN ('image/jpeg','image/png') OR " +
            "length($alias.content_bytes) < 1 OR length($alias.content_bytes) > 786432 OR " +
            "(SELECT COUNT(*) FROM draft_attachments WHERE draft_id = $alias.draft_id$exclusion) >= 10 OR " +
            "(SELECT COALESCE(SUM(length(content_bytes)),0) FROM draft_attachments " +
            "WHERE draft_id = $alias.draft_id$exclusion) + length($alias.content_bytes) > 917504"
    }
}
