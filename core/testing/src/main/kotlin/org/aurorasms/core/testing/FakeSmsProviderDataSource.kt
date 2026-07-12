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
import org.aurorasms.core.telephony.IncomingDeliveryDisposition
import org.aurorasms.core.telephony.OutgoingSmsRecord
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

    var failure: ProviderAccessResult<Nothing>? = null
    val insertedIncoming = mutableListOf<IncomingSmsRecord>()
    val insertedOutgoing = mutableListOf<OutgoingSmsRecord>()
    val updatedStatuses = linkedMapOf<ProviderMessageId, SmsProviderStatus>()

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
            val conversationId = conversationIdFor(message.recipient)
            messages += SmsProviderMessage(
                id = id,
                providerThreadId = ProviderThreadId(conversationId.value),
                sender = message.recipient,
                body = message.body,
                direction = MessageDirection.OUTGOING,
                box = MessageBox.OUTBOX,
                status = MessageStatus.PENDING,
                rawStatus = null,
                rawErrorCode = null,
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

    override suspend fun updateStatus(
        id: ProviderMessageId,
        status: SmsProviderStatus,
    ): ProviderAccessResult<Unit> = synchronized(lock) {
        val index = messages.indexOfFirst { it.id == id }
        failure ?: if (index >= 0) {
            updatedStatuses[id] = status
            val current = messages[index]
            val (box, messageStatus, rawStatus) = when (status) {
                SmsProviderStatus.COMPLETE -> Triple(MessageBox.SENT, MessageStatus.COMPLETE, 0)
                SmsProviderStatus.FAILED -> Triple(MessageBox.FAILED, MessageStatus.FAILED, 64)
                SmsProviderStatus.PENDING -> Triple(MessageBox.OUTBOX, MessageStatus.PENDING, 32)
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
            ProviderAccessResult.Success(Unit)
        } else {
            ProviderAccessResult.InvalidInput("id")
        }
    }

    fun snapshot(): List<SmsProviderMessage> = synchronized(lock) { messages.toList() }

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
