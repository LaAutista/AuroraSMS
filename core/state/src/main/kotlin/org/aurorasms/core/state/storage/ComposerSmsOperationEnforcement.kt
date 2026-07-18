// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.db.SupportSQLiteDatabase
import org.aurorasms.core.state.MAXIMUM_COMPOSER_SMS_OPERATIONS
import org.aurorasms.core.state.MAXIMUM_COMPOSER_SMS_UNIT_COUNT

/** Physical enforcement for the bounded content-free composer operation table. */
internal object ComposerSmsOperationEnforcement {
    const val INSERT_LIMIT_TRIGGER_NAME: String = "composer_sms_operations_enforce_limit_insert"
    const val INSERT_INTEGRITY_TRIGGER_NAME: String = "composer_sms_operations_enforce_integrity_insert"
    const val UPDATE_INTEGRITY_TRIGGER_NAME: String = "composer_sms_operations_enforce_integrity_update"

    const val CREATE_INSERT_LIMIT_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $INSERT_LIMIT_TRIGGER_NAME " +
            "BEFORE INSERT ON $COMPOSER_SMS_OPERATIONS_TABLE " +
            "WHEN (SELECT COUNT(*) FROM $COMPOSER_SMS_OPERATIONS_TABLE) >= " +
            "$MAXIMUM_COMPOSER_SMS_OPERATIONS " +
            "BEGIN SELECT RAISE(ABORT, 'composer SMS operation limit reached'); END"

    const val CREATE_INSERT_INTEGRITY_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $INSERT_INTEGRITY_TRIGGER_NAME " +
            "BEFORE INSERT ON $COMPOSER_SMS_OPERATIONS_TABLE WHEN " +
            "NEW.provider_thread_id <= 0 OR NEW.draft_id <= 0 OR " +
            "NEW.draft_revision_ms < 0 OR NEW.subscription_id < 0 OR " +
            "NEW.phase_code != 'reserved_v1' OR " +
            "NEW.provider_message_id IS NOT NULL OR " +
            "NEW.provider_conversation_id IS NOT NULL OR NEW.unit_count IS NOT NULL OR " +
            "NEW.created_timestamp_ms < 0 OR " +
            "NEW.updated_timestamp_ms != NEW.created_timestamp_ms " +
            "BEGIN SELECT RAISE(ABORT, 'invalid composer SMS reservation'); END"

    const val CREATE_UPDATE_INTEGRITY_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $UPDATE_INTEGRITY_TRIGGER_NAME " +
            "BEFORE UPDATE ON $COMPOSER_SMS_OPERATIONS_TABLE WHEN " +
            "NEW.local_operation_id != OLD.local_operation_id OR " +
            "NEW.provider_thread_id != OLD.provider_thread_id OR " +
            "NEW.draft_id != OLD.draft_id OR " +
            "NEW.draft_revision_ms != OLD.draft_revision_ms OR " +
            "NEW.subscription_id != OLD.subscription_id OR " +
            "NEW.created_timestamp_ms != OLD.created_timestamp_ms OR " +
            "NEW.updated_timestamp_ms <= OLD.updated_timestamp_ms OR " +
            "(NEW.provider_message_id IS NULL) != (NEW.provider_conversation_id IS NULL) OR " +
            "(NEW.provider_message_id IS NULL) != (NEW.unit_count IS NULL) OR " +
            "(NEW.provider_message_id IS NOT NULL AND NEW.provider_message_id <= 0) OR " +
            "(NEW.provider_conversation_id IS NOT NULL AND NEW.provider_conversation_id <= 0) OR " +
            "(NEW.unit_count IS NOT NULL AND " +
            "(NEW.unit_count < 1 OR NEW.unit_count > $MAXIMUM_COMPOSER_SMS_UNIT_COUNT)) OR " +
            "NOT (" +
            "(OLD.phase_code = 'reserved_v1' AND NEW.phase_code = 'prepared_v1' AND " +
            "OLD.provider_message_id IS NULL AND NEW.provider_message_id IS NOT NULL) OR " +
            "(OLD.phase_code = 'reserved_v1' AND NEW.phase_code = 'known_unsent_v1' AND " +
            "NEW.provider_message_id IS NULL) OR " +
            "(OLD.phase_code = 'prepared_v1' AND " +
            "NEW.phase_code IN ('submitting_v1', 'known_unsent_v1') AND " +
            "NEW.provider_message_id = OLD.provider_message_id AND " +
            "NEW.provider_conversation_id = OLD.provider_conversation_id AND " +
            "NEW.unit_count = OLD.unit_count) OR " +
            "(OLD.phase_code = 'submitting_v1' AND " +
            "NEW.phase_code IN (" +
            "'platform_accepted_v1', 'sent_callback_succeeded_v1', " +
            "'submission_unknown_v1', 'known_unsent_v1') AND " +
            "NEW.provider_message_id = OLD.provider_message_id AND " +
            "NEW.provider_conversation_id = OLD.provider_conversation_id AND " +
            "NEW.unit_count = OLD.unit_count) OR " +
            "(OLD.phase_code = 'platform_accepted_v1' AND " +
            "NEW.phase_code IN ('sent_callback_succeeded_v1', 'known_unsent_v1') AND " +
            "NEW.provider_message_id = OLD.provider_message_id AND " +
            "NEW.provider_conversation_id = OLD.provider_conversation_id AND " +
            "NEW.unit_count = OLD.unit_count) OR " +
            "(OLD.phase_code = 'submission_unknown_v1' AND " +
            "NEW.phase_code IN ('sent_callback_succeeded_v1', 'known_unsent_v1') AND " +
            "NEW.provider_message_id = OLD.provider_message_id AND " +
            "NEW.provider_conversation_id = OLD.provider_conversation_id AND " +
            "NEW.unit_count = OLD.unit_count) OR " +
            "(OLD.phase_code = 'platform_accepted_v1' AND " +
            "NEW.phase_code = 'submission_unknown_v1' AND " +
            "NEW.provider_message_id = OLD.provider_message_id AND " +
            "NEW.provider_conversation_id = OLD.provider_conversation_id AND " +
            "NEW.unit_count = OLD.unit_count)" +
            ") BEGIN SELECT RAISE(ABORT, 'invalid composer SMS transition'); END"

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
