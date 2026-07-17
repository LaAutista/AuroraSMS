// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.BaseColumns
import android.provider.Telephony
import android.service.notification.StatusBarNotification
import android.util.Base64
import androidx.core.app.NotificationManagerCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.aurorasms.app.AuroraSmsApplication
import org.aurorasms.core.index.conversation.ConversationLookupResult
import org.aurorasms.core.index.conversation.ConversationPageResult
import org.aurorasms.core.index.timeline.TimelineContentResult
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.notifications.NotificationChannels
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Owner-gated phases for the host-driven API 26 emulator modem journey.
 *
 * This test never creates the incoming message. The host must inject the reviewed PDU through the
 * emulator console after [PHASE_PREPARE] exits, prove the cold process and SystemUI transition,
 * and invoke [PHASE_VERIFY] only after the notification opened the real provider-backed Thread.
 */
class IncomingSmsColdNotificationSmokeTest {
    @Test
    fun realModemSmsTraversesReceiverProviderOrchestratorAndColdNotificationRoute() {
        requireGate()
        assumeTrue(API_26_REQUIRED, Build.VERSION.SDK_INT == Build.VERSION_CODES.O)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        requireFixed(context.packageName == TARGET_PACKAGE, TARGET_PACKAGE_INVALID)
        val phase = InstrumentationRegistry.getArguments().getString(PHASE_ARGUMENT)
            ?: failFixed(PHASE_REQUIRED)

        when (phase) {
            PHASE_PREPARE -> {
                prepare(context)
                instrumentation.sendStatus(
                    PREPARE_STATUS_CODE,
                    Bundle().apply { putString(PREPARE_SENTINEL, PASS) },
                )
            }

            PHASE_VERIFY -> {
                verifyAndClean(context)
                instrumentation.sendStatus(
                    VERIFY_STATUS_CODE,
                    Bundle().apply { putString(VERIFY_SENTINEL, PASS) },
                )
            }

            PHASE_CLEANUP -> {
                cleanup(context)
                instrumentation.sendStatus(
                    CLEANUP_STATUS_CODE,
                    Bundle().apply { putString(CLEANUP_SENTINEL, PASS) },
                )
            }

            else -> failFixed(PHASE_INVALID)
        }
    }

    private fun prepare(context: Context) {
        val app = context as? AuroraSmsApplication ?: failFixed(APPLICATION_INVALID)
        requireFixed(app.defaultSmsRoleState.isRoleHeld(), DEFAULT_SMS_REQUIRED)
        requireFixed(
            context.checkSelfPermission(Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED,
            READ_SMS_REQUIRED,
        )
        requireFixed(
            context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) ==
                PackageManager.PERMISSION_GRANTED,
            RECEIVE_SMS_REQUIRED,
        )
        requireFixed(
            NotificationManagerCompat.from(context).areNotificationsEnabled(),
            NOTIFICATIONS_REQUIRED,
        )

        val checkpoint = checkpoint(context)
        requireFixed(!checkpoint.getBoolean(CHECKPOINT_ARMED, false), STALE_CHECKPOINT)
        requireFixed(providerCount(context) == 0L, EMPTY_PROVIDER_REQUIRED)
        requireFixed(controlledProviderRows(context).isEmpty(), PROVIDER_COLLISION)
        requireFixed(deliveryJournal(context).all.isEmpty(), EMPTY_JOURNAL_REQUIRED)
        requireFixed(replyTargets(context).all.isEmpty(), EMPTY_REPLY_TARGETS_REQUIRED)
        requireFixed(activeNotifications(context).isEmpty(), EMPTY_NOTIFICATIONS_REQUIRED)

        NotificationChannels.ensureCreated(context)
        val channels = notificationChannelSnapshot(context)
        requireFixed(channels.isNotEmpty(), CHANNEL_BASELINE_REQUIRED)

        withForegroundIndex(app) {
            app.container.onExternalProviderChanged()
            awaitEmptyVerifiedIndex(app, expectedThreadId = null)
            awaitIndexLedgerCleared(context)
            val committed = checkpoint.edit()
                .clear()
                .putBoolean(CHECKPOINT_ARMED, true)
                .putLong(CHECKPOINT_PREPARED_AT, System.currentTimeMillis())
                .putStringSet(CHECKPOINT_CHANNELS, channels)
                .putBoolean(CHECKPOINT_LEDGER_PRESENT, true)
                .putBoolean(CHECKPOINT_LEDGER_VALUE, false)
                .commit()
            requireFixed(committed, CHECKPOINT_WRITE_FAILED)
            awaitIndexLedgerCleared(context)
        }
    }

    private fun verifyAndClean(context: Context) {
        val app = context as? AuroraSmsApplication ?: failFixed(APPLICATION_INVALID)
        val snapshot = readCheckpoint(context)
        val row = awaitControlledProviderRow(context, snapshot.preparedAtMillis)
        val journal = awaitCompletedJournal(context, row, snapshot.preparedAtMillis)
        assertReplyTarget(context, row, journal)

        requireFixed(activeNotifications(context).isEmpty(), NOTIFICATION_AUTO_CANCEL_REQUIRED)
        requireFixed(
            notificationChannelSnapshot(context) == snapshot.channels,
            CHANNEL_BASELINE_CHANGED,
        )

        withForegroundIndex(app) {
            app.container.onExternalProviderChanged()
            awaitIndexedConversation(app, row)
        }

        cleanupExactMutation(
            context = context,
            app = app,
            snapshot = snapshot,
            row = row,
            journal = journal,
        )
    }

    private fun cleanup(context: Context) {
        val app = context as? AuroraSmsApplication ?: failFixed(APPLICATION_INVALID)
        val snapshot = readCheckpoint(context)
        val rows = controlledProviderRows(context)
        requireFixed(rows.size <= 1, CLEANUP_PROVIDER_AMBIGUOUS)
        val row = rows.singleOrNull()
        val journals = parsedJournals(context)
        requireFixed(journals.size <= 1, CLEANUP_JOURNAL_AMBIGUOUS)
        val journal = journals.singleOrNull()

        if (row != null && journal != null && journal.providerId > 0L) {
            requireFixed(
                journal.providerId == row.id && journal.conversationId == row.threadId,
                CLEANUP_IDENTITY_MISMATCH,
            )
        }
        journal?.let { assertControlledJournalIdentity(it, row, snapshot.preparedAtMillis) }

        cleanupExactMutation(
            context = context,
            app = app,
            snapshot = snapshot,
            row = row,
            journal = journal,
        )
    }

    private fun cleanupExactMutation(
        context: Context,
        app: AuroraSmsApplication,
        snapshot: Checkpoint,
        row: ProviderRow?,
        journal: JournalEntry?,
    ) {
        val currentProviderCount = providerCount(context)
        if (row == null) {
            requireFixed(currentProviderCount == 0L, CLEANUP_PROVIDER_AMBIGUOUS)
        } else {
            requireFixed(currentProviderCount == 1L, CLEANUP_PROVIDER_AMBIGUOUS)
            assertControlledProviderRow(row, snapshot.preparedAtMillis)
            requireFixed(journal != null, CLEANUP_JOURNAL_REQUIRED)
        }
        if (journal != null) {
            assertControlledJournalIdentity(journal, row, snapshot.preparedAtMillis)
            requireFixed(
                deliveryJournal(context).all.keys == setOf(journal.key),
                JOURNAL_DELTA_CHANGED,
            )
            if (row != null && journal.providerId > 0L) {
                requireFixed(
                    journal.providerId == row.id && journal.conversationId == row.threadId,
                    CLEANUP_IDENTITY_MISMATCH,
                )
            }
        } else {
            requireFixed(deliveryJournal(context).all.isEmpty(), JOURNAL_DELTA_CHANGED)
        }
        val replyTargetKey = validateCleanupReplyTarget(context, row, journal)
        val threadId = row?.threadId?.takeIf { it > 0L }
            ?: journal?.conversationId?.takeIf { it > 0L }
        val activeNotification = controlledActiveNotification(context, threadId, journal)
        requireFixed(
            notificationChannelSnapshot(context) == snapshot.channels,
            CHANNEL_BASELINE_CHANGED,
        )

        activeNotification?.let { notification ->
            val manager = context.getSystemService(NotificationManager::class.java)
                ?: failFixed(NOTIFICATION_MANAGER_UNAVAILABLE)
            manager.cancel(notification.tag, notification.id)
        }

        if (row != null) {
            val exact = controlledProviderRows(context)
            requireFixed(
                exact.size == 1 && sameProviderRow(exact.single(), row),
                CLEANUP_PROVIDER_CHANGED,
            )
            val deleted = context.contentResolver.delete(
                ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, row.id),
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.ADDRESS} = ? AND " +
                    "${Telephony.Sms.BODY} = ? AND ${Telephony.Sms.DATE} = ? AND " +
                    "${Telephony.Sms.DATE_SENT} = ?",
                arrayOf(
                    row.threadId.toString(),
                    FIXTURE_SENDER,
                    FIXTURE_BODY,
                    row.receivedAtMillis.toString(),
                    row.sentAtMillis.toString(),
                ),
            )
            requireFixed(deleted == 1, EXACT_PROVIDER_DELETE_FAILED)
        }

        if (journal != null) {
            val journals = deliveryJournal(context)
            requireFixed(journals.all.keys == setOf(journal.key), JOURNAL_DELTA_CHANGED)
            requireFixed(journals.edit().remove(journal.key).commit(), JOURNAL_DELETE_FAILED)
        }

        if (replyTargetKey != null) {
            val targets = replyTargets(context)
            requireFixed(
                targets.all.keys == setOf(replyTargetKey),
                REPLY_TARGET_DELTA_CHANGED,
            )
            requireFixed(
                targets.edit().remove(replyTargetKey).commit(),
                REPLY_TARGET_DELETE_FAILED,
            )
        }

        withForegroundIndex(app) {
            app.container.onExternalProviderChanged()
            awaitEmptyVerifiedIndex(
                app = app,
                expectedThreadId = threadId?.let(::ProviderThreadId),
            )
            // Keep provider reconciliation eligible until the durable signal is
            // consumed. With an already-empty provider/index (for example, a
            // pre-injection recovery), the logical empty check can otherwise
            // finish before the queued signal clears its ledger bit.
            awaitIndexLedgerBaseline(context, snapshot)
        }
        requireFixed(providerCount(context) == 0L, PROVIDER_BASELINE_NOT_RESTORED)
        requireFixed(controlledProviderRows(context).isEmpty(), PROVIDER_ROW_REMAINED)
        requireFixed(deliveryJournal(context).all.isEmpty(), JOURNAL_BASELINE_NOT_RESTORED)
        requireFixed(replyTargets(context).all.isEmpty(), REPLY_BASELINE_NOT_RESTORED)
        requireFixed(activeNotifications(context).isEmpty(), NOTIFICATION_BASELINE_NOT_RESTORED)
        requireFixed(
            notificationChannelSnapshot(context) == snapshot.channels,
            CHANNEL_BASELINE_CHANGED,
        )
        requireFixed(checkpoint(context).edit().clear().commit(), CHECKPOINT_DELETE_FAILED)
    }

    private fun awaitControlledProviderRow(
        context: Context,
        preparedAtMillis: Long,
    ): ProviderRow {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        do {
            val rows = controlledProviderRows(context)
            if (rows.size == 1 && providerCount(context) == 1L) {
                val row = rows.single()
                assertControlledProviderRow(row, preparedAtMillis)
                return row
            }
            requireFixed(rows.size <= 1, PROVIDER_DELTA_AMBIGUOUS)
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(PROVIDER_ROW_UNAVAILABLE)
    }

    private fun assertControlledProviderRow(row: ProviderRow, preparedAtMillis: Long) {
        val now = System.currentTimeMillis()
        requireFixed(row.id > 0L && row.threadId > 0L, PROVIDER_IDS_INVALID)
        requireFixed(row.address == FIXTURE_SENDER, PROVIDER_SENDER_INVALID)
        requireFixed(row.body == FIXTURE_BODY, PROVIDER_BODY_INVALID)
        requireFixed(row.type == Telephony.Sms.MESSAGE_TYPE_INBOX, PROVIDER_BOX_INVALID)
        requireFixed(!row.read && !row.seen, PROVIDER_READ_STATE_INVALID)
        requireFixed(row.receivedAtMillis in preparedAtMillis..(now + CLOCK_TOLERANCE_MILLIS), PROVIDER_RECEIVED_TIME_INVALID)
        requireFixed(row.sentAtMillis == FIXTURE_SENT_TIMESTAMP_MILLIS, PROVIDER_SENT_TIME_INVALID)
    }

    private fun awaitCompletedJournal(
        context: Context,
        row: ProviderRow,
        preparedAtMillis: Long,
    ): JournalEntry {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        do {
            val journals = parsedJournals(context)
            requireFixed(journals.size <= 1, JOURNAL_DELTA_AMBIGUOUS)
            val journal = journals.singleOrNull()
            if (journal != null && journal.state == JOURNAL_COMPLETE) {
                assertControlledJournalIdentity(journal, row, preparedAtMillis)
                requireFixed(journal.providerId == row.id, JOURNAL_PROVIDER_ID_INVALID)
                requireFixed(journal.conversationId == row.threadId, JOURNAL_THREAD_ID_INVALID)
                return journal
            }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(JOURNAL_COMPLETE_UNAVAILABLE)
    }

    private fun assertControlledJournalIdentity(
        journal: JournalEntry,
        row: ProviderRow?,
        preparedAtMillis: Long,
    ) {
        requireFixed(journal.key == expectedJournalKey(journal.subscriptionId), JOURNAL_KEY_INVALID)
        requireFixed(RECOVERY_TOKEN_PATTERN.matches(journal.recoveryToken), JOURNAL_TOKEN_INVALID)
        requireFixed(journal.state in JOURNAL_STATES, JOURNAL_STATE_INVALID)
        requireFixed(journal.receivedAtMillis >= preparedAtMillis, JOURNAL_RECEIVED_TIME_INVALID)
        requireFixed(journal.sentAtMillis == FIXTURE_SENT_TIMESTAMP_MILLIS, JOURNAL_SENT_TIME_INVALID)
        requireFixed(journal.updatedAtMillis >= journal.receivedAtMillis, JOURNAL_UPDATED_TIME_INVALID)
        if (row != null) {
            requireFixed(journal.receivedAtMillis == row.receivedAtMillis, JOURNAL_RECEIVED_TIME_INVALID)
            requireFixed(journal.sentAtMillis == row.sentAtMillis, JOURNAL_SENT_TIME_INVALID)
            requireFixed(journal.subscriptionId == (row.subscriptionId ?: NO_SUBSCRIPTION), JOURNAL_SUBSCRIPTION_INVALID)
        }
        if (journal.state == JOURNAL_PENDING) {
            requireFixed(journal.providerId == 0L && journal.conversationId == 0L, JOURNAL_PENDING_IDS_INVALID)
        } else {
            requireFixed(journal.providerId > 0L && journal.conversationId > 0L, JOURNAL_STORED_IDS_INVALID)
        }
    }

    private fun assertReplyTarget(
        context: Context,
        row: ProviderRow,
        journal: JournalEntry,
    ) {
        val targets = replyTargets(context).all
        if (journal.subscriptionId == NO_SUBSCRIPTION) {
            requireFixed(targets.isEmpty(), REPLY_TARGET_UNEXPECTED)
            return
        }
        requireFixed(targets.size == 1, REPLY_TARGET_REQUIRED)
        val key = "$REPLY_TARGET_PREFIX${ProviderKind.SMS.name}:${row.id}"
        val encoded = targets[key] as? String ?: failFixed(REPLY_TARGET_IDENTITY_INVALID)
        assertEncodedReplyTarget(
            encoded = encoded,
            providerId = row.id,
            conversationId = row.threadId,
            subscriptionId = journal.subscriptionId,
        )
    }

    private fun validateCleanupReplyTarget(
        context: Context,
        row: ProviderRow?,
        journal: JournalEntry?,
    ): String? {
        val targets = replyTargets(context).all
        requireFixed(targets.size <= 1, REPLY_TARGET_DELTA_CHANGED)
        if (targets.isEmpty()) return null
        val controlledJournal = journal ?: failFixed(REPLY_TARGET_IDENTITY_INVALID)
        requireFixed(controlledJournal.providerId > 0L, REPLY_TARGET_IDENTITY_INVALID)
        requireFixed(controlledJournal.subscriptionId >= 0, REPLY_TARGET_UNEXPECTED)
        val providerId = controlledJournal.providerId
        val conversationId = controlledJournal.conversationId
        if (row != null) {
            requireFixed(
                row.id == providerId && row.threadId == conversationId,
                REPLY_TARGET_IDENTITY_INVALID,
            )
        }
        val key = "$REPLY_TARGET_PREFIX${ProviderKind.SMS.name}:$providerId"
        val encoded = targets[key] as? String ?: failFixed(REPLY_TARGET_IDENTITY_INVALID)
        assertEncodedReplyTarget(
            encoded,
            providerId,
            conversationId,
            controlledJournal.subscriptionId,
        )
        return key
    }

    private fun assertEncodedReplyTarget(
        encoded: String,
        providerId: Long,
        conversationId: Long,
        subscriptionId: Int,
    ) {
        requireFixed(providerId > 0L && conversationId > 0L, REPLY_TARGET_IDENTITY_INVALID)
        val fields = encoded.split(REPLY_TARGET_SEPARATOR)
        requireFixed(fields.size == REPLY_TARGET_FIELD_COUNT, REPLY_TARGET_ENCODING_INVALID)
        requireFixed(fields[0] == REPLY_TARGET_VERSION, REPLY_TARGET_ENCODING_INVALID)
        requireFixed(fields[1].toLongOrNull() == conversationId, REPLY_TARGET_IDENTITY_INVALID)
        requireFixed(fields[2].toIntOrNull() == subscriptionId, REPLY_TARGET_SUBSCRIPTION_INVALID)
        requireFixed(
            fields[3].toLongOrNull()?.let { it > System.currentTimeMillis() } == true,
            REPLY_TARGET_EXPIRY_INVALID,
        )
        val decodedAddress = runCatching {
            String(
                Base64.decode(
                    fields[4],
                    Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING,
                ),
                Charsets.UTF_8,
            )
        }.getOrNull()
        requireFixed(decodedAddress == FIXTURE_SENDER, REPLY_TARGET_RECIPIENT_INVALID)
    }

    private fun controlledActiveNotification(
        context: Context,
        threadId: Long?,
        journal: JournalEntry?,
    ): StatusBarNotification? {
        val active = activeNotifications(context)
        requireFixed(active.size <= 1, NOTIFICATION_DELTA_AMBIGUOUS)
        val status = active.singleOrNull() ?: return null
        val conversation = threadId ?: failFixed(NOTIFICATION_IDENTITY_INVALID)
        val expectedId = notificationId(conversation)
        val expectedTag = "$CONVERSATION_NOTIFICATION_TAG_PREFIX$conversation"
        val notification = status.notification
        val contentIntent = notification.contentIntent
            ?: failFixed(NOTIFICATION_CONTRACT_INVALID)
        val expectedActions = if ((journal?.subscriptionId ?: NO_SUBSCRIPTION) >= 0) 1 else 0
        requireFixed(
            status.packageName == TARGET_PACKAGE &&
                status.id == expectedId &&
                status.tag == expectedTag &&
                status.uid == context.applicationInfo.uid &&
                status.isClearable &&
                notification.channelId == NotificationChannels.MESSAGES &&
                notification.category == Notification.CATEGORY_MESSAGE &&
                notification.visibility == Notification.VISIBILITY_PRIVATE &&
                notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ==
                GENERIC_NOTIFICATION_TITLE &&
                notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ==
                GENERIC_NOTIFICATION_BODY &&
                notification.flags and Notification.FLAG_AUTO_CANCEL != 0 &&
                notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 &&
                notification.actions.orEmpty().size == expectedActions &&
                notification.publicVersion?.contentIntent == contentIntent &&
                contentIntent.creatorPackage == TARGET_PACKAGE &&
                contentIntent.creatorUid == context.applicationInfo.uid &&
                contentIntent.isActivity,
            NOTIFICATION_CONTRACT_INVALID,
        )
        return status
    }

    private fun notificationId(conversationId: Long): Int {
        val folded = (conversationId xor (conversationId ushr 32)).toInt() and Int.MAX_VALUE
        return folded.takeUnless { it == 0 } ?: 1
    }

    private fun sameProviderRow(first: ProviderRow, second: ProviderRow): Boolean =
        first.id == second.id &&
            first.threadId == second.threadId &&
            first.address == second.address &&
            first.body == second.body &&
            first.type == second.type &&
            first.receivedAtMillis == second.receivedAtMillis &&
            first.sentAtMillis == second.sentAtMillis &&
            first.subscriptionId == second.subscriptionId &&
            first.read == second.read &&
            first.seen == second.seen

    private suspend fun awaitIndexedConversation(app: AuroraSmsApplication, row: ProviderRow) {
        val providerThreadId = ProviderThreadId(row.threadId)
        val providerMessageId = ProviderMessageId(ProviderKind.SMS, row.id)
        val timeoutAt = SystemClock.uptimeMillis() + INDEX_WAIT_TIMEOUT_MILLIS
        do {
            val lookup = app.container.conversationRepository.loadConversation(providerThreadId)
            val content = app.container.threadTimelineRepository.loadContent(
                providerThreadId,
                providerMessageId,
            )
            if (lookup is ConversationLookupResult.Found &&
                lookup.coverage.verifiedComplete &&
                lookup.verifiedIdentity != null &&
                content is TimelineContentResult.Found &&
                content.coverage.verifiedComplete
            ) {
                val summary = lookup.summary
                val identity = lookup.verifiedIdentity ?: failFixed(VERIFIED_IDENTITY_INVALID)
                requireFixed(summary.providerThreadId == providerThreadId, INDEX_THREAD_ID_INVALID)
                requireFixed(summary.latestProviderMessageId == providerMessageId, INDEX_PROVIDER_ID_INVALID)
                requireFixed(summary.latestDirection == MessageDirection.INCOMING, INDEX_DIRECTION_INVALID)
                requireFixed(summary.latestBox == MessageBox.INBOX, INDEX_BOX_INVALID)
                requireFixed(summary.latestStatus == MessageStatus.NONE, INDEX_STATUS_INVALID)
                requireFixed(summary.latestSenderAddress == ParticipantAddress(FIXTURE_SENDER), INDEX_SENDER_INVALID)
                requireFixed(summary.latestSnippet == FIXTURE_BODY, INDEX_BODY_INVALID)
                requireFixed(!summary.latestRead && summary.indexedUnreadCount == 1L, INDEX_UNREAD_INVALID)
                requireFixed(summary.indexedMessageCount == 1L, INDEX_COUNT_INVALID)
                requireFixed(
                    identity.providerThreadId == providerThreadId &&
                        identity.participants == listOf(ParticipantAddress(FIXTURE_SENDER)),
                    VERIFIED_IDENTITY_INVALID,
                )
                requireFixed(content.content.providerMessageId == providerMessageId, TIMELINE_PROVIDER_ID_INVALID)
                requireFixed(content.content.body == FIXTURE_BODY, TIMELINE_BODY_INVALID)
                requireFixed(!content.content.sourceTruncated, TIMELINE_TRUNCATION_INVALID)
                return
            }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(VERIFIED_INDEX_UNAVAILABLE)
    }

    private suspend fun awaitEmptyVerifiedIndex(
        app: AuroraSmsApplication,
        expectedThreadId: ProviderThreadId?,
    ) {
        val timeoutAt = SystemClock.uptimeMillis() + INDEX_WAIT_TIMEOUT_MILLIS
        do {
            val inbox = app.container.conversationRepository.loadInbox()
            val conversation = expectedThreadId?.let {
                app.container.conversationRepository.loadConversation(it)
            }
            val empty = inbox is ConversationPageResult.Page &&
                inbox.page.coverage.verifiedComplete &&
                inbox.page.coverage.indexedMessageCount == 0L &&
                inbox.page.items.isEmpty()
            val exactMissing = conversation == null ||
                (conversation is ConversationLookupResult.Missing && conversation.coverage.verifiedComplete)
            if (empty && exactMissing) return
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(EMPTY_VERIFIED_INDEX_UNAVAILABLE)
    }

    private fun withForegroundIndex(
        app: AuroraSmsApplication,
        block: suspend () -> Unit,
    ) = runBlocking {
        app.container.onMessagingActivityStarted()
        try {
            block()
        } finally {
            app.container.onMessagingActivityStopped()
        }
    }

    private fun providerCount(context: Context): Long = context.contentResolver.query(
        Telephony.Sms.CONTENT_URI,
        arrayOf(BaseColumns._ID),
        null,
        null,
        null,
    )?.use { cursor -> cursor.count.toLong() } ?: failFixed(PROVIDER_QUERY_UNAVAILABLE)

    private fun controlledProviderRows(context: Context): List<ProviderRow> =
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            PROVIDER_PROJECTION,
            "${Telephony.Sms.ADDRESS} = ? OR ${Telephony.Sms.BODY} = ?",
            arrayOf(FIXTURE_SENDER, FIXTURE_BODY),
            null,
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(BaseColumns._ID)
            val threadId = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val address = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val body = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val type = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val date = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val dateSent = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)
            val subscription = cursor.getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID)
            val read = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
            val seen = cursor.getColumnIndexOrThrow(Telephony.Sms.SEEN)
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ProviderRow(
                            id = cursor.getLong(id),
                            threadId = cursor.getLong(threadId),
                            address = cursor.getString(address),
                            body = cursor.getString(body),
                            type = cursor.getInt(type),
                            receivedAtMillis = cursor.getLong(date),
                            sentAtMillis = cursor.getLong(dateSent),
                            subscriptionId = if (cursor.isNull(subscription)) null else cursor.getInt(subscription),
                            read = cursor.getInt(read) != 0,
                            seen = cursor.getInt(seen) != 0,
                        ),
                    )
                }
            }
        } ?: failFixed(PROVIDER_QUERY_UNAVAILABLE)

    private fun parsedJournals(context: Context): List<JournalEntry> =
        deliveryJournal(context).all.map { (key, raw) ->
            val encoded = raw as? String ?: failFixed(JOURNAL_ENCODING_INVALID)
            val fields = encoded.split(JOURNAL_SEPARATOR)
            requireFixed(fields.size == JOURNAL_FIELD_COUNT, JOURNAL_ENCODING_INVALID)
            requireFixed(fields[0] == JOURNAL_VERSION, JOURNAL_ENCODING_INVALID)
            JournalEntry(
                key = key,
                recoveryToken = fields[1],
                state = fields[2],
                providerId = fields[3].toLongOrNull() ?: failFixed(JOURNAL_ENCODING_INVALID),
                conversationId = fields[4].toLongOrNull() ?: failFixed(JOURNAL_ENCODING_INVALID),
                receivedAtMillis = fields[5].toLongOrNull() ?: failFixed(JOURNAL_ENCODING_INVALID),
                sentAtMillis = fields[6].toLongOrNull() ?: failFixed(JOURNAL_ENCODING_INVALID),
                subscriptionId = fields[7].toIntOrNull() ?: failFixed(JOURNAL_ENCODING_INVALID),
                updatedAtMillis = fields[8].toLongOrNull() ?: failFixed(JOURNAL_ENCODING_INVALID),
            )
        }

    private fun expectedJournalKey(subscriptionId: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(FINGERPRINT_DOMAIN)
        digest.updateInt(subscriptionId)
        digest.updateInt(FINGERPRINT_FORMAT.size)
        digest.update(FINGERPRINT_FORMAT)
        digest.updateInt(1)
        digest.updateInt(FIXTURE_PDU.size)
        digest.update(FIXTURE_PDU)
        return JOURNAL_KEY_PREFIX + digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun MessageDigest.updateInt(value: Int) {
        update((value ushr 24).toByte())
        update((value ushr 16).toByte())
        update((value ushr 8).toByte())
        update(value.toByte())
    }

    private fun notificationChannelSnapshot(context: Context): Set<String> {
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: failFixed(NOTIFICATION_MANAGER_UNAVAILABLE)
        return manager.notificationChannels.mapTo(mutableSetOf(), ::channelFingerprint)
    }

    private fun channelFingerprint(channel: NotificationChannel): String = listOf(
        channel.id,
        channel.name?.toString().orEmpty(),
        channel.description.orEmpty(),
        channel.importance.toString(),
        channel.lockscreenVisibility.toString(),
        channel.canBypassDnd().toString(),
        channel.canShowBadge().toString(),
        channel.shouldShowLights().toString(),
        channel.shouldVibrate().toString(),
        channel.lightColor.toString(),
        channel.sound?.toString().orEmpty(),
        channel.audioAttributes?.usage?.toString().orEmpty(),
        channel.audioAttributes?.contentType?.toString().orEmpty(),
        channel.audioAttributes?.flags?.toString().orEmpty(),
        channel.vibrationPattern?.joinToString(",").orEmpty(),
    ).joinToString(CHANNEL_FIELD_SEPARATOR)

    private fun activeNotifications(context: Context) =
        (context.getSystemService(NotificationManager::class.java)
            ?: failFixed(NOTIFICATION_MANAGER_UNAVAILABLE))
            .activeNotifications
            .also { notifications ->
                requireFixed(
                    notifications.all { it.packageName == TARGET_PACKAGE },
                    NOTIFICATION_SCOPE_INVALID,
                )
            }

    private fun readCheckpoint(context: Context): Checkpoint {
        val checkpoint = checkpoint(context)
        requireFixed(checkpoint.getBoolean(CHECKPOINT_ARMED, false), CHECKPOINT_REQUIRED)
        val preparedAt = checkpoint.getLong(CHECKPOINT_PREPARED_AT, -1L)
        val channels = checkpoint.getStringSet(CHECKPOINT_CHANNELS, null)?.toSet()
            ?: failFixed(CHECKPOINT_INVALID)
        requireFixed(preparedAt >= 0L && channels.isNotEmpty(), CHECKPOINT_INVALID)
        return Checkpoint(
            preparedAtMillis = preparedAt,
            channels = channels,
            ledgerPresent = checkpoint.getBoolean(CHECKPOINT_LEDGER_PRESENT, false),
            ledgerValue = checkpoint.getBoolean(CHECKPOINT_LEDGER_VALUE, false),
        )
    }

    private fun awaitIndexLedgerBaseline(context: Context, snapshot: Checkpoint) {
        val timeoutAt = SystemClock.uptimeMillis() + INDEX_WAIT_TIMEOUT_MILLIS
        do {
            val ledger = indexLedger(context)
            val exact = ledger.all.keys.all { it == INDEX_LEDGER_KEY } &&
                ledger.contains(INDEX_LEDGER_KEY) == snapshot.ledgerPresent &&
                ledger.getBoolean(INDEX_LEDGER_KEY, false) == snapshot.ledgerValue
            if (exact) return
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(INDEX_LEDGER_BASELINE_NOT_RESTORED)
    }

    private fun awaitIndexLedgerCleared(context: Context) {
        val timeoutAt = SystemClock.uptimeMillis() + INDEX_WAIT_TIMEOUT_MILLIS
        var stableSamples = 0
        do {
            val ledger = indexLedger(context)
            val cleared = ledger.all.keys == setOf(INDEX_LEDGER_KEY) &&
                ledger.contains(INDEX_LEDGER_KEY) &&
                !ledger.getBoolean(INDEX_LEDGER_KEY, true)
            stableSamples = if (cleared) stableSamples + 1 else 0
            if (stableSamples >= INDEX_LEDGER_STABLE_SAMPLE_COUNT) return
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(INDEX_LEDGER_BASELINE_INVALID)
    }

    private fun checkpoint(context: Context): SharedPreferences =
        context.getSharedPreferences(CHECKPOINT_PREFERENCES, Context.MODE_PRIVATE)

    private fun deliveryJournal(context: Context): SharedPreferences =
        context.getSharedPreferences(JOURNAL_PREFERENCES, Context.MODE_PRIVATE)

    private fun replyTargets(context: Context): SharedPreferences =
        context.getSharedPreferences(REPLY_TARGET_PREFERENCES, Context.MODE_PRIVATE)

    private fun indexLedger(context: Context): SharedPreferences =
        context.getSharedPreferences(INDEX_LEDGER_PREFERENCES, Context.MODE_PRIVATE)

    private fun requireGate() {
        val enabled = InstrumentationRegistry.getArguments().getString(GATE_ARGUMENT)
        assumeTrue(GATE_REQUIRED, enabled != null)
        assumeTrue(GATE_REQUIRED, enabled == true.toString())
    }

    private fun requireFixed(condition: Boolean, message: String) {
        if (!condition) failFixed(message)
    }

    private fun failFixed(message: String): Nothing = throw AssertionError(message)

    private data class Checkpoint(
        val preparedAtMillis: Long,
        val channels: Set<String>,
        val ledgerPresent: Boolean,
        val ledgerValue: Boolean,
    )

    private data class ProviderRow(
        val id: Long,
        val threadId: Long,
        val address: String?,
        val body: String?,
        val type: Int,
        val receivedAtMillis: Long,
        val sentAtMillis: Long,
        val subscriptionId: Int?,
        val read: Boolean,
        val seen: Boolean,
    ) {
        override fun toString(): String = "ProviderRow(REDACTED)"
    }

    private data class JournalEntry(
        val key: String,
        val recoveryToken: String,
        val state: String,
        val providerId: Long,
        val conversationId: Long,
        val receivedAtMillis: Long,
        val sentAtMillis: Long,
        val subscriptionId: Int,
        val updatedAtMillis: Long,
    ) {
        override fun toString(): String = "JournalEntry(state=$state, REDACTED)"
    }

    private companion object {
        const val TARGET_PACKAGE = "org.aurorasms.app"
        const val GATE_ARGUMENT = "auroraEmulatorIncomingSmsColdNotification"
        const val PHASE_ARGUMENT = "auroraEmulatorIncomingSmsColdNotificationPhase"
        const val PHASE_PREPARE = "prepare"
        const val PHASE_VERIFY = "verify"
        const val PHASE_CLEANUP = "cleanup"
        const val PREPARE_STATUS_CODE = 46
        const val VERIFY_STATUS_CODE = 47
        const val CLEANUP_STATUS_CODE = 48
        const val PREPARE_SENTINEL = "auroraIncomingSmsPrepareResult"
        const val VERIFY_SENTINEL = "auroraIncomingSmsVerifyResult"
        const val CLEANUP_SENTINEL = "auroraIncomingSmsCleanupResult"
        const val PASS = "pass"

        const val FIXTURE_SENDER = "+15551230017"
        const val FIXTURE_BODY = "AuroraSMS modem delivery 900017"
        const val FIXTURE_SENT_TIMESTAMP_MILLIS = 1_784_328_000_000L
        const val GENERIC_NOTIFICATION_TITLE = "AuroraSMS"
        const val GENERIC_NOTIFICATION_BODY = "New message"
        const val CONVERSATION_NOTIFICATION_TAG_PREFIX = "aurora-conversation:"
        const val FIXTURE_PDU_HEX =
            "00040B915155210310F70000627071220400001FC1BAFC2D0F4F9B5350FB4D2EB741E4323B6D2FCBF3A01C0C068BDD00"
        val FIXTURE_PDU: ByteArray = FIXTURE_PDU_HEX.chunked(2)
            .map { octet -> octet.toInt(16).toByte() }
            .toByteArray()
        val FINGERPRINT_DOMAIN: ByteArray =
            "AuroraSMS.SMS_DELIVERY.v1".toByteArray(StandardCharsets.US_ASCII)
        val FINGERPRINT_FORMAT: ByteArray = "3gpp".toByteArray(StandardCharsets.US_ASCII)

        const val CHECKPOINT_PREFERENCES = "aurora_incoming_sms_smoke_v1"
        const val CHECKPOINT_ARMED = "armed"
        const val CHECKPOINT_PREPARED_AT = "prepared_at"
        const val CHECKPOINT_CHANNELS = "channels"
        const val CHECKPOINT_LEDGER_PRESENT = "ledger_present"
        const val CHECKPOINT_LEDGER_VALUE = "ledger_value"
        const val JOURNAL_PREFERENCES = "aurora_sms_delivery_journal_v1"
        const val JOURNAL_KEY_PREFIX = "delivery."
        const val JOURNAL_VERSION = "2"
        const val JOURNAL_SEPARATOR = ","
        const val JOURNAL_FIELD_COUNT = 9
        const val JOURNAL_PENDING = "P"
        const val JOURNAL_STORED = "S"
        const val JOURNAL_COMPLETE = "C"
        val JOURNAL_STATES = setOf(JOURNAL_PENDING, JOURNAL_STORED, JOURNAL_COMPLETE)
        const val NO_SUBSCRIPTION = -1
        val RECOVERY_TOKEN_PATTERN =
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        const val REPLY_TARGET_PREFERENCES = "aurora_inline_reply_targets"
        const val REPLY_TARGET_PREFIX = "target."
        const val REPLY_TARGET_VERSION = "1"
        const val REPLY_TARGET_SEPARATOR = "|"
        const val REPLY_TARGET_FIELD_COUNT = 5
        const val INDEX_LEDGER_PREFERENCES = "aurora_index_signal_ledger"
        const val INDEX_LEDGER_KEY = "ambiguous_provider_change_pending"
        const val CHANNEL_FIELD_SEPARATOR = "\u001f"

        const val WAIT_TIMEOUT_MILLIS = 20_000L
        const val INDEX_WAIT_TIMEOUT_MILLIS = 30_000L
        const val INDEX_LEDGER_STABLE_SAMPLE_COUNT = 5
        const val POLL_INTERVAL_MILLIS = 100L
        const val CLOCK_TOLERANCE_MILLIS = 5_000L

        val PROVIDER_PROJECTION = arrayOf(
            BaseColumns._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.TYPE,
            Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT,
            Telephony.Sms.SUBSCRIPTION_ID,
            Telephony.Sms.READ,
            Telephony.Sms.SEEN,
        )

        const val GATE_REQUIRED = "Owner-gated incoming SMS emulator journey was not enabled"
        const val API_26_REQUIRED = "Incoming SMS emulator journey requires API 26"
        const val TARGET_PACKAGE_INVALID = "Incoming SMS target package is invalid"
        const val PHASE_REQUIRED = "Incoming SMS phase is required"
        const val PHASE_INVALID = "Incoming SMS phase is invalid"
        const val APPLICATION_INVALID = "Incoming SMS application entry point is invalid"
        const val DEFAULT_SMS_REQUIRED = "Incoming SMS journey requires default SMS role"
        const val READ_SMS_REQUIRED = "Incoming SMS journey requires READ_SMS"
        const val RECEIVE_SMS_REQUIRED = "Incoming SMS journey requires RECEIVE_SMS"
        const val NOTIFICATIONS_REQUIRED = "Incoming SMS journey requires notifications"
        const val STALE_CHECKPOINT = "Incoming SMS checkpoint is already armed"
        const val EMPTY_PROVIDER_REQUIRED = "Incoming SMS journey requires an empty SMS provider"
        const val PROVIDER_COLLISION = "Incoming SMS fixture collides with provider state"
        const val EMPTY_JOURNAL_REQUIRED = "Incoming SMS journey requires an empty replay journal"
        const val EMPTY_REPLY_TARGETS_REQUIRED = "Incoming SMS journey requires empty reply targets"
        const val EMPTY_NOTIFICATIONS_REQUIRED = "Incoming SMS journey requires no active Aurora notifications"
        const val CHANNEL_BASELINE_REQUIRED = "Incoming SMS channel baseline is unavailable"
        const val INDEX_LEDGER_BASELINE_INVALID = "Incoming SMS index ledger baseline is invalid"
        const val CHECKPOINT_WRITE_FAILED = "Incoming SMS checkpoint could not be written"
        const val CHECKPOINT_REQUIRED = "Incoming SMS checkpoint is not armed"
        const val CHECKPOINT_INVALID = "Incoming SMS checkpoint is invalid"
        const val PROVIDER_QUERY_UNAVAILABLE = "Incoming SMS provider query is unavailable"
        const val PROVIDER_DELTA_AMBIGUOUS = "Incoming SMS provider delta is ambiguous"
        const val PROVIDER_ROW_UNAVAILABLE = "Incoming SMS provider row did not appear"
        const val PROVIDER_IDS_INVALID = "Incoming SMS provider IDs are invalid"
        const val PROVIDER_SENDER_INVALID = "Incoming SMS provider sender is invalid"
        const val PROVIDER_BODY_INVALID = "Incoming SMS provider body is invalid"
        const val PROVIDER_BOX_INVALID = "Incoming SMS provider box is invalid"
        const val PROVIDER_READ_STATE_INVALID = "Incoming SMS provider read state is invalid"
        const val PROVIDER_RECEIVED_TIME_INVALID = "Incoming SMS provider receive time is invalid"
        const val PROVIDER_SENT_TIME_INVALID = "Incoming SMS provider sent time is invalid"
        const val JOURNAL_DELTA_AMBIGUOUS = "Incoming SMS journal delta is ambiguous"
        const val JOURNAL_COMPLETE_UNAVAILABLE = "Incoming SMS journal did not complete"
        const val JOURNAL_ENCODING_INVALID = "Incoming SMS journal encoding is invalid"
        const val JOURNAL_KEY_INVALID = "Incoming SMS journal key is invalid"
        const val JOURNAL_TOKEN_INVALID = "Incoming SMS journal recovery token is invalid"
        const val JOURNAL_STATE_INVALID = "Incoming SMS journal state is invalid"
        const val JOURNAL_PROVIDER_ID_INVALID = "Incoming SMS journal provider ID is invalid"
        const val JOURNAL_THREAD_ID_INVALID = "Incoming SMS journal thread ID is invalid"
        const val JOURNAL_RECEIVED_TIME_INVALID = "Incoming SMS journal receive time is invalid"
        const val JOURNAL_SENT_TIME_INVALID = "Incoming SMS journal sent time is invalid"
        const val JOURNAL_UPDATED_TIME_INVALID = "Incoming SMS journal update time is invalid"
        const val JOURNAL_SUBSCRIPTION_INVALID = "Incoming SMS journal subscription is invalid"
        const val JOURNAL_PENDING_IDS_INVALID = "Incoming SMS pending journal IDs are invalid"
        const val JOURNAL_STORED_IDS_INVALID = "Incoming SMS stored journal IDs are invalid"
        const val REPLY_TARGET_UNEXPECTED = "Incoming SMS reply target is unexpected"
        const val REPLY_TARGET_REQUIRED = "Incoming SMS reply target is missing"
        const val REPLY_TARGET_IDENTITY_INVALID = "Incoming SMS reply target identity is invalid"
        const val REPLY_TARGET_ENCODING_INVALID = "Incoming SMS reply target encoding is invalid"
        const val REPLY_TARGET_SUBSCRIPTION_INVALID = "Incoming SMS reply target subscription is invalid"
        const val REPLY_TARGET_EXPIRY_INVALID = "Incoming SMS reply target expiry is invalid"
        const val REPLY_TARGET_RECIPIENT_INVALID = "Incoming SMS reply target recipient is invalid"
        const val NOTIFICATION_AUTO_CANCEL_REQUIRED = "Incoming SMS notification did not auto-cancel"
        const val NOTIFICATION_MANAGER_UNAVAILABLE = "Incoming SMS notification manager is unavailable"
        const val NOTIFICATION_SCOPE_INVALID = "Incoming SMS notification scope is invalid"
        const val CHANNEL_BASELINE_CHANGED = "Incoming SMS notification channels changed"
        const val VERIFIED_INDEX_UNAVAILABLE = "Incoming SMS verified index row is unavailable"
        const val INDEX_THREAD_ID_INVALID = "Incoming SMS index thread ID is invalid"
        const val INDEX_PROVIDER_ID_INVALID = "Incoming SMS index provider ID is invalid"
        const val INDEX_DIRECTION_INVALID = "Incoming SMS index direction is invalid"
        const val INDEX_BOX_INVALID = "Incoming SMS index box is invalid"
        const val INDEX_STATUS_INVALID = "Incoming SMS index status is invalid"
        const val INDEX_SENDER_INVALID = "Incoming SMS index sender is invalid"
        const val INDEX_BODY_INVALID = "Incoming SMS index body is invalid"
        const val INDEX_UNREAD_INVALID = "Incoming SMS index unread state is invalid"
        const val INDEX_COUNT_INVALID = "Incoming SMS index count is invalid"
        const val VERIFIED_IDENTITY_INVALID = "Incoming SMS verified identity is invalid"
        const val TIMELINE_PROVIDER_ID_INVALID = "Incoming SMS timeline provider ID is invalid"
        const val TIMELINE_BODY_INVALID = "Incoming SMS timeline body is invalid"
        const val TIMELINE_TRUNCATION_INVALID = "Incoming SMS timeline truncation is invalid"
        const val EMPTY_VERIFIED_INDEX_UNAVAILABLE = "Incoming SMS empty verified index is unavailable"
        const val CLEANUP_PROVIDER_AMBIGUOUS = "Incoming SMS cleanup provider state is ambiguous"
        const val CLEANUP_PROVIDER_CHANGED = "Incoming SMS cleanup provider row changed"
        const val CLEANUP_JOURNAL_REQUIRED = "Incoming SMS cleanup journal is missing"
        const val CLEANUP_JOURNAL_AMBIGUOUS = "Incoming SMS cleanup journal state is ambiguous"
        const val CLEANUP_IDENTITY_MISMATCH = "Incoming SMS cleanup identity does not match"
        const val EXACT_PROVIDER_DELETE_FAILED = "Incoming SMS exact provider delete failed"
        const val JOURNAL_DELTA_CHANGED = "Incoming SMS journal delta changed"
        const val JOURNAL_DELETE_FAILED = "Incoming SMS journal delete failed"
        const val REPLY_TARGET_DELTA_CHANGED = "Incoming SMS reply target delta changed"
        const val REPLY_TARGET_DELETE_FAILED = "Incoming SMS reply target delete failed"
        const val INDEX_LEDGER_BASELINE_NOT_RESTORED = "Incoming SMS index ledger was not restored"
        const val PROVIDER_BASELINE_NOT_RESTORED = "Incoming SMS provider baseline was not restored"
        const val PROVIDER_ROW_REMAINED = "Incoming SMS provider row remained"
        const val JOURNAL_BASELINE_NOT_RESTORED = "Incoming SMS journal baseline was not restored"
        const val REPLY_BASELINE_NOT_RESTORED = "Incoming SMS reply baseline was not restored"
        const val NOTIFICATION_BASELINE_NOT_RESTORED = "Incoming SMS notification baseline was not restored"
        const val NOTIFICATION_DELTA_AMBIGUOUS = "Incoming SMS notification delta is ambiguous"
        const val NOTIFICATION_IDENTITY_INVALID = "Incoming SMS notification identity is invalid"
        const val NOTIFICATION_CONTRACT_INVALID = "Incoming SMS notification contract is invalid"
        const val CHECKPOINT_DELETE_FAILED = "Incoming SMS checkpoint delete failed"
    }
}
