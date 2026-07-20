// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind

class AndroidMessageNotifier internal constructor(
    context: Context,
    private val intentFactory: NotificationIntentFactory,
    private val incomingGenerationTracker: IncomingNotificationGenerationTracker,
    notificationGateway: NotificationMutationGateway?,
    private val replyFailureChannelId: String = NotificationChannels.REPLY_FAILURES,
) : MessageNotifier {
    constructor(
        context: Context,
        intentFactory: NotificationIntentFactory,
    ) : this(
        context = context,
        intentFactory = intentFactory,
        incomingGenerationTracker = IncomingNotificationGenerationTracker(
            SharedPreferencesIncomingNotificationGenerationStore(context),
        ),
        notificationGateway = null,
    )

    private val appContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)
    private val platformNotificationManager =
        appContext.getSystemService(NotificationManager::class.java)
    private val notificationGateway = notificationGateway ?: AndroidNotificationMutationGateway(
        notificationManager,
        platformNotificationManager,
    )

    override fun notifyIncoming(
        message: IncomingMessageNotification,
        config: NotificationConfig,
    ): NotificationPostResult = synchronized(notificationMutationLock) {
        NotificationChannels.ensureCreated(appContext)
        if (!notificationsEnabledForChannel(NotificationChannels.MESSAGES)) {
            return@synchronized NotificationPostResult.NotificationsDisabled
        }

        val contentIntent = when (val result = contentPendingIntent(message.conversationId)) {
            is ContentIntentResult.Accepted -> result.pendingIntent
            is ContentIntentResult.Rejected -> {
                return@synchronized NotificationPostResult.Rejected(result.reason)
            }
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
        retainedConversationMessages(
            message = message,
            privacy = config.privacy,
            exposeGroupMetadata = exposeGroupMetadata,
            conversationTitle = presentation.conversationTitle,
        ).forEach(style::addMessage)
        style.addMessage(
            presentation.messagingText,
            message.receivedAtEpochMillis,
            sender,
        ).setGroupConversation(exposeGroupMetadata)
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
            .addExtras(
                Bundle().apply {
                    putString(
                        SOURCE_MESSAGE_ID_EXTRA,
                        sourceMessageIdMarker(message.messageId),
                    )
                    putString(NOTIFICATION_PRIVACY_EXTRA, config.privacy.name)
                    putBoolean(NOTIFICATION_GROUP_CONVERSATION_EXTRA, exposeGroupMetadata)
                },
            )

        if (message.canReply) {
            builder.addAction(inlineReplyAction(message, config))
        }
        if (message.messageId.kind == ProviderKind.SMS) {
            builder.addInvisibleAction(markConversationReadAction(message))
        }

        val notificationId = notificationIdForConversation(message.conversationId)
        post(
            tag = conversationTag(message.conversationId),
            notificationId = notificationId,
            builder = builder,
            incomingGeneration = IncomingNotificationGeneration(
                conversationId = message.conversationId,
                messageId = message.messageId,
                receivedAtEpochMillis = message.receivedAtEpochMillis,
            ),
        )
    }

    private fun retainedConversationMessages(
        message: IncomingMessageNotification,
        privacy: NotificationPrivacy,
        exposeGroupMetadata: Boolean,
        conversationTitle: String?,
    ): List<NotificationCompat.MessagingStyle.Message> {
        val tag = conversationTag(message.conversationId)
        val id = notificationIdForConversation(message.conversationId)
        val active = try {
            notificationGateway.activeNotifications().singleOrNull { candidate ->
                candidate.tag == tag && candidate.id == id
            }
        } catch (_: RuntimeException) {
            null
        } ?: return emptyList()
        val extras = active.notification.extras
        if (extras.getString(NOTIFICATION_PRIVACY_EXTRA) != privacy.name) return emptyList()
        if (
            extras.getBoolean(NOTIFICATION_GROUP_CONVERSATION_EXTRA, false) != exposeGroupMetadata
        ) {
            return emptyList()
        }
        val previousStyle = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(active.notification)
            ?: return emptyList()
        if (previousStyle.isGroupConversation != exposeGroupMetadata) return emptyList()
        if (previousStyle.conversationTitle?.toString() != conversationTitle) return emptyList()

        val sameGeneration = parseSourceMessageIdMarker(
            extras.getString(SOURCE_MESSAGE_ID_EXTRA),
        ) == message.messageId
        val previous = if (sameGeneration) {
            previousStyle.messages.dropLast(1)
        } else {
            previousStyle.messages
        }
        return previous
            .takeLast(MAXIMUM_RETAINED_MESSAGES - 1)
            .mapNotNull { retainedMessage ->
                val timestamp = retainedMessage.timestamp.takeIf { it in 0..message.receivedAtEpochMillis }
                    ?: return@mapNotNull null
                val person = retainedMessage.person ?: return@mapNotNull null
                val name = person.name?.toString()
                    ?.toNotificationLine(MAXIMUM_PERSON_CHARACTERS)
                    ?.takeIf(String::isNotEmpty)
                    ?: return@mapNotNull null
                val key = person.key
                    ?.takeIf { it.isNotBlank() && it.length <= IncomingMessageNotification.MAXIMUM_PERSON_KEY_CHARACTERS }
                    ?: return@mapNotNull null
                val text = retainedMessage.text?.toString()
                    ?.toNotificationBody(MAXIMUM_BODY_CHARACTERS)
                    ?: return@mapNotNull null
                NotificationCompat.MessagingStyle.Message(
                    text,
                    timestamp,
                    Person.Builder().setName(name).setKey(key).build(),
                )
            }
    }

    override fun notifyInlineReplyFailure(key: InlineReplyFailureKey): NotificationPostResult {
        NotificationChannels.ensureCreated(appContext)
        if (!notificationsEnabledForChannel(replyFailureChannelId)) {
            return NotificationPostResult.NotificationsDisabled
        }

        val contentIntent = when (val result = contentPendingIntent(key.conversationId)) {
            is ContentIntentResult.Accepted -> result.pendingIntent
            is ContentIntentResult.Rejected -> return NotificationPostResult.Rejected(result.reason)
        }
        val builder = NotificationCompat.Builder(appContext, replyFailureChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(appContext.getString(R.string.notification_reply_failed_title))
            .setContentText(appContext.getString(R.string.notification_reply_failed_body))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .addExtras(
                Bundle().apply {
                    putString(
                        REPLY_OPERATION_ID_EXTRA,
                        replyOperationIdMarker(key.operationId),
                    )
                },
            )

        return post(
            replyFailureTag(key),
            replyFailureNotificationId(key.conversationId),
            builder,
        )
    }

    override fun notifyUnreadReminder(
        conversationId: ConversationId,
        expectedMessageId: MessageId,
    ): NotificationPostResult = synchronized(notificationMutationLock) {
        NotificationChannels.ensureCreated(appContext)
        if (!notificationsEnabledForChannel(NotificationChannels.MESSAGES)) {
            return@synchronized NotificationPostResult.NotificationsDisabled
        }
        when (val tracked = incomingGenerationTracker.lookup(conversationId)) {
            is IncomingNotificationGenerationTracker.Lookup.Tracked -> {
                if (tracked.messageId != expectedMessageId) {
                    return@synchronized NotificationPostResult.SupersededByNewer
                }
            }
            IncomingNotificationGenerationTracker.Lookup.Corrupt,
            IncomingNotificationGenerationTracker.Lookup.PersistenceFailure,
            IncomingNotificationGenerationTracker.Lookup.Untracked,
            IncomingNotificationGenerationTracker.Lookup.UntrackedAfterOverflow,
            -> return@synchronized generationStateUnavailable()
        }
        val contentIntent = when (val result = contentPendingIntent(conversationId)) {
            is ContentIntentResult.Accepted -> result.pendingIntent
            is ContentIntentResult.Rejected -> {
                return@synchronized NotificationPostResult.Rejected(result.reason)
            }
        }
        val tag = conversationTag(conversationId)
        val notificationId = notificationIdForConversation(conversationId)
        val exactActive = try {
            notificationGateway.activeNotifications().filter { candidate ->
                candidate.tag == tag && candidate.id == notificationId
            }
        } catch (_: RuntimeException) {
            return@synchronized generationStateUnavailable()
        }
        if (exactActive.size > 1) return@synchronized generationStateUnavailable()
        exactActive.singleOrNull()?.let { active ->
            val activeMessageId = parseSourceMessageIdMarker(
                active.notification.extras.getString(SOURCE_MESSAGE_ID_EXTRA),
            ) ?: return@synchronized generationStateUnavailable()
            if (activeMessageId != expectedMessageId) {
                return@synchronized NotificationPostResult.SupersededByNewer
            }
        }
        val builder = NotificationCompat.Builder(appContext, NotificationChannels.MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(appContext.getString(R.string.notification_reminder_title))
            .setContentText(appContext.getString(R.string.notification_reminder_body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(genericPublicNotification(privacyText(), contentIntent))
            .setContentIntent(contentIntent)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)
            .addExtras(
                Bundle().apply {
                    putString(SOURCE_MESSAGE_ID_EXTRA, sourceMessageIdMarker(expectedMessageId))
                },
            )
        try {
            notificationGateway.notify(tag, notificationId, builder.build())
            NotificationPostResult.Posted(notificationId)
        } catch (_: SecurityException) {
            NotificationPostResult.Rejected(NotificationPostResult.RejectionReason.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            generationStateUnavailable()
        }
    }

    override fun cancelIncomingConversation(
        conversationId: ConversationId,
        expectedMessageId: MessageId,
    ): NotificationCancelResult = synchronized(notificationMutationLock) {
        try {
            val tag = conversationTag(conversationId)
            val notificationId = notificationIdForConversation(conversationId)
            val trackedGeneration = incomingGenerationTracker.lookup(conversationId)
            when (trackedGeneration) {
                is IncomingNotificationGenerationTracker.Lookup.Tracked -> {
                    if (trackedGeneration.messageId != expectedMessageId) {
                        return@synchronized NotificationCancelResult.AlreadyAbsentOrReplaced
                    }
                }

                IncomingNotificationGenerationTracker.Lookup.UntrackedAfterOverflow -> {
                    // A successful active-notification snapshot below is now
                    // authoritative for legacy/untracked generations.
                }

                IncomingNotificationGenerationTracker.Lookup.Corrupt,
                IncomingNotificationGenerationTracker.Lookup.PersistenceFailure,
                -> return@synchronized NotificationCancelResult.RetryableFailure

                IncomingNotificationGenerationTracker.Lookup.Untracked -> Unit
            }
            val active = notificationGateway.activeNotifications().firstOrNull { candidate ->
                candidate.tag == tag && candidate.id == notificationId
            }
            if (active == null) {
                return@synchronized if (
                    trackedGeneration is IncomingNotificationGenerationTracker.Lookup.Tracked
                ) {
                    cancelTrackedGeneration(tag, notificationId, conversationId, expectedMessageId)
                } else {
                    NotificationCancelResult.AlreadyAbsentOrReplaced
                }
            }
            val activeMessageId = parseSourceMessageIdMarker(
                active.notification.extras.getString(SOURCE_MESSAGE_ID_EXTRA),
            ) ?: return@synchronized NotificationCancelResult.RetryableFailure
            if (activeMessageId == expectedMessageId) {
                cancelTrackedGeneration(tag, notificationId, conversationId, expectedMessageId)
            } else if (
                trackedGeneration is IncomingNotificationGenerationTracker.Lookup.Tracked
            ) {
                NotificationCancelResult.RetryableFailure
            } else {
                NotificationCancelResult.AlreadyAbsentOrReplaced
            }
        } catch (_: RuntimeException) {
            NotificationCancelResult.RetryableFailure
        }
    }

    override fun cancelAllIncoming(): NotificationCancelResult = synchronized(
        notificationMutationLock,
    ) {
        val active = try {
            notificationGateway.activeNotifications()
        } catch (_: RuntimeException) {
            return@synchronized NotificationCancelResult.RetryableFailure
        }
        var cancelledAny = false
        var retryRequired = false
        active.forEach { candidate ->
            val conversationId = parseConversationTag(candidate.tag) ?: return@forEach
            if (candidate.id != notificationIdForConversation(conversationId)) {
                retryRequired = true
                return@forEach
            }
            val messageId = parseSourceMessageIdMarker(
                candidate.notification.extras.getString(SOURCE_MESSAGE_ID_EXTRA),
            )
            if (messageId == null) {
                retryRequired = true
                return@forEach
            }
            try {
                notificationGateway.cancel(requireNotNull(candidate.tag), candidate.id)
                cancelledAny = true
            } catch (_: RuntimeException) {
                retryRequired = true
            }
        }
        val remaining = try {
            notificationGateway.activeNotifications()
        } catch (_: RuntimeException) {
            return@synchronized NotificationCancelResult.RetryableFailure
        }
        if (!replaceIncomingGenerationsFromActiveNotifications(remaining)) {
            retryRequired = true
        }
        when {
            retryRequired -> NotificationCancelResult.RetryableFailure
            cancelledAny -> NotificationCancelResult.Cancelled
            else -> NotificationCancelResult.AlreadyAbsentOrReplaced
        }
    }

    override fun cancelLegacyInlineReplyFailures(): NotificationCancelResult = synchronized(
        notificationMutationLock,
    ) {
        val active = try {
            notificationGateway.activeNotifications()
        } catch (_: RuntimeException) {
            return@synchronized NotificationCancelResult.RetryableFailure
        }
        var cancelledAny = false
        active.forEach { candidate ->
            val conversationId = parseLegacyReplyFailureTag(candidate.tag) ?: return@forEach
            if (candidate.id != replyFailureNotificationId(conversationId)) {
                return@forEach
            }
            try {
                notificationGateway.cancel(requireNotNull(candidate.tag), candidate.id)
                cancelledAny = true
            } catch (_: RuntimeException) {
                return@synchronized NotificationCancelResult.RetryableFailure
            }
        }
        if (cancelledAny) {
            NotificationCancelResult.Cancelled
        } else {
            NotificationCancelResult.AlreadyAbsentOrReplaced
        }
    }

    override fun cancelInlineReplyFailure(
        key: InlineReplyFailureKey,
    ): NotificationCancelResult = synchronized(notificationMutationLock) {
        try {
            notificationGateway.cancel(
                replyFailureTag(key),
                replyFailureNotificationId(key.conversationId),
            )
            NotificationCancelResult.Cancelled
        } catch (_: RuntimeException) {
            NotificationCancelResult.RetryableFailure
        }
    }

    @SuppressLint("MissingPermission")
    private fun post(
        tag: String,
        notificationId: Int,
        builder: NotificationCompat.Builder,
        incomingGeneration: IncomingNotificationGeneration? = null,
    ): NotificationPostResult = synchronized(notificationMutationLock) {
        if (incomingGeneration != null) {
            when (incomingGenerationOrdering(incomingGeneration, tag, notificationId)) {
                IncomingGenerationOrdering.Proceed -> Unit
                IncomingGenerationOrdering.Superseded ->
                    return@synchronized NotificationPostResult.SupersededByNewer
                IncomingGenerationOrdering.Unavailable ->
                    return@synchronized generationStateUnavailable()
            }
            if (
                !persistIncomingGenerationBeforeNotify(
                    incomingGeneration.conversationId,
                    incomingGeneration.messageId,
                )
            ) {
                return@synchronized generationStateUnavailable()
            }
        }
        try {
            notificationGateway.notify(tag, notificationId, builder.build())
            NotificationPostResult.Posted(notificationId)
        } catch (_: SecurityException) {
            NotificationPostResult.Rejected(NotificationPostResult.RejectionReason.PERMISSION_DENIED)
        } catch (failure: RuntimeException) {
            if (incomingGeneration != null) {
                NotificationPostResult.Rejected(
                    NotificationPostResult.RejectionReason.GENERATION_STATE_UNAVAILABLE,
                )
            } else {
                throw failure
            }
        }
    }

    /**
     * Establishes that an incoming generation may safely replace the exact
     * conversation slot before any durable state or NMS mutation occurs.
     */
    private fun incomingGenerationOrdering(
        candidate: IncomingNotificationGeneration,
        tag: String,
        notificationId: Int,
    ): IncomingGenerationOrdering {
        val trackedGeneration = incomingGenerationTracker.lookup(candidate.conversationId)
        if (
            trackedGeneration is IncomingNotificationGenerationTracker.Lookup.Tracked &&
            trackedGeneration.messageId.isProvablyNewerThan(candidate.messageId)
        ) {
            return IncomingGenerationOrdering.Superseded
        }

        val exactActive = try {
            notificationGateway.activeNotifications().filter { active ->
                active.tag == tag && active.id == notificationId
            }
        } catch (_: RuntimeException) {
            return IncomingGenerationOrdering.Unavailable
        }
        if (exactActive.size > 1) return IncomingGenerationOrdering.Unavailable
        val active = exactActive.singleOrNull()
            ?: return when (trackedGeneration) {
                IncomingNotificationGenerationTracker.Lookup.PersistenceFailure ->
                    IncomingGenerationOrdering.Unavailable
                else -> IncomingGenerationOrdering.Proceed
            }

        val activeMessageId = parseSourceMessageIdMarker(
            active.notification.extras.getString(SOURCE_MESSAGE_ID_EXTRA),
        )
        if (activeMessageId != null && activeMessageId.kind == candidate.messageId.kind) {
            return if (activeMessageId.value > candidate.messageId.value) {
                IncomingGenerationOrdering.Superseded
            } else {
                IncomingGenerationOrdering.Proceed
            }
        }

        val activeWhen = active.notification.`when`
        if (activeWhen < 0L) return IncomingGenerationOrdering.Unavailable
        if (activeWhen > candidate.receivedAtEpochMillis) {
            return IncomingGenerationOrdering.Superseded
        }
        if (activeWhen < candidate.receivedAtEpochMillis) {
            return IncomingGenerationOrdering.Proceed
        }

        return IncomingGenerationOrdering.Unavailable
    }

    private fun MessageId.isProvablyNewerThan(candidate: MessageId): Boolean =
        kind == candidate.kind && value > candidate.value

    private fun generationStateUnavailable() = NotificationPostResult.Rejected(
        NotificationPostResult.RejectionReason.GENERATION_STATE_UNAVAILABLE,
    )

    private fun persistIncomingGenerationBeforeNotify(
        conversationId: ConversationId,
        messageId: MessageId,
    ): Boolean = when (incomingGenerationTracker.record(conversationId, messageId)) {
        IncomingNotificationGenerationTracker.RecordResult.Recorded -> true
        IncomingNotificationGenerationTracker.RecordResult.Full -> {
            val activeConversationIds = try {
                notificationGateway.activeNotifications()
                    .mapNotNull(::activeConversationIdOrNull)
                    .toSet()
            } catch (_: RuntimeException) {
                return false
            }
            if (
                incomingGenerationTracker.reconcileProvablyAbsent(activeConversationIds) !=
                IncomingNotificationGenerationTracker.MutationResult.Success
            ) {
                return false
            }
            when (incomingGenerationTracker.record(conversationId, messageId)) {
                IncomingNotificationGenerationTracker.RecordResult.Recorded -> true
                IncomingNotificationGenerationTracker.RecordResult.Full -> {
                    false
                }
                IncomingNotificationGenerationTracker.RecordResult.Corrupt,
                IncomingNotificationGenerationTracker.RecordResult.PersistenceFailure,
                -> false
            }
        }
        IncomingNotificationGenerationTracker.RecordResult.Corrupt ->
            rebuildIncomingGenerationsFromActiveNotifications(conversationId, messageId)
        IncomingNotificationGenerationTracker.RecordResult.PersistenceFailure -> false
    }

    private fun rebuildIncomingGenerationsFromActiveNotifications(
        conversationId: ConversationId,
        messageId: MessageId,
    ): Boolean {
        val active = try {
            notificationGateway.activeNotifications()
        } catch (_: RuntimeException) {
            return false
        }
        val rebuilt = activeIncomingGenerations(active) ?: return false
        rebuilt[conversationId] = messageId
        return incomingGenerationTracker.replaceAll(rebuilt) ==
            IncomingNotificationGenerationTracker.RecordResult.Recorded
    }

    private fun replaceIncomingGenerationsFromActiveNotifications(
        active: List<ActiveNotificationSnapshot>,
    ): Boolean {
        val rebuilt = activeIncomingGenerations(active) ?: return false
        return incomingGenerationTracker.replaceAll(rebuilt) ==
            IncomingNotificationGenerationTracker.RecordResult.Recorded
    }

    private fun activeIncomingGenerations(
        active: List<ActiveNotificationSnapshot>,
    ): MutableMap<ConversationId, MessageId>? {
        val rebuilt = linkedMapOf<ConversationId, MessageId>()
        active.forEach { candidate ->
            val activeConversationId = parseConversationTag(candidate.tag) ?: return@forEach
            if (candidate.id != notificationIdForConversation(activeConversationId)) return null
            val activeMessageId = parseSourceMessageIdMarker(
                candidate.notification.extras.getString(SOURCE_MESSAGE_ID_EXTRA),
            ) ?: return null
            val previous = rebuilt.put(activeConversationId, activeMessageId)
            if (previous != null && previous != activeMessageId) return null
        }
        return rebuilt
    }

    private fun activeConversationIdOrNull(
        active: ActiveNotificationSnapshot,
    ): ConversationId? {
        val conversationId = parseConversationTag(active.tag) ?: return null
        return conversationId.takeIf { candidate ->
            active.id == notificationIdForConversation(candidate)
        }
    }

    private fun cancelTrackedGeneration(
        tag: String,
        notificationId: Int,
        conversationId: ConversationId,
        messageId: MessageId,
    ): NotificationCancelResult {
        notificationGateway.cancel(tag, notificationId)
        return if (
            incomingGenerationTracker.forgetIfCurrent(conversationId, messageId) ==
            IncomingNotificationGenerationTracker.MutationResult.Success
        ) {
            NotificationCancelResult.Cancelled
        } else {
            NotificationCancelResult.RetryableFailure
        }
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

    private fun notificationsEnabledForChannel(channelId: String): Boolean {
        if (!notificationManager.areNotificationsEnabled()) return false
        val channel = platformNotificationManager.getNotificationChannel(channelId) ?: return false
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        if (Build.VERSION.SDK_INT >= 28) {
            val groupId = channel.group
            val groupBlocked = groupId != null &&
                platformNotificationManager.getNotificationChannelGroup(groupId)?.isBlocked == true
            if (groupBlocked) {
                return false
            }
        }
        return true
    }

    private fun inlineReplyAction(
        message: IncomingMessageNotification,
        config: NotificationConfig,
    ): NotificationCompat.Action {
        val replyIntent = Intent(appContext, MessagingNotificationActionService::class.java)
            .setAction(NotificationProtocol.ACTION_INLINE_REPLY)
            .addCategory(conversationCategory(message.conversationId))
            .setData(inlineReplyData(message, config))
        val pendingIntent = PendingIntent.getService(
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
            .setAuthenticationRequired(false)
            .build()
    }

    private fun markConversationReadAction(
        message: IncomingMessageNotification,
    ): NotificationCompat.Action {
        val request = MarkConversationReadRequest(
            conversationId = message.conversationId,
            throughMessageId = message.messageId,
        )
        val intent = Intent(appContext, MessagingNotificationActionService::class.java)
            .setAction(NotificationProtocol.ACTION_MARK_AS_READ)
            .addCategory(conversationCategory(message.conversationId))
            .setData(markConversationReadData(request))
        val pendingIntent = PendingIntent.getService(
            appContext,
            stableRequestCode(message.conversationId.value.toString(), MARK_AS_READ_SALT),
            intent,
            PendingIntentPolicy.immutableUpdateCurrent(),
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            appContext.getString(R.string.notification_mark_as_read_label),
            pendingIntent,
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .setAuthenticationRequired(false)
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

    private data class IncomingNotificationGeneration(
        val conversationId: ConversationId,
        val messageId: MessageId,
        val receivedAtEpochMillis: Long,
    )

    private enum class IncomingGenerationOrdering {
        Proceed,
        Superseded,
        Unavailable,
    }

    private companion object {
        const val MAXIMUM_PERSON_CHARACTERS = 256
        const val MAXIMUM_CONVERSATION_TITLE_CHARACTERS = 256
        const val MAXIMUM_BODY_CHARACTERS = 4_096
        const val MAXIMUM_RETAINED_MESSAGES = 25
        const val CONTENT_INTENT_SALT = 0x434F4E54
        const val INLINE_REPLY_SALT = 0x52504C59
        const val MARK_AS_READ_SALT = 0x4D41524B
        const val LOCAL_USER_PERSON_KEY = "aurorasms-local-user"
        val notificationMutationLock = Any()
    }
}

internal data class ActiveNotificationSnapshot(
    val tag: String?,
    val id: Int,
    val notification: Notification,
)

internal interface NotificationMutationGateway {
    fun notify(
        tag: String,
        notificationId: Int,
        notification: Notification,
    )

    fun cancel(tag: String, notificationId: Int)

    fun activeNotifications(): List<ActiveNotificationSnapshot>
}

private class AndroidNotificationMutationGateway(
    private val notificationManager: NotificationManagerCompat,
    private val platformNotificationManager: NotificationManager,
) : NotificationMutationGateway {
    private val recentlyPosted = linkedMapOf<NotificationSlot, RecentNotification>()

    @SuppressLint("MissingPermission")
    override fun notify(
        tag: String,
        notificationId: Int,
        notification: Notification,
    ) {
        notificationManager.notify(tag, notificationId, notification)
        synchronized(recentlyPosted) {
            val now = SystemClock.elapsedRealtime()
            discardExpiredLocked(now)
            val slot = NotificationSlot(tag, notificationId)
            recentlyPosted.remove(slot)
            recentlyPosted[slot] = RecentNotification(
                snapshot = ActiveNotificationSnapshot(tag, notificationId, notification),
                postedAtElapsedMillis = now,
            )
            while (recentlyPosted.size > MAXIMUM_RECENT_POSTS) {
                recentlyPosted.entries.iterator().run {
                    next()
                    remove()
                }
            }
        }
    }

    override fun cancel(tag: String, notificationId: Int) {
        notificationManager.cancel(tag, notificationId)
        synchronized(recentlyPosted) {
            recentlyPosted.remove(NotificationSlot(tag, notificationId))
        }
    }

    override fun activeNotifications(): List<ActiveNotificationSnapshot> {
        val platform = platformNotificationManager.activeNotifications.map { active ->
            ActiveNotificationSnapshot(
                tag = active.tag,
                id = active.id,
                notification = active.notification,
            )
        }
        return synchronized(recentlyPosted) {
            val now = SystemClock.elapsedRealtime()
            discardExpiredLocked(now)
            val platformSlots = platform.mapTo(mutableSetOf()) { active ->
                NotificationSlot(active.tag, active.id)
            }
            platformSlots.forEach(recentlyPosted::remove)
            platform + recentlyPosted
                .filterKeys { it !in platformSlots }
                .values
                .map(RecentNotification::snapshot)
        }
    }

    private fun discardExpiredLocked(now: Long) {
        recentlyPosted.entries.removeAll { (_, recent) ->
            now - recent.postedAtElapsedMillis > RECENT_POST_VISIBILITY_WINDOW_MILLIS
        }
    }

    private companion object {
        const val MAXIMUM_RECENT_POSTS = 64
        const val RECENT_POST_VISIBILITY_WINDOW_MILLIS = 2_000L
    }
}

private data class NotificationSlot(
    val tag: String?,
    val id: Int,
)

private data class RecentNotification(
    val snapshot: ActiveNotificationSnapshot,
    val postedAtElapsedMillis: Long,
)

internal const val SOURCE_MESSAGE_ID_EXTRA =
    "org.aurorasms.core.notifications.extra.SOURCE_MESSAGE_ID"

internal const val REPLY_OPERATION_ID_EXTRA =
    "org.aurorasms.core.notifications.extra.REPLY_OPERATION_ID"

internal const val NOTIFICATION_PRIVACY_EXTRA =
    "org.aurorasms.core.notifications.extra.PRIVACY"

internal const val NOTIFICATION_GROUP_CONVERSATION_EXTRA =
    "org.aurorasms.core.notifications.extra.GROUP_CONVERSATION"

internal fun sourceMessageIdMarker(messageId: MessageId): String =
    "${messageId.kind.name}:${messageId.value}"

internal fun parseSourceMessageIdMarker(marker: String?): MessageId? {
    if (marker == null || marker.count { it == ':' } != 1) return null
    val separator = marker.indexOf(':')
    val kind = ProviderKind.entries.firstOrNull { candidate ->
        candidate.name == marker.substring(0, separator)
    } ?: return null
    if (!kind.isTelephonyProvider) return null
    val value = marker.substring(separator + 1).toLongOrNull() ?: return null
    val messageId = runCatching { MessageId(kind, value) }.getOrNull() ?: return null
    return messageId.takeIf { sourceMessageIdMarker(it) == marker }
}

internal fun replyOperationIdMarker(operationId: MessageId): String {
    require(operationId.kind == ProviderKind.PENDING_OPERATION)
    return operationId.value.toString()
}

internal fun parseReplyOperationIdMarker(marker: String?): MessageId? {
    val value = marker?.toCanonicalPositiveLongOrNull() ?: return null
    return MessageId(ProviderKind.PENDING_OPERATION, value)
        .takeIf { replyOperationIdMarker(it) == marker }
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

internal fun conversationTag(conversationId: ConversationId): String =
    "aurora-conversation:${conversationId.value}"

internal fun parseConversationTag(tag: String?): ConversationId? {
    if (tag == null || !tag.startsWith(CONVERSATION_TAG_PREFIX)) return null
    val value = tag.removePrefix(CONVERSATION_TAG_PREFIX)
        .toCanonicalPositiveLongOrNull()
        ?: return null
    return ConversationId(value).takeIf { conversationTag(it) == tag }
}

internal fun replyFailureTag(key: InlineReplyFailureKey): String =
    "$REPLY_FAILURE_TAG_PREFIX${key.conversationId.value}:${key.operationId.value}"

internal fun legacyReplyFailureTag(conversationId: ConversationId): String =
    "$REPLY_FAILURE_TAG_PREFIX${conversationId.value}"

internal fun parseLegacyReplyFailureTag(tag: String?): ConversationId? {
    if (tag == null || !tag.startsWith(REPLY_FAILURE_TAG_PREFIX)) return null
    val value = tag.removePrefix(REPLY_FAILURE_TAG_PREFIX)
        .toCanonicalPositiveLongOrNull()
        ?: return null
    return ConversationId(value).takeIf { candidate ->
        tag == legacyReplyFailureTag(candidate)
    }
}

private const val CONVERSATION_TAG_PREFIX = "aurora-conversation:"
private const val REPLY_FAILURE_TAG_PREFIX = "aurora-reply-failure:"
