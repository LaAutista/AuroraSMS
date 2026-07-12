// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.index.storage.AuroraIndexDatabase
import org.aurorasms.core.index.storage.GenerationStateCode
import org.aurorasms.core.index.storage.IndexDatabaseFactory
import org.aurorasms.core.index.sync.IndexProjectionMapper
import org.aurorasms.core.index.sync.IndexSyncOutcome
import org.aurorasms.core.index.sync.TelephonyIndexSynchronizer
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.MessageSyncFingerprint
import org.aurorasms.core.model.MmsAttachmentSummary
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.telephony.MmsProviderDataSource
import org.aurorasms.core.telephony.MmsProviderMessage
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderPage
import org.aurorasms.core.telephony.ProviderPageCursor
import org.aurorasms.core.telephony.ProviderPageRequest
import org.aurorasms.core.telephony.SmsProviderDataSource
import org.aurorasms.core.telephony.SmsProviderMessage
import org.aurorasms.core.testing.FakeMmsProviderDataSource
import org.aurorasms.core.testing.FakeRoleState
import org.aurorasms.core.testing.FakeSmsProviderDataSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndexDurabilityEvidenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun deleteBefore() {
        context.deleteDatabase(IndexDatabaseFactory.DATABASE_NAME)
    }

    @After
    fun deleteAfter() {
        context.deleteDatabase(IndexDatabaseFactory.DATABASE_NAME)
    }

    @Test
    fun bothProviderCursorsResumeFromOneDurableReopenedSnapshot() = runBlocking {
        val smsCursor = ProviderPageCursor(timestampMillis = 500L, providerRowId = 5L)
        val mmsCursor = ProviderPageCursor(timestampMillis = 400L, providerRowId = 4L)
        val first = IndexDatabaseFactory.create(context)
        val generation = first.indexSyncDao().startGeneration(10L)
        val committedSms = smsMessage(5L, 500L)
        val committedMms = mmsMessage(4L, 400L)
        first.indexedMessageDao().commitScanningBatch(
            generationId = generation,
            entities = listOf(
                IndexProjectionMapper.fromSms(committedSms, generation),
                IndexProjectionMapper.fromMms(committedMms, generation),
            ),
            smsCheckpoint = checkpoint(generation, 1, providerId = 5L, count = 1L).copy(
                cursorTimestampMillis = smsCursor.timestampMillis,
            ),
            mmsCheckpoint = checkpoint(generation, 2, providerId = 4L, count = 1L).copy(
                cursorTimestampMillis = mmsCursor.timestampMillis,
            ),
            nowMillis = 20L,
            targetBatchSize = 500,
        )
        assertEquals(
            1,
            first.indexSyncDao().stopActiveGeneration(
                generationId = generation,
                terminalState = GenerationStateCode.PAUSED,
                failureCode = null,
                nowMillis = 30L,
            ),
        )
        first.close()

        val reopened = IndexDatabaseFactory.create(context)
        try {
            val sms = RecordingSmsSource(
                FakeSmsProviderDataSource(
                    listOf(
                        committedSms,
                        smsMessage(3L, 300L),
                    ),
                ),
            )
            val mms = RecordingMmsSource(
                FakeMmsProviderDataSource(
                    listOf(
                        committedMms,
                        mmsMessage(2L, 200L),
                    ),
                ),
            )

            val outcome = TelephonyIndexSynchronizer(
                database = reopened,
                smsSource = sms,
                mmsSource = mms,
                roleState = FakeRoleState(),
            ).synchronize()

            assertTrue("Expected completed durable resume but was $outcome", outcome is IndexSyncOutcome.Complete)
            assertEquals(smsCursor, sms.requests.first().before)
            assertEquals(mmsCursor, mms.requests.first().before)
            assertEquals(1L, reopened.indexSyncDao().latestGeneration()?.generationId)
            assertEquals(4L, reopened.indexedMessageDao().count())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun sqlitePageLimitRollsBackContentAndBothCheckpoints() = runBlocking {
        val database = IndexDatabaseFactory.createInMemory(context)
        try {
            val generation = database.indexSyncDao().startGeneration(10L)
            val sqlite = database.openHelper.writableDatabase
            val originalPageCount = sqlite.singleLong("PRAGMA page_count")
            val configuredLimit = sqlite.singleLong("PRAGMA max_page_count = $originalPageCount")
            assertEquals(originalPageCount, configuredLimit)
            val oversizedBody = "x".repeat(1_048_576)

            val failure = runCatching {
                database.indexedMessageDao().commitScanningBatch(
                    generationId = generation,
                    entities = listOf(
                        entity(
                            kind = ProviderKind.SMS,
                            providerId = 99L,
                            generationId = generation,
                            body = oversizedBody,
                        ),
                    ),
                    smsCheckpoint = checkpoint(generation, 1, providerId = 99L, count = 1L),
                    mmsCheckpoint = checkpoint(generation, 2, exhausted = true),
                    nowMillis = 20L,
                    targetBatchSize = 500,
                )
            }

            assertTrue("The fixed SQLite page ceiling must reject the oversized transaction", failure.isFailure)
            assertEquals(0L, database.indexedMessageDao().count())
            assertEquals(0L, database.indexSyncDao().latestGeneration()?.committedCount)
            database.indexSyncDao().checkpoints(generation).forEach { checkpoint ->
                assertEquals(null, checkpoint.cursorProviderId)
                assertEquals(0L, checkpoint.committedCount)
                assertTrue(!checkpoint.exhausted)
            }
        } finally {
            database.close()
        }
    }
}

private class RecordingSmsSource(
    private val delegate: SmsProviderDataSource,
) : SmsProviderDataSource by delegate {
    val requests = mutableListOf<ProviderPageRequest>()

    override suspend fun readPage(
        request: ProviderPageRequest,
    ): ProviderAccessResult<ProviderPage<SmsProviderMessage>> {
        requests += request
        return delegate.readPage(request)
    }
}

private class RecordingMmsSource(
    private val delegate: MmsProviderDataSource,
) : MmsProviderDataSource by delegate {
    val requests = mutableListOf<ProviderPageRequest>()

    override suspend fun readPage(
        request: ProviderPageRequest,
    ): ProviderAccessResult<ProviderPage<MmsProviderMessage>> {
        requests += request
        return delegate.readPage(request)
    }
}

private fun smsMessage(id: Long, timestamp: Long) = SmsProviderMessage(
    id = ProviderMessageId(ProviderKind.SMS, id),
    providerThreadId = ProviderThreadId(50L),
    sender = null,
    body = "sms $id",
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
    syncFingerprint = evidenceFingerprint(id),
)

private fun mmsMessage(id: Long, timestamp: Long) = MmsProviderMessage(
    id = ProviderMessageId(ProviderKind.MMS, id),
    providerThreadId = ProviderThreadId(50L),
    sender = null,
    participants = emptyList(),
    participantsTruncated = false,
    body = "mms $id",
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
    syncFingerprint = evidenceFingerprint(id + 100L),
)

private fun evidenceFingerprint(seed: Long): MessageSyncFingerprint =
    MessageSyncFingerprint.fromSha256(ByteArray(MessageSyncFingerprint.SHA_256_BYTES) { seed.toByte() })

private fun SupportSQLiteDatabase.singleLong(sql: String): Long = query(sql).use { cursor ->
    check(cursor.moveToFirst()) { "Expected a scalar SQLite result" }
    cursor.getLong(0)
}
