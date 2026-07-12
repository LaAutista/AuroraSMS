// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import android.app.Application
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.notifications.InlineReplyHandler
import org.aurorasms.core.notifications.NotificationEntryPoint
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.EncodedMmsPdu
import org.aurorasms.core.telephony.IncomingMessageSink
import org.aurorasms.core.telephony.MessageTransport
import org.aurorasms.core.telephony.TelephonyEntryPoint

class AuroraSmsApplication : Application(), TelephonyEntryPoint, NotificationEntryPoint {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTerminate() {
        if (::container.isInitialized) container.close()
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
