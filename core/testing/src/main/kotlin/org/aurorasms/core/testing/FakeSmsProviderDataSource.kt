// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.testing

import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageDeliveryFingerprint
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
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
        (messages.maxOfOrNull { it.threadId } ?: 0L) + 1L,
    )
    private val conversationIds = linkedMapOf<String, ConversationId>().apply {
        messages.filter { it.threadId > 0L }.forEach { message ->
            putIfAbsent(message.address.value, ConversationId(message.threadId))
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
                threadId = conversationId.value,
                address = message.sender,
                body = message.body,
                direction = MessageDirection.INCOMING,
                timestampMillis = message.receivedTimestampMillis,
                sentTimestampMillis = message.sentTimestampMillis,
                subscriptionId = message.subscriptionId,
                read = false,
                seen = false,
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
                threadId = conversationId.value,
                address = message.recipient,
                body = message.body,
                direction = MessageDirection.OUTGOING,
                timestampMillis = message.timestampMillis,
                sentTimestampMillis = null,
                subscriptionId = message.subscriptionId,
                read = true,
                seen = true,
            )
            ProviderAccessResult.Success(ProviderStoredMessage(id, conversationId))
        }
    }

    override suspend fun updateStatus(
        id: ProviderMessageId,
        status: SmsProviderStatus,
    ): ProviderAccessResult<Unit> = synchronized(lock) {
        failure ?: if (messages.any { it.id == id }) {
            updatedStatuses[id] = status
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
