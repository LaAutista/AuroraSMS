// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.db.SupportSQLiteDatabase
import org.aurorasms.core.state.FirstContactAttachmentSetEvidence
import org.aurorasms.core.state.FirstContactParticipantSetKey
import org.aurorasms.core.state.MAXIMUM_FIRST_CONTACT_OPERATIONS
import org.aurorasms.core.state.MessageSignature

/** Physical enforcement for bounded, content-free first-contact ownership. */
internal object FirstContactOperationEnforcement {
    const val INSERT_LIMIT_TRIGGER_NAME: String =
        "first_contact_operations_enforce_limit_insert"
    const val INSERT_INTEGRITY_TRIGGER_NAME: String =
        "first_contact_operations_enforce_integrity_insert"
    const val UPDATE_INTEGRITY_TRIGGER_NAME: String =
        "first_contact_operations_enforce_integrity_update"
    const val DELETE_INTEGRITY_TRIGGER_NAME: String =
        "first_contact_operations_enforce_integrity_delete"

    private const val NEW_INTEGER_TYPE_VIOLATION: String =
        "typeof(NEW.first_contact_id) != 'integer' OR " +
            "typeof(NEW.draft_id) != 'integer' OR " +
            "typeof(NEW.source_draft_revision_ms) != 'integer' OR " +
            "typeof(NEW.subscription_id) != 'integer' OR " +
            "(NEW.provider_thread_id IS NOT NULL AND " +
            "typeof(NEW.provider_thread_id) != 'integer') OR " +
            "(NEW.handoff_draft_revision_ms IS NOT NULL AND " +
            "typeof(NEW.handoff_draft_revision_ms) != 'integer') OR " +
            "typeof(NEW.created_timestamp_ms) != 'integer' OR " +
            "typeof(NEW.updated_timestamp_ms) != 'integer'"

    const val CREATE_INSERT_LIMIT_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $INSERT_LIMIT_TRIGGER_NAME " +
            "BEFORE INSERT ON $FIRST_CONTACT_OPERATIONS_TABLE " +
            "WHEN (SELECT COUNT(*) FROM $FIRST_CONTACT_OPERATIONS_TABLE) >= " +
            "$MAXIMUM_FIRST_CONTACT_OPERATIONS " +
            "BEGIN SELECT RAISE(ABORT, 'first-contact operation limit reached'); END"

    const val CREATE_INSERT_INTEGRITY_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $INSERT_INTEGRITY_TRIGGER_NAME " +
            "BEFORE INSERT ON $FIRST_CONTACT_OPERATIONS_TABLE WHEN " +
            "$NEW_INTEGER_TYPE_VIOLATION OR " +
            "length(NEW.participant_set_key) != " +
            "${FirstContactParticipantSetKey.STORAGE_CHARACTERS} OR " +
            "substr(NEW.participant_set_key, 1, 10) != 'sha256-v1:' OR " +
            "substr(NEW.participant_set_key, 11) GLOB '*[^0-9a-f]*' OR " +
            "length(NEW.attachment_set_evidence) != " +
            "${FirstContactAttachmentSetEvidence.STORAGE_CHARACTERS} OR " +
            "substr(NEW.attachment_set_evidence, 1, 10) != 'sha256-v1:' OR " +
            "substr(NEW.attachment_set_evidence, 11) GLOB '*[^0-9a-f]*' OR " +
            "NEW.draft_id <= 0 OR NEW.source_draft_revision_ms < 0 OR " +
            "NEW.subscription_id < 0 OR NEW.transport_code NOT IN ('sms_v1','mms_v1') OR " +
            "NEW.phase_code != 'reserved_v1' OR NEW.provider_thread_id IS NOT NULL OR " +
            "NEW.handoff_draft_revision_ms IS NOT NULL OR " +
            "(NEW.signature_text IS NOT NULL AND (length(NEW.signature_text) < 1 OR " +
            "length(NEW.signature_text) > ${MessageSignature.MAX_CHARACTERS})) OR " +
            "NEW.created_timestamp_ms < 0 OR NEW.created_timestamp_ms >= ${Long.MAX_VALUE} OR " +
            "NEW.updated_timestamp_ms != NEW.created_timestamp_ms " +
            "BEGIN SELECT RAISE(ABORT, 'invalid first-contact reservation'); END"

    val CREATE_UPDATE_INTEGRITY_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $UPDATE_INTEGRITY_TRIGGER_NAME " +
            "BEFORE UPDATE ON $FIRST_CONTACT_OPERATIONS_TABLE WHEN " +
            "$NEW_INTEGER_TYPE_VIOLATION OR " +
            "NEW.first_contact_id != OLD.first_contact_id OR " +
            "NEW.participant_set_key != OLD.participant_set_key OR " +
            "NEW.draft_id != OLD.draft_id OR " +
            "NEW.source_draft_revision_ms != OLD.source_draft_revision_ms OR " +
            "NEW.attachment_set_evidence != OLD.attachment_set_evidence OR " +
            "NEW.subscription_id != OLD.subscription_id OR " +
            "NEW.transport_code != OLD.transport_code OR " +
            "NEW.signature_text IS NOT OLD.signature_text OR " +
            "NEW.created_timestamp_ms != OLD.created_timestamp_ms OR " +
            "NEW.updated_timestamp_ms <= OLD.updated_timestamp_ms OR " +
            "NEW.updated_timestamp_ms >= ${Long.MAX_VALUE} OR NOT (" +
            "(OLD.phase_code = 'reserved_v1' AND " +
            " NEW.phase_code = 'resolution_started_v1' AND " +
            unchangedNullBinding() + ") OR " +
            "(OLD.phase_code = 'reserved_v1' AND NEW.phase_code = 'known_unsent_v1' AND " +
            unchangedNullBinding() + ") OR " +
            "(OLD.phase_code = 'resolution_started_v1' AND " +
            " NEW.phase_code = 'thread_bound_v1' AND " +
            " OLD.provider_thread_id IS NULL AND NEW.provider_thread_id > 0 AND " +
            " OLD.handoff_draft_revision_ms IS NULL AND " +
            " NEW.handoff_draft_revision_ms IS NULL) OR " +
            "(OLD.phase_code = 'resolution_started_v1' AND " +
            " NEW.phase_code = 'resolution_unknown_v1' AND " +
            unchangedNullBinding() + ") OR " +
            "(OLD.phase_code = 'thread_bound_v1' AND " +
            " NEW.phase_code = 'handoff_reserved_v1' AND " +
            " NEW.provider_thread_id = OLD.provider_thread_id AND " +
            " NEW.provider_thread_id > 0 AND OLD.handoff_draft_revision_ms IS NULL AND " +
            " NEW.handoff_draft_revision_ms > NEW.source_draft_revision_ms)" +
            ") BEGIN SELECT RAISE(ABORT, 'invalid first-contact transition'); END"

    const val CREATE_DELETE_INTEGRITY_TRIGGER: String =
        "CREATE TRIGGER IF NOT EXISTS $DELETE_INTEGRITY_TRIGGER_NAME " +
            "BEFORE DELETE ON $FIRST_CONTACT_OPERATIONS_TABLE " +
            "WHEN OLD.phase_code != 'known_unsent_v1' AND NOT (" +
            "OLD.phase_code = 'handoff_reserved_v1' AND " +
            "EXISTS(SELECT 1 FROM $COMPOSER_SMS_OPERATIONS_TABLE AS composer WHERE " +
            "composer.provider_thread_id = OLD.provider_thread_id AND " +
            "composer.draft_id = OLD.draft_id AND " +
            "composer.draft_revision_ms = OLD.handoff_draft_revision_ms AND " +
            "composer.subscription_id = OLD.subscription_id AND " +
            "composer.transport_code = OLD.transport_code AND " +
            "composer.phase_code = 'reserved_v1' AND " +
            "composer.provider_message_id IS NULL AND " +
            "composer.provider_conversation_id IS NULL AND composer.unit_count IS NULL AND " +
            "composer.signature_text IS OLD.signature_text)) " +
            "BEGIN SELECT RAISE(ABORT, 'first-contact owner is not releasable'); END"

    const val CREATE_DELETE_INTEGRITY_TRIGGER_V15: String =
        "CREATE TRIGGER IF NOT EXISTS $DELETE_INTEGRITY_TRIGGER_NAME " +
            "BEFORE DELETE ON $FIRST_CONTACT_OPERATIONS_TABLE " +
            "WHEN OLD.phase_code != 'known_unsent_v1' " +
            "BEGIN SELECT RAISE(ABORT, 'first-contact owner is not releasable'); END"

    val callback: RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) = install(db)
        override fun onOpen(db: SupportSQLiteDatabase) = install(db)
        override fun onCreate(connection: SQLiteConnection) = install(connection)
        override fun onOpen(connection: SQLiteConnection) = install(connection)
    }

    fun install(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_INSERT_LIMIT_TRIGGER)
        db.execSQL(CREATE_INSERT_INTEGRITY_TRIGGER)
        db.execSQL(CREATE_UPDATE_INTEGRITY_TRIGGER)
        db.execSQL(CREATE_DELETE_INTEGRITY_TRIGGER)
    }

    fun installV15(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_INSERT_LIMIT_TRIGGER)
        db.execSQL(CREATE_INSERT_INTEGRITY_TRIGGER)
        db.execSQL(CREATE_UPDATE_INTEGRITY_TRIGGER)
        db.execSQL(CREATE_DELETE_INTEGRITY_TRIGGER_V15)
    }

    private fun install(connection: SQLiteConnection) {
        connection.execSQL(CREATE_INSERT_LIMIT_TRIGGER)
        connection.execSQL(CREATE_INSERT_INTEGRITY_TRIGGER)
        connection.execSQL(CREATE_UPDATE_INTEGRITY_TRIGGER)
        connection.execSQL(CREATE_DELETE_INTEGRITY_TRIGGER)
    }

    private fun unchangedNullBinding(): String =
        "OLD.provider_thread_id IS NULL AND NEW.provider_thread_id IS NULL AND " +
            "OLD.handoff_draft_revision_ms IS NULL AND " +
            "NEW.handoff_draft_revision_ms IS NULL"
}
