// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aurorasms.core.state.storage.AcknowledgedComposerSmsEnforcement
import org.aurorasms.core.state.storage.AuroraStateDatabase
import org.aurorasms.core.state.storage.STATE_MIGRATION_5_6
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
class StateMigration5To6Test {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AuroraStateDatabase::class.java,
    )

    @Before
    fun clearDatabase() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun cleanDatabase() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun migrationAddsBoundedContentFreeReceiptsWithPhysicalInvariants() {
        migrationHelper.createDatabase(DATABASE_NAME, 5).close()

        migrationHelper.runMigrationsAndValidate(
            DATABASE_NAME,
            6,
            true,
            STATE_MIGRATION_5_6,
        ).use { database ->
            assertEquals(
                setOf(
                    "local_operation_id",
                    "provider_message_id",
                    "provider_conversation_id",
                    "unit_count",
                    "callback_proof_code",
                    "acknowledged_timestamp_ms",
                    "updated_timestamp_ms",
                ),
                database.tableColumns("acknowledged_composer_sms_receipts"),
            )
            assertFalse(
                database.tableColumns("acknowledged_composer_sms_receipts")
                    .any { it in setOf("body", "subject", "recipient", "digest") },
            )
            assertTrue(
                database.hasIndex("index_acknowledged_composer_sms_receipts_provider_message_id"),
            )
            assertTrue(
                database.hasIndex(
                    "index_acknowledged_composer_sms_receipts_updated_timestamp_ms_local_operation_id",
                ),
            )
            assertTrue(database.hasTrigger(AcknowledgedComposerSmsEnforcement.INSERT_LIMIT_TRIGGER_NAME))
            assertTrue(
                database.hasTrigger(AcknowledgedComposerSmsEnforcement.INSERT_INTEGRITY_TRIGGER_NAME),
            )
            assertTrue(
                database.hasTrigger(AcknowledgedComposerSmsEnforcement.UPDATE_INTEGRITY_TRIGGER_NAME),
            )

            assertThrows(SQLiteConstraintException::class.java) {
                database.insertReceipt(localOperationId = 1L, callbackProof = "sent_v1")
            }
            database.insertReceipt(localOperationId = 1L)
            database.execSQL(
                """
                UPDATE acknowledged_composer_sms_receipts
                SET callback_proof_code = 'sent_v1', updated_timestamp_ms = 41
                WHERE local_operation_id = 1
                """.trimIndent(),
            )
            assertThrows(SQLiteConstraintException::class.java) {
                database.execSQL(
                    """
                    UPDATE acknowledged_composer_sms_receipts
                    SET callback_proof_code = 'failed_v1', updated_timestamp_ms = 42
                    WHERE local_operation_id = 1
                    """.trimIndent(),
                )
            }
            for (index in 2L..MAXIMUM_ACKNOWLEDGED_COMPOSER_SMS_RECEIPTS.toLong()) {
                database.insertReceipt(localOperationId = index)
            }
            assertEquals(
                MAXIMUM_ACKNOWLEDGED_COMPOSER_SMS_RECEIPTS.toLong(),
                database.queryLong("SELECT COUNT(*) FROM acknowledged_composer_sms_receipts"),
            )
            assertThrows(SQLiteConstraintException::class.java) {
                database.insertReceipt(
                    localOperationId = MAXIMUM_ACKNOWLEDGED_COMPOSER_SMS_RECEIPTS + 1L,
                )
            }
        }
    }

    private fun SupportSQLiteDatabase.insertReceipt(
        localOperationId: Long,
        callbackProof: String = "awaiting_callback_v1",
    ) {
        execSQL(
            """
            INSERT INTO acknowledged_composer_sms_receipts(
                local_operation_id, provider_message_id, provider_conversation_id, unit_count,
                callback_proof_code, acknowledged_timestamp_ms, updated_timestamp_ms
            ) VALUES(
                $localOperationId, ${1_000L + localOperationId}, ${2_000L + localOperationId}, 1,
                '$callbackProof', 40, 40
            )
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.tableColumns(table: String): Set<String> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameColumn))
            }
        }

    private fun SupportSQLiteDatabase.queryLong(sql: String): Long = query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun SupportSQLiteDatabase.hasIndex(name: String): Boolean = query(
        "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?",
        arrayOf(name),
    ).use { it.moveToFirst() }

    private fun SupportSQLiteDatabase.hasTrigger(name: String): Boolean = query(
        "SELECT 1 FROM sqlite_master WHERE type = 'trigger' AND name = ?",
        arrayOf(name),
    ).use { it.moveToFirst() }

    companion object {
        private const val DATABASE_NAME: String = "aurora-state-migration-5-6-test.db"
    }
}
