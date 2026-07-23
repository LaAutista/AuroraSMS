// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.PendingOperationNamespace
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.pendingOperationNamespaceOrNull

/** App-facing, bounded metadata needed to post and durably acknowledge one downloaded MMS. */
data class IncomingMmsDelivery(
    val operationId: MessageId,
    val stagedFileName: String,
    val providerId: ProviderMessageId,
    val conversationId: ConversationId,
    val sender: ParticipantAddress,
    val participants: List<ParticipantAddress>,
    val body: String,
    val receivedTimestampMillis: Long,
    val subscriptionId: AuroraSubscriptionId,
) {
    init {
        require(operationId.pendingOperationNamespaceOrNull() == PendingOperationNamespace.INCOMING_MMS)
        require(STAGED_FILE_NAME.matches(stagedFileName))
        require(providerId.kind == ProviderKind.MMS)
        require(conversationId.value > 0L)
        require(sender in participants)
        require(participants.isNotEmpty() && participants.size <= MmsProviderMessage.MAX_MMS_PARTICIPANTS)
        require(participants.distinct() == participants)
        require(body.length <= MmsProviderMessage.MAX_MMS_TEXT_CHARACTERS)
        require(receivedTimestampMillis >= 0L)
    }

    val isGroupConversation: Boolean
        get() = participants.size > 1

    override fun toString(): String =
        "IncomingMmsDelivery(participantCount=${participants.size}, bodyLength=${body.length}, REDACTED)"

    private companion object {
        val STAGED_FILE_NAME = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pdu",
        )
    }
}

/** Result of authenticating, decoding, and provider-persisting one platform download callback. */
sealed interface IncomingMmsDownloadResult {
    data class ReadyForNotification(val delivery: IncomingMmsDelivery) : IncomingMmsDownloadResult
    data object TerminalRejected : IncomingMmsDownloadResult
    data object Deferred : IncomingMmsDownloadResult
    data object Ignored : IncomingMmsDownloadResult
}

sealed interface IncomingMmsRecoveryResult {
    data class Available(
        val pendingNotifications: List<IncomingMmsDelivery>,
        val recoveredCount: Int,
        val deferredCount: Int,
        val unknownSubmissionCount: Int,
    ) : IncomingMmsRecoveryResult {
        init {
            require(recoveredCount >= 0 && deferredCount >= 0 && unknownSubmissionCount >= 0)
        }

        override fun toString(): String =
            "IncomingMmsRecoveryResult.Available(pendingNotificationCount=${pendingNotifications.size}, " +
                "recoveredCount=$recoveredCount, deferredCount=$deferredCount, " +
                "unknownSubmissionCount=$unknownSubmissionCount, REDACTED)"
    }

    data object JournalBlocked : IncomingMmsRecoveryResult
}

val IncomingMmsRecoveryResult.followUpRequired: Boolean
    get() = when (this) {
        is IncomingMmsRecoveryResult.Available ->
            deferredCount > 0 || unknownSubmissionCount > 0 || pendingNotifications.isNotEmpty()
        IncomingMmsRecoveryResult.JournalBlocked -> true
    }

enum class MmsStagedPduDisposition {
    CLEANUP,
    RETAIN,
}
