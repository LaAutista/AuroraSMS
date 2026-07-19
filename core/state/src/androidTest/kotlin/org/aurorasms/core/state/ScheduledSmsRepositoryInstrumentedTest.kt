// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.storage.RoomDraftRepository
import org.aurorasms.core.state.storage.RoomScheduledSmsRepository
import org.aurorasms.core.state.storage.StateDatabaseFactory
import org.aurorasms.core.state.storage.StateDatabaseOpenResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduledSmsRepositoryInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clearDatabase() { context.deleteDatabase(StateDatabaseFactory.DATABASE_NAME) }
    @After fun cleanDatabase() { context.deleteDatabase(StateDatabaseFactory.DATABASE_NAME) }

    @Test
    fun exactDraftReservationIsContentFreeDurableAndTransitionsWithCas() = runBlocking {
        val database = openDatabase()
        try {
            val drafts = RoomDraftRepository(database)
            val repository = RoomScheduledSmsRepository(database)
            val thread = ProviderThreadId(81L)
            val draft = drafts.create(
                NewDraft(
                    identity = DraftIdentity.ProviderThread(thread),
                    body = "synthetic scheduled body",
                    subject = null,
                    createdTimestampMillis = 100L,
                    updatedTimestampMillis = 100L,
                ),
            ).success()
            val reservation = repository.create(
                ScheduledSmsRequest(
                    participantSetKey = ScheduledSmsParticipantSetKey.fromParticipants(
                        listOf(ParticipantAddress("synthetic@example.invalid")),
                    ),
                    providerThreadId = thread,
                    draftId = draft.id,
                    expectedDraftRevision = draft.revision,
                    subscriptionId = AuroraSubscriptionId(1),
                    dueTimestampMillis = 10_000L,
                    createdTimestampMillis = 200L,
                    armedElapsedRealtimeMillis = 50L,
                ),
            ).success()
            assertEquals("synthetic scheduled body", reservation.authoritativeBody)
            assertEquals(
                reservation.schedule,
                repository.observeByThread(thread).first().success(),
            )
            val columns = database.openHelper.writableDatabase
                .query("PRAGMA table_info(scheduled_sms_operations)")
                .use { cursor ->
                    val name = cursor.getColumnIndexOrThrow("name")
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
                }
            assertFalse(columns.any { it in setOf("body", "subject", "recipient", "address", "label") })

            val armed = repository.markArmed(
                reservation.schedule.id,
                reservation.schedule.revision,
                ScheduledSmsPrecision.EXACT,
                armedWallTimestampMillis = 201L,
                armedElapsedRealtimeMillis = 51L,
                updatedTimestampMillis = 201L,
            ).success()
            assertEquals(ScheduledSmsPrecision.EXACT, armed.precision)
            assertEquals(
                ScheduledSmsResult.StaleWrite,
                repository.markDispatching(armed.id, reservation.schedule.revision, 202L),
            )
            val dispatching = repository.markDispatching(armed.id, armed.revision, 202L).success()
            assertEquals(ScheduledSmsPhase.DISPATCHING, dispatching.phase)
            assertEquals(
                ScheduledSmsDispatchReconciliation.REVIEW_REQUIRED,
                repository.reconcileDispatch(dispatching.id, 203L).success(),
            )
            assertEquals(
                ScheduledSmsReviewReason.INTERRUPTED_BEFORE_RESERVATION,
                repository.read(dispatching.id).success().reviewReason,
            )
        } finally {
            database.close()
        }
    }

    private fun openDatabase() = when (val result = StateDatabaseFactory.open(context)) {
        is StateDatabaseOpenResult.Opened -> result.database
        is StateDatabaseOpenResult.Failed -> error("State database open failed: ${result.reason}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> ScheduledSmsResult<T>.success(): T = (this as ScheduledSmsResult.Success<T>).value

    @Suppress("UNCHECKED_CAST")
    private fun <T> DraftRepositoryResult<T>.success(): T = (this as DraftRepositoryResult.Success<T>).value
}
