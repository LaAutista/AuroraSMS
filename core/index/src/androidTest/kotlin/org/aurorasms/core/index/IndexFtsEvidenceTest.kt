// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.index.search.RoomMessageIndex
import org.aurorasms.core.index.storage.AuroraIndexDatabase
import org.aurorasms.core.index.storage.IndexDatabaseFactory
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderThreadId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndexFtsEvidenceTest {
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
    fun externalContentFtsTracksInsertSearchableUpdateAndDelete() = runBlocking {
        val generation = database.indexSyncDao().startGeneration(nowMillis = 10L)
        val dao = database.indexedMessageDao()

        dao.upsertBatchPreservingLocalIds(
            listOf(
                entity(
                    kind = ProviderKind.SMS,
                    providerId = 1L,
                    body = "aurora-insert-token",
                    fingerprintSeed = 'a',
                ),
            ),
        )
        assertEquals(1L, ftsMatchCount("aurora"))

        dao.upsertBatchPreservingLocalIds(
            listOf(
                entity(
                    kind = ProviderKind.SMS,
                    providerId = 1L,
                    body = "borealis-update-token",
                    fingerprintSeed = 'b',
                ),
            ),
        )
        assertEquals(0L, ftsMatchCount("aurora"))
        assertEquals(1L, ftsMatchCount("borealis"))

        assertEquals(1, dao.deleteNotSeenInGeneration(generation + 1L))
        assertEquals(0L, ftsMatchCount("borealis"))
        assertEquals(0L, ftsRowCount())
    }

    @Test
    fun unchangedFingerprintGenerationMarkDoesNotChurnFtsTriggers() = runBlocking {
        val dao = database.indexedMessageDao()
        val original = entity(
            kind = ProviderKind.SMS,
            providerId = 2L,
            generationId = 1L,
            body = "stable searchable content",
            fingerprintSeed = 'c',
        )
        dao.upsertBatchPreservingLocalIds(listOf(original))
        val sqlite = database.openHelper.writableDatabase
        val changesBefore = sqlite.singleLong("SELECT total_changes()")

        val summary = dao.upsertBatchPreservingLocalIds(
            listOf(original.copy(lastSeenGeneration = 2L)),
        )

        val changesAfter = sqlite.singleLong("SELECT total_changes()")
        assertEquals(1, summary.unchanged)
        assertEquals(
            "Only the indexed_messages generation mark may change; FTS delete/insert triggers must remain idle",
            1L,
            changesAfter - changesBefore,
        )
        assertEquals(1L, ftsMatchCount("stable"))
        assertEquals(1L, ftsRowCount())
    }

    @Test
    fun threadScopedFtsKeysetRejectsCrossQueryAndCrossThreadCursors() = runBlocking {
        val generation = database.indexSyncDao().startGeneration(nowMillis = 10L)
        database.indexedMessageDao().commitScanningBatch(
            generationId = generation,
            entities = listOf(
                entity(ProviderKind.SMS, 1L, providerThreadId = 42L, timestampMillis = 1_100L),
                entity(ProviderKind.MMS, 2L, providerThreadId = 84L, timestampMillis = 1_075L),
                entity(ProviderKind.SMS, 3L, providerThreadId = 42L, timestampMillis = 1_050L),
                entity(ProviderKind.MMS, 4L, providerThreadId = 84L, timestampMillis = 1_025L),
                entity(ProviderKind.SMS, 5L, providerThreadId = 42L, timestampMillis = 1_000L),
            ),
            smsCheckpoint = checkpoint(generation, providerKind = 1, providerId = 5L, count = 3L),
            mmsCheckpoint = checkpoint(generation, providerKind = 2, providerId = 4L, count = 2L),
            nowMillis = 20L,
            targetBatchSize = 500,
        )
        val index = RoomMessageIndex(database)
        val thread42 = ProviderThreadId(42L)
        val first = requirePage(index.search(SearchRequest("alpha", thread42, limit = 2)))
        val cursor = requireNotNull(first.next)
        val second = requirePage(index.search(SearchRequest("ALPHA", thread42, limit = 2, cursor = cursor)))

        assertEquals(listOf(1_100L, 1_050L), first.items.map { it.timestampMillis })
        assertEquals(listOf(1_000L), second.items.map { it.timestampMillis })
        assertTrue((first.items + second.items).all { it.providerThreadId == thread42 })
        assertTrue(
            first.items.map { it.localRowId }.toSet()
                .intersect(second.items.map { it.localRowId }.toSet())
                .isEmpty(),
        )

        assertEquals(
            SearchResult.Invalid(SearchValidationFailure.CURSOR_MISMATCH),
            index.search(SearchRequest("different", thread42, limit = 2, cursor = cursor)),
        )
        assertEquals(
            SearchResult.Invalid(SearchValidationFailure.CURSOR_MISMATCH),
            index.search(SearchRequest("alpha", ProviderThreadId(84L), limit = 2, cursor = cursor)),
        )
        assertEquals(
            SearchResult.Invalid(SearchValidationFailure.CURSOR_MISMATCH),
            index.search(SearchRequest("alpha", limit = 2, cursor = cursor)),
        )
    }

    private fun ftsMatchCount(expression: String): Long = database.openHelper.writableDatabase.singleLong(
        "SELECT COUNT(*) FROM indexed_messages_fts WHERE indexed_messages_fts MATCH ?",
        arrayOf(expression),
    )

    private fun ftsRowCount(): Long = database.openHelper.writableDatabase.singleLong(
        "SELECT COUNT(*) FROM indexed_messages_fts",
    )

    private fun requirePage(result: SearchResult): SearchPage {
        assertTrue("Expected a search page but was $result", result is SearchResult.Page)
        return (result as SearchResult.Page).page
    }
}

private fun SupportSQLiteDatabase.singleLong(
    sql: String,
    bindArgs: Array<out Any?> = emptyArray(),
): Long = query(sql, bindArgs).use { cursor ->
    check(cursor.moveToFirst()) { "Expected a scalar SQLite result" }
    cursor.getLong(0)
}
