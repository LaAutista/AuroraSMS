// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import kotlinx.coroutines.CancellationException
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.notifications.MarkConversationReadDisposition
import org.aurorasms.core.notifications.MarkConversationReadHandler
import org.aurorasms.core.notifications.MarkConversationReadRequest
import org.aurorasms.core.notifications.MessageNotifier
import org.aurorasms.core.telephony.ConversationReadThroughOutcome
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.SmsProviderDataSource

internal class NotificationMarkReadCoordinator(
    private val roleState: DefaultSmsRoleState,
    private val smsProvider: SmsProviderDataSource,
    private val notifier: MessageNotifier,
    private val reminders: NotificationReminderController,
    private val onProviderChanged: suspend () -> Unit,
) : MarkConversationReadHandler {
    override suspend fun handle(
        request: MarkConversationReadRequest,
    ): MarkConversationReadDisposition {
        if (!roleState.isRoleHeld()) return MarkConversationReadDisposition.REJECTED
        val providerId = ProviderMessageId(
            kind = request.throughMessageId.kind,
            value = request.throughMessageId.value,
        )
        val result = try {
            smsProvider.markConversationReadThrough(request.conversationId, providerId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            return MarkConversationReadDisposition.REJECTED
        }
        if (
            result !is ProviderAccessResult.Success ||
            result.value != ConversationReadThroughOutcome.APPLIED_OR_ALREADY_READ
        ) {
            return MarkConversationReadDisposition.REJECTED
        }

        // Provider state is already authoritative. All later cleanup is exact and
        // best effort so a platform cancellation failure cannot misreport the read.
        try {
            reminders.cancelGenerationOwner(request.conversationId, providerId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            // Startup reminder recovery will revalidate the now-read provider row.
        }
        try {
            notifier.cancelIncomingConversation(
                request.conversationId,
                request.throughMessageId,
            )
        } catch (_: RuntimeException) {
            // Exact generation recovery retains ownership for a later retry.
        }
        try {
            onProviderChanged()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            // The provider observer or next foreground refresh will reconcile it.
        }
        return MarkConversationReadDisposition.APPLIED
    }
}
