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
import org.aurorasms.core.state.storage.STATE_MIGRATION_4_5
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
class StateMigration4To5Test {
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
    fun migrationPreservesDraftAndAddsBoundedContentFreeJournalWithPhysicalInvariants() {
        migrationHelper.createDatabase(DATABASE_NAME, 4).use { database ->
            database.execSQL(
                """
                INSERT INTO drafts(
                    draft_id, provider_thread_id, participant_set_key, body, subject,
                    created_timestamp_ms, updated_timestamp_ms
                ) VALUES(7, 42, NULL, 'synthetic migration body', NULL, 10, 20)
                """.trimIndent(),
            )
        }

        migrationHelper.runMigrationsAndValidate(
            DATABASE_NAME,
            5,
            true,
            STATE_MIGRATION_4_5,
        ).use { database ->
            assertEquals("synthetic migration body", database.queryString("SELECT body FROM drafts"))
            assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM composer_sms_operations"))
            assertEquals(
                setOf(
                    "local_operation_id",
                    "provider_thread_id",
                    "draft_id",
                    "draft_revision_ms",
                    "subscription_id",
                    "phase_code",
                    "provider_message_id",
                    "provider_conversation_id",
                    "unit_count",
                    "created_timestamp_ms",
                    "updated_timestamp_ms",
                ),
                database.tableColumns("composer_sms_operations"),
            )
            assertTrue(database.hasIndex("index_composer_sms_operations_provider_thread_id"))
            assertTrue(database.hasIndex("index_composer_sms_operations_provider_message_id"))
            assertTrue(database.hasIndex("index_composer_sms_operations_draft_id_draft_revision_ms"))
            assertTrue(
                database.hasIndex(
                    "index_composer_sms_operations_updated_timestamp_ms_local_operation_id",
                ),
            )
            assertTrue(database.hasTrigger(ComposerSmsOperationEnforcement.INSERT_LIMIT_TRIGGER_NAME))
            assertTrue(database.hasTrigger(ComposerSmsOperationEnforcement.INSERT_INTEGRITY_TRIGGER_NAME))
            assertTrue(database.hasTrigger(ComposerSmsOperationEnforcement.UPDATE_INTEGRITY_TRIGGER_NAME))
            assertFalse(database.query("PRAGMA foreign_key_list(composer_sms_operations)").use { it.moveToFirst() })

            assertThrows(SQLiteConstraintException::class.java) {
                database.insertReserved(threadId = 1L, draftId = 7L, phase = "submitting_v1")
            }
            database.insertReserved(threadId = 1L, draftId = 7L)
            for (index in 2L..MAXIMUM_COMPOSER_SMS_OPERATIONS.toLong()) {
                database.insertReserved(threadId = index, draftId = 10_000L + index)
            }
            assertEquals(
                MAXIMUM_COMPOSER_SMS_OPERATIONS.toLong(),
                database.queryLong("SELECT COUNT(*) FROM composer_sms_operations"),
            )
            assertThrows(SQLiteConstraintException::class.java) {
                database.insertReserved(threadId = 1_000L, draftId = 11_000L)
            }
        }
    }

    @Test
    fun updateTriggerPermitsExactLateFailedCallbackAndRejectsTerminalSources() {
        migrationHelper.createDatabase(DATABASE_NAME, 4).close()

        migrationHelper.runMigrationsAndValidate(
            DATABASE_NAME,
            5,
            true,
            STATE_MIGRATION_4_5,
        ).use { database ->
            database.insertReserved(threadId = 81L, draftId = 8_081L)
            val lateFailureId = database.queryLong(
                "SELECT local_operation_id FROM composer_sms_operations WHERE provider_thread_id = 81",
            )
            database.advanceToSubmissionUnknown(
                localOperationId = lateFailureId,
                providerMessageId = 901L,
                providerConversationId = 9_901L,
            )

            assertThrows(SQLiteConstraintException::class.java) {
                database.execSQL(
                    """
                    UPDATE composer_sms_operations
                    SET phase_code = 'known_unsent_v1',
                        provider_message_id = 999,
                        updated_timestamp_ms = 35
                    WHERE local_operation_id = $lateFailureId
                        AND phase_code = 'submission_unknown_v1'
                    """.trimIndent(),
                )
            }
            database.transitionBound(
                localOperationId = lateFailureId,
                sourcePhase = "submission_unknown_v1",
                targetPhase = "known_unsent_v1",
                updatedTimestampMillis = 35L,
            )
            assertEquals(
                "known_unsent_v1",
                database.queryString(
                    "SELECT phase_code FROM composer_sms_operations " +
                        "WHERE local_operation_id = $lateFailureId",
                ),
            )
            assertThrows(SQLiteConstraintException::class.java) {
                database.transitionBound(
                    localOperationId = lateFailureId,
                    sourcePhase = "known_unsent_v1",
                    targetPhase = "known_unsent_v1",
                    updatedTimestampMillis = 36L,
                )
            }

            database.insertReserved(threadId = 82L, draftId = 8_082L)
            val successfulCallbackId = database.queryLong(
                "SELECT local_operation_id FROM composer_sms_operations WHERE provider_thread_id = 82",
            )
            database.advanceToSubmissionUnknown(
                localOperationId = successfulCallbackId,
                providerMessageId = 902L,
                providerConversationId = 9_902L,
            )
            database.transitionBound(
                localOperationId = successfulCallbackId,
                sourcePhase = "submission_unknown_v1",
                targetPhase = "sent_callback_succeeded_v1",
                updatedTimestampMillis = 35L,
            )

            assertThrows(SQLiteConstraintException::class.java) {
                database.transitionBound(
                    localOperationId = successfulCallbackId,
                    sourcePhase = "sent_callback_succeeded_v1",
                    targetPhase = "known_unsent_v1",
                    updatedTimestampMillis = 36L,
                )
            }
            assertEquals(
                "sent_callback_succeeded_v1",
                database.queryString(
                    "SELECT phase_code FROM composer_sms_operations " +
                        "WHERE local_operation_id = $successfulCallbackId",
                ),
            )
        }
    }

    private fun SupportSQLiteDatabase.insertReserved(
        threadId: Long,
        draftId: Long,
        phase: String = "reserved_v1",
    ) {
        execSQL(
            """
            INSERT INTO composer_sms_operations(
                provider_thread_id, draft_id, draft_revision_ms, subscription_id, phase_code,
                provider_message_id, provider_conversation_id, unit_count,
                created_timestamp_ms, updated_timestamp_ms
            ) VALUES($threadId, $draftId, 20, 1, '$phase', NULL, NULL, NULL, 30, 30)
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.advanceToSubmissionUnknown(
        localOperationId: Long,
        providerMessageId: Long,
        providerConversationId: Long,
    ) {
        execSQL(
            """
            UPDATE composer_sms_operations
            SET phase_code = 'prepared_v1',
                provider_message_id = $providerMessageId,
                provider_conversation_id = $providerConversationId,
                unit_count = 1,
                updated_timestamp_ms = 31
            WHERE local_operation_id = $localOperationId AND phase_code = 'reserved_v1'
            """.trimIndent(),
        )
        transitionBound(localOperationId, "prepared_v1", "submitting_v1", 32L)
        transitionBound(localOperationId, "submitting_v1", "platform_accepted_v1", 33L)
        transitionBound(localOperationId, "platform_accepted_v1", "submission_unknown_v1", 34L)
    }

    private fun SupportSQLiteDatabase.transitionBound(
        localOperationId: Long,
        sourcePhase: String,
        targetPhase: String,
        updatedTimestampMillis: Long,
    ) {
        execSQL(
            """
            UPDATE composer_sms_operations
            SET phase_code = '$targetPhase', updated_timestamp_ms = $updatedTimestampMillis
            WHERE local_operation_id = $localOperationId AND phase_code = '$sourcePhase'
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

    private fun SupportSQLiteDatabase.queryString(sql: String): String = query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getString(0)
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
        private const val DATABASE_NAME: String = "aurora-state-migration-4-5-test.db"
    }
}
