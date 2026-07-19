// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val STATE_MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `appearance_profiles` (
                `profile_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `normalized_name` TEXT NOT NULL,
                `profile_schema_version` INTEGER NOT NULL,
                `palette_code` TEXT NOT NULL,
                `hue_degrees` INTEGER NOT NULL,
                `row_density_code` TEXT NOT NULL,
                `avatar_mask_code` TEXT NOT NULL,
                `navigation_style_code` TEXT NOT NULL,
                `bubble_geometry_code` TEXT NOT NULL,
                `reduced_motion` INTEGER NOT NULL,
                `high_contrast` INTEGER NOT NULL,
                `wallpaper_dim_permill` INTEGER NOT NULL,
                `focal_x_permill` INTEGER NOT NULL,
                `focal_y_permill` INTEGER NOT NULL,
                `revision` INTEGER NOT NULL,
                `created_timestamp_ms` INTEGER NOT NULL,
                `updated_timestamp_ms` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS `index_appearance_profiles_normalized_name`
            ON `appearance_profiles` (`normalized_name`)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `appearance_selection` (
                `singleton_id` INTEGER NOT NULL,
                `active_profile_id` INTEGER,
                `snapshot_revision` INTEGER NOT NULL,
                PRIMARY KEY(`singleton_id`),
                FOREIGN KEY(`active_profile_id`) REFERENCES `appearance_profiles`(`profile_id`)
                    ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_appearance_selection_active_profile_id`
            ON `appearance_selection` (`active_profile_id`)
            """.trimIndent(),
        )
        db.execSQL(AppearanceSelectionEnforcement.CREATE_INSERT_TRIGGER)
        db.execSQL(AppearanceSelectionEnforcement.CREATE_UPDATE_TRIGGER)
        db.execSQL(AppearanceSelectionEnforcement.INSERT_DEFAULT_SELECTION)
    }
}

val STATE_MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `appearance_override_revision_sequence` (
                `singleton_id` INTEGER NOT NULL,
                `last_allocated_revision` INTEGER NOT NULL,
                PRIMARY KEY(`singleton_id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `appearance_screen_overrides` (
                `screen_code` TEXT NOT NULL,
                `profile_id` INTEGER NOT NULL,
                `revision` INTEGER NOT NULL,
                PRIMARY KEY(`screen_code`),
                FOREIGN KEY(`profile_id`) REFERENCES `appearance_profiles`(`profile_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_appearance_screen_overrides_profile_id`
            ON `appearance_screen_overrides` (`profile_id`)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `appearance_conversation_overrides` (
                `participant_set_key` TEXT NOT NULL,
                `provider_thread_id` INTEGER NOT NULL,
                `profile_id` INTEGER NOT NULL,
                `revision` INTEGER NOT NULL,
                PRIMARY KEY(`participant_set_key`),
                FOREIGN KEY(`profile_id`) REFERENCES `appearance_profiles`(`profile_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_appearance_conversation_overrides_provider_thread_id`
            ON `appearance_conversation_overrides` (`provider_thread_id`)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_appearance_conversation_overrides_profile_id`
            ON `appearance_conversation_overrides` (`profile_id`)
            """.trimIndent(),
        )
        db.execSQL(AppearanceOverrideSequenceEnforcement.CREATE_INSERT_TRIGGER)
        db.execSQL(AppearanceOverrideSequenceEnforcement.CREATE_UPDATE_TRIGGER)
        db.execSQL(AppearanceOverrideSequenceEnforcement.CREATE_DELETE_TRIGGER)
        db.execSQL(AppearanceOverrideSequenceEnforcement.INSERT_INITIAL_SEQUENCE)
    }
}

val STATE_MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `appearance_screen_wallpapers` (
                `screen_code` TEXT NOT NULL,
                `media_kind_code` TEXT NOT NULL,
                `media_id` TEXT NOT NULL,
                `dim_permill` INTEGER NOT NULL,
                `focal_x_permill` INTEGER NOT NULL,
                `focal_y_permill` INTEGER NOT NULL,
                `revision` INTEGER NOT NULL,
                PRIMARY KEY(`screen_code`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_appearance_screen_wallpapers_media_kind_code_media_id`
            ON `appearance_screen_wallpapers` (`media_kind_code`, `media_id`)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `appearance_conversation_wallpapers` (
                `participant_set_key` TEXT NOT NULL,
                `provider_thread_id` INTEGER NOT NULL,
                `media_kind_code` TEXT NOT NULL,
                `media_id` TEXT NOT NULL,
                `dim_permill` INTEGER NOT NULL,
                `focal_x_permill` INTEGER NOT NULL,
                `focal_y_permill` INTEGER NOT NULL,
                `revision` INTEGER NOT NULL,
                PRIMARY KEY(`participant_set_key`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_appearance_conversation_wallpapers_provider_thread_id`
            ON `appearance_conversation_wallpapers` (`provider_thread_id`)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_appearance_conversation_wallpapers_media_kind_code_media_id`
            ON `appearance_conversation_wallpapers` (`media_kind_code`, `media_id`)
            """.trimIndent(),
        )
    }
}

val STATE_MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `composer_sms_operations` (
                `local_operation_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `provider_thread_id` INTEGER NOT NULL,
                `draft_id` INTEGER NOT NULL,
                `draft_revision_ms` INTEGER NOT NULL,
                `subscription_id` INTEGER NOT NULL,
                `phase_code` TEXT NOT NULL,
                `provider_message_id` INTEGER,
                `provider_conversation_id` INTEGER,
                `unit_count` INTEGER,
                `created_timestamp_ms` INTEGER NOT NULL,
                `updated_timestamp_ms` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS `index_composer_sms_operations_provider_thread_id`
            ON `composer_sms_operations` (`provider_thread_id`)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS `index_composer_sms_operations_provider_message_id`
            ON `composer_sms_operations` (`provider_message_id`)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_composer_sms_operations_draft_id_draft_revision_ms`
            ON `composer_sms_operations` (`draft_id`, `draft_revision_ms`)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_composer_sms_operations_updated_timestamp_ms_local_operation_id`
            ON `composer_sms_operations` (`updated_timestamp_ms`, `local_operation_id`)
            """.trimIndent(),
        )
        db.execSQL(ComposerSmsOperationEnforcement.CREATE_INSERT_LIMIT_TRIGGER)
        db.execSQL(ComposerSmsOperationEnforcement.CREATE_INSERT_INTEGRITY_TRIGGER)
        db.execSQL(ComposerSmsOperationEnforcement.CREATE_UPDATE_INTEGRITY_TRIGGER)
    }
}

val STATE_MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `acknowledged_composer_sms_receipts` (
                `local_operation_id` INTEGER NOT NULL,
                `provider_message_id` INTEGER NOT NULL,
                `provider_conversation_id` INTEGER NOT NULL,
                `unit_count` INTEGER NOT NULL,
                `callback_proof_code` TEXT NOT NULL,
                `acknowledged_timestamp_ms` INTEGER NOT NULL,
                `updated_timestamp_ms` INTEGER NOT NULL,
                PRIMARY KEY(`local_operation_id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS
            `index_acknowledged_composer_sms_receipts_provider_message_id`
            ON `acknowledged_composer_sms_receipts` (`provider_message_id`)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_acknowledged_composer_sms_receipts_updated_timestamp_ms_local_operation_id`
            ON `acknowledged_composer_sms_receipts` (`updated_timestamp_ms`, `local_operation_id`)
            """.trimIndent(),
        )
        db.execSQL(AcknowledgedComposerSmsEnforcement.CREATE_INSERT_LIMIT_TRIGGER)
        db.execSQL(AcknowledgedComposerSmsEnforcement.CREATE_INSERT_INTEGRITY_TRIGGER)
        db.execSQL(AcknowledgedComposerSmsEnforcement.CREATE_UPDATE_INTEGRITY_TRIGGER)
    }
}

val STATE_MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `conversation_subscription_preferences` (
                `participant_set_key` TEXT NOT NULL,
                `provider_thread_id` INTEGER NOT NULL,
                `subscription_id` INTEGER NOT NULL,
                `revision` INTEGER NOT NULL,
                `updated_timestamp_ms` INTEGER NOT NULL,
                PRIMARY KEY(`participant_set_key`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_conversation_subscription_preferences_provider_thread_id`
            ON `conversation_subscription_preferences` (`provider_thread_id`)
            """.trimIndent(),
        )
        db.execSQL(ConversationSubscriptionPreferenceEnforcement.CREATE_INSERT_INTEGRITY_TRIGGER)
        db.execSQL(ConversationSubscriptionPreferenceEnforcement.CREATE_UPDATE_INTEGRITY_TRIGGER)
    }
}

val STATE_MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `scheduled_sms_operations` (
                `schedule_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `participant_set_key` TEXT NOT NULL,
                `provider_thread_id` INTEGER NOT NULL,
                `draft_id` INTEGER NOT NULL,
                `draft_revision_ms` INTEGER NOT NULL,
                `subscription_id` INTEGER NOT NULL,
                `due_timestamp_ms` INTEGER NOT NULL,
                `phase_code` TEXT NOT NULL,
                `precision_code` TEXT NOT NULL,
                `review_reason_code` TEXT,
                `armed_wall_timestamp_ms` INTEGER NOT NULL,
                `armed_elapsed_realtime_ms` INTEGER NOT NULL,
                `created_timestamp_ms` INTEGER NOT NULL,
                `updated_timestamp_ms` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_scheduled_sms_operations_provider_thread_id` " +
                "ON `scheduled_sms_operations` (`provider_thread_id`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_scheduled_sms_operations_draft_id` " +
                "ON `scheduled_sms_operations` (`draft_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_scheduled_sms_operations_due_timestamp_ms_schedule_id` " +
                "ON `scheduled_sms_operations` (`due_timestamp_ms`, `schedule_id`)",
        )
        db.execSQL(ScheduledSmsEnforcement.CREATE_INSERT_LIMIT_TRIGGER)
        db.execSQL(ScheduledSmsEnforcement.CREATE_INSERT_INTEGRITY_TRIGGER)
        db.execSQL(ScheduledSmsEnforcement.CREATE_UPDATE_INTEGRITY_TRIGGER)
    }
}

val STATE_MIGRATION_8_9: Migration = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `send_delay_operations` (
                `send_delay_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `participant_set_key` TEXT NOT NULL,
                `provider_thread_id` INTEGER NOT NULL,
                `draft_id` INTEGER NOT NULL,
                `draft_revision_ms` INTEGER NOT NULL,
                `subscription_id` INTEGER NOT NULL,
                `due_timestamp_ms` INTEGER NOT NULL,
                `phase_code` TEXT NOT NULL,
                `review_reason_code` TEXT,
                `armed_wall_timestamp_ms` INTEGER NOT NULL,
                `armed_elapsed_realtime_ms` INTEGER NOT NULL,
                `created_timestamp_ms` INTEGER NOT NULL,
                `updated_timestamp_ms` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_send_delay_operations_provider_thread_id` " +
                "ON `send_delay_operations` (`provider_thread_id`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_send_delay_operations_draft_id` " +
                "ON `send_delay_operations` (`draft_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_send_delay_operations_due_timestamp_ms_send_delay_id` " +
                "ON `send_delay_operations` (`due_timestamp_ms`, `send_delay_id`)",
        )
        db.execSQL(SendDelayEnforcement.CREATE_INSERT_LIMIT_TRIGGER)
        db.execSQL(SendDelayEnforcement.CREATE_INSERT_INTEGRITY_TRIGGER)
        db.execSQL(SendDelayEnforcement.CREATE_UPDATE_INTEGRITY_TRIGGER)
    }
}
