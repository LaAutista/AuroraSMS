// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.conversation

import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId

data class ConversationSummary(
    val providerThreadId: ProviderThreadId,
    val latestLocalRowId: Long,
    val latestProviderMessageId: ProviderMessageId,
    val latestTimestampMillis: Long,
    val latestSentTimestampMillis: Long?,
    val latestDirection: MessageDirection,
    val latestBox: MessageBox,
    val latestStatus: MessageStatus,
    val latestSubscriptionId: AuroraSubscriptionId?,
    val latestSenderAddress: ParticipantAddress?,
    val latestSnippet: String?,
    val latestAttachmentCount: Int,
    val latestAttachmentTypeSummary: String,
    val latestRead: Boolean,
    val indexedMessageCount: Long,
    val indexedUnreadCount: Long,
    val participants: List<ParticipantAddress>,
    val indexedParticipantCount: Int,
    val participantsTruncated: Boolean,
) {
    init {
        require(latestLocalRowId > 0L) { "Latest local row IDs must be positive" }
        require(latestTimestampMillis >= 0L) { "Latest timestamps cannot be negative" }
        require(latestSentTimestampMillis == null || latestSentTimestampMillis >= 0L) {
            "Latest sent timestamps cannot be negative"
        }
        require(latestSnippet == null || latestSnippet.length <= MAXIMUM_CONVERSATION_SNIPPET_CHARACTERS) {
            "Conversation snippets must remain bounded"
        }
        require(latestAttachmentCount >= 0) { "Attachment counts cannot be negative" }
        require(indexedMessageCount > 0L) { "Conversation summaries require an indexed message" }
        require(indexedUnreadCount in 0L..indexedMessageCount) { "Unread counts must fit the indexed count" }
        require(participants.size <= MAXIMUM_PARTICIPANT_PREVIEW) { "Participant previews must remain bounded" }
        require(indexedParticipantCount >= participants.size) { "Participant counts cannot be below the preview" }
    }

    override fun toString(): String =
        "ConversationSummary(messageCount=$indexedMessageCount, unreadCount=$indexedUnreadCount, " +
            "participantPreviewCount=${participants.size}, participantsTruncated=$participantsTruncated, REDACTED)"
}

const val MAXIMUM_CONVERSATION_SNIPPET_CHARACTERS: Int = 512
const val MAXIMUM_PARTICIPANT_PREVIEW: Int = 8
