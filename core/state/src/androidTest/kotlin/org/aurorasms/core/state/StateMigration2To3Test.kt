// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aurorasms.core.state.storage.AuroraStateDatabase
import org.aurorasms.core.state.storage.AppearanceSelectionEnforcement
import org.aurorasms.core.state.storage.AppearanceOverrideSequenceEnforcement
import org.aurorasms.core.state.storage.DraftIdentityEnforcement
import org.aurorasms.core.state.storage.STATE_MIGRATION_1_2
import org.aurorasms.core.state.storage.STATE_MIGRATION_2_3
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StateMigration2To3Test {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AuroraStateDatabase::class.java,
    )

    @Before
    fun clearDatabases() {
        context.deleteDatabase(VERSION_TWO_DATABASE)
        context.deleteDatabase(VERSION_ONE_DATABASE)
    }

    @After
    fun cleanDatabases() {
        context.deleteDatabase(VERSION_TWO_DATABASE)
        context.deleteDatabase(VERSION_ONE_DATABASE)
    }

    @Test
    fun migrationPreservesDraftProfilesAndSelectionAndCreatesEmptyOverrideTables() {
        migrationHelper.createDatabase(VERSION_TWO_DATABASE, 2).use { database ->
            database.insertSyntheticDraft()
            database.insertSyntheticProfileAndSelection()
            database.installVersionTwoTriggers()
        }

        migrationHelper.runMigrationsAndValidate(
            VERSION_TWO_DATABASE,
            3,
            true,
            STATE_MIGRATION_2_3,
        ).use { database ->
            assertEquals("synthetic migration body", database.queryString("SELECT body FROM drafts"))
            assertEquals("Synthetic migrated profile", database.queryString("SELECT name FROM appearance_profiles"))
            assertEquals(7L, database.queryLong("SELECT active_profile_id FROM appearance_selection"))
            assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM appearance_screen_overrides"))
            assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM appearance_conversation_overrides"))
            assertEquals(
                1L,
                database.queryLong("SELECT COUNT(*) FROM appearance_override_revision_sequence"),
            )
            assertEquals(
                0L,
                database.queryLong(
                    "SELECT last_allocated_revision FROM appearance_override_revision_sequence",
                ),
            )
            assertTrue(database.hasIndex("index_appearance_screen_overrides_profile_id"))
            assertTrue(database.hasIndex("index_appearance_conversation_overrides_provider_thread_id"))
            assertTrue(database.hasIndex("index_appearance_conversation_overrides_profile_id"))
            database.assertStateTriggersExist()
            assertFalse(database.query("PRAGMA foreign_key_check").use { it.moveToFirst() })
        }
    }

    @Test
    fun chainedMigrationFromVersionOnePreservesDraftAndSeedsSelection() {
        migrationHelper.createDatabase(VERSION_ONE_DATABASE, 1).use { database ->
            database.insertSyntheticDraft()
            database.installVersionOneTriggers()
        }

        migrationHelper.runMigrationsAndValidate(
            VERSION_ONE_DATABASE,
            3,
            true,
            STATE_MIGRATION_1_2,
            STATE_MIGRATION_2_3,
        ).use { database ->
            assertEquals("synthetic migration body", database.queryString("SELECT body FROM drafts"))
            assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM appearance_selection"))
            assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM appearance_profiles"))
            assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM appearance_screen_overrides"))
            assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM appearance_conversation_overrides"))
            assertEquals(
                1L,
                database.queryLong("SELECT COUNT(*) FROM appearance_override_revision_sequence"),
            )
            assertEquals(
                0L,
                database.queryLong(
                    "SELECT last_allocated_revision FROM appearance_override_revision_sequence",
                ),
            )
            database.assertStateTriggersExist()
            assertFalse(database.query("PRAGMA foreign_key_check").use { it.moveToFirst() })
        }
    }

    private fun SupportSQLiteDatabase.insertSyntheticDraft() {
        execSQL(
            """
            INSERT INTO drafts(
                provider_thread_id, participant_set_key, body, subject,
                created_timestamp_ms, updated_timestamp_ms
            ) VALUES(42, NULL, 'synthetic migration body', NULL, 10, 20)
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.insertSyntheticProfileAndSelection() {
        execSQL(
            """
            INSERT INTO appearance_profiles(
                profile_id, name, normalized_name, profile_schema_version, palette_code,
                hue_degrees, row_density_code, avatar_mask_code, navigation_style_code,
                bubble_geometry_code, reduced_motion, high_contrast, wallpaper_dim_permill,
                focal_x_permill, focal_y_permill, revision, created_timestamp_ms,
                updated_timestamp_ms
            ) VALUES(
                7, 'Synthetic migrated profile', 'synthetic migrated profile', 1, 'aurora_dark',
                174, 'comfortable', 'circle', 'classic', 'rounded', 0, 0, 520,
                500, 500, 1, 10, 10
            )
            """.trimIndent(),
        )
        execSQL(
            "INSERT INTO appearance_selection(singleton_id, active_profile_id, snapshot_revision) " +
                "VALUES(1, 7, 2)",
        )
    }

    private fun SupportSQLiteDatabase.installVersionTwoTriggers() {
        installVersionOneTriggers()
        execSQL(AppearanceSelectionEnforcement.CREATE_INSERT_TRIGGER)
        execSQL(AppearanceSelectionEnforcement.CREATE_UPDATE_TRIGGER)
    }

    private fun SupportSQLiteDatabase.installVersionOneTriggers() {
        execSQL(DraftIdentityEnforcement.CREATE_INSERT_TRIGGER)
        execSQL(DraftIdentityEnforcement.CREATE_UPDATE_TRIGGER)
    }

    private fun SupportSQLiteDatabase.assertStateTriggersExist() {
        assertTrue(hasTrigger(DraftIdentityEnforcement.INSERT_TRIGGER_NAME))
        assertTrue(hasTrigger(DraftIdentityEnforcement.UPDATE_TRIGGER_NAME))
        assertTrue(hasTrigger(AppearanceSelectionEnforcement.INSERT_TRIGGER_NAME))
        assertTrue(hasTrigger(AppearanceSelectionEnforcement.UPDATE_TRIGGER_NAME))
        assertTrue(hasTrigger(AppearanceOverrideSequenceEnforcement.INSERT_TRIGGER_NAME))
        assertTrue(hasTrigger(AppearanceOverrideSequenceEnforcement.UPDATE_TRIGGER_NAME))
        assertTrue(hasTrigger(AppearanceOverrideSequenceEnforcement.DELETE_TRIGGER_NAME))
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
        private const val VERSION_TWO_DATABASE: String = "aurora-state-migration-2-3-test.db"
        private const val VERSION_ONE_DATABASE: String = "aurora-state-migration-1-3-test.db"
    }
}
