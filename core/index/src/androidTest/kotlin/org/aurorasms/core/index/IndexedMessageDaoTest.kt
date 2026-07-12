// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.index.search.RoomMessageIndex
import org.aurorasms.core.index.storage.AuroraIndexDatabase
import org.aurorasms.core.index.storage.IndexCheckpointEntity
import org.aurorasms.core.index.storage.IndexDatabaseFactory
import org.aurorasms.core.index.storage.IndexedMessageEntity
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndexedMessageDaoTest {
    private lateinit var database: AuroraIndexDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = IndexDatabaseFactory.createInMemory(context)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun compoundIdentityCoexistsAndUpsertPreservesLocalRowId() = runBlocking {
        val generation = database.indexSyncDao().startGeneration(nowMillis = 10L)
        commit(
            generation,
            listOf(
                entity(ProviderKind.SMS, providerId = 7L, fingerprintSeed = 'a'),
                entity(ProviderKind.MMS, providerId = 7L, fingerprintSeed = 'b'),
            ),
        )
        val dao = database.indexedMessageDao()
        val smsBefore = requireNotNull(dao.byProviderIdentity(1, 7L))
        val mms = requireNotNull(dao.byProviderIdentity(2, 7L))
        assertNotEquals(smsBefore.rowId, mms.rowId)

        val summary = commit(
            generation,
            listOf(
                entity(
                    ProviderKind.SMS,
                    providerId = 7L,
                    body = "changed body",
                    fingerprintSeed = 'c',
                ),
            ),
            smsCount = 2L,
            mmsCount = 1L,
        )
        val smsAfter = requireNotNull(dao.byProviderIdentity(1, 7L))
        assertEquals(1, summary.updated)
        assertEquals(smsBefore.rowId, smsAfter.rowId)
        assertEquals("changed body", smsAfter.body)
        assertEquals(2L, dao.count())
    }

    @Test
    fun failedBatchCannotAdvanceRowsOrCheckpoints() = runBlocking {
        val generation = database.indexSyncDao().startGeneration(nowMillis = 10L)
        val invalidGeneration = generation + 1L
        val failure = runCatching {
            database.indexedMessageDao().commitScanningBatch(
                generationId = invalidGeneration,
                entities = listOf(entity(ProviderKind.SMS, 9L, generationId = invalidGeneration)),
                smsCheckpoint = checkpoint(invalidGeneration, providerKind = 1, providerId = 9L, count = 1L),
                mmsCheckpoint = checkpoint(invalidGeneration, providerKind = 2, exhausted = true),
                nowMillis = 20L,
                targetBatchSize = 500,
            )
        }
        assertTrue(failure.isFailure)
        assertEquals(0L, database.indexedMessageDao().count())
        database.indexSyncDao().checkpoints(generation).forEach { checkpoint ->
            assertNull(checkpoint.cursorTimestampMillis)
            assertEquals(0L, checkpoint.committedCount)
            assertFalse(checkpoint.exhausted)
        }
    }

    @Test
    fun safeFtsKeysetsAndExactAnchorRemainBounded() = runBlocking {
        val generation = database.indexSyncDao().startGeneration(nowMillis = 10L)
        commit(
            generation,
            (1L..6L).map { providerId ->
                entity(
                    kind = if (providerId % 2L == 0L) ProviderKind.MMS else ProviderKind.SMS,
                    providerId = providerId,
                    timestampMillis = 1_000L,
                    body = "alpha row $providerId",
                    fingerprintSeed = "abcdef"[((providerId - 1L) % 6L).toInt()],
                )
            },
            smsCount = 3L,
            mmsCount = 3L,
        )
        val index = RoomMessageIndex(database)
        val first = (index.search(SearchRequest("alpha", limit = 2)) as SearchResult.Page).page
        assertEquals(2, first.items.size)
        val second = (
            index.search(SearchRequest("ALPHA", limit = 2, cursor = requireNotNull(first.next)))
                as SearchResult.Page
            ).page
        assertEquals(2, second.items.size)
        assertTrue(first.items.map { it.localRowId }.toSet().intersect(second.items.map { it.localRowId }.toSet()).isEmpty())
        assertTrue(first.items.last().localRowId > second.items.first().localRowId)

        val selected = second.items.first()
        val anchor = index.loadAnchor(
            SearchAnchor(
                localRowId = selected.localRowId,
                providerId = selected.providerId,
                providerThreadId = selected.providerThreadId,
            ),
            halfWindow = 1,
        ) as AnchorWindowResult.Found
        assertTrue(anchor.messages.size <= 3)
        assertEquals(selected.localRowId, anchor.highlightedLocalRowId)
        assertEquals(selected.localRowId, anchor.messages[anchor.anchorPosition].localRowId)

        val rebuiltAnchor = index.loadAnchor(
            SearchAnchor(
                localRowId = Long.MAX_VALUE,
                providerId = selected.providerId,
                providerThreadId = selected.providerThreadId,
            ),
            halfWindow = 1,
        ) as AnchorWindowResult.Found
        assertTrue(rebuiltAnchor.reResolvedAfterRebuild)
    }

    @Test
    fun exactAnchorUsesCurrentThreadWhenStableRowMovesThreads() = runBlocking {
        val generation = database.indexSyncDao().startGeneration(nowMillis = 10L)
        commit(
            generation,
            listOf(entity(ProviderKind.SMS, providerId = 20L, timestampMillis = 1_000L)),
        )
        val original = requireNotNull(database.indexedMessageDao().byProviderIdentity(1, 20L))
        commit(
            generation,
            listOf(
                entity(
                    ProviderKind.SMS,
                    providerId = 21L,
                    providerThreadId = 84L,
                    timestampMillis = 1_100L,
                    fingerprintSeed = 'b',
                ),
                entity(
                    ProviderKind.SMS,
                    providerId = 20L,
                    providerThreadId = 84L,
                    timestampMillis = 1_000L,
                    fingerprintSeed = 'c',
                ),
                entity(
                    ProviderKind.SMS,
                    providerId = 19L,
                    providerThreadId = 84L,
                    timestampMillis = 900L,
                    fingerprintSeed = 'd',
                ),
                entity(
                    ProviderKind.SMS,
                    providerId = 18L,
                    providerThreadId = 42L,
                    timestampMillis = 1_050L,
                    fingerprintSeed = 'e',
                ),
            ),
            smsCount = 5L,
        )

        val found = RoomMessageIndex(database).loadAnchor(
            anchor = SearchAnchor(
                localRowId = original.rowId,
                providerId = ProviderMessageId(ProviderKind.SMS, 20L),
                providerThreadId = ProviderThreadId(42L),
            ),
            halfWindow = 1,
        ) as AnchorWindowResult.Found

        assertFalse(found.reResolvedAfterRebuild)
        assertEquals(listOf(21L, 20L, 19L), found.messages.map { it.providerId.value })
        assertTrue(found.messages.all { it.providerThreadId.value == 84L })
    }

    @Test
    fun providerFallbackIgnoresStaleThreadAndKeepsStoredWindowRedacted() = runBlocking {
        val generation = database.indexSyncDao().startGeneration(nowMillis = 10L)
        val privateBody = "private anchor body"
        commit(
            generation,
            listOf(
                entity(
                    ProviderKind.MMS,
                    providerId = 30L,
                    providerThreadId = 84L,
                    body = privateBody,
                    fingerprintSeed = 'f',
                ),
            ),
            mmsCount = 1L,
        )
        val stored = requireNotNull(
            database.indexedMessageDao().anchorWindow(
                localRowId = Long.MAX_VALUE,
                providerKind = 2,
                providerId = 30L,
                halfWindow = 1,
            ),
        )
        assertFalse(stored.toString().contains(privateBody))
        assertFalse(stored.toString().contains("30"))

        val found = RoomMessageIndex(database).loadAnchor(
            anchor = SearchAnchor(
                localRowId = Long.MAX_VALUE,
                providerId = ProviderMessageId(ProviderKind.MMS, 30L),
                providerThreadId = ProviderThreadId(42L),
            ),
            halfWindow = 1,
        ) as AnchorWindowResult.Found
        assertTrue(found.reResolvedAfterRebuild)
        assertEquals(84L, found.messages.single().providerThreadId.value)
        assertFalse(found.toString().contains(privateBody))
        assertFalse(found.toString().contains("30"))
    }

    private suspend fun commit(
        generationId: Long,
        entities: List<IndexedMessageEntity>,
        smsCount: Long = entities.count { it.providerKind == 1 }.toLong(),
        mmsCount: Long = entities.count { it.providerKind == 2 }.toLong(),
    ) = database.indexedMessageDao().commitScanningBatch(
        generationId = generationId,
        entities = entities,
        smsCheckpoint = checkpoint(
            generationId,
            providerKind = 1,
            providerId = entities.lastOrNull { it.providerKind == 1 }?.providerId,
            count = smsCount,
        ),
        mmsCheckpoint = checkpoint(
            generationId,
            providerKind = 2,
            providerId = entities.lastOrNull { it.providerKind == 2 }?.providerId,
            count = mmsCount,
        ),
        nowMillis = 20L,
        targetBatchSize = 500,
    )
}

internal fun checkpoint(
    generationId: Long,
    providerKind: Int,
    providerId: Long? = null,
    count: Long = 0L,
    exhausted: Boolean = false,
) = IndexCheckpointEntity(
    generationId = generationId,
    providerKind = providerKind,
    cursorTimestampMillis = providerId?.let { 1_000L },
    cursorProviderId = providerId,
    exhausted = exhausted,
    committedCount = count,
    updatedAtMillis = 20L,
)

internal fun entity(
    kind: ProviderKind,
    providerId: Long,
    generationId: Long = 1L,
    providerThreadId: Long = 42L,
    timestampMillis: Long = 1_000L,
    body: String = "alpha",
    fingerprintSeed: Char = 'a',
) = IndexedMessageEntity(
    providerKind = if (kind == ProviderKind.SMS) 1 else 2,
    providerId = providerId,
    providerThreadId = providerThreadId,
    timestampMillis = timestampMillis,
    sentTimestampMillis = null,
    direction = 1,
    messageBox = "inbox",
    messageStatus = "complete",
    subscriptionId = null,
    senderAddress = "+15550000000",
    body = body,
    subject = null,
    attachmentCount = 0,
    attachmentTypeSummary = "",
    attachmentTotalBytes = null,
    isRead = false,
    isSeen = false,
    isLocked = false,
    syncFingerprint = fingerprintSeed.toString().repeat(64),
    searchableText = "sender",
    lastSeenGeneration = generationId,
)
