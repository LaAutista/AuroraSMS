// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.content.Context
import android.content.Intent
import org.aurorasms.app.MainActivity
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.notifications.NotificationIntentFactory

class AppNotificationIntentFactory(
    context: Context,
) : NotificationIntentFactory {
    private val appContext = context.applicationContext

    override fun conversationIntent(conversationId: ConversationId): Intent =
        Intent(appContext, MainActivity::class.java)
            .setAction(ACTION_OPEN_CONVERSATION)
            .putExtra(EXTRA_CONVERSATION_ID, conversationId.value)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    companion object {
        const val ACTION_OPEN_CONVERSATION = "org.aurorasms.app.action.OPEN_CONVERSATION"
        const val EXTRA_CONVERSATION_ID = "org.aurorasms.app.extra.CONVERSATION_ID"
    }
}
