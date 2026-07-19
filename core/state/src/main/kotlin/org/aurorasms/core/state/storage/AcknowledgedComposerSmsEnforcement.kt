// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.db.SupportSQLiteDatabase
import org.aurorasms.core.state.MAXIMUM_ACKNOWLEDGED_COMPOSER_SMS_RECEIPTS
import org.aurorasms.core.state.MAXIMUM_COMPOSER_SMS_UNIT_COUNT

/** Physical enforcement for bounded, content-free late composer callback receipts. */
internal object AcknowledgedComposerSmsEnforcement {
    const val INSERT_LIMIT_TRIGGER_NAME: String =
        "acknowledged_composer_sms_receipts_enforce_limit_insert"
    const val INSERT_INTEGRITY_TRIGGER_NAME: String =
        "acknowledged_composer_sms_receipts_enforce_integrity_insert"
    const val UPDATE_INTEGRITY_TRIGGER_NAME: String =
        "acknowledged_composer_sms_receipts_enforce_integrity_update"

    const val CREATE_INSERT_LIMIT_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $INSERT_LIMIT_TRIGGER_NAME " +
            "BEFORE INSERT ON $ACKNOWLEDGED_COMPOSER_SMS_RECEIPTS_TABLE " +
            "WHEN (SELECT COUNT(*) FROM $ACKNOWLEDGED_COMPOSER_SMS_RECEIPTS_TABLE) >= " +
            "$MAXIMUM_ACKNOWLEDGED_COMPOSER_SMS_RECEIPTS " +
            "BEGIN SELECT RAISE(ABORT, 'acknowledged composer SMS receipt limit reached'); END"

    const val CREATE_INSERT_INTEGRITY_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $INSERT_INTEGRITY_TRIGGER_NAME " +
            "BEFORE INSERT ON $ACKNOWLEDGED_COMPOSER_SMS_RECEIPTS_TABLE WHEN " +
            "NEW.local_operation_id <= 0 OR " +
            "NEW.provider_message_id <= 0 OR NEW.provider_conversation_id <= 0 OR " +
            "NEW.unit_count < 1 OR NEW.unit_count > $MAXIMUM_COMPOSER_SMS_UNIT_COUNT OR " +
            "NEW.callback_proof_code != 'awaiting_callback_v1' OR " +
            "NEW.acknowledged_timestamp_ms < 0 OR " +
            "NEW.updated_timestamp_ms != NEW.acknowledged_timestamp_ms " +
            "BEGIN SELECT RAISE(ABORT, 'invalid acknowledged composer SMS receipt'); END"

    const val CREATE_UPDATE_INTEGRITY_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $UPDATE_INTEGRITY_TRIGGER_NAME " +
            "BEFORE UPDATE ON $ACKNOWLEDGED_COMPOSER_SMS_RECEIPTS_TABLE WHEN " +
            "NEW.local_operation_id != OLD.local_operation_id OR " +
            "NEW.provider_message_id != OLD.provider_message_id OR " +
            "NEW.provider_conversation_id != OLD.provider_conversation_id OR " +
            "NEW.unit_count != OLD.unit_count OR " +
            "NEW.acknowledged_timestamp_ms != OLD.acknowledged_timestamp_ms OR " +
            "NEW.updated_timestamp_ms <= OLD.updated_timestamp_ms OR " +
            "OLD.callback_proof_code != 'awaiting_callback_v1' OR " +
            "NEW.callback_proof_code NOT IN ('sent_v1', 'failed_v1') " +
            "BEGIN SELECT RAISE(ABORT, 'invalid acknowledged composer SMS transition'); END"

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
