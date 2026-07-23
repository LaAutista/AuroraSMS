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
import android.telephony.SmsMessage
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
import org.aurorasms.core.model.INLINE_REPLY_OPERATION_ID_BOUNDARY
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
 * Owner-gated phases for host-driven emulator-modem delivery journeys.
 *
 * These tests never create the incoming message. After [PHASE_PREPARE], the host injects either the
 * reviewed fixed PDU or documented text through the emulator console, independently captures the
 * exact delivered PDU when needed, proves the journey-specific production UI state, and invokes
 * [PHASE_VERIFY] only after the real provider-backed Thread has been inspected.
 */
class IncomingSmsColdNotificationSmokeTest {
    @Test
    fun realModemSmsTraversesReceiverProviderOrchestratorAndColdNotificationRoute() {
        runJourney(COLD_NOTIFICATION_CONTRACT)
    }

    @Test
    fun realModemSmsRemainsReadableWhenNotificationPermissionIsDenied() {
        runJourney(NOTIFICATION_DENIED_CONTRACT)
    }

    @Test
    fun twoRealModemSmsShareOneColdNotificationThread() {
        runJourney(MULTIPLE_MESSAGE_CONTRACT)
    }

    @Test
    fun realModemSmsSupportsColdInlineReplyPermissionDeniedJourney() {
        runJourney(INLINE_REPLY_PERMISSION_DENIED_CONTRACT)
    }

    private fun runJourney(contract: JourneyContract) {
        requireGate(contract.gateArgument)
        assumeTrue(contract.apiRequiredMessage, Build.VERSION.SDK_INT == contract.expectedSdk)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        requireFixed(context.packageName == TARGET_PACKAGE, TARGET_PACKAGE_INVALID)
        val phase = InstrumentationRegistry.getArguments().getString(contract.phaseArgument)
            ?: failFixed(PHASE_REQUIRED)

        when (phase) {
            PHASE_PREPARE -> {
                prepare(context, contract)
                instrumentation.sendStatus(
                    contract.prepareStatusCode,
                    Bundle().apply { putString(contract.prepareSentinel, PASS) },
                )
            }

            PHASE_VERIFY -> {
                verifyAndClean(context, contract)
                instrumentation.sendStatus(
                    contract.verifyStatusCode,
                    Bundle().apply { putString(contract.verifySentinel, PASS) },
                )
            }

            PHASE_CLEANUP -> {
                cleanup(context, contract)
                instrumentation.sendStatus(
                    contract.cleanupStatusCode,
                    Bundle().apply { putString(contract.cleanupSentinel, PASS) },
                )
            }

            else -> failFixed(PHASE_INVALID)
        }
    }

    private fun prepare(context: Context, contract: JourneyContract) {
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
        val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (contract.notificationsEnabled) {
            requireFixed(notificationsEnabled, NOTIFICATIONS_REQUIRED)
        } else {
            requireFixed(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU, API_33_REQUIRED)
            requireFixed(
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED && !notificationsEnabled,
                NOTIFICATIONS_DISABLED_REQUIRED,
            )
        }

        val checkpoint = checkpoint(context)
        requireFixed(!checkpoint.getBoolean(CHECKPOINT_ARMED, false), STALE_CHECKPOINT)
        requireFixed(providerCount(context) == 0L, EMPTY_PROVIDER_REQUIRED)
        requireFixed(controlledProviderRows(context, contract).isEmpty(), PROVIDER_COLLISION)
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
                .putString(CHECKPOINT_JOURNEY, contract.checkpointValue)
                .putLong(CHECKPOINT_PREPARED_AT, System.currentTimeMillis())
                .putStringSet(CHECKPOINT_CHANNELS, channels)
                .putBoolean(CHECKPOINT_LEDGER_PRESENT, true)
                .putBoolean(CHECKPOINT_LEDGER_VALUE, false)
                .commit()
            requireFixed(committed, CHECKPOINT_WRITE_FAILED)
            awaitIndexLedgerCleared(context)
        }
    }

    private fun verifyAndClean(context: Context, contract: JourneyContract) {
        val app = context as? AuroraSmsApplication ?: failFixed(APPLICATION_INVALID)
        val snapshot = readCheckpoint(context, contract.checkpointValue)
        val deliveries = expectedDeliveries(contract)
        val rows = awaitControlledProviderRows(
            context = context,
            preparedAtMillis = snapshot.preparedAtMillis,
            contract = contract,
            deliveries = deliveries,
        )
        val journals = awaitCompletedJournals(
            context = context,
            rows = rows,
            preparedAtMillis = snapshot.preparedAtMillis,
            deliveries = deliveries,
        )
        assertReplyTargets(context, rows, journals)

        requireFixed(activeNotifications(context).isEmpty(), NOTIFICATION_AUTO_CANCEL_REQUIRED)
        requireFixed(
            notificationChannelSnapshot(context) == snapshot.channels,
            CHANNEL_BASELINE_CHANGED,
        )

        withForegroundIndex(app) {
            app.container.onExternalProviderChanged()
            awaitIndexedConversation(app, rows)
        }

        cleanupExactMutation(
            context = context,
            app = app,
            snapshot = snapshot,
            rows = rows,
            journals = journals,
            contract = contract,
            deliveries = deliveries,
        )
    }

    private fun cleanup(context: Context, contract: JourneyContract) {
        val app = context as? AuroraSmsApplication ?: failFixed(APPLICATION_INVALID)
        val snapshot = readCheckpoint(context, contract.checkpointValue)
        val rows = controlledProviderRows(context, contract)
        requireFixed(rows.size <= contract.expectedDeliveryCount, CLEANUP_PROVIDER_AMBIGUOUS)
        val journals = parsedJournals(context)
        requireFixed(journals.size <= contract.expectedDeliveryCount, CLEANUP_JOURNAL_AMBIGUOUS)
        val deliveries = if (
            contract.fixedPduHexes != null || rows.isNotEmpty() || journals.isNotEmpty()
        ) {
            expectedDeliveries(contract)
        } else {
            emptyList()
        }
        assertControlledProviderRows(
            rows = rows,
            preparedAtMillis = snapshot.preparedAtMillis,
            deliveries = deliveries,
            allowPartial = true,
        )
        val journalsByBody = journalsByBody(journals, deliveries)
        journalsByBody.forEach { (body, journal) ->
            val delivery = deliveries.single { it.body == body }
            val row = rows.singleOrNull { it.body == delivery.body }
            assertControlledJournalIdentity(
                journal = journal,
                row = row,
                preparedAtMillis = snapshot.preparedAtMillis,
                delivery = delivery,
            )
            if (row != null && journal.providerId > 0L) {
                requireFixed(
                    journal.providerId == row.id && journal.conversationId == row.threadId,
                    CLEANUP_IDENTITY_MISMATCH,
                )
            }
        }

        cleanupExactMutation(
            context = context,
            app = app,
            snapshot = snapshot,
            rows = rows,
            journals = journals,
            contract = contract,
            deliveries = deliveries,
        )
    }

    private fun cleanupExactMutation(
        context: Context,
        app: AuroraSmsApplication,
        snapshot: Checkpoint,
        rows: List<ProviderRow>,
        journals: List<JournalEntry>,
        contract: JourneyContract,
        deliveries: List<ExpectedDelivery>,
    ) {
        val currentProviderCount = providerCount(context)
        requireFixed(currentProviderCount == rows.size.toLong(), CLEANUP_PROVIDER_AMBIGUOUS)
        assertControlledProviderRows(
            rows = rows,
            preparedAtMillis = snapshot.preparedAtMillis,
            deliveries = deliveries,
            allowPartial = true,
        )
        requireFixed(
            deliveryJournal(context).all.keys == journals.mapTo(mutableSetOf(), JournalEntry::key),
            JOURNAL_DELTA_CHANGED,
        )
        val journalsByBody = journalsByBody(journals, deliveries)
        journalsByBody.forEach { (body, journal) ->
            val delivery = deliveries.single { it.body == body }
            val row = rows.singleOrNull { it.body == delivery.body }
            assertControlledJournalIdentity(
                journal = journal,
                row = row,
                preparedAtMillis = snapshot.preparedAtMillis,
                delivery = delivery,
            )
            if (row != null && journal.providerId > 0L) {
                requireFixed(
                    journal.providerId == row.id && journal.conversationId == row.threadId,
                    CLEANUP_IDENTITY_MISMATCH,
                )
            }
        }
        rows.forEach { row ->
            val journal = journalsByBody[row.body]
            requireFixed(
                journal?.providerId == row.id && journal.conversationId == row.threadId,
                CLEANUP_JOURNAL_REQUIRED,
            )
        }
        val replyTargetKeys = validateCleanupReplyTargets(context, rows, journals)
        val threadIds = buildSet {
            rows.mapTo(this) { it.threadId }
            journals.mapNotNullTo(this) { it.conversationId.takeIf { value -> value > 0L } }
        }
        requireFixed(threadIds.size <= 1, CLEANUP_IDENTITY_MISMATCH)
        val threadId = threadIds.singleOrNull()
        val notificationJournal = journals
            .filter { it.providerId > 0L }
            .maxWithOrNull(compareBy<JournalEntry>({ it.receivedAtMillis }, { it.providerId }))
        val controlledNotifications = controlledActiveNotifications(
            context,
            threadId,
            notificationJournal,
            contract,
        )
        requireFixed(
            notificationChannelSnapshot(context) == snapshot.channels,
            CHANNEL_BASELINE_CHANGED,
        )

        if (controlledNotifications.isNotEmpty()) {
            val manager = context.getSystemService(NotificationManager::class.java)
                ?: failFixed(NOTIFICATION_MANAGER_UNAVAILABLE)
            controlledNotifications.forEach { notification ->
                manager.cancel(notification.tag, notification.id)
            }
        }

        if (rows.isNotEmpty()) {
            val exact = controlledProviderRows(context, contract)
            requireFixed(sameProviderRows(exact, rows), CLEANUP_PROVIDER_CHANGED)
        }
        rows.forEach { row ->
            val deleted = context.contentResolver.delete(
                ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, row.id),
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.ADDRESS} = ? AND " +
                    "${Telephony.Sms.BODY} = ? AND ${Telephony.Sms.DATE} = ? AND " +
                    "${Telephony.Sms.DATE_SENT} = ?",
                arrayOf(
                    row.threadId.toString(),
                    FIXTURE_SENDER,
                    row.body,
                    row.receivedAtMillis.toString(),
                    row.sentAtMillis.toString(),
                ),
            )
            requireFixed(deleted == 1, EXACT_PROVIDER_DELETE_FAILED)
        }

        if (journals.isNotEmpty()) {
            val journalPreferences = deliveryJournal(context)
            requireFixed(
                journalPreferences.all.keys == journals.mapTo(mutableSetOf(), JournalEntry::key),
                JOURNAL_DELTA_CHANGED,
            )
            val editor = journalPreferences.edit()
            journals.forEach { editor.remove(it.key) }
            requireFixed(editor.commit(), JOURNAL_DELETE_FAILED)
        }

        if (replyTargetKeys.isNotEmpty()) {
            val targets = replyTargets(context)
            requireFixed(
                targets.all.keys == replyTargetKeys,
                REPLY_TARGET_DELTA_CHANGED,
            )
            val editor = targets.edit()
            replyTargetKeys.forEach(editor::remove)
            requireFixed(editor.commit(), REPLY_TARGET_DELETE_FAILED)
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
        requireFixed(controlledProviderRows(context, contract).isEmpty(), PROVIDER_ROW_REMAINED)
        requireFixed(deliveryJournal(context).all.isEmpty(), JOURNAL_BASELINE_NOT_RESTORED)
        requireFixed(replyTargets(context).all.isEmpty(), REPLY_BASELINE_NOT_RESTORED)
        requireFixed(activeNotifications(context).isEmpty(), NOTIFICATION_BASELINE_NOT_RESTORED)
        requireFixed(
            notificationChannelSnapshot(context) == snapshot.channels,
            CHANNEL_BASELINE_CHANGED,
        )
        requireFixed(checkpoint(context).edit().clear().commit(), CHECKPOINT_DELETE_FAILED)
    }

    private fun awaitControlledProviderRows(
        context: Context,
        preparedAtMillis: Long,
        contract: JourneyContract,
        deliveries: List<ExpectedDelivery>,
    ): List<ProviderRow> {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        do {
            val rows = controlledProviderRows(context, contract)
            if (rows.size == deliveries.size && providerCount(context) == deliveries.size.toLong()) {
                assertControlledProviderRows(
                    rows = rows,
                    preparedAtMillis = preparedAtMillis,
                    deliveries = deliveries,
                    allowPartial = false,
                )
                return rows
            }
            requireFixed(rows.size <= deliveries.size, PROVIDER_DELTA_AMBIGUOUS)
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(PROVIDER_ROW_UNAVAILABLE)
    }

    private fun assertControlledProviderRows(
        rows: List<ProviderRow>,
        preparedAtMillis: Long,
        deliveries: List<ExpectedDelivery>,
        allowPartial: Boolean,
    ) {
        requireFixed(
            if (allowPartial) rows.size <= deliveries.size else rows.size == deliveries.size,
            PROVIDER_DELTA_AMBIGUOUS,
        )
        requireFixed(rows.map(ProviderRow::id).distinct().size == rows.size, PROVIDER_IDS_INVALID)
        requireFixed(rows.map(ProviderRow::body).distinct().size == rows.size, PROVIDER_BODY_INVALID)
        val deliveriesByBody = deliveries.associateBy(ExpectedDelivery::body)
        rows.forEach { row ->
            val delivery = row.body?.let(deliveriesByBody::get)
                ?: failFixed(PROVIDER_BODY_INVALID)
            assertControlledProviderRow(row, preparedAtMillis, delivery)
        }
        if (rows.size > 1) {
            requireFixed(rows.map(ProviderRow::threadId).distinct().size == 1, PROVIDER_THREAD_INVALID)
            requireFixed(
                rows.map(ProviderRow::receivedAtMillis).distinct().size == rows.size,
                PROVIDER_RECEIVED_TIME_INVALID,
            )
            requireFixed(
                rows.map(ProviderRow::sentAtMillis).distinct().size == rows.size,
                PROVIDER_SENT_TIME_INVALID,
            )
            requireFixed(
                rows.sortedBy(ProviderRow::receivedAtMillis).map(ProviderRow::body) ==
                    deliveries.sortedBy(ExpectedDelivery::sentTimestampMillis)
                        .map(ExpectedDelivery::body),
                PROVIDER_RECEIVED_TIME_INVALID,
            )
        }
    }

    private fun assertControlledProviderRow(
        row: ProviderRow,
        preparedAtMillis: Long,
        delivery: ExpectedDelivery,
    ) {
        val now = System.currentTimeMillis()
        requireFixed(row.id > 0L && row.threadId > 0L, PROVIDER_IDS_INVALID)
        requireFixed(row.address == FIXTURE_SENDER, PROVIDER_SENDER_INVALID)
        requireFixed(row.body == delivery.body, PROVIDER_BODY_INVALID)
        requireFixed(row.type == Telephony.Sms.MESSAGE_TYPE_INBOX, PROVIDER_BOX_INVALID)
        requireFixed(!row.read && !row.seen, PROVIDER_READ_STATE_INVALID)
        requireFixed(row.receivedAtMillis in preparedAtMillis..(now + CLOCK_TOLERANCE_MILLIS), PROVIDER_RECEIVED_TIME_INVALID)
        requireFixed(
            row.sentAtMillis == delivery.sentTimestampMillis,
            PROVIDER_SENT_TIME_INVALID,
        )
    }

    private fun awaitCompletedJournals(
        context: Context,
        rows: List<ProviderRow>,
        preparedAtMillis: Long,
        deliveries: List<ExpectedDelivery>,
    ): List<JournalEntry> {
        val timeoutAt = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS
        do {
            val journals = parsedJournals(context)
            requireFixed(journals.size <= deliveries.size, JOURNAL_DELTA_AMBIGUOUS)
            if (journals.size == deliveries.size && journals.all { it.state == JOURNAL_COMPLETE }) {
                val matchedDeliveries = journals.map { journal ->
                    val delivery = expectedDeliveryForJournal(journal, deliveries)
                    val row = rows.singleOrNull { it.body == delivery.body }
                        ?: failFixed(JOURNAL_PROVIDER_ID_INVALID)
                    assertControlledJournalIdentity(
                        journal = journal,
                        row = row,
                        preparedAtMillis = preparedAtMillis,
                        delivery = delivery,
                    )
                    requireFixed(journal.providerId == row.id, JOURNAL_PROVIDER_ID_INVALID)
                    requireFixed(journal.conversationId == row.threadId, JOURNAL_THREAD_ID_INVALID)
                    delivery.body
                }
                requireFixed(
                    matchedDeliveries.toSet() == deliveries.mapTo(mutableSetOf(), ExpectedDelivery::body),
                    JOURNAL_DELTA_AMBIGUOUS,
                )
                return journals
            }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.uptimeMillis() < timeoutAt)
        failFixed(JOURNAL_COMPLETE_UNAVAILABLE)
    }

    private fun expectedDeliveryForJournal(
        journal: JournalEntry,
        deliveries: List<ExpectedDelivery>,
    ): ExpectedDelivery {
        val matches = deliveries.filter { delivery ->
            journal.key == expectedJournalKey(journal.subscriptionId, delivery.pdu)
        }
        requireFixed(matches.size == 1, JOURNAL_KEY_INVALID)
        return matches.single()
    }

    private fun journalsByBody(
        journals: List<JournalEntry>,
        deliveries: List<ExpectedDelivery>,
    ): Map<String, JournalEntry> {
        val pairs = journals.map { journal ->
            expectedDeliveryForJournal(journal, deliveries).body to journal
        }
        requireFixed(pairs.map(Pair<String, JournalEntry>::first).distinct().size == pairs.size, JOURNAL_DELTA_AMBIGUOUS)
        return pairs.toMap()
    }

    private fun assertControlledJournalIdentity(
        journal: JournalEntry,
        row: ProviderRow?,
        preparedAtMillis: Long,
        delivery: ExpectedDelivery,
    ) {
        requireFixed(
            journal.key == expectedJournalKey(journal.subscriptionId, delivery.pdu),
            JOURNAL_KEY_INVALID,
        )
        requireFixed(RECOVERY_TOKEN_PATTERN.matches(journal.recoveryToken), JOURNAL_TOKEN_INVALID)
        requireFixed(journal.state in JOURNAL_STATES, JOURNAL_STATE_INVALID)
        requireFixed(journal.receivedAtMillis >= preparedAtMillis, JOURNAL_RECEIVED_TIME_INVALID)
        requireFixed(
            journal.sentAtMillis == delivery.sentTimestampMillis,
            JOURNAL_SENT_TIME_INVALID,
        )
        requireFixed(journal.updatedAtMillis >= journal.receivedAtMillis, JOURNAL_UPDATED_TIME_INVALID)
        requireFixed(
            journal.providerContentDigest == expectedProviderContentDigest(
                sender = FIXTURE_SENDER,
                body = delivery.body,
            ),
            JOURNAL_PROVIDER_CONTENT_DIGEST_INVALID,
        )
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

    private fun assertReplyTargets(
        context: Context,
        rows: List<ProviderRow>,
        journals: List<JournalEntry>,
    ) {
        val targets = replyTargets(context).all
        val expectedKeys = journals.mapNotNullTo(mutableSetOf()) { journal ->
            journal.providerId.takeIf { journal.subscriptionId >= 0 && it > 0L }?.let { providerId ->
                "$REPLY_TARGET_PREFIX${ProviderKind.SMS.name}:$providerId"
            }
        }
        requireFixed(targets.keys == expectedKeys, REPLY_TARGET_REQUIRED)
        journals.forEach { journal ->
            if (journal.subscriptionId == NO_SUBSCRIPTION) return@forEach
            val row = rows.singleOrNull { it.id == journal.providerId }
                ?: failFixed(REPLY_TARGET_IDENTITY_INVALID)
            val key = "$REPLY_TARGET_PREFIX${ProviderKind.SMS.name}:${row.id}"
            val encoded = targets[key] as? String ?: failFixed(REPLY_TARGET_IDENTITY_INVALID)
            assertEncodedReplyTarget(encoded, row.id, row.threadId, journal.subscriptionId)
        }
    }

    private fun validateCleanupReplyTargets(
        context: Context,
        rows: List<ProviderRow>,
        journals: List<JournalEntry>,
    ): Set<String> {
        val targets = replyTargets(context).all
        requireFixed(targets.size <= journals.size, REPLY_TARGET_DELTA_CHANGED)
        targets.forEach { (key, raw) ->
            val controlledJournal = journals.singleOrNull { journal ->
                key == "$REPLY_TARGET_PREFIX${ProviderKind.SMS.name}:${journal.providerId}"
            } ?: failFixed(REPLY_TARGET_IDENTITY_INVALID)
            requireFixed(
                controlledJournal.providerId > 0L && controlledJournal.subscriptionId >= 0,
                REPLY_TARGET_UNEXPECTED,
            )
            val row = rows.singleOrNull { it.id == controlledJournal.providerId }
            if (row != null) {
                requireFixed(
                    row.threadId == controlledJournal.conversationId,
                    REPLY_TARGET_IDENTITY_INVALID,
                )
            }
            val encoded = raw as? String ?: failFixed(REPLY_TARGET_IDENTITY_INVALID)
            assertEncodedReplyTarget(
                encoded,
                controlledJournal.providerId,
                controlledJournal.conversationId,
                controlledJournal.subscriptionId,
            )
        }
        return targets.keys
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
        val requestId = "${ProviderKind.SMS.name}:$providerId"
        requireFixed(
            fields[1] == encodeCanonicalReplyTargetText(requestId),
            REPLY_TARGET_IDENTITY_INVALID,
        )
        requireFixed(
            fields[2].toLongOrNull() == conversationId &&
                fields[2] == conversationId.toString(),
            REPLY_TARGET_IDENTITY_INVALID,
        )
        requireFixed(
            fields[3].toIntOrNull() == subscriptionId &&
                fields[3] == subscriptionId.toString(),
            REPLY_TARGET_SUBSCRIPTION_INVALID,
        )
        requireFixed(
            fields[4].toLongOrNull()?.let {
                it > System.currentTimeMillis() && fields[4] == it.toString()
            } == true,
            REPLY_TARGET_EXPIRY_INVALID,
        )
        val decodedAddress = runCatching {
            String(
                Base64.decode(
                    fields[5],
                    REPLY_TARGET_BASE64_FLAGS,
                ),
                Charsets.UTF_8,
            )
        }.getOrNull()
        requireFixed(
            decodedAddress == FIXTURE_SENDER &&
                fields[5] == encodeCanonicalReplyTargetText(FIXTURE_SENDER),
            REPLY_TARGET_RECIPIENT_INVALID,
        )
        val expectedChecksum = sha256Hex(
            fields.dropLast(1)
                .joinToString(REPLY_TARGET_SEPARATOR)
                .toByteArray(StandardCharsets.UTF_8),
        )
        requireFixed(
            REPLY_TARGET_SHA256_PATTERN.matches(fields[6]) &&
                MessageDigest.isEqual(
                    fields[6].toByteArray(StandardCharsets.US_ASCII),
                    expectedChecksum.toByteArray(StandardCharsets.US_ASCII),
                ),
            REPLY_TARGET_ENCODING_INVALID,
        )
    }

    private fun encodeCanonicalReplyTargetText(value: String): String = Base64.encodeToString(
        value.toByteArray(StandardCharsets.UTF_8),
        REPLY_TARGET_BASE64_FLAGS,
    )

    private fun sha256Hex(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }

    private fun controlledActiveNotifications(
        context: Context,
        threadId: Long?,
        journal: JournalEntry?,
        contract: JourneyContract,
    ): List<StatusBarNotification> {
        val active = activeNotifications(context)
        val maximumActiveNotifications = if (contract.allowReplyFailureNotification) 2 else 1
        requireFixed(active.size <= maximumActiveNotifications, NOTIFICATION_DELTA_AMBIGUOUS)
        if (active.isEmpty()) return emptyList()
        val conversation = threadId ?: failFixed(NOTIFICATION_IDENTITY_INVALID)
        val expectedId = notificationId(conversation)
        val expectedTag = "$CONVERSATION_NOTIFICATION_TAG_PREFIX$conversation"
        val expectedFailureId = expectedId xor REPLY_FAILURE_ID_MASK
        val expectedFailureTagPrefix = "$REPLY_FAILURE_NOTIFICATION_TAG_PREFIX$conversation:"
        val conversationStatus = active.singleOrNull { status ->
            status.id == expectedId && status.tag == expectedTag
        }
        val failureStatus = active.singleOrNull { status ->
            status.id == expectedFailureId && status.tag?.let { tag ->
                tag.startsWith(expectedFailureTagPrefix) &&
                    tag.removePrefix(expectedFailureTagPrefix)
                        .toLongOrNull()
                        ?.takeIf { operationId ->
                            operationId >= INLINE_REPLY_OPERATION_ID_BOUNDARY &&
                                tag == "$expectedFailureTagPrefix$operationId"
                        } != null
            } == true
        }
        requireFixed(
            contract.allowReplyFailureNotification || failureStatus == null,
            NOTIFICATION_DELTA_AMBIGUOUS,
        )
        requireFixed(
            active.all { status ->
                status === conversationStatus ||
                    contract.allowReplyFailureNotification && status === failureStatus
            },
            NOTIFICATION_DELTA_AMBIGUOUS,
        )
        conversationStatus?.let { status ->
            assertControlledConversationNotification(
                context = context,
                status = status,
                journal = journal,
                expectedId = expectedId,
                expectedTag = expectedTag,
            )
        }
        failureStatus?.let { status ->
            assertControlledReplyFailureNotification(context, status)
        }
        return active.toList()
    }

    private fun assertControlledConversationNotification(
        context: Context,
        status: StatusBarNotification,
        journal: JournalEntry?,
        expectedId: Int,
        expectedTag: String,
    ) {
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
    }

    private fun assertControlledReplyFailureNotification(
        context: Context,
        status: StatusBarNotification,
    ) {
        val notification = status.notification
        val contentIntent = notification.contentIntent
            ?: failFixed(NOTIFICATION_CONTRACT_INVALID)
        requireFixed(
            status.packageName == TARGET_PACKAGE &&
                status.uid == context.applicationInfo.uid &&
                status.isClearable &&
                notification.channelId == NotificationChannels.REPLY_FAILURES &&
                notification.category == Notification.CATEGORY_ERROR &&
                notification.visibility == Notification.VISIBILITY_PRIVATE &&
                notification.flags and Notification.FLAG_AUTO_CANCEL != 0 &&
                notification.actions.orEmpty().isEmpty() &&
                contentIntent.creatorPackage == TARGET_PACKAGE &&
                contentIntent.creatorUid == context.applicationInfo.uid &&
                contentIntent.isActivity,
            NOTIFICATION_CONTRACT_INVALID,
        )
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

    private fun sameProviderRows(first: List<ProviderRow>, second: List<ProviderRow>): Boolean {
        if (first.size != second.size) return false
        val firstById = first.associateBy(ProviderRow::id)
        val secondById = second.associateBy(ProviderRow::id)
        return firstById.keys == secondById.keys && firstById.all { (id, row) ->
            sameProviderRow(row, secondById.getValue(id))
        }
    }

    private suspend fun awaitIndexedConversation(app: AuroraSmsApplication, rows: List<ProviderRow>) {
        requireFixed(rows.isNotEmpty(), INDEX_COUNT_INVALID)
        val threadIds = rows.map(ProviderRow::threadId).distinct()
        requireFixed(threadIds.size == 1, INDEX_THREAD_ID_INVALID)
        val providerThreadId = ProviderThreadId(threadIds.single())
        val latestRow = rows.maxWithOrNull(
            compareBy<ProviderRow>({ it.receivedAtMillis }, { it.id }),
        ) ?: failFixed(INDEX_PROVIDER_ID_INVALID)
        val providerMessageId = ProviderMessageId(ProviderKind.SMS, latestRow.id)
        val timeoutAt = SystemClock.uptimeMillis() + INDEX_WAIT_TIMEOUT_MILLIS
        do {
            val lookup = app.container.conversationRepository.loadConversation(providerThreadId)
            val contents = rows.associateWith { row ->
                app.container.threadTimelineRepository.loadContent(
                    providerThreadId,
                    ProviderMessageId(ProviderKind.SMS, row.id),
                )
            }
            if (lookup is ConversationLookupResult.Found &&
                lookup.coverage.verifiedComplete &&
                lookup.verifiedIdentity != null &&
                contents.values.all { content ->
                    content is TimelineContentResult.Found && content.coverage.verifiedComplete
                }
            ) {
                val summary = lookup.summary
                val identity = lookup.verifiedIdentity ?: failFixed(VERIFIED_IDENTITY_INVALID)
                requireFixed(summary.providerThreadId == providerThreadId, INDEX_THREAD_ID_INVALID)
                requireFixed(summary.latestProviderMessageId == providerMessageId, INDEX_PROVIDER_ID_INVALID)
                requireFixed(summary.latestDirection == MessageDirection.INCOMING, INDEX_DIRECTION_INVALID)
                requireFixed(summary.latestBox == MessageBox.INBOX, INDEX_BOX_INVALID)
                requireFixed(summary.latestStatus == MessageStatus.NONE, INDEX_STATUS_INVALID)
                requireFixed(summary.latestSenderAddress == ParticipantAddress(FIXTURE_SENDER), INDEX_SENDER_INVALID)
                requireFixed(summary.latestSnippet == latestRow.body, INDEX_BODY_INVALID)
                requireFixed(
                    !summary.latestRead && summary.indexedUnreadCount == rows.size.toLong(),
                    INDEX_UNREAD_INVALID,
                )
                requireFixed(summary.indexedMessageCount == rows.size.toLong(), INDEX_COUNT_INVALID)
                requireFixed(
                    identity.providerThreadId == providerThreadId &&
                        identity.participants == listOf(ParticipantAddress(FIXTURE_SENDER)),
                    VERIFIED_IDENTITY_INVALID,
                )
                contents.forEach { (row, result) ->
                    val content = result as? TimelineContentResult.Found
                        ?: failFixed(TIMELINE_PROVIDER_ID_INVALID)
                    requireFixed(
                        content.content.providerMessageId ==
                            ProviderMessageId(ProviderKind.SMS, row.id),
                        TIMELINE_PROVIDER_ID_INVALID,
                    )
                    requireFixed(content.content.body == row.body, TIMELINE_BODY_INVALID)
                    requireFixed(!content.content.sourceTruncated, TIMELINE_TRUNCATION_INVALID)
                }
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

    private fun controlledProviderRows(
        context: Context,
        contract: JourneyContract,
    ): List<ProviderRow> {
        val bodyPredicates = contract.expectedBodies.joinToString(" OR ") {
            "${Telephony.Sms.BODY} = ?"
        }
        val selectionArguments = buildList {
            add(FIXTURE_SENDER)
            addAll(contract.expectedBodies)
        }.toTypedArray()
        return context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            PROVIDER_PROJECTION,
            "${Telephony.Sms.ADDRESS} = ? OR $bodyPredicates",
            selectionArguments,
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
            buildList<ProviderRow> {
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
    }

    private fun parsedJournals(context: Context): List<JournalEntry> =
        deliveryJournal(context).all.map { (key, raw) ->
            val encoded = raw as? String ?: failFixed(JOURNAL_ENCODING_INVALID)
            val fields = encoded.split(JOURNAL_SEPARATOR)
            requireFixed(fields.size == JOURNAL_FIELD_COUNT, JOURNAL_ENCODING_INVALID)
            requireFixed(fields[0] == JOURNAL_VERSION, JOURNAL_ENCODING_INVALID)
            val storageToken = key.removePrefix(JOURNAL_KEY_PREFIX)
            requireFixed(
                key == JOURNAL_KEY_PREFIX + storageToken &&
                    JOURNAL_STORAGE_TOKEN_PATTERN.matches(storageToken),
                JOURNAL_KEY_INVALID,
            )
            val actualChecksum = fields.last()
            val expectedChecksum = expectedJournalChecksum(
                storageToken = storageToken,
                payload = fields.dropLast(1).joinToString(JOURNAL_SEPARATOR),
            )
            requireFixed(
                JOURNAL_SHA256_PATTERN.matches(actualChecksum) &&
                    MessageDigest.isEqual(
                        actualChecksum.toByteArray(StandardCharsets.US_ASCII),
                        expectedChecksum.toByteArray(StandardCharsets.US_ASCII),
                    ),
                JOURNAL_ENCODING_INVALID,
            )
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
                providerContentDigest = fields[9].takeIf(
                    JOURNAL_PROVIDER_CONTENT_DIGEST_PATTERN::matches,
                ) ?: failFixed(JOURNAL_ENCODING_INVALID),
            )
        }

    private fun expectedDeliveries(contract: JourneyContract): List<ExpectedDelivery> {
        val encodedPdus = contract.fixedPduHexes ?: listOf(
            InstrumentationRegistry.getArguments().getString(
                contract.emittedPduArgument ?: failFixed(EMITTED_PDU_REQUIRED),
            ) ?: failFixed(EMITTED_PDU_REQUIRED),
        )
        requireFixed(encodedPdus.size == contract.expectedDeliveryCount, EXPECTED_PDU_INVALID)
        return contract.expectedBodies.zip(encodedPdus).mapIndexed { index, (body, encoded) ->
            val pdu = decodeExpectedPdu(encoded, body)
            val decodedTimestamp = expectedSentTimestampMillis(pdu)
            val expectedTimestamp = contract.fixedSentTimestampsMillis
                ?.get(index)
                ?: decodedTimestamp
            requireFixed(decodedTimestamp == expectedTimestamp, EXPECTED_PDU_INVALID)
            ExpectedDelivery(
                body = body,
                pdu = pdu,
                sentTimestampMillis = expectedTimestamp,
            )
        }
    }

    private fun decodeExpectedPdu(encoded: String, expectedBody: String): ByteArray {
        requireFixed(
            encoded.isNotEmpty() &&
                encoded.length % 2 == 0 &&
                EMITTED_PDU_HEX_PATTERN.matches(encoded),
            EXPECTED_PDU_INVALID,
        )
        val decoded = ByteArray(encoded.length / 2) { index ->
            encoded.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        val message = decodedSmsMessage(decoded)
        requireFixed(
            message.displayOriginatingAddress?.trim() == FIXTURE_SENDER,
            EXPECTED_PDU_INVALID,
        )
        requireFixed(
            message.displayMessageBody.orEmpty() == expectedBody,
            EXPECTED_PDU_INVALID,
        )
        return decoded
    }

    private fun expectedSentTimestampMillis(pdu: ByteArray): Long =
        decodedSmsMessage(pdu).timestampMillis.coerceAtLeast(0L)

    private fun decodedSmsMessage(pdu: ByteArray): SmsMessage =
        SmsMessage.createFromPdu(pdu, FINGERPRINT_FORMAT_VALUE)
            ?: failFixed(EXPECTED_PDU_INVALID)

    private fun expectedJournalKey(subscriptionId: Int, pdu: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(FINGERPRINT_DOMAIN)
        digest.updateInt(subscriptionId)
        digest.updateInt(FINGERPRINT_FORMAT.size)
        digest.update(FINGERPRINT_FORMAT)
        digest.updateInt(1)
        digest.updateInt(pdu.size)
        digest.update(pdu)
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

    private fun expectedProviderContentDigest(sender: String, body: String): String {
        val senderBytes = sender.toByteArray(StandardCharsets.UTF_8)
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").apply {
            update(PROVIDER_CONTENT_DIGEST_DOMAIN)
            updateInt(senderBytes.size)
            update(senderBytes)
            updateInt(bodyBytes.size)
            update(bodyBytes)
        }.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun expectedJournalChecksum(storageToken: String, payload: String): String =
        MessageDigest.getInstance("SHA-256").apply {
            update(JOURNAL_CHECKSUM_DOMAIN)
            update(byteArrayOf(0))
            update(storageToken.toByteArray(StandardCharsets.US_ASCII))
            update(byteArrayOf(0))
            update(payload.toByteArray(StandardCharsets.UTF_8))
        }.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
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

    private fun readCheckpoint(context: Context, expectedJourney: String): Checkpoint {
        val checkpoint = checkpoint(context)
        requireFixed(checkpoint.getBoolean(CHECKPOINT_ARMED, false), CHECKPOINT_REQUIRED)
        requireFixed(
            checkpoint.getString(CHECKPOINT_JOURNEY, null) == expectedJourney,
            CHECKPOINT_INVALID,
        )
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

    private fun requireGate(argument: String) {
        val enabled = InstrumentationRegistry.getArguments().getString(argument)
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

    private data class JourneyContract(
        val gateArgument: String,
        val phaseArgument: String,
        val expectedSdk: Int,
        val apiRequiredMessage: String,
        val checkpointValue: String,
        val notificationsEnabled: Boolean,
        val allowReplyFailureNotification: Boolean,
        val prepareStatusCode: Int,
        val verifyStatusCode: Int,
        val cleanupStatusCode: Int,
        val prepareSentinel: String,
        val verifySentinel: String,
        val cleanupSentinel: String,
        val emittedPduArgument: String?,
        val expectedBodies: List<String>,
        val fixedPduHexes: List<String>?,
        val fixedSentTimestampsMillis: List<Long>?,
    ) {
        init {
            require(expectedBodies.size in 1..MAX_EXPECTED_DELIVERIES)
            require(expectedBodies.distinct().size == expectedBodies.size)
            require((emittedPduArgument == null) != (fixedPduHexes == null))
            require(fixedPduHexes == null || fixedPduHexes.size == expectedBodies.size)
            require((fixedPduHexes == null) == (fixedSentTimestampsMillis == null))
            require(
                fixedSentTimestampsMillis == null ||
                    fixedSentTimestampsMillis.size == expectedBodies.size,
            )
        }

        val expectedDeliveryCount: Int
            get() = expectedBodies.size

        override fun toString(): String = "JourneyContract(expectedDeliveryCount=$expectedDeliveryCount)"
    }

    private data class ExpectedDelivery(
        val body: String,
        val pdu: ByteArray,
        val sentTimestampMillis: Long,
    ) {
        override fun toString(): String = "ExpectedDelivery(REDACTED)"
    }

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
        val providerContentDigest: String,
    ) {
        override fun toString(): String = "JournalEntry(state=$state, REDACTED)"
    }

    private companion object {
        const val TARGET_PACKAGE = "org.aurorasms.app"
        const val MAX_EXPECTED_DELIVERIES = 2
        const val GATE_ARGUMENT = "auroraEmulatorIncomingSmsColdNotification"
        const val PHASE_ARGUMENT = "auroraEmulatorIncomingSmsColdNotificationPhase"
        const val NOTIFICATION_DENIED_GATE_ARGUMENT =
            "auroraEmulatorIncomingSmsNotificationDenied"
        const val NOTIFICATION_DENIED_PHASE_ARGUMENT =
            "auroraEmulatorIncomingSmsNotificationDeniedPhase"
        const val MULTIPLE_MESSAGE_GATE_ARGUMENT =
            "auroraEmulatorIncomingSmsMultipleMessage"
        const val MULTIPLE_MESSAGE_PHASE_ARGUMENT =
            "auroraEmulatorIncomingSmsMultipleMessagePhase"
        const val INLINE_REPLY_PERMISSION_DENIED_GATE_ARGUMENT =
            "auroraEmulatorInlineReplyPermissionDenied"
        const val INLINE_REPLY_PERMISSION_DENIED_PHASE_ARGUMENT =
            "auroraEmulatorInlineReplyPermissionDeniedPhase"
        const val EMITTED_PDU_ARGUMENT = "auroraEmulatorIncomingSmsEmittedPduHex"
        const val PHASE_PREPARE = "prepare"
        const val PHASE_VERIFY = "verify"
        const val PHASE_CLEANUP = "cleanup"
        const val PREPARE_STATUS_CODE = 46
        const val VERIFY_STATUS_CODE = 47
        const val CLEANUP_STATUS_CODE = 48
        const val PREPARE_SENTINEL = "auroraIncomingSmsPrepareResult"
        const val VERIFY_SENTINEL = "auroraIncomingSmsVerifyResult"
        const val CLEANUP_SENTINEL = "auroraIncomingSmsCleanupResult"
        const val NOTIFICATION_DENIED_PREPARE_STATUS_CODE = 49
        const val NOTIFICATION_DENIED_VERIFY_STATUS_CODE = 50
        const val NOTIFICATION_DENIED_CLEANUP_STATUS_CODE = 51
        const val NOTIFICATION_DENIED_PREPARE_SENTINEL =
            "auroraNotificationDeniedPrepareResult"
        const val NOTIFICATION_DENIED_VERIFY_SENTINEL =
            "auroraNotificationDeniedVerifyResult"
        const val NOTIFICATION_DENIED_CLEANUP_SENTINEL =
            "auroraNotificationDeniedCleanupResult"
        const val MULTIPLE_MESSAGE_PREPARE_STATUS_CODE = 52
        const val MULTIPLE_MESSAGE_VERIFY_STATUS_CODE = 53
        const val MULTIPLE_MESSAGE_CLEANUP_STATUS_CODE = 54
        const val MULTIPLE_MESSAGE_PREPARE_SENTINEL =
            "auroraMultipleMessagePrepareResult"
        const val MULTIPLE_MESSAGE_VERIFY_SENTINEL =
            "auroraMultipleMessageVerifyResult"
        const val MULTIPLE_MESSAGE_CLEANUP_SENTINEL =
            "auroraMultipleMessageCleanupResult"
        const val INLINE_REPLY_PERMISSION_DENIED_PREPARE_STATUS_CODE = 55
        const val INLINE_REPLY_PERMISSION_DENIED_VERIFY_STATUS_CODE = 56
        const val INLINE_REPLY_PERMISSION_DENIED_CLEANUP_STATUS_CODE = 57
        const val INLINE_REPLY_PERMISSION_DENIED_PREPARE_SENTINEL =
            "auroraInlineReplyPermissionDeniedPrepareResult"
        const val INLINE_REPLY_PERMISSION_DENIED_VERIFY_SENTINEL =
            "auroraInlineReplyPermissionDeniedVerifyResult"
        const val INLINE_REPLY_PERMISSION_DENIED_CLEANUP_SENTINEL =
            "auroraInlineReplyPermissionDeniedCleanupResult"
        const val PASS = "pass"

        val COLD_NOTIFICATION_CONTRACT = JourneyContract(
            gateArgument = GATE_ARGUMENT,
            phaseArgument = PHASE_ARGUMENT,
            expectedSdk = Build.VERSION_CODES.O,
            apiRequiredMessage = API_26_REQUIRED,
            checkpointValue = JOURNEY_COLD_NOTIFICATION,
            notificationsEnabled = true,
            allowReplyFailureNotification = false,
            prepareStatusCode = PREPARE_STATUS_CODE,
            verifyStatusCode = VERIFY_STATUS_CODE,
            cleanupStatusCode = CLEANUP_STATUS_CODE,
            prepareSentinel = PREPARE_SENTINEL,
            verifySentinel = VERIFY_SENTINEL,
            cleanupSentinel = CLEANUP_SENTINEL,
            emittedPduArgument = null,
            expectedBodies = listOf(FIXTURE_BODY),
            fixedPduHexes = listOf(FIXTURE_PDU_HEX),
            fixedSentTimestampsMillis = listOf(FIXTURE_SENT_TIMESTAMP_MILLIS),
        )
        val NOTIFICATION_DENIED_CONTRACT = JourneyContract(
            gateArgument = NOTIFICATION_DENIED_GATE_ARGUMENT,
            phaseArgument = NOTIFICATION_DENIED_PHASE_ARGUMENT,
            expectedSdk = Build.VERSION_CODES.BAKLAVA,
            apiRequiredMessage = API_36_REQUIRED,
            checkpointValue = JOURNEY_NOTIFICATION_DENIED,
            notificationsEnabled = false,
            allowReplyFailureNotification = false,
            prepareStatusCode = NOTIFICATION_DENIED_PREPARE_STATUS_CODE,
            verifyStatusCode = NOTIFICATION_DENIED_VERIFY_STATUS_CODE,
            cleanupStatusCode = NOTIFICATION_DENIED_CLEANUP_STATUS_CODE,
            prepareSentinel = NOTIFICATION_DENIED_PREPARE_SENTINEL,
            verifySentinel = NOTIFICATION_DENIED_VERIFY_SENTINEL,
            cleanupSentinel = NOTIFICATION_DENIED_CLEANUP_SENTINEL,
            emittedPduArgument = EMITTED_PDU_ARGUMENT,
            expectedBodies = listOf(NOTIFICATION_DENIED_BODY),
            fixedPduHexes = null,
            fixedSentTimestampsMillis = null,
        )
        val MULTIPLE_MESSAGE_CONTRACT = JourneyContract(
            gateArgument = MULTIPLE_MESSAGE_GATE_ARGUMENT,
            phaseArgument = MULTIPLE_MESSAGE_PHASE_ARGUMENT,
            expectedSdk = Build.VERSION_CODES.O,
            apiRequiredMessage = API_26_REQUIRED,
            checkpointValue = JOURNEY_MULTIPLE_MESSAGE,
            notificationsEnabled = true,
            allowReplyFailureNotification = false,
            prepareStatusCode = MULTIPLE_MESSAGE_PREPARE_STATUS_CODE,
            verifyStatusCode = MULTIPLE_MESSAGE_VERIFY_STATUS_CODE,
            cleanupStatusCode = MULTIPLE_MESSAGE_CLEANUP_STATUS_CODE,
            prepareSentinel = MULTIPLE_MESSAGE_PREPARE_SENTINEL,
            verifySentinel = MULTIPLE_MESSAGE_VERIFY_SENTINEL,
            cleanupSentinel = MULTIPLE_MESSAGE_CLEANUP_SENTINEL,
            emittedPduArgument = null,
            expectedBodies = listOf(FIXTURE_BODY, MULTIPLE_MESSAGE_SECOND_BODY),
            fixedPduHexes = listOf(FIXTURE_PDU_HEX, MULTIPLE_MESSAGE_SECOND_PDU_HEX),
            fixedSentTimestampsMillis = listOf(
                FIXTURE_SENT_TIMESTAMP_MILLIS,
                MULTIPLE_MESSAGE_SECOND_SENT_TIMESTAMP_MILLIS,
            ),
        )
        val INLINE_REPLY_PERMISSION_DENIED_CONTRACT = JourneyContract(
            gateArgument = INLINE_REPLY_PERMISSION_DENIED_GATE_ARGUMENT,
            phaseArgument = INLINE_REPLY_PERMISSION_DENIED_PHASE_ARGUMENT,
            expectedSdk = Build.VERSION_CODES.O,
            apiRequiredMessage = API_26_REQUIRED,
            checkpointValue = JOURNEY_INLINE_REPLY_PERMISSION_DENIED,
            notificationsEnabled = true,
            allowReplyFailureNotification = true,
            prepareStatusCode = INLINE_REPLY_PERMISSION_DENIED_PREPARE_STATUS_CODE,
            verifyStatusCode = INLINE_REPLY_PERMISSION_DENIED_VERIFY_STATUS_CODE,
            cleanupStatusCode = INLINE_REPLY_PERMISSION_DENIED_CLEANUP_STATUS_CODE,
            prepareSentinel = INLINE_REPLY_PERMISSION_DENIED_PREPARE_SENTINEL,
            verifySentinel = INLINE_REPLY_PERMISSION_DENIED_VERIFY_SENTINEL,
            cleanupSentinel = INLINE_REPLY_PERMISSION_DENIED_CLEANUP_SENTINEL,
            emittedPduArgument = null,
            expectedBodies = listOf(FIXTURE_BODY),
            fixedPduHexes = listOf(FIXTURE_PDU_HEX),
            fixedSentTimestampsMillis = listOf(FIXTURE_SENT_TIMESTAMP_MILLIS),
        )

        const val FIXTURE_SENDER = "+15551230017"
        const val FIXTURE_BODY = "AuroraSMS modem delivery 900017"
        const val MULTIPLE_MESSAGE_SECOND_BODY = "AuroraSMS modem delivery 900018"
        const val NOTIFICATION_DENIED_BODY = "AuroraSMS modem delivery marker-alpha"
        const val FIXTURE_SENT_TIMESTAMP_MILLIS = 1_784_328_000_000L
        const val MULTIPLE_MESSAGE_SECOND_SENT_TIMESTAMP_MILLIS = 1_784_328_060_000L
        const val GENERIC_NOTIFICATION_TITLE = "AuroraSMS"
        const val GENERIC_NOTIFICATION_BODY = "New message"
        const val CONVERSATION_NOTIFICATION_TAG_PREFIX = "aurora-conversation:"
        const val FIXTURE_PDU_HEX =
            "00040B915155210310F70000627071220400001FC1BAFC2D0F4F9B5350FB4D2EB741E4323B6D2FCBF3A01C0C068BDD00"
        const val MULTIPLE_MESSAGE_SECOND_PDU_HEX =
            "00040B915155210310F70000627071221400001FC1BAFC2D0F4F9B5350FB4D2EB741E4323B6D2FCBF3A01C0C068BE100"
        val FINGERPRINT_DOMAIN: ByteArray =
            "AuroraSMS.SMS_DELIVERY.v1".toByteArray(StandardCharsets.US_ASCII)
        const val FINGERPRINT_FORMAT_VALUE = "3gpp"
        val FINGERPRINT_FORMAT: ByteArray =
            FINGERPRINT_FORMAT_VALUE.toByteArray(StandardCharsets.US_ASCII)
        val PROVIDER_CONTENT_DIGEST_DOMAIN: ByteArray =
            "AuroraSMS.INCOMING_PROVIDER_CONTENT.v1".toByteArray(StandardCharsets.US_ASCII)
        val EMITTED_PDU_HEX_PATTERN = Regex("[0-9a-fA-F]+")

        const val CHECKPOINT_PREFERENCES = "aurora_incoming_sms_smoke_v1"
        const val CHECKPOINT_ARMED = "armed"
        const val CHECKPOINT_JOURNEY = "journey"
        const val JOURNEY_COLD_NOTIFICATION = "cold_notification"
        const val JOURNEY_NOTIFICATION_DENIED = "notification_denied"
        const val JOURNEY_MULTIPLE_MESSAGE = "multiple_message"
        const val JOURNEY_INLINE_REPLY_PERMISSION_DENIED = "inline_reply_permission_denied"
        const val CHECKPOINT_PREPARED_AT = "prepared_at"
        const val CHECKPOINT_CHANNELS = "channels"
        const val CHECKPOINT_LEDGER_PRESENT = "ledger_present"
        const val CHECKPOINT_LEDGER_VALUE = "ledger_value"
        const val JOURNAL_PREFERENCES = "aurora_sms_delivery_journal_v1"
        const val JOURNAL_KEY_PREFIX = "delivery."
        const val JOURNAL_VERSION = "4"
        const val JOURNAL_SEPARATOR = ","
        const val JOURNAL_FIELD_COUNT = 11
        const val JOURNAL_PENDING = "P"
        const val JOURNAL_STORED = "S"
        const val JOURNAL_COMPLETE = "C"
        val JOURNAL_STATES = setOf(JOURNAL_PENDING, JOURNAL_STORED, JOURNAL_COMPLETE)
        val JOURNAL_PROVIDER_CONTENT_DIGEST_PATTERN = Regex("[0-9a-f]{64}")
        val JOURNAL_STORAGE_TOKEN_PATTERN = Regex("[0-9a-f]{64}")
        val JOURNAL_SHA256_PATTERN = Regex("[0-9a-f]{64}")
        val JOURNAL_CHECKSUM_DOMAIN: ByteArray =
            "AuroraSMS.INCOMING_SMS_REPLAY_JOURNAL.v4"
                .toByteArray(StandardCharsets.US_ASCII)
        const val NO_SUBSCRIPTION = -1
        val RECOVERY_TOKEN_PATTERN =
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        const val REPLY_TARGET_PREFERENCES = "aurora_inline_reply_targets"
        const val REPLY_TARGET_PREFIX = "target."
        const val REPLY_TARGET_VERSION = "2"
        const val REPLY_TARGET_SEPARATOR = "|"
        const val REPLY_TARGET_FIELD_COUNT = 7
        const val REPLY_TARGET_BASE64_FLAGS =
            Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING
        val REPLY_TARGET_SHA256_PATTERN = Regex("[0-9a-f]{64}")
        const val REPLY_FAILURE_NOTIFICATION_TAG_PREFIX = "aurora-reply-failure:"
        const val REPLY_FAILURE_ID_MASK = 0x4000_0000
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
        const val API_33_REQUIRED = "Notification-denied journey requires API 33 or newer"
        const val API_36_REQUIRED = "Notification-denied journey requires API 36"
        const val TARGET_PACKAGE_INVALID = "Incoming SMS target package is invalid"
        const val PHASE_REQUIRED = "Incoming SMS phase is required"
        const val PHASE_INVALID = "Incoming SMS phase is invalid"
        const val EMITTED_PDU_REQUIRED =
            "Notification-denied journey requires the exact emitted SMS PDU hex"
        const val EXPECTED_PDU_INVALID = "Incoming SMS expected PDU is invalid"
        const val APPLICATION_INVALID = "Incoming SMS application entry point is invalid"
        const val DEFAULT_SMS_REQUIRED = "Incoming SMS journey requires default SMS role"
        const val READ_SMS_REQUIRED = "Incoming SMS journey requires READ_SMS"
        const val RECEIVE_SMS_REQUIRED = "Incoming SMS journey requires RECEIVE_SMS"
        const val NOTIFICATIONS_REQUIRED = "Incoming SMS journey requires notifications"
        const val NOTIFICATIONS_DISABLED_REQUIRED =
            "Notification-denied journey requires POST_NOTIFICATIONS to remain denied"
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
        const val PROVIDER_THREAD_INVALID = "Incoming SMS provider thread is invalid"
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
        const val JOURNAL_PROVIDER_CONTENT_DIGEST_INVALID =
            "Incoming SMS journal provider content digest is invalid"
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
