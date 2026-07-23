// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.model.MessageSyncFingerprint
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.storage.RoomDraftRepository
import org.aurorasms.core.state.storage.RoomPermanentDeletionRepository
import org.aurorasms.core.state.storage.StateDatabaseFactory
import org.aurorasms.core.state.storage.StateDatabaseOpenResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermanentDeletionRepositoryInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clearDatabase() { context.deleteDatabase(StateDatabaseFactory.DATABASE_NAME) }
    @After fun cleanDatabase() { context.deleteDatabase(StateDatabaseFactory.DATABASE_NAME) }

    @Test
    fun messageReservationIsContentFreeUndoableAndCasProtected() = runBlocking {
        val database = openDatabase()
        try {
            val repository = RoomPermanentDeletionRepository(database)
            val target = PermanentDeletionTarget.Message(
                providerMessageId = ProviderMessageId(ProviderKind.MMS, 4L),
                providerThreadId = ProviderThreadId(9L),
                syncFingerprint = MessageSyncFingerprint.fromSha256(ByteArray(32) { 1 }),
            )
            val operation = repository.create(request(target)).success()

            assertEquals(operation, repository.observeByThread(target.providerThreadId).first().success())
            val columns = database.openHelper.writableDatabase
                .query("PRAGMA table_info(permanent_deletion_operations)")
                .use { cursor ->
                    val name = cursor.getColumnIndexOrThrow("name")
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
                }
            assertFalse(columns.any { it in setOf("body", "subject", "recipient", "address", "label") })
            assertEquals(
                PermanentDeletionResult.StaleWrite,
                repository.markCommitting(operation.id, PermanentDeletionRevision(99L), 101L),
            )
            assertEquals(
                PermanentDeletionResult.Success(Unit),
                repository.removeUndoable(operation.id, operation.revision),
            )
            assertEquals(PermanentDeletionResult.NotFound, repository.read(operation.id))
        } finally {
            database.close()
        }
    }

    @Test
    fun committedThreadDeletionClearsOnlyTheExactSnapshottedDraftRevision() = runBlocking {
        val database = openDatabase()
        try {
            val drafts = RoomDraftRepository(database)
            val repository = RoomPermanentDeletionRepository(database)
            val thread = ProviderThreadId(12L)
            val draft = drafts.create(
                NewDraft(
                    identity = DraftIdentity.ProviderThread(thread),
                    body = "synthetic draft",
                    subject = null,
                    createdTimestampMillis = 20L,
                    updatedTimestampMillis = 20L,
                ),
            ).successDraft()
            val created = repository.create(
                request(
                    PermanentDeletionTarget.Thread(
                        providerThreadId = thread,
                        smsCount = 1L,
                        latestSmsId = ProviderMessageId(ProviderKind.SMS, 8L),
                        mmsCount = 0L,
                        latestMmsId = null,
                    ),
                ),
            ).success()
            val snapshotted = created.target as PermanentDeletionTarget.Thread
            assertEquals(draft.id, snapshotted.draftId)
            assertEquals(draft.revision, snapshotted.draftRevision)
            val committing = repository.markCommitting(created.id, created.revision, 101L).success()

            assertEquals(
                PermanentDeletionResult.Success(Unit),
                repository.removeCommitted(committing.id, committing.revision),
            )
            assertEquals(DraftRepositoryResult.NotFound, drafts.read(draft.id))
        } finally {
            database.close()
        }
    }

    private fun request(target: PermanentDeletionTarget) = PermanentDeletionRequest(
        target = target,
        dueTimestampMillis = 5_100L,
        createdTimestampMillis = 100L,
        armedElapsedRealtimeMillis = 50L,
    )

    private fun openDatabase() = when (val result = StateDatabaseFactory.open(context)) {
        is StateDatabaseOpenResult.Opened -> result.database
        is StateDatabaseOpenResult.Failed -> error("State database open failed: ${result.reason}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> PermanentDeletionResult<T>.success(): T =
        (this as PermanentDeletionResult.Success<T>).value

    @Suppress("UNCHECKED_CAST")
    private fun <T> DraftRepositoryResult<T>.successDraft(): T =
        (this as DraftRepositoryResult.Success<T>).value
}
