// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMessageNotifierGenerationCancellationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifier = AndroidMessageNotifier(context) {
        Intent().setComponent(ComponentName(context, InlineReplyReceiver::class.java))
    }

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
        manager.cancelAll()
        awaitNoActiveNotifications()
        clearGenerationPreferences(INCOMING_NOTIFICATION_GENERATION_PREFERENCE_NAME)
        clearGenerationPreferences(TEST_GENERATION_PREFERENCE_NAME)
        NotificationChannels.ensureCreated(context)
    }

    @After
    fun tearDown() {
        manager.cancelAll()
        clearGenerationPreferences(INCOMING_NOTIFICATION_GENERATION_PREFERENCE_NAME)
        clearGenerationPreferences(TEST_GENERATION_PREFERENCE_NAME)
    }

    @Test
    fun matchingGenerationIsCancelled() {
        val message = incomingMessage(
            MATCHING_CONVERSATION_ID,
            MATCHING_MESSAGE_ID,
            "matching generation",
        )

        assertEquals(
            NotificationPostResult.Posted(
                notificationIdForConversation(MATCHING_CONVERSATION_ID),
            ),
            notifier.notifyIncoming(message, VISIBLE_CONFIG),
        )
        assertNotNull(awaitActiveConversationNotification(MATCHING_CONVERSATION_ID))

        assertSame(
            NotificationCancelResult.Cancelled,
            notifier.cancelIncomingConversation(
                MATCHING_CONVERSATION_ID,
                MATCHING_MESSAGE_ID,
            ),
        )
        assertNull(awaitNoActiveConversationNotification(MATCHING_CONVERSATION_ID))
    }

    @Test
    fun newerSameConversationGenerationIsPreserved() {
        val older = incomingMessage(
            REPLACEMENT_CONVERSATION_ID,
            OLDER_MESSAGE_ID,
            "older generation",
        )
        val newer = incomingMessage(
            REPLACEMENT_CONVERSATION_ID,
            NEWER_MESSAGE_ID,
            "newer generation",
        )

        assertEquals(
            NotificationPostResult.Posted(
                notificationIdForConversation(REPLACEMENT_CONVERSATION_ID),
            ),
            notifier.notifyIncoming(older, VISIBLE_CONFIG),
        )
        assertNotNull(
            awaitActiveConversationNotification(
                REPLACEMENT_CONVERSATION_ID,
                OLDER_MESSAGE_ID,
            ),
        )
        assertEquals(
            NotificationPostResult.Posted(
                notificationIdForConversation(REPLACEMENT_CONVERSATION_ID),
            ),
            notifier.notifyIncoming(newer, VISIBLE_CONFIG),
        )
        assertNotNull(
            awaitActiveConversationNotification(
                REPLACEMENT_CONVERSATION_ID,
                NEWER_MESSAGE_ID,
            ),
        )

        assertSame(
            NotificationCancelResult.AlreadyAbsentOrReplaced,
            notifier.cancelIncomingConversation(
                REPLACEMENT_CONVERSATION_ID,
                OLDER_MESSAGE_ID,
            ),
        )
        val preserved = requireNotNull(
            awaitActiveConversationNotification(
                REPLACEMENT_CONVERSATION_ID,
                NEWER_MESSAGE_ID,
            ),
        )
        assertEquals(
            sourceMessageIdMarker(NEWER_MESSAGE_ID),
            preserved.notification.extras.getString(SOURCE_MESSAGE_ID_EXTRA),
        )
        assertEquals(
            "newer generation",
            preserved.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }

    @Test
    fun roleLossBulkCancellationUsesExactActiveGenerationMarkers() {
        val gateway = FakeNotificationMutationGateway()
        val tracker = sharedPreferencesTracker()
        val seamNotifier = notifierWithTracker(gateway, tracker)
        assertTrue(
            seamNotifier.notifyIncoming(
                incomingMessage(CAPACITY_CONVERSATION_ONE, MESSAGE_ONE, "first active"),
                VISIBLE_CONFIG,
            ) is NotificationPostResult.Posted,
        )
        assertTrue(
            seamNotifier.notifyIncoming(
                incomingMessage(CAPACITY_CONVERSATION_TWO, MESSAGE_TWO, "second active"),
                VISIBLE_CONFIG,
            ) is NotificationPostResult.Posted,
        )

        assertSame(NotificationCancelResult.Cancelled, seamNotifier.cancelAllIncoming())

        assertEquals(
            setOf(
                conversationTag(CAPACITY_CONVERSATION_ONE) to
                    notificationIdForConversation(CAPACITY_CONVERSATION_ONE),
                conversationTag(CAPACITY_CONVERSATION_TWO) to
                    notificationIdForConversation(CAPACITY_CONVERSATION_TWO),
            ),
            gateway.cancelCalls.toSet(),
        )
        assertTrue(gateway.active.isEmpty())
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Untracked,
            tracker.lookup(CAPACITY_CONVERSATION_ONE),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Untracked,
            tracker.lookup(CAPACITY_CONVERSATION_TWO),
        )
    }

    @Test
    fun emptyRetrySnapshotClearsTrackerAfterCancelPersistenceFailure() {
        val gateway = FakeNotificationMutationGateway()
        val store = FailNextWriteGenerationStore()
        val tracker = IncomingNotificationGenerationTracker(store)
        val seamNotifier = notifierWithTracker(gateway, tracker)
        assertTrue(
            seamNotifier.notifyIncoming(
                incomingMessage(SEAM_CONVERSATION_ID, MATCHING_MESSAGE_ID, "active"),
                VISIBLE_CONFIG,
            ) is NotificationPostResult.Posted,
        )
        store.failNextWrite = true

        assertSame(NotificationCancelResult.RetryableFailure, seamNotifier.cancelAllIncoming())
        assertTrue(gateway.active.isEmpty())
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Tracked(MATCHING_MESSAGE_ID),
            tracker.lookup(SEAM_CONVERSATION_ID),
        )

        assertSame(
            NotificationCancelResult.AlreadyAbsentOrReplaced,
            seamNotifier.cancelAllIncoming(),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Untracked,
            tracker.lookup(SEAM_CONVERSATION_ID),
        )
    }

    @SuppressLint("MissingPermission")
    @Test
    fun occupiedSlotWithoutGenerationMarkerIsRetryableAndPreserved() {
        manager.notify(
            conversationTag(UNMARKED_CONVERSATION_ID),
            notificationIdForConversation(UNMARKED_CONVERSATION_ID),
            NotificationCompat.Builder(context, NotificationChannels.MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Unmarked synthetic notification")
                .build(),
        )
        assertNotNull(awaitActiveConversationNotification(UNMARKED_CONVERSATION_ID))

        assertSame(
            NotificationCancelResult.RetryableFailure,
            notifier.cancelIncomingConversation(
                UNMARKED_CONVERSATION_ID,
                MATCHING_MESSAGE_ID,
            ),
        )
        assertNotNull(awaitActiveConversationNotification(UNMARKED_CONVERSATION_ID))
    }

    @Test
    fun recreatedStorePreservesNewerGenerationWhileActiveSnapshotStillShowsOldNotification() {
        val gateway = FakeNotificationMutationGateway()
        val olderNotifier = seamNotifier(gateway)
        assertEquals(
            NotificationPostResult.Posted(notificationIdForConversation(SEAM_CONVERSATION_ID)),
            olderNotifier.notifyIncoming(
                incomingMessage(SEAM_CONVERSATION_ID, OLDER_MESSAGE_ID, "old active"),
                VISIBLE_CONFIG,
            ),
        )
        gateway.preserveActiveOnNotify = true

        val replacementNotifier = seamNotifier(gateway)
        assertEquals(
            NotificationPostResult.Posted(notificationIdForConversation(SEAM_CONVERSATION_ID)),
            replacementNotifier.notifyIncoming(
                incomingMessage(SEAM_CONVERSATION_ID, NEWER_MESSAGE_ID, "new persisted"),
                VISIBLE_CONFIG,
            ),
        )
        val recreatedTracker = sharedPreferencesTracker()
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Tracked(NEWER_MESSAGE_ID),
            recreatedTracker.lookup(SEAM_CONVERSATION_ID),
        )

        val recreatedNotifier = seamNotifier(gateway)
        assertSame(
            NotificationCancelResult.AlreadyAbsentOrReplaced,
            recreatedNotifier.cancelIncomingConversation(
                SEAM_CONVERSATION_ID,
                OLDER_MESSAGE_ID,
            ),
        )
        assertTrue(gateway.cancelCalls.isEmpty())
        assertEquals(
            sourceMessageIdMarker(OLDER_MESSAGE_ID),
            gateway.active.single().notification.extras.getString(SOURCE_MESSAGE_ID_EXTRA),
        )
    }

    @Test
    fun olderReplayIsSupersededByNewerTrackedGenerationWithoutMutation() {
        val gateway = FakeNotificationMutationGateway().apply {
            activeSnapshotFailure = IllegalStateException("active snapshot must not be required")
        }
        val tracker = sharedPreferencesTracker()
        assertEquals(
            IncomingNotificationGenerationTracker.RecordResult.Recorded,
            tracker.record(SEAM_CONVERSATION_ID, NEWER_MESSAGE_ID),
        )
        val encodedBefore = generationState(TEST_GENERATION_PREFERENCE_NAME)

        assertSame(
            NotificationPostResult.SupersededByNewer,
            notifierWithTracker(gateway, tracker).notifyIncoming(
                incomingMessage(
                    SEAM_CONVERSATION_ID,
                    OLDER_MESSAGE_ID,
                    "older replay",
                    OLDER_RECEIVED_AT,
                ),
                VISIBLE_CONFIG,
            ),
        )

        assertTrue(gateway.notifyCalls.isEmpty())
        assertTrue(gateway.cancelCalls.isEmpty())
        assertEquals(encodedBefore, generationState(TEST_GENERATION_PREFERENCE_NAME))
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Tracked(NEWER_MESSAGE_ID),
            tracker.lookup(SEAM_CONVERSATION_ID),
        )
    }

    @Test
    fun olderReplayIsSupersededByNewerActiveTimestampWithoutMutation() {
        val gateway = FakeNotificationMutationGateway()
        val active = activeIncomingSnapshot(
            conversationId = SEAM_CONVERSATION_ID,
            messageId = NEWER_MESSAGE_ID,
            receivedAtEpochMillis = NEWER_RECEIVED_AT,
        )
        gateway.active += active
        val tracker = sharedPreferencesTracker()

        assertSame(
            NotificationPostResult.SupersededByNewer,
            notifierWithTracker(gateway, tracker).notifyIncoming(
                incomingMessage(
                    SEAM_CONVERSATION_ID,
                    OLDER_MESSAGE_ID,
                    "older replay",
                    OLDER_RECEIVED_AT,
                ),
                VISIBLE_CONFIG,
            ),
        )

        assertTrue(gateway.notifyCalls.isEmpty())
        assertTrue(gateway.cancelCalls.isEmpty())
        assertSame(active, gateway.active.single())
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Untracked,
            tracker.lookup(SEAM_CONVERSATION_ID),
        )
    }

    @Test
    fun equalTimestampHigherSameProviderActiveIdSupersedesWithoutMutation() {
        val gateway = FakeNotificationMutationGateway()
        val active = activeIncomingSnapshot(
            conversationId = SEAM_CONVERSATION_ID,
            messageId = NEWER_MESSAGE_ID,
            receivedAtEpochMillis = NEWER_RECEIVED_AT,
        )
        gateway.active += active
        val tracker = sharedPreferencesTracker()

        assertSame(
            NotificationPostResult.SupersededByNewer,
            notifierWithTracker(gateway, tracker).notifyIncoming(
                incomingMessage(
                    SEAM_CONVERSATION_ID,
                    OLDER_MESSAGE_ID,
                    "equal-time older replay",
                    NEWER_RECEIVED_AT,
                ),
                VISIBLE_CONFIG,
            ),
        )

        assertTrue(gateway.notifyCalls.isEmpty())
        assertSame(active, gateway.active.single())
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Untracked,
            tracker.lookup(SEAM_CONVERSATION_ID),
        )
    }

    @Test
    fun higherSameProviderCandidateProceedsDespiteClockRollback() {
        val gateway = FakeNotificationMutationGateway()
        gateway.active += activeIncomingSnapshot(
            conversationId = SEAM_CONVERSATION_ID,
            messageId = OLDER_MESSAGE_ID,
            receivedAtEpochMillis = NEWER_RECEIVED_AT,
        )
        val tracker = sharedPreferencesTracker()

        assertEquals(
            NotificationPostResult.Posted(notificationIdForConversation(SEAM_CONVERSATION_ID)),
            notifierWithTracker(gateway, tracker).notifyIncoming(
                incomingMessage(
                    SEAM_CONVERSATION_ID,
                    NEWER_MESSAGE_ID,
                    "newer provider row after clock rollback",
                    OLDER_RECEIVED_AT,
                ),
                VISIBLE_CONFIG,
            ),
        )

        assertEquals(1, gateway.notifyCalls.size)
        assertEquals(
            sourceMessageIdMarker(NEWER_MESSAGE_ID),
            gateway.active.single().notification.extras.getString(SOURCE_MESSAGE_ID_EXTRA),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Tracked(NEWER_MESSAGE_ID),
            tracker.lookup(SEAM_CONVERSATION_ID),
        )
    }

    @Test
    fun lowerSameProviderCandidateIsSupersededDespiteNewerWallClock() {
        val gateway = FakeNotificationMutationGateway()
        val active = activeIncomingSnapshot(
            conversationId = SEAM_CONVERSATION_ID,
            messageId = NEWER_MESSAGE_ID,
            receivedAtEpochMillis = OLDER_RECEIVED_AT,
        )
        gateway.active += active
        val tracker = sharedPreferencesTracker()

        assertSame(
            NotificationPostResult.SupersededByNewer,
            notifierWithTracker(gateway, tracker).notifyIncoming(
                incomingMessage(
                    SEAM_CONVERSATION_ID,
                    OLDER_MESSAGE_ID,
                    "older provider row after clock advance",
                    NEWER_RECEIVED_AT,
                ),
                VISIBLE_CONFIG,
            ),
        )

        assertTrue(gateway.notifyCalls.isEmpty())
        assertSame(active, gateway.active.single())
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Untracked,
            tracker.lookup(SEAM_CONVERSATION_ID),
        )
    }

    @Test
    fun equalTimestampMalformedActiveGenerationRejectsWithoutMutation() {
        val gateway = FakeNotificationMutationGateway()
        val active = activeIncomingSnapshot(
            conversationId = SEAM_CONVERSATION_ID,
            messageId = null,
            receivedAtEpochMillis = NEWER_RECEIVED_AT,
        )
        gateway.active += active
        val tracker = sharedPreferencesTracker()

        assertEquals(
            NotificationPostResult.Rejected(
                NotificationPostResult.RejectionReason.GENERATION_STATE_UNAVAILABLE,
            ),
            notifierWithTracker(gateway, tracker).notifyIncoming(
                incomingMessage(
                    SEAM_CONVERSATION_ID,
                    OLDER_MESSAGE_ID,
                    "ambiguous equal-time replay",
                    NEWER_RECEIVED_AT,
                ),
                VISIBLE_CONFIG,
            ),
        )

        assertTrue(gateway.notifyCalls.isEmpty())
        assertTrue(gateway.cancelCalls.isEmpty())
        assertSame(active, gateway.active.single())
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Untracked,
            tracker.lookup(SEAM_CONVERSATION_ID),
        )
    }

    @Test
    fun capacityReconciliationReclaimsOnlyProvablyAbsentConversation() {
        val gateway = FakeNotificationMutationGateway()
        val firstNotifier = seamNotifier(gateway, maximumTrackedConversations = 1)
        assertTrue(
            firstNotifier.notifyIncoming(
                incomingMessage(CAPACITY_CONVERSATION_ONE, MESSAGE_ONE, "first"),
                VISIBLE_CONFIG,
            ) is NotificationPostResult.Posted,
        )
        gateway.active.clear()

        val recreatedNotifier = seamNotifier(gateway, maximumTrackedConversations = 1)
        assertTrue(
            recreatedNotifier.notifyIncoming(
                incomingMessage(CAPACITY_CONVERSATION_TWO, MESSAGE_TWO, "second"),
                VISIBLE_CONFIG,
            ) is NotificationPostResult.Posted,
        )

        val recreatedTracker = sharedPreferencesTracker(maximumTrackedConversations = 1)
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Untracked,
            recreatedTracker.lookup(CAPACITY_CONVERSATION_ONE),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Tracked(MESSAGE_TWO),
            recreatedTracker.lookup(CAPACITY_CONVERSATION_TWO),
        )
        assertEquals(2, gateway.notifyCalls.size)
    }

    @Test
    fun capacitySnapshotFailureRejectsBeforeNotifyAndKeepsDurableState() {
        val gateway = FakeNotificationMutationGateway()
        val firstNotifier = seamNotifier(gateway, maximumTrackedConversations = 1)
        assertTrue(
            firstNotifier.notifyIncoming(
                incomingMessage(CAPACITY_CONVERSATION_ONE, MESSAGE_ONE, "first"),
                VISIBLE_CONFIG,
            ) is NotificationPostResult.Posted,
        )
        gateway.activeSnapshotFailure = IllegalStateException("synthetic active query failure")

        val result = seamNotifier(gateway, maximumTrackedConversations = 1).notifyIncoming(
            incomingMessage(CAPACITY_CONVERSATION_TWO, MESSAGE_TWO, "rejected"),
            VISIBLE_CONFIG,
        )

        assertEquals(
            NotificationPostResult.Rejected(
                NotificationPostResult.RejectionReason.GENERATION_STATE_UNAVAILABLE,
            ),
            result,
        )
        assertEquals(1, gateway.notifyCalls.size)
        val recreatedTracker = sharedPreferencesTracker(maximumTrackedConversations = 1)
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Tracked(MESSAGE_ONE),
            recreatedTracker.lookup(CAPACITY_CONVERSATION_ONE),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Untracked,
            recreatedTracker.lookup(CAPACITY_CONVERSATION_TWO),
        )
    }

    @Test
    fun fullActiveCapacityRejectsWithoutPersistingOverflowForUnpostedGeneration() {
        val gateway = FakeNotificationMutationGateway()
        assertTrue(
            seamNotifier(gateway, maximumTrackedConversations = 1).notifyIncoming(
                incomingMessage(CAPACITY_CONVERSATION_ONE, MESSAGE_ONE, "first"),
                VISIBLE_CONFIG,
            ) is NotificationPostResult.Posted,
        )

        assertEquals(
            NotificationPostResult.Rejected(
                NotificationPostResult.RejectionReason.GENERATION_STATE_UNAVAILABLE,
            ),
            seamNotifier(gateway, maximumTrackedConversations = 1).notifyIncoming(
                incomingMessage(CAPACITY_CONVERSATION_TWO, MESSAGE_TWO, "overflow"),
                VISIBLE_CONFIG,
            ),
        )
        assertEquals(1, gateway.notifyCalls.size)
        val recreatedTracker = sharedPreferencesTracker(maximumTrackedConversations = 1)
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Tracked(MESSAGE_ONE),
            recreatedTracker.lookup(CAPACITY_CONVERSATION_ONE),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Untracked,
            recreatedTracker.lookup(CAPACITY_CONVERSATION_TWO),
        )
    }

    @SuppressLint("ApplySharedPref", "UseKtx")
    @Test
    fun corruptGenerationStateIsRebuiltFromActiveSnapshotBeforePostingNewGeneration() {
        val gateway = FakeNotificationMutationGateway()
        assertEquals(
            NotificationPostResult.Posted(
                notificationIdForConversation(CAPACITY_CONVERSATION_ONE),
            ),
            seamNotifier(gateway).notifyIncoming(
                incomingMessage(CAPACITY_CONVERSATION_ONE, MESSAGE_ONE, "active before corruption"),
                VISIBLE_CONFIG,
            ),
        )
        check(
            context.getSharedPreferences(TEST_GENERATION_PREFERENCE_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(GENERATION_STATE_KEY, "not-a-generation-state")
                .commit(),
        )

        assertEquals(
            NotificationPostResult.Posted(
                notificationIdForConversation(CAPACITY_CONVERSATION_TWO),
            ),
            seamNotifier(gateway).notifyIncoming(
                incomingMessage(CAPACITY_CONVERSATION_TWO, MESSAGE_TWO, "posted after rebuild"),
                VISIBLE_CONFIG,
            ),
        )

        val recreatedTracker = sharedPreferencesTracker()
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Tracked(MESSAGE_ONE),
            recreatedTracker.lookup(CAPACITY_CONVERSATION_ONE),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Tracked(MESSAGE_TWO),
            recreatedTracker.lookup(CAPACITY_CONVERSATION_TWO),
        )
        assertEquals(2, gateway.notifyCalls.size)
        assertEquals(2, gateway.active.size)
    }

    @Test
    fun generationPersistenceFailureRejectsBeforeNmsNotify() {
        val gateway = FakeNotificationMutationGateway()
        val notifier = notifierWithTracker(
            gateway = gateway,
            tracker = IncomingNotificationGenerationTracker(FailingGenerationStore),
        )

        assertEquals(
            NotificationPostResult.Rejected(
                NotificationPostResult.RejectionReason.GENERATION_STATE_UNAVAILABLE,
            ),
            notifier.notifyIncoming(
                incomingMessage(SEAM_CONVERSATION_ID, NEWER_MESSAGE_ID, "never posted"),
                VISIBLE_CONFIG,
            ),
        )
        assertTrue(gateway.notifyCalls.isEmpty())
    }

    @Test
    fun notifyFailureNeverRollsBackNewerPersistedGeneration() {
        val gateway = FakeNotificationMutationGateway()
        assertTrue(
            seamNotifier(gateway).notifyIncoming(
                incomingMessage(SEAM_CONVERSATION_ID, OLDER_MESSAGE_ID, "old active"),
                VISIBLE_CONFIG,
            ) is NotificationPostResult.Posted,
        )
        gateway.notifyFailure = SecurityException("synthetic notification denial")
        gateway.beforeNotify = {
            assertEquals(
                IncomingNotificationGenerationTracker.Lookup.Tracked(NEWER_MESSAGE_ID),
                sharedPreferencesTracker().lookup(SEAM_CONVERSATION_ID),
            )
        }

        assertEquals(
            NotificationPostResult.Rejected(
                NotificationPostResult.RejectionReason.PERMISSION_DENIED,
            ),
            seamNotifier(gateway).notifyIncoming(
                incomingMessage(SEAM_CONVERSATION_ID, NEWER_MESSAGE_ID, "new durable"),
                VISIBLE_CONFIG,
            ),
        )
        assertEquals(
            IncomingNotificationGenerationTracker.Lookup.Tracked(NEWER_MESSAGE_ID),
            sharedPreferencesTracker().lookup(SEAM_CONVERSATION_ID),
        )
        gateway.notifyFailure = null
        assertSame(
            NotificationCancelResult.AlreadyAbsentOrReplaced,
            seamNotifier(gateway).cancelIncomingConversation(
                SEAM_CONVERSATION_ID,
                OLDER_MESSAGE_ID,
            ),
        )
        assertTrue(gateway.cancelCalls.isEmpty())
    }

    private fun seamNotifier(
        gateway: NotificationMutationGateway,
        maximumTrackedConversations: Int = 4_096,
    ) = notifierWithTracker(
        gateway = gateway,
        tracker = sharedPreferencesTracker(maximumTrackedConversations),
    )

    private fun notifierWithTracker(
        gateway: NotificationMutationGateway,
        tracker: IncomingNotificationGenerationTracker,
    ) = AndroidMessageNotifier(
        context = context,
        intentFactory = NotificationIntentFactory {
            Intent().setComponent(ComponentName(context, InlineReplyReceiver::class.java))
        },
        incomingGenerationTracker = tracker,
        notificationGateway = gateway,
    )

    private fun sharedPreferencesTracker(
        maximumTrackedConversations: Int = 4_096,
    ) = IncomingNotificationGenerationTracker(
        store = SharedPreferencesIncomingNotificationGenerationStore(
            context,
            TEST_GENERATION_PREFERENCE_NAME,
        ),
        maximumTrackedConversations = maximumTrackedConversations,
    )

    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun clearGenerationPreferences(name: String) {
        check(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
    }

    private fun generationState(name: String): String? =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)
            .getString(GENERATION_STATE_KEY, null)

    private fun incomingMessage(
        conversationId: ConversationId,
        messageId: MessageId,
        body: String,
        receivedAtEpochMillis: Long = System.currentTimeMillis(),
    ) =
        IncomingMessageNotification(
            messageId = messageId,
            conversationId = conversationId,
            senderDisplayName = "Synthetic sender",
            senderPersonKey = "synthetic-sender-key",
            body = body,
            receivedAtEpochMillis = receivedAtEpochMillis,
            canReply = false,
        )

    private fun activeIncomingSnapshot(
        conversationId: ConversationId,
        messageId: MessageId?,
        receivedAtEpochMillis: Long,
    ): ActiveNotificationSnapshot {
        val extras = Bundle()
        messageId?.let { extras.putString(SOURCE_MESSAGE_ID_EXTRA, sourceMessageIdMarker(it)) }
        return ActiveNotificationSnapshot(
            tag = conversationTag(conversationId),
            id = notificationIdForConversation(conversationId),
            notification = NotificationCompat.Builder(context, NotificationChannels.MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setWhen(receivedAtEpochMillis)
                .addExtras(extras)
                .build(),
        )
    }

    private fun awaitActiveConversationNotification(
        conversationId: ConversationId,
        expectedMessageId: MessageId? = null,
    ): StatusBarNotification? {
        repeat(POLL_ATTEMPTS) {
            activeConversationNotification(conversationId)
                ?.takeIf { notification ->
                    expectedMessageId == null ||
                        notification.notification.extras.getString(SOURCE_MESSAGE_ID_EXTRA) ==
                        sourceMessageIdMarker(expectedMessageId)
                }
                ?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        return activeConversationNotification(conversationId)?.takeIf { notification ->
            expectedMessageId == null ||
                notification.notification.extras.getString(SOURCE_MESSAGE_ID_EXTRA) ==
                sourceMessageIdMarker(expectedMessageId)
        }
    }

    private fun awaitNoActiveConversationNotification(
        conversationId: ConversationId,
    ): StatusBarNotification? {
        repeat(POLL_ATTEMPTS) {
            if (activeConversationNotification(conversationId) == null) return null
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        return activeConversationNotification(conversationId)
    }

    private fun activeConversationNotification(
        conversationId: ConversationId,
    ): StatusBarNotification? =
        manager.activeNotifications.singleOrNull { notification ->
            notification.tag == conversationTag(conversationId) &&
                notification.id == notificationIdForConversation(conversationId)
        }

    private fun awaitNoActiveNotifications() {
        repeat(POLL_ATTEMPTS) {
            if (manager.activeNotifications.isEmpty()) return
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
    }

    private class FakeNotificationMutationGateway : NotificationMutationGateway {
        val notifyCalls = mutableListOf<ActiveNotificationSnapshot>()
        val cancelCalls = mutableListOf<Pair<String, Int>>()
        val active = mutableListOf<ActiveNotificationSnapshot>()
        var preserveActiveOnNotify = false
        var activeSnapshotFailure: RuntimeException? = null
        var notifyFailure: RuntimeException? = null
        var beforeNotify: (() -> Unit)? = null

        override fun notify(
            tag: String,
            notificationId: Int,
            notification: Notification,
        ) {
            val snapshot = ActiveNotificationSnapshot(tag, notificationId, notification)
            beforeNotify?.invoke()
            notifyCalls += snapshot
            notifyFailure?.let { throw it }
            if (!preserveActiveOnNotify) {
                active.removeAll { candidate ->
                    candidate.tag == tag && candidate.id == notificationId
                }
                active += snapshot
            }
        }

        override fun cancel(tag: String, notificationId: Int) {
            cancelCalls += tag to notificationId
            active.removeAll { candidate ->
                candidate.tag == tag && candidate.id == notificationId
            }
        }

        override fun activeNotifications(): List<ActiveNotificationSnapshot> {
            activeSnapshotFailure?.let { throw it }
            return active.toList()
        }
    }

    private data object FailingGenerationStore : IncomingNotificationGenerationStore {
        override fun read(): IncomingNotificationGenerationStore.ReadResult =
            IncomingNotificationGenerationStore.ReadResult.Available(null)

        override fun write(encoded: String): Boolean = false
    }

    private class FailNextWriteGenerationStore : IncomingNotificationGenerationStore {
        private val delegate = InMemoryIncomingNotificationGenerationStore()
        var failNextWrite: Boolean = false

        override fun read(): IncomingNotificationGenerationStore.ReadResult = delegate.read()

        override fun write(encoded: String): Boolean {
            if (failNextWrite) {
                failNextWrite = false
                return false
            }
            return delegate.write(encoded)
        }
    }

    private companion object {
        val MATCHING_CONVERSATION_ID = ConversationId(7701L)
        val REPLACEMENT_CONVERSATION_ID = ConversationId(7702L)
        val UNMARKED_CONVERSATION_ID = ConversationId(7703L)
        val SEAM_CONVERSATION_ID = ConversationId(7704L)
        val CAPACITY_CONVERSATION_ONE = ConversationId(7705L)
        val CAPACITY_CONVERSATION_TWO = ConversationId(7706L)
        val OLDER_MESSAGE_ID = MessageId(ProviderKind.SMS, 8801L)
        val NEWER_MESSAGE_ID = MessageId(ProviderKind.SMS, 8802L)
        val MATCHING_MESSAGE_ID = MessageId(ProviderKind.MMS, 8803L)
        val MESSAGE_ONE = MessageId(ProviderKind.SMS, 8804L)
        val MESSAGE_TWO = MessageId(ProviderKind.MMS, 8805L)
        val VISIBLE_CONFIG = NotificationConfig(NotificationPrivacy.SENDER_AND_BODY)

        const val TEST_GENERATION_PREFERENCE_NAME = "notification-generation-seam-test"
        const val GENERATION_STATE_KEY = "state"
        const val OLDER_RECEIVED_AT = 10_000L
        const val NEWER_RECEIVED_AT = 20_000L
        const val POLL_ATTEMPTS = 40
        const val POLL_INTERVAL_MILLIS = 25L
    }
}
