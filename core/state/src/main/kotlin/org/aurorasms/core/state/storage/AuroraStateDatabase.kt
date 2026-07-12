// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [DraftEntity::class],
    version = AuroraStateDatabase.VERSION,
    exportSchema = true,
)
abstract class AuroraStateDatabase : RoomDatabase() {
    internal abstract fun draftDao(): DraftDao

    companion object {
        const val VERSION: Int = 1
    }
}
