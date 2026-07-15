// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.state.storage.AuroraStateDatabase
import org.aurorasms.core.state.storage.AppearanceOverrideSequenceEnforcement
import org.aurorasms.core.state.storage.AppearanceSelectionEnforcement
import org.aurorasms.core.state.storage.DraftIdentityEnforcement
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
class StateMigration3To4Test {
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
    fun migrationPreservesVersionThreeStateAndCreatesIndependentWallpaperTables() {
        val participantKey = AppearanceParticipantSetKey.fromParticipants(
            listOf(ParticipantAddress("synthetic@example.invalid")),
        ).toStorageValue()
        migrationHelper.createDatabase(DATABASE_NAME, 3).use { database ->
            database.insertVersionThreeState(participantKey)
            database.installVersionThreeTriggers()
            database.execSQL(
                "UPDATE appearance_override_revision_sequence SET last_allocated_revision = 1",
            )
            database.execSQL(
                "UPDATE appearance_override_revision_sequence SET last_allocated_revision = 2",
            )
        }

        migrationHelper.runMigrationsAndValidate(
            DATABASE_NAME,
            4,
            true,
            STATE_MIGRATION_3_4,
        ).use { database ->
            assertEquals("synthetic migration body", database.queryString("SELECT body FROM drafts"))
            assertEquals("Synthetic migrated profile", database.queryString("SELECT name FROM appearance_profiles"))
            assertEquals(7L, database.queryLong("SELECT active_profile_id FROM appearance_selection"))
            assertEquals(2L, database.queryLong("SELECT snapshot_revision FROM appearance_selection"))
            assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM appearance_screen_overrides"))
            assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM appearance_conversation_overrides"))
            assertEquals(2L, database.queryLong("SELECT last_allocated_revision FROM appearance_override_revision_sequence"))
            assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM appearance_screen_wallpapers"))
            assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM appearance_conversation_wallpapers"))
            assertTrue(database.hasIndex("index_appearance_screen_wallpapers_media_kind_code_media_id"))
            assertTrue(database.hasIndex("index_appearance_conversation_wallpapers_provider_thread_id"))
            assertTrue(
                database.hasIndex(
                    "index_appearance_conversation_wallpapers_media_kind_code_media_id",
                ),
            )
            database.assertStateTriggersExist()
            assertFalse(database.query("PRAGMA foreign_key_check").use { it.moveToFirst() })
        }
    }

    private fun SupportSQLiteDatabase.insertVersionThreeState(participantKey: String) {
        execSQL(
            """
            INSERT INTO drafts(
                provider_thread_id, participant_set_key, body, subject,
                created_timestamp_ms, updated_timestamp_ms
            ) VALUES(42, NULL, 'synthetic migration body', NULL, 10, 20)
            """.trimIndent(),
        )
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
        execSQL(
            "INSERT INTO appearance_override_revision_sequence(" +
                "singleton_id, last_allocated_revision) VALUES(1, 0)",
        )
        execSQL(
            "INSERT INTO appearance_screen_overrides(screen_code, profile_id, revision) " +
                "VALUES('global_thread', 7, 1)",
        )
        execSQL(
            "INSERT INTO appearance_conversation_overrides(" +
                "participant_set_key, provider_thread_id, profile_id, revision" +
                ") VALUES('$participantKey', 42, 7, 2)",
        )
    }

    private fun SupportSQLiteDatabase.installVersionThreeTriggers() {
        execSQL(DraftIdentityEnforcement.CREATE_INSERT_TRIGGER)
        execSQL(DraftIdentityEnforcement.CREATE_UPDATE_TRIGGER)
        execSQL(AppearanceSelectionEnforcement.CREATE_INSERT_TRIGGER)
        execSQL(AppearanceSelectionEnforcement.CREATE_UPDATE_TRIGGER)
        execSQL(AppearanceOverrideSequenceEnforcement.CREATE_INSERT_TRIGGER)
        execSQL(AppearanceOverrideSequenceEnforcement.CREATE_UPDATE_TRIGGER)
        execSQL(AppearanceOverrideSequenceEnforcement.CREATE_DELETE_TRIGGER)
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
        private const val DATABASE_NAME: String = "aurora-state-migration-3-4-test.db"
    }
}
