// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.db.SupportSQLiteDatabase
import org.aurorasms.core.state.MAXIMUM_SEND_DELAY_MILLIS
import org.aurorasms.core.state.MAXIMUM_SEND_DELAY_OPERATIONS
import org.aurorasms.core.state.MINIMUM_SEND_DELAY_MILLIS
import org.aurorasms.core.state.SendDelayParticipantSetKey

/** Physical enforcement for the bounded, content-free short send-delay table. */
internal object SendDelayEnforcement {
    const val INSERT_LIMIT_TRIGGER_NAME = "send_delay_operations_enforce_limit_insert"
    const val INSERT_INTEGRITY_TRIGGER_NAME = "send_delay_operations_enforce_integrity_insert"
    const val UPDATE_INTEGRITY_TRIGGER_NAME = "send_delay_operations_enforce_integrity_update"

    const val CREATE_INSERT_LIMIT_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $INSERT_LIMIT_TRIGGER_NAME " +
            "BEFORE INSERT ON $SEND_DELAY_OPERATIONS_TABLE " +
            "WHEN (SELECT COUNT(*) FROM $SEND_DELAY_OPERATIONS_TABLE) >= " +
            "$MAXIMUM_SEND_DELAY_OPERATIONS " +
            "BEGIN SELECT RAISE(ABORT, 'send-delay operation limit reached'); END"

    const val CREATE_INSERT_INTEGRITY_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $INSERT_INTEGRITY_TRIGGER_NAME " +
            "BEFORE INSERT ON $SEND_DELAY_OPERATIONS_TABLE WHEN " +
            "length(NEW.participant_set_key) != ${SendDelayParticipantSetKey.STORAGE_CHARACTERS} OR " +
            "substr(NEW.participant_set_key, 1, 10) != 'sha256-v1:' OR " +
            "substr(NEW.participant_set_key, 11) GLOB '*[^0-9a-f]*' OR " +
            "NEW.provider_thread_id <= 0 OR NEW.draft_id <= 0 OR " +
            "NEW.draft_revision_ms < 0 OR NEW.subscription_id < 0 OR " +
            "NEW.due_timestamp_ms - NEW.created_timestamp_ms < $MINIMUM_SEND_DELAY_MILLIS OR " +
            "NEW.due_timestamp_ms - NEW.created_timestamp_ms > $MAXIMUM_SEND_DELAY_MILLIS OR " +
            "NEW.phase_code != 'pending_v1' OR NEW.review_reason_code IS NOT NULL OR " +
            "NEW.armed_wall_timestamp_ms != NEW.created_timestamp_ms OR " +
            "NEW.armed_elapsed_realtime_ms < 0 OR NEW.created_timestamp_ms < 0 OR " +
            "NEW.updated_timestamp_ms != NEW.created_timestamp_ms " +
            "BEGIN SELECT RAISE(ABORT, 'invalid send-delay reservation'); END"

    const val CREATE_UPDATE_INTEGRITY_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $UPDATE_INTEGRITY_TRIGGER_NAME " +
            "BEFORE UPDATE ON $SEND_DELAY_OPERATIONS_TABLE WHEN " +
            "NEW.send_delay_id != OLD.send_delay_id OR " +
            "NEW.participant_set_key != OLD.participant_set_key OR " +
            "NEW.provider_thread_id != OLD.provider_thread_id OR " +
            "NEW.draft_id != OLD.draft_id OR NEW.draft_revision_ms != OLD.draft_revision_ms OR " +
            "NEW.subscription_id != OLD.subscription_id OR " +
            "NEW.due_timestamp_ms != OLD.due_timestamp_ms OR " +
            "NEW.armed_wall_timestamp_ms != OLD.armed_wall_timestamp_ms OR " +
            "NEW.armed_elapsed_realtime_ms != OLD.armed_elapsed_realtime_ms OR " +
            "NEW.created_timestamp_ms != OLD.created_timestamp_ms OR " +
            "NEW.updated_timestamp_ms <= OLD.updated_timestamp_ms OR " +
            "NOT (" +
            "(OLD.phase_code = 'pending_v1' AND NEW.phase_code = 'dispatching_v1' AND " +
            "NEW.review_reason_code IS NULL) OR " +
            "(OLD.phase_code IN ('pending_v1', 'dispatching_v1') AND " +
            "NEW.phase_code = 'review_required_v1' AND NEW.review_reason_code IN (" +
            "'clock_changed_v1', 'missed_after_restart_v1', 'precondition_failed_v1', " +
            "'arming_failed_v1', 'interrupted_before_reservation_v1'))" +
            ") BEGIN SELECT RAISE(ABORT, 'invalid send-delay transition'); END"

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
