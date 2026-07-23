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
import org.aurorasms.core.state.storage.ConversationSubscriptionPreferenceEnforcement
import org.aurorasms.core.state.storage.STATE_MIGRATION_6_7
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
class StateMigration6To7Test {
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
    fun migrationAddsContentFreePurposeSeparatedPreferenceWithPhysicalInvariants() {
        migrationHelper.createDatabase(DATABASE_NAME, 6).close()

        migrationHelper.runMigrationsAndValidate(
            DATABASE_NAME,
            7,
            true,
            STATE_MIGRATION_6_7,
        ).use { database ->
            assertEquals(
                setOf(
                    "participant_set_key",
                    "provider_thread_id",
                    "subscription_id",
                    "revision",
                    "updated_timestamp_ms",
                ),
                database.tableColumns("conversation_subscription_preferences"),
            )
            assertFalse(
                database.tableColumns("conversation_subscription_preferences")
                    .any { it in setOf("address", "recipient", "body", "subject", "display_label") },
            )
            assertTrue(
                database.hasIndex(
                    "index_conversation_subscription_preferences_provider_thread_id",
                ),
            )
            assertTrue(
                database.hasTrigger(
                    ConversationSubscriptionPreferenceEnforcement.INSERT_INTEGRITY_TRIGGER_NAME,
                ),
            )
            assertTrue(
                database.hasTrigger(
                    ConversationSubscriptionPreferenceEnforcement.UPDATE_INTEGRITY_TRIGGER_NAME,
                ),
            )

            val key = "sha256-v1:" + "a".repeat(64)
            database.insertPreference(key)
            database.execSQL(
                """
                UPDATE conversation_subscription_preferences
                SET provider_thread_id = 12, subscription_id = 2,
                    revision = 2, updated_timestamp_ms = 101
                WHERE participant_set_key = '$key'
                """.trimIndent(),
            )
            assertThrows(SQLiteConstraintException::class.java) {
                database.execSQL(
                    """
                    UPDATE conversation_subscription_preferences
                    SET subscription_id = 3, revision = 3, updated_timestamp_ms = 101
                    WHERE participant_set_key = '$key'
                    """.trimIndent(),
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                database.insertPreference("sha256-v1:" + "G".repeat(64))
            }
        }
    }

    private fun SupportSQLiteDatabase.insertPreference(key: String) {
        execSQL(
            """
            INSERT INTO conversation_subscription_preferences(
                participant_set_key, provider_thread_id, subscription_id, revision,
                updated_timestamp_ms
            ) VALUES('$key', 11, 1, 1, 100)
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

    private fun SupportSQLiteDatabase.hasIndex(name: String): Boolean = query(
        "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?",
        arrayOf(name),
    ).use { it.moveToFirst() }

    private fun SupportSQLiteDatabase.hasTrigger(name: String): Boolean = query(
        "SELECT 1 FROM sqlite_master WHERE type = 'trigger' AND name = ?",
        arrayOf(name),
    ).use { it.moveToFirst() }

    companion object {
        private const val DATABASE_NAME: String = "aurora-state-migration-6-7-test.db"
    }
}
