// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        DraftEntity::class,
        AppearanceProfileEntity::class,
        AppearanceSelectionEntity::class,
        AppearanceScreenOverrideEntity::class,
        AppearanceConversationOverrideEntity::class,
        AppearanceScreenWallpaperEntity::class,
        AppearanceConversationWallpaperEntity::class,
        AppearanceOverrideSequenceEntity::class,
        ComposerSmsOperationEntity::class,
        AcknowledgedComposerSmsEntity::class,
        ConversationSubscriptionPreferenceEntity::class,
        ScheduledSmsEntity::class,
        SendDelayEntity::class,
        PermanentDeletionEntity::class,
        SpamSafetyDecisionEntity::class,
    ],
    version = AuroraStateDatabase.VERSION,
    exportSchema = true,
)
abstract class AuroraStateDatabase : RoomDatabase() {
    internal abstract fun draftDao(): DraftDao

    internal abstract fun appearanceProfileDao(): AppearanceProfileDao

    internal abstract fun appearanceOverrideDao(): AppearanceOverrideDao

    internal abstract fun composerSmsOperationDao(): ComposerSmsOperationDao

    internal abstract fun conversationSubscriptionPreferenceDao():
        ConversationSubscriptionPreferenceDao

    internal abstract fun scheduledSmsDao(): ScheduledSmsDao

    internal abstract fun sendDelayDao(): SendDelayDao

    internal abstract fun permanentDeletionDao(): PermanentDeletionDao

    internal abstract fun spamSafetyDecisionDao(): SpamSafetyDecisionDao

    companion object {
        const val VERSION: Int = 12
    }
}
