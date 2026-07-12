// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.index.search.RoomMessageIndex
import org.aurorasms.core.index.storage.AuroraIndexDatabase
import org.aurorasms.core.index.storage.GenerationStateCode
import org.aurorasms.core.index.storage.IndexDatabaseFactory
import org.aurorasms.core.index.sync.IndexSyncOutcome
import org.aurorasms.core.index.sync.TelephonyIndexSynchronizer
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageSyncFingerprint
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.MmsAttachmentSummary
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.telephony.IncomingSmsRecord
import org.aurorasms.core.telephony.MmsProviderMessage
import org.aurorasms.core.telephony.OutgoingSmsRecord
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderPage
import org.aurorasms.core.telephony.ProviderPageRequest
import org.aurorasms.core.telephony.ProviderStoredMessage
import org.aurorasms.core.telephony.SmsProviderDataSource
import org.aurorasms.core.telephony.SmsProviderMessage
import org.aurorasms.core.telephony.SmsProviderStatus
import org.aurorasms.core.testing.FakeMmsProviderDataSource
import org.aurorasms.core.testing.FakeRoleState
import org.aurorasms.core.testing.FakeSmsProviderDataSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TelephonyIndexSynchronizerInstrumentedTest {
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
    fun fullScanThenVerifiedRescanUpdatesAndDeletes() = runBlocking {
        val initialSms = listOf(
            sms(id = 1L, timestamp = 300L, body = "alpha newest"),
            sms(id = 2L, timestamp = 100L, body = "to be deleted"),
        )
        val initialMms = listOf(mms(id = 1L, timestamp = 200L, body = "alpha mms"))
        val first = synchronizer(initialSms, initialMms).synchronize()
        assertTrue(first is IndexSyncOutcome.Complete)
        first as IndexSyncOutcome.Complete
        assertTrue(first.coverage.verifiedComplete)
        assertEquals(3L, first.coverage.indexedMessageCount)
        assertEquals(3L, first.coverage.generationCommittedCount)
        assertEquals(2L, first.coverage.smsCheckpointCommittedCount)
        assertEquals(1L, first.coverage.mmsCheckpointCommittedCount)

        val dao = database.indexedMessageDao()
        val stableLocalId = requireNotNull(dao.byProviderIdentity(1, 1L)).rowId
        assertTrue(dao.byProviderIdentity(2, 1L) != null)
        val search = RoomMessageIndex(database).search(SearchRequest("alpha")) as SearchResult.Page
        assertEquals(2, search.page.items.size)

        val updatedSms = listOf(
            sms(id = 1L, timestamp = 301L, body = "alpha changed", fingerprintSeed = 9),
        )
        val second = synchronizer(updatedSms, initialMms).synchronize()
        assertTrue(second is IndexSyncOutcome.Complete)
        second as IndexSyncOutcome.Complete
        assertEquals(1, second.deletedStaleRows)
        assertEquals(2L, second.coverage.indexedMessageCount)
        assertEquals(2L, second.coverage.generationCommittedCount)
        assertEquals(1L, second.coverage.smsCheckpointCommittedCount)
        assertEquals(1L, second.coverage.mmsCheckpointCommittedCount)
        assertEquals(stableLocalId, requireNotNull(dao.byProviderIdentity(1, 1L)).rowId)
        assertEquals("alpha changed", requireNotNull(dao.byProviderIdentity(1, 1L)).body)
        assertTrue(dao.byProviderIdentity(1, 2L) == null)
    }

    @Test
    fun pendingSignalMakesCompleteCoverageTruthfulUntilNextScan() = runBlocking {
        val synchronizer = synchronizer(listOf(sms(1L, 10L, "body")), emptyList())
        assertTrue(synchronizer.synchronize() is IndexSyncOutcome.Complete)
        synchronizer.markPendingChanges()
        assertFalse(RoomMessageIndex(database).coverage().verifiedComplete)
        val rescanned = synchronizer.synchronize()
        assertTrue(rescanned is IndexSyncOutcome.Complete)
        assertTrue((rescanned as IndexSyncOutcome.Complete).coverage.verifiedComplete)
    }

    @Test
    fun roleAndPermissionFailuresAreTypedWithoutAdvancingRows() = runBlocking {
        val missingRole = TelephonyIndexSynchronizer(
            database = database,
            smsSource = FakeSmsProviderDataSource(),
            mmsSource = FakeMmsProviderDataSource(),
            roleState = FakeRoleState(held = false),
        ).synchronize()
        assertEquals(IndexSyncOutcome.Paused(IndexFailureCode.ROLE_REQUIRED), missingRole)
        assertEquals(0L, database.indexedMessageDao().count())

        val deniedSms = FakeSmsProviderDataSource().apply {
            failure = ProviderAccessResult.PermissionDenied
        }
        val denied = TelephonyIndexSynchronizer(
            database = database,
            smsSource = deniedSms,
            mmsSource = FakeMmsProviderDataSource(),
            roleState = FakeRoleState(),
        ).synchronize()
        assertEquals(IndexSyncOutcome.Failed(IndexFailureCode.PERMISSION_DENIED), denied)
        assertEquals(0L, database.indexedMessageDao().count())
        val failedGeneration = requireNotNull(database.indexSyncDao().latestGeneration())
        assertEquals(GenerationStateCode.FAILED, failedGeneration.state)
        database.indexSyncDao().checkpoints(failedGeneration.generationId).forEach { checkpoint ->
            assertEquals(0L, checkpoint.committedCount)
        }
    }

    @Test
    fun cancellationPropagatesAndPausesUncommittedGeneration() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val synchronizer = TelephonyIndexSynchronizer(
            database = database,
            smsSource = CancellableSmsSource(started),
            mmsSource = FakeMmsProviderDataSource(),
            roleState = FakeRoleState(),
            ioDispatcher = Dispatchers.Default,
        )
        val result = async(Dispatchers.Default) { synchronizer.synchronize() }
        started.await()
        result.cancelAndJoin()
        assertTrue(result.isCancelled)
        val generation = requireNotNull(database.indexSyncDao().latestGeneration())
        assertEquals(GenerationStateCode.PAUSED, generation.state)
        assertEquals(0L, database.indexedMessageDao().count())
    }

    private fun synchronizer(
        sms: List<SmsProviderMessage>,
        mms: List<MmsProviderMessage>,
    ) = TelephonyIndexSynchronizer(
        database = database,
        smsSource = FakeSmsProviderDataSource(sms),
        mmsSource = FakeMmsProviderDataSource(mms),
        roleState = FakeRoleState(),
    )
}

private class CancellableSmsSource(
    private val started: CompletableDeferred<Unit>,
) : SmsProviderDataSource {
    override suspend fun count(): ProviderAccessResult<Long> = ProviderAccessResult.Success(0L)

    override suspend fun readPage(
        request: ProviderPageRequest,
    ): ProviderAccessResult<ProviderPage<SmsProviderMessage>> {
        started.complete(Unit)
        awaitCancellation()
    }

    override suspend fun insertIncoming(message: IncomingSmsRecord): ProviderAccessResult<ProviderStoredMessage> =
        ProviderAccessResult.Unsupported("instrumentation")

    override suspend fun markIncomingHandled(
        deliveryFingerprint: org.aurorasms.core.model.MessageDeliveryFingerprint,
        providerId: ProviderMessageId,
        conversationId: org.aurorasms.core.model.ConversationId,
    ): ProviderAccessResult<Unit> = ProviderAccessResult.Unsupported("instrumentation")

    override suspend fun insertOutgoing(message: OutgoingSmsRecord): ProviderAccessResult<ProviderStoredMessage> =
        ProviderAccessResult.Unsupported("instrumentation")

    override suspend fun updateStatus(
        id: ProviderMessageId,
        status: SmsProviderStatus,
    ): ProviderAccessResult<Unit> = ProviderAccessResult.Unsupported("instrumentation")
}

private fun sms(
    id: Long,
    timestamp: Long,
    body: String,
    fingerprintSeed: Int = id.toInt(),
) = SmsProviderMessage(
    id = ProviderMessageId(ProviderKind.SMS, id),
    providerThreadId = ProviderThreadId(50L),
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
    read = false,
    seen = false,
    locked = false,
    syncFingerprint = fingerprint(fingerprintSeed),
)

private fun mms(
    id: Long,
    timestamp: Long,
    body: String,
) = MmsProviderMessage(
    id = ProviderMessageId(ProviderKind.MMS, id),
    providerThreadId = ProviderThreadId(50L),
    sender = null,
    participants = emptyList(),
    participantsTruncated = false,
    body = body,
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
    read = false,
    seen = false,
    locked = false,
    syncFingerprint = fingerprint(id.toInt() + 100),
)

private fun fingerprint(seed: Int): MessageSyncFingerprint =
    MessageSyncFingerprint.fromSha256(ByteArray(MessageSyncFingerprint.SHA_256_BYTES) { seed.toByte() })
