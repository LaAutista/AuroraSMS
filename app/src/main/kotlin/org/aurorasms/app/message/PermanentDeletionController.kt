// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import org.aurorasms.core.model.MessageSyncFingerprint
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.PermanentDeletionId
import org.aurorasms.core.state.PermanentDeletionReviewReason

internal sealed interface PermanentDeletionCommand {
    val providerThreadId: ProviderThreadId

    data class Message(
        val providerMessageId: ProviderMessageId,
        override val providerThreadId: ProviderThreadId,
        val syncFingerprint: MessageSyncFingerprint,
    ) : PermanentDeletionCommand {
        override fun toString(): String = "PermanentDeletionCommand.Message(REDACTED)"
    }

    data class Thread(
        override val providerThreadId: ProviderThreadId,
    ) : PermanentDeletionCommand {
        override fun toString(): String = "PermanentDeletionCommand.Thread(REDACTED)"
    }
}

internal enum class PermanentDeletionTargetKind { MESSAGE, THREAD }

internal sealed interface PermanentDeletionObservation {
    data object Loading : PermanentDeletionObservation
    data object None : PermanentDeletionObservation
    data class Pending(
        val targetKind: PermanentDeletionTargetKind,
        val providerMessageId: ProviderMessageId?,
        val dueTimestampMillis: Long,
    ) : PermanentDeletionObservation
    data class Committing(val targetKind: PermanentDeletionTargetKind) : PermanentDeletionObservation
    data class ReviewRequired(
        val targetKind: PermanentDeletionTargetKind,
        val reason: PermanentDeletionReviewReason,
        val commitMayHaveStarted: Boolean,
    ) : PermanentDeletionObservation
    data class Completed(
        val targetKind: PermanentDeletionTargetKind,
        val epoch: Long,
    ) : PermanentDeletionObservation
}

internal enum class PermanentDeletionAttempt { ACCEPTED, REFUSED }

internal enum class PermanentDeletionRecoveryReason {
    APP_STARTUP,
    BOOT_COMPLETED,
    TIME_CHANGED,
    PACKAGE_REPLACED,
    ROLE_CHANGED,
}

internal interface PermanentDeletionController {
    fun observe(providerThreadId: ProviderThreadId): Flow<PermanentDeletionObservation>
    suspend fun request(command: PermanentDeletionCommand): PermanentDeletionAttempt
    suspend fun undo(providerThreadId: ProviderThreadId): Boolean
    suspend fun handleAlarm(id: PermanentDeletionId)
    suspend fun recover(reason: PermanentDeletionRecoveryReason)
    suspend fun acknowledgeCompletion(providerThreadId: ProviderThreadId, epoch: Long)
    fun fence()
}

internal object UnavailablePermanentDeletionController : PermanentDeletionController {
    override fun observe(providerThreadId: ProviderThreadId) =
        flowOf<PermanentDeletionObservation>(PermanentDeletionObservation.None)
    override suspend fun request(command: PermanentDeletionCommand) = PermanentDeletionAttempt.REFUSED
    override suspend fun undo(providerThreadId: ProviderThreadId) = false
    override suspend fun handleAlarm(id: PermanentDeletionId) = Unit
    override suspend fun recover(reason: PermanentDeletionRecoveryReason) = Unit
    override suspend fun acknowledgeCompletion(providerThreadId: ProviderThreadId, epoch: Long) = Unit
    override fun fence() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class DeferredPermanentDeletionController : PermanentDeletionController {
    private val delegate = MutableStateFlow<PermanentDeletionController?>(null)
    @Volatile private var fenced = false

    fun install(controller: PermanentDeletionController) {
        if (fenced) controller.fence()
        delegate.value = controller
    }

    override fun observe(providerThreadId: ProviderThreadId): Flow<PermanentDeletionObservation> =
        delegate.flatMapLatest { controller ->
            controller?.observe(providerThreadId) ?: flowOf(PermanentDeletionObservation.Loading)
        }

    override suspend fun request(command: PermanentDeletionCommand) = await().request(command)
    override suspend fun undo(providerThreadId: ProviderThreadId) = await().undo(providerThreadId)
    override suspend fun handleAlarm(id: PermanentDeletionId) = await().handleAlarm(id)
    override suspend fun recover(reason: PermanentDeletionRecoveryReason) = await().recover(reason)
    override suspend fun acknowledgeCompletion(providerThreadId: ProviderThreadId, epoch: Long) =
        await().acknowledgeCompletion(providerThreadId, epoch)

    override fun fence() {
        fenced = true
        delegate.value?.fence()
    }

    private suspend fun await(): PermanentDeletionController =
        delegate.value ?: delegate.first { it != null }.let(::checkNotNull)
}
