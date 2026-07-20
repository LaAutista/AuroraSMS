// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.COMPOSER_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageDeliveryFingerprint
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.telephony.ActiveSubscription
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.IncomingSmsRecord
import org.aurorasms.core.telephony.OutgoingSmsRecord
import org.aurorasms.core.telephony.OutgoingSmsRollbackOutcome
import org.aurorasms.core.telephony.OutgoingSmsStatusUpdateOutcome
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderPage
import org.aurorasms.core.telephony.ProviderPageRequest
import org.aurorasms.core.telephony.ProviderStoredMessage
import org.aurorasms.core.telephony.RecipientSet
import org.aurorasms.core.telephony.SmsProviderDataSource
import org.aurorasms.core.telephony.SmsProviderMessage
import org.aurorasms.core.telephony.SmsProviderStatus
import org.aurorasms.core.telephony.SmsSendRequest
import org.aurorasms.core.telephony.SmsSubmissionObserver
import org.aurorasms.core.telephony.SmsSubmissionOwnership
import org.aurorasms.core.telephony.SubscriptionRepository
import org.aurorasms.core.telephony.SubscriptionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSmsTransportRolePreflightTest {
    @Test
    fun lostRoleRejectsComposerBeforeAnyProviderCheckpointOrPlatformSubmissionSetup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val subscriptionId = AuroraSubscriptionId(7)
        val roleState = AuthoritativeLostRoleState()
        val subscriptions = CountingSubscriptionRepository(subscriptionId)
        val provider = CountingSmsProviderDataSource()
        val observer = CountingSubmissionObserver()
        val transport = AndroidSmsTransport(
            context = context,
            roleState = roleState,
            subscriptions = subscriptions,
            smsProvider = provider,
            mmsTransport = AndroidMmsTransport(
                context = context,
                roleState = roleState,
                subscriptions = subscriptions,
                stagingStore = MmsPduStagingStore(context),
                provider = AndroidMmsProviderDataSource(context, roleState),
            ),
        )
        val recipients = RecipientSet.parse(listOf("+15550100001"))
        assertTrue(recipients is RecipientSet.CreationResult.Valid)
        val request = SmsSendRequest(
            operationId = MessageId(
                ProviderKind.PENDING_OPERATION,
                COMPOSER_OPERATION_ID_BOUNDARY + 101L,
            ),
            recipients = (recipients as RecipientSet.CreationResult.Valid).recipients,
            body = "Synthetic preflight message",
            subscriptionId = subscriptionId,
            requestDeliveryReport = false,
            operationOrigin = TransportResult.OperationOrigin.COMPOSER,
        )

        val result = transport.sendSms(
            request = request,
            ownership = SmsSubmissionOwnership.CallerOwned(observer),
        )

        assertEquals(
            TransportResult.Rejected(
                operationId = request.operationId,
                transport = request.recipients.requiredTransport(),
                reason = TransportResult.FailureReason.ROLE_NOT_HELD,
                operationOrigin = TransportResult.OperationOrigin.COMPOSER,
            ),
            result,
        )
        assertEquals(1, roleState.heldChecks)
        assertEquals(1, subscriptions.snapshotReads)
        assertEquals(0, provider.insertCalls)
        assertEquals(0, provider.armCalls)
        assertEquals(0, provider.rollbackCalls)
        assertEquals(0, provider.statusCalls)
        assertEquals(0, provider.otherMutationCalls)
        assertEquals(0, observer.preparedCheckpoints)
        assertEquals(0, observer.submittingCheckpoints)
    }

    private class AuthoritativeLostRoleState : DefaultSmsRoleState {
        var heldChecks: Int = 0
            private set

        override fun isRoleAvailable(): Boolean = true

        override fun isRoleHeld(): Boolean {
            heldChecks += 1
            return false
        }
    }

    private class CountingSubscriptionRepository(
        private val subscriptionId: AuroraSubscriptionId,
    ) : SubscriptionRepository {
        var snapshotReads: Int = 0
            private set

        override suspend fun activeSubscriptions(): SubscriptionSnapshot {
            snapshotReads += 1
            return SubscriptionSnapshot.Available(
                listOf(
                    ActiveSubscription(
                        id = subscriptionId,
                        slotIndex = 0,
                        displayLabel = "Synthetic subscription",
                        smsCapable = true,
                    ),
                ),
            )
        }
    }

    private class CountingSubmissionObserver : SmsSubmissionObserver {
        var preparedCheckpoints: Int = 0
            private set
        var submittingCheckpoints: Int = 0
            private set

        override suspend fun onPrepared(
            providerId: ProviderMessageId,
            providerConversationId: ConversationId,
            unitCount: Int,
        ): Boolean {
            preparedCheckpoints += 1
            return true
        }

        override suspend fun onSubmitting(
            providerId: ProviderMessageId,
            providerConversationId: ConversationId,
            unitCount: Int,
        ): Boolean {
            submittingCheckpoints += 1
            return true
        }
    }

    private class CountingSmsProviderDataSource : SmsProviderDataSource {
        var insertCalls: Int = 0
            private set
        var armCalls: Int = 0
            private set
        var rollbackCalls: Int = 0
            private set
        var statusCalls: Int = 0
            private set
        var otherMutationCalls: Int = 0
            private set

        override suspend fun count(): ProviderAccessResult<Long> = ProviderAccessResult.Success(0L)

        override suspend fun readPage(
            request: ProviderPageRequest,
        ): ProviderAccessResult<ProviderPage<SmsProviderMessage>> =
            ProviderAccessResult.Success(ProviderPage(emptyList(), next = null, exhausted = true))

        override suspend fun insertIncoming(
            message: IncomingSmsRecord,
        ): ProviderAccessResult<ProviderStoredMessage> {
            insertCalls += 1
            return ProviderAccessResult.Unavailable("unexpected incoming insert")
        }

        override suspend fun markIncomingHandled(
            deliveryFingerprint: MessageDeliveryFingerprint,
            providerId: ProviderMessageId,
            conversationId: ConversationId,
        ): ProviderAccessResult<Unit> {
            otherMutationCalls += 1
            return ProviderAccessResult.Unavailable("unexpected incoming acknowledgement")
        }

        override suspend fun insertOutgoing(
            message: OutgoingSmsRecord,
        ): ProviderAccessResult<ProviderStoredMessage> {
            insertCalls += 1
            return ProviderAccessResult.Unavailable("unexpected outgoing insert")
        }

        override suspend fun armOutgoing(
            id: ProviderMessageId,
        ): ProviderAccessResult<Unit> {
            armCalls += 1
            return ProviderAccessResult.Unavailable("unexpected outgoing arm")
        }

        override suspend fun rollbackOutgoing(
            id: ProviderMessageId,
            conversationId: ConversationId,
        ): ProviderAccessResult<OutgoingSmsRollbackOutcome> {
            rollbackCalls += 1
            return ProviderAccessResult.Success(OutgoingSmsRollbackOutcome.ROW_ABSENT)
        }

        override suspend fun updateOutgoingStatus(
            id: ProviderMessageId,
            conversationId: ConversationId,
            status: SmsProviderStatus,
        ): ProviderAccessResult<OutgoingSmsStatusUpdateOutcome> {
            statusCalls += 1
            return ProviderAccessResult.Success(OutgoingSmsStatusUpdateOutcome.ROW_ABSENT)
        }

        override suspend fun updateStatus(
            id: ProviderMessageId,
            status: SmsProviderStatus,
        ): ProviderAccessResult<Unit> {
            statusCalls += 1
            return ProviderAccessResult.Success(Unit)
        }
    }
}
