// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.COMPOSER_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.telephony.ActiveSubscription
import org.aurorasms.core.telephony.DecodedIncomingMmsRecord
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.MmsDownloadRequest
import org.aurorasms.core.telephony.MmsProviderDataSource
import org.aurorasms.core.telephony.MmsProviderMessage
import org.aurorasms.core.telephony.MmsSendRequest
import org.aurorasms.core.telephony.MmsSubmissionObserver
import org.aurorasms.core.telephony.MmsSubmissionOwnership
import org.aurorasms.core.telephony.OutgoingMmsAttachment
import org.aurorasms.core.telephony.OutgoingMmsPayload
import org.aurorasms.core.telephony.OutgoingMmsProviderRecord
import org.aurorasms.core.telephony.OutgoingMmsProviderStatus
import org.aurorasms.core.telephony.OutgoingMmsStatusUpdateOutcome
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderPage
import org.aurorasms.core.telephony.ProviderPageRequest
import org.aurorasms.core.telephony.ProviderStoredMessage
import org.aurorasms.core.telephony.RecipientSet
import org.aurorasms.core.telephony.SubscriptionRepository
import org.aurorasms.core.telephony.SubscriptionSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMmsGeneralSubmissionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val submitted = mutableListOf<StagedMmsPdu>()

    @get:Rule
    val sendPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.SEND_SMS)

    @Before
    @After
    fun clearJournalAndStaging() {
        runBlocking {
            submitted.forEach { MmsPduStagingStore(context).cleanup(it.uri, MmsPduDirection.SEND_SOURCE) }
        }
        submitted.clear()
        context.getSharedPreferences(JOURNAL_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun groupSubmissionOwnsOneProviderRowAndJournalBeforeSinglePlatformCall() = runBlocking {
        val provider = CapturingProvider()
        val observedStates = mutableListOf<OutgoingMmsSubmissionJournal.State>()
        val transport = transport(provider) { request, staged, _ ->
            assertTrue(request.recipients.isGroup)
            submitted += staged
            observedStates += journalRecords().single().state
            assertEquals(listOf(OutgoingMmsProviderStatus.OUTBOX), provider.statuses)
        }

        val result = transport.sendMms(request()) as TransportResult.Submitted

        assertEquals(OPERATION, result.operationId)
        assertEquals(PROVIDER, result.providerMessageId)
        assertEquals(CONVERSATION, result.providerConversationId)
        assertEquals(1, submitted.size)
        assertEquals(listOf(OutgoingMmsSubmissionJournal.State.SUBMITTING), observedStates)
        assertEquals(1, provider.messages.size)
        assertEquals(2, provider.messages.single().recipients.size)

        val disposition = transport.reconcileTransportResult(
            TransportResult.Sent(
                operationId = OPERATION,
                transport = MessageTransportKind.MMS,
                platformResultCode = Activity.RESULT_OK,
                unitIndex = 0,
                unitCount = 1,
                providerMessageId = PROVIDER,
                providerConversationId = CONVERSATION,
            ),
        )
        assertEquals(OutgoingMmsCallbackDisposition.APPLIED, disposition)
        assertEquals(
            listOf(OutgoingMmsProviderStatus.OUTBOX, OutgoingMmsProviderStatus.SENT),
            provider.statuses,
        )
        assertTrue(journalRecords().isEmpty())
    }

    @Test
    fun composerSubmissionAwaitsBothCallerCheckpointsAndPreservesOrigin() = runBlocking {
        val provider = CapturingProvider()
        val checkpoints = mutableListOf<String>()
        val transport = transport(provider) { request, staged, _ ->
            assertEquals(listOf("prepared", "submitting"), checkpoints)
            assertEquals(TransportResult.OperationOrigin.COMPOSER, request.operationOrigin)
            submitted += staged
        }
        val observer = object : MmsSubmissionObserver {
            override suspend fun onPrepared(
                providerId: ProviderMessageId,
                providerConversationId: ConversationId,
                unitCount: Int,
            ): Boolean {
                assertEquals(PROVIDER, providerId)
                assertEquals(CONVERSATION, providerConversationId)
                assertEquals(1, unitCount)
                checkpoints += "prepared"
                return true
            }

            override suspend fun onSubmitting(
                providerId: ProviderMessageId,
                providerConversationId: ConversationId,
                unitCount: Int,
            ): Boolean {
                assertEquals(PROVIDER, providerId)
                assertEquals(CONVERSATION, providerConversationId)
                assertEquals(1, unitCount)
                checkpoints += "submitting"
                return true
            }
        }

        val result = transport.sendMms(
            request().copy(operationOrigin = TransportResult.OperationOrigin.COMPOSER),
            MmsSubmissionOwnership.CallerOwned(observer),
        ) as TransportResult.Submitted

        assertEquals(listOf("prepared", "submitting"), checkpoints)
        assertEquals(TransportResult.OperationOrigin.COMPOSER, result.operationOrigin)
        assertEquals(listOf(OutgoingMmsProviderStatus.OUTBOX), provider.statuses)
    }

    @Test
    fun exceptionAfterSubmittingBecomesUnknownAndNeverRollsBackProviderRow() = runBlocking {
        val provider = CapturingProvider()
        val transport = transport(provider) { _, staged, _ ->
            submitted += staged
            assertEquals(OutgoingMmsSubmissionJournal.State.SUBMITTING, journalRecords().single().state)
            error("synthetic platform uncertainty")
        }

        val result = transport.sendMms(request()) as TransportResult.Failed

        assertEquals(TransportResult.FailureStage.SUBMISSION_UNKNOWN, result.stage)
        assertEquals(false, result.retryable)
        assertEquals(OutgoingMmsSubmissionJournal.State.SUBMISSION_UNKNOWN, journalRecords().single().state)
        assertEquals(listOf(OutgoingMmsProviderStatus.OUTBOX), provider.statuses)
        assertEquals(0, provider.rollbacks)
    }

    @Test
    fun providerRefusalCleansJournalAndNeverCrossesPlatformBoundary() = runBlocking {
        val provider = CapturingProvider(refuseInsert = true)
        var platformCalls = 0
        val transport = transport(provider) { _, _, _ -> platformCalls += 1 }

        val result = transport.sendMms(request())

        assertTrue(result is TransportResult.Rejected)
        assertEquals(0, platformCalls)
        assertEquals(1, provider.rollbacks)
        assertTrue(journalRecords().isEmpty())
    }

    private fun transport(
        provider: MmsProviderDataSource,
        submitter: (MmsSendRequest, StagedMmsPdu, PendingIntent) -> Unit,
    ): AndroidMmsTransport = AndroidMmsTransport(
        context = context,
        roleState = HeldRole,
        subscriptions = ActiveSubscriptions,
        stagingStore = MmsPduStagingStore(context),
        provider = provider,
        sendSubmitter = submitter,
    )

    private fun request(): MmsSendRequest = MmsSendRequest(
        operationId = OPERATION,
        recipients = GROUP,
        payload = OutgoingMmsPayload.Message(
            text = "Synthetic group body",
            subject = "Synthetic group subject",
            attachments = listOf(
                (
                    OutgoingMmsAttachment.create(
                        OutgoingMmsAttachment.IMAGE_PNG,
                        byteArrayOf(1, 2, 3),
                    ) as OutgoingMmsAttachment.CreationResult.Valid
                    ).attachment,
            ),
        ),
        subscriptionId = SUBSCRIPTION,
        providerThreadId = THREAD,
    )

    private fun journalRecords(): List<OutgoingMmsSubmissionJournal.Record> =
        (
            OutgoingMmsSubmissionJournal(context).recoverySnapshot() as
                OutgoingMmsSubmissionJournal.RecoveryResult.Available
            ).records

    private object HeldRole : DefaultSmsRoleState {
        override fun isRoleAvailable(): Boolean = true
        override fun isRoleHeld(): Boolean = true
    }

    private object ActiveSubscriptions : SubscriptionRepository {
        override suspend fun activeSubscriptions(): SubscriptionSnapshot =
            SubscriptionSnapshot.Available(
                listOf(ActiveSubscription(SUBSCRIPTION, 0, "Synthetic", true)),
            )
    }

    private class CapturingProvider(
        private val refuseInsert: Boolean = false,
    ) : MmsProviderDataSource {
        val messages = mutableListOf<OutgoingMmsProviderRecord>()
        val statuses = mutableListOf<OutgoingMmsProviderStatus>()
        var rollbacks = 0

        override suspend fun count(): ProviderAccessResult<Long> = ProviderAccessResult.Success(0L)

        override suspend fun readPage(
            request: ProviderPageRequest,
        ): ProviderAccessResult<ProviderPage<MmsProviderMessage>> =
            ProviderAccessResult.Success(ProviderPage(emptyList(), null, true))

        override suspend fun insertIncoming(
            message: DecodedIncomingMmsRecord,
        ): ProviderAccessResult<ProviderStoredMessage> = ProviderAccessResult.Unsupported("synthetic")

        override suspend fun insertOutgoing(
            message: OutgoingMmsProviderRecord,
        ): ProviderAccessResult<ProviderStoredMessage> {
            if (refuseInsert) return ProviderAccessResult.Unavailable("synthetic")
            messages += message
            return ProviderAccessResult.Success(ProviderStoredMessage(PROVIDER, CONVERSATION))
        }

        override suspend fun updateOutgoingStatus(
            id: ProviderMessageId,
            conversationId: ConversationId,
            status: OutgoingMmsProviderStatus,
        ): ProviderAccessResult<OutgoingMmsStatusUpdateOutcome> {
            if (id != PROVIDER || conversationId != CONVERSATION) {
                return ProviderAccessResult.InvalidInput("synthetic identity")
            }
            statuses += status
            return ProviderAccessResult.Success(OutgoingMmsStatusUpdateOutcome.APPLIED)
        }

        override suspend fun rollbackOutgoingPreparation(
            operationId: MessageId,
            conversationId: ConversationId,
            transactionId: String,
        ): ProviderAccessResult<OutgoingMmsStatusUpdateOutcome> {
            rollbacks += 1
            return ProviderAccessResult.Success(OutgoingMmsStatusUpdateOutcome.ROW_ABSENT)
        }
    }

    private companion object {
        const val JOURNAL_NAME = "aurora_outgoing_mms_submission_journal_v1"
        val SUBSCRIPTION = AuroraSubscriptionId(1)
        val THREAD = ProviderThreadId(71L)
        val CONVERSATION = ConversationId(71L)
        val PROVIDER = ProviderMessageId(ProviderKind.MMS, 81L)
        val OPERATION = MessageId(
            ProviderKind.PENDING_OPERATION,
            COMPOSER_OPERATION_ID_BOUNDARY + 301L,
        )
        val GROUP = (
            RecipientSet.parse(listOf("+15551230001", "+15551230002")) as
                RecipientSet.CreationResult.Valid
            ).recipients
    }
}
