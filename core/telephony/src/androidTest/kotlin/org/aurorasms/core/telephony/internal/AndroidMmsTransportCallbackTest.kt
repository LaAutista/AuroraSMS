// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.telephony.ActiveSubscription
import org.aurorasms.core.telephony.DecodedIncomingMmsRecord
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.MmsProviderDataSource
import org.aurorasms.core.telephony.MmsProviderMessage
import org.aurorasms.core.telephony.OutgoingMmsProviderStatus
import org.aurorasms.core.telephony.OutgoingMmsStatusUpdateOutcome
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderPage
import org.aurorasms.core.telephony.ProviderPageRequest
import org.aurorasms.core.telephony.ProviderStoredMessage
import org.aurorasms.core.telephony.SubscriptionRepository
import org.aurorasms.core.telephony.SubscriptionSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMmsTransportCallbackTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun clearProductionJournal() {
        context.getSharedPreferences(JOURNAL_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun exactAuthenticatedCallbackUpdatesProviderAndAcknowledgesJournal() = runBlocking {
        val provider = CallbackProvider()
        val transport = transport(provider)
        prepareSubmittingJournal()

        val changed = transport.reconcileTransportResult(sentResult())

        assertEquals(OutgoingMmsCallbackDisposition.APPLIED, changed)
        assertEquals(listOf(OutgoingMmsProviderStatus.SENT), provider.statuses)
        assertTrue(journalRecords().isEmpty())
    }

    @Test
    fun providerFailureLeavesDurableCallbackForStartupRecovery() = runBlocking {
        val provider = CallbackProvider(available = false)
        val transport = transport(provider)
        prepareSubmittingJournal()

        assertEquals(
            OutgoingMmsCallbackDisposition.AUTHENTICATED_DEFERRED,
            transport.reconcileTransportResult(failedResult()),
        )
        assertEquals(
            listOf(OutgoingMmsSubmissionJournal.State.CALLBACK_FAILED),
            journalRecords().map { it.state },
        )

        provider.available = true
        assertEquals(
            org.aurorasms.core.telephony.OutgoingMmsRecoveryResult.Available(1, 0, 0),
            transport.recoverOutgoingSubmissions(),
        )
        assertEquals(listOf(OutgoingMmsProviderStatus.FAILED), provider.statuses)
        assertTrue(journalRecords().isEmpty())
    }

    @Test
    fun mismatchedCallbackCannotMutateProviderOrJournal() = runBlocking {
        val provider = CallbackProvider()
        val transport = transport(provider)
        prepareSubmittingJournal()
        val mismatched = sentResult().copy(
            providerMessageId = ProviderMessageId(ProviderKind.MMS, PROVIDER.value + 1L),
        )

        assertEquals(
            OutgoingMmsCallbackDisposition.IGNORED,
            transport.reconcileTransportResult(mismatched),
        )
        assertTrue(provider.statuses.isEmpty())
        assertEquals(
            listOf(OutgoingMmsSubmissionJournal.State.SUBMITTING),
            journalRecords().map { it.state },
        )
    }

    private fun transport(provider: MmsProviderDataSource): AndroidMmsTransport = AndroidMmsTransport(
        context = context,
        roleState = object : DefaultSmsRoleState {
            override fun isRoleAvailable(): Boolean = true
            override fun isRoleHeld(): Boolean = true
        },
        subscriptions = object : SubscriptionRepository {
            override suspend fun activeSubscriptions(): SubscriptionSnapshot =
                SubscriptionSnapshot.Available(
                    listOf(ActiveSubscription(SUBSCRIPTION, 0, "Synthetic", true)),
                )
        },
        stagingStore = MmsPduStagingStore(context),
        provider = provider,
    )

    private fun prepareSubmittingJournal() {
        val journal = OutgoingMmsSubmissionJournal(context)
        assertTrue(journal.reserve(OPERATION, CONVERSATION, TRANSACTION))
        assertTrue(journal.markPrepared(OPERATION, PROVIDER, CONVERSATION))
        assertTrue(journal.markSubmitting(OPERATION, PROVIDER, CONVERSATION))
    }

    private fun journalRecords(): List<OutgoingMmsSubmissionJournal.Record> =
        (
            OutgoingMmsSubmissionJournal(context).recoverySnapshot() as
                OutgoingMmsSubmissionJournal.RecoveryResult.Available
            ).records

    private fun sentResult(): TransportResult.Sent = TransportResult.Sent(
        operationId = OPERATION,
        transport = MessageTransportKind.MMS,
        platformResultCode = Activity.RESULT_OK,
        providerMessageId = PROVIDER,
        providerConversationId = CONVERSATION,
    )

    private fun failedResult(): TransportResult.Failed = TransportResult.Failed(
        operationId = OPERATION,
        transport = MessageTransportKind.MMS,
        reason = TransportResult.FailureReason.PLATFORM_REJECTED,
        retryable = false,
        stage = TransportResult.FailureStage.SENT_CALLBACK,
        providerMessageId = PROVIDER,
        providerConversationId = CONVERSATION,
    )

    private class CallbackProvider(
        var available: Boolean = true,
    ) : MmsProviderDataSource {
        val statuses = mutableListOf<OutgoingMmsProviderStatus>()

        override suspend fun count(): ProviderAccessResult<Long> = ProviderAccessResult.Success(0L)

        override suspend fun readPage(
            request: ProviderPageRequest,
        ): ProviderAccessResult<ProviderPage<MmsProviderMessage>> =
            ProviderAccessResult.Success(ProviderPage(emptyList(), null, true))

        override suspend fun insertIncoming(
            message: DecodedIncomingMmsRecord,
        ): ProviderAccessResult<ProviderStoredMessage> =
            ProviderAccessResult.Unsupported("synthetic")

        override suspend fun updateOutgoingStatus(
            id: ProviderMessageId,
            conversationId: ConversationId,
            status: OutgoingMmsProviderStatus,
        ): ProviderAccessResult<OutgoingMmsStatusUpdateOutcome> {
            if (!available) return ProviderAccessResult.Unavailable("synthetic")
            assertEquals(PROVIDER, id)
            assertEquals(CONVERSATION, conversationId)
            statuses += status
            return ProviderAccessResult.Success(OutgoingMmsStatusUpdateOutcome.APPLIED)
        }
    }

    private companion object {
        const val JOURNAL_NAME = "aurora_outgoing_mms_submission_journal_v1"
        const val TRANSACTION = "callback-test-transaction"
        val OPERATION = MessageId(ProviderKind.PENDING_OPERATION, 101L)
        val PROVIDER = ProviderMessageId(ProviderKind.MMS, 103L)
        val CONVERSATION = ConversationId(105L)
        val SUBSCRIPTION = AuroraSubscriptionId(1)
    }
}
