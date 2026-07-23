// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAutoNotificationContractTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private lateinit var gateway: RecordingNotificationGateway
    private lateinit var notifier: AndroidMessageNotifier

    @Before
    fun setUp() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        NotificationChannels.ensureCreated(context)
        manager.cancelAll()
        gateway = RecordingNotificationGateway()
        notifier = AndroidMessageNotifier(
            context = context,
            intentFactory = NotificationIntentFactory {
                Intent().setComponent(
                    ComponentName(context, MessagingNotificationActionService::class.java),
                )
            },
            incomingGenerationTracker = IncomingNotificationGenerationTracker(
                InMemoryIncomingNotificationGenerationStore(),
            ),
            notificationGateway = gateway,
        )
    }

    @After
    fun tearDown() {
        manager.cancelAll()
    }

    @Test
    fun incomingNotificationCarriesAndroidAutoReplyAndMarkReadActions() {
        post(incoming(conversation = 41L, message = 101L, body = "Synthetic message"))

        val notification = gateway.active.single().notification
        val reply = requireNotNull(NotificationCompat.getAction(notification, 0))
        assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_REPLY, reply.semanticAction)
        assertFalse(reply.showsUserInterface)
        assertFalse(reply.isAuthenticationRequired)
        assertEquals(1, reply.remoteInputs?.size)
        val replyIntent = requireNotNull(reply.actionIntent)
        assertEquals(context.packageName, replyIntent.creatorPackage)
        if (Build.VERSION.SDK_INT >= 31) {
            assertFalse(replyIntent.isActivity)
            assertFalse(replyIntent.isBroadcast)
        }

        val markRead = NotificationCompat.getInvisibleActions(notification).single()
        assertEquals(
            NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ,
            markRead.semanticAction,
        )
        assertFalse(markRead.showsUserInterface)
        assertFalse(markRead.isAuthenticationRequired)
        assertTrue(markRead.remoteInputs.isNullOrEmpty())
        assertEquals(context.packageName, requireNotNull(markRead.actionIntent).creatorPackage)
    }

    @Test
    fun messagingStylePreservesBoundedChronologicalHistoryForSamePrivacy() {
        repeat(31) { offset ->
            post(
                incoming(
                    conversation = 52L,
                    message = 200L + offset,
                    body = "Synthetic body $offset",
                    receivedAt = 10_000L + offset,
                    canReply = false,
                ),
            )
        }

        val style = requireNotNull(
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(
                gateway.active.single().notification,
            ),
        )
        assertEquals(25, style.messages.size)
        assertEquals("Synthetic body 6", style.messages.first().text.toString())
        assertEquals("Synthetic body 30", style.messages.last().text.toString())
        assertEquals(
            (10_006L..10_030L).toList(),
            style.messages.map { it.timestamp },
        )
    }

    @Test
    fun platformRoundTripRetainsSameConversationHistoryAndActionMetadata() {
        val platformNotifier = AndroidMessageNotifier(
            context = context,
            intentFactory = NotificationIntentFactory {
                Intent().setComponent(
                    ComponentName(context, MessagingNotificationActionService::class.java),
                )
            },
            incomingGenerationTracker = IncomingNotificationGenerationTracker(
                InMemoryIncomingNotificationGenerationStore(),
            ),
            notificationGateway = null,
        )
        val now = System.currentTimeMillis()
        val first = incoming(
            conversation = 57L,
            message = 251L,
            body = "Synthetic platform first",
            receivedAt = now,
        )
        val second = incoming(
            conversation = 57L,
            message = 252L,
            body = "Synthetic platform second",
            receivedAt = now + 1L,
        )
        assertTrue(
            platformNotifier.notifyIncoming(
                first,
                NotificationConfig(NotificationPrivacy.SENDER_AND_BODY),
            ) is NotificationPostResult.Posted,
        )
        assertTrue(
            platformNotifier.notifyIncoming(
                second,
                NotificationConfig(NotificationPrivacy.SENDER_AND_BODY),
            ) is NotificationPostResult.Posted,
        )

        val active = awaitPlatformNotification(
            conversationId = ConversationId(57L),
            messageId = MessageId(ProviderKind.SMS, 252L),
        )
        val style = requireNotNull(
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(
                requireNotNull(active).notification,
            ),
        )
        assertEquals(
            listOf("Synthetic platform first", "Synthetic platform second"),
            style.messages.map { it.text.toString() },
        )
        assertEquals(
            NotificationCompat.Action.SEMANTIC_ACTION_REPLY,
            requireNotNull(NotificationCompat.getAction(active.notification, 0)).semanticAction,
        )
        assertEquals(
            NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ,
            NotificationCompat.getInvisibleActions(active.notification).single().semanticAction,
        )
    }

    @Test
    fun stricterPrivacyStartsFreshHistoryAndCannotRetainPrivateContent() {
        val privateBody = "Private synthetic cobalt message"
        val privateSender = "Private Synthetic Sender"
        post(
            incoming(
                conversation = 63L,
                message = 301L,
                body = privateBody,
                sender = privateSender,
                receivedAt = 20_000L,
            ),
            NotificationPrivacy.SENDER_AND_BODY,
        )
        post(
            incoming(
                conversation = 63L,
                message = 302L,
                body = "Another private synthetic body",
                sender = privateSender,
                receivedAt = 20_001L,
            ),
            NotificationPrivacy.GENERIC,
        )

        val notification = gateway.active.single().notification
        val style = requireNotNull(
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification),
        )
        assertEquals(1, style.messages.size)
        assertEquals(context.getString(R.string.notification_generic_body), style.messages.single().text)
        assertFalse(notification.extras.toString().contains(privateBody))
        assertFalse(notification.extras.toString().contains(privateSender))
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, notification.visibility)
        assertNotNull(notification.publicVersion)
        assertFalse(notification.publicVersion.extras.toString().contains(privateBody))
    }

    @Test
    fun groupConversationMetadataIsExplicitOnlyAtFullContentPrivacy() {
        val group = incoming(
            conversation = 68L,
            message = 351L,
            body = "Synthetic group body",
            receivedAt = 25_000L,
            canReply = false,
        ).copy(
            conversationTitle = "Synthetic Observatory Group",
            isGroupConversation = true,
        )
        post(group, NotificationPrivacy.SENDER_AND_BODY)

        val visibleStyle = requireNotNull(
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(
                gateway.active.single().notification,
            ),
        )
        assertTrue(visibleStyle.isGroupConversation)
        assertEquals("Synthetic Observatory Group", visibleStyle.conversationTitle)

        post(group.copy(messageId = MessageId(ProviderKind.SMS, 352L)), NotificationPrivacy.GENERIC)
        val privateStyle = requireNotNull(
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(
                gateway.active.single().notification,
            ),
        )
        assertFalse(privateStyle.isGroupConversation)
        assertEquals(null, privateStyle.conversationTitle)
        assertFalse(
            gateway.active.single().notification.extras.toString()
                .contains("Synthetic Observatory Group"),
        )
    }

    @Test
    fun exactGenerationRepostDoesNotDuplicateLatestMessage() {
        val message = incoming(
            conversation = 74L,
            message = 401L,
            body = "Exact synthetic generation",
            receivedAt = 30_000L,
            canReply = false,
        )
        post(message)
        post(message)

        val style = requireNotNull(
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(
                gateway.active.single().notification,
            ),
        )
        assertEquals(1, style.messages.size)
    }

    @Test
    fun markReadProtocolAcceptsOnlyCanonicalSmsGenerationShape() {
        val request = MarkConversationReadRequest(
            conversationId = ConversationId(85L),
            throughMessageId = MessageId(ProviderKind.SMS, 501L),
        )
        val valid = markConversationReadData(request)

        assertEquals(request, parseMarkConversationReadData(valid))
        assertEquals(null, parseMarkConversationReadData(valid.buildUpon().authority("outside").build()))
        assertEquals(null, parseMarkConversationReadData(valid.buildUpon().appendPath("extra").build()))
        assertEquals(
            null,
            parseMarkConversationReadData(
                valid.buildUpon().path("/85/MMS/501").build(),
            ),
        )
    }

    private fun post(
        message: IncomingMessageNotification,
        privacy: NotificationPrivacy = NotificationPrivacy.SENDER_AND_BODY,
    ) {
        assertTrue(
            notifier.notifyIncoming(message, NotificationConfig(privacy = privacy)) is
                NotificationPostResult.Posted,
        )
    }

    private fun incoming(
        conversation: Long,
        message: Long,
        body: String,
        sender: String = "Synthetic Sender",
        receivedAt: Long = 1_704_067_200_000L,
        canReply: Boolean = true,
    ) = IncomingMessageNotification(
        messageId = MessageId(ProviderKind.SMS, message),
        conversationId = ConversationId(conversation),
        senderDisplayName = sender,
        senderPersonKey = "synthetic-person-$conversation",
        body = body,
        receivedAtEpochMillis = receivedAt,
        canReply = canReply,
    )

    private fun awaitPlatformNotification(
        conversationId: ConversationId,
        messageId: MessageId,
    ) = run {
        repeat(40) {
            manager.activeNotifications.firstOrNull { active ->
                active.tag == conversationTag(conversationId) &&
                    active.id == notificationIdForConversation(conversationId) &&
                    active.notification.extras.getString(SOURCE_MESSAGE_ID_EXTRA) ==
                    sourceMessageIdMarker(messageId)
            }?.let { return@run it }
            SystemClock.sleep(50L)
        }
        manager.activeNotifications.firstOrNull { active ->
            active.tag == conversationTag(conversationId) &&
                active.id == notificationIdForConversation(conversationId) &&
                active.notification.extras.getString(SOURCE_MESSAGE_ID_EXTRA) ==
                sourceMessageIdMarker(messageId)
        }
    }
}

private class RecordingNotificationGateway : NotificationMutationGateway {
    val active = mutableListOf<ActiveNotificationSnapshot>()

    override fun notify(tag: String, notificationId: Int, notification: Notification) {
        active.removeAll { it.tag == tag && it.id == notificationId }
        active += ActiveNotificationSnapshot(tag, notificationId, notification)
    }

    override fun cancel(tag: String, notificationId: Int) {
        active.removeAll { it.tag == tag && it.id == notificationId }
    }

    override fun activeNotifications(): List<ActiveNotificationSnapshot> = active.toList()
}
