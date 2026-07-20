// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aurorasms.core.state.storage.AuroraStateDatabase
import org.aurorasms.core.state.storage.STATE_MIGRATION_12_13
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StateMigration12To13Test {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AuroraStateDatabase::class.java,
    )

    @Before fun clear() { context.deleteDatabase(DATABASE_NAME) }
    @After fun clean() { context.deleteDatabase(DATABASE_NAME) }

    @Test
    fun migrationPreservesSmsOwnershipAndAddsConstrainedMmsOwnership() {
        helper.createDatabase(DATABASE_NAME, 12).use { db ->
            db.execSQL(
                "INSERT INTO composer_sms_operations(" +
                    "local_operation_id,provider_thread_id,draft_id,draft_revision_ms," +
                    "subscription_id,phase_code,provider_message_id,provider_conversation_id," +
                    "unit_count,created_timestamp_ms,updated_timestamp_ms,signature_text) " +
                    "VALUES(1,10,20,30,0,'reserved_v1',NULL,NULL,NULL,40,40,NULL)",
            )
            db.execSQL(
                "INSERT INTO acknowledged_composer_sms_receipts(" +
                    "local_operation_id,provider_message_id,provider_conversation_id,unit_count," +
                    "callback_proof_code,acknowledged_timestamp_ms,updated_timestamp_ms) " +
                    "VALUES(2,50,60,1,'awaiting_callback_v1',70,70)",
            )
        }

        helper.runMigrationsAndValidate(DATABASE_NAME, 13, true, STATE_MIGRATION_12_13).use { db ->
            assertEquals(
                "sms_v1",
                db.query("SELECT transport_code FROM composer_sms_operations WHERE local_operation_id=1")
                    .use { cursor ->
                        check(cursor.moveToFirst())
                        cursor.getString(0)
                    },
            )
            assertEquals(
                "sms_v1",
                db.query(
                    "SELECT provider_kind_code FROM acknowledged_composer_sms_receipts " +
                        "WHERE local_operation_id=2",
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getString(0)
                },
            )
            db.execSQL(
                "INSERT INTO composer_sms_operations(" +
                    "local_operation_id,provider_thread_id,draft_id,draft_revision_ms," +
                    "subscription_id,transport_code,phase_code,provider_message_id," +
                    "provider_conversation_id,unit_count,created_timestamp_ms," +
                    "updated_timestamp_ms,signature_text) " +
                    "VALUES(3,11,21,31,0,'mms_v1','reserved_v1',NULL,NULL,NULL,41,41,NULL)",
            )
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    "INSERT INTO composer_sms_operations(" +
                        "local_operation_id,provider_thread_id,draft_id,draft_revision_ms," +
                        "subscription_id,transport_code,phase_code,provider_message_id," +
                        "provider_conversation_id,unit_count,created_timestamp_ms," +
                        "updated_timestamp_ms,signature_text) " +
                        "VALUES(4,12,22,32,0,'invalid','reserved_v1',NULL,NULL,NULL,42,42,NULL)",
                )
            }
        }
    }

    companion object { private const val DATABASE_NAME = "aurora-state-migration-12-13-test.db" }
}
