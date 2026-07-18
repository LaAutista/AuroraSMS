// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.telephony.OutgoingSmsRollbackOutcome
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.TransportOwnedSmsRecoveryResult
import org.aurorasms.core.telephony.acceptsNewSubmissions
import org.aurorasms.core.telephony.followUpRequired
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportOwnedSubmissionRecoveryTest {
    @Test
    fun absentPreparedRowIsRetiredAndConflictIsQuarantinedWithoutAcknowledgement() = runTest {
        val absent = record(operationId = 11L, providerId = 21L)
        val conflict = record(operationId = 12L, providerId = 22L)
        val acknowledged = mutableListOf<Long>()
        val quarantined = mutableListOf<Long>()

        val result = recoverTransportOwnedSubmissionRecords(
            recoverySnapshot = { available(absent, conflict) },
            rollbackOutgoing = { record ->
                ProviderAccessResult.Success(
                    if (record == absent) {
                        OutgoingSmsRollbackOutcome.ROW_ABSENT
                    } else {
                        OutgoingSmsRollbackOutcome.OWNERSHIP_CONFLICT
                    },
                )
            },
            acknowledgeKnownUnsent = { record ->
                acknowledged += record.operationId
                true
            },
            quarantineKnownUnsent = { record ->
                quarantined += record.operationId
                true
            },
            recordSubmissionUnknown = { error("no submitting record") },
        )

        assertEquals(TransportOwnedSmsRecoveryResult.Available(2, 0), result)
        assertEquals(listOf(absent.operationId), acknowledged)
        assertEquals(listOf(conflict.operationId), quarantined)
        assertTrue(result.acceptsNewSubmissions)
        assertFalse(result.followUpRequired)
    }

    @Test
    fun transientPreparedFailureDefersExactRecordAndContinuesIndependentRecovery() = runTest {
        val deferred = record(operationId = 31L, providerId = 41L)
        val independent = record(operationId = 32L, providerId = 42L)
        val rollbackOrder = mutableListOf<Long>()
        val acknowledged = mutableListOf<Long>()

        val result = recoverTransportOwnedSubmissionRecords(
            recoverySnapshot = { available(deferred, independent) },
            rollbackOutgoing = { record ->
                rollbackOrder += record.operationId
                if (record == deferred) {
                    ProviderAccessResult.Unavailable("synthetic transient provider failure")
                } else {
                    ProviderAccessResult.Success(OutgoingSmsRollbackOutcome.TERMINALIZED)
                }
            },
            acknowledgeKnownUnsent = { record ->
                acknowledged += record.operationId
                true
            },
            quarantineKnownUnsent = { error("no ownership conflict") },
            recordSubmissionUnknown = { error("no submitting record") },
        )

        assertEquals(TransportOwnedSmsRecoveryResult.Available(1, 1), result)
        assertEquals(listOf(deferred.operationId, independent.operationId), rollbackOrder)
        assertEquals(listOf(independent.operationId), acknowledged)
        assertTrue(result.acceptsNewSubmissions)
        assertTrue(result.followUpRequired)
    }

    @Test
    fun submittingRecordBecomesUnknownWithoutProviderRollback() = runTest {
        val submitting = record(
            operationId = 51L,
            providerId = 61L,
            state = OutgoingSmsSubmissionJournal.State.SUBMITTING,
        )
        val unknown = mutableListOf<Long>()

        val result = recoverTransportOwnedSubmissionRecords(
            recoverySnapshot = { available(submitting) },
            rollbackOutgoing = { error("submitting recovery must not touch provider") },
            acknowledgeKnownUnsent = { error("submitting recovery must not acknowledge") },
            quarantineKnownUnsent = { error("submitting recovery must not quarantine") },
            recordSubmissionUnknown = { record ->
                unknown += record.operationId
                true
            },
        )

        assertEquals(TransportOwnedSmsRecoveryResult.Available(1, 0), result)
        assertEquals(listOf(submitting.operationId), unknown)
    }

    @Test
    fun corruptJournalIsTheGlobalFailClosedGate() = runTest {
        val result = recoverTransportOwnedSubmissionRecords(
            recoverySnapshot = {
                OutgoingSmsSubmissionJournal.RecoverySnapshotResult.PersistenceFailure
            },
            rollbackOutgoing = { error("corrupt snapshot has no records") },
            acknowledgeKnownUnsent = { error("corrupt snapshot has no records") },
            quarantineKnownUnsent = { error("corrupt snapshot has no records") },
            recordSubmissionUnknown = { error("corrupt snapshot has no records") },
        )

        assertEquals(TransportOwnedSmsRecoveryResult.JournalBlocked, result)
        assertFalse(result.acceptsNewSubmissions)
        assertTrue(result.followUpRequired)
    }

    private fun available(
        vararg records: OutgoingSmsSubmissionJournal.Record,
    ): OutgoingSmsSubmissionJournal.RecoverySnapshotResult.Available =
        OutgoingSmsSubmissionJournal.RecoverySnapshotResult.Available(records.toList())

    private fun record(
        operationId: Long,
        providerId: Long,
        state: OutgoingSmsSubmissionJournal.State = OutgoingSmsSubmissionJournal.State.PREPARED,
    ): OutgoingSmsSubmissionJournal.Record = OutgoingSmsSubmissionJournal.Record(
        operationId = operationId,
        providerId = ProviderMessageId(ProviderKind.SMS, providerId),
        conversationId = ConversationId(providerId + 1L),
        unitCount = 1,
        state = state,
        createdAtMillis = 1_000L,
        expiresAtMillis = 2_000L,
    )
}
