// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.storage.RoomSpamSafetyRepository
import org.aurorasms.core.state.storage.StateDatabaseFactory
import org.aurorasms.core.state.storage.StateDatabaseOpenResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpamSafetyRepositoryInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clearDatabase() { context.deleteDatabase(StateDatabaseFactory.DATABASE_NAME) }
    @After fun cleanDatabase() { context.deleteDatabase(StateDatabaseFactory.DATABASE_NAME) }

    @Test
    fun classificationsAndBlockStateAreIndependentRevisionCheckedAndDurable() = runBlocking {
        val sender = ParticipantAddress("+12025550177")
        val scope = singleScope(sender)
        var database = openDatabase()
        val blocked = try {
            val repository = RoomSpamSafetyRepository(database)
            assertEquals(SpamSafetyRepositoryResult.NotFound, repository.read(scope))
            assertFalse(repository.isSenderBlocked(sender).requireSuccess())
            val spam = repository.set(
                scope = scope,
                classification = SpamClassification.SPAM,
                blocked = false,
                expectedRevision = null,
                updatedTimestampMillis = 10L,
            ).requireDecision()
            assertEquals(SpamSafetyRevision(1L), spam.revision)
            assertEquals(1, repository.snapshots.first().decisions.size)
            assertEquals(
                SpamSafetyRepositoryResult.StaleWrite,
                repository.set(scope, SpamClassification.NOT_SPAM, false, null, 11L),
            )
            repository.set(
                scope = scope,
                classification = SpamClassification.SPAM,
                blocked = true,
                expectedRevision = spam.revision,
                updatedTimestampMillis = 11L,
            ).requireDecision()
        } finally {
            database.close()
        }
        database = openDatabase()
        try {
            val repository = RoomSpamSafetyRepository(database)
            assertTrue(repository.isSenderBlocked(sender).requireSuccess())
            val current = repository.read(scope).requireReadDecision()
            assertEquals(blocked, current)
            val trusted = repository.set(
                scope = scope,
                classification = SpamClassification.NOT_SPAM,
                blocked = false,
                expectedRevision = current.revision,
                updatedTimestampMillis = 12L,
            ).requireDecision()
            assertFalse(repository.isSenderBlocked(sender).requireSuccess())
            assertEquals(SpamClassification.NOT_SPAM, trusted.classification)
            val cleared = repository.set(
                scope = scope,
                classification = SpamClassification.NEUTRAL,
                blocked = false,
                expectedRevision = trusted.revision,
                updatedTimestampMillis = 13L,
            )
            assertEquals(SpamSafetyRepositoryResult.Success(null), cleared)
            assertEquals(SpamSafetyRepositoryResult.NotFound, repository.read(scope))
        } finally {
            database.close()
        }
    }

    @Test
    fun groupConversationCannotSuppressSenderNotification() = runBlocking {
        val database = openDatabase()
        try {
            val repository = RoomSpamSafetyRepository(database)
            val groupScope = SpamSafetyScope(
                participantSetKey = SpamParticipantSetKey.fromParticipants(
                    listOf(ParticipantAddress("+12025550101"), ParticipantAddress("+12025550102")),
                ),
                providerThreadId = ProviderThreadId(9L),
                singleSenderKey = null,
            )
            assertEquals(
                SpamSafetyRepositoryResult.InvalidBlockTarget,
                repository.set(groupScope, SpamClassification.SPAM, true, null, 1L),
            )
        } finally {
            database.close()
        }
    }

    private fun singleScope(sender: ParticipantAddress) = SpamSafetyScope(
        participantSetKey = SpamParticipantSetKey.fromParticipants(listOf(sender)),
        providerThreadId = ProviderThreadId(77L),
        singleSenderKey = BlockedSenderKey.fromSender(sender),
    )

    private fun openDatabase() = when (val result = StateDatabaseFactory.open(context)) {
        is StateDatabaseOpenResult.Opened -> result.database
        is StateDatabaseOpenResult.Failed -> error("State database did not open: ${result.reason}")
    }

    private fun <T> SpamSafetyRepositoryResult<T>.requireSuccess(): T =
        (this as SpamSafetyRepositoryResult.Success).value

    private fun SpamSafetyRepositoryResult<SpamSafetyDecision?>.requireDecision(): SpamSafetyDecision =
        requireNotNull(requireSuccess())

    private fun SpamSafetyRepositoryResult<SpamSafetyDecision>.requireReadDecision(): SpamSafetyDecision =
        requireSuccess()
}
