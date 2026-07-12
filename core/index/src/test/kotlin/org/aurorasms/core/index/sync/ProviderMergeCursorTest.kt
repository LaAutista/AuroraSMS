// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.sync

import java.util.ArrayDeque
import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageSyncFingerprint
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.MmsAttachmentSummary
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.telephony.DecodedIncomingMmsRecord
import org.aurorasms.core.telephony.IncomingSmsRecord
import org.aurorasms.core.telephony.MmsProviderDataSource
import org.aurorasms.core.telephony.MmsProviderMessage
import org.aurorasms.core.telephony.OutgoingSmsRecord
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderPage
import org.aurorasms.core.telephony.ProviderPageCursor
import org.aurorasms.core.telephony.ProviderPageRequest
import org.aurorasms.core.telephony.ProviderStoredMessage
import org.aurorasms.core.telephony.SmsProviderDataSource
import org.aurorasms.core.telephony.SmsProviderMessage
import org.aurorasms.core.telephony.SmsProviderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderMergeCursorTest {
    @Test
    fun `merges timestamp then SMS kind then provider ID descending`() = runTest {
        val sms = queuedSmsSource(
            success(
                page(
                    items = listOf(sms(id = 10, timestamp = 100), sms(id = 8, timestamp = 100), sms(id = 7, timestamp = 90)),
                ),
            ),
        )
        val mms = queuedMmsSource(
            success(
                page(
                    items = listOf(
                        mms(id = 99, timestamp = 100),
                        mms(id = 1, timestamp = 100),
                        mms(id = 5, timestamp = 95),
                    ),
                ),
            ),
        )

        val batch = ProviderMergeCursor(sms, mms).stageBatch(null, null).readyBatch()

        assertEquals(
            listOf("SMS:10", "SMS:8", "MMS:99", "MMS:1", "MMS:5", "SMS:7"),
            batch.messages.map { "${it.providerId.kind}:${it.providerId.value}" },
        )
        assertEquals(3, batch.smsProgress.consumedItemCount)
        assertEquals(3, batch.mmsProgress.consumedItemCount)
        assertTrue(batch.smsProgress.exhausted)
        assertTrue(batch.mmsProgress.exhausted)
    }

    @Test
    fun `refills a consumed lookahead before choosing the other source`() = runTest {
        val sms = queuedSmsSource(
            success(
                page(
                    items = listOf(sms(id = 10, timestamp = 100)),
                    next = cursor(timestamp = 100, id = 10),
                ),
            ),
            success(page(items = listOf(sms(id = 9, timestamp = 99)))),
        )
        val mms = queuedMmsSource(
            success(page(items = listOf(mms(id = 50, timestamp = 95)))),
        )

        val batch = ProviderMergeCursor(
            smsSource = sms,
            mmsSource = mms,
            pageSize = 1,
            maxBatchSize = 3,
        ).stageBatch(null, null).readyBatch()

        assertEquals(listOf(100L, 99L, 95L), batch.messages.map(ProviderMergeItem::timestampMillis))
        assertEquals(2, sms.requests.size)
        assertEquals(cursor(timestamp = 100, id = 10), sms.requests[1].before)
    }

    @Test
    fun `partial page stages only consumed cursor and refetches remaining rows`() = runTest {
        val sms = filteringSmsSource(
            listOf(sms(id = 10, timestamp = 100), sms(id = 9, timestamp = 90), sms(id = 8, timestamp = 80)),
        )
        val mms = emptyMmsSource()
        val merge = ProviderMergeCursor(
            smsSource = sms,
            mmsSource = mms,
            pageSize = 3,
            maxBatchSize = 2,
        )

        val first = merge.stageBatch(null, null).readyBatch()
        assertEquals(listOf(10L, 9L), first.messages.map { it.providerId.value })
        assertEquals(cursor(timestamp = 90, id = 9), first.smsProgress.stagedCursor)
        assertFalse(first.smsProgress.exhausted)

        val second = merge.stageBatch(first.smsProgress.stagedCursor, null).readyBatch()
        assertEquals(listOf(8L), second.messages.map { it.providerId.value })
        assertEquals(cursor(timestamp = 90, id = 9), sms.requests[1].before)
        assertTrue(second.smsProgress.exhausted)
    }

    @Test
    fun `process recreation does not query an already exhausted source`() = runTest {
        val sms = queuedSmsSource(ProviderAccessResult.PermissionDenied)
        val mms = queuedMmsSource(
            success(page(items = listOf(mms(id = 4, timestamp = 40)))),
        )

        val batch = ProviderMergeCursor(sms, mms).stageBatch(
            smsCursor = cursor(timestamp = 50, id = 5),
            mmsCursor = null,
            smsExhausted = true,
        ).readyBatch()

        assertTrue(sms.requests.isEmpty())
        assertTrue(batch.smsProgress.exhausted)
        assertEquals(0, batch.smsProgress.fetchedPageCount)
        assertEquals(listOf(4L), batch.messages.map { it.providerId.value })
    }

    @Test
    fun `fully consumed projected page may stage its older raw next cursor`() = runTest {
        val rawNext = cursor(timestamp = 85, id = 5)
        val sms = queuedSmsSource(
            success(
                page(
                    items = listOf(sms(id = 10, timestamp = 100), sms(id = 9, timestamp = 90)),
                    next = rawNext,
                ),
            ),
        )

        val batch = ProviderMergeCursor(
            smsSource = sms,
            mmsSource = emptyMmsSource(),
            maxBatchSize = 2,
        ).stageBatch(null, null).readyBatch()

        assertEquals(rawNext, batch.smsProgress.stagedCursor)
        assertFalse(batch.smsProgress.exhausted)
        assertEquals(2, batch.smsProgress.consumedItemCount)
    }

    @Test
    fun `rejects an out of order provider page`() = runTest {
        val sms = queuedSmsSource(
            success(
                page(
                    items = listOf(sms(id = 10, timestamp = 100), sms(id = 11, timestamp = 101)),
                ),
            ),
        )

        val failure = ProviderMergeCursor(sms, emptyMmsSource())
            .stageBatch(null, null)
            .failure()

        assertEquals(ProviderMergeSource.SMS, failure.source)
        assertEquals(ProviderMergeFailureReason.OUT_OF_ORDER_PAGE, failure.reason)
    }

    @Test
    fun `rejects a non advancing empty page cursor`() = runTest {
        val before = cursor(timestamp = 100, id = 10)
        val sms = queuedSmsSource(
            success(page(items = emptyList(), next = before)),
        )

        val failure = ProviderMergeCursor(sms, emptyMmsSource())
            .stageBatch(before, null)
            .failure()

        assertEquals(ProviderMergeFailureReason.NON_ADVANCING_CURSOR, failure.reason)
    }

    @Test
    fun `rejects page next cursor newer than its last projected row`() = runTest {
        val sms = queuedSmsSource(
            success(
                page(
                    items = listOf(sms(id = 9, timestamp = 90)),
                    next = cursor(timestamp = 95, id = 5),
                ),
            ),
        )

        val failure = ProviderMergeCursor(sms, emptyMmsSource())
            .stageBatch(null, null)
            .failure()

        assertEquals(ProviderMergeFailureReason.INCONSISTENT_PAGE_CURSOR, failure.reason)
    }

    @Test
    fun `malformed-only windows yield committable progress until an older valid row`() = runTest {
        val sms = queuedSmsSource(
            success(page(items = emptyList(), next = cursor(timestamp = 100, id = 10))),
            success(page(items = emptyList(), next = cursor(timestamp = 90, id = 9))),
            success(page(items = emptyList(), next = cursor(timestamp = 80, id = 8))),
            success(page(items = emptyList(), next = cursor(timestamp = 70, id = 7))),
            success(page(items = listOf(sms(id = 6, timestamp = 60)))),
        )
        val mms = emptyMmsSource()
        val merge = ProviderMergeCursor(
            smsSource = sms,
            mmsSource = mms,
            emptyPageBudgetPerSource = 2,
        )

        val first = merge.stageBatch(cursor(timestamp = 110, id = 11), null).readyBatch()
        assertTrue(first.messages.isEmpty())
        assertEquals(0, first.smsProgress.consumedItemCount)
        assertEquals(cursor(timestamp = 90, id = 9), first.smsProgress.stagedCursor)
        assertEquals(2, first.smsProgress.fetchedPageCount)
        assertFalse(first.smsProgress.exhausted)
        assertTrue(mms.requests.isEmpty())

        val second = merge.stageBatch(first.smsProgress.stagedCursor, null).readyBatch()
        assertTrue(second.messages.isEmpty())
        assertEquals(0, second.smsProgress.consumedItemCount)
        assertEquals(cursor(timestamp = 70, id = 7), second.smsProgress.stagedCursor)
        assertEquals(2, second.smsProgress.fetchedPageCount)
        assertFalse(second.smsProgress.exhausted)
        assertTrue(mms.requests.isEmpty())

        val third = merge.stageBatch(second.smsProgress.stagedCursor, null).readyBatch()
        assertEquals(listOf(6L), third.messages.map { it.providerId.value })
        assertEquals(cursor(timestamp = 60, id = 6), third.smsProgress.stagedCursor)
        assertTrue(third.smsProgress.exhausted)
        assertEquals(1, mms.requests.size)

        assertEquals(5, sms.requests.size)
        assertEquals(
            listOf(
                cursor(timestamp = 110, id = 11),
                cursor(timestamp = 100, id = 10),
                cursor(timestamp = 90, id = 9),
                cursor(timestamp = 80, id = 8),
                cursor(timestamp = 70, id = 7),
            ),
            sms.requests.map { it.before },
        )
    }

    @Test
    fun `maps provider access denial to a typed source failure`() = runTest {
        val sms = queuedSmsSource(ProviderAccessResult.PermissionDenied)

        val failure = ProviderMergeCursor(sms, emptyMmsSource())
            .stageBatch(null, null)
            .failure()

        assertEquals(ProviderMergeSource.SMS, failure.source)
        assertEquals(ProviderMergeFailureReason.PERMISSION_DENIED, failure.reason)
    }

    @Test
    fun `never requests more than 200 or stages more than 500 rows`() = runTest {
        val messages = (501L downTo 1L).map { sms(id = it, timestamp = 1_000) }
        val sms = filteringSmsSource(messages)

        val batch = ProviderMergeCursor(sms, emptyMmsSource())
            .stageBatch(null, null)
            .readyBatch()

        assertEquals(500, batch.messages.size)
        assertEquals(500, batch.smsProgress.consumedItemCount)
        assertEquals(cursor(timestamp = 1_000, id = 2), batch.smsProgress.stagedCursor)
        assertFalse(batch.smsProgress.exhausted)
        assertEquals(3, sms.requests.size)
        assertTrue(sms.requests.all { it.limit <= ProviderPageRequest.MAX_PROVIDER_PAGE_SIZE })
    }

    @Test
    fun `diagnostic strings redact staged message content`() = runTest {
        val secret = "private message body"
        val batch = ProviderMergeCursor(
            queuedSmsSource(success(page(items = listOf(sms(id = 1, timestamp = 1, body = secret))))),
            emptyMmsSource(),
        ).stageBatch(null, null).readyBatch()

        assertFalse(batch.toString().contains(secret))
        assertFalse(batch.messages.single().toString().contains(secret))
    }

    private fun ProviderMergeResult.readyBatch(): ProviderMergeBatch {
        assertTrue("Expected a ready provider batch but was $this", this is ProviderMergeResult.Ready)
        return (this as ProviderMergeResult.Ready).batch
    }

    private fun ProviderMergeResult.failure(): ProviderMergeFailure {
        assertTrue("Expected a provider failure but was $this", this is ProviderMergeResult.Failure)
        return (this as ProviderMergeResult.Failure).failure
    }
}

private class TestSmsSource(
    private val reader: suspend (ProviderPageRequest) -> ProviderAccessResult<ProviderPage<SmsProviderMessage>>,
) : SmsProviderDataSource {
    val requests = mutableListOf<ProviderPageRequest>()

    override suspend fun count(): ProviderAccessResult<Long> = ProviderAccessResult.Success(0L)

    override suspend fun readPage(
        request: ProviderPageRequest,
    ): ProviderAccessResult<ProviderPage<SmsProviderMessage>> {
        requests += request
        return reader(request)
    }

    override suspend fun insertIncoming(message: IncomingSmsRecord): ProviderAccessResult<ProviderStoredMessage> =
        ProviderAccessResult.Unsupported("test")

    override suspend fun markIncomingHandled(
        deliveryFingerprint: org.aurorasms.core.model.MessageDeliveryFingerprint,
        providerId: ProviderMessageId,
        conversationId: org.aurorasms.core.model.ConversationId,
    ): ProviderAccessResult<Unit> = ProviderAccessResult.Unsupported("test")

    override suspend fun insertOutgoing(message: OutgoingSmsRecord): ProviderAccessResult<ProviderStoredMessage> =
        ProviderAccessResult.Unsupported("test")

    override suspend fun updateStatus(
        id: ProviderMessageId,
        status: SmsProviderStatus,
    ): ProviderAccessResult<Unit> = ProviderAccessResult.Unsupported("test")
}

private class TestMmsSource(
    private val reader: suspend (ProviderPageRequest) -> ProviderAccessResult<ProviderPage<MmsProviderMessage>>,
) : MmsProviderDataSource {
    val requests = mutableListOf<ProviderPageRequest>()

    override suspend fun count(): ProviderAccessResult<Long> = ProviderAccessResult.Success(0L)

    override suspend fun readPage(
        request: ProviderPageRequest,
    ): ProviderAccessResult<ProviderPage<MmsProviderMessage>> {
        requests += request
        return reader(request)
    }

    override suspend fun insertIncoming(message: DecodedIncomingMmsRecord): ProviderAccessResult<ProviderStoredMessage> =
        ProviderAccessResult.Unsupported("test")
}

private fun queuedSmsSource(
    vararg results: ProviderAccessResult<ProviderPage<SmsProviderMessage>>,
): TestSmsSource {
    val queue = ArrayDeque(results.asList())
    return TestSmsSource { requireNotNull(queue.pollFirst()) { "Unexpected SMS provider read" } }
}

private fun queuedMmsSource(
    vararg results: ProviderAccessResult<ProviderPage<MmsProviderMessage>>,
): TestMmsSource {
    val queue = ArrayDeque(results.asList())
    return TestMmsSource { requireNotNull(queue.pollFirst()) { "Unexpected MMS provider read" } }
}

private fun emptyMmsSource(): TestMmsSource = TestMmsSource {
    success(page<MmsProviderMessage>(items = emptyList()))
}

private fun filteringSmsSource(messages: List<SmsProviderMessage>): TestSmsSource = TestSmsSource { request ->
    val remaining = messages.filter { message ->
        request.before?.let { message.cursor().isOlderThan(it) } ?: true
    }
    val selected = remaining.take(request.limit)
    val exhausted = selected.size == remaining.size
    success(
        ProviderPage(
            items = selected,
            next = if (exhausted) null else selected.last().cursor(),
            exhausted = exhausted,
        ),
    )
}

private fun <T> success(page: ProviderPage<T>): ProviderAccessResult<ProviderPage<T>> =
    ProviderAccessResult.Success(page)

private fun <T> page(
    items: List<T>,
    next: ProviderPageCursor? = null,
): ProviderPage<T> = ProviderPage(
    items = items,
    next = next,
    exhausted = next == null,
)

private fun sms(
    id: Long,
    timestamp: Long,
    body: String = "sms-$id",
): SmsProviderMessage = SmsProviderMessage(
    id = ProviderMessageId(ProviderKind.SMS, id),
    providerThreadId = ProviderThreadId(1),
    sender = null,
    body = body,
    direction = MessageDirection.INCOMING,
    box = MessageBox.INBOX,
    status = MessageStatus.COMPLETE,
    rawStatus = null,
    rawErrorCode = null,
    timestampMillis = timestamp,
    sentTimestampMillis = null,
    subscriptionId = null,
    read = true,
    seen = true,
    locked = false,
    syncFingerprint = fingerprint(id),
)

private fun mms(
    id: Long,
    timestamp: Long,
): MmsProviderMessage = MmsProviderMessage(
    id = ProviderMessageId(ProviderKind.MMS, id),
    providerThreadId = ProviderThreadId(1),
    sender = null,
    participants = emptyList(),
    participantsTruncated = false,
    body = "mms-$id",
    subject = null,
    direction = MessageDirection.INCOMING,
    box = MessageBox.INBOX,
    status = MessageStatus.COMPLETE,
    rawStatus = null,
    rawResponseStatus = null,
    rawRetrieveStatus = null,
    timestampMillis = timestamp,
    sentTimestampMillis = null,
    subscriptionId = null,
    attachments = MmsAttachmentSummary.EMPTY,
    read = true,
    seen = true,
    locked = false,
    syncFingerprint = fingerprint(id),
)

private fun fingerprint(seed: Long): MessageSyncFingerprint =
    MessageSyncFingerprint.fromSha256(ByteArray(MessageSyncFingerprint.SHA_256_BYTES) { (seed % 251).toByte() })

private fun cursor(timestamp: Long, id: Long): ProviderPageCursor = ProviderPageCursor(timestamp, id)

private fun SmsProviderMessage.cursor(): ProviderPageCursor = cursor(timestampMillis, id.value)

private fun ProviderPageCursor.isOlderThan(other: ProviderPageCursor): Boolean =
    timestampMillis < other.timestampMillis ||
        (timestampMillis == other.timestampMillis && providerRowId < other.providerRowId)
