// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aurorasms.core.state.storage.AuroraStateDatabase
import org.aurorasms.core.state.storage.ComposerSmsOperationEnforcement
import org.aurorasms.core.state.storage.STATE_MIGRATION_10_11
import org.aurorasms.core.state.storage.ScheduledSmsEnforcement
import org.aurorasms.core.state.storage.SendDelayEnforcement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StateMigration10To11Test {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AuroraStateDatabase::class.java,
    )

    @Before fun clear() { context.deleteDatabase(DATABASE_NAME) }
    @After fun clean() { context.deleteDatabase(DATABASE_NAME) }

    @Test
    fun migrationAddsNullableFrozenSignaturesAndReinstallsImmutableBounds() {
        helper.createDatabase(DATABASE_NAME, 10).use { db ->
            db.insertLegacyComposer()
            db.insertLegacySchedule()
            db.insertLegacyDelay()
        }
        helper.runMigrationsAndValidate(DATABASE_NAME, 11, true, STATE_MIGRATION_10_11).use { db ->
            listOf(
                "composer_sms_operations",
                "scheduled_sms_operations",
                "send_delay_operations",
            ).forEach { table ->
                assertEquals(true, "signature_text" in db.tableColumns(table))
                db.query("SELECT signature_text FROM `$table` LIMIT 1").use { cursor ->
                    cursor.moveToFirst()
                    assertNull(cursor.getString(0))
                }
            }
            listOf(
                ComposerSmsOperationEnforcement.INSERT_INTEGRITY_TRIGGER_NAME,
                ComposerSmsOperationEnforcement.UPDATE_INTEGRITY_TRIGGER_NAME,
                ScheduledSmsEnforcement.INSERT_INTEGRITY_TRIGGER,
                ScheduledSmsEnforcement.UPDATE_INTEGRITY_TRIGGER,
                SendDelayEnforcement.INSERT_INTEGRITY_TRIGGER_NAME,
                SendDelayEnforcement.UPDATE_INTEGRITY_TRIGGER_NAME,
            ).forEach { trigger -> assertEquals(true, db.hasTrigger(trigger)) }

            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    "UPDATE composer_sms_operations SET signature_text='changed', " +
                        "phase_code='known_unsent_v1', updated_timestamp_ms=11 " +
                    "WHERE local_operation_id=1",
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    "UPDATE scheduled_sms_operations SET signature_text='changed', " +
                        "phase_code='dispatching_v1', updated_timestamp_ms=11 " +
                        "WHERE schedule_id=1",
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    "UPDATE send_delay_operations SET signature_text='changed', " +
                        "phase_code='dispatching_v1', updated_timestamp_ms=11 " +
                        "WHERE send_delay_id=1",
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    "INSERT INTO composer_sms_operations(provider_thread_id,draft_id," +
                        "draft_revision_ms,subscription_id,phase_code,provider_message_id," +
                        "provider_conversation_id,unit_count,created_timestamp_ms," +
                        "updated_timestamp_ms,signature_text) VALUES(9,9,1,1,'reserved_v1'," +
                        "NULL,NULL,NULL,20,20,'${"x".repeat(MessageSignature.MAX_CHARACTERS + 1)}')",
                )
            }
        }
    }

    private fun SupportSQLiteDatabase.insertLegacyComposer() = execSQL(
        "INSERT INTO composer_sms_operations(provider_thread_id,draft_id,draft_revision_ms," +
            "subscription_id,phase_code,provider_message_id,provider_conversation_id,unit_count," +
            "created_timestamp_ms,updated_timestamp_ms) " +
            "VALUES(1,1,1,1,'reserved_v1',NULL,NULL,NULL,10,10)",
    )

    private fun SupportSQLiteDatabase.insertLegacySchedule() = execSQL(
        "INSERT INTO scheduled_sms_operations(participant_set_key,provider_thread_id,draft_id," +
            "draft_revision_ms,subscription_id,due_timestamp_ms,phase_code,precision_code," +
            "review_reason_code,armed_wall_timestamp_ms,armed_elapsed_realtime_ms," +
            "created_timestamp_ms,updated_timestamp_ms) VALUES('${"sha256-v1:" + "a".repeat(64)}'," +
            "2,2,1,1,1000,'pending_v1','inexact_v1',NULL,10,20,10,10)",
    )

    private fun SupportSQLiteDatabase.insertLegacyDelay() = execSQL(
        "INSERT INTO send_delay_operations(participant_set_key,provider_thread_id,draft_id," +
            "draft_revision_ms,subscription_id,due_timestamp_ms,phase_code,review_reason_code," +
            "armed_wall_timestamp_ms,armed_elapsed_realtime_ms,created_timestamp_ms," +
            "updated_timestamp_ms) VALUES('${"sha256-v1:" + "b".repeat(64)}'," +
            "3,3,1,1,1010,'pending_v1',NULL,10,20,10,10)",
    )

    private fun SupportSQLiteDatabase.tableColumns(table: String): Set<String> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
        }

    private fun SupportSQLiteDatabase.hasTrigger(name: String): Boolean = query(
        "SELECT 1 FROM sqlite_master WHERE type='trigger' AND name=?",
        arrayOf(name),
    ).use { it.moveToFirst() }

    companion object { private const val DATABASE_NAME = "aurora-state-migration-10-11-test.db" }
}
