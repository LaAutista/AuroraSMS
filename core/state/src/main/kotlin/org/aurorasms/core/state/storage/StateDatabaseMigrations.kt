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
