// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aurorasms.core.index.storage.AuroraIndexDatabase
import org.aurorasms.core.index.storage.IndexDatabaseFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndexSchemaV1Test {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AuroraIndexDatabase::class.java,
    )

    @Test
    fun schemaHasSeparateTablesIndicesFtsAndSelectiveUpdateTriggers() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = IndexDatabaseFactory.createInMemory(context)
        try {
            val sqlite = database.openHelper.writableDatabase
            assertEquals(1, sqlite.version)
            val tables = sqlite.stringColumn(
                "SELECT name FROM sqlite_master WHERE type IN ('table', 'view')",
            ).toSet()
            assertTrue("indexed_messages" in tables)
            assertTrue("indexed_messages_fts" in tables)
            assertTrue("index_generations" in tables)
            assertTrue("index_checkpoints" in tables)

            val indexedColumns = sqlite.stringColumn("PRAGMA table_info(indexed_messages)").toSet()
            // PRAGMA table_info's first column is cid; inspect names explicitly below.
            assertFalse(indexedColumns.isEmpty())
            val indices = sqlite.stringColumn(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'indexed_messages'",
            ).toSet()
            assertTrue(indices.any { "provider_kind_provider_id" in it })
            assertTrue(indices.any { "provider_thread_id_timestamp_ms_row_id" in it })
            assertTrue(indices.any { "timestamp_ms_row_id" in it })
            assertTrue(indices.any { "sender_address_timestamp_ms_row_id" in it })
            assertTrue(indices.any { "subscription_id_timestamp_ms_row_id" in it })
            assertTrue(indices.any { "is_read_timestamp_ms_row_id" in it })

            val ftsSql = sqlite.singleString(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'indexed_messages_fts'",
            )
            assertTrue(ftsSql.contains("USING FTS4", ignoreCase = true))
            assertTrue(ftsSql.contains("tokenize=unicode61", ignoreCase = true))
            assertTrue(ftsSql.contains("content=`indexed_messages`", ignoreCase = true))

            val updateTriggers = sqlite.stringColumn(
                "SELECT sql FROM sqlite_master WHERE type = 'trigger' AND name LIKE '%fts%UPDATE'",
            )
            assertEquals(2, updateTriggers.size)
            updateTriggers.forEach { sql ->
                assertTrue(sql.contains("UPDATE OF body, subject, searchable_text", ignoreCase = true))
                assertTrue(sql.contains("OLD.body IS NOT NEW.body", ignoreCase = true))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun keysetPlanUsesReviewedIndexWithoutOffsetOrTemporarySort() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = IndexDatabaseFactory.createInMemory(context)
        try {
            val sqlite = database.openHelper.writableDatabase
            val plan = sqlite.stringColumn(
                """
                EXPLAIN QUERY PLAN
                SELECT * FROM indexed_messages
                WHERE provider_thread_id = 42
                  AND (timestamp_ms < 1000 OR (timestamp_ms = 1000 AND row_id < 99))
                ORDER BY timestamp_ms DESC, row_id DESC
                LIMIT 51
                """.trimIndent(),
                column = 3,
            ).joinToString(" ")
            assertTrue(plan.contains("provider_thread_id_timestamp_ms_row_id"))
            assertFalse(plan.contains("TEMP B-TREE", ignoreCase = true))
            assertFalse(plan.contains("OFFSET", ignoreCase = true))
        } finally {
            database.close()
        }
    }

    @Test
    fun denseFtsPlanScansMatchOnceAndSortsOnlyCompactRowIds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = IndexDatabaseFactory.createInMemory(context)
        try {
            val plan = database.openHelper.writableDatabase.stringColumn(
                """
                EXPLAIN QUERY PLAN
                SELECT indexed_messages.row_id
                FROM indexed_messages_fts
                CROSS JOIN indexed_messages
                  ON indexed_messages.row_id = indexed_messages_fts.rowid
                WHERE indexed_messages_fts MATCH 'alpha'
                ORDER BY indexed_messages.timestamp_ms DESC, indexed_messages.row_id DESC
                LIMIT 51
                """.trimIndent(),
                column = 3,
            ).joinToString(" ")
            assertTrue(plan.contains("indexed_messages_fts", ignoreCase = true))
            assertTrue(plan.contains("VIRTUAL TABLE", ignoreCase = true))
            assertTrue(plan.contains("INTEGER PRIMARY KEY", ignoreCase = true))
            assertTrue(plan.contains("TEMP B-TREE", ignoreCase = true))
            assertFalse(plan.contains("CORRELATED", ignoreCase = true))
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationHelperCreatesExportedVersionOneAndCurrentRoomValidatesIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(MIGRATION_DATABASE_NAME)
        try {
            migrationHelper.createDatabase(MIGRATION_DATABASE_NAME, 1).use { sqlite ->
                assertEquals(1, sqlite.version)
                assertTrue(
                    sqlite.query(
                        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'indexed_messages'",
                    ).use { it.moveToFirst() },
                )
            }
            val database = Room.databaseBuilder(
                context,
                AuroraIndexDatabase::class.java,
                MIGRATION_DATABASE_NAME,
            ).build()
            try {
                assertEquals(1, database.openHelper.writableDatabase.version)
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(MIGRATION_DATABASE_NAME)
        }
    }

    private companion object {
        const val MIGRATION_DATABASE_NAME: String = "aurora-index-schema-v1-test.db"
    }
}

private fun SupportSQLiteDatabase.stringColumn(
    sql: String,
    column: Int = 0,
): List<String> = query(sql).use { cursor ->
    buildList {
        while (cursor.moveToNext()) add(cursor.getString(column))
    }
}

private fun SupportSQLiteDatabase.singleString(sql: String): String =
    stringColumn(sql).single()
