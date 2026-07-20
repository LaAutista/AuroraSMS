// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import org.aurorasms.core.index.conversation.VerifiedConversationIdentity
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.state.DraftId
import org.aurorasms.core.state.DraftRevision
import org.aurorasms.core.state.MessageSignature
import org.aurorasms.core.telephony.OutgoingMmsAttachment
import org.aurorasms.core.telephony.OutgoingMmsPayload

internal data class ThreadSmsSendCommand(
    val identity: VerifiedConversationIdentity,
    val subscriptionId: AuroraSubscriptionId,
    val draftId: DraftId,
    val draftRevision: DraftRevision,
    val frozenSignature: MessageSignature? = null,
    val transport: MessageTransportKind = MessageTransportKind.SMS,
    val attachments: List<OutgoingMmsAttachment> = emptyList(),
) {
    init {
        require(attachments.size <= OutgoingMmsPayload.Message.MAX_ATTACHMENTS)
        require(
            attachments.sumOf { it.size.toLong() } <=
                OutgoingMmsPayload.Message.MAX_ATTACHMENT_BYTES_TOTAL,
        )
    }

    override fun toString(): String = "ThreadSmsSendCommand(REDACTED)"
}

internal enum class ThreadSmsSendPhase {
    RECOVERY_PENDING,
    IDLE,
    SENDING,
    KNOWN_UNSENT,
    SUBMISSION_UNKNOWN,
}

internal data class ThreadSmsSendObservation(
    val phase: ThreadSmsSendPhase,
    val completionEpoch: Long = 0L,
    val unknownAcknowledgementEpoch: Long = 0L,
) {
    init {
        require(completionEpoch >= 0L) { "Composer completion epochs cannot be negative" }
        require(unknownAcknowledgementEpoch >= 0L) {
            "Composer acknowledgement epochs cannot be negative"
        }
    }
}

internal enum class ThreadSmsSendAttempt {
    STARTED,
    REFUSED,
}

internal enum class ThreadSmsRecoveryResult {
    READY,
    /** The durable snapshot is valid and new unrelated work is safe, but cleanup needs a retry. */
    READY_WITH_DEFERRED_OPERATIONS,
    DEFERRED,
    STORAGE_BLOCKED,
}

internal val ThreadSmsRecoveryResult.requiresFollowUp: Boolean
    get() = this == ThreadSmsRecoveryResult.READY_WITH_DEFERRED_OPERATIONS ||
        this == ThreadSmsRecoveryResult.DEFERRED

internal interface ThreadSmsSendController {
    fun observe(providerThreadId: ProviderThreadId): Flow<ThreadSmsSendObservation>

    suspend fun send(command: ThreadSmsSendCommand): ThreadSmsSendAttempt

    suspend fun acknowledgeSubmissionUnknown(providerThreadId: ProviderThreadId): Boolean

    suspend fun recover(): ThreadSmsRecoveryResult

    /** Immediately disables new composer acceptance after role/eligibility loss. */
    fun fence()

    /** Returns true only when this controller durably owned the callback. */
    suspend fun handleTransportResult(result: TransportResult): Boolean
}

internal object UnavailableThreadSmsSendController : ThreadSmsSendController {
    override fun observe(providerThreadId: ProviderThreadId): Flow<ThreadSmsSendObservation> =
        flowOf(ThreadSmsSendObservation(ThreadSmsSendPhase.RECOVERY_PENDING))

    override suspend fun send(command: ThreadSmsSendCommand): ThreadSmsSendAttempt =
        ThreadSmsSendAttempt.REFUSED

    override suspend fun acknowledgeSubmissionUnknown(providerThreadId: ProviderThreadId): Boolean = false

    override suspend fun recover(): ThreadSmsRecoveryResult = ThreadSmsRecoveryResult.STORAGE_BLOCKED

    override fun fence() = Unit

    override suspend fun handleTransportResult(result: TransportResult): Boolean = false
}

/** Waits for the non-destructive state database open before exposing composer operations. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DeferredThreadSmsSendController : ThreadSmsSendController {
    private val delegate = MutableStateFlow<ThreadSmsSendController?>(null)
    @Volatile
    private var fenced = false

    fun install(controller: ThreadSmsSendController) {
        if (fenced) controller.fence()
        delegate.value = controller
    }

    override fun observe(providerThreadId: ProviderThreadId): Flow<ThreadSmsSendObservation> =
        delegate.flatMapLatest { controller ->
            controller?.observe(providerThreadId)
                ?: flowOf(ThreadSmsSendObservation(ThreadSmsSendPhase.RECOVERY_PENDING))
        }

    override suspend fun send(command: ThreadSmsSendCommand): ThreadSmsSendAttempt =
        awaitDelegate().send(command)

    override suspend fun acknowledgeSubmissionUnknown(providerThreadId: ProviderThreadId): Boolean =
        awaitDelegate().acknowledgeSubmissionUnknown(providerThreadId)

    override suspend fun recover(): ThreadSmsRecoveryResult = awaitDelegate().recover()

    override fun fence() {
        fenced = true
        delegate.value?.fence()
    }

    override suspend fun handleTransportResult(result: TransportResult): Boolean =
        awaitDelegate().handleTransportResult(result)

    private suspend fun awaitDelegate(): ThreadSmsSendController =
        delegate.value ?: delegate.first { it != null }.let(::checkNotNull)
}
