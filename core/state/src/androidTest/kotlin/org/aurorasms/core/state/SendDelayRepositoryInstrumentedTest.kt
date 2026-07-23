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
import org.aurorasms.core.state.storage.RoomSendDelayRepository
import org.aurorasms.core.state.storage.StateDatabaseFactory
import org.aurorasms.core.state.storage.StateDatabaseOpenResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SendDelayRepositoryInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clearDatabase() { context.deleteDatabase(StateDatabaseFactory.DATABASE_NAME) }
    @After fun cleanDatabase() { context.deleteDatabase(StateDatabaseFactory.DATABASE_NAME) }

    @Test
    fun exactDraftReservationFreezesBoundedSignatureAndIsCasProtected() = runBlocking {
        val database = openDatabase()
        try {
            val drafts = RoomDraftRepository(database)
            val repository = RoomSendDelayRepository(database)
            val thread = ProviderThreadId(91L)
            val signature = checkNotNull(MessageSignature.fromUserInput("Synthetic signature"))
            val draft = drafts.create(
                NewDraft(
                    identity = DraftIdentity.ProviderThread(thread),
                    body = "synthetic delayed body",
                    subject = null,
                    createdTimestampMillis = 100L,
                    updatedTimestampMillis = 100L,
                ),
            ).success()
            val reservation = repository.create(
                SendDelayRequest(
                    participantSetKey = SendDelayParticipantSetKey.fromParticipants(
                        listOf(ParticipantAddress("synthetic@example.invalid")),
                    ),
                    providerThreadId = thread,
                    draftId = draft.id,
                    expectedDraftRevision = draft.revision,
                    subscriptionId = AuroraSubscriptionId(1),
                    dueTimestampMillis = 5_200L,
                    createdTimestampMillis = 200L,
                    armedElapsedRealtimeMillis = 50L,
                    frozenSignature = signature,
                ),
            ).success()

            assertEquals("synthetic delayed body", reservation.authoritativeBody)
            assertEquals(signature, reservation.operation.frozenSignature)
            assertEquals(
                reservation.operation,
                repository.observeByThread(thread).first().success(),
            )
            val columns = database.openHelper.writableDatabase
                .query("PRAGMA table_info(send_delay_operations)")
                .use { cursor ->
                    val name = cursor.getColumnIndexOrThrow("name")
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
                }
            assertFalse(columns.any { it in setOf("body", "subject", "recipient", "address", "label") })

            assertEquals(
                SendDelayResult.StaleWrite,
                repository.markDispatching(
                    reservation.operation.id,
                    SendDelayRevision(199L),
                    201L,
                ),
            )
            val dispatching = repository.markDispatching(
                reservation.operation.id,
                reservation.operation.revision,
                201L,
            ).success()
            assertEquals(SendDelayPhase.DISPATCHING, dispatching.phase)
            assertEquals(signature, dispatching.frozenSignature)
            assertEquals(
                SendDelayResult.PhaseMismatch,
                repository.remove(dispatching.id, dispatching.revision),
            )
            assertEquals(
                SendDelayDispatchReconciliation.REVIEW_REQUIRED,
                repository.reconcileDispatch(dispatching.id, 202L).success(),
            )
            assertEquals(
                SendDelayReviewReason.INTERRUPTED_BEFORE_RESERVATION,
                repository.read(dispatching.id).success().reviewReason,
            )
            assertEquals(
                SendDelayResult.Success(Unit),
                repository.remove(
                    dispatching.id,
                    repository.read(dispatching.id).success().revision,
                ),
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
    private fun <T> SendDelayResult<T>.success(): T = (this as SendDelayResult.Success<T>).value

    @Suppress("UNCHECKED_CAST")
    private fun <T> DraftRepositoryResult<T>.success(): T = (this as DraftRepositoryResult.Success<T>).value
}
