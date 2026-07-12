// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.aurorasms.app.message.AppNotificationIntentFactory
import org.aurorasms.app.message.IncomingMessageOrchestrator
import org.aurorasms.app.message.InlineReplyOrchestrator
import org.aurorasms.app.message.ReplyTargetRegistry
import org.aurorasms.app.message.SharedPreferencesReplyReplayGuard
import org.aurorasms.app.message.SharedPreferencesReplyTargetStore
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.notifications.AndroidMessageNotifier
import org.aurorasms.core.notifications.InlineReplyHandler
import org.aurorasms.core.notifications.MessageNotifier
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.EncodedMmsPdu
import org.aurorasms.core.telephony.IncomingMessageSink
import org.aurorasms.core.telephony.MessageTransport
import org.aurorasms.core.telephony.SmsProviderDataSource
import org.aurorasms.core.telephony.SmsProviderStatus
import org.aurorasms.core.telephony.internal.AndroidContactResolver
import org.aurorasms.core.telephony.internal.AndroidDefaultSmsRoleState
import org.aurorasms.core.telephony.internal.AndroidMmsProviderDataSource
import org.aurorasms.core.telephony.internal.AndroidMmsTransport
import org.aurorasms.core.telephony.internal.AndroidSmsProviderDataSource
import org.aurorasms.core.telephony.internal.AndroidSmsTransport
import org.aurorasms.core.telephony.internal.AndroidSubscriptionRepository
import org.aurorasms.core.telephony.internal.MmsPduStagingStore

class AppContainer(
    val application: Application,
) {
    private val replyTargets = ReplyTargetRegistry(
        maximumEntries = 4_096,
        targetStore = SharedPreferencesReplyTargetStore(application),
    )
    private val sentPartTracker = SentPartTracker()

    val defaultSmsRoleState: DefaultSmsRoleState = AndroidDefaultSmsRoleState(application)
    val smsProviderDataSource: SmsProviderDataSource =
        AndroidSmsProviderDataSource(application, defaultSmsRoleState)
    private val subscriptionRepository = AndroidSubscriptionRepository(application)
    private val contactResolver = AndroidContactResolver(application)
    val mmsProviderDataSource = AndroidMmsProviderDataSource(application, defaultSmsRoleState)
    private val mmsStagingStore = MmsPduStagingStore(application)
    private val mmsTransport = AndroidMmsTransport(
        context = application,
        roleState = defaultSmsRoleState,
        subscriptions = subscriptionRepository,
        stagingStore = mmsStagingStore,
    )
    val messageTransport: MessageTransport = AndroidSmsTransport(
        context = application,
        roleState = defaultSmsRoleState,
        subscriptions = subscriptionRepository,
        smsProvider = smsProviderDataSource,
        mmsTransport = mmsTransport,
    )
    val messageNotifier: MessageNotifier = AndroidMessageNotifier(
        context = application,
        intentFactory = AppNotificationIntentFactory(application),
    )
    val incomingMessageSink: IncomingMessageSink = IncomingMessageOrchestrator(
        roleState = defaultSmsRoleState,
        smsProvider = smsProviderDataSource,
        contactResolver = contactResolver,
        messageNotifier = messageNotifier,
        replyTargets = replyTargets,
    )
    val inlineReplyHandler: InlineReplyHandler = InlineReplyOrchestrator(
        roleState = defaultSmsRoleState,
        replyTargets = replyTargets,
        replayGuard = SharedPreferencesReplyReplayGuard(application),
        messageTransport = messageTransport,
        messageNotifier = messageNotifier,
    )

    private val _lastTransportResult = MutableStateFlow<TransportResult?>(null)
    val lastTransportResult: StateFlow<TransportResult?> = _lastTransportResult.asStateFlow()

    suspend fun onTransportResult(result: TransportResult) {
        _lastTransportResult.value = result
        when (result) {
            is TransportResult.Sent -> {
                val providerId = result.providerMessageId ?: return
                if (sentPartTracker.record(result)) {
                    smsProviderDataSource.updateStatus(providerId, SmsProviderStatus.COMPLETE)
                }
            }

            is TransportResult.Failed -> {
                sentPartTracker.forget(result.operationId)
                result.providerMessageId?.let { providerId ->
                    smsProviderDataSource.updateStatus(providerId, SmsProviderStatus.FAILED)
                }
            }

            is TransportResult.Delivered,
            is TransportResult.Downloaded,
            is TransportResult.Rejected,
            is TransportResult.Submitted -> Unit
        }
    }

    fun onDownloadedMms(
        @Suppress("UNUSED_PARAMETER") operationId: MessageId,
        @Suppress("UNUSED_PARAMETER") pdu: EncodedMmsPdu,
    ) {
        // ADR 0001 intentionally forbids retaining or partially decoding this
        // payload until an audited codec is admitted.
    }

    fun onDefaultSmsRoleChanged(isDefaultSmsApp: Boolean) {
        if (!isDefaultSmsApp) {
            replyTargets.clear()
            sentPartTracker.clear()
        }
    }

    fun onExternalProviderChanged() {
        // Phase 2 owns bounded provider reconciliation. Phase 1 deliberately
        // does not start an unbounded observer/index pass here.
    }
}

private class SentPartTracker {
    private val sentParts = mutableMapOf<MessageId, BooleanArray>()

    @Synchronized
    fun record(result: TransportResult.Sent): Boolean {
        val parts = sentParts[result.operationId]
            ?.takeIf { it.size == result.unitCount }
            ?: BooleanArray(result.unitCount).also { sentParts[result.operationId] = it }
        parts[result.unitIndex] = true
        val complete = parts.all { it }
        if (complete) sentParts.remove(result.operationId)
        return complete
    }

    @Synchronized
    fun forget(operationId: MessageId) {
        sentParts.remove(operationId)
    }

    @Synchronized
    fun clear() {
        sentParts.clear()
    }
}
