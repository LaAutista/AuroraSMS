// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import android.app.Application
import org.aurorasms.app.benchmark.BuildVariantBenchmarkPolicy
import org.aurorasms.app.strictmode.BuildVariantStrictMode
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.notifications.InlineReplyHandler
import org.aurorasms.core.notifications.MarkConversationReadHandler
import org.aurorasms.core.notifications.NotificationEntryPoint
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.EncodedMmsPdu
import org.aurorasms.core.telephony.IncomingMessageSink
import org.aurorasms.core.telephony.MessageTransport
import org.aurorasms.core.telephony.TelephonyEntryPoint

class AuroraSmsApplication : Application(), TelephonyEntryPoint, NotificationEntryPoint {
    private val containerDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(
            application = this,
            syntheticIndexOnly = BuildVariantBenchmarkPolicy.syntheticIndexOnly,
        )
    }
    val container: AppContainer by containerDelegate

    internal val isContainerInitialized: Boolean
        get() = containerDelegate.isInitialized()

    override fun onCreate() {
        super.onCreate()
        BuildVariantStrictMode.install()
        if (!BuildVariantBenchmarkPolicy.deferContainerInitialization) container
    }

    override fun onTerminate() {
        if (containerDelegate.isInitialized()) container.close()
        super.onTerminate()
    }

    override val defaultSmsRoleState: DefaultSmsRoleState
        get() = container.defaultSmsRoleState

    override val incomingMessageSink: IncomingMessageSink
        get() = container.incomingMessageSink

    override val messageTransport: MessageTransport
        get() = container.messageTransport

    override val inlineReplyHandler: InlineReplyHandler
        get() = container.inlineReplyHandler

    override val markConversationReadHandler: MarkConversationReadHandler
        get() = container.markConversationReadHandler

    override suspend fun onTransportResult(result: TransportResult) {
        container.onTransportResult(result)
    }

    override suspend fun onDownloadedMms(operationId: MessageId, pdu: EncodedMmsPdu) {
        container.onDownloadedMms(operationId, pdu)
    }

    override suspend fun onDefaultSmsRoleChanged(isDefaultSmsApp: Boolean) {
        container.onDefaultSmsRoleChanged(isDefaultSmsApp)
    }

    override suspend fun onExternalProviderChanged() {
        container.onExternalProviderChanged()
    }
}
