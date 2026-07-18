// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.testing

import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.notifications.IncomingMessageNotification
import org.aurorasms.core.notifications.MessageNotifier
import org.aurorasms.core.notifications.NotificationCancelResult
import org.aurorasms.core.notifications.NotificationConfig
import org.aurorasms.core.notifications.NotificationPostResult

class FakeMessageNotifier : MessageNotifier {
    val incoming = mutableListOf<NotificationCall>()
    val replyFailures = mutableListOf<ConversationId>()
    val cancelledConversations = mutableListOf<ConversationId>()
    val incomingCancellations = mutableListOf<IncomingCancellationCall>()
    var cancelAllIncomingCalls: Int = 0
    val cancelledReplyFailures = mutableListOf<ConversationId>()

    var incomingResponder: (IncomingMessageNotification, NotificationConfig) -> NotificationPostResult =
        { message, _ -> NotificationPostResult.Posted(message.conversationId.fakeNotificationId()) }
    var replyFailureResponder: (ConversationId) -> NotificationPostResult =
        { conversationId -> NotificationPostResult.Posted(conversationId.fakeNotificationId()) }
    var cancelIncomingResponder: (ConversationId, MessageId) -> NotificationCancelResult =
        { _, _ -> NotificationCancelResult.Cancelled }
    var cancelAllIncomingResponder: () -> NotificationCancelResult =
        { NotificationCancelResult.Cancelled }
    var cancelReplyFailureResponder: (ConversationId) -> Unit = {}

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

    override fun cancelIncomingConversation(
        conversationId: ConversationId,
        expectedMessageId: MessageId,
    ): NotificationCancelResult {
        incomingCancellations += IncomingCancellationCall(conversationId, expectedMessageId)
        cancelledConversations += conversationId
        return cancelIncomingResponder(conversationId, expectedMessageId)
    }

    override fun cancelAllIncoming(): NotificationCancelResult {
        cancelAllIncomingCalls += 1
        return cancelAllIncomingResponder()
    }

    override fun cancelInlineReplyFailure(conversationId: ConversationId) {
        cancelReplyFailureResponder(conversationId)
        cancelledReplyFailures += conversationId
    }

    data class NotificationCall(
        val message: IncomingMessageNotification,
        val config: NotificationConfig,
    )

    data class IncomingCancellationCall(
        val conversationId: ConversationId,
        val expectedMessageId: MessageId,
    )
}

private fun ConversationId.fakeNotificationId(): Int =
    ((value xor (value ushr 32)).toInt() and Int.MAX_VALUE).takeUnless { it == 0 } ?: 1
