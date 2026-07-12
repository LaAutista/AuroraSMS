// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.notifications

/** Controls the maximum message detail AuroraSMS places in a notification. */
enum class NotificationPrivacy {
    SENDER_AND_BODY,
    SENDER_ONLY,
    GENERIC,
}

internal const val GENERIC_NOTIFICATION_PERSON_KEY = "aurorasms-generic-sender"

internal fun NotificationPrivacy.personKey(senderPersonKey: String): String =
    if (this == NotificationPrivacy.GENERIC) {
        GENERIC_NOTIFICATION_PERSON_KEY
    } else {
        senderPersonKey
    }

/** Raw display material supplied by the app after contact resolution. */
internal data class NotificationContent(
    val senderDisplayName: String,
    val body: String,
    val conversationTitle: String? = null,
    val isGroupConversation: Boolean = false,
)

/** Localized, non-sensitive fallback text used while applying privacy policy. */
internal data class NotificationPrivacyText(
    val genericTitle: String,
    val genericBody: String,
    val senderOnlyBody: String,
) {
    init {
        require(genericTitle.isNotBlank()) { "genericTitle cannot be blank" }
        require(genericBody.isNotBlank()) { "genericBody cannot be blank" }
        require(senderOnlyBody.isNotBlank()) { "senderOnlyBody cannot be blank" }
    }
}

/**
 * The only text passed to the Android notification builder.
 *
 * Applying privacy before building the notification prevents hidden content from
 * lingering in notification extras or [androidx.core.app.NotificationCompat.MessagingStyle].
 */
internal data class NotificationPresentation(
    val title: String,
    val body: String,
    val messagingPersonName: String,
    val messagingText: String,
    val conversationTitle: String?,
)

internal fun NotificationPrivacy.present(
    content: NotificationContent,
    text: NotificationPrivacyText,
): NotificationPresentation {
    val genericTitle = text.genericTitle.trim()
    val genericBody = text.genericBody.trim()
    val senderOnlyBody = text.senderOnlyBody.trim()
    val sender = content.senderDisplayName.nonBlankOr(genericTitle)
    val groupTitle = content.conversationTitle
        ?.trim()
        ?.takeIf { content.isGroupConversation && it.isNotEmpty() }
    val visibleSenderTitle = groupTitle ?: sender

    return when (this) {
        NotificationPrivacy.SENDER_AND_BODY -> {
            val body = content.body.nonBlankOr(genericBody)
            NotificationPresentation(
                title = visibleSenderTitle,
                body = body,
                messagingPersonName = sender,
                messagingText = body,
                conversationTitle = groupTitle,
            )
        }

        NotificationPrivacy.SENDER_ONLY -> NotificationPresentation(
            title = sender,
            body = senderOnlyBody,
            messagingPersonName = sender,
            messagingText = senderOnlyBody,
            conversationTitle = null,
        )

        NotificationPrivacy.GENERIC -> NotificationPresentation(
            title = genericTitle,
            body = genericBody,
            messagingPersonName = genericTitle,
            messagingText = genericBody,
            conversationTitle = null,
        )
    }
}

private fun String.nonBlankOr(fallback: String): String = trim().ifEmpty { fallback }
