// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import org.aurorasms.core.notifications.IncomingMessageNotification
import org.aurorasms.core.notifications.MessageNotifier
import org.aurorasms.core.notifications.NotificationConfig
import org.aurorasms.core.telephony.ContactResolver
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.IncomingMessage
import org.aurorasms.core.telephony.IncomingMessageSink
import org.aurorasms.core.telephony.IncomingPersistResult
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
    override suspend fun persist(message: IncomingMessage): IncomingPersistResult {
        if (!roleState.isRoleHeld()) {
            return IncomingPersistResult.Rejected(IncomingPersistResult.Reason.ROLE_NOT_HELD)
        }
        return when (message) {
            is IncomingMessage.Sms -> persistSms(message)
            is IncomingMessage.MmsWapPush ->
                IncomingPersistResult.Rejected(IncomingPersistResult.Reason.CODEC_UNAVAILABLE)
        }
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
                    notifyAndAcknowledge(message, stored)
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
        message: IncomingMessage.Sms,
        stored: ProviderStoredMessage,
    ): IncomingPersistResult {
        val displayName = contactResolver.resolve(listOf(message.sender))
            .singleOrNull()
            ?.displayNameOrAddress
            ?: message.sender.value
        val canReply = message.subscriptionId?.let { subscriptionId ->
            replyTargets.remember(
                requestId = "${stored.providerId.kind.name}:${stored.providerId.value}",
                conversationId = stored.conversationId,
                recipient = message.sender,
                subscriptionId = subscriptionId,
            )
        } == true
        messageNotifier.notifyIncoming(
            IncomingMessageNotification(
                messageId = stored.providerId.asMessageId(),
                conversationId = stored.conversationId,
                senderDisplayName = displayName,
                senderPersonKey = "conversation-${stored.conversationId.value}",
                body = message.body,
                receivedAtEpochMillis = message.receivedTimestampMillis,
                canReply = canReply,
            ),
            notificationConfig,
        )
        return when (
            smsProvider.markIncomingHandled(
                deliveryFingerprint = message.deliveryFingerprint,
                providerId = stored.providerId,
                conversationId = stored.conversationId,
            )
        ) {
            is ProviderAccessResult.Success -> IncomingPersistResult.Persisted(
                providerId = stored.providerId,
                conversationId = stored.conversationId,
            )
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
}
