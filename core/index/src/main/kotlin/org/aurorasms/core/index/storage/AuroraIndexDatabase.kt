// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.storage

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        IndexedMessageEntity::class,
        IndexedMessageFtsEntity::class,
        IndexGenerationEntity::class,
        IndexCheckpointEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AuroraIndexDatabase : RoomDatabase() {
    abstract fun indexedMessageDao(): IndexedMessageDao

    abstract fun indexSyncDao(): IndexSyncDao
}
