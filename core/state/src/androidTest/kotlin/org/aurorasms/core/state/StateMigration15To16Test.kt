// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.state.storage.AuroraStateDatabase
import org.aurorasms.core.state.storage.FirstContactOperationEnforcement
import org.aurorasms.core.state.storage.STATE_MIGRATION_15_16
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StateMigration15To16Test {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AuroraStateDatabase::class.java,
    )

    @Before fun clear() { context.deleteDatabase(DATABASE_NAME) }
    @After fun clean() { context.deleteDatabase(DATABASE_NAME) }

    @Test
    fun migrationAllowsOnlyAnExactReservedComposerToConsumeHandoffOwnership() {
        val participantKey = FirstContactParticipantSetKey.fromParticipants(
            listOf(ParticipantAddress("+15550000077")),
        ).toStorageValue()
        val attachmentEvidence = FirstContactAttachmentSetEvidence.fromAttachments(emptyList())
            .toStorageValue()
        helper.createDatabase(DATABASE_NAME, 15).use { db ->
            FirstContactOperationEnforcement.installV15(db)
            db.execSQL(
                "INSERT INTO drafts(draft_id,provider_thread_id,participant_set_key,body,subject," +
                    "created_timestamp_ms,updated_timestamp_ms) " +
                    "VALUES(7,77,NULL,'Synthetic handoff',NULL,100,101)",
            )
            db.execSQL(
                "INSERT INTO first_contact_operations(participant_set_key,draft_id," +
                    "source_draft_revision_ms,attachment_set_evidence,subscription_id," +
                    "transport_code,phase_code,provider_thread_id,handoff_draft_revision_ms," +
                    "created_timestamp_ms,updated_timestamp_ms,signature_text) VALUES(" +
                    "'$participantKey',7,100,'$attachmentEvidence',0,'sms_v1','reserved_v1'," +
                    "NULL,NULL,200,200,NULL)",
            )
            db.execSQL(
                "UPDATE first_contact_operations SET phase_code='resolution_started_v1'," +
                    "updated_timestamp_ms=201 WHERE first_contact_id=1",
            )
            db.execSQL(
                "UPDATE first_contact_operations SET phase_code='thread_bound_v1'," +
                    "provider_thread_id=77,updated_timestamp_ms=202 WHERE first_contact_id=1",
            )
            db.execSQL(
                "UPDATE first_contact_operations SET phase_code='handoff_reserved_v1'," +
                    "handoff_draft_revision_ms=101,updated_timestamp_ms=203 " +
                    "WHERE first_contact_id=1",
            )
        }

        helper.runMigrationsAndValidate(DATABASE_NAME, 16, true, STATE_MIGRATION_15_16).use { db ->
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL("DELETE FROM first_contact_operations WHERE first_contact_id=1")
            }
            db.execSQL(
                "INSERT INTO composer_sms_operations(provider_thread_id,draft_id,draft_revision_ms," +
                    "subscription_id,transport_code,phase_code,provider_message_id," +
                    "provider_conversation_id,unit_count,created_timestamp_ms," +
                    "updated_timestamp_ms,signature_text) VALUES(" +
                    "77,7,101,1,'sms_v1','reserved_v1',NULL,NULL,NULL,300,300,NULL)",
            )
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL("DELETE FROM first_contact_operations WHERE first_contact_id=1")
            }
            db.execSQL("DELETE FROM composer_sms_operations")
            db.execSQL(
                "INSERT INTO composer_sms_operations(provider_thread_id,draft_id,draft_revision_ms," +
                    "subscription_id,transport_code,phase_code,provider_message_id," +
                    "provider_conversation_id,unit_count,created_timestamp_ms," +
                    "updated_timestamp_ms,signature_text) VALUES(" +
                    "77,7,101,0,'sms_v1','reserved_v1',NULL,NULL,NULL,300,300,NULL)",
            )
            db.execSQL("DELETE FROM first_contact_operations WHERE first_contact_id=1")

            assertEquals(0, db.count("first_contact_operations"))
            assertEquals(1, db.count("composer_sms_operations"))
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.count(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    companion object {
        private const val DATABASE_NAME: String = "aurora-state-migration-15-16-test.db"
    }
}
