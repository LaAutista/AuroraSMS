// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.db.SupportSQLiteDatabase
import org.aurorasms.core.state.MAXIMUM_SPAM_SAFETY_DECISIONS
import org.aurorasms.core.state.SpamParticipantSetKey

internal object SpamSafetyDecisionEnforcement {
    const val INSERT_LIMIT_TRIGGER_NAME = "spam_safety_decisions_enforce_limit_insert"
    const val INSERT_INTEGRITY_TRIGGER_NAME = "spam_safety_decisions_enforce_integrity_insert"
    const val UPDATE_INTEGRITY_TRIGGER_NAME = "spam_safety_decisions_enforce_integrity_update"

    const val CREATE_INSERT_LIMIT_TRIGGER =
        "CREATE TRIGGER IF NOT EXISTS $INSERT_LIMIT_TRIGGER_NAME " +
            "BEFORE INSERT ON $SPAM_SAFETY_DECISIONS_TABLE " +
            "WHEN (SELECT COUNT(*) FROM $SPAM_SAFETY_DECISIONS_TABLE) >= " +
            "$MAXIMUM_SPAM_SAFETY_DECISIONS " +
            "BEGIN SELECT RAISE(ABORT, 'spam-safety decision limit reached'); END"

    const val CREATE_INSERT_INTEGRITY_TRIGGER =
        "CREATE TRIGGER IF NOT EXISTS $INSERT_INTEGRITY_TRIGGER_NAME " +
            "BEFORE INSERT ON $SPAM_SAFETY_DECISIONS_TABLE WHEN " +
            "length(NEW.participant_set_key) != ${SpamParticipantSetKey.STORAGE_CHARACTERS} OR " +
            "substr(NEW.participant_set_key, 1, 10) != 'sha256-v1:' OR " +
            "substr(NEW.participant_set_key, 11) GLOB '*[^0-9a-f]*' OR " +
            "NEW.provider_thread_id <= 0 OR " +
            "NEW.classification_code NOT IN ('neutral_v1', 'spam_v1', 'not_spam_v1') OR " +
            "NEW.blocked NOT IN (0, 1) OR " +
            "(NEW.single_sender_key IS NOT NULL AND (" +
            "length(NEW.single_sender_key) != 74 OR " +
            "substr(NEW.single_sender_key, 1, 10) != 'sha256-v1:' OR " +
            "substr(NEW.single_sender_key, 11) GLOB '*[^0-9a-f]*')) OR " +
            "(NEW.blocked = 1 AND NEW.single_sender_key IS NULL) OR " +
            "(NEW.classification_code = 'neutral_v1' AND NEW.blocked = 0) OR " +
            "NEW.revision != 1 OR NEW.updated_timestamp_ms < 0 " +
            "BEGIN SELECT RAISE(ABORT, 'invalid spam-safety decision'); END"

    const val CREATE_UPDATE_INTEGRITY_TRIGGER =
        "CREATE TRIGGER IF NOT EXISTS $UPDATE_INTEGRITY_TRIGGER_NAME " +
            "BEFORE UPDATE ON $SPAM_SAFETY_DECISIONS_TABLE WHEN " +
            "NEW.participant_set_key != OLD.participant_set_key OR " +
            "NEW.provider_thread_id <= 0 OR " +
            "NEW.classification_code NOT IN ('neutral_v1', 'spam_v1', 'not_spam_v1') OR " +
            "NEW.blocked NOT IN (0, 1) OR " +
            "(NEW.single_sender_key IS NOT NULL AND (" +
            "length(NEW.single_sender_key) != 74 OR " +
            "substr(NEW.single_sender_key, 1, 10) != 'sha256-v1:' OR " +
            "substr(NEW.single_sender_key, 11) GLOB '*[^0-9a-f]*')) OR " +
            "(NEW.blocked = 1 AND NEW.single_sender_key IS NULL) OR " +
            "(NEW.classification_code = 'neutral_v1' AND NEW.blocked = 0) OR " +
            "NEW.revision != OLD.revision + 1 OR " +
            "NEW.updated_timestamp_ms <= OLD.updated_timestamp_ms " +
            "BEGIN SELECT RAISE(ABORT, 'invalid spam-safety transition'); END"

    val callback: RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) = install(db)
        override fun onCreate(connection: SQLiteConnection) = install(connection)
        override fun onOpen(db: SupportSQLiteDatabase) = install(db)
        override fun onOpen(connection: SQLiteConnection) = install(connection)
    }

    private fun install(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_INSERT_LIMIT_TRIGGER)
        db.execSQL(CREATE_INSERT_INTEGRITY_TRIGGER)
        db.execSQL(CREATE_UPDATE_INTEGRITY_TRIGGER)
    }

    private fun install(connection: SQLiteConnection) {
        connection.execSQL(CREATE_INSERT_LIMIT_TRIGGER)
        connection.execSQL(CREATE_INSERT_INTEGRITY_TRIGGER)
        connection.execSQL(CREATE_UPDATE_INTEGRITY_TRIGGER)
    }
}
