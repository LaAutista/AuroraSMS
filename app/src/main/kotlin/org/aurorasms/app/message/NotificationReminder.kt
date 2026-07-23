// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import kotlinx.coroutines.flow.StateFlow
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.telephony.ProviderStoredMessage

internal interface NotificationReminderController {
    val delayMinutes: StateFlow<Int>

    suspend fun setDelayMinutes(value: Int): Boolean

    suspend fun schedule(stored: ProviderStoredMessage): Boolean

    suspend fun handleAlarm(id: NotificationReminderId)

    suspend fun cancelConversation(conversationId: ConversationId)

    /** Removes only a reminder still owned by the exact incoming generation. */
    suspend fun cancelGenerationOwner(
        conversationId: ConversationId,
        messageId: ProviderMessageId,
    )

    suspend fun recover(reason: NotificationReminderRecoveryReason)

    suspend fun fence()
}

@JvmInline
internal value class NotificationReminderId(val value: Long) {
    init {
        require(value > 0L) { "Notification reminder IDs must be positive" }
    }
}

internal enum class NotificationReminderRecoveryReason {
    APP_STARTUP,
    BOOT_COMPLETED,
    WALL_CLOCK_CHANGED,
    TIMEZONE_CHANGED,
    PACKAGE_REPLACED,
    EXACT_ACCESS_CHANGED,
}
