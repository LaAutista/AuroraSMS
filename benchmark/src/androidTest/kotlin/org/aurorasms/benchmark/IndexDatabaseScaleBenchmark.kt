// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.benchmark

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.index.AnchorWindowResult
import org.aurorasms.core.index.SearchAnchor
import org.aurorasms.core.index.SearchRequest
import org.aurorasms.core.index.SearchResult
import org.aurorasms.core.index.search.RoomMessageIndex
import org.aurorasms.core.index.storage.AuroraIndexDatabase
import org.aurorasms.core.index.storage.IndexCheckpointEntity
import org.aurorasms.core.index.storage.IndexDatabaseFactory
import org.aurorasms.core.index.storage.IndexDatabaseOpenResult
import org.aurorasms.core.index.storage.IndexedMessageEntity
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndexDatabaseScaleBenchmark {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments
        get() = InstrumentationRegistry.getArguments()
    private val reporter = RedactedEvidenceReporter()
    private val targetViolations = mutableListOf<String>()

    @After
    fun deleteSyntheticDatabase() {
        context.deleteDatabase(IndexDatabaseFactory.DATABASE_NAME)
    }

    @Test
    fun fixtureAndStatisticsContracts_areDeterministicAndComplete() {
        val requiredCounts = DeterministicIndexFixtures.requiredMessageScales.map(FixtureShape::messageCount)
        assertEquals(listOf(0, 1, 10_000, 100_000, 500_000, 1_000_000), requiredCounts)
        assertEquals(250_000, FixtureShape.SINGLE_THREAD_250K.messageCount)
        assertEquals(1, FixtureShape.SINGLE_THREAD_250K.threadCount)
        assertEquals(20_000, FixtureShape.SHALLOW_THREADS_20K.threadCount)

        val featureRows = (0 until 24).map { ordinal ->
            DeterministicIndexFixtures.entityAt(FixtureShape.TEN_THOUSAND, ordinal, generationId = 7L)
        }
        assertEquals(featureRows[0].providerId, featureRows[1].providerId)
        assertTrue(featureRows[0].providerKind != featureRows[1].providerKind)
        assertEquals(featureRows[0].timestampMillis, featureRows[1].timestampMillis)
        assertTrue(featureRows.any { it.senderAddress == null })
        assertEquals(setOf(0, 1), featureRows.mapNotNull { it.subscriptionId }.toSet())
        assertTrue(featureRows.any { it.attachmentCount > 1 })
        assertTrue(featureRows.any { it.subject == null })
        assertTrue(featureRows.any { it.body.orEmpty().contains("\ud83c\udf0c") })
        assertTrue(featureRows.any { it.body.orEmpty().contains("e\u0301") })
        assertTrue(featureRows.any { it.body.orEmpty().contains("\u0631\u0633\u0627\u0644\u0629") })
        assertTrue(
            featureRows.indices.any {
                !DeterministicIndexFixtures.retainedDuringReconciliation(FixtureShape.TEN_THOUSAND, it)
            },
        )
        assertTrue(
            featureRows.indices.any {
                DeterministicIndexFixtures.retainedDuringReconciliation(FixtureShape.TEN_THOUSAND, it)
            },
        )

        FixtureShape.entries.filter { it.messageCount > 0 }.forEach { shape ->
            val ordinal = shape.messageCount - 1
            val first = DeterministicIndexFixtures.entityAt(shape, ordinal, generationId = 7L)
            val second = DeterministicIndexFixtures.entityAt(shape, ordinal, generationId = 7L)
            assertEquals(first, second)
            assertEquals(shape.threadIdFor(ordinal), first.providerThreadId)
            assertTrue(
                first.body.orEmpty().contains(
                    DeterministicIndexFixtures.COMMON_SEARCH_TOKEN,
                    ignoreCase = true,
                ),
            )
        }

        val summary = BenchmarkStatistics.summarize(2, longArrayOf(50L, 10L, 40L, 20L, 30L))
        assertEquals(30L, summary.p50Nanos)
        assertEquals(50L, summary.p95Nanos)
        assertArrayEquals(
            longArrayOf(0L, 1L, 10_000L, 100_000L, 500_000L, 1_000_000L),
            requiredCounts.map(Int::toLong).toLongArray(),
        )
    }

    /** Ordinary connected tests exercise the real database but stay at a deterministic 10k rows. */
    @Test
    fun boundedTenThousandRowSmoke() = runBlocking {
        runWorkload(
            shape = FixtureShape.TEN_THOUSAND,
            profile = MeasurementProfile.SMOKE,
            includeRebuild = false,
            includeReconciliation = true,
        )
    }

    /**
     * Large and special-dimension runs are deliberately excluded from an unqualified aggregate
     * connected test. See the exact commands in the handoff; both opt-in and a known shape are
     * required, so a typo cannot silently launch a million-row database build.
     */
    @Test
    fun configuredScaleBenchmark_requiresExplicitOptIn() = runBlocking {
        assumeTrue("Large scale benchmark requires auroraBenchmarkFull=true", fullRunRequested())
        val rawShape = arguments.getString(ARGUMENT_SHAPE)
        val shape = requireNotNull(FixtureShape.fromArgument(rawShape)) {
            "Unknown benchmark shape. Use 500000, 1000000, single-thread-250k, or shallow-threads-20k"
        }
        require(shape in EXPLICIT_SHAPES) {
            "The explicit scale entry point only accepts reviewed large or special-dimension shapes"
        }
        runWorkload(
            shape = shape,
            profile = MeasurementProfile.FULL,
            includeRebuild = shape == FixtureShape.FIVE_HUNDRED_THOUSAND ||
                shape == FixtureShape.ONE_MILLION,
            includeReconciliation = true,
        )
    }

    private suspend fun runWorkload(
        shape: FixtureShape,
        profile: MeasurementProfile,
        includeRebuild: Boolean,
        includeReconciliation: Boolean,
    ) {
        targetViolations.clear()
        context.deleteDatabase(IndexDatabaseFactory.DATABASE_NAME)
        val build = buildFreshDatabase(shape)
        var database = build.database
        report(shape, BenchmarkOperation.BUILD, TimingSummary(0, 1, build.elapsedNanos, build.elapsedNanos), database)
        reportBatchSamples(shape, build.batchSamplesNanos, database)

        if (includeRebuild) {
            database.close()
            context.deleteDatabase(IndexDatabaseFactory.DATABASE_NAME)
            val rebuild = buildFreshDatabase(shape)
            database = rebuild.database
            report(
                shape,
                BenchmarkOperation.REBUILD,
                TimingSummary(0, 1, rebuild.elapsedNanos, rebuild.elapsedNanos),
                database,
            )
            reportBatchSamples(shape, rebuild.batchSamplesNanos, database)
        }

        database = measureReopen(shape, profile, database)
        if (shape.messageCount > 0) {
            measureSearchAndNavigation(shape, profile, database)
        }
        if (includeReconciliation) {
            measureDeletionReconciliation(shape, database)
        }
        database.close()
        assertTrue(
            targetViolations.joinToString(separator = "; "),
            targetViolations.isEmpty(),
        )
    }

    private suspend fun buildFreshDatabase(shape: FixtureShape): BuildResult {
        lateinit var database: AuroraIndexDatabase
        lateinit var batchSamples: LongArray
        val started = SystemClock.elapsedRealtimeNanos()
        database = IndexDatabaseFactory.create(context)
        batchSamples = populateCompleteGeneration(database, shape)
        val elapsed = (SystemClock.elapsedRealtimeNanos() - started).coerceAtLeast(1L)
        return BuildResult(database, elapsed, batchSamples)
    }

    private suspend fun populateCompleteGeneration(
        database: AuroraIndexDatabase,
        shape: FixtureShape,
    ): LongArray {
        val generation = database.indexSyncDao().startGeneration(nowMillis = 1L)
        if (shape.messageCount == 0) {
            database.indexSyncDao().putCheckpoint(emptyCheckpoint(generation, SMS_KIND, exhausted = true))
            database.indexSyncDao().putCheckpoint(emptyCheckpoint(generation, MMS_KIND, exhausted = true))
            assertEquals(1, database.indexSyncDao().markVerifying(generation, 2L))
            assertNotNull(
                database.indexSyncDao().finishVerifiedGeneration(
                    generationId = generation,
                    nowMillis = 3L,
                    smsProviderCount = 0L,
                    mmsProviderCount = 0L,
                ),
            )
            return LongArray(0)
        }

        val batchDurations = ArrayList<Long>((shape.messageCount + BATCH_SIZE - 1) / BATCH_SIZE)
        var state = CheckpointState()
        var start = 0
        var batchNumber = 0L
        while (start < shape.messageCount) {
            val rows = DeterministicIndexFixtures.batch(shape, start, generation)
            state = state.consuming(rows)
            val exhausted = start + rows.size == shape.messageCount
            val started = SystemClock.elapsedRealtimeNanos()
            database.indexedMessageDao().commitScanningBatch(
                generationId = generation,
                entities = rows,
                smsCheckpoint = state.toCheckpoint(generation, SMS_KIND, exhausted, batchNumber + 10L),
                mmsCheckpoint = state.toCheckpoint(generation, MMS_KIND, exhausted, batchNumber + 10L),
                nowMillis = batchNumber + 10L,
                targetBatchSize = BATCH_SIZE,
            )
            batchDurations += (SystemClock.elapsedRealtimeNanos() - started).coerceAtLeast(1L)
            start += rows.size
            batchNumber += 1L
        }
        val verifyingAt = batchNumber + 20L
        assertEquals(1, database.indexSyncDao().markVerifying(generation, verifyingAt))
        assertNotNull(
            database.indexSyncDao().finishVerifiedGeneration(
                generationId = generation,
                nowMillis = verifyingAt + 1L,
                smsProviderCount = state.smsCount,
                mmsProviderCount = state.mmsCount,
            ),
        )
        assertEquals(shape.messageCount.toLong(), database.indexedMessageDao().count())
        return batchDurations.toLongArray()
    }

    private suspend fun measureSearchAndNavigation(
        shape: FixtureShape,
        profile: MeasurementProfile,
        database: AuroraIndexDatabase,
    ) {
        val index = RoomMessageIndex(database)
        measureAndReport(shape, BenchmarkOperation.SEARCH_GLOBAL_COMMON, profile, database) {
            val result = index.search(SearchRequest(rawQuery = COMMON_QUERY, limit = PAGE_SIZE))
            check(result is SearchResult.Page && result.page.items.isNotEmpty())
        }
        measureAndReport(shape, BenchmarkOperation.SEARCH_GLOBAL_NO_HIT, profile, database) {
            val result = index.search(SearchRequest(rawQuery = NO_HIT_QUERY, limit = PAGE_SIZE))
            check(result is SearchResult.Page && result.page.items.isEmpty())
        }

        val middleOrdinal = shape.messageCount / 2
        val middleIdentity = DeterministicIndexFixtures.identityAt(shape, middleOrdinal)
        val threadId = ProviderThreadId(middleIdentity.providerThreadId)
        measureAndReport(shape, BenchmarkOperation.SEARCH_THREAD_COMMON, profile, database) {
            val result = index.search(
                SearchRequest(rawQuery = COMMON_QUERY, threadId = threadId, limit = PAGE_SIZE),
            )
            check(result is SearchResult.Page && result.page.items.isNotEmpty())
        }

        val first = index.search(SearchRequest(rawQuery = COMMON_QUERY, limit = PAGE_SIZE)) as SearchResult.Page
        val cursor = requireNotNull(first.page.next) { "Keyset benchmark requires more than one page" }
        measureAndReport(shape, BenchmarkOperation.KEYSET_FORWARD, profile, database) {
            val result = index.search(
                SearchRequest(rawQuery = COMMON_QUERY, limit = PAGE_SIZE, cursor = cursor),
            )
            check(result is SearchResult.Page && result.page.items.isNotEmpty())
        }

        val middleRow = requireNotNull(
            database.indexedMessageDao().byProviderIdentity(
                middleIdentity.providerKind,
                middleIdentity.providerId,
            ),
        )
        measureAndReport(shape, BenchmarkOperation.KEYSET_BACKWARD, profile, database) {
            database.indexedMessageDao().newerThanAnchor(
                providerThreadId = middleRow.providerThreadId,
                anchorTimestampMillis = middleRow.timestampMillis,
                anchorRowId = middleRow.rowId,
                limit = PAGE_SIZE,
            )
        }

        val anchorOrdinals = listOf(0, middleOrdinal, shape.messageCount - 1)
        val operations = listOf(
            BenchmarkOperation.ANCHOR_NEWEST,
            BenchmarkOperation.ANCHOR_MIDDLE,
            BenchmarkOperation.ANCHOR_OLDEST,
        )
        anchorOrdinals.zip(operations).forEach { (ordinal, operation) ->
            val identity = DeterministicIndexFixtures.identityAt(shape, ordinal)
            val row = requireNotNull(
                database.indexedMessageDao().byProviderIdentity(identity.providerKind, identity.providerId),
            )
            val anchor = SearchAnchor(
                localRowId = row.rowId,
                providerId = ProviderMessageId(
                    kind = if (identity.providerKind == SMS_KIND) ProviderKind.SMS else ProviderKind.MMS,
                    value = identity.providerId,
                ),
                providerThreadId = ProviderThreadId(identity.providerThreadId),
            )
            measureAndReport(shape, operation, profile, database) {
                val result = index.loadAnchor(anchor)
                check(result is AnchorWindowResult.Found && result.highlightedLocalRowId == row.rowId)
            }
        }
    }

    private suspend fun measureDeletionReconciliation(
        shape: FixtureShape,
        database: AuroraIndexDatabase,
    ) {
        val generation = database.indexSyncDao().startGeneration(nowMillis = 100_000L)
        var state = CheckpointState()
        var sourceOrdinal = 0
        var batchNumber = 0L
        var committedExhaustion = false
        var expectedRetained = 0L
        val retainedBatch = ArrayList<IndexedMessageEntity>(BATCH_SIZE)
        while (sourceOrdinal < shape.messageCount) {
            val fixtureRow = DeterministicIndexFixtures.rowAt(shape, sourceOrdinal, generation)
            if (fixtureRow.retainedDuringReconciliation) {
                retainedBatch += fixtureRow.entity
                expectedRetained += 1L
            }
            sourceOrdinal += 1
            val sourceExhausted = sourceOrdinal == shape.messageCount
            if (retainedBatch.size == BATCH_SIZE || (sourceExhausted && retainedBatch.isNotEmpty())) {
                state = state.consuming(retainedBatch)
                database.indexedMessageDao().commitScanningBatch(
                    generationId = generation,
                    entities = retainedBatch.toList(),
                    smsCheckpoint = state.toCheckpoint(
                        generation,
                        SMS_KIND,
                        sourceExhausted,
                        100_010L + batchNumber,
                    ),
                    mmsCheckpoint = state.toCheckpoint(
                        generation,
                        MMS_KIND,
                        sourceExhausted,
                        100_010L + batchNumber,
                    ),
                    nowMillis = 100_010L + batchNumber,
                    targetBatchSize = BATCH_SIZE,
                )
                retainedBatch.clear()
                batchNumber += 1L
                committedExhaustion = sourceExhausted
            }
        }
        if (!committedExhaustion) {
            // The empty shape, or a deleted final source row following an exactly full retained
            // batch, has no content row with which to atomically carry exhaustion. Persisting only
            // the terminal marker after every retained row is durable cannot skip index content.
            database.indexSyncDao().putCheckpoint(
                state.toCheckpoint(generation, SMS_KIND, exhausted = true, nowMillis = 150_000L),
            )
            database.indexSyncDao().putCheckpoint(
                state.toCheckpoint(generation, MMS_KIND, exhausted = true, nowMillis = 150_000L),
            )
        }
        val verifyingAt = 200_000L + batchNumber
        assertEquals(1, database.indexSyncDao().markVerifying(generation, verifyingAt))
        val timing = BenchmarkStatistics.measure(0, 1) {
            assertNotNull(
                database.indexSyncDao().finishVerifiedGeneration(
                    generationId = generation,
                    nowMillis = verifyingAt + 1L,
                    smsProviderCount = state.smsCount,
                    mmsProviderCount = state.mmsCount,
                ),
            )
        }
        report(shape, BenchmarkOperation.RECONCILE_DELETIONS, timing, database)
        assertEquals(expectedRetained, database.indexedMessageDao().count())
    }

    private suspend fun measureReopen(
        shape: FixtureShape,
        profile: MeasurementProfile,
        initial: AuroraIndexDatabase,
    ): AuroraIndexDatabase {
        var database = initial
        repeat(profile.warmups) {
            database.close()
            database = openVerifiedIndex()
            assertNotNull(database.indexSyncDao().latestGeneration())
        }
        val samples = LongArray(profile.samples) {
            database.close()
            val started = SystemClock.elapsedRealtimeNanos()
            database = openVerifiedIndex()
            assertNotNull(database.indexSyncDao().latestGeneration())
            (SystemClock.elapsedRealtimeNanos() - started).coerceAtLeast(1L)
        }
        report(
            shape,
            BenchmarkOperation.REOPEN_CHECKPOINT,
            BenchmarkStatistics.summarize(profile.warmups, samples),
            database,
        )
        return database
    }

    private fun openVerifiedIndex(): AuroraIndexDatabase {
        val result = IndexDatabaseFactory.open(context)
        check(result is IndexDatabaseOpenResult.Opened && !result.recovered) {
            "Benchmark index reopen did not preserve its verified database"
        }
        return result.database
    }

    private suspend fun measureAndReport(
        shape: FixtureShape,
        operation: BenchmarkOperation,
        profile: MeasurementProfile,
        database: AuroraIndexDatabase,
        block: suspend () -> Unit,
    ) {
        val timing = BenchmarkStatistics.measure(profile.warmups, profile.samples, block)
        report(shape, operation, timing, database)
        if (
            profile == MeasurementProfile.FULL &&
            shape.messageCount >= 500_000 &&
            operation == BenchmarkOperation.SEARCH_GLOBAL_COMMON
        ) {
            if (timing.p50Millis > 120.0 || timing.p95Millis > 220.0) {
                targetViolations +=
                    "search target missed: p50=${timing.p50Millis} ms, p95=${timing.p95Millis} ms"
            }
        }
        if (
            profile == MeasurementProfile.FULL &&
            shape.messageCount >= 500_000 &&
            operation in ANCHOR_OPERATIONS
        ) {
            if (timing.p50Millis > 350.0 || timing.p95Millis > 650.0) {
                targetViolations +=
                    "${operation.reportName} target missed: " +
                    "p50=${timing.p50Millis} ms, p95=${timing.p95Millis} ms"
            }
        }
    }

    private fun reportBatchSamples(
        shape: FixtureShape,
        samples: LongArray,
        database: AuroraIndexDatabase,
    ) {
        if (samples.isEmpty()) return
        report(
            shape,
            BenchmarkOperation.COMMITTED_BATCH_500,
            BenchmarkStatistics.summarize(0, samples),
            database,
        )
    }

    private fun report(
        shape: FixtureShape,
        operation: BenchmarkOperation,
        timing: TimingSummary,
        database: AuroraIndexDatabase,
    ) {
        check(database.isOpen) { "Benchmark evidence requires an open database" }
        reporter.report(
            BenchmarkEvidence(
                operation = operation,
                shape = shape,
                timing = timing,
                databaseSizeBytes = databaseSizeBytes(),
                commit = BenchmarkEvidence.sanitizedCommit(arguments.getString(ARGUMENT_COMMIT)),
            ),
        )
    }

    private fun databaseSizeBytes(): Long {
        val database = context.getDatabasePath(IndexDatabaseFactory.DATABASE_NAME)
        return listOf(
            database,
            java.io.File(database.path + "-wal"),
            java.io.File(database.path + "-shm"),
        ).sumOf { file -> if (file.isFile) file.length() else 0L }
    }

    private fun fullRunRequested(): Boolean = arguments.getString(ARGUMENT_FULL) == "true"

    private fun emptyCheckpoint(
        generationId: Long,
        providerKind: Int,
        exhausted: Boolean,
    ) = IndexCheckpointEntity(
        generationId = generationId,
        providerKind = providerKind,
        cursorTimestampMillis = null,
        cursorProviderId = null,
        exhausted = exhausted,
        committedCount = 0L,
        updatedAtMillis = 2L,
    )

    private data class BuildResult(
        val database: AuroraIndexDatabase,
        val elapsedNanos: Long,
        val batchSamplesNanos: LongArray,
    )

    private data class CheckpointState(
        val smsTimestampMillis: Long? = null,
        val smsProviderId: Long? = null,
        val smsCount: Long = 0L,
        val mmsTimestampMillis: Long? = null,
        val mmsProviderId: Long? = null,
        val mmsCount: Long = 0L,
    ) {
        fun consuming(rows: List<IndexedMessageEntity>): CheckpointState {
            val lastSms = rows.lastOrNull { it.providerKind == SMS_KIND }
            val lastMms = rows.lastOrNull { it.providerKind == MMS_KIND }
            return copy(
                smsTimestampMillis = lastSms?.timestampMillis ?: smsTimestampMillis,
                smsProviderId = lastSms?.providerId ?: smsProviderId,
                smsCount = smsCount + rows.count { it.providerKind == SMS_KIND },
                mmsTimestampMillis = lastMms?.timestampMillis ?: mmsTimestampMillis,
                mmsProviderId = lastMms?.providerId ?: mmsProviderId,
                mmsCount = mmsCount + rows.count { it.providerKind == MMS_KIND },
            )
        }

        fun toCheckpoint(
            generationId: Long,
            providerKind: Int,
            exhausted: Boolean,
            nowMillis: Long,
        ): IndexCheckpointEntity = if (providerKind == SMS_KIND) {
            IndexCheckpointEntity(
                generationId,
                providerKind,
                smsTimestampMillis,
                smsProviderId,
                exhausted,
                smsCount,
                nowMillis,
            )
        } else {
            IndexCheckpointEntity(
                generationId,
                providerKind,
                mmsTimestampMillis,
                mmsProviderId,
                exhausted,
                mmsCount,
                nowMillis,
            )
        }
    }

    private enum class MeasurementProfile(val warmups: Int, val samples: Int) {
        SMOKE(warmups = 1, samples = 3),
        FULL(warmups = 3, samples = 15),
    }

    companion object {
        private const val ARGUMENT_FULL = "auroraBenchmarkFull"
        private const val ARGUMENT_SHAPE = "auroraBenchmarkShape"
        private const val ARGUMENT_COMMIT = "auroraBenchmarkCommit"
        private const val SMS_KIND = 1
        private const val MMS_KIND = 2
        private const val BATCH_SIZE = DeterministicIndexFixtures.BATCH_SIZE
        private const val PAGE_SIZE = 50
        private const val COMMON_QUERY = DeterministicIndexFixtures.COMMON_SEARCH_TOKEN
        private const val NO_HIT_QUERY = "syntheticabsenttoken"

        private val EXPLICIT_SHAPES = setOf(
            FixtureShape.FIVE_HUNDRED_THOUSAND,
            FixtureShape.ONE_MILLION,
            FixtureShape.SINGLE_THREAD_250K,
            FixtureShape.SHALLOW_THREADS_20K,
        )
        private val ANCHOR_OPERATIONS = setOf(
            BenchmarkOperation.ANCHOR_NEWEST,
            BenchmarkOperation.ANCHOR_MIDDLE,
            BenchmarkOperation.ANCHOR_OLDEST,
        )
    }
}
