// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import java.util.concurrent.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.notifications.InlineReplyDisposition
import org.aurorasms.core.notifications.InlineReplyHandler
import org.aurorasms.core.notifications.InlineReplyRequest
import org.aurorasms.core.notifications.MessageNotifier
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.MessageTransport
import org.aurorasms.core.telephony.RecipientSet
import org.aurorasms.core.telephony.SmsSendRequest
import org.aurorasms.core.telephony.SmsSubmissionOwnership
import org.aurorasms.core.telephony.SmsSubmissionObserver

class InlineReplyOrchestrator internal constructor(
    private val roleState: DefaultSmsRoleState,
    private val replyTargets: ReplyTargetRegistry,
    private val replayGuard: ReplyReplayGuard,
    private val replyOperations: ReplyOperationRegistry,
    private val messageTransport: MessageTransport,
    private val messageNotifier: MessageNotifier,
    private val transportResultHandler: InlineReplyTransportResultHandler =
        InlineReplyTransportResultHandler(replyOperations, messageNotifier),
    private val reconcileProviderUpdate: suspend (MessageId) -> Unit = {},
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : InlineReplyHandler {
    override suspend fun handle(request: InlineReplyRequest): InlineReplyDisposition {
        val text = request.text.trim()
        if (
            text.isEmpty() ||
            text.length > MAX_REPLY_CHARACTERS ||
            !roleState.isRoleHeld()
        ) {
            return InlineReplyDisposition.REJECTED
        }
        val nowMillis = clockMillis()
        val target = replyTargets.resolve(
            conversationId = request.conversationId,
            requestId = request.replyRequestId,
            nowMillis = nowMillis,
        )
            ?: return InlineReplyDisposition.REJECTED
        val recipients = when (val parsed = RecipientSet.from(listOf(target.recipient))) {
            is RecipientSet.CreationResult.Valid -> parsed.recipients
            is RecipientSet.CreationResult.Rejected -> return InlineReplyDisposition.REJECTED
        }
        val sourceMessageId = request.replyRequestId.toSourceMessageIdOrNull()
            ?: return InlineReplyDisposition.REJECTED
        val reservation = replyOperations.reserve(request.conversationId, sourceMessageId)
        val operationId = when (reservation) {
            is ReplyOperationReservationResult.Reserved -> reservation.operationId
            ReplyOperationReservationResult.Full,
            ReplyOperationReservationResult.IdentifierCollision,
            ReplyOperationReservationResult.InvalidSource,
            ReplyOperationReservationResult.PersistenceFailure
            -> return InlineReplyDisposition.REJECTED
        }
        if (
            !replayGuard.claim(
                claim = ReplyReplayClaim(
                    requestId = request.replyRequestId,
                    conversationId = request.conversationId,
                    recipient = target.recipient,
                    subscriptionId = target.subscriptionId,
                    expiresAtMillis = target.expiresAtMillis,
                ),
                claimedAtMillis = nowMillis,
            )
        ) {
            replyOperations.discard(operationId)
            return InlineReplyDisposition.REJECTED
        }

        if (!replyOperations.markClaimed(operationId).allowsSubmissionCheckpoint()) {
            recoverAfterInterruption(operationId, request.conversationId)
            return InlineReplyDisposition.ACCEPTED
        }

        val submissionObserver = object : SmsSubmissionObserver {
            override suspend fun onPrepared(
                providerId: ProviderMessageId,
                providerConversationId: ConversationId,
                unitCount: Int,
            ): Boolean =
                providerConversationId == request.conversationId &&
                    replyOperations.recordPrepared(operationId, providerId, unitCount)
                    .allowsSubmissionCheckpoint()

            override suspend fun onSubmitting(
                providerId: ProviderMessageId,
                providerConversationId: ConversationId,
                unitCount: Int,
            ): Boolean =
                providerConversationId == request.conversationId &&
                    replyOperations.recordSubmitting(operationId, providerId, unitCount)
                    .allowsSubmissionCheckpoint()
        }
        val result = try {
            messageTransport.sendSms(
                request = SmsSendRequest(
                    operationId = operationId,
                    recipients = recipients,
                    body = text,
                    subscriptionId = target.subscriptionId,
                    operationOrigin = TransportResult.OperationOrigin.INLINE_REPLY,
                ),
                ownership = SmsSubmissionOwnership.CallerOwned(submissionObserver),
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                recoverAfterInterruption(operationId, request.conversationId)
            }
            throw cancelled
        } catch (_: RuntimeException) {
            recoverAfterInterruption(operationId, request.conversationId)
            return InlineReplyDisposition.ACCEPTED
        }
        if (
            result.operationId != operationId ||
            result.transport != MessageTransportKind.SMS ||
            result.operationOrigin != TransportResult.OperationOrigin.INLINE_REPLY ||
            !result.matchesProviderConversation(request.conversationId)
        ) {
            recoverAfterInterruption(operationId, request.conversationId)
            return InlineReplyDisposition.ACCEPTED
        }
        when (result) {
            is TransportResult.Submitted -> {
                when (
                    replyOperations.recordSubmitted(
                        operationId = operationId,
                        unitCount = result.unitCount,
                        providerMessageId = result.providerMessageId,
                    )
                ) {
                    ReplyOperationSubmittedResult.SuccessPending ->
                        transportResultHandler.handleOwnedSuccess(operationId)
                    ReplyOperationSubmittedResult.Tracked,
                    ReplyOperationSubmittedResult.FailureNotified,
                    ReplyOperationSubmittedResult.SubmissionUnknownNotified,
                    ReplyOperationSubmittedResult.SuccessComplete,
                    -> Unit
                    ReplyOperationSubmittedResult.FailurePending ->
                        transportResultHandler.handleOwnedFailure(
                            operationId = operationId,
                            fallbackConversationId = request.conversationId,
                            providerMessageId = result.providerMessageId,
                            unitCount = result.unitCount,
                        )
                    ReplyOperationSubmittedResult.SubmissionUnknownPending ->
                        transportResultHandler.reconcilePendingOperations()
                    ReplyOperationSubmittedResult.Invalid,
                    ReplyOperationSubmittedResult.PersistenceFailure,
                    ReplyOperationSubmittedResult.ProviderMismatch,
                    ReplyOperationSubmittedResult.UnitCountMismatch,
                    ReplyOperationSubmittedResult.PhaseMismatch,
                    ReplyOperationSubmittedResult.CorruptOwnership,
                    ReplyOperationSubmittedResult.Untracked ->
                        recoverAfterInterruption(operationId, request.conversationId)
                }
            }
            is TransportResult.Sent -> {
                transportResultHandler.handle(result)
                reconcileProviderUpdate(operationId)
            }
            is TransportResult.Delivered -> {
                transportResultHandler.handle(result)
                reconcileProviderUpdate(operationId)
            }
            is TransportResult.Failed -> {
                when (result.stage) {
                    TransportResult.FailureStage.SUBMISSION_UNKNOWN,
                    TransportResult.FailureStage.DOWNLOAD_CALLBACK ->
                        recoverAfterInterruption(operationId, request.conversationId)
                    TransportResult.FailureStage.DELIVERY_CALLBACK ->
                        transportResultHandler.handle(result)
                    TransportResult.FailureStage.SUBMISSION,
                    TransportResult.FailureStage.SENT_CALLBACK ->
                        transportResultHandler.handleOwnedFailure(
                            operationId = operationId,
                            fallbackConversationId = request.conversationId,
                            providerMessageId = result.providerMessageId,
                            unitIndex = result.unitIndex,
                            unitCount = result.unitCount,
                            operationOrigin = result.operationOrigin,
                        )
                }
                reconcileProviderUpdate(operationId)
            }
            is TransportResult.Downloaded,
            is TransportResult.Rejected -> {
                notifyFailure(operationId, request.conversationId)
                reconcileProviderUpdate(operationId)
            }
        }
        return InlineReplyDisposition.ACCEPTED
    }

    private suspend fun recoverAfterInterruption(
        operationId: MessageId,
        fallbackConversationId: ConversationId,
    ) {
        transportResultHandler.recoverInterruptedOperation(
            operationId = operationId,
            fallbackConversationId = fallbackConversationId,
        )
        reconcileProviderUpdate(operationId)
    }

    private fun notifyFailure(
        operationId: MessageId,
        fallbackConversationId: ConversationId,
    ) {
        transportResultHandler.handleOwnedFailure(operationId, fallbackConversationId)
    }

    private companion object {
        const val MAX_REPLY_CHARACTERS = 4_000
    }
}

private fun ReplyOperationPhaseTransitionResult.allowsSubmissionCheckpoint(): Boolean =
    this == ReplyOperationPhaseTransitionResult.Transitioned ||
        this == ReplyOperationPhaseTransitionResult.AlreadyInPhase

private fun TransportResult.matchesProviderConversation(expected: ConversationId): Boolean =
    when (this) {
        is TransportResult.Submitted ->
            providerMessageId == null || providerConversationId == expected
        is TransportResult.Sent ->
            providerMessageId == null || providerConversationId == expected
        is TransportResult.Delivered ->
            providerMessageId == null || providerConversationId == expected
        is TransportResult.Failed ->
            providerMessageId == null || providerConversationId == expected
        is TransportResult.Downloaded,
        is TransportResult.Rejected,
        -> true
    }

private fun String.toSourceMessageIdOrNull(): MessageId? {
    val separator = indexOf(':')
    if (separator <= 0 || separator != lastIndexOf(':')) return null
    val kind = when (substring(0, separator)) {
        ProviderKind.SMS.name -> ProviderKind.SMS
        ProviderKind.MMS.name -> ProviderKind.MMS
        else -> return null
    }
    val valueText = substring(separator + 1)
    val value = valueText.toLongOrNull()
        ?.takeIf { it > 0L && valueText == it.toString() }
        ?: return null
    return MessageId(kind, value)
}

fun interface ReplyReplayGuard {
    /** Atomically persists an at-most-once claim before transport can start. */
    fun claim(claim: ReplyReplayClaim, claimedAtMillis: Long): Boolean
}

data class ReplyReplayClaim(
    val requestId: String,
    val conversationId: ConversationId,
    val recipient: ParticipantAddress,
    val subscriptionId: AuroraSubscriptionId,
    val expiresAtMillis: Long,
) {
    init {
        require(expiresAtMillis > 0L) { "expiresAtMillis must be positive" }
    }
}

class ReplyTargetRegistry(
    maximumEntries: Int = 256,
    private val timeToLiveMillis: Long = 24L * 60L * 60L * 1_000L,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    targetStore: ReplyTargetStore? = null,
) {
    private val targetStore = targetStore ?: InMemoryReplyTargetStore(maximumEntries)

    init {
        require(maximumEntries in 1..4_096) { "maximumEntries is out of bounds" }
        require(timeToLiveMillis > 0L) { "timeToLiveMillis must be positive" }
    }

    fun remember(
        requestId: String,
        conversationId: ConversationId,
        recipient: ParticipantAddress,
        subscriptionId: AuroraSubscriptionId,
    ): Boolean {
        require(
            requestId.isNotBlank() &&
                requestId.length <= 256 &&
                requestId.none(Char::isISOControl),
        ) {
            "requestId is invalid"
        }
        val nowMillis = clockMillis().coerceAtLeast(0L)
        val target = ReplyTarget(
            requestId = requestId,
            conversationId = conversationId,
            recipient = recipient,
            subscriptionId = subscriptionId,
            expiresAtMillis = nowMillis.let { now ->
                if (Long.MAX_VALUE - now < timeToLiveMillis) Long.MAX_VALUE else now + timeToLiveMillis
            },
        )
        return targetStore.put(target, nowMillis)
    }

    fun resolve(
        conversationId: ConversationId,
        requestId: String,
        nowMillis: Long = clockMillis(),
    ): ReplyTarget? {
        val target = targetStore.get(requestId, nowMillis) ?: return null
        if (target.conversationId != conversationId || target.requestId != requestId) return null
        return target
    }

    fun forget(requestId: String, conversationId: ConversationId): Boolean =
        targetStore.remove(requestId, conversationId)

    fun clear() {
        targetStore.clear()
    }
}

data class ReplyTarget(
    val requestId: String,
    val conversationId: ConversationId,
    val recipient: ParticipantAddress,
    val subscriptionId: AuroraSubscriptionId,
    val expiresAtMillis: Long,
)

interface ReplyTargetStore {
    fun put(target: ReplyTarget, nowMillis: Long): Boolean
    fun get(requestId: String, nowMillis: Long): ReplyTarget?
    fun remove(requestId: String, conversationId: ConversationId): Boolean
    fun clear(): Boolean
}

internal class InMemoryReplyTargetStore(
    private val maximumEntries: Int,
) : ReplyTargetStore {
    private val targets = linkedMapOf<String, ReplyTarget>()

    @Synchronized
    override fun put(target: ReplyTarget, nowMillis: Long): Boolean {
        targets.entries.removeAll { (_, existing) -> existing.expiresAtMillis <= nowMillis }
        targets.remove(target.requestId)
        targets[target.requestId] = target
        while (targets.size > maximumEntries) targets.remove(targets.keys.first())
        return true
    }

    @Synchronized
    override fun get(requestId: String, nowMillis: Long): ReplyTarget? {
        val target = targets[requestId] ?: return null
        if (target.expiresAtMillis <= nowMillis) {
            targets.remove(requestId)
            return null
        }
        return target
    }

    @Synchronized
    override fun remove(requestId: String, conversationId: ConversationId): Boolean {
        val target = targets[requestId] ?: return true
        if (target.conversationId != conversationId) return false
        targets.remove(requestId)
        return true
    }

    @Synchronized
    override fun clear(): Boolean {
        targets.clear()
        return true
    }
}
