// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import org.aurorasms.core.model.MessageId
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.SmsProviderDataSource

/** Applies the durable provider-status outbox without acknowledging failed writes. */
internal class InlineReplyProviderUpdateCoordinator(
    private val replyOperations: ReplyOperationRegistry,
    private val smsProvider: SmsProviderDataSource,
) {
    suspend fun reconcile(operationId: MessageId): Boolean {
        var providerStateMayHaveChanged = false
        repeat(MAXIMUM_STALE_RETRIES) {
            val update = when (val pending = replyOperations.pendingProviderUpdate(operationId)) {
                is ReplyOperationPendingProviderUpdateResult.Available ->
                    pending.update ?: return providerStateMayHaveChanged
                ReplyOperationPendingProviderUpdateResult.Invalid,
                ReplyOperationPendingProviderUpdateResult.CorruptOwnership,
                ReplyOperationPendingProviderUpdateResult.PersistenceFailure ->
                    return providerStateMayHaveChanged
            }
            if (smsProvider.updateStatus(update.providerMessageId, update.status) !is
                ProviderAccessResult.Success
            ) {
                return providerStateMayHaveChanged
            }
            providerStateMayHaveChanged = true
            when (replyOperations.acknowledgeProviderUpdate(update)) {
                ReplyOperationProviderAcknowledgementResult.Acknowledged,
                ReplyOperationProviderAcknowledgementResult.AlreadyAcknowledged,
                ReplyOperationProviderAcknowledgementResult.Untracked ->
                    return providerStateMayHaveChanged
                ReplyOperationProviderAcknowledgementResult.Stale -> Unit
                ReplyOperationProviderAcknowledgementResult.Invalid,
                ReplyOperationProviderAcknowledgementResult.CorruptOwnership,
                ReplyOperationProviderAcknowledgementResult.PersistenceFailure ->
                    return providerStateMayHaveChanged
            }
        }
        return providerStateMayHaveChanged
    }

    suspend fun reconcilePending(): Boolean {
        val updates = when (val pending = replyOperations.pendingProviderUpdates()) {
            is ReplyOperationPendingProviderUpdatesResult.Available -> pending.updates
            ReplyOperationPendingProviderUpdatesResult.PersistenceFailure -> return false
        }
        var providerStateMayHaveChanged = false
        updates.forEach { update ->
            providerStateMayHaveChanged = reconcile(update.operationId) || providerStateMayHaveChanged
        }
        return providerStateMayHaveChanged
    }

    private companion object {
        const val MAXIMUM_STALE_RETRIES = 4
    }
}
