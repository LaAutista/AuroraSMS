// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.aurorasms.core.model.COMPOSER_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.INCOMING_MMS_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OutgoingMmsSubmissionJournalTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun lifecycleSurvivesReopenAndPersistsCallbackBeforeAcknowledgement() = withStore { name ->
        val first = journal(name)
        assertTrue(first.reserve(OPERATION, CONVERSATION, TRANSACTION_ID))
        assertEquals(listOf(OutgoingMmsSubmissionJournal.State.PREPARING), first.states())

        val reopened = journal(name)
        assertTrue(reopened.markPrepared(OPERATION, PROVIDER, CONVERSATION))
        assertTrue(reopened.markSubmitting(OPERATION, PROVIDER, CONVERSATION))
        assertTrue(reopened.markSubmissionUnknown(OPERATION, PROVIDER, CONVERSATION))
        assertTrue(reopened.recordCallback(OPERATION, PROVIDER, CONVERSATION, sent = true))
        assertEquals(listOf(OutgoingMmsSubmissionJournal.State.CALLBACK_SENT), reopened.states())

        val callbackReopen = journal(name)
        assertTrue(callbackReopen.acknowledgeCallback(OPERATION, PROVIDER, CONVERSATION))
        assertTrue(callbackReopen.states().isEmpty())
    }

    @Test
    fun callbackRequiresExactSubmittingOwnershipAndCannotChangeOutcome() = withStore { name ->
        val journal = journal(name)
        assertTrue(journal.reserve(OPERATION, CONVERSATION, TRANSACTION_ID))
        assertTrue(journal.markPrepared(OPERATION, PROVIDER, CONVERSATION))
        assertFalse(journal.recordCallback(OPERATION, PROVIDER, CONVERSATION, sent = true))
        assertTrue(journal.markSubmitting(OPERATION, PROVIDER, CONVERSATION))
        assertFalse(journal.recordCallback(OPERATION, OTHER_PROVIDER, CONVERSATION, sent = true))
        assertTrue(journal.recordCallback(OPERATION, PROVIDER, CONVERSATION, sent = false))
        assertFalse(journal.recordCallback(OPERATION, PROVIDER, CONVERSATION, sent = true))
        assertEquals(listOf(OutgoingMmsSubmissionJournal.State.CALLBACK_FAILED), journal.states())
    }

    @Test
    fun knownUnsentAcknowledgementAcceptsOnlyPrePlatformStates() = withStore { name ->
        val journal = journal(name)
        assertTrue(journal.reserve(OPERATION, CONVERSATION, TRANSACTION_ID))
        assertTrue(journal.acknowledgeKnownUnsent(OPERATION))
        assertTrue(journal.states().isEmpty())

        assertTrue(journal.reserve(OPERATION, CONVERSATION, TRANSACTION_ID))
        assertTrue(journal.markPrepared(OPERATION, PROVIDER, CONVERSATION))
        assertTrue(journal.markSubmitting(OPERATION, PROVIDER, CONVERSATION))
        assertFalse(journal.acknowledgeKnownUnsent(OPERATION))
        assertEquals(listOf(OutgoingMmsSubmissionJournal.State.SUBMITTING), journal.states())
    }

    @Test
    fun checksumMutationBlocksRecoveryAndFurtherTransitions() = withStore { name ->
        val journal = journal(name)
        assertTrue(journal.reserve(OPERATION, CONVERSATION, TRANSACTION_ID))
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val key = requireNotNull(preferences.all.keys.singleOrNull())
        val encoded = requireNotNull(preferences.getString(key, null))
        assertTrue(preferences.edit().putString(key, encoded.replace(TRANSACTION_ID, "changed")).commit())

        assertEquals(
            OutgoingMmsSubmissionJournal.RecoveryResult.PersistenceFailure,
            journal.recoverySnapshot(),
        )
        assertFalse(journal.markPrepared(OPERATION, PROVIDER, CONVERSATION))
    }

    @Test
    fun capacityNeverEvictsAndRecordsRemainContentFree() = withStore { name ->
        val journal = journal(name, maximumEntries = 1)
        val second = MessageId(ProviderKind.PENDING_OPERATION, OPERATION.value + 1L)
        val composer = MessageId(ProviderKind.PENDING_OPERATION, COMPOSER_OPERATION_ID_BOUNDARY)

        assertTrue(journal.reserve(OPERATION, CONVERSATION, TRANSACTION_ID))
        assertFalse(journal.reserve(second, CONVERSATION, "mms-second"))
        assertFalse(journal.reserve(composer, CONVERSATION, "mms-composer"))
        val stored = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.values.single()
        assertTrue(stored is String)
        assertFalse((stored as String).contains("recipient", ignoreCase = true))
        assertFalse(stored.contains("audio", ignoreCase = true))
        assertTrue(journal.recoverySnapshot().toString().contains("REDACTED"))
    }

    @Test
    fun outgoingJournalAcceptsRespondAndComposerButRejectsIncomingNamespace() = withStore { name ->
        val journal = journal(name)
        val composer = MessageId(
            ProviderKind.PENDING_OPERATION,
            COMPOSER_OPERATION_ID_BOUNDARY + 1L,
        )
        val incoming = MessageId(
            ProviderKind.PENDING_OPERATION,
            INCOMING_MMS_OPERATION_ID_BOUNDARY + 1L,
        )

        assertTrue(journal.reserve(composer, CONVERSATION, "mms-composer"))
        assertFalse(journal.reserve(incoming, CONVERSATION, "mms-incoming"))
    }

    private fun journal(
        name: String,
        maximumEntries: Int = OutgoingMmsSubmissionJournal.MAXIMUM_ENTRIES,
    ): OutgoingMmsSubmissionJournal = OutgoingMmsSubmissionJournal(
        context = context,
        preferenceName = name,
        maximumEntries = maximumEntries,
        nowMillis = { 1_000L },
    )

    private fun OutgoingMmsSubmissionJournal.states(): List<OutgoingMmsSubmissionJournal.State> =
        when (val snapshot = recoverySnapshot()) {
            is OutgoingMmsSubmissionJournal.RecoveryResult.Available ->
                snapshot.records.map { it.state }
            OutgoingMmsSubmissionJournal.RecoveryResult.PersistenceFailure -> error("journal blocked")
        }

    private inline fun withStore(block: (String) -> Unit) {
        val name = "aurora_outgoing_mms_test_${UUID.randomUUID()}"
        try {
            block(name)
        } finally {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private companion object {
        val OPERATION = MessageId(ProviderKind.PENDING_OPERATION, 81L)
        val PROVIDER = ProviderMessageId(ProviderKind.MMS, 83L)
        val OTHER_PROVIDER = ProviderMessageId(ProviderKind.MMS, 84L)
        val CONVERSATION = ConversationId(85L)
        const val TRANSACTION_ID = "aurora-test-transaction"
    }
}
