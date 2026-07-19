// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.storage.RoomConversationSubscriptionPreferenceRepository
import org.aurorasms.core.state.storage.StateDatabaseFactory
import org.aurorasms.core.state.storage.StateDatabaseOpenResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationSubscriptionPreferenceRepositoryInstrumentedTest {
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
    fun createUpdateStaleWriteAndReopenPreserveExactConversationChoice() = runBlocking {
        val initialScope = scope(threadId = 41L)
        val database = openDatabase()
        val first = try {
            val repository = RoomConversationSubscriptionPreferenceRepository(database)
            assertEquals(
                ConversationSubscriptionRepositoryResult.NotFound,
                repository.read(initialScope),
            )
            val created = repository.set(
                scope = initialScope,
                subscriptionId = AuroraSubscriptionId(1),
                expectedRevision = null,
                updatedTimestampMillis = 100L,
            ).requireSuccess()
            assertEquals(ConversationSubscriptionRevision(1L), created.revision)
            assertEquals(
                ConversationSubscriptionRepositoryResult.StaleWrite,
                repository.set(
                    scope = initialScope,
                    subscriptionId = AuroraSubscriptionId(2),
                    expectedRevision = null,
                    updatedTimestampMillis = 101L,
                ),
            )
            val movedScope = scope(threadId = 42L)
            val updated = repository.set(
                scope = movedScope,
                subscriptionId = AuroraSubscriptionId(2),
                expectedRevision = created.revision,
                updatedTimestampMillis = 101L,
            ).requireSuccess()
            assertEquals(ConversationSubscriptionRevision(2L), updated.revision)
            assertEquals(ProviderThreadId(42L), updated.scope.providerThreadId)
            updated
        } finally {
            database.close()
        }

        val reopened = openDatabase()
        try {
            val loaded = RoomConversationSubscriptionPreferenceRepository(reopened)
                .read(scope(threadId = 900L))
                .requireSuccess()
            assertEquals(first.subscriptionId, loaded.subscriptionId)
            assertEquals(first.revision, loaded.revision)
            assertEquals(ProviderThreadId(42L), loaded.scope.providerThreadId)
            assertNotEquals(ProviderThreadId(900L), loaded.scope.providerThreadId)
        } finally {
            reopened.close()
        }
    }

    private fun scope(threadId: Long): ConversationSubscriptionScope =
        ConversationSubscriptionScope(
            participantSetKey = ConversationSubscriptionParticipantSetKey.fromParticipants(
                listOf(ParticipantAddress("synthetic@example.invalid")),
            ),
            providerThreadId = ProviderThreadId(threadId),
        )

    private fun openDatabase() = when (val result = StateDatabaseFactory.open(context)) {
        is StateDatabaseOpenResult.Opened -> result.database
        is StateDatabaseOpenResult.Failed -> error("State database did not open: ${result.reason}")
    }

    private fun ConversationSubscriptionRepositoryResult<ConversationSubscriptionPreference>
        .requireSuccess(): ConversationSubscriptionPreference =
        (this as ConversationSubscriptionRepositoryResult.Success).value
}
