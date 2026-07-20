// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.notifications

import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind

data class MarkConversationReadRequest(
    val conversationId: ConversationId,
    val throughMessageId: MessageId,
) {
    init {
        require(throughMessageId.kind == ProviderKind.SMS) {
            "Notification mark-read currently supports SMS generations only"
        }
    }

    override fun toString(): String = "MarkConversationReadRequest(content=REDACTED)"
}

fun interface MarkConversationReadHandler {
    /**
     * Applies a generation-fenced local provider read transition. Returning
     * [MarkConversationReadDisposition.APPLIED] means the exact source generation was
     * handled or was already read; it never claims that a newer generation was read.
     */
    suspend fun handle(request: MarkConversationReadRequest): MarkConversationReadDisposition
}

enum class MarkConversationReadDisposition {
    APPLIED,
    REJECTED,
}
