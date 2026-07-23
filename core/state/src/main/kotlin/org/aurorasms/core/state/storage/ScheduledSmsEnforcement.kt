// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.db.SupportSQLiteDatabase
import org.aurorasms.core.state.MAXIMUM_SCHEDULED_SMS_OPERATIONS
import org.aurorasms.core.state.MessageSignature
import org.aurorasms.core.state.ScheduledSmsParticipantSetKey

internal object ScheduledSmsEnforcement {
    const val INSERT_LIMIT_TRIGGER = "scheduled_sms_operations_enforce_limit_insert"
    const val INSERT_INTEGRITY_TRIGGER = "scheduled_sms_operations_enforce_integrity_insert"
    const val UPDATE_INTEGRITY_TRIGGER = "scheduled_sms_operations_enforce_integrity_update"

    private const val SIGNATURE_INSERT_CONDITION: String =
        "(NEW.signature_text IS NOT NULL AND (length(NEW.signature_text) < 1 OR " +
            "length(NEW.signature_text) > ${MessageSignature.MAX_CHARACTERS})) OR "
    private const val SIGNATURE_UPDATE_CONDITION: String =
        "NEW.signature_text IS NOT OLD.signature_text OR "

    const val CREATE_INSERT_LIMIT_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $INSERT_LIMIT_TRIGGER BEFORE INSERT ON " +
            "$SCHEDULED_SMS_OPERATIONS_TABLE WHEN (SELECT COUNT(*) FROM " +
            "$SCHEDULED_SMS_OPERATIONS_TABLE) >= $MAXIMUM_SCHEDULED_SMS_OPERATIONS " +
            "BEGIN SELECT RAISE(ABORT, 'scheduled SMS operation limit exceeded'); END"

    const val CREATE_INSERT_INTEGRITY_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $INSERT_INTEGRITY_TRIGGER BEFORE INSERT ON " +
            "$SCHEDULED_SMS_OPERATIONS_TABLE WHEN " +
            "length(NEW.participant_set_key) != ${ScheduledSmsParticipantSetKey.STORAGE_CHARACTERS} OR " +
            "substr(NEW.participant_set_key, 1, 10) != 'sha256-v1:' OR " +
            "substr(NEW.participant_set_key, 11) GLOB '*[^0-9a-f]*' OR " +
            "NEW.provider_thread_id <= 0 OR NEW.draft_id <= 0 OR NEW.draft_revision_ms < 0 OR " +
            "NEW.subscription_id < 0 OR NEW.due_timestamp_ms <= NEW.created_timestamp_ms OR " +
            "NEW.phase_code != 'pending_v1' OR NEW.precision_code != 'inexact_v1' OR " +
            "NEW.review_reason_code IS NOT NULL OR NEW.armed_wall_timestamp_ms < 0 OR " +
            SIGNATURE_INSERT_CONDITION +
            "NEW.armed_elapsed_realtime_ms < 0 OR NEW.created_timestamp_ms < 0 OR " +
            "NEW.updated_timestamp_ms != NEW.created_timestamp_ms " +
            "BEGIN SELECT RAISE(ABORT, 'invalid scheduled SMS operation'); END"

    const val CREATE_UPDATE_INTEGRITY_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $UPDATE_INTEGRITY_TRIGGER BEFORE UPDATE ON " +
            "$SCHEDULED_SMS_OPERATIONS_TABLE WHEN " +
            "NEW.schedule_id != OLD.schedule_id OR NEW.participant_set_key != OLD.participant_set_key OR " +
            "NEW.provider_thread_id != OLD.provider_thread_id OR NEW.draft_id != OLD.draft_id OR " +
            "NEW.draft_revision_ms != OLD.draft_revision_ms OR NEW.subscription_id != OLD.subscription_id OR " +
            SIGNATURE_UPDATE_CONDITION +
            "NEW.due_timestamp_ms != OLD.due_timestamp_ms OR NEW.created_timestamp_ms != OLD.created_timestamp_ms OR " +
            "NEW.updated_timestamp_ms <= OLD.updated_timestamp_ms OR " +
            "NEW.precision_code NOT IN ('exact_v1','inexact_v1') OR NEW.armed_wall_timestamp_ms < 0 OR " +
            "NEW.armed_elapsed_realtime_ms < 0 OR " +
            "(NEW.phase_code = 'pending_v1' AND NEW.review_reason_code IS NOT NULL) OR " +
            "(NEW.phase_code = 'dispatching_v1' AND (OLD.phase_code != 'pending_v1' OR NEW.review_reason_code IS NOT NULL)) OR " +
            "(NEW.phase_code = 'review_required_v1' AND (OLD.phase_code NOT IN ('pending_v1','dispatching_v1') OR NEW.review_reason_code IS NULL)) OR " +
            "NEW.phase_code NOT IN ('pending_v1','dispatching_v1','review_required_v1') " +
            "BEGIN SELECT RAISE(ABORT, 'invalid scheduled SMS transition'); END"

    /** The version-8 table predates the optional frozen signature column. */
    val CREATE_INSERT_INTEGRITY_TRIGGER_V8: String =
        CREATE_INSERT_INTEGRITY_TRIGGER.replace(SIGNATURE_INSERT_CONDITION, "")

    /** The version-8 table predates the optional frozen signature column. */
    val CREATE_UPDATE_INTEGRITY_TRIGGER_V8: String =
        CREATE_UPDATE_INTEGRITY_TRIGGER.replace(SIGNATURE_UPDATE_CONDITION, "")

    val callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) = install(db)
        override fun onOpen(db: SupportSQLiteDatabase) = install(db)
        override fun onCreate(connection: SQLiteConnection) = install(connection)
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
