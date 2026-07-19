// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.storage.RoomComposerSmsOperationRepository
import org.aurorasms.core.state.storage.RoomDraftRepository
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
class ComposerSmsOperationRepositoryInstrumentedTest {
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
    fun exactReservationAndCallbackProofKeepContentInDraftUntilAtomicCompletion() = runBlocking {
        val database = openStateDatabase()
        val drafts = RoomDraftRepository(database)
        val operations = RoomComposerSmsOperationRepository(database)
        try {
            val threadId = ProviderThreadId(42L)
            val draft = drafts.createDraft(threadId, "synthetic authoritative body", 100L)
            val reserved = operations.reserve(
                reservationRequest(threadId, draft, 200L),
            ).successValue()

            assertEquals("synthetic authoritative body", reserved.authoritativeBody)
            assertEquals(ComposerSmsOperationPhase.RESERVED, reserved.operation.phase)
            assertEquals(null, reserved.operation.providerBinding)
            assertTrue(reserved.operation.operationId.isComposerSmsOperationId())
            assertEquals(
                reserved.operation,
                operations.observeByThread(threadId).first().successValue(),
            )
            assertEquals(
                ComposerSmsOperationResult.Conflict,
                operations.reserve(reservationRequest(threadId, draft, 201L)),
            )
            val operationColumns = database.openHelper.writableDatabase
                .query("PRAGMA table_info(composer_sms_operations)")
                .use { cursor ->
                    val name = cursor.getColumnIndexOrThrow("name")
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
                }
            assertFalse(operationColumns.any { it in setOf("body", "subject", "recipient", "digest") })

            val binding = providerBinding(501L)
            val prepared = operations.markPrepared(
                reserved.operation.operationId,
                reserved.operation.revision,
                binding,
                201L,
            ).successValue()
            assertEquals(
                ComposerSmsOperationResult.StaleWrite,
                operations.markSubmitting(
                    prepared.operationId,
                    reserved.operation.revision,
                    binding,
                    202L,
                ),
            )
            assertEquals(
                ComposerSmsOperationResult.ProviderMismatch,
                operations.markSubmitting(
                    prepared.operationId,
                    prepared.revision,
                    providerBinding(999L),
                    202L,
                ),
            )
            val submitting = operations.markSubmitting(
                prepared.operationId,
                prepared.revision,
                binding,
                202L,
            ).successValue()
            val accepted = operations.markPlatformAccepted(
                submitting.operationId,
                submitting.revision,
                binding,
                203L,
            ).successValue()
            assertEquals(draft, drafts.read(draft.id).draftSuccessValue())
            assertEquals(
                ComposerSmsOperationResult.PhaseMismatch,
                operations.completeSentAndRemove(accepted.operationId, accepted.revision, binding),
            )

            val unknown = operations.markSubmissionUnknown(
                accepted.operationId,
                accepted.revision,
                binding,
                204L,
            ).successValue()
            assertEquals(
                unknown,
                operations.markSubmissionUnknown(
                    unknown.operationId,
                    unknown.revision,
                    binding,
                    205L,
                ).successValue(),
            )
            assertEquals(listOf(unknown), operations.recoverySnapshot().successValue())
            val callbackSucceeded = operations.markSentCallbackSucceeded(
                unknown.operationId,
                unknown.revision,
                binding,
                205L,
            ).successValue()
            assertEquals(ComposerSmsOperationPhase.SENT_CALLBACK_SUCCEEDED, callbackSucceeded.phase)
            assertEquals(draft, drafts.read(draft.id).draftSuccessValue())

            val completion = operations.completeSentAndRemove(
                callbackSucceeded.operationId,
                callbackSucceeded.revision,
                binding,
            ).successValue()
            assertEquals(ComposerSmsDraftClearance.CLEARED, completion.draftClearance)
            assertEquals(DraftRepositoryResult.NotFound, drafts.read(draft.id))
            assertEquals(
                ComposerSmsOperationResult.NotFound,
                operations.read(callbackSucceeded.operationId),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun failedCallbackKeepsDraftAndNewerDraftRevisionIsNeverCleared() = runBlocking {
        val database = openStateDatabase()
        val drafts = RoomDraftRepository(database)
        val operations = RoomComposerSmsOperationRepository(database)
        try {
            val failedThread = ProviderThreadId(51L)
            val failedDraft = drafts.createDraft(failedThread, "synthetic retry body", 100L)
            val failedReserved = operations.reserve(
                reservationRequest(failedThread, failedDraft, 200L),
            ).successValue().operation
            val failedBinding = providerBinding(601L)
            val failedPrepared = operations.markPrepared(
                failedReserved.operationId,
                failedReserved.revision,
                failedBinding,
                201L,
            ).successValue()
            val failedSubmitting = operations.markSubmitting(
                failedPrepared.operationId,
                failedPrepared.revision,
                failedBinding,
                202L,
            ).successValue()
            val failedAccepted = operations.markPlatformAccepted(
                failedSubmitting.operationId,
                failedSubmitting.revision,
                failedBinding,
                203L,
            ).successValue()
            val failedUnknown = operations.markSubmissionUnknown(
                failedAccepted.operationId,
                failedAccepted.revision,
                failedBinding,
                204L,
            ).successValue()
            val knownUnsent = operations.markSentCallbackFailed(
                failedUnknown.operationId,
                failedUnknown.revision,
                failedBinding,
                205L,
            ).successValue()
            assertEquals(ComposerSmsOperationPhase.KNOWN_UNSENT, knownUnsent.phase)
            assertEquals(failedDraft, drafts.read(failedDraft.id).draftSuccessValue())
            assertEquals(
                ComposerSmsOperationResult.Success(Unit),
                operations.acknowledgeAndRemove(
                    knownUnsent.operationId,
                    knownUnsent.revision,
                    206L,
                ),
            )
            assertEquals(failedDraft, drafts.read(failedDraft.id).draftSuccessValue())

            val newerThread = ProviderThreadId(52L)
            val original = drafts.createDraft(newerThread, "synthetic original body", 300L)
            val reserved = operations.reserve(
                reservationRequest(newerThread, original, 400L),
            ).successValue().operation
            val newerDraft = original.copy(
                body = "synthetic newer body",
                updatedTimestampMillis = 301L,
            )
            assertEquals(
                newerDraft,
                drafts.update(newerDraft, original.revision).draftSuccessValue(),
            )
            val binding = providerBinding(602L)
            val prepared = operations.markPrepared(
                reserved.operationId,
                reserved.revision,
                binding,
                401L,
            ).successValue()
            val submitting = operations.markSubmitting(
                prepared.operationId,
                prepared.revision,
                binding,
                402L,
            ).successValue()
            val succeeded = operations.markSentCallbackSucceeded(
                submitting.operationId,
                submitting.revision,
                binding,
                403L,
            ).successValue()

            assertEquals(
                ComposerSmsDraftClearance.NEWER_REVISION_PRESERVED,
                operations.completeSentAndRemove(
                    succeeded.operationId,
                    succeeded.revision,
                    binding,
                ).successValue().draftClearance,
            )
            assertEquals(newerDraft, drafts.read(newerDraft.id).draftSuccessValue())
            assertEquals(ComposerSmsOperationResult.NotFound, operations.read(succeeded.operationId))
        } finally {
            database.close()
        }
    }

    @Test
    fun unknownAcknowledgementAtomicallyRetainsContentFreeLateCallbackAuthority() = runBlocking {
        val database = openStateDatabase()
        val drafts = RoomDraftRepository(database)
        val operations = RoomComposerSmsOperationRepository(database)
        try {
            val threadId = ProviderThreadId(61L)
            val draft = drafts.createDraft(threadId, "synthetic retained draft", 100L)
            val reserved = operations.reserve(
                reservationRequest(threadId, draft, 200L),
            ).successValue().operation
            val binding = providerBinding(701L)
            val prepared = operations.markPrepared(
                reserved.operationId,
                reserved.revision,
                binding,
                201L,
            ).successValue()
            val submitting = operations.markSubmitting(
                prepared.operationId,
                prepared.revision,
                binding,
                202L,
            ).successValue()
            val unknown = operations.markSubmissionUnknown(
                submitting.operationId,
                submitting.revision,
                binding,
                203L,
            ).successValue()

            assertEquals(
                ComposerSmsOperationResult.Success(Unit),
                operations.acknowledgeAndRemove(unknown.operationId, unknown.revision, 204L),
            )
            assertEquals(ComposerSmsOperationResult.NotFound, operations.read(unknown.operationId))
            assertEquals(emptyList<ComposerSmsOperation>(), operations.recoverySnapshot().successValue())
            val awaiting = operations.readAcknowledged(unknown.operationId).successValue()
            assertEquals(AcknowledgedComposerSmsCallbackProof.AWAITING_CALLBACK, awaiting.callbackProof)
            assertEquals(binding, awaiting.providerBinding)
            assertEquals(draft, drafts.read(draft.id).draftSuccessValue())

            assertEquals(
                ComposerSmsOperationResult.ProviderMismatch,
                operations.markAcknowledgedSent(
                    awaiting.operationId,
                    awaiting.revision,
                    providerBinding(999L),
                    205L,
                ),
            )
            val sent = operations.markAcknowledgedSent(
                awaiting.operationId,
                awaiting.revision,
                binding,
                205L,
            ).successValue()
            assertEquals(AcknowledgedComposerSmsCallbackProof.SENT, sent.callbackProof)
            assertEquals(listOf(sent), operations.acknowledgedRecoverySnapshot().successValue())

            assertEquals(
                ComposerSmsOperationResult.Success(Unit),
                operations.completeAcknowledged(
                    sent.operationId,
                    sent.revision,
                    binding,
                    AcknowledgedComposerSmsCallbackProof.SENT,
                ),
            )
            assertEquals(
                ComposerSmsOperationResult.NotFound,
                operations.readAcknowledged(sent.operationId),
            )
            assertEquals(draft, drafts.read(draft.id).draftSuccessValue())
            val receiptColumns = database.openHelper.writableDatabase
                .query("PRAGMA table_info(acknowledged_composer_sms_receipts)")
                .use { cursor ->
                    val name = cursor.getColumnIndexOrThrow("name")
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
                }
            assertFalse(receiptColumns.any { it in setOf("body", "subject", "recipient", "digest") })
        } finally {
            database.close()
        }
    }

    @Test
    fun reservationRejectsStaleBlankSubjectAndParticipantSetDrafts() = runBlocking {
        val database = openStateDatabase()
        val drafts = RoomDraftRepository(database)
        val operations = RoomComposerSmsOperationRepository(database)
        try {
            val threadId = ProviderThreadId(70L)
            val eligible = drafts.createDraft(threadId, "synthetic body", 100L)
            assertEquals(
                ComposerSmsOperationResult.StaleWrite,
                operations.reserve(
                    reservationRequest(threadId, eligible, 200L).copy(
                        expectedDraftRevision = DraftRevision(99L),
                    ),
                ),
            )

            val blank = drafts.createDraft(ProviderThreadId(71L), "   ", 110L)
            assertEquals(
                ComposerSmsOperationResult.IneligibleDraft,
                operations.reserve(reservationRequest(ProviderThreadId(71L), blank, 210L)),
            )
            val subject = drafts.create(
                NewDraft(
                    identity = DraftIdentity.ProviderThread(ProviderThreadId(72L)),
                    body = "synthetic body",
                    subject = "synthetic subject",
                    createdTimestampMillis = 120L,
                    updatedTimestampMillis = 120L,
                ),
            ).draftSuccessValue()
            assertEquals(
                ComposerSmsOperationResult.IneligibleDraft,
                operations.reserve(reservationRequest(ProviderThreadId(72L), subject, 220L)),
            )
            val participant = drafts.create(
                NewDraft(
                    identity = DraftIdentity.ParticipantSet(
                        DraftParticipantSetKey.fromParticipants(
                            listOf(org.aurorasms.core.model.ParticipantAddress("+15550000000")),
                        ),
                    ),
                    body = "synthetic body",
                    subject = null,
                    createdTimestampMillis = 130L,
                    updatedTimestampMillis = 130L,
                ),
            ).draftSuccessValue()
            assertEquals(
                ComposerSmsOperationResult.StaleWrite,
                operations.reserve(reservationRequest(ProviderThreadId(73L), participant, 230L)),
            )
        } finally {
            database.close()
        }
    }

    private fun openStateDatabase() =
        (StateDatabaseFactory.open(context) as StateDatabaseOpenResult.Opened).database

    private suspend fun RoomDraftRepository.createDraft(
        threadId: ProviderThreadId,
        body: String,
        timestamp: Long,
    ): Draft = create(
        NewDraft(
            identity = DraftIdentity.ProviderThread(threadId),
            body = body,
            subject = null,
            createdTimestampMillis = timestamp,
            updatedTimestampMillis = timestamp,
        ),
    ).draftSuccessValue()

    private fun reservationRequest(
        threadId: ProviderThreadId,
        draft: Draft,
        timestamp: Long,
    ): ComposerSmsReservationRequest = ComposerSmsReservationRequest(
        providerThreadId = threadId,
        draftId = draft.id,
        expectedDraftRevision = draft.revision,
        subscriptionId = AuroraSubscriptionId(1),
        createdTimestampMillis = timestamp,
    )

    private fun providerBinding(providerMessageId: Long): ComposerSmsProviderBinding =
        ComposerSmsProviderBinding(
            providerMessageId = ProviderMessageId(ProviderKind.SMS, providerMessageId),
            providerConversationId = ConversationId(providerMessageId + 10_000L),
            unitCount = 1,
        )
}

private fun <T> ComposerSmsOperationResult<T>.successValue(): T {
    assertTrue("Expected composer success but was $this", this is ComposerSmsOperationResult.Success<T>)
    return (this as ComposerSmsOperationResult.Success<T>).value
}

private fun <T> DraftRepositoryResult<T>.draftSuccessValue(): T {
    assertTrue("Expected draft success but was $this", this is DraftRepositoryResult.Success<T>)
    return (this as DraftRepositoryResult.Success<T>).value
}
