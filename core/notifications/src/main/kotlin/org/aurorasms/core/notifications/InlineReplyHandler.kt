// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.notifications

import org.aurorasms.core.model.ConversationId

data class InlineReplyRequest(
    val conversationId: ConversationId,
    val notificationId: Int,
    val replyRequestId: String,
    val text: String,
) {
    override fun toString(): String =
        "InlineReplyRequest(textLength=${text.length})"
}

fun interface InlineReplyHandler {
    /**
     * Accepts bounded input for app-owned role, recipient, subscription, duplicate,
     * and expiry validation. Returning [InlineReplyDisposition.ACCEPTED] means the
     * app took ownership of the operation; it does not claim carrier submission.
     * The receiver invokes this on a bounded IO coroutine. Implementations may
     * suspend for the local provider/platform submission boundary, but must not
     * wait for sent or delivery callbacks.
     */
    suspend fun handle(request: InlineReplyRequest): InlineReplyDisposition
}

enum class InlineReplyDisposition {
    ACCEPTED,
    REJECTED,
}
