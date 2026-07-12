// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aurorasms.core.state.storage.AuroraStateDatabase
import org.aurorasms.core.state.storage.DraftIdentityEnforcement
import org.aurorasms.core.state.storage.StateDatabaseFactory
import org.aurorasms.core.state.storage.StateDatabaseOpenFailureReason
import org.aurorasms.core.state.storage.StateDatabaseOpenResult
import org.aurorasms.core.state.storage.StateDatabaseRecoveryAction
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
class StateSchemaV1Test {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AuroraStateDatabase::class.java,
    )

    @Before
    fun clearDatabase() {
        context.deleteDatabase(StateDatabaseFactory.DATABASE_NAME)
        context.deleteDatabase(MIGRATION_DATABASE_NAME)
    }

    @After
    fun cleanDatabase() {
        context.deleteDatabase(StateDatabaseFactory.DATABASE_NAME)
        context.deleteDatabase(MIGRATION_DATABASE_NAME)
    }

    @Test
    fun schemaVersionOne_hasBoundedDraftColumnsAndIndices() {
        val database = openStateDatabase()
        val sqlite = database.openHelper.writableDatabase
        try {
            assertEquals(AuroraStateDatabase.VERSION, sqlite.version)
            assertEquals(
                setOf(
                    "draft_id",
                    "provider_thread_id",
                    "participant_set_key",
                    "body",
                    "subject",
                    "created_timestamp_ms",
                    "updated_timestamp_ms",
                ),
                sqlite.query("PRAGMA table_info(`drafts`)").use { cursor ->
                    val nameColumn = cursor.getColumnIndexOrThrow("name")
                    buildSet {
                        while (cursor.moveToNext()) add(cursor.getString(nameColumn))
                    }
                },
            )

            val indices = sqlite.query("PRAGMA index_list(`drafts`)").use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(nameColumn))
                }
            }
            assertTrue(indices.contains("index_drafts_provider_thread_id"))
            assertTrue(indices.contains("index_drafts_participant_set_key"))
            assertTrue(indices.contains("index_drafts_updated_timestamp_ms_draft_id"))
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationTestHelper_createsExportedSchemaVersionOneAndCurrentRoomValidatesIt() {
        migrationHelper.createDatabase(MIGRATION_DATABASE_NAME, AuroraStateDatabase.VERSION).use { sqlite ->
            assertEquals(AuroraStateDatabase.VERSION, sqlite.version)
            assertTrue(
                sqlite.query(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'drafts'",
                ).use { it.moveToFirst() },
            )
        }

        val database = Room.databaseBuilder(
            context,
            AuroraStateDatabase::class.java,
            MIGRATION_DATABASE_NAME,
        )
            .addCallback(DraftIdentityEnforcement.callback)
            .build()
        try {
            assertEquals(AuroraStateDatabase.VERSION, database.openHelper.writableDatabase.version)
        } finally {
            database.close()
        }
    }

    @Test
    fun physicalTriggers_rejectMissingAndAmbiguousIdentityAndReinstallOnOpen() {
        var database = openStateDatabase()
        var sqlite = database.openHelper.writableDatabase
        assertTriggerExists(sqlite, DraftIdentityEnforcement.INSERT_TRIGGER_NAME)
        assertTriggerExists(sqlite, DraftIdentityEnforcement.UPDATE_TRIGGER_NAME)

        assertThrows(SQLiteConstraintException::class.java) {
            sqlite.execSQL(
                """
                INSERT INTO drafts(
                    provider_thread_id,
                    participant_set_key,
                    body,
                    subject,
                    created_timestamp_ms,
                    updated_timestamp_ms
                ) VALUES (NULL, NULL, NULL, NULL, 0, 0)
                """.trimIndent(),
            )
        }

        sqlite.execSQL("DROP TRIGGER ${DraftIdentityEnforcement.INSERT_TRIGGER_NAME}")
        sqlite.execSQL("DROP TRIGGER ${DraftIdentityEnforcement.UPDATE_TRIGGER_NAME}")
        database.close()

        database = openStateDatabase()
        try {
            sqlite = database.openHelper.writableDatabase
            assertTriggerExists(sqlite, DraftIdentityEnforcement.INSERT_TRIGGER_NAME)
            assertTriggerExists(sqlite, DraftIdentityEnforcement.UPDATE_TRIGGER_NAME)
            assertThrows(SQLiteConstraintException::class.java) {
                sqlite.execSQL(
                    """
                    INSERT INTO drafts(
                        provider_thread_id,
                        participant_set_key,
                        body,
                        subject,
                        created_timestamp_ms,
                        updated_timestamp_ms
                    ) VALUES (17, '+15550000017', NULL, NULL, 0, 0)
                    """.trimIndent(),
                )
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun newerUnknownSchema_isNotDestructivelyReplaced() {
        val path = context.getDatabasePath(StateDatabaseFactory.DATABASE_NAME)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
            sqlite.execSQL("CREATE TABLE preservation_marker (value TEXT NOT NULL)")
            sqlite.execSQL("INSERT INTO preservation_marker(value) VALUES ('synthetic-marker')")
            sqlite.version = AuroraStateDatabase.VERSION + 1
        }

        assertEquals(
            StateDatabaseOpenResult.Failed(
                reason = StateDatabaseOpenFailureReason.INCOMPATIBLE_SCHEMA,
                recoveryAction =
                    StateDatabaseRecoveryAction.PRESERVE_DATABASE_AND_REQUIRE_EXPLICIT_RECOVERY,
            ),
            StateDatabaseFactory.open(context),
        )

        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            assertEquals(AuroraStateDatabase.VERSION + 1, sqlite.version)
            val marker = sqlite.rawQuery("SELECT value FROM preservation_marker", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            }
            assertEquals("synthetic-marker", marker)
            assertFalse(
                sqlite.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'drafts'",
                    null,
                ).use { it.moveToFirst() },
            )
        }
    }

    @Test
    fun corruptStateBytesAreReportedAndPreservedWithoutFrameworkDeletion() {
        val database = openStateDatabase()
        database.openHelper.writableDatabase
        database.close()
        val path = context.getDatabasePath(StateDatabaseFactory.DATABASE_NAME)
        val corruptBytes = path.readBytes()
        for (index in 100 until corruptBytes.size) corruptBytes[index] = 0x7f
        path.writeBytes(corruptBytes)

        assertEquals(
            StateDatabaseOpenResult.Failed(
                reason = StateDatabaseOpenFailureReason.UNREADABLE_OR_CORRUPT,
                recoveryAction =
                    StateDatabaseRecoveryAction.PRESERVE_DATABASE_AND_REQUIRE_EXPLICIT_RECOVERY,
            ),
            StateDatabaseFactory.open(context),
        )
        assertTrue(path.readBytes().contentEquals(corruptBytes))
    }

    private fun openStateDatabase() =
        (StateDatabaseFactory.open(context) as StateDatabaseOpenResult.Opened).database

    private fun assertTriggerExists(
        sqlite: androidx.sqlite.db.SupportSQLiteDatabase,
        triggerName: String,
    ) {
        assertTrue(
            sqlite.query(
                "SELECT 1 FROM sqlite_master WHERE type = 'trigger' AND name = ?",
                arrayOf(triggerName),
            ).use { it.moveToFirst() },
        )
    }

    companion object {
        private const val MIGRATION_DATABASE_NAME: String = "aurora-state-schema-v1-test.db"
    }
}
