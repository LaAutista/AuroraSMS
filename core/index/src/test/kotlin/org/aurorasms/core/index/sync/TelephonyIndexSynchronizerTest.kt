// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.sync

import android.database.sqlite.SQLiteException
import androidx.room.InvalidationTracker
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.aurorasms.core.index.IndexFailureCode
import org.aurorasms.core.index.storage.AuroraIndexDatabase
import org.aurorasms.core.index.storage.ConversationDao
import org.aurorasms.core.index.storage.GenerationStateCode
import org.aurorasms.core.index.storage.IndexCheckpointEntity
import org.aurorasms.core.index.storage.IndexGenerationEntity
import org.aurorasms.core.index.storage.IndexSyncDao
import org.aurorasms.core.index.storage.IndexUpsertSummary
import org.aurorasms.core.index.storage.IndexedConversationEntity
import org.aurorasms.core.index.storage.IndexedConversationParticipantEntity
import org.aurorasms.core.index.storage.IndexedMessageDao
import org.aurorasms.core.index.storage.IndexedMessageEntity
import org.aurorasms.core.index.storage.ProviderKindCode
import org.aurorasms.core.index.storage.StoredMessageIdentity
import org.aurorasms.core.index.storage.StoredSearchOrder
import org.aurorasms.core.index.storage.StoredSyncState
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDeliveryFingerprint
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.MessageSyncFingerprint
import org.aurorasms.core.model.MmsAttachmentSummary
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.telephony.DecodedIncomingMmsRecord
import org.aurorasms.core.telephony.DefaultSmsRoleState
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
import org.junit.Assert.fail
import org.junit.Test

class TelephonyIndexSynchronizerTest {
    @Test
    fun `paused generation resumes from its durable cursor when no change is pending`() = runTest {
        val database = FakeIndexDatabase()
        val generationId = database.sync.startGeneration(10L)
        val cursor = cursor(timestamp = 50L, id = 5L)
        database.sync.putCheckpoint(
            checkpoint(generationId, ProviderKindCode.SMS, cursor, committedCount = 5L),
        )
        database.sync.stopActiveGeneration(
            generationId = generationId,
            terminalState = GenerationStateCode.PAUSED,
            failureCode = null,
            nowMillis = 20L,
        )
        val sms = SyncTestSmsSource(
            messages = listOf(sms(id = 4L, timestamp = 40L)),
            countResult = ProviderAccessResult.Success(6L),
        )

        val outcome = synchronizer(database, sms, SyncTestMmsSource()).synchronize()

        assertTrue(outcome is IndexSyncOutcome.Complete)
        assertEquals(1, database.sync.allGenerations().size)
        assertEquals(generationId, database.sync.latestGeneration()?.generationId)
        assertEquals(cursor, sms.readRequests.first().before)
    }

    @Test
    fun `crash after final checkpoint resumes by entering verification without another scan page`() = runTest {
        val database = FakeIndexDatabase()
        val generationId = database.sync.startGeneration(10L)
        database.sync.putCheckpoint(
            checkpoint(generationId, ProviderKindCode.SMS, exhausted = true),
        )
        database.sync.putCheckpoint(
            checkpoint(generationId, ProviderKindCode.MMS, exhausted = true),
        )
        val sms = SyncTestSmsSource()
        val mms = SyncTestMmsSource()

        val outcome = synchronizer(database, sms, mms).synchronize()

        assertTrue(outcome is IndexSyncOutcome.Complete)
        assertEquals(1, sms.readRequests.size)
        assertEquals(1, mms.readRequests.size)
        assertEquals(null, sms.readRequests.single().before)
        assertEquals(GenerationStateCode.COMPLETE, database.sync.generationById(generationId)?.state)
    }

    @Test
    fun `full scan verifies both heads and completes generation`() = runTest {
        val database = FakeIndexDatabase()
        val sms = SyncTestSmsSource(messages = listOf(sms(id = 2, timestamp = 20)))
        val mms = SyncTestMmsSource(messages = listOf(mms(id = 1, timestamp = 10)))

        val outcome = synchronizer(database, sms, mms).synchronize()

        assertTrue(outcome is IndexSyncOutcome.Complete)
        assertEquals(2L, database.messages.count())
        assertEquals(GenerationStateCode.COMPLETE, database.sync.latestGeneration()?.state)
        assertEquals(2, sms.readRequests.size)
        assertEquals(2, mms.readRequests.size)
        assertTrue((outcome as IndexSyncOutcome.Complete).coverage.verifiedComplete)
    }

    @Test
    fun `clean startup checks only bounded heads and retains complete generation`() = runTest {
        val database = FakeIndexDatabase()
        val originalSms = SyncTestSmsSource(messages = listOf(sms(id = 1L, timestamp = 10L)))
        assertTrue(
            synchronizer(database, originalSms, SyncTestMmsSource()).synchronize() is
                IndexSyncOutcome.Complete,
        )
        val generationId = requireNotNull(database.sync.latestGeneration()).generationId
        val startupSms = SyncTestSmsSource(messages = listOf(sms(id = 1L, timestamp = 10L)))
        val startupMms = SyncTestMmsSource()

        val outcome = synchronizer(database, startupSms, startupMms).synchronize()

        assertTrue(outcome is IndexSyncOutcome.Complete)
        assertEquals(1, startupSms.readRequests.size)
        assertEquals(1, startupMms.readRequests.size)
        assertEquals(1, database.sync.allGenerations().size)
        assertEquals(generationId, database.sync.latestGeneration()?.generationId)
    }

    @Test
    fun `owned leading insert commits bounded head without full generation`() = runTest {
        val database = FakeIndexDatabase()
        assertTrue(
            synchronizer(
                database,
                SyncTestSmsSource(messages = listOf(sms(id = 1L, timestamp = 10L))),
                SyncTestMmsSource(),
            ).synchronize() is IndexSyncOutcome.Complete,
        )
        val generationId = requireNotNull(database.sync.latestGeneration()).generationId
        val sms = SyncTestSmsSource(
            messages = listOf(sms(id = 2L, timestamp = 20L), sms(id = 1L, timestamp = 10L)),
        )
        val synchronizer = synchronizer(database, sms, SyncTestMmsSource())
        synchronizer.markPendingChanges()

        val outcome = synchronizer.reconcile(setOf(IndexSignal.INCOMING_INSERT))

        assertTrue(outcome is IndexSyncOutcome.Complete)
        assertEquals(1, sms.readRequests.size)
        assertEquals(1, database.sync.allGenerations().size)
        assertEquals(generationId, database.sync.latestGeneration()?.generationId)
        assertEquals(2L, database.messages.count())
        assertFalse(requireNotNull(database.sync.latestGeneration()).pendingChanges)
    }

    @Test
    fun `dirty unchanged head remains ambiguous and starts full generation`() = runTest {
        val database = FakeIndexDatabase()
        assertTrue(
            synchronizer(
                database,
                SyncTestSmsSource(messages = listOf(sms(id = 1L, timestamp = 10L))),
                SyncTestMmsSource(),
            ).synchronize() is IndexSyncOutcome.Complete,
        )
        val sms = SyncTestSmsSource(messages = listOf(sms(id = 1L, timestamp = 10L)))
        val mms = SyncTestMmsSource()
        val synchronizer = synchronizer(database, sms, mms)
        synchronizer.markPendingChanges()

        val outcome = synchronizer.reconcile(setOf(IndexSignal.EXTERNAL_PROVIDER_CHANGE))

        assertTrue(outcome is IndexSyncOutcome.Complete)
        assertEquals(2, database.sync.allGenerations().size)
        assertEquals(3, sms.readRequests.size)
        assertEquals(3, mms.readRequests.size)
    }

    @Test
    fun `owned insert coalesced with generic observer signal starts full generation`() = runTest {
        val database = FakeIndexDatabase()
        assertTrue(
            synchronizer(
                database,
                SyncTestSmsSource(messages = listOf(sms(id = 1L, timestamp = 10L))),
                SyncTestMmsSource(),
            ).synchronize() is IndexSyncOutcome.Complete,
        )
        val sms = SyncTestSmsSource(
            messages = listOf(sms(id = 2L, timestamp = 20L), sms(id = 1L, timestamp = 10L)),
        )
        val synchronizer = synchronizer(database, sms, SyncTestMmsSource())
        synchronizer.markPendingChanges()

        val outcome = synchronizer.reconcile(
            setOf(IndexSignal.INCOMING_INSERT, IndexSignal.CONTENT_OBSERVER_CHANGE),
        )

        assertTrue(outcome is IndexSyncOutcome.Complete)
        assertEquals(2, database.sync.allGenerations().size)
    }

    @Test
    fun `nonrecoverable cursor failure starts a new generation`() = runTest {
        val database = FakeIndexDatabase()
        val failedGeneration = database.sync.startGeneration(10L)
        database.sync.stopActiveGeneration(
            generationId = failedGeneration,
            terminalState = GenerationStateCode.FAILED,
            failureCode = 6,
            nowMillis = 20L,
        )

        val outcome = synchronizer(database, SyncTestSmsSource(), SyncTestMmsSource()).synchronize()

        assertTrue(outcome is IndexSyncOutcome.Complete)
        assertEquals(2, database.sync.allGenerations().size)
        assertEquals(GenerationStateCode.FAILED, database.sync.allGenerations().first().state)
        assertTrue(database.sync.latestGeneration()?.generationId != failedGeneration)
    }

    @Test
    fun `resume skips exhausted source during scan and retains committed cursor`() = runTest {
        val database = FakeIndexDatabase()
        val generationId = database.sync.startGeneration(10L)
        val committedSms = checkpoint(
            generationId = generationId,
            providerKind = ProviderKindCode.SMS,
            cursor = cursor(timestamp = 50, id = 5),
            exhausted = true,
            committedCount = 5L,
        )
        database.sync.putCheckpoint(committedSms)
        val sms = SyncTestSmsSource(messages = emptyList(), countResult = ProviderAccessResult.Success(5L))
        val mms = SyncTestMmsSource(messages = listOf(mms(id = 4, timestamp = 40)))

        val outcome = synchronizer(database, sms, mms).synchronize()

        assertTrue(outcome is IndexSyncOutcome.Complete)
        // The only SMS read is the bounded verification head read. Scanning
        // does not reopen a source whose durable checkpoint says exhausted.
        assertEquals(1, sms.readRequests.size)
        assertEquals(2, mms.readRequests.size)
        assertEquals(null, sms.readRequests.single().before)
        val retainedSms = database.sync.checkpoints(generationId)
            .single { it.providerKind == ProviderKindCode.SMS }
        assertEquals(committedSms.cursorProviderId, retainedSms.cursorProviderId)
        assertEquals(GenerationStateCode.COMPLETE, database.sync.generationById(generationId)?.state)
    }

    @Test
    fun `dirty verification pauses without deleting and immediately rescans`() = runTest {
        val database = FakeIndexDatabase()
        database.messages.seed(IndexProjectionMapper.fromSms(sms(id = 99, timestamp = 1), generationId = 99L))
        var dirtiedFirstGeneration = false
        database.messages.afterCommit = { generationId ->
            if (!dirtiedFirstGeneration) {
                dirtiedFirstGeneration = true
                database.sync.markPendingChanges(generationId, 1_000L)
            }
        }
        val sms = SyncTestSmsSource(messages = listOf(sms(id = 1, timestamp = 10)))
        val mms = SyncTestMmsSource()

        val outcome = synchronizer(database, sms, mms).synchronize()

        assertTrue(outcome is IndexSyncOutcome.Complete)
        val generations = database.sync.allGenerations()
        assertEquals(2, generations.size)
        assertEquals(GenerationStateCode.PAUSED, generations[0].state)
        assertEquals(GenerationStateCode.COMPLETE, generations[1].state)
        assertEquals(listOf(generations[1].generationId), database.sync.staleDeletionAttempts)
        assertFalse(database.messages.contains(ProviderKindCode.SMS, 99L))
    }

    @Test
    fun `missing default role pauses before provider or database work`() = runTest {
        val database = FakeIndexDatabase()
        val sms = SyncTestSmsSource(messages = listOf(sms(id = 1, timestamp = 1)))
        val mms = SyncTestMmsSource()

        val outcome = synchronizer(database, sms, mms, roleHeld = false).synchronize()

        assertEquals(IndexSyncOutcome.Paused(IndexFailureCode.ROLE_REQUIRED), outcome)
        assertTrue(sms.readRequests.isEmpty())
        assertTrue(mms.readRequests.isEmpty())
        assertTrue(database.sync.allGenerations().isEmpty())
    }

    @Test
    fun `provider role and permission failures retain typed outcomes`() = runTest {
        val roleDatabase = FakeIndexDatabase()
        val roleOutcome = synchronizer(
            roleDatabase,
            SyncTestSmsSource(readResult = ProviderAccessResult.RoleRequired),
            SyncTestMmsSource(),
        ).synchronize()
        assertEquals(IndexSyncOutcome.Paused(IndexFailureCode.ROLE_REQUIRED), roleOutcome)
        assertEquals(GenerationStateCode.PAUSED, roleDatabase.sync.latestGeneration()?.state)

        val permissionDatabase = FakeIndexDatabase()
        val permissionOutcome = synchronizer(
            permissionDatabase,
            SyncTestSmsSource(readResult = ProviderAccessResult.PermissionDenied),
            SyncTestMmsSource(),
        ).synchronize()
        assertEquals(IndexSyncOutcome.Failed(IndexFailureCode.PERMISSION_DENIED), permissionOutcome)
        assertEquals(GenerationStateCode.FAILED, permissionDatabase.sync.latestGeneration()?.state)
    }

    @Test
    fun `nonadvancing provider cursor fails without moving checkpoint`() = runTest {
        val database = FakeIndexDatabase()
        val generationId = database.sync.startGeneration(10L)
        val before = cursor(timestamp = 100, id = 10)
        val original = checkpoint(generationId, ProviderKindCode.SMS, before)
        database.sync.putCheckpoint(original)
        val sms = SyncTestSmsSource(
            readBlock = {
                ProviderAccessResult.Success(
                    ProviderPage(items = emptyList(), next = before, exhausted = false),
                )
            },
        )

        val outcome = synchronizer(database, sms, SyncTestMmsSource()).synchronize()

        assertEquals(IndexSyncOutcome.Failed(IndexFailureCode.NON_ADVANCING_CURSOR), outcome)
        val retained = database.sync.checkpoints(generationId)
            .single { it.providerKind == ProviderKindCode.SMS }
        assertEquals(original.cursorTimestampMillis, retained.cursorTimestampMillis)
        assertEquals(original.cursorProviderId, retained.cursorProviderId)
        assertEquals(0L, retained.committedCount)
    }

    @Test
    fun `failed content transaction cannot advance either checkpoint`() = runTest {
        val database = FakeIndexDatabase()
        database.messages.failNextCommit = true
        val sms = SyncTestSmsSource(messages = listOf(sms(id = 7, timestamp = 70)))

        val outcome = synchronizer(database, sms, SyncTestMmsSource()).synchronize()

        assertEquals(IndexSyncOutcome.Failed(IndexFailureCode.STORAGE_UNAVAILABLE), outcome)
        val generation = requireNotNull(database.sync.latestGeneration())
        val checkpoints = database.sync.checkpoints(generation.generationId)
        assertTrue(checkpoints.all { it.cursorProviderId == null && it.committedCount == 0L })
        assertEquals(0L, database.messages.count())
    }

    @Test
    fun `cancellation propagates after best effort generation pause`() = runTest {
        val database = FakeIndexDatabase()
        val sms = SyncTestSmsSource(
            readBlock = { throw CancellationException("cancel provider read") },
        )

        try {
            synchronizer(database, sms, SyncTestMmsSource()).synchronize()
            fail("Expected provider cancellation to propagate")
        } catch (_: CancellationException) {
            // Expected: cancellation is control flow, never a provider error.
        }

        assertEquals(GenerationStateCode.PAUSED, database.sync.latestGeneration()?.state)
    }

    @Test
    fun `slow commits shrink to but never below minimum batch size`() = runTest {
        val database = FakeIndexDatabase()
        val sms = SyncTestSmsSource(messages = smsRange(1_001))
        var monotonic = 0L

        val outcome = synchronizer(
            database = database,
            sms = sms,
            mms = SyncTestMmsSource(),
            monotonicMillis = {
                val value = monotonic
                monotonic += 100L
                value
            },
        ).synchronize()

        assertTrue(outcome is IndexSyncOutcome.Complete)
        assertEquals(listOf(500, 250, 125, 100, 100), database.messages.committedBatchTargets)
        assertTrue(database.messages.committedBatchTargets.all { it in 100..500 })
    }

    @Test
    fun `fast commits grow by fifty but never exceed maximum batch size`() = runTest {
        val database = FakeIndexDatabase()
        val generationId = database.sync.startGeneration(10L)
        database.sync.forceTargetBatchSize(generationId, 100)

        val outcome = synchronizer(
            database = database,
            sms = SyncTestSmsSource(messages = smsRange(1_000)),
            mms = SyncTestMmsSource(),
            monotonicMillis = { 10L },
        ).synchronize()

        assertTrue(outcome is IndexSyncOutcome.Complete)
        assertEquals(listOf(100, 150, 200, 250, 300), database.messages.committedBatchTargets)
        assertTrue(database.messages.committedBatchTargets.all { it in 100..500 })
    }

    @Test
    fun `provider read permission is rechecked between bounded scan batches`() = runTest {
        val database = FakeIndexDatabase()
        val sms = SyncTestSmsSource(messages = smsRange(600))
        var permitChecks = 0
        val synchronizer = synchronizer(
            database = database,
            sms = sms,
            mms = SyncTestMmsSource(),
            isProviderReadPermitted = { ++permitChecks < 3 },
        )

        val outcome = synchronizer.synchronize()
        assertEquals(3, permitChecks)
        val readsBeforePause = sms.readRequests.size
        assertTrue(readsBeforePause > 0)
        assertEquals(500L, database.messages.count())
        assertTrue(outcome is IndexSyncOutcome.Pending)
        assertEquals(GenerationStateCode.SCANNING, database.sync.latestGeneration()?.state)

        val resumed = synchronizer(
            database = database,
            sms = sms,
            mms = SyncTestMmsSource(),
        ).synchronize()
        assertTrue(resumed is IndexSyncOutcome.Complete)
        assertTrue(sms.readRequests.size > readsBeforePause)
        assertEquals(600L, database.messages.count())
    }

    @Test
    fun `background deferral releases mutex for immediate role loss pause`() = runTest {
        val database = FakeIndexDatabase()
        val generationId = database.sync.startGeneration(10L)
        val synchronizer = synchronizer(
            database = database,
            sms = SyncTestSmsSource(),
            mms = SyncTestMmsSource(),
            isProviderReadPermitted = { false },
        )

        assertTrue(synchronizer.synchronize() is IndexSyncOutcome.Pending)
        synchronizer.pauseForRoleLoss()

        assertEquals(GenerationStateCode.PAUSED, database.sync.generationById(generationId)?.state)
    }

    private fun synchronizer(
        database: FakeIndexDatabase,
        sms: SmsProviderDataSource,
        mms: MmsProviderDataSource,
        roleHeld: Boolean = true,
        monotonicMillis: () -> Long = { 10L },
        isProviderReadPermitted: () -> Boolean = { true },
    ): TelephonyIndexSynchronizer = TelephonyIndexSynchronizer(
        database = database,
        smsSource = sms,
        mmsSource = mms,
        roleState = TestRoleState(roleHeld),
        ioDispatcher = Dispatchers.Unconfined,
        wallClockMillis = { 1_000L },
        monotonicMillis = monotonicMillis,
        isProviderReadPermitted = isProviderReadPermitted,
    )
}

private class TestRoleState(
    private val held: Boolean,
) : DefaultSmsRoleState {
    override fun isRoleAvailable(): Boolean = true

    override fun isRoleHeld(): Boolean = held
}

private class SyncTestSmsSource(
    private val messages: List<SmsProviderMessage> = emptyList(),
    private val countResult: ProviderAccessResult<Long>? = null,
    private val readResult: ProviderAccessResult<ProviderPage<SmsProviderMessage>>? = null,
    private val readBlock: (suspend (ProviderPageRequest) -> ProviderAccessResult<ProviderPage<SmsProviderMessage>>)? = null,
) : SmsProviderDataSource {
    val readRequests = mutableListOf<ProviderPageRequest>()

    override suspend fun count(): ProviderAccessResult<Long> =
        countResult ?: ProviderAccessResult.Success(messages.size.toLong())

    override suspend fun readPage(
        request: ProviderPageRequest,
    ): ProviderAccessResult<ProviderPage<SmsProviderMessage>> {
        readRequests += request
        return readBlock?.invoke(request) ?: readResult ?: ProviderAccessResult.Success(page(messages, request) { it.cursor() })
    }

    override suspend fun insertIncoming(message: IncomingSmsRecord): ProviderAccessResult<ProviderStoredMessage> =
        ProviderAccessResult.Unsupported("test")

    override suspend fun markIncomingHandled(
        deliveryFingerprint: MessageDeliveryFingerprint,
        providerId: ProviderMessageId,
        conversationId: ConversationId,
    ): ProviderAccessResult<Unit> = ProviderAccessResult.Unsupported("test")

    override suspend fun insertOutgoing(message: OutgoingSmsRecord): ProviderAccessResult<ProviderStoredMessage> =
        ProviderAccessResult.Unsupported("test")

    override suspend fun updateStatus(
        id: ProviderMessageId,
        status: SmsProviderStatus,
    ): ProviderAccessResult<Unit> = ProviderAccessResult.Unsupported("test")
}

private class SyncTestMmsSource(
    private val messages: List<MmsProviderMessage> = emptyList(),
    private val countResult: ProviderAccessResult<Long>? = null,
    private val readResult: ProviderAccessResult<ProviderPage<MmsProviderMessage>>? = null,
) : MmsProviderDataSource {
    val readRequests = mutableListOf<ProviderPageRequest>()

    override suspend fun count(): ProviderAccessResult<Long> =
        countResult ?: ProviderAccessResult.Success(messages.size.toLong())

    override suspend fun readPage(
        request: ProviderPageRequest,
    ): ProviderAccessResult<ProviderPage<MmsProviderMessage>> {
        readRequests += request
        return readResult ?: ProviderAccessResult.Success(page(messages, request) { it.cursor() })
    }

    override suspend fun insertIncoming(message: DecodedIncomingMmsRecord): ProviderAccessResult<ProviderStoredMessage> =
        ProviderAccessResult.Unsupported("test")
}

private class FakeIndexDatabase : AuroraIndexDatabase() {
    val messages = FakeIndexedMessageDao()
    val sync = FakeIndexSyncDao(messages)

    init {
        messages.sync = sync
    }

    override fun indexedMessageDao(): IndexedMessageDao = messages

    override fun conversationDao(): ConversationDao = error("The synchronizer fake has no presentation reader")

    override fun indexSyncDao(): IndexSyncDao = sync

    protected override fun createInvalidationTracker(): InvalidationTracker =
        error("The host synchronizer fake never initializes Room")

    override fun clearAllTables() {
        messages.clear()
        sync.clear()
    }
}

private class FakeIndexSyncDao(
    private val messages: FakeIndexedMessageDao,
) : IndexSyncDao() {
    private val generations = linkedMapOf<Long, IndexGenerationEntity>()
    private val checkpointRows = linkedMapOf<Pair<Long, Int>, IndexCheckpointEntity>()
    private var nextGenerationId = 1L
    val staleDeletionAttempts = mutableListOf<Long>()

    protected override suspend fun insertGeneration(entity: IndexGenerationEntity): Long {
        val id = nextGenerationId++
        generations[id] = entity.copy(generationId = id)
        return id
    }

    protected override suspend fun insertCheckpoints(entities: List<IndexCheckpointEntity>) {
        entities.forEach { entity ->
            check(checkpointRows.putIfAbsent(entity.generationId to entity.providerKind, entity) == null)
        }
    }

    override suspend fun activeGeneration(
        scanningState: Int,
        verifyingState: Int,
    ): IndexGenerationEntity? = generations.values.lastOrNull { it.state == scanningState || it.state == verifyingState }

    override suspend fun latestGeneration(): IndexGenerationEntity? = generations.values.lastOrNull()

    override suspend fun checkpoints(generationId: Long): List<IndexCheckpointEntity> = checkpointRows.values
        .filter { it.generationId == generationId }
        .sortedBy(IndexCheckpointEntity::providerKind)

    override suspend fun putCheckpoint(entity: IndexCheckpointEntity) {
        checkpointRows[entity.generationId to entity.providerKind] = entity
    }

    override suspend fun updateGeneration(
        generationId: Long,
        state: Int,
        nowMillis: Long,
        completedAtMillis: Long?,
        committedCount: Long,
        pendingChanges: Boolean,
        failureCode: Int?,
        targetBatchSize: Int,
    ): Int {
        val current = generations[generationId] ?: return 0
        generations[generationId] = current.copy(
            state = state,
            updatedAtMillis = nowMillis,
            completedAtMillis = completedAtMillis,
            committedCount = committedCount,
            pendingChanges = pendingChanges,
            failureCode = failureCode,
            targetBatchSize = targetBatchSize,
        )
        return 1
    }

    override suspend fun markPendingChanges(generationId: Long, nowMillis: Long): Int {
        val current = generations[generationId] ?: return 0
        generations[generationId] = current.copy(
            pendingChanges = true,
            updatedAtMillis = nowMillis,
            signalSequence = current.signalSequence + 1L,
        )
        return 1
    }

    override suspend fun generationById(generationId: Long): IndexGenerationEntity? = generations[generationId]

    override suspend fun markVerifying(
        generationId: Long,
        nowMillis: Long,
        verifyingState: Int,
        scanningState: Int,
    ): Int {
        val current = generations[generationId]?.takeIf { it.state == scanningState } ?: return 0
        generations[generationId] = current.copy(state = verifyingState, updatedAtMillis = nowMillis)
        return 1
    }

    override suspend fun stopActiveGeneration(
        generationId: Long,
        terminalState: Int,
        failureCode: Int?,
        nowMillis: Long,
        scanningState: Int,
        verifyingState: Int,
    ): Int {
        val current = generations[generationId]
            ?.takeIf { it.state == scanningState || it.state == verifyingState }
            ?: return 0
        generations[generationId] = current.copy(
            state = terminalState,
            updatedAtMillis = nowMillis,
            completedAtMillis = null,
            failureCode = failureCode,
        )
        return 1
    }

    protected override suspend fun deleteRowsOutsideGeneration(generationId: Long): Int {
        staleDeletionAttempts += generationId
        return messages.deleteNotSeenInGeneration(generationId)
    }

    protected override suspend fun deleteConversationsOutsideGeneration(generationId: Long): Int =
        messages.deleteConversationsOutside(generationId)

    protected override suspend fun deleteParticipantsOutsideGeneration(generationId: Long): Int =
        messages.deleteParticipantsOutside(generationId)

    protected override suspend fun indexedMessageCount(): Long = messages.count()

    protected override suspend fun indexedThreadCount(generationId: Long): Long =
        messages.indexedThreadCount(generationId)

    protected override suspend fun indexedConversationCount(generationId: Long): Long =
        messages.indexedConversationCount(generationId)

    protected override suspend fun summarizedMessageCount(generationId: Long): Long =
        messages.summarizedMessageCount(generationId)

    protected override suspend fun indexedUnreadCount(generationId: Long): Long =
        messages.indexedUnreadCount(generationId)

    protected override suspend fun summarizedUnreadCount(generationId: Long): Long =
        messages.summarizedUnreadCount(generationId)

    protected override suspend fun invalidLatestConversationCount(generationId: Long): Long =
        messages.invalidLatestConversationCount(generationId)

    protected override suspend fun invalidParticipantCount(generationId: Long): Long =
        messages.invalidParticipantCount(generationId)

    protected override suspend fun markComplete(
        generationId: Long,
        nowMillis: Long,
        indexedCount: Long,
        completeState: Int,
        verifyingState: Int,
    ): Int {
        val current = generations[generationId]
            ?.takeIf { it.state == verifyingState && !it.pendingChanges }
            ?: return 0
        generations[generationId] = current.copy(
            state = completeState,
            updatedAtMillis = nowMillis,
            completedAtMillis = nowMillis,
            committedCount = indexedCount,
            pendingChanges = false,
            failureCode = null,
        )
        return 1
    }

    override suspend fun deleteCheckpoints(generationId: Long): Int {
        val before = checkpointRows.size
        checkpointRows.keys.removeAll { it.first == generationId }
        return before - checkpointRows.size
    }

    suspend fun forceTargetBatchSize(generationId: Long, target: Int) {
        val current = requireNotNull(generations[generationId])
        generations[generationId] = current.copy(targetBatchSize = target)
    }

    fun allGenerations(): List<IndexGenerationEntity> = generations.values.toList()

    fun clear() {
        generations.clear()
        checkpointRows.clear()
        staleDeletionAttempts.clear()
    }
}

private class FakeIndexedMessageDao : IndexedMessageDao() {
    private val rows = linkedMapOf<Pair<Int, Long>, IndexedMessageEntity>()
    private val conversations = linkedMapOf<Long, IndexedConversationEntity>()
    private val participants = linkedMapOf<Pair<Long, String>, IndexedConversationParticipantEntity>()
    private var nextRowId = 1L
    lateinit var sync: FakeIndexSyncDao
    var failNextCommit: Boolean = false
    var afterCommit: (suspend (Long) -> Unit)? = null
    val committedBatchTargets = mutableListOf<Int>()

    override suspend fun commitScanningProjectionBatch(
        generationId: Long,
        projections: List<IndexedProviderProjection>,
        smsCheckpoint: IndexCheckpointEntity,
        mmsCheckpoint: IndexCheckpointEntity,
        nowMillis: Long,
        targetBatchSize: Int,
    ): IndexUpsertSummary {
        if (failNextCommit) {
            failNextCommit = false
            throw SQLiteException("synthetic transaction failure")
        }
        val result = super.commitScanningProjectionBatch(
            generationId,
            projections,
            smsCheckpoint,
            mmsCheckpoint,
            nowMillis,
            targetBatchSize,
        )
        committedBatchTargets += targetBatchSize
        afterCommit?.invoke(generationId)
        return result
    }

    protected override suspend fun storedConversation(providerThreadId: Long): IndexedConversationEntity? =
        conversations[providerThreadId]

    protected override suspend fun putConversation(entity: IndexedConversationEntity) {
        conversations[entity.providerThreadId] = entity
    }

    protected override suspend fun deleteConversationParticipants(providerThreadId: Long): Int {
        val before = participants.size
        participants.keys.removeAll { it.first == providerThreadId }
        return before - participants.size
    }

    protected override suspend fun storedParticipantAddresses(
        providerThreadId: Long,
        generationId: Long,
        limit: Int,
    ): List<String> = participants.values
        .asSequence()
        .filter { it.providerThreadId == providerThreadId && it.lastSeenGeneration == generationId }
        .map(IndexedConversationParticipantEntity::address)
        .sorted()
        .take(limit)
        .toList()

    protected override suspend fun insertParticipantIgnoringConflict(
        entity: IndexedConversationParticipantEntity,
    ): Long {
        val key = entity.providerThreadId to entity.address
        if (key in participants) return -1L
        participants[key] = entity
        return participants.size.toLong()
    }

    protected override suspend fun participantCount(providerThreadId: Long, generationId: Long): Int =
        participants.values.count {
            it.providerThreadId == providerThreadId && it.lastSeenGeneration == generationId
        }

    protected override suspend fun storedIdentity(
        providerKind: Int,
        providerId: Long,
    ): StoredMessageIdentity? = rows[providerKind to providerId]?.let {
        StoredMessageIdentity(it.rowId, it.syncFingerprint)
    }

    protected override suspend fun insertIgnoringConflict(entity: IndexedMessageEntity): Long {
        val key = entity.providerKind to entity.providerId
        if (key in rows) return -1L
        val rowId = nextRowId++
        rows[key] = entity.copy(rowId = rowId)
        return rowId
    }

    protected override suspend fun updateByRowId(entity: IndexedMessageEntity): Int {
        val current = rows.entries.firstOrNull { it.value.rowId == entity.rowId } ?: return 0
        rows.remove(current.key)
        rows[entity.providerKind to entity.providerId] = entity
        return 1
    }

    protected override suspend fun markSeen(rowId: Long, generation: Long): Int {
        val current = rows.entries.firstOrNull { it.value.rowId == rowId } ?: return 0
        rows[current.key] = current.value.copy(lastSeenGeneration = generation)
        return 1
    }

    protected override suspend fun replaceCheckpoints(entities: List<IndexCheckpointEntity>) {
        entities.forEach { sync.putCheckpoint(it) }
    }

    protected override suspend fun advanceScanningGeneration(
        generationId: Long,
        nowMillis: Long,
        processedCount: Int,
        targetBatchSize: Int,
        scanningState: Int,
    ): Int {
        val current = sync.generationById(generationId)?.takeIf { it.state == scanningState } ?: return 0
        return sync.updateGeneration(
            generationId = generationId,
            state = current.state,
            nowMillis = nowMillis,
            completedAtMillis = current.completedAtMillis,
            committedCount = current.committedCount + processedCount,
            pendingChanges = current.pendingChanges,
            failureCode = current.failureCode,
            targetBatchSize = targetBatchSize,
        )
    }

    protected override suspend fun finishSteadyStateCommit(
        generationId: Long,
        expectedSignalSequence: Long,
        nowMillis: Long,
        indexedCount: Long,
        completeState: Int,
    ): Int {
        val current = sync.generationById(generationId)?.takeIf {
            it.state == completeState && it.signalSequence == expectedSignalSequence
        } ?: return 0
        return sync.updateGeneration(
            generationId = generationId,
            state = current.state,
            nowMillis = nowMillis,
            completedAtMillis = current.completedAtMillis,
            committedCount = indexedCount,
            pendingChanges = false,
            failureCode = current.failureCode,
            targetBatchSize = current.targetBatchSize,
        )
    }

    override suspend fun count(): Long = rows.size.toLong()

    override suspend fun byLocalRowId(rowId: Long): IndexedMessageEntity? =
        rows.values.firstOrNull { it.rowId == rowId }

    override suspend fun byProviderIdentity(
        providerKind: Int,
        providerId: Long,
    ): IndexedMessageEntity? = rows[providerKind to providerId]

    override suspend fun syncStates(
        providerKind: Int,
        providerIds: List<Long>,
    ): List<StoredSyncState> = providerIds.mapNotNull { providerId ->
        rows[providerKind to providerId]?.let { entity ->
            StoredSyncState(providerId, entity.syncFingerprint, entity.lastSeenGeneration)
        }
    }

    override suspend fun messagesByLocalRowIds(
        rowIds: List<Long>,
    ): List<IndexedMessageEntity> = emptyList()

    override suspend fun searchCandidateRowIds(
        matchExpression: String,
        limit: Int,
    ): List<Long> = emptyList()

    override suspend fun searchCandidateRowIdsAfterLocalRowId(
        matchExpression: String,
        afterRowId: Long,
        limit: Int,
    ): List<Long> = emptyList()

    override suspend fun searchOrdersByLocalRowIds(
        rowIds: List<Long>,
    ): List<StoredSearchOrder> = emptyList()

    override suspend fun newestGlobalOrderOutside(
        excludedRowIds: List<Long>,
    ): StoredSearchOrder? = null

    override suspend fun newestGlobalOrderOutsideAfter(
        excludedRowIds: List<Long>,
        beforeTimestampMillis: Long,
        beforeRowId: Long,
    ): StoredSearchOrder? = null

    override suspend fun optimizeFullTextIndex() = Unit

    override suspend fun searchGlobalFirstRowIds(
        matchExpression: String,
        limit: Int,
    ): List<Long> = emptyList()

    override suspend fun searchGlobalAfterRowIds(
        matchExpression: String,
        beforeTimestampMillis: Long,
        beforeRowId: Long,
        limit: Int,
    ): List<Long> = emptyList()

    override suspend fun searchThreadFirstRowIds(
        matchExpression: String,
        providerThreadId: Long,
        limit: Int,
    ): List<Long> = emptyList()

    override suspend fun searchThreadAfterRowIds(
        matchExpression: String,
        providerThreadId: Long,
        beforeTimestampMillis: Long,
        beforeRowId: Long,
        limit: Int,
    ): List<Long> = emptyList()

    override suspend fun newerThanAnchor(
        providerThreadId: Long,
        anchorTimestampMillis: Long,
        anchorRowId: Long,
        limit: Int,
    ): List<IndexedMessageEntity> = emptyList()

    override suspend fun olderThanAnchor(
        providerThreadId: Long,
        anchorTimestampMillis: Long,
        anchorRowId: Long,
        limit: Int,
    ): List<IndexedMessageEntity> = emptyList()

    override suspend fun deleteNotSeenInGeneration(generationId: Long): Int {
        val before = rows.size
        rows.entries.removeAll { it.value.lastSeenGeneration != generationId }
        return before - rows.size
    }

    override suspend fun deleteAll(): Int {
        val before = rows.size
        rows.clear()
        return before
    }

    suspend fun seed(entity: IndexedMessageEntity) {
        upsertBatchPreservingLocalIds(listOf(entity))
    }

    fun contains(providerKind: Int, providerId: Long): Boolean = providerKind to providerId in rows

    fun deleteConversationsOutside(generationId: Long): Int {
        val before = conversations.size
        conversations.entries.removeAll { it.value.lastSeenGeneration != generationId }
        return before - conversations.size
    }

    fun deleteParticipantsOutside(generationId: Long): Int {
        val before = participants.size
        participants.entries.removeAll { it.value.lastSeenGeneration != generationId }
        return before - participants.size
    }

    fun indexedThreadCount(generationId: Long): Long = rows.values
        .filter { it.lastSeenGeneration == generationId }
        .map(IndexedMessageEntity::providerThreadId)
        .distinct()
        .size
        .toLong()

    fun indexedConversationCount(generationId: Long): Long = conversations.values
        .count { it.lastSeenGeneration == generationId }
        .toLong()

    fun summarizedMessageCount(generationId: Long): Long = conversations.values
        .filter { it.lastSeenGeneration == generationId }
        .sumOf(IndexedConversationEntity::indexedMessageCount)

    fun indexedUnreadCount(generationId: Long): Long = rows.values
        .count { it.lastSeenGeneration == generationId && !it.isRead }
        .toLong()

    fun summarizedUnreadCount(generationId: Long): Long = conversations.values
        .filter { it.lastSeenGeneration == generationId }
        .sumOf(IndexedConversationEntity::indexedUnreadCount)

    fun invalidLatestConversationCount(generationId: Long): Long = conversations.values
        .count { conversation ->
            if (conversation.lastSeenGeneration != generationId) return@count false
            val message = rows.values.firstOrNull { it.rowId == conversation.latestRowId }
            message == null ||
                message.lastSeenGeneration != generationId ||
                message.providerThreadId != conversation.providerThreadId ||
                message.providerKind != conversation.latestProviderKind ||
                message.providerId != conversation.latestProviderId ||
                message.timestampMillis != conversation.latestTimestampMillis
        }.toLong()

    fun invalidParticipantCount(generationId: Long): Long = conversations.values
        .count { conversation ->
            conversation.lastSeenGeneration == generationId &&
                conversation.indexedParticipantCount != participants.values.count {
                    it.providerThreadId == conversation.providerThreadId &&
                        it.lastSeenGeneration == generationId
                }
        }.toLong()

    fun clear() {
        rows.clear()
        conversations.clear()
        participants.clear()
        committedBatchTargets.clear()
        nextRowId = 1L
    }
}

private fun <T> page(
    messages: List<T>,
    request: ProviderPageRequest,
    cursorOf: (T) -> ProviderPageCursor,
): ProviderPage<T> {
    val remaining = messages.filter { item ->
        request.before?.let { cursorOf(item).isOlderThan(it) } ?: true
    }
    val selected = remaining.take(request.limit)
    val exhausted = selected.size == remaining.size
    return ProviderPage(
        items = selected,
        next = if (exhausted) null else cursorOf(requireNotNull(selected.lastOrNull())),
        exhausted = exhausted,
    )
}

private fun smsRange(size: Int): List<SmsProviderMessage> =
    (size.toLong() downTo 1L).map { id -> sms(id = id, timestamp = id) }

private fun sms(id: Long, timestamp: Long): SmsProviderMessage = SmsProviderMessage(
    id = ProviderMessageId(ProviderKind.SMS, id),
    providerThreadId = ProviderThreadId(1L),
    sender = null,
    body = "sms-$id",
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

private fun mms(id: Long, timestamp: Long): MmsProviderMessage = MmsProviderMessage(
    id = ProviderMessageId(ProviderKind.MMS, id),
    providerThreadId = ProviderThreadId(1L),
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
    MessageSyncFingerprint.fromSha256(
        ByteArray(MessageSyncFingerprint.SHA_256_BYTES) { index -> ((seed + index) % 251L).toByte() },
    )

private fun checkpoint(
    generationId: Long,
    providerKind: Int,
    cursor: ProviderPageCursor? = null,
    exhausted: Boolean = false,
    committedCount: Long = 0L,
): IndexCheckpointEntity = IndexCheckpointEntity(
    generationId = generationId,
    providerKind = providerKind,
    cursorTimestampMillis = cursor?.timestampMillis,
    cursorProviderId = cursor?.providerRowId,
    exhausted = exhausted,
    committedCount = committedCount,
    updatedAtMillis = 10L,
)

private fun cursor(timestamp: Long, id: Long): ProviderPageCursor = ProviderPageCursor(timestamp, id)

private fun SmsProviderMessage.cursor(): ProviderPageCursor = cursor(timestampMillis, id.value)

private fun MmsProviderMessage.cursor(): ProviderPageCursor = cursor(timestampMillis, id.value)

private fun ProviderPageCursor.isOlderThan(other: ProviderPageCursor): Boolean =
    timestampMillis < other.timestampMillis ||
        (timestampMillis == other.timestampMillis && providerRowId < other.providerRowId)
