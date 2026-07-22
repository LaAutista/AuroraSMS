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
import org.aurorasms.core.state.storage.STATE_MIGRATION_14_15
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StateMigration14To15Test {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AuroraStateDatabase::class.java,
    )

    @Before fun clear() { context.deleteDatabase(DATABASE_NAME) }
    @After fun clean() { context.deleteDatabase(DATABASE_NAME) }

    @Test
    fun migrationPreservesVersionFourteenStateAndAddsEmptyEnforcedFirstContactOwner() {
        helper.createDatabase(DATABASE_NAME, 14).use { db ->
            db.execSQL(
                "INSERT INTO drafts(draft_id,provider_thread_id,participant_set_key,body,subject," +
                    "created_timestamp_ms,updated_timestamp_ms) " +
                    "VALUES(7,NULL,'+15550000100','Synthetic migration body',NULL,100,100)",
            )
            db.execSQL(
                "INSERT INTO draft_attachments" +
                    "(draft_id,attachment_index,content_type,content_bytes) " +
                    "VALUES(7,0,'image/jpeg',X'010203')",
            )
        }

        helper.runMigrationsAndValidate(DATABASE_NAME, 15, true, STATE_MIGRATION_14_15).use { db ->
            assertEquals(
                "Synthetic migration body",
                db.query("SELECT body FROM drafts WHERE draft_id=7").use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getString(0)
                },
            )
            assertEquals(
                3,
                db.query("SELECT length(content_bytes) FROM draft_attachments WHERE draft_id=7")
                    .use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) },
            )
            assertEquals(
                0,
                db.query("SELECT COUNT(*) FROM first_contact_operations").use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getInt(0)
                },
            )
            val columns = db.query("PRAGMA table_info(`first_contact_operations`)").use { cursor ->
                val name = cursor.getColumnIndexOrThrow("name")
                buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
            }
            assertFalse(columns.contains("body"))
            assertFalse(columns.contains("subject"))
            assertFalse(columns.contains("recipient"))

            val participantKey = FirstContactParticipantSetKey.fromParticipants(
                listOf(ParticipantAddress("+15550000100")),
            ).toStorageValue()
            val attachmentEvidence = FirstContactAttachmentSetEvidence.fromAttachments(
                listOf(
                    (DraftAttachment.create("image/jpeg", byteArrayOf(1, 2, 3)) as
                        DraftAttachment.CreationResult.Valid).attachment,
                ),
            ).toStorageValue()
            db.execSQL(
                "INSERT INTO first_contact_operations(" +
                    "participant_set_key,draft_id,source_draft_revision_ms," +
                    "attachment_set_evidence,subscription_id,transport_code,phase_code," +
                    "provider_thread_id,handoff_draft_revision_ms,created_timestamp_ms," +
                    "updated_timestamp_ms,signature_text) VALUES(" +
                    "'$participantKey',7,100,'$attachmentEvidence',0,'mms_v1','reserved_v1'," +
                    "NULL,NULL,200,200,NULL)",
            )
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    "UPDATE first_contact_operations SET subscription_id=1," +
                        "updated_timestamp_ms=201 WHERE first_contact_id=1",
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL("DELETE FROM first_contact_operations WHERE first_contact_id=1")
            }
            db.execSQL(
                "UPDATE first_contact_operations SET phase_code='known_unsent_v1'," +
                    "updated_timestamp_ms=201 WHERE first_contact_id=1",
            )
            db.execSQL("DELETE FROM first_contact_operations WHERE first_contact_id=1")
            assertEquals(
                0,
                db.query("SELECT COUNT(*) FROM first_contact_operations").use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getInt(0)
                },
            )
        }
    }

    @Test
    fun migrationInstallsTypeHardenedOneWayFirstContactTriggers() {
        helper.createDatabase(DATABASE_NAME, 14).close()

        helper.runMigrationsAndValidate(DATABASE_NAME, 15, true, STATE_MIGRATION_14_15).use { db ->
            val insertTrigger = db.query(
                "SELECT sql FROM sqlite_master WHERE type='trigger' AND name=?",
                arrayOf(FirstContactOperationEnforcement.INSERT_INTEGRITY_TRIGGER_NAME),
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }
            val updateTrigger = db.query(
                "SELECT sql FROM sqlite_master WHERE type='trigger' AND name=?",
                arrayOf(FirstContactOperationEnforcement.UPDATE_INTEGRITY_TRIGGER_NAME),
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }
            val integerColumns = listOf(
                "first_contact_id",
                "draft_id",
                "source_draft_revision_ms",
                "subscription_id",
                "provider_thread_id",
                "handoff_draft_revision_ms",
                "created_timestamp_ms",
                "updated_timestamp_ms",
            )
            integerColumns.forEach { column ->
                val clause = "typeof(NEW.$column) != 'integer'"
                assertTrue(insertTrigger.contains(clause))
                assertTrue(updateTrigger.contains(clause))
            }
            assertEquals(1, "'known_unsent_v1'".toRegex().findAll(updateTrigger).count())

            val participantKey = FirstContactParticipantSetKey.fromParticipants(
                listOf(ParticipantAddress("+15550000888")),
            ).toStorageValue()
            val attachmentEvidence = FirstContactAttachmentSetEvidence
                .fromAttachments(emptyList())
                .toStorageValue()
            val insertPrefix =
                "INSERT INTO first_contact_operations(" +
                    "participant_set_key,draft_id,source_draft_revision_ms," +
                    "attachment_set_evidence,subscription_id,transport_code,phase_code," +
                    "provider_thread_id,handoff_draft_revision_ms,created_timestamp_ms," +
                    "updated_timestamp_ms,signature_text) VALUES("
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    insertPrefix +
                        "'$participantKey','not-an-integer',100,'$attachmentEvidence',0," +
                        "'sms_v1','reserved_v1',NULL,NULL,200,200,NULL)",
                )
            }
            db.execSQL(
                insertPrefix +
                    "'$participantKey',7,100,'$attachmentEvidence',0,'sms_v1'," +
                    "'reserved_v1',NULL,NULL,200,200,NULL)",
            )
            val operationId = db.query(
                "SELECT first_contact_id FROM first_contact_operations",
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            }
            db.execSQL(
                "UPDATE first_contact_operations SET phase_code='resolution_started_v1'," +
                    "updated_timestamp_ms=201 WHERE first_contact_id=$operationId",
            )
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    "UPDATE first_contact_operations SET phase_code='known_unsent_v1'," +
                        "updated_timestamp_ms=202 WHERE first_contact_id=$operationId",
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    "UPDATE first_contact_operations SET phase_code='thread_bound_v1'," +
                        "provider_thread_id='not-an-integer',updated_timestamp_ms=202 " +
                        "WHERE first_contact_id=$operationId",
                )
            }
            db.execSQL(
                "UPDATE first_contact_operations SET phase_code='thread_bound_v1'," +
                    "provider_thread_id=77,updated_timestamp_ms=202 " +
                    "WHERE first_contact_id=$operationId",
            )
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    "UPDATE first_contact_operations SET phase_code='handoff_reserved_v1'," +
                        "handoff_draft_revision_ms='not-an-integer',updated_timestamp_ms=203 " +
                        "WHERE first_contact_id=$operationId",
                )
            }
            db.query(
                "SELECT phase_code,provider_thread_id,handoff_draft_revision_ms," +
                    "updated_timestamp_ms FROM first_contact_operations " +
                    "WHERE first_contact_id=$operationId",
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("thread_bound_v1", cursor.getString(0))
                assertEquals(77L, cursor.getLong(1))
                assertTrue(cursor.isNull(2))
                assertEquals(202L, cursor.getLong(3))
            }
        }
    }

    companion object {
        private const val DATABASE_NAME = "aurora-state-migration-14-15-test.db"
    }
}
