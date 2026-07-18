// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import java.util.concurrent.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageDeliveryFingerprint
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.notifications.IncomingMessageNotification
import org.aurorasms.core.notifications.MessageNotifier
import org.aurorasms.core.notifications.NotificationCancelResult
import org.aurorasms.core.notifications.NotificationConfig
import org.aurorasms.core.notifications.NotificationPostResult
import org.aurorasms.core.telephony.ContactResolver
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.IncomingMessage
import org.aurorasms.core.telephony.IncomingMessageSink
import org.aurorasms.core.telephony.IncomingPersistResult
import org.aurorasms.core.telephony.IncomingSmsNotificationReplay
import org.aurorasms.core.telephony.IncomingSmsNotificationReplayRequest
import org.aurorasms.core.telephony.IncomingSmsRecord
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderStoredMessage
import org.aurorasms.core.telephony.SmsProviderDataSource

class IncomingMessageOrchestrator(
    private val roleState: DefaultSmsRoleState,
    private val smsProvider: SmsProviderDataSource,
    private val contactResolver: ContactResolver,
    private val messageNotifier: MessageNotifier,
    private val replyTargets: ReplyTargetRegistry,
    private val notificationConfig: NotificationConfig = NotificationConfig(),
    private val onProviderInsertComplete: suspend () -> Unit = {},
) : IncomingMessageSink {
    private val incomingWorkMutex = Mutex()

    override suspend fun persist(message: IncomingMessage): IncomingPersistResult =
        when (message) {
            is IncomingMessage.Sms -> incomingWorkMutex.withLock {
                if (!roleState.isRoleHeld()) {
                    IncomingPersistResult.Rejected(IncomingPersistResult.Reason.ROLE_NOT_HELD)
                } else {
                    persistSms(message)
                }
            }
            is IncomingMessage.MmsWapPush ->
                IncomingPersistResult.Rejected(IncomingPersistResult.Reason.CODEC_UNAVAILABLE)
        }

    /**
     * Rebuilds notifications for provider rows whose durable delivery journal was
     * stored but never acknowledged. Work is bounded so a damaged or busy provider
     * cannot monopolize the application scope; later lifecycle triggers retry it.
     */
    internal suspend fun recoverPendingNotifications(
        batchLimit: Int = IncomingSmsNotificationReplayRequest.MAXIMUM_LIMIT,
        maximumBatches: Int = DEFAULT_MAXIMUM_RECOVERY_BATCHES,
    ): IncomingNotificationRecoveryResult = incomingWorkMutex.withLock {
        require(maximumBatches in 1..ABSOLUTE_MAXIMUM_RECOVERY_BATCHES) {
            "maximumBatches is out of bounds"
        }
        if (!roleState.isRoleHeld()) {
            return@withLock IncomingNotificationRecoveryResult.Deferred(recoveredCount = 0)
        }
        val request = IncomingSmsNotificationReplayRequest(batchLimit)
        var recoveredCount = 0
        repeat(maximumBatches) {
            if (!roleState.isRoleHeld()) {
                return@withLock IncomingNotificationRecoveryResult.Deferred(recoveredCount)
            }
            val pending = when (val result = smsProvider.readPendingIncomingNotifications(request)) {
                is ProviderAccessResult.Success -> result.value
                ProviderAccessResult.RoleRequired,
                ProviderAccessResult.PermissionDenied,
                is ProviderAccessResult.InvalidInput,
                is ProviderAccessResult.Unsupported,
                is ProviderAccessResult.Unavailable ->
                    return@withLock IncomingNotificationRecoveryResult.Deferred(recoveredCount)
            }
            if (pending.isEmpty()) {
                return@withLock IncomingNotificationRecoveryResult.Complete(recoveredCount)
            }
            for (replay in pending) {
                if (!roleState.isRoleHeld()) {
                    return@withLock IncomingNotificationRecoveryResult.Deferred(recoveredCount)
                }
                if (notifyAndAcknowledge(replay) !is IncomingPersistResult.Persisted) {
                    return@withLock IncomingNotificationRecoveryResult.Deferred(recoveredCount)
                }
                recoveredCount += 1
            }
        }
        IncomingNotificationRecoveryResult.Deferred(recoveredCount)
    }

    /**
     * Fences role loss against live persistence/recovery and removes only the
     * exact incoming generations posted during this held-role process epoch.
     */
    internal suspend fun onRoleLost() = incomingWorkMutex.withLock {
        repeat(ROLE_LOSS_CANCELLATION_MAXIMUM_ATTEMPTS) { attempt ->
            if (roleState.isRoleHeld()) return@withLock
            val result = try {
                messageNotifier.cancelAllIncoming()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                NotificationCancelResult.RetryableFailure
            }
            if (result != NotificationCancelResult.RetryableFailure) {
                replyTargets.clear()
                return@withLock
            }
            if (attempt < ROLE_LOSS_CANCELLATION_MAXIMUM_ATTEMPTS - 1) {
                delay(ROLE_LOSS_CANCELLATION_RETRY_MILLIS)
            }
        }
        if (!roleState.isRoleHeld()) replyTargets.clear()
    }

    private suspend fun persistSms(message: IncomingMessage.Sms): IncomingPersistResult {
        val insert = smsProvider.insertIncoming(
            IncomingSmsRecord(
                deliveryFingerprint = message.deliveryFingerprint,
                sender = message.sender,
                body = message.body,
                sentTimestampMillis = message.sentTimestampMillis,
                receivedTimestampMillis = message.receivedTimestampMillis,
                subscriptionId = message.subscriptionId,
            ),
        )
        return when (insert) {
            is ProviderAccessResult.Success -> {
                val stored = insert.value
                onProviderInsertComplete()
                if (!stored.notificationRequired) {
                    IncomingPersistResult.Duplicate(
                        providerId = stored.providerId,
                        conversationId = stored.conversationId,
                    )
                } else {
                    notifyAndAcknowledge(
                        deliveryFingerprint = message.deliveryFingerprint,
                        sender = message.sender,
                        body = message.body,
                        receivedTimestampMillis = message.receivedTimestampMillis,
                        subscriptionId = message.subscriptionId,
                        stored = stored,
                    )
                }
            }

            ProviderAccessResult.RoleRequired ->
                IncomingPersistResult.Rejected(IncomingPersistResult.Reason.ROLE_NOT_HELD)
            ProviderAccessResult.PermissionDenied ->
                IncomingPersistResult.Rejected(IncomingPersistResult.Reason.PERMISSION_DENIED)
            is ProviderAccessResult.InvalidInput ->
                IncomingPersistResult.Rejected(IncomingPersistResult.Reason.MALFORMED_INPUT)
            is ProviderAccessResult.Unsupported,
            is ProviderAccessResult.Unavailable ->
                IncomingPersistResult.Rejected(IncomingPersistResult.Reason.PROVIDER_UNAVAILABLE)
        }
    }

    private suspend fun notifyAndAcknowledge(
        replay: IncomingSmsNotificationReplay,
    ): IncomingPersistResult = notifyAndAcknowledge(
        deliveryFingerprint = replay.deliveryFingerprint,
        sender = replay.sender,
        body = replay.body,
        receivedTimestampMillis = replay.receivedTimestampMillis,
        subscriptionId = replay.subscriptionId,
        stored = ProviderStoredMessage(
            providerId = replay.providerId,
            conversationId = replay.conversationId,
        ),
    )

    private suspend fun notifyAndAcknowledge(
        deliveryFingerprint: MessageDeliveryFingerprint,
        sender: ParticipantAddress,
        body: String,
        receivedTimestampMillis: Long,
        subscriptionId: AuroraSubscriptionId?,
        stored: ProviderStoredMessage,
    ): IncomingPersistResult {
        val displayName = contactResolver.resolve(listOf(sender))
            .singleOrNull()
            ?.displayNameOrAddress
            ?: sender.value
        if (!roleState.isRoleHeld()) {
            return IncomingPersistResult.Rejected(IncomingPersistResult.Reason.ROLE_NOT_HELD)
        }
        val requestId = "${stored.providerId.kind.name}:${stored.providerId.value}"
        var targetRemembered = false
        val canReply = subscriptionId?.let { replySubscriptionId ->
            replyTargets.remember(
                requestId = requestId,
                conversationId = stored.conversationId,
                recipient = sender,
                subscriptionId = replySubscriptionId,
            ).also { remembered -> targetRemembered = remembered }
        } == true
        if (!roleState.isRoleHeld()) {
            if (targetRemembered) replyTargets.forget(requestId, stored.conversationId)
            return IncomingPersistResult.Rejected(IncomingPersistResult.Reason.ROLE_NOT_HELD)
        }
        val notificationResult = try {
            messageNotifier.notifyIncoming(
                IncomingMessageNotification(
                    messageId = stored.providerId.asMessageId(),
                    conversationId = stored.conversationId,
                    senderDisplayName = displayName,
                    senderPersonKey = "conversation-${stored.conversationId.value}",
                    body = body,
                    receivedAtEpochMillis = receivedTimestampMillis,
                    canReply = canReply,
                ),
                notificationConfig,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            if (targetRemembered) replyTargets.forget(requestId, stored.conversationId)
            return IncomingPersistResult.Rejected(
                IncomingPersistResult.Reason.NOTIFICATION_UNAVAILABLE,
            )
        }
        when (notificationResult) {
            is NotificationPostResult.Posted -> Unit
            NotificationPostResult.NotificationsDisabled,
            NotificationPostResult.SupersededByNewer,
            -> if (!replyTargets.forget(requestId, stored.conversationId)) {
                return IncomingPersistResult.Rejected(
                    IncomingPersistResult.Reason.NOTIFICATION_UNAVAILABLE,
                )
            }
            is NotificationPostResult.Rejected -> {
                if (targetRemembered) replyTargets.forget(requestId, stored.conversationId)
                return IncomingPersistResult.Rejected(
                    IncomingPersistResult.Reason.NOTIFICATION_UNAVAILABLE,
                )
            }
        }
        if (!roleState.isRoleHeld()) {
            revokePostedNotification(
                notificationResult = notificationResult,
                requestId = requestId,
                stored = stored,
            )
            return IncomingPersistResult.Rejected(IncomingPersistResult.Reason.ROLE_NOT_HELD)
        }
        val handled = smsProvider.markIncomingHandled(
            deliveryFingerprint = deliveryFingerprint,
            providerId = stored.providerId,
            conversationId = stored.conversationId,
        )
        return when (handled) {
            is ProviderAccessResult.Success -> if (roleState.isRoleHeld()) {
                IncomingPersistResult.Persisted(
                    providerId = stored.providerId,
                    conversationId = stored.conversationId,
                )
            } else {
                revokePostedNotification(
                    notificationResult = notificationResult,
                    requestId = requestId,
                    stored = stored,
                )
                IncomingPersistResult.Rejected(IncomingPersistResult.Reason.ROLE_NOT_HELD)
            }
            ProviderAccessResult.RoleRequired -> {
                revokePostedNotification(
                    notificationResult = notificationResult,
                    requestId = requestId,
                    stored = stored,
                )
                IncomingPersistResult.Rejected(IncomingPersistResult.Reason.ROLE_NOT_HELD)
            }
            ProviderAccessResult.PermissionDenied ->
                IncomingPersistResult.Rejected(IncomingPersistResult.Reason.PERMISSION_DENIED)
            is ProviderAccessResult.InvalidInput ->
                IncomingPersistResult.Rejected(IncomingPersistResult.Reason.MALFORMED_INPUT)
            is ProviderAccessResult.Unsupported,
            is ProviderAccessResult.Unavailable ->
                IncomingPersistResult.Rejected(IncomingPersistResult.Reason.PROVIDER_UNAVAILABLE)
        }
    }

    private fun revokePostedNotification(
        notificationResult: NotificationPostResult,
        requestId: String,
        stored: ProviderStoredMessage,
    ) {
        if (notificationResult is NotificationPostResult.Posted) {
            cancelPostedGeneration(stored.conversationId, stored.providerId.asMessageId())
        }
        replyTargets.forget(requestId, stored.conversationId)
    }

    private fun cancelPostedGeneration(
        conversationId: ConversationId,
        messageId: MessageId,
    ) {
        try {
            when (
                messageNotifier.cancelIncomingConversation(
                    conversationId = conversationId,
                    expectedMessageId = messageId,
                )
            ) {
                NotificationCancelResult.Cancelled,
                NotificationCancelResult.AlreadyAbsentOrReplaced,
                NotificationCancelResult.RetryableFailure,
                -> Unit
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            // The role-loss fence retries notifier-wide exact cancellation.
        }
    }

    private companion object {
        const val DEFAULT_MAXIMUM_RECOVERY_BATCHES = 4
        const val ABSOLUTE_MAXIMUM_RECOVERY_BATCHES = 16
        const val ROLE_LOSS_CANCELLATION_MAXIMUM_ATTEMPTS = 3
        const val ROLE_LOSS_CANCELLATION_RETRY_MILLIS = 50L
    }
}

internal sealed interface IncomingNotificationRecoveryResult {
    data class Complete(val recoveredCount: Int) : IncomingNotificationRecoveryResult

    data class Deferred(val recoveredCount: Int) : IncomingNotificationRecoveryResult
}
