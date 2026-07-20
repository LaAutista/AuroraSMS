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
import org.aurorasms.core.state.ScheduledSmsId
import org.aurorasms.core.state.ScheduledSmsPrecision
import org.aurorasms.core.state.ScheduledSmsReviewReason
import org.aurorasms.core.state.MessageSignature

internal data class ScheduledSmsCommand(
    val identity: VerifiedConversationIdentity,
    val subscriptionId: AuroraSubscriptionId,
    val draftId: DraftId,
    val draftRevision: DraftRevision,
    val dueTimestampMillis: Long,
    val frozenSignature: MessageSignature? = null,
) {
    override fun toString(): String = "ScheduledSmsCommand(REDACTED)"
}

internal sealed interface ScheduledSmsObservation {
    data object Loading : ScheduledSmsObservation
    data object None : ScheduledSmsObservation
    data class Pending(
        val dueTimestampMillis: Long,
        val precision: ScheduledSmsPrecision,
    ) : ScheduledSmsObservation
    data class Dispatching(val dueTimestampMillis: Long) : ScheduledSmsObservation
    data class ReviewRequired(
        val dueTimestampMillis: Long,
        val reason: ScheduledSmsReviewReason,
    ) : ScheduledSmsObservation
}

internal enum class ScheduledSmsAttempt { ACCEPTED, REFUSED }

internal enum class ScheduledSmsRecoveryReason {
    APP_STARTUP,
    BOOT_COMPLETED,
    WALL_CLOCK_CHANGED,
    TIMEZONE_CHANGED,
    PACKAGE_REPLACED,
    EXACT_ACCESS_CHANGED,
}

internal interface ScheduledSmsController {
    fun observe(providerThreadId: ProviderThreadId): Flow<ScheduledSmsObservation>
    suspend fun schedule(command: ScheduledSmsCommand): ScheduledSmsAttempt
    suspend fun cancel(providerThreadId: ProviderThreadId): Boolean
    suspend fun handleAlarm(id: ScheduledSmsId)
    suspend fun recover(reason: ScheduledSmsRecoveryReason)
    suspend fun reconcileDispatches()
    fun fence()
}

internal object UnavailableScheduledSmsController : ScheduledSmsController {
    override fun observe(providerThreadId: ProviderThreadId) =
        flowOf<ScheduledSmsObservation>(ScheduledSmsObservation.None)
    override suspend fun schedule(command: ScheduledSmsCommand) = ScheduledSmsAttempt.REFUSED
    override suspend fun cancel(providerThreadId: ProviderThreadId) = false
    override suspend fun handleAlarm(id: ScheduledSmsId) = Unit
    override suspend fun recover(reason: ScheduledSmsRecoveryReason) = Unit
    override suspend fun reconcileDispatches() = Unit
    override fun fence() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class DeferredScheduledSmsController : ScheduledSmsController {
    private val delegate = MutableStateFlow<ScheduledSmsController?>(null)
    @Volatile private var fenced = false

    fun install(controller: ScheduledSmsController) {
        if (fenced) controller.fence()
        delegate.value = controller
    }

    override fun observe(providerThreadId: ProviderThreadId): Flow<ScheduledSmsObservation> =
        delegate.flatMapLatest { controller ->
            controller?.observe(providerThreadId)
                ?: flowOf(ScheduledSmsObservation.Loading)
        }

    override suspend fun schedule(command: ScheduledSmsCommand) = await().schedule(command)
    override suspend fun cancel(providerThreadId: ProviderThreadId) = await().cancel(providerThreadId)
    override suspend fun handleAlarm(id: ScheduledSmsId) = await().handleAlarm(id)
    override suspend fun recover(reason: ScheduledSmsRecoveryReason) = await().recover(reason)
    override suspend fun reconcileDispatches() = await().reconcileDispatches()
    override fun fence() {
        fenced = true
        delegate.value?.fence()
    }

    private suspend fun await() = delegate.value ?: delegate.first { it != null }.let(::checkNotNull)
}
