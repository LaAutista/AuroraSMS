// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import java.util.concurrent.atomic.AtomicLong
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ParticipantAddress
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

class InlineReplyOrchestrator(
    private val roleState: DefaultSmsRoleState,
    private val replyTargets: ReplyTargetRegistry,
    private val replayGuard: ReplyReplayGuard,
    private val messageTransport: MessageTransport,
    private val messageNotifier: MessageNotifier,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : InlineReplyHandler {
    private val nextOperationId = AtomicLong(clockMillis().coerceAtLeast(1L))

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
            return InlineReplyDisposition.REJECTED
        }

        val result = messageTransport.sendSms(
            SmsSendRequest(
                operationId = MessageId(
                    kind = ProviderKind.PENDING_OPERATION,
                    value = nextOperationId.updateAndGet { current ->
                        if (current == Long.MAX_VALUE) 1L else current + 1L
                    },
                ),
                recipients = recipients,
                body = text,
                subscriptionId = target.subscriptionId,
            ),
        )
        when (result) {
            is TransportResult.Submitted,
            is TransportResult.Sent,
            is TransportResult.Delivered ->
                messageNotifier.cancelConversation(request.conversationId)
            is TransportResult.Downloaded,
            is TransportResult.Failed,
            is TransportResult.Rejected ->
                messageNotifier.notifyInlineReplyFailure(request.conversationId)
        }
        return InlineReplyDisposition.ACCEPTED
    }

    private companion object {
        const val MAX_REPLY_CHARACTERS = 4_000
    }
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
    override fun clear(): Boolean {
        targets.clear()
        return true
    }
}
