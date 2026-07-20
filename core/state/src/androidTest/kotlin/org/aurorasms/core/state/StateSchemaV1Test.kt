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
import org.aurorasms.core.state.storage.AppearanceSelectionEnforcement
import org.aurorasms.core.state.storage.AppearanceOverrideSequenceEnforcement
import org.aurorasms.core.state.storage.ComposerSmsOperationEnforcement
import org.aurorasms.core.state.storage.ConversationSubscriptionPreferenceEnforcement
import org.aurorasms.core.state.storage.DraftIdentityEnforcement
import org.aurorasms.core.state.storage.ScheduledSmsEnforcement
import org.aurorasms.core.state.storage.SendDelayEnforcement
import org.aurorasms.core.state.storage.PermanentDeletionEnforcement
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
class StateSchemaCurrentTest {
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
    fun schemaVersionThirteen_hasBoundedStateTablesAndComposerTransportOwnership() {
        val database = openStateDatabase()
        val sqlite = database.openHelper.writableDatabase
        try {
            assertEquals(13, AuroraStateDatabase.VERSION)
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
            assertTrue(
                sqlite.query(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'appearance_profiles'",
                ).use { it.moveToFirst() },
            )
            assertEquals(
                setOf("screen_code", "profile_id", "revision"),
                sqlite.tableColumns("appearance_screen_overrides"),
            )
            assertEquals(
                setOf("participant_set_key", "provider_thread_id", "profile_id", "revision"),
                sqlite.tableColumns("appearance_conversation_overrides"),
            )
            assertTrue(
                sqlite.indexNames("appearance_screen_overrides")
                    .contains("index_appearance_screen_overrides_profile_id"),
            )
            assertTrue(
                sqlite.indexNames("appearance_conversation_overrides")
                    .contains("index_appearance_conversation_overrides_provider_thread_id"),
            )
            assertFalse(
                sqlite.indexIsUnique(
                    "appearance_conversation_overrides",
                    "index_appearance_conversation_overrides_provider_thread_id",
                ),
            )
            assertTrue(
                sqlite.indexNames("appearance_conversation_overrides")
                    .contains("index_appearance_conversation_overrides_profile_id"),
            )
            assertTrue(
                sqlite.query(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'appearance_screen_overrides'",
                ).use { it.moveToFirst() },
            )
            assertTrue(
                sqlite.query(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'appearance_conversation_overrides'",
                ).use { it.moveToFirst() },
            )
            assertTrue(
                sqlite.query(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'appearance_override_revision_sequence'",
                ).use { it.moveToFirst() },
            )
            assertEquals(
                setOf("singleton_id", "last_allocated_revision"),
                sqlite.tableColumns("appearance_override_revision_sequence"),
            )
            assertEquals(
                setOf(
                    "screen_code",
                    "media_kind_code",
                    "media_id",
                    "dim_permill",
                    "focal_x_permill",
                    "focal_y_permill",
                    "revision",
                ),
                sqlite.tableColumns("appearance_screen_wallpapers"),
            )
            assertEquals(
                setOf(
                    "participant_set_key",
                    "provider_thread_id",
                    "media_kind_code",
                    "media_id",
                    "dim_permill",
                    "focal_x_permill",
                    "focal_y_permill",
                    "revision",
                ),
                sqlite.tableColumns("appearance_conversation_wallpapers"),
            )
            assertTrue(
                sqlite.indexNames("appearance_screen_wallpapers")
                    .contains("index_appearance_screen_wallpapers_media_kind_code_media_id"),
            )
            assertTrue(
                sqlite.indexNames("appearance_conversation_wallpapers")
                    .contains("index_appearance_conversation_wallpapers_provider_thread_id"),
            )
            assertTrue(
                sqlite.indexNames("appearance_conversation_wallpapers")
                    .contains(
                        "index_appearance_conversation_wallpapers_media_kind_code_media_id",
                    ),
            )
            assertTrue(
                sqlite.query(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'appearance_selection'",
                ).use { it.moveToFirst() },
            )
            assertEquals(
                1L,
                sqlite.query("SELECT COUNT(*) FROM appearance_selection").use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getLong(0)
                },
            )
            assertEquals(
                1L,
                sqlite.query("SELECT COUNT(*) FROM appearance_override_revision_sequence")
                    .use { cursor ->
                        check(cursor.moveToFirst())
                        cursor.getLong(0)
                    },
            )
            assertEquals(
                0L,
                sqlite.query(
                    "SELECT last_allocated_revision FROM appearance_override_revision_sequence",
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getLong(0)
                },
            )
            assertEquals(
                setOf(
                    "local_operation_id",
                    "provider_thread_id",
                    "draft_id",
                    "draft_revision_ms",
                    "subscription_id",
                    "transport_code",
                    "phase_code",
                    "provider_message_id",
                    "provider_conversation_id",
                    "unit_count",
                    "signature_text",
                    "created_timestamp_ms",
                    "updated_timestamp_ms",
                ),
                sqlite.tableColumns("composer_sms_operations"),
            )
            assertTrue(
                sqlite.indexNames("composer_sms_operations")
                    .contains("index_composer_sms_operations_provider_thread_id"),
            )
            assertTrue(
                sqlite.indexIsUnique(
                    "composer_sms_operations",
                    "index_composer_sms_operations_provider_thread_id",
                ),
            )
            assertTrue(
                sqlite.indexIsUnique(
                    "composer_sms_operations",
                    "index_composer_sms_operations_transport_code_provider_message_id",
                ),
            )
            assertEquals(
                setOf(
                    "local_operation_id",
                    "provider_message_id",
                    "provider_kind_code",
                    "provider_conversation_id",
                    "unit_count",
                    "callback_proof_code",
                    "acknowledged_timestamp_ms",
                    "updated_timestamp_ms",
                ),
                sqlite.tableColumns("acknowledged_composer_sms_receipts"),
            )
            assertTrue(
                sqlite.indexIsUnique(
                    "acknowledged_composer_sms_receipts",
                    "index_acknowledged_composer_sms_receipts_provider_kind_code_provider_message_id",
                ),
            )
            assertEquals(
                setOf(
                    "participant_set_key",
                    "provider_thread_id",
                    "subscription_id",
                    "revision",
                    "updated_timestamp_ms",
                ),
                sqlite.tableColumns("conversation_subscription_preferences"),
            )
            assertTrue(
                sqlite.indexNames("conversation_subscription_preferences")
                    .contains(
                        "index_conversation_subscription_preferences_provider_thread_id",
                    ),
            )
            assertEquals(
                setOf(
                    "schedule_id",
                    "participant_set_key",
                    "provider_thread_id",
                    "draft_id",
                    "draft_revision_ms",
                    "subscription_id",
                    "due_timestamp_ms",
                    "phase_code",
                    "precision_code",
                    "review_reason_code",
                    "signature_text",
                    "armed_wall_timestamp_ms",
                    "armed_elapsed_realtime_ms",
                    "created_timestamp_ms",
                    "updated_timestamp_ms",
                ),
                sqlite.tableColumns("scheduled_sms_operations"),
            )
            assertTrue(
                sqlite.indexIsUnique(
                    "scheduled_sms_operations",
                    "index_scheduled_sms_operations_provider_thread_id",
                ),
            )
            assertTrue(
                sqlite.indexIsUnique(
                    "scheduled_sms_operations",
                    "index_scheduled_sms_operations_draft_id",
                ),
            )
            assertTrue(
                sqlite.indexNames("scheduled_sms_operations")
                    .contains("index_scheduled_sms_operations_due_timestamp_ms_schedule_id"),
            )
            assertEquals(
                setOf(
                    "send_delay_id",
                    "participant_set_key",
                    "provider_thread_id",
                    "draft_id",
                    "draft_revision_ms",
                    "subscription_id",
                    "due_timestamp_ms",
                    "phase_code",
                    "review_reason_code",
                    "signature_text",
                    "armed_wall_timestamp_ms",
                    "armed_elapsed_realtime_ms",
                    "created_timestamp_ms",
                    "updated_timestamp_ms",
                ),
                sqlite.tableColumns("send_delay_operations"),
            )
            assertTrue(
                sqlite.indexIsUnique(
                    "send_delay_operations",
                    "index_send_delay_operations_provider_thread_id",
                ),
            )
            assertTrue(
                sqlite.indexIsUnique(
                    "send_delay_operations",
                    "index_send_delay_operations_draft_id",
                ),
            )
            assertTrue(
                sqlite.indexNames("send_delay_operations")
                    .contains("index_send_delay_operations_due_timestamp_ms_send_delay_id"),
            )
            assertEquals(
                setOf(
                    "deletion_id", "target_kind_code", "provider_thread_id", "provider_kind",
                    "provider_message_id", "sync_fingerprint", "sms_count", "latest_sms_id",
                    "mms_count", "latest_mms_id", "draft_id", "draft_revision_ms",
                    "due_timestamp_ms", "phase_code", "review_reason_code",
                    "armed_wall_timestamp_ms", "armed_elapsed_realtime_ms",
                    "created_timestamp_ms", "updated_timestamp_ms",
                ),
                sqlite.tableColumns("permanent_deletion_operations"),
            )
            assertTrue(
                sqlite.indexIsUnique(
                    "permanent_deletion_operations",
                    "index_permanent_deletion_operations_provider_thread_id",
                ),
            )
            assertTrue(
                sqlite.indexIsUnique(
                    "permanent_deletion_operations",
                    "index_permanent_deletion_operations_provider_kind_provider_message_id",
                ),
            )
            assertTrue(
                sqlite.indexNames("permanent_deletion_operations").contains(
                    "index_permanent_deletion_operations_due_timestamp_ms_deletion_id",
                ),
            )
            assertEquals(
                setOf(
                    "participant_set_key", "provider_thread_id", "single_sender_key",
                    "classification_code", "blocked", "revision", "updated_timestamp_ms",
                ),
                sqlite.tableColumns("spam_safety_decisions"),
            )
            assertTrue(
                sqlite.indexIsUnique(
                    "spam_safety_decisions",
                    "index_spam_safety_decisions_provider_thread_id",
                ),
            )
            assertTrue(
                sqlite.indexNames("spam_safety_decisions")
                    .contains("index_spam_safety_decisions_single_sender_key"),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun exportedVersionTwelveStructureValidatesWithoutRepairingMissingSemanticSelection() {
        migrationHelper.createDatabase(MIGRATION_DATABASE_NAME, AuroraStateDatabase.VERSION).use { sqlite ->
            assertEquals(AuroraStateDatabase.VERSION, sqlite.version)
            assertTrue(
                sqlite.query(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'drafts'",
                ).use { it.moveToFirst() },
            )
            assertTrue(
                sqlite.query(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'spam_safety_decisions'",
                ).use { it.moveToFirst() },
            )
            assertTrue(
                sqlite.query(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'permanent_deletion_operations'",
                ).use { it.moveToFirst() },
            )
            assertTrue(
                sqlite.query(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'composer_sms_operations'",
                ).use { it.moveToFirst() },
            )
            assertTrue(
                sqlite.query(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'appearance_profiles'",
                ).use { it.moveToFirst() },
            )
            assertTrue(
                sqlite.query(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'appearance_screen_overrides'",
                ).use { it.moveToFirst() },
            )
            assertTrue(
                sqlite.query(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'appearance_conversation_overrides'",
                ).use { it.moveToFirst() },
            )
            assertTrue(
                sqlite.query(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'appearance_override_revision_sequence'",
                ).use { it.moveToFirst() },
            )
            assertTrue(
                sqlite.query(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'appearance_screen_wallpapers'",
                ).use { it.moveToFirst() },
            )
            assertTrue(
                sqlite.query(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'appearance_conversation_wallpapers'",
                ).use { it.moveToFirst() },
            )
            assertTrue(
                sqlite.query(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'conversation_subscription_preferences'",
                ).use { it.moveToFirst() },
            )
            assertTrue(
                sqlite.query(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'scheduled_sms_operations'",
                ).use { it.moveToFirst() },
            )
            assertTrue(
                sqlite.query(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'send_delay_operations'",
                ).use { it.moveToFirst() },
            )
        }

        val database = Room.databaseBuilder(
            context,
            AuroraStateDatabase::class.java,
            MIGRATION_DATABASE_NAME,
        )
            .addCallback(DraftIdentityEnforcement.callback)
            .addCallback(AppearanceSelectionEnforcement.callback)
            .addCallback(AppearanceOverrideSequenceEnforcement.callback)
            .addCallback(ComposerSmsOperationEnforcement.callback)
            .addCallback(ConversationSubscriptionPreferenceEnforcement.callback)
            .addCallback(ScheduledSmsEnforcement.callback)
            .addCallback(SendDelayEnforcement.callback)
            .addCallback(PermanentDeletionEnforcement.callback)
            .build()
        try {
            assertEquals(AuroraStateDatabase.VERSION, database.openHelper.writableDatabase.version)
            assertEquals(
                0L,
                database.openHelper.writableDatabase
                    .query("SELECT COUNT(*) FROM appearance_selection")
                    .use { cursor ->
                        check(cursor.moveToFirst())
                        cursor.getLong(0)
                    },
            )
            assertEquals(
                0L,
                database.openHelper.writableDatabase
                    .query("SELECT COUNT(*) FROM appearance_override_revision_sequence")
                    .use { cursor ->
                        check(cursor.moveToFirst())
                        cursor.getLong(0)
                    },
            )
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
        assertTriggerExists(sqlite, AppearanceSelectionEnforcement.INSERT_TRIGGER_NAME)
        assertTriggerExists(sqlite, AppearanceSelectionEnforcement.UPDATE_TRIGGER_NAME)
        assertTriggerExists(sqlite, AppearanceOverrideSequenceEnforcement.INSERT_TRIGGER_NAME)
        assertTriggerExists(sqlite, AppearanceOverrideSequenceEnforcement.UPDATE_TRIGGER_NAME)
        assertTriggerExists(sqlite, AppearanceOverrideSequenceEnforcement.DELETE_TRIGGER_NAME)
        assertTriggerExists(sqlite, ComposerSmsOperationEnforcement.INSERT_LIMIT_TRIGGER_NAME)
        assertTriggerExists(sqlite, ComposerSmsOperationEnforcement.INSERT_INTEGRITY_TRIGGER_NAME)
        assertTriggerExists(sqlite, ComposerSmsOperationEnforcement.UPDATE_INTEGRITY_TRIGGER_NAME)
        assertTriggerExists(
            sqlite,
            ConversationSubscriptionPreferenceEnforcement.INSERT_INTEGRITY_TRIGGER_NAME,
        )
        assertTriggerExists(
            sqlite,
            ConversationSubscriptionPreferenceEnforcement.UPDATE_INTEGRITY_TRIGGER_NAME,
        )
        assertTriggerExists(sqlite, ScheduledSmsEnforcement.INSERT_LIMIT_TRIGGER)
        assertTriggerExists(sqlite, ScheduledSmsEnforcement.INSERT_INTEGRITY_TRIGGER)
        assertTriggerExists(sqlite, ScheduledSmsEnforcement.UPDATE_INTEGRITY_TRIGGER)
        assertTriggerExists(sqlite, SendDelayEnforcement.INSERT_LIMIT_TRIGGER_NAME)
        assertTriggerExists(sqlite, SendDelayEnforcement.INSERT_INTEGRITY_TRIGGER_NAME)
        assertTriggerExists(sqlite, SendDelayEnforcement.UPDATE_INTEGRITY_TRIGGER_NAME)
        assertTriggerExists(sqlite, PermanentDeletionEnforcement.INSERT_LIMIT_TRIGGER_NAME)
        assertTriggerExists(sqlite, PermanentDeletionEnforcement.INSERT_INTEGRITY_TRIGGER_NAME)
        assertTriggerExists(sqlite, PermanentDeletionEnforcement.UPDATE_INTEGRITY_TRIGGER_NAME)

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
        sqlite.execSQL("DROP TRIGGER ${AppearanceSelectionEnforcement.INSERT_TRIGGER_NAME}")
        sqlite.execSQL("DROP TRIGGER ${AppearanceSelectionEnforcement.UPDATE_TRIGGER_NAME}")
        sqlite.execSQL("DROP TRIGGER ${AppearanceOverrideSequenceEnforcement.INSERT_TRIGGER_NAME}")
        sqlite.execSQL("DROP TRIGGER ${AppearanceOverrideSequenceEnforcement.UPDATE_TRIGGER_NAME}")
        sqlite.execSQL("DROP TRIGGER ${AppearanceOverrideSequenceEnforcement.DELETE_TRIGGER_NAME}")
        sqlite.execSQL("DROP TRIGGER ${ComposerSmsOperationEnforcement.INSERT_LIMIT_TRIGGER_NAME}")
        sqlite.execSQL("DROP TRIGGER ${ComposerSmsOperationEnforcement.INSERT_INTEGRITY_TRIGGER_NAME}")
        sqlite.execSQL("DROP TRIGGER ${ComposerSmsOperationEnforcement.UPDATE_INTEGRITY_TRIGGER_NAME}")
        sqlite.execSQL(
            "DROP TRIGGER " +
                ConversationSubscriptionPreferenceEnforcement.INSERT_INTEGRITY_TRIGGER_NAME,
        )
        sqlite.execSQL(
            "DROP TRIGGER " +
                ConversationSubscriptionPreferenceEnforcement.UPDATE_INTEGRITY_TRIGGER_NAME,
        )
        sqlite.execSQL("DROP TRIGGER ${ScheduledSmsEnforcement.INSERT_LIMIT_TRIGGER}")
        sqlite.execSQL("DROP TRIGGER ${ScheduledSmsEnforcement.INSERT_INTEGRITY_TRIGGER}")
        sqlite.execSQL("DROP TRIGGER ${ScheduledSmsEnforcement.UPDATE_INTEGRITY_TRIGGER}")
        sqlite.execSQL("DROP TRIGGER ${SendDelayEnforcement.INSERT_LIMIT_TRIGGER_NAME}")
        sqlite.execSQL("DROP TRIGGER ${SendDelayEnforcement.INSERT_INTEGRITY_TRIGGER_NAME}")
        sqlite.execSQL("DROP TRIGGER ${SendDelayEnforcement.UPDATE_INTEGRITY_TRIGGER_NAME}")
        sqlite.execSQL("DROP TRIGGER ${PermanentDeletionEnforcement.INSERT_LIMIT_TRIGGER_NAME}")
        sqlite.execSQL("DROP TRIGGER ${PermanentDeletionEnforcement.INSERT_INTEGRITY_TRIGGER_NAME}")
        sqlite.execSQL("DROP TRIGGER ${PermanentDeletionEnforcement.UPDATE_INTEGRITY_TRIGGER_NAME}")
        database.close()

        database = openStateDatabase()
        try {
            sqlite = database.openHelper.writableDatabase
            assertTriggerExists(sqlite, DraftIdentityEnforcement.INSERT_TRIGGER_NAME)
            assertTriggerExists(sqlite, DraftIdentityEnforcement.UPDATE_TRIGGER_NAME)
            assertTriggerExists(sqlite, AppearanceSelectionEnforcement.INSERT_TRIGGER_NAME)
            assertTriggerExists(sqlite, AppearanceSelectionEnforcement.UPDATE_TRIGGER_NAME)
            assertTriggerExists(sqlite, AppearanceOverrideSequenceEnforcement.INSERT_TRIGGER_NAME)
            assertTriggerExists(sqlite, AppearanceOverrideSequenceEnforcement.UPDATE_TRIGGER_NAME)
            assertTriggerExists(sqlite, AppearanceOverrideSequenceEnforcement.DELETE_TRIGGER_NAME)
            assertTriggerExists(sqlite, ComposerSmsOperationEnforcement.INSERT_LIMIT_TRIGGER_NAME)
            assertTriggerExists(sqlite, ComposerSmsOperationEnforcement.INSERT_INTEGRITY_TRIGGER_NAME)
            assertTriggerExists(sqlite, ComposerSmsOperationEnforcement.UPDATE_INTEGRITY_TRIGGER_NAME)
            assertTriggerExists(
                sqlite,
                ConversationSubscriptionPreferenceEnforcement.INSERT_INTEGRITY_TRIGGER_NAME,
            )
            assertTriggerExists(
                sqlite,
                ConversationSubscriptionPreferenceEnforcement.UPDATE_INTEGRITY_TRIGGER_NAME,
            )
            assertTriggerExists(sqlite, ScheduledSmsEnforcement.INSERT_LIMIT_TRIGGER)
            assertTriggerExists(sqlite, ScheduledSmsEnforcement.INSERT_INTEGRITY_TRIGGER)
            assertTriggerExists(sqlite, ScheduledSmsEnforcement.UPDATE_INTEGRITY_TRIGGER)
            assertTriggerExists(sqlite, SendDelayEnforcement.INSERT_LIMIT_TRIGGER_NAME)
            assertTriggerExists(sqlite, SendDelayEnforcement.INSERT_INTEGRITY_TRIGGER_NAME)
            assertTriggerExists(sqlite, SendDelayEnforcement.UPDATE_INTEGRITY_TRIGGER_NAME)
            assertTriggerExists(sqlite, PermanentDeletionEnforcement.INSERT_LIMIT_TRIGGER_NAME)
            assertTriggerExists(sqlite, PermanentDeletionEnforcement.INSERT_INTEGRITY_TRIGGER_NAME)
            assertTriggerExists(sqlite, PermanentDeletionEnforcement.UPDATE_INTEGRITY_TRIGGER_NAME)
            assertThrows(SQLiteConstraintException::class.java) {
                sqlite.execSQL(
                    "INSERT INTO appearance_selection(" +
                        "singleton_id, active_profile_id, snapshot_revision" +
                        ") VALUES (2, NULL, 1)",
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                sqlite.execSQL(
                    "INSERT INTO appearance_override_revision_sequence(" +
                        "singleton_id, last_allocated_revision" +
                        ") VALUES (2, 0)",
                )
            }
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

    private fun androidx.sqlite.db.SupportSQLiteDatabase.tableColumns(table: String): Set<String> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameColumn))
            }
        }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.indexNames(table: String): Set<String> =
        query("PRAGMA index_list(`$table`)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameColumn))
            }
        }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.indexIsUnique(
        table: String,
        index: String,
    ): Boolean = query("PRAGMA index_list(`$table`)").use { cursor ->
        val nameColumn = cursor.getColumnIndexOrThrow("name")
        val uniqueColumn = cursor.getColumnIndexOrThrow("unique")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameColumn) == index) return@use cursor.getInt(uniqueColumn) != 0
        }
        error("Missing expected index")
    }

    companion object {
        private const val MIGRATION_DATABASE_NAME: String = "aurora-state-schema-v1-test.db"
    }
}
