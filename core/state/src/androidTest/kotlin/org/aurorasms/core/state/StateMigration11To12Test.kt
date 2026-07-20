// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aurorasms.core.state.storage.AuroraStateDatabase
import org.aurorasms.core.state.storage.SPAM_SAFETY_DECISIONS_TABLE
import org.aurorasms.core.state.storage.STATE_MIGRATION_11_12
import org.aurorasms.core.state.storage.SpamSafetyDecisionEnforcement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StateMigration11To12Test {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AuroraStateDatabase::class.java,
    )

    @Before fun clear() { context.deleteDatabase(DATABASE_NAME) }
    @After fun clean() { context.deleteDatabase(DATABASE_NAME) }

    @Test
    fun migrationAddsBoundedContentFreeSpamSafetyStateWithPhysicalConstraints() {
        helper.createDatabase(DATABASE_NAME, 11).close()
        helper.runMigrationsAndValidate(DATABASE_NAME, 12, true, STATE_MIGRATION_11_12).use { db ->
            assertEquals(
                setOf(
                    "participant_set_key", "provider_thread_id", "single_sender_key",
                    "classification_code", "blocked", "revision", "updated_timestamp_ms",
                ),
                db.query("PRAGMA table_info(`$SPAM_SAFETY_DECISIONS_TABLE`)").use { cursor ->
                    val name = cursor.getColumnIndexOrThrow("name")
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
                },
            )
            listOf(
                SpamSafetyDecisionEnforcement.INSERT_LIMIT_TRIGGER_NAME,
                SpamSafetyDecisionEnforcement.INSERT_INTEGRITY_TRIGGER_NAME,
                SpamSafetyDecisionEnforcement.UPDATE_INTEGRITY_TRIGGER_NAME,
            ).forEach { trigger ->
                assertEquals(
                    true,
                    db.query(
                        "SELECT 1 FROM sqlite_master WHERE type='trigger' AND name=?",
                        arrayOf(trigger),
                    ).use { it.moveToFirst() },
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    "INSERT INTO spam_safety_decisions(participant_set_key," +
                        "provider_thread_id,single_sender_key,classification_code,blocked," +
                        "revision,updated_timestamp_ms) VALUES(" +
                        "'${"sha256-v1:" + "a".repeat(64)}',1,NULL,'neutral_v1',0,1,1)",
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    "INSERT INTO spam_safety_decisions(participant_set_key," +
                        "provider_thread_id,single_sender_key,classification_code,blocked," +
                        "revision,updated_timestamp_ms) VALUES(" +
                        "'${"sha256-v1:" + "b".repeat(64)}',2,NULL,'spam_v1',1,1,1)",
                )
            }
        }
    }

    companion object { private const val DATABASE_NAME = "aurora-state-migration-11-12-test.db" }
}
