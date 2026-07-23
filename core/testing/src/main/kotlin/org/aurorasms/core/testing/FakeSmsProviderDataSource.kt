// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.testing

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageDeliveryFingerprint
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.MessageSyncFingerprint
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.telephony.IncomingSmsRecord
import org.aurorasms.core.telephony.ConversationReadThroughOutcome
import org.aurorasms.core.telephony.IncomingDeliveryDisposition
import org.aurorasms.core.telephony.IncomingSmsNotificationReplay
import org.aurorasms.core.telephony.IncomingSmsNotificationReplayRequest
import org.aurorasms.core.telephony.OutgoingSmsRecord
import org.aurorasms.core.telephony.OutgoingSmsRollbackOutcome
import org.aurorasms.core.telephony.OutgoingSmsStatusUpdateOutcome
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderPage
import org.aurorasms.core.telephony.ProviderPageCursor
import org.aurorasms.core.telephony.ProviderPageRequest
import org.aurorasms.core.telephony.ProviderStoredMessage
import org.aurorasms.core.telephony.SmsProviderDataSource
import org.aurorasms.core.telephony.SmsProviderMessage
import org.aurorasms.core.telephony.SmsProviderStatus

class FakeSmsProviderDataSource(
    seed: Iterable<SmsProviderMessage> = emptyList(),
) : SmsProviderDataSource {
    private val lock = Any()
    private val messages = seed.toMutableList()
    private var nextProviderId = (messages.maxOfOrNull { it.id.value } ?: 0L) + 1L
    private var nextConversationId = maxOf(
        10_000L,
        (messages.maxOfOrNull { it.providerThreadId.value } ?: 0L) + 1L,
    )
    private val conversationIds = linkedMapOf<String, ConversationId>().apply {
        messages.forEach { message ->
            message.sender?.let { sender ->
                putIfAbsent(sender.value, ConversationId(message.providerThreadId.value))
            }
        }
    }
    private val incomingDeliveries = linkedMapOf<MessageDeliveryFingerprint, FakeIncomingDelivery>()
    private val appOwnedOutgoingIds = linkedSetOf<ProviderMessageId>()

    var failure: ProviderAccessResult<Nothing>? = null
    val insertedIncoming = mutableListOf<IncomingSmsRecord>()
    val insertedOutgoing = mutableListOf<OutgoingSmsRecord>()
    val armedOutgoing = mutableListOf<ProviderMessageId>()
    val updatedStatuses = linkedMapOf<ProviderMessageId, SmsProviderStatus>()
    val markedReadThrough = mutableListOf<Pair<ConversationId, ProviderMessageId>>()

    override suspend fun count(): ProviderAccessResult<Long> = synchronized(lock) {
        failure ?: ProviderAccessResult.Success(messages.size.toLong())
    }

    override suspend fun readPage(
        request: ProviderPageRequest,
    ): ProviderAccessResult<ProviderPage<SmsProviderMessage>> = synchronized(lock) {
        failure ?: ProviderAccessResult.Success(page(request))
    }

    override suspend fun insertIncoming(
        message: IncomingSmsRecord,
    ): ProviderAccessResult<ProviderStoredMessage> = synchronized(lock) {
        failure ?: incomingDeliveries[message.deliveryFingerprint]?.let { delivery ->
            ProviderAccessResult.Success(
                delivery.stored.copy(
                    incomingDisposition = if (delivery.complete) {
                        IncomingDeliveryDisposition.COMPLETED_REPLAY
                    } else {
                        IncomingDeliveryDisposition.RECOVERED_UNACKNOWLEDGED
                    },
                ),
            )
        } ?: run {
            insertedIncoming += message
            val id = ProviderMessageId(ProviderKind.SMS, nextProviderId++)
            val conversationId = conversationIdFor(message.sender)
            messages += SmsProviderMessage(
                id = id,
                providerThreadId = ProviderThreadId(conversationId.value),
                sender = message.sender,
                body = message.body,
                direction = MessageDirection.INCOMING,
                box = MessageBox.INBOX,
                status = MessageStatus.NONE,
                rawStatus = null,
                rawErrorCode = null,
                timestampMillis = message.receivedTimestampMillis,
                sentTimestampMillis = message.sentTimestampMillis,
                subscriptionId = message.subscriptionId,
                read = false,
                seen = false,
                locked = false,
                syncFingerprint = fakeSyncFingerprint(
                    id.value,
                    conversationId.value,
                    message.sender.value,
                    message.body,
                    message.receivedTimestampMillis,
                ),
            )
            val stored = ProviderStoredMessage(
                providerId = id,
                conversationId = conversationId,
                incomingDisposition = IncomingDeliveryDisposition.NEWLY_INSERTED,
            )
            incomingDeliveries[message.deliveryFingerprint] = FakeIncomingDelivery(stored)
            ProviderAccessResult.Success(stored)
        }
    }

    override suspend fun readPendingIncomingNotifications(
        request: IncomingSmsNotificationReplayRequest,
    ): ProviderAccessResult<List<IncomingSmsNotificationReplay>> = synchronized(lock) {
        failure ?: run {
            val replays = ArrayList<IncomingSmsNotificationReplay>(request.limit)
            for ((fingerprint, delivery) in incomingDeliveries) {
                if (delivery.complete) continue
                val stored = delivery.stored
                val row = messages.singleOrNull { it.id == stored.providerId }
                    ?: return@synchronized ProviderAccessResult.Unavailable(
                        "read journaled incoming SMS provider row",
                    )
                val sender = row.sender
                    ?: return@synchronized ProviderAccessResult.Unavailable(
                        "validate journaled incoming SMS provider row",
                    )
                val sentTimestampMillis = row.sentTimestampMillis
                    ?: return@synchronized ProviderAccessResult.Unavailable(
                        "validate journaled incoming SMS provider row",
                    )
                if (
                    row.providerThreadId.value != stored.conversationId.value ||
                    row.direction != MessageDirection.INCOMING ||
                    row.box != MessageBox.INBOX
                ) {
                    return@synchronized ProviderAccessResult.Unavailable(
                        "validate journaled incoming SMS provider row",
                    )
                }
                replays += IncomingSmsNotificationReplay(
                    deliveryFingerprint = fingerprint,
                    providerId = stored.providerId,
                    conversationId = stored.conversationId,
                    sender = sender,
                    body = row.body,
                    receivedTimestampMillis = row.timestampMillis,
                    sentTimestampMillis = sentTimestampMillis,
                    subscriptionId = row.subscriptionId,
                )
                if (replays.size == request.limit) break
            }
            ProviderAccessResult.Success(replays)
        }
    }

    override suspend fun markConversationReadThrough(
        conversationId: ConversationId,
        throughMessageId: ProviderMessageId,
    ): ProviderAccessResult<ConversationReadThroughOutcome> = synchronized(lock) {
        failure ?: run {
            if (throughMessageId.kind != ProviderKind.SMS) {
                return@synchronized ProviderAccessResult.InvalidInput("provider message kind")
            }
            val source = messages.singleOrNull { it.id == throughMessageId }
            if (
                source == null ||
                source.providerThreadId.value != conversationId.value ||
                source.direction != MessageDirection.INCOMING ||
                source.box != MessageBox.INBOX
            ) {
                return@synchronized ProviderAccessResult.Success(
                    ConversationReadThroughOutcome.SOURCE_ABSENT_OR_MISMATCH,
                )
            }
            markedReadThrough += conversationId to throughMessageId
            messages.replaceAll { message ->
                if (
                    message.providerThreadId.value == conversationId.value &&
                    message.direction == MessageDirection.INCOMING &&
                    message.box == MessageBox.INBOX &&
                    message.id.kind == ProviderKind.SMS &&
                    message.id.value <= throughMessageId.value
                ) {
                    message.copy(read = true, seen = true)
                } else {
                    message
                }
            }
            ProviderAccessResult.Success(ConversationReadThroughOutcome.APPLIED_OR_ALREADY_READ)
        }
    }

    override suspend fun markIncomingHandled(
        deliveryFingerprint: MessageDeliveryFingerprint,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
    ): ProviderAccessResult<Unit> = synchronized(lock) {
        failure ?: incomingDeliveries[deliveryFingerprint]
            ?.takeIf { it.stored.providerId == providerId && it.stored.conversationId == conversationId }
            ?.let {
                it.complete = true
                ProviderAccessResult.Success(Unit)
            }
            ?: ProviderAccessResult.InvalidInput("delivery fingerprint")
    }

    override suspend fun insertOutgoing(
        message: OutgoingSmsRecord,
    ): ProviderAccessResult<ProviderStoredMessage> = synchronized(lock) {
        failure ?: run {
            insertedOutgoing += message
            val id = ProviderMessageId(ProviderKind.SMS, nextProviderId++)
            appOwnedOutgoingIds += id
            val conversationId = conversationIdFor(message.recipient)
            messages += SmsProviderMessage(
                id = id,
                providerThreadId = ProviderThreadId(conversationId.value),
                sender = message.recipient,
                body = message.body,
                direction = MessageDirection.OUTGOING,
                box = MessageBox.FAILED,
                status = MessageStatus.FAILED,
                rawStatus = RAW_STATUS_FAILED,
                rawErrorCode = OUTGOING_STAGING_ERROR_CODE,
                timestampMillis = message.timestampMillis,
                sentTimestampMillis = null,
                subscriptionId = message.subscriptionId,
                read = true,
                seen = true,
                locked = false,
                syncFingerprint = fakeSyncFingerprint(
                    id.value,
                    conversationId.value,
                    message.recipient.value,
                    message.body,
                    message.timestampMillis,
                ),
            )
            ProviderAccessResult.Success(ProviderStoredMessage(id, conversationId))
        }
    }

    override suspend fun armOutgoing(
        id: ProviderMessageId,
    ): ProviderAccessResult<Unit> = synchronized(lock) {
        if (id.kind != ProviderKind.SMS) {
            return@synchronized ProviderAccessResult.InvalidInput("provider message kind")
        }
        val index = messages.indexOfFirst { it.id == id }
        failure ?: if (index >= 0) {
            val current = messages[index]
            if (
                id !in appOwnedOutgoingIds ||
                current.smsProviderStatusOrNull() != SmsProviderStatus.FAILED ||
                current.rawErrorCode != OUTGOING_STAGING_ERROR_CODE
            ) {
                return@synchronized ProviderAccessResult.Unavailable("arm outgoing SMS")
            }
            val box = MessageBox.OUTBOX
            val status = MessageStatus.PENDING
            messages[index] = current.copy(
                box = box,
                status = status,
                rawStatus = RAW_STATUS_PENDING,
                rawErrorCode = CLEARED_OUTGOING_ERROR_CODE,
                syncFingerprint = fakeSyncFingerprint(
                    current.id.value,
                    current.providerThreadId.value,
                    current.sender?.value,
                    current.body,
                    current.timestampMillis,
                    box.toStorageCode(),
                    status.toStorageCode(),
                    RAW_STATUS_PENDING,
                ),
            )
            armedOutgoing += id
            ProviderAccessResult.Success(Unit)
        } else {
            ProviderAccessResult.Unavailable("arm outgoing SMS")
        }
    }

    override suspend fun rollbackOutgoing(
        id: ProviderMessageId,
        conversationId: ConversationId,
    ): ProviderAccessResult<OutgoingSmsRollbackOutcome> = synchronized(lock) {
        if (id.kind != ProviderKind.SMS || conversationId.value <= 0L) {
            return@synchronized ProviderAccessResult.InvalidInput("provider message kind")
        }
        val index = messages.indexOfFirst { it.id == id }
        failure ?: if (index >= 0) {
            val current = messages[index]
            if (
                id !in appOwnedOutgoingIds ||
                current.providerThreadId.value != conversationId.value
            ) {
                return@synchronized ProviderAccessResult.Success(
                    OutgoingSmsRollbackOutcome.OWNERSHIP_CONFLICT,
                )
            }
            val currentStatus = current.smsProviderStatusOrNull()
            val ownedSubmissionState =
                (currentStatus == SmsProviderStatus.FAILED &&
                    current.rawErrorCode == OUTGOING_STAGING_ERROR_CODE) ||
                    (currentStatus == SmsProviderStatus.PENDING &&
                        current.rawErrorCode == CLEARED_OUTGOING_ERROR_CODE) ||
                    (currentStatus == SmsProviderStatus.FAILED &&
                        current.rawErrorCode == CLEARED_OUTGOING_ERROR_CODE)
            if (!ownedSubmissionState) {
                return@synchronized ProviderAccessResult.Success(
                    OutgoingSmsRollbackOutcome.OWNERSHIP_CONFLICT,
                )
            }
            val box = MessageBox.FAILED
            val status = MessageStatus.FAILED
            messages[index] = current.copy(
                box = box,
                status = status,
                rawStatus = RAW_STATUS_FAILED,
                rawErrorCode = CLEARED_OUTGOING_ERROR_CODE,
                syncFingerprint = fakeSyncFingerprint(
                    current.id.value,
                    current.providerThreadId.value,
                    current.sender?.value,
                    current.body,
                    current.timestampMillis,
                    box.toStorageCode(),
                    status.toStorageCode(),
                    RAW_STATUS_FAILED,
                ),
            )
            ProviderAccessResult.Success(OutgoingSmsRollbackOutcome.TERMINALIZED)
        } else {
            ProviderAccessResult.Success(OutgoingSmsRollbackOutcome.ROW_ABSENT)
        }
    }

    override suspend fun updateStatus(
        id: ProviderMessageId,
        status: SmsProviderStatus,
    ): ProviderAccessResult<Unit> = synchronized(lock) {
        val index = messages.indexOfFirst { it.id == id }
        failure ?: if (index >= 0) {
            if (applyMonotonicStatus(index, status)) {
                ProviderAccessResult.Success(Unit)
            } else {
                ProviderAccessResult.Unavailable("update SMS status")
            }
        } else {
            ProviderAccessResult.InvalidInput("id")
        }
    }

    override suspend fun updateOutgoingStatus(
        id: ProviderMessageId,
        conversationId: ConversationId,
        status: SmsProviderStatus,
    ): ProviderAccessResult<OutgoingSmsStatusUpdateOutcome> = synchronized(lock) {
        if (id.kind != ProviderKind.SMS || conversationId.value <= 0L) {
            return@synchronized ProviderAccessResult.InvalidInput("provider message identity")
        }
        failure?.let { return@synchronized it }
        val index = messages.indexOfFirst { it.id == id }
        if (index < 0) {
            return@synchronized ProviderAccessResult.Success(
                OutgoingSmsStatusUpdateOutcome.ROW_ABSENT,
            )
        }
        val current = messages[index]
        if (
            id !in appOwnedOutgoingIds ||
            current.providerThreadId.value != conversationId.value ||
            current.rawErrorCode != CLEARED_OUTGOING_ERROR_CODE ||
            current.smsProviderStatusOrNull() == null
        ) {
            return@synchronized ProviderAccessResult.Success(
                OutgoingSmsStatusUpdateOutcome.OWNERSHIP_CONFLICT,
            )
        }
        if (!applyMonotonicStatus(index, status)) {
            return@synchronized ProviderAccessResult.Success(
                OutgoingSmsStatusUpdateOutcome.OWNERSHIP_CONFLICT,
            )
        }
        ProviderAccessResult.Success(OutgoingSmsStatusUpdateOutcome.APPLIED)
    }

    fun snapshot(): List<SmsProviderMessage> = synchronized(lock) { messages.toList() }

    private fun applyMonotonicStatus(index: Int, requested: SmsProviderStatus): Boolean {
        val current = messages[index]
        val currentStatus = current.smsProviderStatusOrNull() ?: return false
        if (requested.transitionRank <= currentStatus.transitionRank) return true
        updatedStatuses[current.id] = requested
        val (box, messageStatus, rawStatus) = when (requested) {
            SmsProviderStatus.COMPLETE ->
                Triple(MessageBox.SENT, MessageStatus.COMPLETE, RAW_STATUS_COMPLETE)
            SmsProviderStatus.DELIVERY_FAILED ->
                Triple(MessageBox.SENT, MessageStatus.FAILED, RAW_STATUS_FAILED)
            SmsProviderStatus.FAILED ->
                Triple(MessageBox.FAILED, MessageStatus.FAILED, RAW_STATUS_FAILED)
            SmsProviderStatus.PENDING ->
                Triple(MessageBox.OUTBOX, MessageStatus.PENDING, RAW_STATUS_PENDING)
        }
        messages[index] = current.copy(
            box = box,
            status = messageStatus,
            rawStatus = rawStatus,
            syncFingerprint = fakeSyncFingerprint(
                current.id.value,
                current.providerThreadId.value,
                current.sender?.value,
                current.body,
                current.timestampMillis,
                box.toStorageCode(),
                messageStatus.toStorageCode(),
                rawStatus,
            ),
        )
        return true
    }

    private fun conversationIdFor(address: ParticipantAddress): ConversationId =
        conversationIds.getOrPut(address.value) { ConversationId(nextConversationId++) }

    private fun page(request: ProviderPageRequest): ProviderPage<SmsProviderMessage> {
        val ordered = messages.sortedWith(
            compareByDescending<SmsProviderMessage> { it.timestampMillis }
                .thenByDescending { it.id.value },
        )
        val eligible = request.before?.let { cursor ->
            ordered.filter { message -> message.isBefore(cursor) }
        } ?: ordered
        val items = eligible.take(request.limit)
        val exhausted = items.size >= eligible.size
        val next = if (exhausted) null else items.lastOrNull()?.let { message ->
            ProviderPageCursor(message.timestampMillis, message.id.value)
        }
        return ProviderPage(items = items, next = next, exhausted = exhausted)
    }
}

private const val RAW_STATUS_COMPLETE = 0
private const val RAW_STATUS_PENDING = 32
private const val RAW_STATUS_FAILED = 64
private const val OUTGOING_STAGING_ERROR_CODE = Int.MIN_VALUE
private const val CLEARED_OUTGOING_ERROR_CODE = 0

private val SmsProviderStatus.transitionRank: Int
    get() = when (this) {
        SmsProviderStatus.PENDING -> 0
        SmsProviderStatus.COMPLETE -> 1
        SmsProviderStatus.DELIVERY_FAILED -> 2
        SmsProviderStatus.FAILED -> 3
    }

private fun SmsProviderMessage.smsProviderStatusOrNull(): SmsProviderStatus? = when {
    box == MessageBox.OUTBOX && status == MessageStatus.PENDING && rawStatus == RAW_STATUS_PENDING -> {
        SmsProviderStatus.PENDING
    }
    box == MessageBox.SENT && status == MessageStatus.COMPLETE && rawStatus == RAW_STATUS_COMPLETE -> {
        SmsProviderStatus.COMPLETE
    }
    box == MessageBox.SENT && status == MessageStatus.FAILED && rawStatus == RAW_STATUS_FAILED -> {
        SmsProviderStatus.DELIVERY_FAILED
    }
    box == MessageBox.FAILED && status == MessageStatus.FAILED && rawStatus == RAW_STATUS_FAILED -> {
        SmsProviderStatus.FAILED
    }
    else -> null
}

private data class FakeIncomingDelivery(
    val stored: ProviderStoredMessage,
    var complete: Boolean = false,
)

private fun SmsProviderMessage.isBefore(cursor: ProviderPageCursor): Boolean =
    timestampMillis < cursor.timestampMillis ||
        (timestampMillis == cursor.timestampMillis && id.value < cursor.providerRowId)

internal fun fakeSyncFingerprint(vararg values: Any?): MessageSyncFingerprint {
    val digest = MessageDigest.getInstance("SHA-256")
    values.forEach { value ->
        val bytes = value.toString().toByteArray(StandardCharsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
        digest.update(0.toByte())
        digest.update(bytes)
    }
    return MessageSyncFingerprint.fromSha256(digest.digest())
}
