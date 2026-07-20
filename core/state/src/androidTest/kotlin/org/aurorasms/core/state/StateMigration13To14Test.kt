// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aurorasms.core.state.storage.AuroraStateDatabase
import org.aurorasms.core.state.storage.STATE_MIGRATION_13_14
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StateMigration13To14Test {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AuroraStateDatabase::class.java,
    )

    @Before fun clear() { context.deleteDatabase(DATABASE_NAME) }
    @After fun clean() { context.deleteDatabase(DATABASE_NAME) }

    @Test
    fun migrationPreservesDraftAndAddsBoundedCascadeOwnedAttachments() {
        helper.createDatabase(DATABASE_NAME, 13).use { db ->
            db.execSQL(
                "INSERT INTO drafts(draft_id,provider_thread_id,participant_set_key,body,subject," +
                    "created_timestamp_ms,updated_timestamp_ms) " +
                    "VALUES(7,42,NULL,'Synthetic',NULL,100,100)",
            )
        }

        helper.runMigrationsAndValidate(DATABASE_NAME, 14, true, STATE_MIGRATION_13_14).use { db ->
            db.execSQL("PRAGMA foreign_keys=ON")
            assertEquals(
                "Synthetic",
                db.query("SELECT body FROM drafts WHERE draft_id=7").use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getString(0)
                },
            )
            db.execSQL(
                "INSERT INTO draft_attachments(draft_id,attachment_index,content_type,content_bytes) " +
                    "VALUES(7,0,'image/jpeg',X'010203')",
            )
            assertEquals(
                3,
                db.query("SELECT length(content_bytes) FROM draft_attachments WHERE draft_id=7")
                    .use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) },
            )
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    "INSERT INTO draft_attachments" +
                        "(draft_id,attachment_index,content_type,content_bytes) " +
                        "VALUES(7,1,'image/gif',X'01')",
                )
            }
            db.execSQL("DELETE FROM drafts WHERE draft_id=7")
            assertEquals(
                0,
                db.query("SELECT COUNT(*) FROM draft_attachments").use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getInt(0)
                },
            )
        }
    }

    companion object { private const val DATABASE_NAME = "aurora-state-migration-13-14-test.db" }
}
