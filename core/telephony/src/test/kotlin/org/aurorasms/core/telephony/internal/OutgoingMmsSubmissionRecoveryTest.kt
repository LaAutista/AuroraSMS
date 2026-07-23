// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.telephony.OutgoingMmsProviderStatus
import org.aurorasms.core.telephony.OutgoingMmsRecoveryResult
import org.aurorasms.core.telephony.OutgoingMmsStatusUpdateOutcome
import org.aurorasms.core.telephony.ProviderAccessResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias State = OutgoingMmsSubmissionJournal.State

class OutgoingMmsSubmissionRecoveryTest {
    @Test
    fun carrierBoundaryRequiresAnExactlyAppliedProviderTransition() {
        assertTrue(applied().isApplied())
        assertFalse(absent().isApplied())
        assertFalse(
            ProviderAccessResult.Success(
                OutgoingMmsStatusUpdateOutcome.OWNERSHIP_CONFLICT,
            ).isApplied(),
        )
        assertFalse(
            ProviderAccessResult.Unavailable("synthetic").isApplied(),
        )
    }

    @Test
    fun prePlatformStatesAreTerminalizedAndAcknowledged() = runTest {
        val records = listOf(record(State.PREPARING, providerId = null), record(State.PREPARED))
        val rollbacks = mutableListOf<Long>()
        val updates = mutableListOf<Pair<Long, OutgoingMmsProviderStatus>>()
        val acknowledgements = mutableListOf<Long>()

        val result = recover(
            records = records,
            rollbackPreparing = {
                rollbacks += it.operationId.value
                applied()
            },
            updateStatus = { item, status ->
                updates += item.operationId.value to status
                absent()
            },
            acknowledgeKnownUnsent = {
                acknowledgements += it.operationId.value
                true
            },
        )

        assertEquals(OutgoingMmsRecoveryResult.Available(2, 0, 0), result)
        assertEquals(listOf(OPERATION_ID), rollbacks)
        assertEquals(listOf(OPERATION_ID + 1L to OutgoingMmsProviderStatus.FAILED), updates)
        assertEquals(listOf(OPERATION_ID, OPERATION_ID + 1L), acknowledgements)
    }

    @Test
    fun submittingBecomesUnknownWithoutMutatingProvider() = runTest {
        var markedUnknown = false
        var providerCalls = 0

        val result = recover(
            records = listOf(record(State.SUBMITTING)),
            rollbackPreparing = {
                providerCalls += 1
                applied()
            },
            updateStatus = { _, _ ->
                providerCalls += 1
                applied()
            },
            markSubmissionUnknown = {
                markedUnknown = true
                true
            },
        )

        assertEquals(OutgoingMmsRecoveryResult.Available(1, 0, 1), result)
        assertTrue(markedUnknown)
        assertEquals(0, providerCalls)
    }

    @Test
    fun callbackOutcomesDriveOnlyTheirExactProviderStatus() = runTest {
        val records = listOf(record(State.CALLBACK_SENT), record(State.CALLBACK_FAILED))
        val updates = mutableListOf<Pair<Long, OutgoingMmsProviderStatus>>()
        val acknowledged = mutableListOf<Long>()

        val result = recover(
            records = records,
            updateStatus = { item, status ->
                updates += item.operationId.value to status
                applied()
            },
            acknowledgeCallback = {
                acknowledged += it.operationId.value
                true
            },
        )

        assertEquals(OutgoingMmsRecoveryResult.Available(2, 0, 0), result)
        assertEquals(
            listOf(
                OPERATION_ID to OutgoingMmsProviderStatus.SENT,
                OPERATION_ID + 1L to OutgoingMmsProviderStatus.FAILED,
            ),
            updates,
        )
        assertEquals(listOf(OPERATION_ID, OPERATION_ID + 1L), acknowledged)
    }

    @Test
    fun providerFailureAndOwnershipConflictDeferOnlyTheirRecords() = runTest {
        var acknowledgements = 0
        val records = listOf(record(State.PREPARING, providerId = null), record(State.PREPARED))

        val result = recover(
            records = records,
            rollbackPreparing = { ProviderAccessResult.Unavailable("synthetic") },
            updateStatus = { _, _ ->
                ProviderAccessResult.Success(OutgoingMmsStatusUpdateOutcome.OWNERSHIP_CONFLICT)
            },
            acknowledgeKnownUnsent = {
                acknowledgements += 1
                true
            },
        )

        assertEquals(OutgoingMmsRecoveryResult.Available(0, 2, 0), result)
        assertEquals(0, acknowledgements)
    }

    @Test
    fun existingUnknownSubmissionRemainsQuarantinedWithoutBusyRetry() = runTest {
        var touched = false
        val result = recover(
            records = listOf(record(State.SUBMISSION_UNKNOWN)),
            updateStatus = { _, _ ->
                touched = true
                applied()
            },
        )

        assertEquals(OutgoingMmsRecoveryResult.Available(0, 0, 1), result)
        assertFalse(touched)
    }

    @Test
    fun journalReadOrCommitFailureBlocksNewSubmissions() = runTest {
        assertEquals(
            OutgoingMmsRecoveryResult.JournalBlocked,
            recoverOutgoingMmsSubmissionRecords(
                recoverySnapshot = {
                    OutgoingMmsSubmissionJournal.RecoveryResult.PersistenceFailure
                },
                rollbackPreparing = { applied() },
                updateStatus = { _, _ -> applied() },
                acknowledgeKnownUnsent = { true },
                markSubmissionUnknown = { true },
                acknowledgeCallback = { true },
            ),
        )

        assertEquals(
            OutgoingMmsRecoveryResult.JournalBlocked,
            recover(
                records = listOf(record(State.CALLBACK_SENT)),
                updateStatus = { _, _ -> applied() },
                acknowledgeCallback = { false },
            ),
        )
    }

    private suspend fun recover(
        records: List<OutgoingMmsSubmissionJournal.Record>,
        rollbackPreparing: suspend (
            OutgoingMmsSubmissionJournal.Record,
        ) -> ProviderAccessResult<OutgoingMmsStatusUpdateOutcome> = { applied() },
        updateStatus: suspend (
            OutgoingMmsSubmissionJournal.Record,
            OutgoingMmsProviderStatus,
        ) -> ProviderAccessResult<OutgoingMmsStatusUpdateOutcome> = { _, _ -> applied() },
        acknowledgeKnownUnsent: (OutgoingMmsSubmissionJournal.Record) -> Boolean = { true },
        markSubmissionUnknown: (OutgoingMmsSubmissionJournal.Record) -> Boolean = { true },
        acknowledgeCallback: (OutgoingMmsSubmissionJournal.Record) -> Boolean = { true },
    ): OutgoingMmsRecoveryResult = recoverOutgoingMmsSubmissionRecords(
        recoverySnapshot = { OutgoingMmsSubmissionJournal.RecoveryResult.Available(records) },
        rollbackPreparing = rollbackPreparing,
        updateStatus = updateStatus,
        acknowledgeKnownUnsent = acknowledgeKnownUnsent,
        markSubmissionUnknown = markSubmissionUnknown,
        acknowledgeCallback = acknowledgeCallback,
    )

    private fun record(
        state: State,
        providerId: ProviderMessageId? = PROVIDER,
    ): OutgoingMmsSubmissionJournal.Record {
        val offset = when (state) {
            State.PREPARING,
            State.SUBMITTING,
            State.SUBMISSION_UNKNOWN,
            State.CALLBACK_SENT,
            -> 0L
            State.PREPARED,
            State.CALLBACK_FAILED,
            -> 1L
        }
        return OutgoingMmsSubmissionJournal.Record(
            operationId = MessageId(ProviderKind.PENDING_OPERATION, OPERATION_ID + offset),
            conversationId = ConversationId(CONVERSATION_ID + offset),
            transactionId = "mms-recovery-$offset",
            providerId = providerId?.let {
                ProviderMessageId(ProviderKind.MMS, it.value + offset)
            },
            state = state,
            createdAtMillis = 1_000L,
        )
    }

    private companion object {
        const val OPERATION_ID = 91L
        const val CONVERSATION_ID = 93L
        val PROVIDER = ProviderMessageId(ProviderKind.MMS, 95L)

        fun applied(): ProviderAccessResult<OutgoingMmsStatusUpdateOutcome> =
            ProviderAccessResult.Success(OutgoingMmsStatusUpdateOutcome.APPLIED)

        fun absent(): ProviderAccessResult<OutgoingMmsStatusUpdateOutcome> =
            ProviderAccessResult.Success(OutgoingMmsStatusUpdateOutcome.ROW_ABSENT)
    }
}
