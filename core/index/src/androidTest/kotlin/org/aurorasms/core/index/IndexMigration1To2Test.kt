// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aurorasms.core.index.storage.AuroraIndexDatabase
import org.aurorasms.core.index.storage.INDEX_MIGRATION_1_2
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndexMigration1To2Test {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AuroraIndexDatabase::class.java,
    )

    @Test
    fun everyVersionOneGenerationStateRequiresFreshVersionTwoProjection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        (1..5).forEach { originalState ->
            val databaseName = "aurora-index-migration-state-$originalState.db"
            context.deleteDatabase(databaseName)
            try {
                migrationHelper.createDatabase(databaseName, 1).use { database ->
                    database.execSQL(
                        """
                        INSERT INTO index_generations(
                            generation_id, state, started_at_ms, updated_at_ms,
                            completed_at_ms, committed_count, pending_changes,
                            failure_code, target_batch_size, signal_sequence
                        ) VALUES(1, ?, 10, 20, ?, 1, 0, ?, 500, 3)
                        """.trimIndent(),
                        arrayOf<Any?>(
                            originalState,
                            if (originalState == 3) 20L else null,
                            if (originalState == 5) 7 else null,
                        ),
                    )
                    insertVersionOneMessage(database)
                }

                migrationHelper.runMigrationsAndValidate(
                    databaseName,
                    2,
                    true,
                    INDEX_MIGRATION_1_2,
                ).use { database ->
                    assertEquals(1L, database.longValue("SELECT COUNT(*) FROM indexed_messages"))
                    assertEquals(
                        1L,
                        database.longValue(
                            "SELECT COUNT(*) FROM indexed_messages_fts WHERE indexed_messages_fts MATCH 'migration'",
                        ),
                    )
                    assertEquals(0L, database.longValue("SELECT COUNT(*) FROM indexed_conversations"))
                    assertEquals(0L, database.longValue("SELECT COUNT(*) FROM indexed_conversation_participants"))
                    assertEquals(4L, database.longValue("SELECT state FROM index_generations WHERE generation_id = 1"))
                    assertEquals(1L, database.longValue("SELECT pending_changes FROM index_generations WHERE generation_id = 1"))
                    assertEquals(4L, database.longValue("SELECT signal_sequence FROM index_generations WHERE generation_id = 1"))
                    assertEquals(0L, database.longValue("SELECT COUNT(completed_at_ms) FROM index_generations"))
                    assertEquals(0L, database.longValue("SELECT COUNT(failure_code) FROM index_generations"))
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
        }
    }
}

private fun insertVersionOneMessage(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        INSERT INTO indexed_messages(
            row_id, provider_kind, provider_id, provider_thread_id,
            timestamp_ms, sent_timestamp_ms, direction, message_box,
            message_status, subscription_id, sender_address, body, subject,
            attachment_count, attachment_type_summary, attachment_total_bytes,
            is_read, is_seen, is_locked, sync_fingerprint, searchable_text,
            last_seen_generation
        ) VALUES(
            1, 1, 10, 20,
            1000, NULL, 1, 'inbox',
            'complete', NULL, '+15550000000', 'migration body', NULL,
            0, '', NULL,
            1, 1, 0, ?, 'migration',
            1
        )
        """.trimIndent(),
        arrayOf("a".repeat(64)),
    )
}

private fun SupportSQLiteDatabase.longValue(sql: String): Long = query(sql).use { cursor ->
    check(cursor.moveToFirst())
    cursor.getLong(0)
}
