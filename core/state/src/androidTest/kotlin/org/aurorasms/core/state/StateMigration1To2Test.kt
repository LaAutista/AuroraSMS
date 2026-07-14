// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aurorasms.core.state.storage.APPEARANCE_SELECTION_SINGLETON_ID
import org.aurorasms.core.state.storage.AppearanceSelectionEnforcement
import org.aurorasms.core.state.storage.AuroraStateDatabase
import org.aurorasms.core.state.storage.STATE_MIGRATION_1_2
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StateMigration1To2Test {
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
    fun migrationPreservesDraftAndCreatesCanonicalSelectionWithoutNamedProfiles() {
        migrationHelper.createDatabase(DATABASE_NAME, 1).use { database ->
            database.execSQL(
                """
                INSERT INTO drafts(
                    provider_thread_id, participant_set_key, body, subject,
                    created_timestamp_ms, updated_timestamp_ms
                ) VALUES(42, NULL, 'synthetic migration body', NULL, 10, 20)
                """.trimIndent(),
            )
        }

        migrationHelper.runMigrationsAndValidate(
            DATABASE_NAME,
            2,
            true,
            STATE_MIGRATION_1_2,
        ).use { database ->
            assertEquals(1L, database.longValue("SELECT COUNT(*) FROM drafts"))
            assertEquals(
                "synthetic migration body",
                database.stringValue("SELECT body FROM drafts WHERE provider_thread_id = 42"),
            )
            assertEquals(0L, database.longValue("SELECT COUNT(*) FROM appearance_profiles"))
            assertEquals(1L, database.longValue("SELECT COUNT(*) FROM appearance_selection"))
            assertEquals(
                APPEARANCE_SELECTION_SINGLETON_ID.toLong(),
                database.longValue("SELECT singleton_id FROM appearance_selection"),
            )
            assertEquals(
                0L,
                database.longValue("SELECT COUNT(active_profile_id) FROM appearance_selection"),
            )
            assertEquals(
                1L,
                database.longValue("SELECT snapshot_revision FROM appearance_selection"),
            )
            assertTrue(database.triggerExists(AppearanceSelectionEnforcement.INSERT_TRIGGER_NAME))
            assertTrue(database.triggerExists(AppearanceSelectionEnforcement.UPDATE_TRIGGER_NAME))
        }
    }

    companion object {
        private const val DATABASE_NAME: String = "aurora-state-migration-1-2-test.db"
    }
}

private fun androidx.sqlite.db.SupportSQLiteDatabase.longValue(sql: String): Long = query(sql).use {
    check(it.moveToFirst())
    it.getLong(0)
}

private fun androidx.sqlite.db.SupportSQLiteDatabase.stringValue(sql: String): String = query(sql).use {
    check(it.moveToFirst())
    it.getString(0)
}

private fun androidx.sqlite.db.SupportSQLiteDatabase.triggerExists(name: String): Boolean =
    query(
        "SELECT 1 FROM sqlite_master WHERE type = 'trigger' AND name = ?",
        arrayOf(name),
    ).use { it.moveToFirst() }
