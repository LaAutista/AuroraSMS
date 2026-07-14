// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.index.conversation.ConversationLookupResult
import org.aurorasms.core.index.conversation.RoomConversationRepository
import org.aurorasms.core.index.storage.AuroraIndexDatabase
import org.aurorasms.core.index.storage.GenerationStateCode
import org.aurorasms.core.index.storage.INDEX_MIGRATION_2_3
import org.aurorasms.core.model.ProviderThreadId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndexMigration2To3Test {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AuroraIndexDatabase::class.java,
    )

    @Test
    fun completeVersionTwoConversationCannotRemainTrustedAfterUpgrade() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DATABASE_NAME)
        try {
            migrationHelper.createDatabase(DATABASE_NAME, 2).use { database ->
                database.insertCompleteVersionTwoProjection()
            }

            migrationHelper.runMigrationsAndValidate(
                DATABASE_NAME,
                3,
                true,
                INDEX_MIGRATION_2_3,
            ).use { database ->
                assertEquals(3, database.version)
                assertEquals(
                    GenerationStateCode.PAUSED.toLong(),
                    database.longValue("SELECT state FROM index_generations WHERE generation_id = 1"),
                )
                assertEquals(
                    1L,
                    database.longValue("SELECT pending_changes FROM index_generations WHERE generation_id = 1"),
                )
                assertEquals(
                    8L,
                    database.longValue("SELECT signal_sequence FROM index_generations WHERE generation_id = 1"),
                )
                assertEquals(0L, database.longValue("SELECT COUNT(completed_at_ms) FROM index_generations"))
                assertEquals(0L, database.longValue("SELECT COUNT(failure_code) FROM index_generations"))
                assertEquals(2L, database.longValue("SELECT COUNT(*) FROM indexed_messages"))
                assertEquals(1L, database.longValue("SELECT COUNT(*) FROM indexed_conversations"))
                assertEquals(
                    0L,
                    database.longValue(
                        "SELECT participants_truncated FROM indexed_conversations " +
                            "WHERE provider_thread_id = 20",
                    ),
                )
            }

            val database = Room.databaseBuilder(
                context,
                AuroraIndexDatabase::class.java,
                DATABASE_NAME,
            ).addMigrations(INDEX_MIGRATION_2_3).build()
            try {
                val result = RoomConversationRepository(database)
                    .loadConversation(ProviderThreadId(20L))
                assertTrue(result is ConversationLookupResult.Found)
                val found = result as ConversationLookupResult.Found
                assertEquals(IndexRunState.PAUSED, found.coverage.state)
                assertFalse(found.coverage.verifiedComplete)
                assertEquals(1, found.summary.indexedParticipantCount)
                assertFalse(found.summary.participantsTruncated)
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(DATABASE_NAME)
        }
    }

    private companion object {
        const val DATABASE_NAME: String = "aurora-index-migration-2-3-test.db"
    }
}

private fun SupportSQLiteDatabase.insertCompleteVersionTwoProjection() {
    execSQL(
        """
        INSERT INTO index_generations(
            generation_id, state, started_at_ms, updated_at_ms,
            completed_at_ms, committed_count, pending_changes,
            failure_code, target_batch_size, signal_sequence
        ) VALUES(1, 3, 10, 20, 20, 2, 0, NULL, 500, 7)
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO index_checkpoints(
            generation_id, provider_kind, cursor_timestamp_ms, cursor_provider_id,
            exhausted, committed_count, updated_at_ms, verified_provider_count
        ) VALUES
            (1, 1, 1000, 11, 1, 2, 20, 2),
            (1, 2, NULL, NULL, 1, 0, 20, 0)
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO indexed_messages(
            row_id, provider_kind, provider_id, provider_thread_id,
            timestamp_ms, sent_timestamp_ms, direction, message_box,
            message_status, subscription_id, sender_address, body, subject,
            attachment_count, attachment_type_summary, attachment_total_bytes,
            is_read, is_seen, is_locked, sync_fingerprint, searchable_text,
            last_seen_generation
        ) VALUES
            (1, 1, 10, 20, 900, NULL, 1, 'inbox', 'complete', NULL,
             '+15550000001', 'older', NULL, 0, '', NULL, 1, 1, 0, ?, '', 1),
            (2, 1, 11, 20, 1000, NULL, 1, 'inbox', 'complete', NULL,
             NULL, 'newer', NULL, 0, '', NULL, 1, 1, 0, ?, '', 1)
        """.trimIndent(),
        arrayOf("a".repeat(64), "b".repeat(64)),
    )
    execSQL(
        """
        INSERT INTO indexed_conversations(
            provider_thread_id, latest_row_id, latest_provider_kind, latest_provider_id,
            latest_timestamp_ms, latest_sent_timestamp_ms, latest_direction,
            latest_message_box, latest_message_status, latest_subscription_id,
            latest_sender_address, latest_snippet, latest_attachment_count,
            latest_attachment_type_summary, latest_is_read, indexed_message_count,
            indexed_unread_count, indexed_participant_count, participants_truncated,
            last_seen_generation
        ) VALUES(
            20, 2, 1, 11, 1000, NULL, 1, 'inbox', 'complete', NULL,
            NULL, 'newer', 0, '', 1, 2, 0, 1, 0, 1
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO indexed_conversation_participants(
            provider_thread_id, address, last_seen_generation
        ) VALUES(20, '+15550000001', 1)
        """.trimIndent(),
    )
}

private fun SupportSQLiteDatabase.longValue(sql: String): Long = query(sql).use { cursor ->
    check(cursor.moveToFirst())
    cursor.getLong(0)
}
