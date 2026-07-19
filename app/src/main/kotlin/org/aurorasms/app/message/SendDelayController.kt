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
import org.aurorasms.core.state.DraftId
import org.aurorasms.core.state.DraftRevision
import org.aurorasms.core.state.SendDelayId

internal data class SendDelayCommand(
    val identity: VerifiedConversationIdentity,
    val subscriptionId: AuroraSubscriptionId,
    val draftId: DraftId,
    val draftRevision: DraftRevision,
    val delayMillis: Long,
) {
    override fun toString(): String = "SendDelayCommand(delayMillis=$delayMillis, REDACTED)"
}

internal sealed interface SendDelayObservation {
    data object Loading : SendDelayObservation
    data object None : SendDelayObservation
    data class Pending(val dueTimestampMillis: Long) : SendDelayObservation
    data class Dispatching(val dueTimestampMillis: Long) : SendDelayObservation
    data class ReviewRequired(val dueTimestampMillis: Long) : SendDelayObservation
}

internal enum class SendDelayAttempt { ACCEPTED, REFUSED }

internal enum class SendDelayRecoveryReason {
    APP_STARTUP,
    BOOT_COMPLETED,
    TIME_CHANGED,
    PACKAGE_REPLACED,
    ROLE_CHANGED,
}

internal interface SendDelayController {
    fun observe(providerThreadId: ProviderThreadId): Flow<SendDelayObservation>
    suspend fun enqueue(command: SendDelayCommand): SendDelayAttempt
    suspend fun undo(providerThreadId: ProviderThreadId): Boolean
    suspend fun handleAlarm(id: SendDelayId)
    suspend fun recover(reason: SendDelayRecoveryReason)
    suspend fun reconcileDispatches()
    fun fence()
}

internal object UnavailableSendDelayController : SendDelayController {
    override fun observe(providerThreadId: ProviderThreadId): Flow<SendDelayObservation> =
        flowOf(SendDelayObservation.None)
    override suspend fun enqueue(command: SendDelayCommand) = SendDelayAttempt.REFUSED
    override suspend fun undo(providerThreadId: ProviderThreadId) = false
    override suspend fun handleAlarm(id: SendDelayId) = Unit
    override suspend fun recover(reason: SendDelayRecoveryReason) = Unit
    override suspend fun reconcileDispatches() = Unit
    override fun fence() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class DeferredSendDelayController : SendDelayController {
    private val delegate = MutableStateFlow<SendDelayController?>(null)
    @Volatile private var fenced = false

    fun install(controller: SendDelayController) {
        if (fenced) controller.fence()
        delegate.value = controller
    }

    override fun observe(providerThreadId: ProviderThreadId): Flow<SendDelayObservation> =
        delegate.flatMapLatest { controller ->
            controller?.observe(providerThreadId) ?: flowOf(SendDelayObservation.Loading)
        }

    override suspend fun enqueue(command: SendDelayCommand): SendDelayAttempt =
        awaitDelegate().enqueue(command)
    override suspend fun undo(providerThreadId: ProviderThreadId): Boolean =
        awaitDelegate().undo(providerThreadId)
    override suspend fun handleAlarm(id: SendDelayId) = awaitDelegate().handleAlarm(id)
    override suspend fun recover(reason: SendDelayRecoveryReason) =
        awaitDelegate().recover(reason)
    override suspend fun reconcileDispatches() = awaitDelegate().reconcileDispatches()

    override fun fence() {
        fenced = true
        delegate.value?.fence()
    }

    private suspend fun awaitDelegate(): SendDelayController =
        delegate.value ?: delegate.first { it != null }.let(::checkNotNull)
}
