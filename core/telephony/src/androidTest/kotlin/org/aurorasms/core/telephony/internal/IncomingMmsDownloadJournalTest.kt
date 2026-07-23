// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.INCOMING_MMS_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.telephony.MmsDownloadRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingMmsDownloadJournalTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun completeLifecycleSurvivesEveryReopenAndRequiresExactFileAndProvider() = withStore { name ->
        val first = journal(name)
        assertTrue(first.reserve(request()) is IncomingMmsDownloadJournal.ReserveResult.Reserved)
        assertApplied(first.markStaged(OPERATION, FILE_NAME), IncomingMmsDownloadJournal.State.STAGED)

        val submitting = journal(name)
        assertApplied(
            submitting.markSubmitting(OPERATION, FILE_NAME),
            IncomingMmsDownloadJournal.State.SUBMITTING,
        )
        assertApplied(
            submitting.markSubmissionUnknown(OPERATION, FILE_NAME),
            IncomingMmsDownloadJournal.State.SUBMISSION_UNKNOWN,
        )
        assertTrue(
            submitting.recordSuccessCallback(OPERATION, OTHER_FILE_NAME) is
                IncomingMmsDownloadJournal.TransitionResult.Rejected,
        )
        assertApplied(
            submitting.recordSuccessCallback(OPERATION, FILE_NAME),
            IncomingMmsDownloadJournal.State.CALLBACK_SUCCEEDED,
        )

        val persisted = journal(name)
        assertApplied(
            persisted.markPersisted(OPERATION, FILE_NAME, PROVIDER, CONVERSATION),
            IncomingMmsDownloadJournal.State.PERSISTED,
        )
        assertFalse(persisted.acknowledgePersisted(OPERATION, FILE_NAME, OTHER_PROVIDER, CONVERSATION))
        assertTrue(persisted.acknowledgePersisted(OPERATION, FILE_NAME, PROVIDER, CONVERSATION))
        assertTrue(persisted.records().isEmpty())
    }

    @Test
    fun duplicateNotificationIsSuppressedAcrossDifferentOperationIdsWithoutStoringRawUrl() = withStore { name ->
        val journal = journal(name)
        assertTrue(journal.reserve(request()) is IncomingMmsDownloadJournal.ReserveResult.Reserved)
        val duplicate = journal.reserve(
            request(operation = incomingOperation(17L)),
        )

        assertTrue(duplicate is IncomingMmsDownloadJournal.ReserveResult.Duplicate)
        assertEquals(OPERATION, (duplicate as IncomingMmsDownloadJournal.ReserveResult.Duplicate).record.operationId)
        val stored = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.values.single() as String
        assertFalse(stored.contains(CONTENT_LOCATION))
        assertFalse(stored.contains(TRANSACTION_ID))
        assertFalse(stored.contains("message body", ignoreCase = true))
        assertTrue(journal.recoverySnapshot().toString().contains("REDACTED"))
    }

    @Test
    fun callbackOutcomeCannotChangeAndFailureMustBeAcknowledgedExactly() = withStore { name ->
        val journal = journal(name)
        assertTrue(journal.reserve(request()) is IncomingMmsDownloadJournal.ReserveResult.Reserved)
        assertApplied(journal.markStaged(OPERATION, FILE_NAME), IncomingMmsDownloadJournal.State.STAGED)
        assertApplied(journal.markSubmitting(OPERATION, FILE_NAME), IncomingMmsDownloadJournal.State.SUBMITTING)
        assertApplied(
            journal.recordFailureCallback(OPERATION, FILE_NAME),
            IncomingMmsDownloadJournal.State.CALLBACK_FAILED,
        )
        assertTrue(
            journal.recordSuccessCallback(OPERATION, FILE_NAME) is IncomingMmsDownloadJournal.TransitionResult.Rejected,
        )
        assertFalse(journal.acknowledgeFailure(OPERATION, OTHER_FILE_NAME))
        assertTrue(journal.acknowledgeFailure(OPERATION, FILE_NAME))
    }

    @Test
    fun preSubmissionAbandonmentCannotErasePossiblePlatformWork() = withStore { name ->
        val journal = journal(name)
        assertTrue(journal.reserve(request()) is IncomingMmsDownloadJournal.ReserveResult.Reserved)
        assertTrue(journal.abandonBeforeSubmission(OPERATION, null))

        assertTrue(journal.reserve(request()) is IncomingMmsDownloadJournal.ReserveResult.Reserved)
        assertApplied(journal.markStaged(OPERATION, FILE_NAME), IncomingMmsDownloadJournal.State.STAGED)
        assertFalse(journal.abandonBeforeSubmission(OPERATION, OTHER_FILE_NAME))
        assertApplied(journal.markSubmitting(OPERATION, FILE_NAME), IncomingMmsDownloadJournal.State.SUBMITTING)
        assertFalse(journal.abandonBeforeSubmission(OPERATION, FILE_NAME))
    }

    @Test
    fun checksumMutationBlocksRecoveryAndEveryFurtherTransition() = withStore { name ->
        val journal = journal(name)
        assertTrue(journal.reserve(request()) is IncomingMmsDownloadJournal.ReserveResult.Reserved)
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val key = preferences.all.keys.single()
        val encoded = requireNotNull(preferences.getString(key, null))
        assertTrue(preferences.edit().putString(key, "${encoded}x").commit())

        assertEquals(
            IncomingMmsDownloadJournal.RecoveryResult.PersistenceFailure,
            journal.recoverySnapshot(),
        )
        assertTrue(
            journal.markStaged(OPERATION, FILE_NAME) is IncomingMmsDownloadJournal.TransitionResult.Rejected,
        )
    }

    @Test
    fun capacityAndNamespaceFailClosedWithoutEviction() = withStore { name ->
        val journal = journal(name, maximumEntries = 1)
        assertTrue(journal.reserve(request()) is IncomingMmsDownloadJournal.ReserveResult.Reserved)
        assertEquals(
            IncomingMmsDownloadJournal.ReserveResult.Rejected,
            journal.reserve(request(operation = incomingOperation(19L), transaction = "Tother")),
        )
        val respondVia = MessageId(ProviderKind.PENDING_OPERATION, 23L)
        assertThrows(IllegalArgumentException::class.java) {
            request(operation = respondVia, transaction = "Trespond")
        }
        assertEquals(1, journal.records().size)
    }

    private fun request(
        operation: MessageId = OPERATION,
        transaction: String = TRANSACTION_ID,
    ): MmsDownloadRequest = MmsDownloadRequest(
        operationId = operation,
        contentLocation = CONTENT_LOCATION,
        subscriptionId = AuroraSubscriptionId(2),
        notificationTransactionId = transaction,
        expectedSizeBytes = 4_096L,
        receivedTimestampMillis = 1_720_000_000_000L,
    )

    private fun journal(
        name: String,
        maximumEntries: Int = IncomingMmsDownloadJournal.MAXIMUM_ENTRIES,
    ): IncomingMmsDownloadJournal = IncomingMmsDownloadJournal(
        context = context,
        preferenceName = name,
        maximumEntries = maximumEntries,
        nowMillis = { 1_720_000_000_100L },
    )

    private fun IncomingMmsDownloadJournal.records(): List<IncomingMmsDownloadJournal.Record> =
        when (val snapshot = recoverySnapshot()) {
            is IncomingMmsDownloadJournal.RecoveryResult.Available -> snapshot.records
            IncomingMmsDownloadJournal.RecoveryResult.PersistenceFailure -> error("journal blocked")
        }

    private fun assertApplied(
        result: IncomingMmsDownloadJournal.TransitionResult,
        expected: IncomingMmsDownloadJournal.State,
    ) {
        assertTrue(result is IncomingMmsDownloadJournal.TransitionResult.Applied)
        assertEquals(expected, (result as IncomingMmsDownloadJournal.TransitionResult.Applied).record.state)
    }

    private inline fun withStore(block: (String) -> Unit) {
        val name = "aurora_incoming_mms_test_${UUID.randomUUID()}"
        try {
            block(name)
        } finally {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private companion object {
        fun incomingOperation(offset: Long): MessageId = MessageId(
            ProviderKind.PENDING_OPERATION,
            INCOMING_MMS_OPERATION_ID_BOUNDARY + offset,
        )

        val OPERATION = incomingOperation(11L)
        val PROVIDER = ProviderMessageId(ProviderKind.MMS, 31L)
        val OTHER_PROVIDER = ProviderMessageId(ProviderKind.MMS, 32L)
        val CONVERSATION = ConversationId(41L)
        const val FILE_NAME = "11111111-1111-4111-8111-111111111111.pdu"
        const val OTHER_FILE_NAME = "22222222-2222-4222-8222-222222222222.pdu"
        const val TRANSACTION_ID = "Tjournal|supports separators"
        const val CONTENT_LOCATION = "https://fixtures.example.invalid/private/mms/Tjournal"
    }
}
