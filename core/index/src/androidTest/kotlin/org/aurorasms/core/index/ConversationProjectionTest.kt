// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.index.conversation.ConversationPageDirection
import org.aurorasms.core.index.conversation.ConversationPageRequest
import org.aurorasms.core.index.conversation.ConversationPageResult
import org.aurorasms.core.index.conversation.RoomConversationRepository
import org.aurorasms.core.index.storage.AuroraIndexDatabase
import org.aurorasms.core.index.storage.IndexDatabaseFactory
import org.aurorasms.core.index.sync.IndexedProviderProjection
import org.aurorasms.core.index.timeline.RoomThreadTimelineRepository
import org.aurorasms.core.index.timeline.TimelinePageDirection
import org.aurorasms.core.index.timeline.TimelinePageRequest
import org.aurorasms.core.index.timeline.TimelinePageResult
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderThreadId
import org.junit.After
import org.junit.Assert.assertEquals
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
): IndexedProviderProjection = IndexedProviderProjection(
    message = entity(
        kind = ProviderKind.SMS,
        providerId = providerId,
        providerThreadId = threadId,
        timestampMillis = timestampMillis,
        body = body,
        fingerprintSeed = fingerprintSeed,
    ).copy(isRead = read),
    participantAddresses = participants,
    participantsTruncated = participantsTruncated,
)
