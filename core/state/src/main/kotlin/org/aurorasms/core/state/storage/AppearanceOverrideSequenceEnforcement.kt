// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.db.SupportSQLiteDatabase

/** Seeds and physically protects the one-row monotonic override-revision allocator. */
internal object AppearanceOverrideSequenceEnforcement {
    const val INSERT_TRIGGER_NAME: String =
        "appearance_override_revision_sequence_require_singleton_insert"
    const val UPDATE_TRIGGER_NAME: String =
        "appearance_override_revision_sequence_require_singleton_update"
    const val DELETE_TRIGGER_NAME: String =
        "appearance_override_revision_sequence_reject_delete"

    private const val DROP_INSERT_TRIGGER: String =
        "DROP TRIGGER IF EXISTS $INSERT_TRIGGER_NAME"
    private const val DROP_UPDATE_TRIGGER: String =
        "DROP TRIGGER IF EXISTS $UPDATE_TRIGGER_NAME"
    private const val DROP_DELETE_TRIGGER: String =
        "DROP TRIGGER IF EXISTS $DELETE_TRIGGER_NAME"

    const val CREATE_INSERT_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $INSERT_TRIGGER_NAME " +
            "BEFORE INSERT ON appearance_override_revision_sequence " +
            "WHEN NEW.singleton_id != $APPEARANCE_OVERRIDE_SEQUENCE_SINGLETON_ID " +
            "OR NEW.last_allocated_revision != $INITIAL_APPEARANCE_OVERRIDE_SEQUENCE " +
            "BEGIN SELECT RAISE(ABORT, 'invalid appearance override sequence singleton'); END"

    const val CREATE_UPDATE_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $UPDATE_TRIGGER_NAME " +
            "BEFORE UPDATE ON appearance_override_revision_sequence " +
            "WHEN NEW.singleton_id != $APPEARANCE_OVERRIDE_SEQUENCE_SINGLETON_ID " +
            "OR NEW.last_allocated_revision != OLD.last_allocated_revision + 1 " +
            "BEGIN SELECT RAISE(ABORT, 'invalid appearance override sequence advance'); END"

    const val CREATE_DELETE_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $DELETE_TRIGGER_NAME " +
            "BEFORE DELETE ON appearance_override_revision_sequence " +
            "BEGIN SELECT RAISE(ABORT, 'appearance override sequence cannot be deleted'); END"

    const val INSERT_INITIAL_SEQUENCE: String =
        "INSERT OR IGNORE INTO appearance_override_revision_sequence(" +
            "singleton_id, last_allocated_revision" +
            ") VALUES($APPEARANCE_OVERRIDE_SEQUENCE_SINGLETON_ID, " +
            "$INITIAL_APPEARANCE_OVERRIDE_SEQUENCE)"

    val callback: RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) = installAndSeed(db)

        override fun onCreate(connection: SQLiteConnection) = installAndSeed(connection)

        override fun onOpen(db: SupportSQLiteDatabase) = installTriggers(db)

        override fun onOpen(connection: SQLiteConnection) = installTriggers(connection)
    }

    private fun installAndSeed(db: SupportSQLiteDatabase) {
        installTriggers(db)
        db.execSQL(INSERT_INITIAL_SEQUENCE)
    }

    private fun installTriggers(db: SupportSQLiteDatabase) {
        db.execSQL(DROP_INSERT_TRIGGER)
        db.execSQL(DROP_UPDATE_TRIGGER)
        db.execSQL(DROP_DELETE_TRIGGER)
        db.execSQL(CREATE_INSERT_TRIGGER)
        db.execSQL(CREATE_UPDATE_TRIGGER)
        db.execSQL(CREATE_DELETE_TRIGGER)
    }

    private fun installAndSeed(connection: SQLiteConnection) {
        installTriggers(connection)
        connection.execSQL(INSERT_INITIAL_SEQUENCE)
    }

    private fun installTriggers(connection: SQLiteConnection) {
        connection.execSQL(DROP_INSERT_TRIGGER)
        connection.execSQL(DROP_UPDATE_TRIGGER)
        connection.execSQL(DROP_DELETE_TRIGGER)
        connection.execSQL(CREATE_INSERT_TRIGGER)
        connection.execSQL(CREATE_UPDATE_TRIGGER)
        connection.execSQL(CREATE_DELETE_TRIGGER)
    }
}
