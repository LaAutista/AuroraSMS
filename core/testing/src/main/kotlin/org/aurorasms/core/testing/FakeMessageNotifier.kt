// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.testing

import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.notifications.IncomingMessageNotification
import org.aurorasms.core.notifications.MessageNotifier
import org.aurorasms.core.notifications.NotificationConfig
import org.aurorasms.core.notifications.NotificationPostResult

class FakeMessageNotifier : MessageNotifier {
    val incoming = mutableListOf<NotificationCall>()
    val replyFailures = mutableListOf<ConversationId>()
    val cancelledConversations = mutableListOf<ConversationId>()

    var incomingResponder: (IncomingMessageNotification, NotificationConfig) -> NotificationPostResult =
        { message, _ -> NotificationPostResult.Posted(message.conversationId.fakeNotificationId()) }
    var replyFailureResponder: (ConversationId) -> NotificationPostResult =
        { conversationId -> NotificationPostResult.Posted(conversationId.fakeNotificationId()) }

    override fun notifyIncoming(
        message: IncomingMessageNotification,
        config: NotificationConfig,
    ): NotificationPostResult {
        incoming += NotificationCall(message, config)
        return incomingResponder(message, config)
    }

    override fun notifyInlineReplyFailure(conversationId: ConversationId): NotificationPostResult {
        replyFailures += conversationId
        return replyFailureResponder(conversationId)
    }

    override fun cancelConversation(conversationId: ConversationId) {
        cancelledConversations += conversationId
    }

    data class NotificationCall(
        val message: IncomingMessageNotification,
        val config: NotificationConfig,
    )
}

private fun ConversationId.fakeNotificationId(): Int =
    ((value xor (value ushr 32)).toInt() and Int.MAX_VALUE).takeUnless { it == 0 } ?: 1
