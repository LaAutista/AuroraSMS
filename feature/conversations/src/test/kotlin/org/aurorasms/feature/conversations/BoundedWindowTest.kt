// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import org.aurorasms.core.index.IndexCoverage
import org.aurorasms.core.index.IndexRunState
import org.aurorasms.core.index.conversation.ConversationCursor
import org.aurorasms.core.index.conversation.ConversationPage
import org.aurorasms.core.index.conversation.ConversationPageDirection
import org.aurorasms.core.index.conversation.ConversationSummary
import org.aurorasms.core.index.timeline.TimelineCursor
import org.aurorasms.core.index.timeline.TimelineMessage
import org.aurorasms.core.index.timeline.TimelinePage
import org.aurorasms.core.index.timeline.TimelinePageDirection
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedWindowTest {
    @Test
    fun `older inbox pages evict newest rows and retain the visible anchor`() {
        val initial = (300L downTo 101L).map(::conversation)
        val window = BoundedInboxWindow(initial, olderCursor = conversationCursor(101L))
        val anchor = WindowAnchor(ProviderThreadId(150L), scrollOffsetPixels = 23)
        val olderPage = conversationPage(100L downTo 51L, ConversationPageDirection.OLDER)

        val mutation = window.appendOlder(olderPage, anchor)

        assertEquals(MAXIMUM_RETAINED_INBOX_ROWS, mutation.window.items.size)
        assertEquals(250L, mutation.window.items.first().providerThreadId.value)
        assertEquals(51L, mutation.window.items.last().providerThreadId.value)
        assertEquals(anchor, mutation.restoreAnchor)
        assertFalse(mutation.window.items.any { it.providerThreadId.value > 250L })
    }

    @Test
    fun `newer inbox pages move an existing thread once and evict the oldest row`() {
        val initial = (200L downTo 1L).map(::conversation)
        val window = BoundedInboxWindow(initial, olderCursor = conversationCursor(1L))
        val moved = conversation(50L, timestampMillis = 1_000L, snippet = "updated")
        val added = conversation(201L, timestampMillis = 999L)
        val anchor = WindowAnchor(ProviderThreadId(200L), scrollOffsetPixels = 7)
        val page = conversationPage(
            items = listOf(moved, added),
            direction = ConversationPageDirection.NEWER,
        )

        val mutation = window.prependNewer(page, userAtNewest = true, visibleAnchor = anchor)

        assertEquals(MAXIMUM_RETAINED_INBOX_ROWS, mutation.window.items.size)
        assertEquals(listOf(50L, 201L), mutation.window.items.take(2).map { it.providerThreadId.value })
        assertEquals("updated", mutation.window.items.first().latestSnippet)
        assertEquals(1, mutation.window.items.count { it.providerThreadId == ProviderThreadId(50L) })
        assertFalse(mutation.window.items.any { it.providerThreadId == ProviderThreadId(1L) })
        assertEquals(anchor, mutation.restoreAnchor)
        assertFalse(mutation.window.pendingNewer)
    }

    @Test
    fun `newer inbox pages only mark pending while the user is away from newest`() {
        val initial = (20L downTo 1L).map(::conversation)
        val window = BoundedInboxWindow(initial, olderCursor = null)
        val anchor = WindowAnchor(ProviderThreadId(10L), scrollOffsetPixels = 31)
        val page = conversationPage(listOf(conversation(21L)), ConversationPageDirection.NEWER)

        val mutation = window.prependNewer(page, userAtNewest = false, visibleAnchor = anchor)

        assertEquals(initial, mutation.window.items)
        assertTrue(mutation.window.pendingNewer)
        assertNull(mutation.window.newerCursor)
        assertEquals(anchor, mutation.restoreAnchor)
    }

    @Test
    fun `newest inbox refresh adopts an older cursor discovered after first paint`() {
        val firstPaint = (20L downTo 1L).map(::conversation)
        val window = BoundedInboxWindow(firstPaint, olderCursor = null)
        val discoveredCursor = conversationCursor(1L)
        val refreshedPage = conversationPage(
            items = (40L downTo 1L).map(::conversation),
            direction = ConversationPageDirection.OLDER,
            next = discoveredCursor,
        )

        val refreshed = window.refreshNewest(refreshedPage)

        assertEquals(discoveredCursor, refreshed.olderCursor)
        assertEquals(40L, refreshed.items.first().providerThreadId.value)
        assertEquals(1L, refreshed.items.last().providerThreadId.value)
    }

    @Test
    fun `older thread pages evict newest messages and retain the visible anchor`() {
        val initial = (101L..300L).map(::message)
        val window = BoundedThreadWindow(initial, olderCursor = timelineCursor(101L), newerCursor = null)
        val anchor = WindowAnchor(messageId(150L), scrollOffsetPixels = 19)
        val page = timelinePage(51L..100L, TimelinePageDirection.OLDER)

        val mutation = window.prependOlder(page, anchor)

        assertEquals(MAXIMUM_RETAINED_THREAD_ROWS, mutation.window.items.size)
        assertEquals(51L, mutation.window.items.first().localRowId)
        assertEquals(250L, mutation.window.items.last().localRowId)
        assertEquals(anchor, mutation.restoreAnchor)
    }

    @Test
    fun `newer thread pages evict oldest messages and retain the visible anchor`() {
        val initial = (101L..300L).map(::message)
        val window = BoundedThreadWindow(initial, olderCursor = null, newerCursor = timelineCursor(300L))
        val anchor = WindowAnchor(messageId(250L), scrollOffsetPixels = 11)
        val page = timelinePage(301L..350L, TimelinePageDirection.NEWER)

        val mutation = window.appendNewer(page, anchor)

        assertEquals(MAXIMUM_RETAINED_THREAD_ROWS, mutation.window.items.size)
        assertEquals(151L, mutation.window.items.first().localRowId)
        assertEquals(350L, mutation.window.items.last().localRowId)
        assertEquals(anchor, mutation.restoreAnchor)
        assertFalse(mutation.window.pendingNewer)
    }

    @Test
    fun `thread merge deduplicates compound provider identities and keeps the newer projection`() {
        val initial = (1L..3L).map(::message)
        val replacement = message(3L, body = "replacement")
        val page = timelinePage(
            items = listOf(replacement, message(4L)),
            direction = TimelinePageDirection.NEWER,
        )
        val window = BoundedThreadWindow(initial, olderCursor = null, newerCursor = timelineCursor(3L))

        val mutation = window.appendNewer(page, visibleAnchor = null)

        assertEquals(listOf(1L, 2L, 3L, 4L), mutation.window.items.map(TimelineMessage::localRowId))
        assertEquals("replacement", mutation.window.items.single { it.localRowId == 3L }.bodyPreview)
    }

    @Test
    fun `latest thread replacement obeys the retained text budget`() {
        val body = "x".repeat(16_384)
        val page = timelinePage(
            items = (1L..100L).map { row -> message(row, body) },
            direction = TimelinePageDirection.LATEST,
        )

        val window = BoundedThreadWindow.fromLatest(page)

        assertEquals(64, window.items.size)
        assertEquals(MAXIMUM_RETAINED_THREAD_TEXT_UNITS, window.items.sumOf { it.bodyPreview.orEmpty().length })
        assertEquals(37L, window.items.first().localRowId)
        assertEquals(100L, window.items.last().localRowId)
    }

    @Test
    fun `incoming signals never mutate retained thread content`() {
        val initial = (1L..10L).map(::message)
        val window = BoundedThreadWindow(initial, olderCursor = null, newerCursor = null)

        val pending = window.noteIncomingWhileAway()

        assertEquals(initial, pending.items)
        assertTrue(pending.pendingNewer)
    }

    @Test
    fun `one expanded body shares the retained thread text budget and remains pageable`() {
        val body = "x".repeat(16_384)
        val window = BoundedThreadWindow(
            items = (1L..64L).map { row -> message(row, body) },
            olderCursor = null,
            newerCursor = null,
        )

        val reserved = window.reserveAdditionalText(
            additionalTextUnits = 100_000,
            preservedMessageId = messageId(50L),
            generationId = 1L,
        )

        assertEquals(57, reserved.items.size)
        assertEquals(8L, reserved.items.first().localRowId)
        assertTrue(reserved.items.any { it.providerMessageId == messageId(50L) })
        assertTrue(reserved.retainedTextUnits + 100_000 <= MAXIMUM_RETAINED_THREAD_TEXT_UNITS)
        assertNotNull(reserved.olderCursor)
    }
}

private val TEST_COVERAGE = IndexCoverage(
    generationId = 1L,
    state = IndexRunState.COMPLETE,
    indexedMessageCount = 1_000L,
    smsExhausted = true,
    mmsExhausted = true,
    pendingChanges = false,
    generationCommittedCount = 1_000L,
    smsCheckpointCommittedCount = 1_000L,
)

private fun conversation(
    row: Long,
    timestampMillis: Long = row,
    snippet: String = "row-$row",
): ConversationSummary = ConversationSummary(
    providerThreadId = ProviderThreadId(row),
    latestLocalRowId = row,
    latestProviderMessageId = messageId(row),
    latestTimestampMillis = timestampMillis,
    latestSentTimestampMillis = null,
    latestDirection = MessageDirection.INCOMING,
    latestBox = MessageBox.INBOX,
    latestStatus = MessageStatus.COMPLETE,
    latestSubscriptionId = null,
    latestSenderAddress = null,
    latestSnippet = snippet,
    latestAttachmentCount = 0,
    latestAttachmentTypeSummary = "",
    latestRead = true,
    indexedMessageCount = 1L,
    indexedUnreadCount = 0L,
    participants = emptyList(),
    indexedParticipantCount = 0,
    participantsTruncated = false,
)

private fun conversationPage(
    rows: Iterable<Long>,
    direction: ConversationPageDirection,
): ConversationPage = conversationPage(rows.map(::conversation), direction)

private fun conversationPage(
    items: List<ConversationSummary>,
    direction: ConversationPageDirection,
    next: ConversationCursor? = null,
): ConversationPage = ConversationPage(
    items = items,
    next = next,
    direction = direction,
    coverage = TEST_COVERAGE,
)

private fun conversationCursor(row: Long): ConversationCursor = ConversationCursor(
    generationId = 1L,
    latestTimestampMillis = row,
    latestLocalRowId = row,
)

private fun message(
    row: Long,
    body: String? = null,
): TimelineMessage = TimelineMessage(
    localRowId = row,
    providerMessageId = messageId(row),
    providerThreadId = ProviderThreadId(7L),
    timestampMillis = row,
    sentTimestampMillis = null,
    direction = MessageDirection.INCOMING,
    box = MessageBox.INBOX,
    status = MessageStatus.COMPLETE,
    subscriptionId = null,
    senderAddress = null,
    bodyPreview = body,
    bodyTruncated = false,
    subject = null,
    attachmentCount = 0,
    attachmentTypeSummary = "",
    read = true,
    seen = true,
    locked = false,
)

private fun timelinePage(
    rows: Iterable<Long>,
    direction: TimelinePageDirection,
): TimelinePage = timelinePage(rows.map(::message), direction)

private fun timelinePage(
    items: List<TimelineMessage>,
    direction: TimelinePageDirection,
): TimelinePage = TimelinePage(
    items = items,
    next = null,
    direction = direction,
    coverage = TEST_COVERAGE,
)

private fun timelineCursor(row: Long): TimelineCursor = TimelineCursor(
    generationId = 1L,
    providerThreadId = ProviderThreadId(7L),
    timestampMillis = row,
    localRowId = row,
)

private fun messageId(row: Long): ProviderMessageId = ProviderMessageId(ProviderKind.SMS, row)
