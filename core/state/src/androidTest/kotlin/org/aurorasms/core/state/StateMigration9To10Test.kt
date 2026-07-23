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
import org.aurorasms.core.state.storage.PermanentDeletionEnforcement
import org.aurorasms.core.state.storage.STATE_MIGRATION_9_10
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
class StateMigration9To10Test {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AuroraStateDatabase::class.java,
    )

    @Before fun clear() { context.deleteDatabase(DATABASE_NAME) }
    @After fun clean() { context.deleteDatabase(DATABASE_NAME) }

    @Test
    fun migrationAddsContentFreePermanentDeletionTableWithPhysicalLifecycleConstraints() {
        helper.createDatabase(DATABASE_NAME, 9).close()
        helper.runMigrationsAndValidate(DATABASE_NAME, 10, true, STATE_MIGRATION_9_10).use { db ->
            val columns = db.tableColumns("permanent_deletion_operations")
            assertEquals(
                setOf(
                    "deletion_id", "target_kind_code", "provider_thread_id", "provider_kind",
                    "provider_message_id", "sync_fingerprint", "sms_count", "latest_sms_id",
                    "mms_count", "latest_mms_id", "draft_id", "draft_revision_ms",
                    "due_timestamp_ms", "phase_code", "review_reason_code",
                    "armed_wall_timestamp_ms", "armed_elapsed_realtime_ms",
                    "created_timestamp_ms", "updated_timestamp_ms",
                ),
                columns,
            )
            assertFalse(columns.any { it in setOf("body", "subject", "recipient", "address", "label") })
            assertTrue(db.hasTrigger(PermanentDeletionEnforcement.INSERT_LIMIT_TRIGGER_NAME))
            assertTrue(db.hasTrigger(PermanentDeletionEnforcement.INSERT_INTEGRITY_TRIGGER_NAME))
            assertTrue(db.hasTrigger(PermanentDeletionEnforcement.UPDATE_INTEGRITY_TRIGGER_NAME))
            db.insertMessageDeletion()
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    "UPDATE permanent_deletion_operations SET phase_code='committing_v1', " +
                        "updated_timestamp_ms=10 WHERE deletion_id=1",
                )
            }
        }
    }

    private fun SupportSQLiteDatabase.insertMessageDeletion() {
        execSQL(
            """
            INSERT INTO permanent_deletion_operations(
              target_kind_code, provider_thread_id, provider_kind, provider_message_id,
              sync_fingerprint, sms_count, latest_sms_id, mms_count, latest_mms_id,
              draft_id, draft_revision_ms, due_timestamp_ms, phase_code, review_reason_code,
              armed_wall_timestamp_ms, armed_elapsed_realtime_ms,
              created_timestamp_ms, updated_timestamp_ms
            ) VALUES('message_v1', 2, 1, 3, '${"a".repeat(64)}', NULL, NULL, NULL, NULL,
              NULL, NULL, 5010, 'pending_v1', NULL, 10, 20, 10, 10)
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.tableColumns(table: String): Set<String> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
        }

    private fun SupportSQLiteDatabase.hasTrigger(name: String): Boolean = query(
        "SELECT 1 FROM sqlite_master WHERE type='trigger' AND name=?",
        arrayOf(name),
    ).use { it.moveToFirst() }

    companion object { private const val DATABASE_NAME = "aurora-state-migration-9-10-test.db" }
}
