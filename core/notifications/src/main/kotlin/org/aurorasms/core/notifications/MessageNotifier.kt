// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.notifications

import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind

/**
 * One bounded notification update. [senderPersonKey] must be an opaque,
 * non-sensitive stable key; never pass a raw address or display name as the key.
 */
data class IncomingMessageNotification(
    val messageId: MessageId,
    val conversationId: ConversationId,
    val senderDisplayName: String,
    val senderPersonKey: String,
    val body: String,
    val receivedAtEpochMillis: Long,
    val conversationTitle: String? = null,
    val isGroupConversation: Boolean = false,
    val canReply: Boolean = true,
) {
    init {
        require(messageId.kind.isTelephonyProvider) {
            "Incoming notifications require an SMS or MMS provider message ID"
        }
        require(receivedAtEpochMillis >= 0L) { "receivedAtEpochMillis cannot be negative" }
        require(senderDisplayName.length <= MAXIMUM_INPUT_LABEL_CHARACTERS) {
            "senderDisplayName is too long"
        }
        require(conversationTitle == null || conversationTitle.length <= MAXIMUM_INPUT_LABEL_CHARACTERS) {
            "conversationTitle is too long"
        }
        require(body.length <= MAXIMUM_INPUT_BODY_CHARACTERS) {
            "notification body is too long"
        }
        require(senderPersonKey.isNotBlank()) { "senderPersonKey cannot be blank" }
        require(senderPersonKey.length <= MAXIMUM_PERSON_KEY_CHARACTERS) {
            "senderPersonKey is too long"
        }
        require(senderPersonKey.none(Char::isISOControl)) {
            "senderPersonKey contains a control character"
        }
    }

    override fun toString(): String =
        "IncomingMessageNotification(bodyLength=${body.length}, " +
            "isGroupConversation=$isGroupConversation, canReply=$canReply)"

    companion object {
        const val MAXIMUM_PERSON_KEY_CHARACTERS: Int = 128
        const val MAXIMUM_INPUT_LABEL_CHARACTERS: Int = 1_000
        const val MAXIMUM_INPUT_BODY_CHARACTERS: Int = 100_000
    }
}

interface MessageNotifier {
    fun notifyIncoming(
        message: IncomingMessageNotification,
        config: NotificationConfig,
    ): NotificationPostResult

    /**
     * Re-alerts the exact still-current incoming generation without exposing
     * sender, address, message text, or attachment metadata.
     */
    fun notifyUnreadReminder(
        conversationId: ConversationId,
        expectedMessageId: MessageId,
    ): NotificationPostResult = NotificationPostResult.NotificationsDisabled

    fun notifyInlineReplyFailure(key: InlineReplyFailureKey): NotificationPostResult

    fun cancelIncomingConversation(
        conversationId: ConversationId,
        expectedMessageId: MessageId,
    ): NotificationCancelResult

    /** Cancels every active incoming slot only after validating its exact source generation. */
    fun cancelAllIncoming(): NotificationCancelResult

    /** Removes only pre-operation-key reply alerts left by an older app build. */
    fun cancelLegacyInlineReplyFailures(): NotificationCancelResult

    /** Cancels only the alert owned by this exact reply operation. */
    fun cancelInlineReplyFailure(key: InlineReplyFailureKey): NotificationCancelResult
}

/**
 * Exact identity for a generic, body-free inline-reply failure alert.
 *
 * The conversation remains the cold-route destination while the operation ID
 * prevents a late positive callback from cancelling another reply's alert.
 */
data class InlineReplyFailureKey(
    val conversationId: ConversationId,
    val operationId: MessageId,
) {
    init {
        // Pre-boundary durable reply operations used the same namespace with a
        // lower numeric value, so kind is the migration-safe ownership check.
        require(operationId.kind == ProviderKind.PENDING_OPERATION) {
            "Inline-reply failure operations must use the pending-operation namespace"
        }
    }
}

/**
 * Result of an exact, idempotent notification cancellation.
 *
 * A missing notification or one already replaced by another identity is a
 * safe, terminal no-op. A retryable failure means the notification manager
 * could not establish or apply that result, so the caller must retain durable
 * ownership.
 */
sealed interface NotificationCancelResult {
    data object Cancelled : NotificationCancelResult

    data object AlreadyAbsentOrReplaced : NotificationCancelResult

    data object RetryableFailure : NotificationCancelResult
}

sealed interface NotificationPostResult {
    data class Posted(val notificationId: Int) : NotificationPostResult

    /** A newer notification already owns this conversation's visible slot. */
    data object SupersededByNewer : NotificationPostResult

    data object NotificationsDisabled : NotificationPostResult

    data class Rejected(val reason: RejectionReason) : NotificationPostResult

    enum class RejectionReason {
        CONTENT_INTENT_NOT_EXPLICIT,
        CONTENT_INTENT_OUTSIDE_APPLICATION,
        PERMISSION_DENIED,
        GENERATION_STATE_UNAVAILABLE,
    }
}

internal fun notificationIdForConversation(conversationId: ConversationId): Int {
    val folded = (conversationId.value xor (conversationId.value ushr 32)).toInt() and Int.MAX_VALUE
    return folded.takeUnless { it == 0 } ?: 1
}

internal fun replyFailureNotificationId(conversationId: ConversationId): Int =
    notificationIdForConversation(conversationId) xor 0x4000_0000
