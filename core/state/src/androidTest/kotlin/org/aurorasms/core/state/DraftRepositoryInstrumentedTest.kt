// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.storage.RoomDraftRepository
import org.aurorasms.core.state.storage.StateDatabaseFactory
import org.aurorasms.core.state.storage.StateDatabaseOpenResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DraftRepositoryInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearDatabase() {
        context.deleteDatabase(StateDatabaseFactory.DATABASE_NAME)
    }

    @After
    fun cleanDatabase() {
        context.deleteDatabase(StateDatabaseFactory.DATABASE_NAME)
    }

    @Test
    fun createReadUpdateDelete_preservesStableIdAndBounds() = runBlocking {
        val database = openStateDatabase()
        val repository = RoomDraftRepository(database)
        try {
            val created = repository.create(
                NewDraft(
                    identity = DraftIdentity.ProviderThread(ProviderThreadId(42L)),
                    body = "Synthetic first draft",
                    subject = null,
                    createdTimestampMillis = 1_000L,
                    updatedTimestampMillis = 1_000L,
                ),
            ).successValue()

            assertTrue(created.id.value > 0L)
            assertEquals(created, repository.read(created.id).successValue())

            val updated = created.copy(
                body = "Synthetic updated draft",
                subject = "Synthetic subject",
                updatedTimestampMillis = 2_000L,
            )
            assertEquals(updated, repository.update(updated, created.revision).successValue())
            assertEquals(created.id, repository.read(created.id).successValue().id)
            assertEquals(updated, repository.read(created.id).successValue())

            assertEquals(Unit, repository.delete(created.id).successValue())
            assertEquals(DraftRepositoryResult.NotFound, repository.read(created.id))
        } finally {
            database.close()
        }
    }

    @Test
    fun participantSetKey_isCanonicalAndConflictsRemainTyped() = runBlocking {
        val database = openStateDatabase()
        val repository = RoomDraftRepository(database)
        try {
            val firstKey = DraftParticipantSetKey.fromParticipants(
                listOf(
                    ParticipantAddress("+15550000002"),
                    ParticipantAddress("+15550000001"),
                    ParticipantAddress("+15550000002"),
                ),
            )
            val secondKey = DraftParticipantSetKey.fromParticipants(
                listOf(
                    ParticipantAddress("+15550000001"),
                    ParticipantAddress("+15550000002"),
                ),
            )
            assertEquals(firstKey, secondKey)

            val draft = NewDraft(
                identity = DraftIdentity.ParticipantSet(firstKey),
                body = null,
                subject = null,
                createdTimestampMillis = 0L,
                updatedTimestampMillis = 0L,
            )
            repository.create(draft).successValue()
            assertEquals(DraftRepositoryResult.Conflict, repository.create(draft))
        } finally {
            database.close()
        }
    }

    @Test
    fun closedDatabase_propagatesRoomCancellation() {
        val database = openStateDatabase()
        val repository = RoomDraftRepository(database)
        database.close()

        assertThrows(CancellationException::class.java) {
            runBlocking {
                repository.create(
                    NewDraft(
                        identity = DraftIdentity.ProviderThread(ProviderThreadId(7L)),
                        body = "Synthetic closed-database draft",
                        subject = null,
                        createdTimestampMillis = 10L,
                        updatedTimestampMillis = 10L,
                    ),
                )
            }
        }
    }

    @Test
    fun staleWriter_cannotOverwriteNewerDraft() = runBlocking {
        val database = openStateDatabase()
        val repository = RoomDraftRepository(database)
        try {
            val original = repository.create(
                NewDraft(
                    identity = DraftIdentity.ProviderThread(ProviderThreadId(8L)),
                    body = "Synthetic original",
                    subject = null,
                    createdTimestampMillis = 100L,
                    updatedTimestampMillis = 100L,
                ),
            ).successValue()
            val firstWriter = original.copy(
                body = "Synthetic first writer",
                updatedTimestampMillis = 200L,
            )
            val staleWriter = original.copy(
                body = "Synthetic stale writer",
                updatedTimestampMillis = 300L,
            )

            assertEquals(
                DraftRepositoryResult.InvalidRevision,
                repository.update(original.copy(body = "Synthetic non-monotonic"), original.revision),
            )
            assertEquals(
                firstWriter,
                repository.update(firstWriter, original.revision).successValue(),
            )
            assertEquals(
                DraftRepositoryResult.StaleWrite,
                repository.update(staleWriter, original.revision),
            )
            assertEquals(firstWriter, repository.read(original.id).successValue())
        } finally {
            database.close()
        }
    }

    private fun openStateDatabase() =
        (StateDatabaseFactory.open(context) as StateDatabaseOpenResult.Opened).database
}

private fun <T> DraftRepositoryResult<T>.successValue(): T {
    assertTrue("Expected success but was $this", this is DraftRepositoryResult.Success<T>)
    return (this as DraftRepositoryResult.Success<T>).value
}
