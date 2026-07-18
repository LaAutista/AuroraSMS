// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.BaseColumns
import android.provider.Telephony
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageDeliveryFingerprint
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.IncomingSmsRecord
import org.aurorasms.core.telephony.IncomingDeliveryDisposition
import org.aurorasms.core.telephony.IncomingSmsNotificationReplay
import org.aurorasms.core.telephony.IncomingSmsNotificationReplayRequest
import org.aurorasms.core.telephony.OutgoingSmsRecord
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderPage
import org.aurorasms.core.telephony.ProviderPageCursor
import org.aurorasms.core.telephony.ProviderPageRequest
import org.aurorasms.core.telephony.ProviderStoredMessage
import org.aurorasms.core.telephony.SmsProviderDataSource
import org.aurorasms.core.telephony.SmsProviderMessage
import org.aurorasms.core.telephony.SmsProviderStatus
import org.aurorasms.core.telephony.buildProviderPageFromRaw

class AndroidSmsProviderDataSource(
    context: Context,
    private val roleState: DefaultSmsRoleState,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SmsProviderDataSource {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val incomingReplayJournal = IncomingSmsReplayJournal(appContext)
    private val incomingInsertMutex = Mutex()

    override suspend fun count(): ProviderAccessResult<Long> = withReadAccess("count SMS") {
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(BaseColumns._ID),
            null,
            null,
            null,
        )?.use { cursor ->
            ProviderAccessResult.Success(cursor.count.toLong())
        } ?: ProviderAccessResult.Unavailable("count SMS")
    }

    override suspend fun readPage(
        request: ProviderPageRequest,
    ): ProviderAccessResult<ProviderPage<SmsProviderMessage>> = withReadAccess("read SMS page") {
        val eligibleRows = "${BaseColumns._ID} > 0 AND ${Telephony.Sms.DATE} >= 0"
        val selection = request.before?.let {
            "$eligibleRows AND " +
                "((${Telephony.Sms.DATE} < ?) OR " +
                "(${Telephony.Sms.DATE} = ? AND ${BaseColumns._ID} < ?))"
        } ?: eligibleRows
        val selectionArgs = request.before?.let {
            arrayOf(
                it.timestampMillis.toString(),
                it.timestampMillis.toString(),
                it.providerRowId.toString(),
            )
        }
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            selectionArgs?.let { putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it) }
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${Telephony.Sms.DATE} DESC, ${BaseColumns._ID} DESC",
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, request.limit + 1)
        }
        val projection = arrayOf(
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
            Telephony.Sms.LOCKED,
            Telephony.Sms.STATUS,
            Telephony.Sms.ERROR_CODE,
        )

        resolver.query(Telephony.Sms.CONTENT_URI, projection, queryArgs, null)?.use { cursor ->
            val rawRows = ArrayList<RawSmsProviderRow>(request.limit + 1)
            val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
            val threadIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val sentIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)
            val subscriptionIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID)
            val readIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
            val seenIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.SEEN)
            val lockedIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.LOCKED)
            val statusIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.STATUS)
            val errorCodeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ERROR_CODE)

            while (rawRows.size <= request.limit && cursor.moveToNext()) {
                rawRows += RawSmsProviderRow(
                    id = cursor.getLong(idIndex),
                    threadId = cursor.getLong(threadIndex),
                    address = cursor.getString(addressIndex),
                    body = cursor.getString(bodyIndex),
                    type = cursor.getInt(typeIndex),
                    timestampMillis = cursor.getLong(dateIndex),
                    sentTimestampMillis = cursor.nullableLong(sentIndex),
                    subscriptionId = cursor.nullableInt(subscriptionIndex),
                    read = cursor.getInt(readIndex) != 0,
                    seen = cursor.getInt(seenIndex) != 0,
                    locked = cursor.getInt(lockedIndex) != 0,
                    rawStatus = cursor.nullableInt(statusIndex),
                    rawErrorCode = cursor.nullableInt(errorCodeIndex),
                )
            }

            ProviderAccessResult.Success(
                buildProviderPageFromRaw(
                    request = request,
                    rawRows = rawRows,
                    cursorFor = RawSmsProviderRow::pageCursor,
                    project = RawSmsProviderRow::toProviderMessageOrNull,
                ),
            )
        } ?: ProviderAccessResult.Unavailable("read SMS page")
    }

    override suspend fun insertIncoming(
        message: IncomingSmsRecord,
    ): ProviderAccessResult<ProviderStoredMessage> = incomingInsertMutex.withLock {
        withWriteAccess("insert incoming SMS") {
            insertIncomingSerialized(message)
        }
    }

    override suspend fun readPendingIncomingNotifications(
        request: IncomingSmsNotificationReplayRequest,
    ): ProviderAccessResult<List<IncomingSmsNotificationReplay>> = incomingInsertMutex.withLock {
        withReadAccess("recover pending incoming SMS notifications") {
            val entries = when (
                val journal = incomingReplayJournal.recoveryEntries(
                    IncomingSmsReplayJournal.MAXIMUM_ENTRIES,
                )
            ) {
                IncomingSmsReplayJournal.RecoveryEntriesResult.Corrupt -> {
                    return@withReadAccess ProviderAccessResult.Unavailable(
                        "read incoming SMS delivery journal",
                    )
                }
                is IncomingSmsReplayJournal.RecoveryEntriesResult.Success -> journal.entries
            }
            val replays = ArrayList<IncomingSmsNotificationReplay>(request.limit)
            var hasDeferredEntry = false
            for (entry in entries) {
                val recovered = if (entry.state == IncomingSmsReplayJournal.State.PENDING) {
                    if (
                        !canVerifyPendingIncomingProviderOwnership(entry.providerContentDigest)
                    ) {
                        hasDeferredEntry = true
                        continue
                    }
                    when (val pending = recoverPendingNotificationProviderRow(entry)) {
                        is PendingRecovery.Found -> {
                            if (
                                !incomingReplayJournal.markStored(
                                    entry.fingerprint,
                                    pending.message.providerId,
                                    pending.message.conversationId,
                                )
                            ) {
                                hasDeferredEntry = true
                                continue
                            }
                            pending.message
                        }
                        PendingRecovery.None -> {
                            hasDeferredEntry = true
                            continue
                        }
                        PendingRecovery.Ambiguous -> {
                            hasDeferredEntry = true
                            continue
                        }
                        PendingRecovery.Unavailable -> {
                            hasDeferredEntry = true
                            continue
                        }
                    }
                } else {
                    val providerId = entry.providerId
                    val conversationId = entry.conversationId
                    if (providerId == null || conversationId == null) {
                        if (
                            !incomingReplayJournal.quarantine(
                                entry.fingerprint,
                                IncomingSmsReplayJournal.QuarantineReason.MALFORMED_JOURNAL_RECORD,
                            )
                        ) {
                            hasDeferredEntry = true
                        }
                        continue
                    }
                    ProviderStoredMessage(providerId, conversationId)
                }
                val providerId = recovered.providerId
                val conversationId = recovered.conversationId
                val row = when (val exact = incomingNotificationReplayRow(providerId)) {
                    is ExactIncomingNotificationReplayRow.Found -> exact.row
                    ExactIncomingNotificationReplayRow.Invalid -> {
                        if (
                            !quarantineTerminalIncomingReplayFailure(
                                entry,
                                IncomingStoredReplayRowFailure.INVALID,
                            )
                        ) {
                            hasDeferredEntry = true
                        }
                        continue
                    }
                    ExactIncomingNotificationReplayRow.Missing -> {
                        if (
                            !quarantineTerminalIncomingReplayFailure(
                                entry,
                                IncomingStoredReplayRowFailure.MISSING,
                            )
                        ) {
                            hasDeferredEntry = true
                        }
                        continue
                    }
                    ExactIncomingNotificationReplayRow.Unavailable -> {
                        hasDeferredEntry = true
                        continue
                    }
                }
                val replay = row.toIncomingSmsNotificationReplayOrNull(
                    fingerprint = entry.fingerprint,
                    expectedProviderId = providerId,
                    expectedConversationId = conversationId,
                    expectedReceivedTimestampMillis = entry.receivedTimestampMillis,
                    expectedSentTimestampMillis = entry.sentTimestampMillis,
                    expectedSubscriptionId = entry.subscriptionId,
                    expectedProviderContentDigest = entry.providerContentDigest,
                )
                if (replay == null) {
                    if (
                        !quarantineTerminalIncomingReplayFailure(
                            entry,
                            IncomingStoredReplayRowFailure.VALIDATION_MISMATCH,
                        )
                    ) {
                        hasDeferredEntry = true
                    }
                    continue
                }
                replays += replay
                if (replays.size == request.limit) break
            }
            pendingIncomingNotificationReadResult(
                replays = replays,
                hasUnresolvedPendingEntry = hasDeferredEntry,
            )
        }
    }

    override suspend fun markIncomingHandled(
        deliveryFingerprint: MessageDeliveryFingerprint,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
    ): ProviderAccessResult<Unit> = incomingInsertMutex.withLock {
        withWriteAccess("complete incoming SMS delivery journal") {
            if (providerId.kind != ProviderKind.SMS) {
                return@withWriteAccess ProviderAccessResult.InvalidInput("provider message kind")
            }
            if (incomingReplayJournal.markComplete(deliveryFingerprint, providerId, conversationId)) {
                ProviderAccessResult.Success(Unit)
            } else {
                ProviderAccessResult.Unavailable("complete incoming SMS delivery journal")
            }
        }
    }

    override suspend fun insertOutgoing(
        message: OutgoingSmsRecord,
    ): ProviderAccessResult<ProviderStoredMessage> = withWriteAccess("insert outgoing SMS") {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, message.recipient.value)
            put(Telephony.Sms.BODY, message.body)
            put(Telephony.Sms.DATE, message.timestampMillis)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_PENDING)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.SUBSCRIPTION_ID, message.subscriptionId.value)
            put(Telephony.Sms.CREATOR, appContext.packageName)
        }
        val uri = resolver.insert(Telephony.Sms.Outbox.CONTENT_URI, values)
            ?: return@withWriteAccess ProviderAccessResult.Unavailable("insert outgoing SMS")
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull()
            ?.takeIf { it > 0L }
            ?: return@withWriteAccess ProviderAccessResult.Unavailable("read inserted SMS ID")
        insertedReference(id, incomingDisposition = null)
    }

    override suspend fun updateStatus(
        id: ProviderMessageId,
        status: SmsProviderStatus,
    ): ProviderAccessResult<Unit> = withWriteAccess("update SMS status") {
        if (id.kind != ProviderKind.SMS) {
            return@withWriteAccess ProviderAccessResult.InvalidInput("provider message kind")
        }
        if (
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withWriteAccess ProviderAccessResult.PermissionDenied
        }
        val uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id.value)
        when (
            updateSmsStatusMonotonically(
                requested = status,
                maxWriteAttempts = MAX_STATUS_UPDATE_ATTEMPTS,
                readCurrent = { readSmsProviderStatus(uri) },
                conditionalWrite = { expected, requested ->
                    writeSmsProviderStatusConditionally(uri, expected, requested)
                },
            )
        ) {
            MonotonicSmsStatusUpdateResult.SUCCESS -> ProviderAccessResult.Success(Unit)
            MonotonicSmsStatusUpdateResult.UNAVAILABLE -> {
                ProviderAccessResult.Unavailable("update SMS status")
            }
        }
    }

    private fun readSmsProviderStatus(uri: Uri): SmsProviderStatus? {
        val cursor = resolver.query(
            uri,
            arrayOf(Telephony.Sms.TYPE, Telephony.Sms.STATUS),
            null,
            null,
            null,
        ) ?: return null
        return cursor.use {
            if (!it.moveToFirst()) return@use null
            val messageTypeIndex = it.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val rawStatusIndex = it.getColumnIndexOrThrow(Telephony.Sms.STATUS)
            if (it.isNull(messageTypeIndex) || it.isNull(rawStatusIndex)) return@use null
            val current = smsProviderStatusFromRaw(
                messageType = it.getInt(messageTypeIndex),
                rawStatus = it.getInt(rawStatusIndex),
            )
            if (it.moveToNext()) null else current
        }
    }

    private fun writeSmsProviderStatusConditionally(
        uri: Uri,
        expected: SmsProviderStatus,
        requested: SmsProviderStatus,
    ): ConditionalSmsStatusWriteResult {
        val expectedProjection = expected.toSmsProviderStatusProjection()
        val requestedProjection = requested.toSmsProviderStatusProjection()
        val values = ContentValues().apply {
            put(Telephony.Sms.STATUS, requestedProjection.rawStatus)
            put(Telephony.Sms.TYPE, requestedProjection.messageType)
        }
        val updated = resolver.update(
            uri,
            values,
            "${Telephony.Sms.TYPE} = ? AND ${Telephony.Sms.STATUS} = ?",
            arrayOf(
                expectedProjection.messageType.toString(),
                expectedProjection.rawStatus.toString(),
            ),
        )
        return when (updated) {
            0 -> ConditionalSmsStatusWriteResult.STALE
            1 -> ConditionalSmsStatusWriteResult.UPDATED
            else -> ConditionalSmsStatusWriteResult.UNAVAILABLE
        }
    }

    private suspend fun <T> withReadAccess(
        operation: String,
        block: () -> ProviderAccessResult<T>,
    ): ProviderAccessResult<T> = withContext(ioDispatcher) {
        if (!roleState.isRoleHeld()) return@withContext ProviderAccessResult.RoleRequired
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext ProviderAccessResult.PermissionDenied
        }
        providerCall(operation, block)
    }

    private suspend fun <T> withWriteAccess(
        operation: String,
        block: () -> ProviderAccessResult<T>,
    ): ProviderAccessResult<T> = withContext(ioDispatcher) {
        if (!roleState.isRoleHeld()) return@withContext ProviderAccessResult.RoleRequired
        providerCall(operation, block)
    }

    private fun <T> providerCall(
        operation: String,
        block: () -> ProviderAccessResult<T>,
    ): ProviderAccessResult<T> = try {
        block()
    } catch (_: SecurityException) {
        ProviderAccessResult.PermissionDenied
    } catch (_: IllegalArgumentException) {
        ProviderAccessResult.InvalidInput(operation)
    } catch (_: RuntimeException) {
        ProviderAccessResult.Unavailable(operation)
    }

    private fun insertIncomingSerialized(
        message: IncomingSmsRecord,
    ): ProviderAccessResult<ProviderStoredMessage> {
        val providerContentDigest = IncomingSmsProviderContentDigest.fromContent(
            sender = message.sender.value,
            body = message.body,
        )
        var existingEntry = true
        var entry = when (val lookup = incomingReplayJournal.lookup(message.deliveryFingerprint)) {
            is IncomingSmsReplayJournal.LookupResult.Found -> lookup.entry
            IncomingSmsReplayJournal.LookupResult.Corrupt -> {
                return ProviderAccessResult.Unavailable("read incoming SMS delivery journal")
            }
            is IncomingSmsReplayJournal.LookupResult.Quarantined -> {
                return ProviderAccessResult.Unavailable("incoming SMS delivery is quarantined")
            }
            IncomingSmsReplayJournal.LookupResult.Missing -> {
                existingEntry = false
                val began = incomingReplayJournal.begin(
                    fingerprint = message.deliveryFingerprint,
                    receivedTimestampMillis = message.receivedTimestampMillis,
                    sentTimestampMillis = message.sentTimestampMillis,
                    subscriptionId = message.subscriptionId,
                    providerContentDigest = providerContentDigest,
                )
                if (!began) {
                    return ProviderAccessResult.Unavailable("begin incoming SMS delivery journal")
                }
                (incomingReplayJournal.lookup(message.deliveryFingerprint) as? IncomingSmsReplayJournal.LookupResult.Found)
                    ?.entry
                    ?: return ProviderAccessResult.Unavailable("verify incoming SMS delivery journal")
            }
        }
        if (entry.sentTimestampMillis != message.sentTimestampMillis ||
            entry.subscriptionId != message.subscriptionId ||
            (entry.providerContentDigest != null && entry.providerContentDigest != providerContentDigest)
        ) {
            return ProviderAccessResult.Unavailable("incoming SMS fingerprint metadata mismatch")
        }

        if (entry.state == IncomingSmsReplayJournal.State.COMPLETE) {
            return entry.storedMessage(IncomingDeliveryDisposition.COMPLETED_REPLAY)
        }
        if (entry.state == IncomingSmsReplayJournal.State.STORED) {
            val storedId = entry.providerId
                ?: return ProviderAccessResult.Unavailable("missing journaled SMS provider ID")
            when (val exact = providerReference(storedId.value)) {
                is ExactProviderReference.Found -> {
                    return ProviderAccessResult.Success(
                        exact.message.copy(
                            incomingDisposition = IncomingDeliveryDisposition.RECOVERED_UNACKNOWLEDGED,
                        ),
                    )
                }
                ExactProviderReference.Unavailable -> {
                    return ProviderAccessResult.Unavailable("verify journaled SMS provider row")
                }
                ExactProviderReference.Missing -> {
                    if (!incomingReplayJournal.resetPending(message.deliveryFingerprint)) {
                        return ProviderAccessResult.Unavailable("reset missing SMS provider row")
                    }
                    entry = (incomingReplayJournal.lookup(message.deliveryFingerprint)
                        as? IncomingSmsReplayJournal.LookupResult.Found)
                        ?.entry
                        ?: return ProviderAccessResult.Unavailable("verify reset SMS delivery journal")
                }
            }
        }

        if (existingEntry) {
            when (val recovery = recoverPendingProviderRow(message, entry)) {
                is PendingRecovery.Found -> {
                    if (
                        !incomingReplayJournal.markStored(
                            message.deliveryFingerprint,
                            recovery.message.providerId,
                            recovery.message.conversationId,
                        )
                    ) {
                        return ProviderAccessResult.Unavailable(
                            "checkpoint recovered incoming SMS provider row",
                        )
                    }
                    return ProviderAccessResult.Success(
                        recovery.message.copy(
                            incomingDisposition = IncomingDeliveryDisposition.RECOVERED_UNACKNOWLEDGED,
                        ),
                    )
                }
                PendingRecovery.None -> Unit
                PendingRecovery.Ambiguous -> {
                    return ProviderAccessResult.Unavailable("ambiguous incoming SMS provider recovery")
                }
                PendingRecovery.Unavailable -> {
                    return ProviderAccessResult.Unavailable("query incoming SMS provider recovery")
                }
            }
        }

        val effectiveReceivedTimestamp = entry.receivedTimestampMillis
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, message.sender.value)
            put(Telephony.Sms.BODY, message.body)
            put(Telephony.Sms.DATE, effectiveReceivedTimestamp)
            put(Telephony.Sms.DATE_SENT, message.sentTimestampMillis)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            message.subscriptionId?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it.value) }
            if (Build.VERSION.SDK_INT >= 37) {
                put(TRANSACTION_ID_COLUMN, transactionId(entry.providerRecoveryToken))
            }
        }
        val uri = resolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            ?: return ProviderAccessResult.Unavailable("insert incoming SMS")
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull()
            ?.takeIf { it > 0L }
            ?: return ProviderAccessResult.Unavailable("read inserted SMS ID")
        val stored = when (val reference = providerReference(id)) {
            is ExactProviderReference.Found -> reference.message.copy(
                incomingDisposition = IncomingDeliveryDisposition.NEWLY_INSERTED,
            )
            ExactProviderReference.Missing,
            ExactProviderReference.Unavailable
            -> return ProviderAccessResult.Unavailable("resolve inserted SMS conversation")
        }
        if (
            !incomingReplayJournal.markStored(
                message.deliveryFingerprint,
                stored.providerId,
                stored.conversationId,
            )
        ) {
            return ProviderAccessResult.Unavailable("checkpoint inserted incoming SMS provider row")
        }
        return ProviderAccessResult.Success(stored)
    }

    private fun recoverPendingNotificationProviderRow(
        entry: IncomingSmsReplayJournal.Entry,
    ): PendingRecovery {
        val expectedDigest = entry.providerContentDigest ?: return PendingRecovery.Unavailable
        val subscriptionClause = if (entry.subscriptionId == null) {
            "(${Telephony.Sms.SUBSCRIPTION_ID} IS NULL OR ${Telephony.Sms.SUBSCRIPTION_ID} < 0)"
        } else {
            "${Telephony.Sms.SUBSCRIPTION_ID} = ?"
        }
        val selection = "${Telephony.Sms.TYPE} = ? AND ${Telephony.Sms.DATE} = ? AND " +
            "${Telephony.Sms.DATE_SENT} = ? AND $subscriptionClause"
        val selectionArgs = mutableListOf(
            Telephony.Sms.MESSAGE_TYPE_INBOX.toString(),
            entry.receivedTimestampMillis.toString(),
            entry.sentTimestampMillis.toString(),
        ).apply {
            entry.subscriptionId?.let { add(it.value.toString()) }
        }.toTypedArray()
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${BaseColumns._ID} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_RECOVERY_CANDIDATES + 1)
        }
        val cursor = resolver.query(
            Telephony.Sms.CONTENT_URI,
            INCOMING_REPLAY_PROJECTION,
            queryArgs,
            null,
        ) ?: return PendingRecovery.Unavailable
        var candidateOverflow = false
        val candidates = cursor.use {
            val rows = ArrayList<ProviderStoredMessage>(MAX_RECOVERY_CANDIDATES + 1)
            val indices = IncomingReplayProjectionIndices.from(it)
            var scannedRows = 0
            while (it.moveToNext()) {
                if (scannedRows == MAX_RECOVERY_CANDIDATES) {
                    candidateOverflow = true
                    break
                }
                scannedRows += 1
                indices.read(it).toPendingRecoveryCandidateOrNull(
                    expectedReceivedTimestampMillis = entry.receivedTimestampMillis,
                    expectedSentTimestampMillis = entry.sentTimestampMillis,
                    expectedSubscriptionId = entry.subscriptionId,
                    expectedProviderContentDigest = expectedDigest,
                )?.let(rows::add)
            }
            rows
        }
        if (candidateOverflow) return PendingRecovery.Ambiguous
        val claimed = incomingReplayJournal.claimedProviderIds(entry.fingerprint)
            ?: return PendingRecovery.Unavailable
        return when (val selectionResult = IncomingProviderRecoveryPolicy.select(candidates, claimed)) {
            IncomingProviderRecoveryPolicy.Result.None -> PendingRecovery.None
            IncomingProviderRecoveryPolicy.Result.Ambiguous -> PendingRecovery.Ambiguous
            is IncomingProviderRecoveryPolicy.Result.Found -> PendingRecovery.Found(selectionResult.message)
        }
    }

    private fun recoverPendingProviderRow(
        message: IncomingSmsRecord,
        entry: IncomingSmsReplayJournal.Entry,
    ): PendingRecovery {
        val selection: String
        val selectionArgs: Array<String>
        if (Build.VERSION.SDK_INT >= 37) {
            selection = "$TRANSACTION_ID_COLUMN = ?"
            selectionArgs = arrayOf(transactionId(entry.providerRecoveryToken))
        } else {
            val subscriptionClause = if (entry.subscriptionId == null) {
                "(${Telephony.Sms.SUBSCRIPTION_ID} IS NULL OR ${Telephony.Sms.SUBSCRIPTION_ID} < 0)"
            } else {
                "${Telephony.Sms.SUBSCRIPTION_ID} = ?"
            }
            selection = "${Telephony.Sms.TYPE} = ? AND ${Telephony.Sms.ADDRESS} = ? AND " +
                "${Telephony.Sms.BODY} = ? AND ${Telephony.Sms.DATE} = ? AND " +
                "${Telephony.Sms.DATE_SENT} = ? AND $subscriptionClause"
            selectionArgs = mutableListOf(
                Telephony.Sms.MESSAGE_TYPE_INBOX.toString(),
                message.sender.value,
                message.body,
                entry.receivedTimestampMillis.toString(),
                entry.sentTimestampMillis.toString(),
            ).apply {
                entry.subscriptionId?.let { add(it.value.toString()) }
            }.toTypedArray()
        }
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${BaseColumns._ID} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_RECOVERY_CANDIDATES + 1)
        }
        val cursor = resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(BaseColumns._ID, Telephony.Sms.THREAD_ID),
            queryArgs,
            null,
        ) ?: return PendingRecovery.Unavailable
        var candidateOverflow = false
        val candidates = cursor.use {
            val rows = ArrayList<ProviderStoredMessage>(MAX_RECOVERY_CANDIDATES + 1)
            val idIndex = it.getColumnIndexOrThrow(BaseColumns._ID)
            val threadIndex = it.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            var scannedRows = 0
            while (it.moveToNext()) {
                if (scannedRows == MAX_RECOVERY_CANDIDATES) {
                    candidateOverflow = true
                    break
                }
                scannedRows += 1
                val providerId = it.getLong(idIndex)
                val conversationId = it.getLong(threadIndex)
                if (providerId > 0L && conversationId > 0L) {
                    rows += ProviderStoredMessage(
                        providerId = ProviderMessageId(ProviderKind.SMS, providerId),
                        conversationId = ConversationId(conversationId),
                    )
                }
            }
            rows
        }
        if (candidateOverflow) return PendingRecovery.Ambiguous
        val claimed = incomingReplayJournal.claimedProviderIds(message.deliveryFingerprint)
            ?: return PendingRecovery.Unavailable
        return when (val selectionResult = IncomingProviderRecoveryPolicy.select(candidates, claimed)) {
            IncomingProviderRecoveryPolicy.Result.None -> PendingRecovery.None
            IncomingProviderRecoveryPolicy.Result.Ambiguous -> PendingRecovery.Ambiguous
            is IncomingProviderRecoveryPolicy.Result.Found -> PendingRecovery.Found(selectionResult.message)
        }
    }

    private fun providerReference(providerId: Long): ExactProviderReference {
        val uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, providerId)
        val cursor = resolver.query(
            uri,
            arrayOf(Telephony.Sms.THREAD_ID),
            null,
            null,
            null,
        ) ?: return ExactProviderReference.Unavailable
        return cursor.use {
            if (!it.moveToFirst()) return@use ExactProviderReference.Missing
            val threadId = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID))
            if (threadId <= 0L) return@use ExactProviderReference.Missing
            ExactProviderReference.Found(
                ProviderStoredMessage(
                    providerId = ProviderMessageId(ProviderKind.SMS, providerId),
                    conversationId = ConversationId(threadId),
                ),
            )
        }
    }

    private fun incomingNotificationReplayRow(
        providerId: ProviderMessageId,
    ): ExactIncomingNotificationReplayRow {
        if (providerId.kind != ProviderKind.SMS) return ExactIncomingNotificationReplayRow.Invalid
        val cursor = resolver.query(
            ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, providerId.value),
            INCOMING_REPLAY_PROJECTION,
            null,
            null,
            null,
        ) ?: return ExactIncomingNotificationReplayRow.Unavailable
        return cursor.use {
            if (!it.moveToFirst()) return@use ExactIncomingNotificationReplayRow.Missing
            val row = IncomingReplayProjectionIndices.from(it).read(it)
            if (it.moveToNext()) {
                ExactIncomingNotificationReplayRow.Invalid
            } else {
                ExactIncomingNotificationReplayRow.Found(row)
            }
        }
    }

    private fun quarantineTerminalIncomingReplayFailure(
        entry: IncomingSmsReplayJournal.Entry,
        failure: IncomingStoredReplayRowFailure,
    ): Boolean {
        val reason = incomingStoredReplayQuarantineReason(failure) ?: return false
        return incomingReplayJournal.quarantine(entry.fingerprint, reason)
    }

    private fun insertedReference(
        providerId: Long,
        incomingDisposition: IncomingDeliveryDisposition?,
    ): ProviderAccessResult<ProviderStoredMessage> = when (val reference = providerReference(providerId)) {
        is ExactProviderReference.Found -> ProviderAccessResult.Success(
            reference.message.copy(incomingDisposition = incomingDisposition),
        )
        ExactProviderReference.Missing,
        ExactProviderReference.Unavailable
        -> ProviderAccessResult.Unavailable("resolve inserted SMS conversation")
    }

    private fun IncomingSmsReplayJournal.Entry.storedMessage(
        disposition: IncomingDeliveryDisposition,
    ): ProviderAccessResult<ProviderStoredMessage> {
        val provider = providerId ?: return ProviderAccessResult.Unavailable("missing journaled SMS provider ID")
        val conversation = conversationId
            ?: return ProviderAccessResult.Unavailable("missing journaled SMS conversation ID")
        return ProviderAccessResult.Success(
            ProviderStoredMessage(provider, conversation, disposition),
        )
    }

    private sealed interface ExactProviderReference {
        data object Missing : ExactProviderReference
        data object Unavailable : ExactProviderReference
        data class Found(val message: ProviderStoredMessage) : ExactProviderReference
    }

    private sealed interface ExactIncomingNotificationReplayRow {
        data object Invalid : ExactIncomingNotificationReplayRow
        data object Missing : ExactIncomingNotificationReplayRow
        data object Unavailable : ExactIncomingNotificationReplayRow
        data class Found(val row: RawIncomingSmsNotificationReplayRow) : ExactIncomingNotificationReplayRow
    }

    private sealed interface PendingRecovery {
        data object None : PendingRecovery
        data object Ambiguous : PendingRecovery
        data object Unavailable : PendingRecovery
        data class Found(val message: ProviderStoredMessage) : PendingRecovery
    }

    companion object {
        private const val TRANSACTION_ID_COLUMN = "tr_id"
        private const val TRANSACTION_ID_PREFIX = "aurora-sms-delivery-v1:"
        private const val MAX_RECOVERY_CANDIDATES = 8
        private const val MAX_STATUS_UPDATE_ATTEMPTS = 4
        private val INCOMING_REPLAY_PROJECTION = arrayOf(
            BaseColumns._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.TYPE,
            Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT,
            Telephony.Sms.SUBSCRIPTION_ID,
        )

        private fun transactionId(providerRecoveryToken: String): String =
            TRANSACTION_ID_PREFIX + providerRecoveryToken
    }
}

internal fun canVerifyPendingIncomingProviderOwnership(
    providerContentDigest: IncomingSmsProviderContentDigest?,
): Boolean = providerContentDigest != null

internal fun pendingIncomingNotificationReadResult(
    replays: List<IncomingSmsNotificationReplay>,
    hasUnresolvedPendingEntry: Boolean,
): ProviderAccessResult<List<IncomingSmsNotificationReplay>> = when {
    replays.isNotEmpty() -> ProviderAccessResult.Success(replays)
    hasUnresolvedPendingEntry -> ProviderAccessResult.Unavailable(
        "resolve pending incoming SMS provider row",
    )
    else -> ProviderAccessResult.Success(emptyList())
}

internal enum class IncomingStoredReplayRowFailure {
    MISSING,
    INVALID,
    VALIDATION_MISMATCH,
    UNAVAILABLE,
}

internal fun incomingStoredReplayQuarantineReason(
    failure: IncomingStoredReplayRowFailure,
): IncomingSmsReplayJournal.QuarantineReason? = when (failure) {
    IncomingStoredReplayRowFailure.MISSING ->
        IncomingSmsReplayJournal.QuarantineReason.PROVIDER_ROW_MISSING
    IncomingStoredReplayRowFailure.INVALID ->
        IncomingSmsReplayJournal.QuarantineReason.PROVIDER_ROW_INVALID
    IncomingStoredReplayRowFailure.VALIDATION_MISMATCH ->
        IncomingSmsReplayJournal.QuarantineReason.PROVIDER_ROW_MISMATCH
    IncomingStoredReplayRowFailure.UNAVAILABLE -> null
}

internal data class SmsProviderStatusProjection(
    val messageType: Int,
    val rawStatus: Int,
)

internal fun SmsProviderStatus.toSmsProviderStatusProjection(): SmsProviderStatusProjection = when (this) {
    SmsProviderStatus.COMPLETE -> SmsProviderStatusProjection(
        messageType = Telephony.Sms.MESSAGE_TYPE_SENT,
        rawStatus = Telephony.Sms.STATUS_COMPLETE,
    )
    SmsProviderStatus.DELIVERY_FAILED -> SmsProviderStatusProjection(
        messageType = Telephony.Sms.MESSAGE_TYPE_SENT,
        rawStatus = Telephony.Sms.STATUS_FAILED,
    )
    SmsProviderStatus.FAILED -> SmsProviderStatusProjection(
        messageType = Telephony.Sms.MESSAGE_TYPE_FAILED,
        rawStatus = Telephony.Sms.STATUS_FAILED,
    )
    SmsProviderStatus.PENDING -> SmsProviderStatusProjection(
        messageType = Telephony.Sms.MESSAGE_TYPE_OUTBOX,
        rawStatus = Telephony.Sms.STATUS_PENDING,
    )
}

internal fun smsProviderStatusFromRaw(
    messageType: Int,
    rawStatus: Int,
): SmsProviderStatus? = when {
    messageType == Telephony.Sms.MESSAGE_TYPE_OUTBOX && rawStatus == Telephony.Sms.STATUS_PENDING -> {
        SmsProviderStatus.PENDING
    }
    messageType == Telephony.Sms.MESSAGE_TYPE_SENT && rawStatus == Telephony.Sms.STATUS_COMPLETE -> {
        SmsProviderStatus.COMPLETE
    }
    messageType == Telephony.Sms.MESSAGE_TYPE_SENT && rawStatus == Telephony.Sms.STATUS_FAILED -> {
        SmsProviderStatus.DELIVERY_FAILED
    }
    messageType == Telephony.Sms.MESSAGE_TYPE_FAILED && rawStatus == Telephony.Sms.STATUS_FAILED -> {
        SmsProviderStatus.FAILED
    }
    else -> null
}

internal object IncomingProviderRecoveryPolicy {
    fun select(
        candidates: List<ProviderStoredMessage>,
        claimedProviderIds: Set<Long>,
    ): Result {
        val unclaimed = candidates.filterNot { it.providerId.value in claimedProviderIds }
        return when (unclaimed.size) {
            0 -> Result.None
            1 -> Result.Found(unclaimed.single())
            else -> Result.Ambiguous
        }
    }

    sealed interface Result {
        data object None : Result
        data object Ambiguous : Result
        data class Found(val message: ProviderStoredMessage) : Result
    }
}

private fun android.database.Cursor.nullableLong(index: Int): Long? =
    if (isNull(index)) null else getLong(index)

private fun android.database.Cursor.nullableInt(index: Int): Int? =
    if (isNull(index)) null else getInt(index)

internal data class RawIncomingSmsNotificationReplayRow(
    val providerId: Long?,
    val providerThreadId: Long?,
    val sender: String?,
    val body: String?,
    val messageType: Int?,
    val receivedTimestampMillis: Long?,
    val sentTimestampMillis: Long?,
    val subscriptionId: Int?,
) {
    override fun toString(): String = "RawIncomingSmsNotificationReplayRow(REDACTED)"
}

private data class IncomingReplayProjectionIndices(
    val providerId: Int,
    val providerThreadId: Int,
    val sender: Int,
    val body: Int,
    val messageType: Int,
    val receivedTimestampMillis: Int,
    val sentTimestampMillis: Int,
    val subscriptionId: Int,
) {
    fun read(cursor: android.database.Cursor): RawIncomingSmsNotificationReplayRow =
        RawIncomingSmsNotificationReplayRow(
            providerId = cursor.nullableLong(providerId),
            providerThreadId = cursor.nullableLong(providerThreadId),
            sender = if (cursor.isNull(sender)) null else cursor.getString(sender),
            body = if (cursor.isNull(body)) null else cursor.getString(body),
            messageType = cursor.nullableInt(messageType),
            receivedTimestampMillis = cursor.nullableLong(receivedTimestampMillis),
            sentTimestampMillis = cursor.nullableLong(sentTimestampMillis),
            subscriptionId = cursor.nullableInt(subscriptionId),
        )

    companion object {
        fun from(cursor: android.database.Cursor): IncomingReplayProjectionIndices =
            IncomingReplayProjectionIndices(
                providerId = cursor.getColumnIndexOrThrow(BaseColumns._ID),
                providerThreadId = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID),
                sender = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS),
                body = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY),
                messageType = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE),
                receivedTimestampMillis = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE),
                sentTimestampMillis = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT),
                subscriptionId = cursor.getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID),
            )
    }
}

internal fun RawIncomingSmsNotificationReplayRow.toPendingRecoveryCandidateOrNull(
    expectedReceivedTimestampMillis: Long,
    expectedSentTimestampMillis: Long,
    expectedSubscriptionId: AuroraSubscriptionId?,
    expectedProviderContentDigest: IncomingSmsProviderContentDigest,
): ProviderStoredMessage? {
    val id = providerId?.takeIf { it > 0L } ?: return null
    val threadId = providerThreadId?.takeIf { it > 0L } ?: return null
    if (messageType != Telephony.Sms.MESSAGE_TYPE_INBOX) return null
    if (receivedTimestampMillis != expectedReceivedTimestampMillis) return null
    if (sentTimestampMillis != expectedSentTimestampMillis) return null
    val normalizedSubscriptionId = when {
        subscriptionId == null || subscriptionId == NO_PROVIDER_SUBSCRIPTION -> null
        subscriptionId >= 0 -> AuroraSubscriptionId(subscriptionId)
        else -> return null
    }
    if (normalizedSubscriptionId != expectedSubscriptionId) return null
    val boundedBody = body
        ?.takeIf { it.length <= IncomingSmsRecord.MAX_SMS_BODY_CHARACTERS }
        ?: return null
    val participant = try {
        ParticipantAddress(sender ?: return null)
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (
        IncomingSmsProviderContentDigest.fromContent(participant.value, boundedBody) !=
        expectedProviderContentDigest
    ) {
        return null
    }
    return ProviderStoredMessage(
        providerId = ProviderMessageId(ProviderKind.SMS, id),
        conversationId = ConversationId(threadId),
    )
}

internal fun RawIncomingSmsNotificationReplayRow.toIncomingSmsNotificationReplayOrNull(
    fingerprint: MessageDeliveryFingerprint,
    expectedProviderId: ProviderMessageId,
    expectedConversationId: ConversationId,
    expectedReceivedTimestampMillis: Long,
    expectedSentTimestampMillis: Long,
    expectedSubscriptionId: AuroraSubscriptionId?,
    expectedProviderContentDigest: IncomingSmsProviderContentDigest? = null,
): IncomingSmsNotificationReplay? {
    if (expectedProviderId.kind != ProviderKind.SMS || providerId != expectedProviderId.value) return null
    if (providerThreadId != expectedConversationId.value) return null
    if (messageType != Telephony.Sms.MESSAGE_TYPE_INBOX) return null
    if (receivedTimestampMillis != expectedReceivedTimestampMillis) return null
    if (sentTimestampMillis != expectedSentTimestampMillis) return null
    val normalizedSubscriptionId = when {
        subscriptionId == null || subscriptionId == NO_PROVIDER_SUBSCRIPTION -> null
        subscriptionId >= 0 -> AuroraSubscriptionId(subscriptionId)
        else -> return null
    }
    if (normalizedSubscriptionId != expectedSubscriptionId) return null
    val boundedBody = body
        ?.takeIf { it.length <= IncomingSmsRecord.MAX_SMS_BODY_CHARACTERS }
        ?: return null
    val participant = try {
        ParticipantAddress(sender ?: return null)
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (
        expectedProviderContentDigest != null &&
        IncomingSmsProviderContentDigest.fromContent(participant.value, boundedBody) !=
        expectedProviderContentDigest
    ) {
        return null
    }
    return IncomingSmsNotificationReplay(
        deliveryFingerprint = fingerprint,
        providerId = expectedProviderId,
        conversationId = expectedConversationId,
        sender = participant,
        body = boundedBody,
        receivedTimestampMillis = expectedReceivedTimestampMillis,
        sentTimestampMillis = expectedSentTimestampMillis,
        subscriptionId = expectedSubscriptionId,
    )
}

private const val NO_PROVIDER_SUBSCRIPTION = -1

private data class RawSmsProviderRow(
    val id: Long,
    val threadId: Long,
    val address: String?,
    val body: String?,
    val type: Int,
    val timestampMillis: Long,
    val sentTimestampMillis: Long?,
    val subscriptionId: Int?,
    val read: Boolean,
    val seen: Boolean,
    val locked: Boolean,
    val rawStatus: Int?,
    val rawErrorCode: Int?,
) {
    fun pageCursor(): ProviderPageCursor = ProviderPageCursor(
        timestampMillis = timestampMillis,
        providerRowId = id,
    )

    fun toProviderMessageOrNull(): SmsProviderMessage? {
        if (id <= 0L || threadId <= 0L || timestampMillis < 0L) return null
        val providerId = ProviderMessageId(ProviderKind.SMS, id)
        val providerThreadId = ProviderThreadId(threadId)
        val sender = address
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { raw ->
                try {
                    ParticipantAddress(raw)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        val boundedBody = body.orEmpty().take(SmsProviderMessage.MAX_PROVIDER_BODY_CHARACTERS)
        val messageBox = type.toSmsMessageBox()
        val messageStatus = rawStatus.toSmsMessageStatus(messageBox)
        val normalizedSentTimestamp = sentTimestampMillis?.takeIf { it >= 0L }
        val normalizedSubscription = subscriptionId
            ?.takeIf { it >= 0 }
            ?.let(::AuroraSubscriptionId)
        val fingerprint = ProviderProjectionFingerprint.sms(
            providerId = providerId,
            providerThreadId = providerThreadId,
            sender = sender,
            body = boundedBody,
            box = messageBox,
            status = messageStatus,
            rawStatus = rawStatus,
            rawErrorCode = rawErrorCode,
            timestampMillis = timestampMillis,
            sentTimestampMillis = normalizedSentTimestamp,
            subscriptionId = normalizedSubscription,
            read = read,
            seen = seen,
            locked = locked,
        )
        return SmsProviderMessage(
            id = providerId,
            providerThreadId = providerThreadId,
            sender = sender,
            body = boundedBody,
            direction = messageBox.toDirection(),
            box = messageBox,
            status = messageStatus,
            rawStatus = rawStatus,
            rawErrorCode = rawErrorCode,
            timestampMillis = timestampMillis,
            sentTimestampMillis = normalizedSentTimestamp,
            subscriptionId = normalizedSubscription,
            read = read,
            seen = seen,
            locked = locked,
            syncFingerprint = fingerprint,
        )
    }
}

private fun Int.toSmsMessageBox(): MessageBox = when (this) {
    Telephony.Sms.MESSAGE_TYPE_INBOX -> MessageBox.INBOX
    Telephony.Sms.MESSAGE_TYPE_SENT -> MessageBox.SENT
    Telephony.Sms.MESSAGE_TYPE_DRAFT -> MessageBox.DRAFT
    Telephony.Sms.MESSAGE_TYPE_OUTBOX -> MessageBox.OUTBOX
    Telephony.Sms.MESSAGE_TYPE_FAILED -> MessageBox.FAILED
    Telephony.Sms.MESSAGE_TYPE_QUEUED -> MessageBox.QUEUED
    else -> MessageBox.UNKNOWN
}

private fun Int?.toSmsMessageStatus(box: MessageBox): MessageStatus = when (this) {
    Telephony.Sms.STATUS_NONE -> MessageStatus.NONE
    Telephony.Sms.STATUS_PENDING -> MessageStatus.PENDING
    Telephony.Sms.STATUS_COMPLETE -> MessageStatus.COMPLETE
    Telephony.Sms.STATUS_FAILED -> MessageStatus.FAILED
    else -> when (box) {
        MessageBox.SENT -> MessageStatus.COMPLETE
        MessageBox.OUTBOX,
        MessageBox.QUEUED,
        -> MessageStatus.PENDING
        MessageBox.FAILED -> MessageStatus.FAILED
        MessageBox.DRAFT,
        MessageBox.INBOX,
        -> MessageStatus.NONE
        MessageBox.UNKNOWN -> MessageStatus.UNKNOWN
    }
}

private fun MessageBox.toDirection(): MessageDirection =
    if (this == MessageBox.INBOX) MessageDirection.INCOMING else MessageDirection.OUTGOING
