// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.index.conversation.ConversationPageDirection
import org.aurorasms.core.index.conversation.ConversationLookupResult
import org.aurorasms.core.index.conversation.ConversationPageRequest
import org.aurorasms.core.index.conversation.ConversationPageResult
import org.aurorasms.core.index.conversation.RoomConversationRepository
import org.aurorasms.core.index.storage.AuroraIndexDatabase
import org.aurorasms.core.index.storage.IndexDatabaseFactory
import org.aurorasms.core.index.sync.IndexedProviderProjection
import org.aurorasms.core.index.timeline.RoomThreadTimelineRepository
import org.aurorasms.core.index.timeline.TimelinePageDirection
import org.aurorasms.core.index.timeline.TimelineContentResult
import org.aurorasms.core.index.timeline.TimelinePageRequest
import org.aurorasms.core.index.timeline.TimelinePageResult
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderThreadId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationProjectionTest {
    private lateinit var database: AuroraIndexDatabase

    @Before
    fun createDatabase() {
        database = IndexDatabaseFactory.createInMemory(
            ApplicationProvider.getApplicationContext<Context>(),
        )
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun scanningProjectionBuildsBoundedInboxParticipantsAndTimeline() = runBlocking {
        val generation = database.indexSyncDao().startGeneration(10L)
        val longBody = "z".repeat(20_000)
        val projections = listOf(
            projection(
                providerId = 10L,
                threadId = 100L,
                timestampMillis = 300L,
                body = "latest one",
                participants = listOf("+15550000001", "+15550000002"),
                fingerprintSeed = 'a',
            ),
            projection(
                providerId = 20L,
                threadId = 200L,
                timestampMillis = 250L,
                body = "latest two",
                participants = listOf("+15550000003"),
                fingerprintSeed = 'b',
            ),
            projection(
                providerId = 9L,
                threadId = 100L,
                timestampMillis = 200L,
                body = longBody,
                participants = listOf("+15550000001", "+15550000004"),
                participantsTruncated = true,
                fingerprintSeed = 'c',
            ),
            projection(
                providerId = 8L,
                threadId = 100L,
                timestampMillis = 100L,
                body = "oldest one",
                participants = listOf("+15550000001"),
                fingerprintSeed = 'd',
                read = true,
            ),
        )
        database.indexedMessageDao().commitScanningProjectionBatch(
            generationId = generation,
            projections = projections,
            smsCheckpoint = checkpoint(generation, 1, providerId = 8L, count = 4L, exhausted = true),
            mmsCheckpoint = checkpoint(generation, 2, count = 0L, exhausted = true),
            nowMillis = 20L,
            targetBatchSize = 500,
        )

        val inbox = RoomConversationRepository(database)
        val first = (inbox.loadInbox(ConversationPageRequest(limit = 1)) as ConversationPageResult.Page).page
        assertEquals(listOf(100L), first.items.map { it.providerThreadId.value })
        assertEquals(3L, first.items.single().indexedMessageCount)
        assertEquals(2L, first.items.single().indexedUnreadCount)
        assertEquals(3, first.items.single().indexedParticipantCount)
        assertTrue(first.items.single().participantsTruncated)
        assertNotNull(first.next)
        assertEquals(IndexRunState.SCANNING, first.coverage.state)

        val exact = inbox.loadConversation(ProviderThreadId(100L)) as ConversationLookupResult.Found
        assertEquals(100L, exact.summary.providerThreadId.value)
        assertEquals(3, exact.summary.indexedParticipantCount)
        assertEquals(3, exact.summary.participants.size)
        assertEquals(IndexRunState.SCANNING, exact.coverage.state)
        assertNull(exact.verifiedIdentity)
        assertTrue(
            inbox.loadConversation(ProviderThreadId(999L)) is ConversationLookupResult.Missing,
        )

        val older = (
            inbox.loadInbox(
                ConversationPageRequest(
                    limit = 1,
                    cursor = requireNotNull(first.next),
                    direction = ConversationPageDirection.OLDER,
                ),
            ) as ConversationPageResult.Page
            ).page
        assertEquals(listOf(200L), older.items.map { it.providerThreadId.value })
        assertNull(older.next)

        val stale = inbox.loadInbox(
            ConversationPageRequest(
                cursor = requireNotNull(first.next).copy(generationId = generation + 1L),
            ),
        )
        assertTrue(stale is ConversationPageResult.StaleGeneration)

        val timeline = RoomThreadTimelineRepository(database)
        val latest = (
            timeline.load(
                TimelinePageRequest(
                    providerThreadId = ProviderThreadId(100L),
                    limit = 2,
                ),
            ) as TimelinePageResult.Page
            ).page
        assertEquals(listOf(9L, 10L), latest.items.map { it.providerMessageId.value })
        assertEquals(16_384, latest.items.first().bodyPreview?.length)
        assertTrue(latest.items.first().bodyTruncated)
        assertNotNull(latest.next)

        val fullContent = timeline.loadContent(
            providerThreadId = ProviderThreadId(100L),
            providerMessageId = latest.items.first().providerMessageId,
        ) as TimelineContentResult.Found
        assertEquals(20_000, fullContent.content.body?.length)
        assertFalse(fullContent.content.sourceTruncated)
        assertTrue(
            timeline.loadContent(
                providerThreadId = ProviderThreadId(200L),
                providerMessageId = latest.items.first().providerMessageId,
            ) is TimelineContentResult.Missing,
        )

        val olderTimeline = (
            timeline.load(
                TimelinePageRequest(
                    providerThreadId = ProviderThreadId(100L),
                    limit = 2,
                    cursor = requireNotNull(latest.next),
                    direction = TimelinePageDirection.OLDER,
                ),
            ) as TimelinePageResult.Page
            ).page
        assertEquals(listOf(8L), olderTimeline.items.map { it.providerMessageId.value })

        assertEquals(1, database.indexSyncDao().markVerifying(generation, 30L))
        assertNotNull(
            database.indexSyncDao().finishVerifiedGeneration(
                generationId = generation,
                nowMillis = 40L,
                smsProviderCount = 4L,
                mmsProviderCount = 0L,
            ),
        )
        val complete = (inbox.loadInbox() as ConversationPageResult.Page).page
        assertTrue(complete.coverage.verifiedComplete)
    }

    @Test
    fun verifiedExactIdentityExtendsBeyondTheEightParticipantPreview() = runBlocking {
        val generation = database.indexSyncDao().startGeneration(10L)
        val participants = (9 downTo 1).map { index -> "+1555000000$index" }
        database.indexedMessageDao().commitScanningProjectionBatch(
            generationId = generation,
            projections = listOf(
                projection(
                    providerId = 30L,
                    threadId = 300L,
                    timestampMillis = 300L,
                    body = "bounded group",
                    participants = participants,
                    fingerprintSeed = 'e',
                ),
            ),
            smsCheckpoint = checkpoint(generation, 1, providerId = 30L, count = 1L, exhausted = true),
            mmsCheckpoint = checkpoint(generation, 2, count = 0L, exhausted = true),
            nowMillis = 20L,
            targetBatchSize = 500,
        )

        val repository = RoomConversationRepository(database)
        val scanning = repository.loadConversation(
            ProviderThreadId(300L),
        ) as ConversationLookupResult.Found
        assertEquals(8, scanning.summary.participants.size)
        assertEquals(9, scanning.summary.indexedParticipantCount)
        assertNull(scanning.verifiedIdentity)

        assertEquals(1, database.indexSyncDao().markVerifying(generation, 30L))
        assertNotNull(
            database.indexSyncDao().finishVerifiedGeneration(
                generationId = generation,
                nowMillis = 40L,
                smsProviderCount = 1L,
                mmsProviderCount = 0L,
            ),
        )

        val complete = repository.loadConversation(
            ProviderThreadId(300L),
        ) as ConversationLookupResult.Found
        assertTrue(complete.coverage.verifiedComplete)
        assertEquals(8, complete.summary.participants.size)
        assertEquals(9, complete.summary.indexedParticipantCount)
        val identity = requireNotNull(complete.verifiedIdentity)
        assertEquals(300L, identity.providerThreadId.value)
        assertEquals(generation, identity.generationId)
        assertEquals(participants.sorted(), identity.participants.map { it.value })
        assertEquals(
            9,
            database.conversationDao().verifiedIdentityParticipants(generation, 300L).size,
        )

        assertEquals(1, database.indexSyncDao().markPendingChanges(generation, 50L))
        val pending = repository.loadConversation(
            ProviderThreadId(300L),
        ) as ConversationLookupResult.Found
        assertFalse(pending.coverage.verifiedComplete)
        assertNull(pending.verifiedIdentity)
    }

    @Test
    fun completeLargeHistoryPagesEveryConversationAndThreadMessageExactlyOnce() = runBlocking {
        val generation = database.indexSyncDao().startGeneration(10L)
        val conversationProjections = (1L..152L).map { index ->
            projection(
                providerId = index,
                threadId = 1_000L + index,
                timestampMillis = 50_000L - index,
                body = "synthetic conversation $index",
                participants = listOf("+15550100000"),
                fingerprintSeed = 'a',
            )
        }
        val timelineProjections = (1_000L..1_150L).map { providerId ->
            projection(
                providerId = providerId,
                threadId = 9_999L,
                timestampMillis = 60_000L + providerId,
                body = "synthetic timeline $providerId",
                participants = listOf("+15550100001"),
                fingerprintSeed = 'b',
            )
        }
        val projections = conversationProjections + timelineProjections
        database.indexedMessageDao().commitScanningProjectionBatch(
            generationId = generation,
            projections = projections,
            smsCheckpoint = checkpoint(
                generation,
                1,
                providerId = 1_150L,
                count = projections.size.toLong(),
                exhausted = true,
            ),
            mmsCheckpoint = checkpoint(generation, 2, count = 0L, exhausted = true),
            nowMillis = 20L,
            targetBatchSize = 500,
        )
        assertEquals(1, database.indexSyncDao().markVerifying(generation, 30L))
        assertNotNull(
            database.indexSyncDao().finishVerifiedGeneration(
                generationId = generation,
                nowMillis = 40L,
                smsProviderCount = projections.size.toLong(),
                mmsProviderCount = 0L,
            ),
        )

        val conversationRepository = RoomConversationRepository(database)
        val conversationIds = mutableListOf<Long>()
        var conversationCursor: org.aurorasms.core.index.conversation.ConversationCursor? = null
        do {
            val page = conversationRepository.loadInbox(
                ConversationPageRequest(limit = 50, cursor = conversationCursor),
            ) as ConversationPageResult.Page
            conversationIds += page.page.items.map { it.providerThreadId.value }
            conversationCursor = page.page.next
        } while (conversationCursor != null)

        assertEquals(153, conversationIds.size)
        assertEquals(153, conversationIds.distinct().size)
        assertTrue(9_999L in conversationIds)
        assertTrue((1L..152L).all { 1_000L + it in conversationIds })

        val timelineRepository = RoomThreadTimelineRepository(database)
        val messageIds = mutableListOf<Long>()
        var timelineCursor: org.aurorasms.core.index.timeline.TimelineCursor? = null
        var direction = TimelinePageDirection.LATEST
        do {
            val page = timelineRepository.load(
                TimelinePageRequest(
                    providerThreadId = ProviderThreadId(9_999L),
                    limit = 50,
                    cursor = timelineCursor,
                    direction = direction,
                ),
            ) as TimelinePageResult.Page
            messageIds += page.page.items.map { it.providerMessageId.value }
            timelineCursor = page.page.next
            direction = TimelinePageDirection.OLDER
        } while (timelineCursor != null)

        assertEquals(151, messageIds.size)
        assertEquals((1_000L..1_150L).toSet(), messageIds.toSet())
    }

    @Test
    fun incompleteRefreshPresentsBestKnownRowsAcrossDurableGenerations() = runBlocking {
        val firstGeneration = database.indexSyncDao().startGeneration(10L)
        database.indexedMessageDao().commitScanningProjectionBatch(
            generationId = firstGeneration,
            projections = listOf(
                projection(
                    providerId = 10L,
                    threadId = 100L,
                    timestampMillis = 100L,
                    body = "cached older message",
                    participants = listOf("+15550000001"),
                    fingerprintSeed = 'c',
                    generationId = firstGeneration,
                ),
                projection(
                    providerId = 20L,
                    threadId = 200L,
                    timestampMillis = 200L,
                    body = "cached conversation",
                    participants = listOf("+15550000002"),
                    fingerprintSeed = 'd',
                    generationId = firstGeneration,
                ),
            ),
            smsCheckpoint = checkpoint(firstGeneration, 1, providerId = 10L, count = 2L, exhausted = true),
            mmsCheckpoint = checkpoint(firstGeneration, 2, count = 0L, exhausted = true),
            nowMillis = 20L,
            targetBatchSize = 500,
        )
        assertEquals(1, database.indexSyncDao().markVerifying(firstGeneration, 30L))
        assertNotNull(
            database.indexSyncDao().finishVerifiedGeneration(
                generationId = firstGeneration,
                nowMillis = 40L,
                smsProviderCount = 2L,
                mmsProviderCount = 0L,
            ),
        )

        val refreshGeneration = database.indexSyncDao().startGeneration(50L)
        database.indexedMessageDao().commitScanningProjectionBatch(
            generationId = refreshGeneration,
            projections = listOf(
                projection(
                    providerId = 11L,
                    threadId = 100L,
                    timestampMillis = 300L,
                    body = "new refresh row",
                    participants = listOf("+15550000001"),
                    fingerprintSeed = 'e',
                    generationId = refreshGeneration,
                ),
            ),
            smsCheckpoint = checkpoint(refreshGeneration, 1, providerId = 11L, count = 1L),
            mmsCheckpoint = checkpoint(refreshGeneration, 2),
            nowMillis = 60L,
            targetBatchSize = 500,
        )

        val conversations = RoomConversationRepository(database)
        val firstPage = conversations.loadInbox(
            ConversationPageRequest(limit = 1),
        ) as ConversationPageResult.Page
        assertFalse(firstPage.page.coverage.verifiedComplete)
        assertEquals(3L, firstPage.page.coverage.indexedMessageCount)
        assertEquals(listOf(100L), firstPage.page.items.map { it.providerThreadId.value })
        val olderPage = conversations.loadInbox(
            ConversationPageRequest(
                limit = 1,
                cursor = requireNotNull(firstPage.page.next),
                direction = ConversationPageDirection.OLDER,
            ),
        ) as ConversationPageResult.Page
        assertEquals(listOf(200L), olderPage.page.items.map { it.providerThreadId.value })
        assertTrue(
            conversations.loadConversation(ProviderThreadId(200L)) is ConversationLookupResult.Found,
        )

        val timeline = RoomThreadTimelineRepository(database)
        val thread = timeline.load(
            TimelinePageRequest(
                providerThreadId = ProviderThreadId(100L),
                limit = 10,
            ),
        ) as TimelinePageResult.Page
        assertEquals(listOf(10L, 11L), thread.page.items.map { it.providerMessageId.value })
        assertTrue(
            timeline.loadContent(
                providerThreadId = ProviderThreadId(100L),
                providerMessageId = thread.page.items.first().providerMessageId,
            ) is TimelineContentResult.Found,
        )
    }
}

private fun projection(
    providerId: Long,
    threadId: Long,
    timestampMillis: Long,
    body: String,
    participants: List<String>,
    fingerprintSeed: Char,
    participantsTruncated: Boolean = false,
    read: Boolean = false,
    generationId: Long = 1L,
): IndexedProviderProjection = IndexedProviderProjection(
    message = entity(
        kind = ProviderKind.SMS,
        providerId = providerId,
        providerThreadId = threadId,
        timestampMillis = timestampMillis,
        body = body,
        fingerprintSeed = fingerprintSeed,
        generationId = generationId,
    ).copy(isRead = read),
    participantAddresses = participants,
    participantsTruncated = participantsTruncated,
)
