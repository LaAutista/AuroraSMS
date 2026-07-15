// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aurorasms.core.state.storage.APPEARANCE_SELECTION_SINGLETON_ID
import org.aurorasms.core.state.storage.AppearanceOverrideSequenceEnforcement
import org.aurorasms.core.state.storage.AppearanceSelectionEnforcement
import org.aurorasms.core.state.storage.AuroraStateDatabase
import org.aurorasms.core.state.storage.DraftIdentityEnforcement
import org.aurorasms.core.state.storage.STATE_MIGRATION_1_2
import org.aurorasms.core.state.storage.STATE_MIGRATION_2_3
import org.aurorasms.core.state.storage.STATE_MIGRATION_3_4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StateMigration1To4Test {
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
    fun directMigrationFromVersionOnePreservesDraftAndSelectionThroughVersionFour() {
        migrationHelper.createDatabase(DATABASE_NAME, 1).use { database ->
            database.execSQL(
                """
                INSERT INTO drafts(
                    provider_thread_id, participant_set_key, body, subject,
                    created_timestamp_ms, updated_timestamp_ms
                ) VALUES(42, NULL, 'synthetic direct migration body',
                    'synthetic direct migration subject', 10, 20)
                """.trimIndent(),
            )
            database.execSQL(DraftIdentityEnforcement.CREATE_INSERT_TRIGGER)
            database.execSQL(DraftIdentityEnforcement.CREATE_UPDATE_TRIGGER)
        }

        migrationHelper.runMigrationsAndValidate(
            DATABASE_NAME,
            4,
            true,
            STATE_MIGRATION_1_2,
            STATE_MIGRATION_2_3,
            STATE_MIGRATION_3_4,
        ).use { database ->
            assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM drafts"))
            assertEquals(
                "synthetic direct migration body",
                database.queryString("SELECT body FROM drafts WHERE provider_thread_id = 42"),
            )
            assertEquals(
                "synthetic direct migration subject",
                database.queryString("SELECT subject FROM drafts WHERE provider_thread_id = 42"),
            )
            assertEquals(10L, database.queryLong("SELECT created_timestamp_ms FROM drafts"))
            assertEquals(20L, database.queryLong("SELECT updated_timestamp_ms FROM drafts"))

            assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM appearance_profiles"))
            assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM appearance_selection"))
            assertEquals(
                APPEARANCE_SELECTION_SINGLETON_ID.toLong(),
                database.queryLong("SELECT singleton_id FROM appearance_selection"),
            )
            assertEquals(
                0L,
                database.queryLong("SELECT COUNT(active_profile_id) FROM appearance_selection"),
            )
            assertEquals(
                1L,
                database.queryLong("SELECT snapshot_revision FROM appearance_selection"),
            )

            assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM appearance_screen_overrides"))
            assertEquals(
                0L,
                database.queryLong("SELECT COUNT(*) FROM appearance_conversation_overrides"),
            )
            assertEquals(
                0L,
                database.queryLong("SELECT last_allocated_revision " +
                    "FROM appearance_override_revision_sequence"),
            )
            assertEquals(
                0L,
                database.queryLong("SELECT COUNT(*) FROM appearance_screen_wallpapers"),
            )
            assertEquals(
                0L,
                database.queryLong("SELECT COUNT(*) FROM appearance_conversation_wallpapers"),
            )
            assertFalse(database.tableExists("appearance_media"))
            database.assertStateTriggersExist()
            assertFalse(database.query("PRAGMA foreign_key_check").use { it.moveToFirst() })
        }
    }

    private fun SupportSQLiteDatabase.assertStateTriggersExist() {
        assertTrue(triggerExists(DraftIdentityEnforcement.INSERT_TRIGGER_NAME))
        assertTrue(triggerExists(DraftIdentityEnforcement.UPDATE_TRIGGER_NAME))
        assertTrue(triggerExists(AppearanceSelectionEnforcement.INSERT_TRIGGER_NAME))
        assertTrue(triggerExists(AppearanceSelectionEnforcement.UPDATE_TRIGGER_NAME))
        assertTrue(triggerExists(AppearanceOverrideSequenceEnforcement.INSERT_TRIGGER_NAME))
        assertTrue(triggerExists(AppearanceOverrideSequenceEnforcement.UPDATE_TRIGGER_NAME))
        assertTrue(triggerExists(AppearanceOverrideSequenceEnforcement.DELETE_TRIGGER_NAME))
    }

    private fun SupportSQLiteDatabase.queryLong(sql: String): Long = query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun SupportSQLiteDatabase.queryString(sql: String): String = query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getString(0)
    }

    private fun SupportSQLiteDatabase.tableExists(name: String): Boolean = query(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
        arrayOf(name),
    ).use { it.moveToFirst() }

    private fun SupportSQLiteDatabase.triggerExists(name: String): Boolean = query(
        "SELECT 1 FROM sqlite_master WHERE type = 'trigger' AND name = ?",
        arrayOf(name),
    ).use { it.moveToFirst() }

    companion object {
        private const val DATABASE_NAME: String = "aurora-state-migration-1-4-test.db"
    }
}
