// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.index.search.RoomMessageIndex
import org.aurorasms.core.index.storage.IndexCheckpointEntity
import org.aurorasms.core.index.storage.IndexDatabaseFactory
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndexResumeAndReconcileTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().context

    @Before
    fun startClean() {
        context.deleteDatabase(IndexDatabaseFactory.DATABASE_NAME)
    }

    @After
    fun deleteDatabase() {
        context.deleteDatabase(IndexDatabaseFactory.DATABASE_NAME)
    }

    @Test
    fun checkpointSurvivesReopenAndPendingVerificationCannotDelete() = runBlocking {
        val first = IndexDatabaseFactory.create(context)
        val generation = first.indexSyncDao().startGeneration(10L)
        first.indexedMessageDao().commitScanningBatch(
            generationId = generation,
            entities = listOf(entity(org.aurorasms.core.model.ProviderKind.SMS, 11L)),
            smsCheckpoint = checkpoint(generation, 1, providerId = 11L, count = 1L, exhausted = true),
            mmsCheckpoint = checkpoint(generation, 2, exhausted = true),
            nowMillis = 20L,
            targetBatchSize = 500,
        )
        first.close()

        val reopened = IndexDatabaseFactory.create(context)
        assertEquals(11L, reopened.indexSyncDao().checkpoints(generation).first().cursorProviderId)
        assertEquals(1L, reopened.indexedMessageDao().count())
        assertEquals(1, reopened.indexSyncDao().markVerifying(generation, 30L))
        assertEquals(1, reopened.indexSyncDao().markPendingChanges(generation, 31L))
        assertNull(reopened.indexSyncDao().finishVerifiedGeneration(generation, 40L, 1L, 0L))
        assertEquals(1L, reopened.indexedMessageDao().count())
        val partialCoverage = RoomMessageIndex(reopened).coverage()
        assertFalse(partialCoverage.verifiedComplete)
        assertEquals(1L, partialCoverage.indexedMessageCount)
        assertEquals(1L, partialCoverage.generationCommittedCount)
        assertEquals(1L, partialCoverage.smsCheckpointCommittedCount)
        assertEquals(0L, partialCoverage.mmsCheckpointCommittedCount)
        reopened.close()
    }

    @Test
    fun cleanVerifiedGenerationDeletesOnlyRowsOutsideGeneration() = runBlocking {
        val database = IndexDatabaseFactory.create(context)
        val firstGeneration = database.indexSyncDao().startGeneration(10L)
        database.indexedMessageDao().commitScanningBatch(
            firstGeneration,
            listOf(entity(org.aurorasms.core.model.ProviderKind.SMS, 1L)),
            checkpoint(firstGeneration, 1, 1L, 1L, true),
            checkpoint(firstGeneration, 2, exhausted = true),
            20L,
            500,
        )
        database.indexSyncDao().markVerifying(firstGeneration, 21L)
        requireNotNull(
            database.indexSyncDao().finishVerifiedGeneration(firstGeneration, 22L, 1L, 0L),
        )

        val secondGeneration = database.indexSyncDao().startGeneration(30L)
        database.indexedMessageDao().commitScanningBatch(
            secondGeneration,
            listOf(entity(org.aurorasms.core.model.ProviderKind.MMS, 2L, generationId = secondGeneration)),
            checkpoint(secondGeneration, 1, exhausted = true),
            checkpoint(secondGeneration, 2, 2L, 1L, true),
            40L,
            500,
        )
        database.indexSyncDao().markVerifying(secondGeneration, 41L)
        val summary = requireNotNull(
            database.indexSyncDao().finishVerifiedGeneration(secondGeneration, 42L, 0L, 1L),
        )
        assertEquals(1, summary.deletedRows)
        assertEquals(1L, summary.retainedRows)
        val completeCoverage = RoomMessageIndex(database).coverage()
        assertTrue(completeCoverage.verifiedComplete)
        assertEquals(1L, completeCoverage.indexedMessageCount)
        assertEquals(1L, completeCoverage.generationCommittedCount)
        assertEquals(0L, completeCoverage.smsCheckpointCommittedCount)
        assertEquals(1L, completeCoverage.mmsCheckpointCommittedCount)
        database.close()
    }
}
