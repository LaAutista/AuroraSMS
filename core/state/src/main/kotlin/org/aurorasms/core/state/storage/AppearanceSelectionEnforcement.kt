// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.db.SupportSQLiteDatabase

/** Seeds and physically protects the one-row active-profile selection. */
internal object AppearanceSelectionEnforcement {
    const val INSERT_TRIGGER_NAME: String = "appearance_selection_require_singleton_insert"
    const val UPDATE_TRIGGER_NAME: String = "appearance_selection_require_singleton_update"

    const val CREATE_INSERT_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $INSERT_TRIGGER_NAME " +
            "BEFORE INSERT ON appearance_selection " +
            "WHEN NEW.singleton_id != $APPEARANCE_SELECTION_SINGLETON_ID " +
            "BEGIN SELECT RAISE(ABORT, 'invalid appearance selection singleton'); END"

    const val CREATE_UPDATE_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $UPDATE_TRIGGER_NAME " +
            "BEFORE UPDATE OF singleton_id ON appearance_selection " +
            "WHEN NEW.singleton_id != $APPEARANCE_SELECTION_SINGLETON_ID " +
            "BEGIN SELECT RAISE(ABORT, 'invalid appearance selection singleton'); END"

    const val INSERT_DEFAULT_SELECTION: String =
        "INSERT OR IGNORE INTO appearance_selection(" +
            "singleton_id, active_profile_id, snapshot_revision" +
            ") VALUES($APPEARANCE_SELECTION_SINGLETON_ID, NULL, " +
            "$INITIAL_APPEARANCE_SNAPSHOT_REVISION)"

    val callback: RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) = installAndSeed(db)

        override fun onCreate(connection: SQLiteConnection) = installAndSeed(connection)

        override fun onOpen(db: SupportSQLiteDatabase) = installTriggers(db)

        override fun onOpen(connection: SQLiteConnection) = installTriggers(connection)
    }

    private fun installAndSeed(db: SupportSQLiteDatabase) {
        installTriggers(db)
        db.execSQL(INSERT_DEFAULT_SELECTION)
    }

    private fun installTriggers(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_INSERT_TRIGGER)
        db.execSQL(CREATE_UPDATE_TRIGGER)
    }

    private fun installAndSeed(connection: SQLiteConnection) {
        installTriggers(connection)
        connection.execSQL(INSERT_DEFAULT_SELECTION)
    }

    private fun installTriggers(connection: SQLiteConnection) {
        connection.execSQL(CREATE_INSERT_TRIGGER)
        connection.execSQL(CREATE_UPDATE_TRIGGER)
    }
}
