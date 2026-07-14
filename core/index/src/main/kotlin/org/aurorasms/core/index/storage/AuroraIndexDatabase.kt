// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.storage

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        IndexedMessageEntity::class,
        IndexedMessageFtsEntity::class,
        IndexedConversationEntity::class,
        IndexedConversationParticipantEntity::class,
        IndexGenerationEntity::class,
        IndexCheckpointEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AuroraIndexDatabase : RoomDatabase() {
    abstract fun indexedMessageDao(): IndexedMessageDao

    abstract fun conversationDao(): ConversationDao

    abstract fun indexSyncDao(): IndexSyncDao
}
