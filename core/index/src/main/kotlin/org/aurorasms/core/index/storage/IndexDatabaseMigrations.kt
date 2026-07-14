// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val INDEX_MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `indexed_conversations` (
                `provider_thread_id` INTEGER NOT NULL,
                `latest_row_id` INTEGER NOT NULL,
                `latest_provider_kind` INTEGER NOT NULL,
                `latest_provider_id` INTEGER NOT NULL,
                `latest_timestamp_ms` INTEGER NOT NULL,
                `latest_sent_timestamp_ms` INTEGER,
                `latest_direction` INTEGER NOT NULL,
                `latest_message_box` TEXT NOT NULL,
                `latest_message_status` TEXT NOT NULL,
                `latest_subscription_id` INTEGER,
                `latest_sender_address` TEXT,
                `latest_snippet` TEXT,
                `latest_attachment_count` INTEGER NOT NULL,
                `latest_attachment_type_summary` TEXT NOT NULL,
                `latest_is_read` INTEGER NOT NULL,
                `indexed_message_count` INTEGER NOT NULL,
                `indexed_unread_count` INTEGER NOT NULL,
                `indexed_participant_count` INTEGER NOT NULL,
                `participants_truncated` INTEGER NOT NULL,
                `last_seen_generation` INTEGER NOT NULL,
                PRIMARY KEY(`provider_thread_id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
                `index_indexed_conversations_last_seen_generation_latest_timestamp_ms_latest_row_id`
            ON `indexed_conversations`
                (`last_seen_generation` ASC, `latest_timestamp_ms` DESC, `latest_row_id` DESC)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
                `index_indexed_conversations_latest_provider_kind_latest_provider_id`
            ON `indexed_conversations` (`latest_provider_kind`, `latest_provider_id`)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `indexed_conversation_participants` (
                `provider_thread_id` INTEGER NOT NULL,
                `address` TEXT NOT NULL,
                `last_seen_generation` INTEGER NOT NULL,
                PRIMARY KEY(`provider_thread_id`, `address`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
                `index_indexed_conversation_participants_last_seen_generation_provider_thread_id_address`
            ON `indexed_conversation_participants`
                (`last_seen_generation`, `provider_thread_id`, `address`)
            """.trimIndent(),
        )

        // No v1 generation may resume beyond an old checkpoint and claim v2
        // participant completeness. Existing message and FTS rows stay intact.
        db.execSQL(
            """
            UPDATE `index_generations`
            SET `state` = ${GenerationStateCode.PAUSED},
                `completed_at_ms` = NULL,
                `pending_changes` = 1,
                `failure_code` = NULL,
                `signal_sequence` = `signal_sequence` + 1
            """.trimIndent(),
        )
    }
}

/**
 * Invalidates v2 participant-completeness projections before scoped appearance can trust them.
 *
 * The physical index remains rebuildable and searchable, but no pre-v3 generation may retain a
 * verified-complete claim. A pending paused latest generation makes the coordinator start a fresh
 * scan from empty checkpoints instead of resuming any v2 cursor.
 */
val INDEX_MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            UPDATE `index_generations`
            SET `state` = ${GenerationStateCode.PAUSED},
                `completed_at_ms` = NULL,
                `pending_changes` = 1,
                `failure_code` = NULL,
                `signal_sequence` = `signal_sequence` + 1
            """.trimIndent(),
        )
    }
}
