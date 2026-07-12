// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.aurorasms.core.telephony.IncomingMessage

class MmsWapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        if (intent.type != IncomingMessage.MmsWapPush.MMS_MIME_TYPE) return
        val entryPoint = context.telephonyEntryPointOrNull() ?: return
        if (!entryPoint.defaultSmsRoleState.isRoleHeld()) return
        val pdu = intent.getByteArrayExtra(EXTRA_WAP_DATA)
            ?.takeIf { it.isNotEmpty() && it.size <= IncomingMessage.MmsWapPush.MAX_WAP_PDU_BYTES }
            ?: return
        val incoming = IncomingMessage.MmsWapPush(
            pdu = pdu,
            mimeType = intent.type.orEmpty(),
            receivedTimestampMillis = System.currentTimeMillis(),
            subscriptionId = intent.subscriptionIdOrNull(),
        )
        dispatchAsync(context) { it.incomingMessageSink.persist(incoming) }
    }

    companion object {
        private const val EXTRA_WAP_DATA = "data"
    }
}
