// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.notifications

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import org.aurorasms.core.model.ConversationId

class AndroidMessageNotifier(
    context: Context,
    private val intentFactory: NotificationIntentFactory,
) : MessageNotifier {
    private val appContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)

    override fun notifyIncoming(
        message: IncomingMessageNotification,
        config: NotificationConfig,
    ): NotificationPostResult {
        NotificationChannels.ensureCreated(appContext)
        if (!notificationManager.areNotificationsEnabled()) {
            return NotificationPostResult.NotificationsDisabled
        }

        val contentIntent = when (val result = contentPendingIntent(message.conversationId)) {
            is ContentIntentResult.Accepted -> result.pendingIntent
            is ContentIntentResult.Rejected -> return NotificationPostResult.Rejected(result.reason)
        }
        val privacyText = privacyText()
        val exposeGroupMetadata =
            config.privacy == NotificationPrivacy.SENDER_AND_BODY && message.isGroupConversation
        val boundedContent = NotificationContent(
            senderDisplayName = message.senderDisplayName.toNotificationLine(MAXIMUM_PERSON_CHARACTERS),
            body = message.body.toNotificationBody(MAXIMUM_BODY_CHARACTERS),
            conversationTitle = message.conversationTitle
                ?.toNotificationLine(MAXIMUM_CONVERSATION_TITLE_CHARACTERS),
            isGroupConversation = exposeGroupMetadata,
        )
        val presentation = config.privacy.present(boundedContent, privacyText)
        val sender = Person.Builder()
            .setName(presentation.messagingPersonName)
            .setKey(config.privacy.personKey(message.senderPersonKey))
            .build()
        val localUser = Person.Builder()
            .setName(privacyText.genericTitle)
            .setKey(LOCAL_USER_PERSON_KEY)
            .build()
        val style = NotificationCompat.MessagingStyle(localUser)
            .addMessage(
                presentation.messagingText,
                message.receivedAtEpochMillis,
                sender,
            )
            .setGroupConversation(exposeGroupMetadata)
        presentation.conversationTitle?.let(style::setConversationTitle)

        val builder = NotificationCompat.Builder(appContext, NotificationChannels.MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(presentation.title)
            .setContentText(presentation.body)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(genericPublicNotification(privacyText, contentIntent))
            .setContentIntent(contentIntent)
            .setWhen(message.receivedAtEpochMillis)
            .setShowWhen(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)

        if (message.canReply) {
            builder.addAction(inlineReplyAction(message, config))
        }

        val notificationId = notificationIdForConversation(message.conversationId)
        return post(conversationTag(message.conversationId), notificationId, builder)
    }

    override fun notifyInlineReplyFailure(conversationId: ConversationId): NotificationPostResult {
        NotificationChannels.ensureCreated(appContext)
        if (!notificationManager.areNotificationsEnabled()) {
            return NotificationPostResult.NotificationsDisabled
        }

        val contentIntent = when (val result = contentPendingIntent(conversationId)) {
            is ContentIntentResult.Accepted -> result.pendingIntent
            is ContentIntentResult.Rejected -> return NotificationPostResult.Rejected(result.reason)
        }
        val builder = NotificationCompat.Builder(appContext, NotificationChannels.REPLY_FAILURES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(appContext.getString(R.string.notification_reply_failed_title))
            .setContentText(appContext.getString(R.string.notification_reply_failed_body))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)

        return post(
            replyFailureTag(conversationId),
            replyFailureNotificationId(conversationId),
            builder,
        )
    }

    override fun cancelConversation(conversationId: ConversationId) {
        notificationManager.cancel(
            conversationTag(conversationId),
            notificationIdForConversation(conversationId),
        )
        notificationManager.cancel(
            replyFailureTag(conversationId),
            replyFailureNotificationId(conversationId),
        )
    }

    @SuppressLint("MissingPermission")
    private fun post(
        tag: String,
        notificationId: Int,
        builder: NotificationCompat.Builder,
    ): NotificationPostResult = try {
        notificationManager.notify(tag, notificationId, builder.build())
        NotificationPostResult.Posted(notificationId)
    } catch (_: SecurityException) {
        NotificationPostResult.Rejected(NotificationPostResult.RejectionReason.PERMISSION_DENIED)
    }

    private fun contentPendingIntent(conversationId: ConversationId): ContentIntentResult {
        val intent = intentFactory.conversationIntent(conversationId)
        val component = intent.component
            ?: return ContentIntentResult.Rejected(
                NotificationPostResult.RejectionReason.CONTENT_INTENT_NOT_EXPLICIT,
            )
        if (component.packageName != appContext.packageName) {
            return ContentIntentResult.Rejected(
                NotificationPostResult.RejectionReason.CONTENT_INTENT_OUTSIDE_APPLICATION,
            )
        }
        val explicitAppIntent = Intent(intent)
            .setPackage(appContext.packageName)
            .addCategory(conversationCategory(conversationId))
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            stableRequestCode(conversationId.value.toString(), CONTENT_INTENT_SALT),
            explicitAppIntent,
            PendingIntentPolicy.immutableUpdateCurrent(),
        )
        return ContentIntentResult.Accepted(pendingIntent)
    }

    private fun inlineReplyAction(
        message: IncomingMessageNotification,
        config: NotificationConfig,
    ): NotificationCompat.Action {
        val replyIntent = Intent(appContext, InlineReplyReceiver::class.java)
            .setAction(NotificationProtocol.ACTION_INLINE_REPLY)
            .addCategory(conversationCategory(message.conversationId))
            .setData(inlineReplyData(message, config))
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            stableRequestCode(message.conversationId.value.toString(), INLINE_REPLY_SALT),
            replyIntent,
            PendingIntentPolicy.inlineReplyUpdateCurrent(Build.VERSION.SDK_INT),
        )
        val remoteInput = RemoteInput.Builder(NotificationProtocol.REMOTE_INPUT_REPLY)
            .setLabel(appContext.getString(R.string.notification_inline_reply_label))
            .build()

        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            appContext.getString(R.string.notification_inline_reply_label),
            pendingIntent,
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAuthenticationRequired(true)
            .build()
    }

    private fun inlineReplyData(
        message: IncomingMessageNotification,
        config: NotificationConfig,
    ): Uri = Uri.Builder()
        .scheme(NotificationProtocol.INLINE_REPLY_SCHEME)
        .authority(NotificationProtocol.INLINE_REPLY_AUTHORITY)
        .appendPath(message.conversationId.value.toString())
        .appendPath(message.messageId.kind.name)
        .appendPath(message.messageId.value.toString())
        .appendPath(config.maximumReplyCharacters.toString())
        .build()

    private fun genericPublicNotification(
        text: NotificationPrivacyText,
        contentIntent: PendingIntent,
    ) =
        NotificationCompat.Builder(appContext, NotificationChannels.MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text.genericTitle)
            .setContentText(text.genericBody)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

    private fun privacyText() = NotificationPrivacyText(
        genericTitle = appContext.getString(R.string.notification_generic_title),
        genericBody = appContext.getString(R.string.notification_generic_body),
        senderOnlyBody = appContext.getString(R.string.notification_sender_only_body),
    )

    private sealed interface ContentIntentResult {
        data class Accepted(val pendingIntent: PendingIntent) : ContentIntentResult

        data class Rejected(
            val reason: NotificationPostResult.RejectionReason,
        ) : ContentIntentResult
    }

    private companion object {
        const val MAXIMUM_PERSON_CHARACTERS = 256
        const val MAXIMUM_CONVERSATION_TITLE_CHARACTERS = 256
        const val MAXIMUM_BODY_CHARACTERS = 4_096
        const val CONTENT_INTENT_SALT = 0x434F4E54
        const val INLINE_REPLY_SALT = 0x52504C59
        const val LOCAL_USER_PERSON_KEY = "aurorasms-local-user"
    }
}

private fun String.toNotificationLine(maximumCharacters: Int): String =
    replace(Regex("\\s+"), " ")
        .filterNot { character ->
            character.isISOControl() || Character.getType(character) == Character.FORMAT.toInt()
        }
        .trim()
        .takeUtf16Safely(maximumCharacters)

private fun String.toNotificationBody(maximumCharacters: Int): String =
    filter { !it.isISOControl() || it == '\n' || it == '\t' }
        .trim()
        .takeUtf16Safely(maximumCharacters)

private fun String.takeUtf16Safely(maximumCharacters: Int): String {
    if (length <= maximumCharacters) return this
    val end = if (
        maximumCharacters > 0 &&
        this[maximumCharacters - 1].isHighSurrogate() &&
        this[maximumCharacters].isLowSurrogate()
    ) {
        maximumCharacters - 1
    } else {
        maximumCharacters
    }
    return substring(0, end)
}

private fun conversationCategory(conversationId: ConversationId): String =
    NotificationProtocol.CATEGORY_CONVERSATION_PREFIX + conversationId.value

private fun conversationTag(conversationId: ConversationId): String =
    "aurora-conversation:${conversationId.value}"

private fun replyFailureTag(conversationId: ConversationId): String =
    "aurora-reply-failure:${conversationId.value}"
