// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.testing

import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.MmsAttachmentSummary
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.telephony.DecodedIncomingMmsRecord
import org.aurorasms.core.telephony.MmsProviderDataSource
import org.aurorasms.core.telephony.MmsProviderMessage
import org.aurorasms.core.telephony.OutgoingMmsProviderStatus
import org.aurorasms.core.telephony.OutgoingMmsStatusUpdateOutcome
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderPage
import org.aurorasms.core.telephony.ProviderPageCursor
import org.aurorasms.core.telephony.ProviderPageRequest
import org.aurorasms.core.telephony.ProviderStoredMessage

class FakeMmsProviderDataSource(
    seed: Iterable<MmsProviderMessage> = emptyList(),
) : MmsProviderDataSource {
    private val lock = Any()
    private val messages = seed.toMutableList()
    private var nextProviderId = (messages.maxOfOrNull { it.id.value } ?: 0L) + 1L
    private var nextConversationId = maxOf(
        20_000L,
        (messages.maxOfOrNull { it.providerThreadId.value } ?: 0L) + 1L,
    )
    private val conversationIds = linkedMapOf<String, ConversationId>().apply {
        messages.forEach { message ->
            putIfAbsent(message.participantKey(), ConversationId(message.providerThreadId.value))
        }
    }

    var failure: ProviderAccessResult<Nothing>? = null
    val insertedIncoming = mutableListOf<DecodedIncomingMmsRecord>()
    val outgoingStatusUpdates = mutableListOf<Triple<ProviderMessageId, ConversationId, OutgoingMmsProviderStatus>>()

    override suspend fun count(): ProviderAccessResult<Long> = synchronized(lock) {
        failure ?: ProviderAccessResult.Success(messages.size.toLong())
    }

    override suspend fun readPage(
        request: ProviderPageRequest,
    ): ProviderAccessResult<ProviderPage<MmsProviderMessage>> = synchronized(lock) {
        failure ?: ProviderAccessResult.Success(page(request))
    }

    override suspend fun insertIncoming(
        message: DecodedIncomingMmsRecord,
    ): ProviderAccessResult<ProviderStoredMessage> = synchronized(lock) {
        failure ?: run {
            insertedIncoming += message
            val id = ProviderMessageId(ProviderKind.MMS, nextProviderId++)
            val conversationId = conversationIds.getOrPut(message.participantKey()) {
                ConversationId(nextConversationId++)
            }
            messages += MmsProviderMessage(
                id = id,
                providerThreadId = ProviderThreadId(conversationId.value),
                sender = message.participants.firstOrNull(),
                participants = message.participants,
                participantsTruncated = false,
                body = message.text,
                subject = message.subject,
                direction = MessageDirection.INCOMING,
                box = MessageBox.INBOX,
                status = MessageStatus.COMPLETE,
                rawStatus = null,
                rawResponseStatus = null,
                rawRetrieveStatus = null,
                timestampMillis = message.receivedTimestampMillis,
                sentTimestampMillis = message.sentTimestampMillis,
                subscriptionId = message.subscriptionId,
                attachments = MmsAttachmentSummary.EMPTY,
                read = false,
                seen = false,
                locked = false,
                syncFingerprint = fakeSyncFingerprint(
                    id.value,
                    conversationId.value,
                    message.participants.joinToString { it.value },
                    message.text,
                    message.subject,
                    message.receivedTimestampMillis,
                ),
            )
            ProviderAccessResult.Success(ProviderStoredMessage(id, conversationId))
        }
    }

    override suspend fun updateOutgoingStatus(
        id: ProviderMessageId,
        conversationId: ConversationId,
        status: OutgoingMmsProviderStatus,
    ): ProviderAccessResult<OutgoingMmsStatusUpdateOutcome> = synchronized(lock) {
        failure ?: run {
            outgoingStatusUpdates += Triple(id, conversationId, status)
            ProviderAccessResult.Success(OutgoingMmsStatusUpdateOutcome.ROW_ABSENT)
        }
    }

    fun snapshot(): List<MmsProviderMessage> = synchronized(lock) { messages.toList() }

    private fun page(request: ProviderPageRequest): ProviderPage<MmsProviderMessage> {
        val ordered = messages.sortedWith(
            compareByDescending<MmsProviderMessage> { it.timestampMillis }
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

private fun MmsProviderMessage.isBefore(cursor: ProviderPageCursor): Boolean =
    timestampMillis < cursor.timestampMillis ||
        (timestampMillis == cursor.timestampMillis && id.value < cursor.providerRowId)

private fun MmsProviderMessage.participantKey(): String =
    participants.map { it.value }.sorted().joinToString(separator = "\u0000")

private fun DecodedIncomingMmsRecord.participantKey(): String =
    participants.map { it.value }.sorted().joinToString(separator = "\u0000")
