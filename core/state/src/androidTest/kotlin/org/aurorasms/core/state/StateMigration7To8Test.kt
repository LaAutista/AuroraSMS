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
import org.aurorasms.core.state.storage.STATE_MIGRATION_7_8
import org.aurorasms.core.state.storage.ScheduledSmsEnforcement
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
class StateMigration7To8Test {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AuroraStateDatabase::class.java,
    )

    @Before fun clear() { context.deleteDatabase(DATABASE_NAME) }
    @After fun clean() { context.deleteDatabase(DATABASE_NAME) }

    @Test
    fun migrationAddsContentFreeScheduleTableWithPhysicalLifecycleConstraints() {
        helper.createDatabase(DATABASE_NAME, 7).close()
        helper.runMigrationsAndValidate(DATABASE_NAME, 8, true, STATE_MIGRATION_7_8).use { db ->
            val columns = db.tableColumns("scheduled_sms_operations")
            assertEquals(
                setOf(
                    "schedule_id", "participant_set_key", "provider_thread_id", "draft_id",
                    "draft_revision_ms", "subscription_id", "due_timestamp_ms", "phase_code",
                    "precision_code", "review_reason_code", "armed_wall_timestamp_ms",
                    "armed_elapsed_realtime_ms", "created_timestamp_ms", "updated_timestamp_ms",
                ),
                columns,
            )
            assertFalse(columns.any { it in setOf("body", "subject", "recipient", "address") })
            assertTrue(db.hasTrigger(ScheduledSmsEnforcement.INSERT_LIMIT_TRIGGER))
            assertTrue(db.hasTrigger(ScheduledSmsEnforcement.INSERT_INTEGRITY_TRIGGER))
            assertTrue(db.hasTrigger(ScheduledSmsEnforcement.UPDATE_INTEGRITY_TRIGGER))
            db.insertSchedule()
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    "UPDATE scheduled_sms_operations SET phase_code='dispatching_v1', " +
                        "updated_timestamp_ms=10 WHERE schedule_id=1",
                )
            }
        }
    }

    private fun SupportSQLiteDatabase.insertSchedule() {
        execSQL(
            """
            INSERT INTO scheduled_sms_operations(
              participant_set_key, provider_thread_id, draft_id, draft_revision_ms,
              subscription_id, due_timestamp_ms, phase_code, precision_code,
              review_reason_code, armed_wall_timestamp_ms, armed_elapsed_realtime_ms,
              created_timestamp_ms, updated_timestamp_ms
            ) VALUES('sha256-v1:${"a".repeat(64)}', 2, 3, 4, 1, 1000,
              'pending_v1', 'inexact_v1', NULL, 10, 20, 10, 10)
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

    companion object { private const val DATABASE_NAME = "aurora-state-migration-7-8-test.db" }
}
