// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.db.SupportSQLiteDatabase
import org.aurorasms.core.state.ConversationSubscriptionParticipantSetKey

/** Physical constraints for content-free, participant-hash keyed SIM preferences. */
internal object ConversationSubscriptionPreferenceEnforcement {
    const val INSERT_INTEGRITY_TRIGGER_NAME: String =
        "conversation_subscription_preferences_enforce_integrity_insert"
    const val UPDATE_INTEGRITY_TRIGGER_NAME: String =
        "conversation_subscription_preferences_enforce_integrity_update"

    const val CREATE_INSERT_INTEGRITY_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $INSERT_INTEGRITY_TRIGGER_NAME " +
            "BEFORE INSERT ON $CONVERSATION_SUBSCRIPTION_PREFERENCES_TABLE WHEN " +
            "length(NEW.participant_set_key) != " +
            "${ConversationSubscriptionParticipantSetKey.STORAGE_CHARACTERS} OR " +
            "substr(NEW.participant_set_key, 1, 10) != 'sha256-v1:' OR " +
            "substr(NEW.participant_set_key, 11) GLOB '*[^0-9a-f]*' OR " +
            "NEW.provider_thread_id <= 0 OR NEW.subscription_id < 0 OR " +
            "NEW.revision != 1 OR NEW.updated_timestamp_ms < 0 " +
            "BEGIN SELECT RAISE(ABORT, 'invalid conversation subscription preference'); END"

    const val CREATE_UPDATE_INTEGRITY_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $UPDATE_INTEGRITY_TRIGGER_NAME " +
            "BEFORE UPDATE ON $CONVERSATION_SUBSCRIPTION_PREFERENCES_TABLE WHEN " +
            "NEW.participant_set_key != OLD.participant_set_key OR " +
            "NEW.provider_thread_id <= 0 OR NEW.subscription_id < 0 OR " +
            "NEW.revision != OLD.revision + 1 OR " +
            "NEW.updated_timestamp_ms <= OLD.updated_timestamp_ms " +
            "BEGIN SELECT RAISE(ABORT, 'invalid conversation subscription transition'); END"

    val callback: RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) = install(db)

        override fun onCreate(connection: SQLiteConnection) = install(connection)

        override fun onOpen(db: SupportSQLiteDatabase) = install(db)

        override fun onOpen(connection: SQLiteConnection) = install(connection)
    }

    private fun install(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_INSERT_INTEGRITY_TRIGGER)
        db.execSQL(CREATE_UPDATE_INTEGRITY_TRIGGER)
    }

    private fun install(connection: SQLiteConnection) {
        connection.execSQL(CREATE_INSERT_INTEGRITY_TRIGGER)
        connection.execSQL(CREATE_UPDATE_INTEGRITY_TRIGGER)
    }
}
