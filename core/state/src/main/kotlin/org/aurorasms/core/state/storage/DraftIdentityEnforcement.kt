// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.db.SupportSQLiteDatabase
import org.aurorasms.core.state.DraftParticipantSetKey

/** Physical SQLite enforcement for invariants Room entity annotations cannot express. */
internal object DraftIdentityEnforcement {
    const val INSERT_TRIGGER_NAME: String = "drafts_require_exactly_one_identity_insert"
    const val UPDATE_TRIGGER_NAME: String = "drafts_require_exactly_one_identity_update"

    val callback: RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            install(db)
        }

        override fun onCreate(connection: SQLiteConnection) {
            install(connection)
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            install(db)
        }

        override fun onOpen(connection: SQLiteConnection) {
            install(connection)
        }
    }

    private fun install(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_INSERT_TRIGGER)
        db.execSQL(CREATE_UPDATE_TRIGGER)
    }

    private fun install(connection: SQLiteConnection) {
        connection.execSQL(CREATE_INSERT_TRIGGER)
        connection.execSQL(CREATE_UPDATE_TRIGGER)
    }

    private const val INVALID_IDENTITY: String =
        "((NEW.provider_thread_id IS NULL) = (NEW.participant_set_key IS NULL)) " +
            "OR (NEW.provider_thread_id IS NOT NULL AND NEW.provider_thread_id <= 0) " +
            "OR (NEW.participant_set_key IS NOT NULL AND " +
            "(length(NEW.participant_set_key) = 0 OR " +
            "length(NEW.participant_set_key) > " +
            "${DraftParticipantSetKey.MAX_STORAGE_CHARACTERS}))"

    const val CREATE_INSERT_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $INSERT_TRIGGER_NAME " +
            "BEFORE INSERT ON drafts WHEN $INVALID_IDENTITY " +
            "BEGIN SELECT RAISE(ABORT, 'invalid draft identity'); END"

    const val CREATE_UPDATE_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $UPDATE_TRIGGER_NAME " +
            "BEFORE UPDATE OF provider_thread_id, participant_set_key ON drafts " +
            "WHEN $INVALID_IDENTITY " +
            "BEGIN SELECT RAISE(ABORT, 'invalid draft identity'); END"
}
