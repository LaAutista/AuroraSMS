// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.telephony.receiver.MmsDownloadResultReceiver
import org.aurorasms.core.telephony.receiver.MmsSendResultReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MmsResultReceiverTest {
    @Test
    fun resultIntentsAreExplicitAndReceiversArePrivate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val operation = MessageId(ProviderKind.PENDING_OPERATION, 91L)
        val uri = Uri.parse("content://${context.packageName}.mms-pdu/mms_send/00000000-0000-0000-0000-000000000000.pdu")
        val send = MmsSendResultReceiver.createIntent(context, operation, uri)
        val download = MmsDownloadResultReceiver.createIntent(context, operation, uri)

        assertEquals(MmsSendResultReceiver::class.java.name, send.component?.className)
        assertEquals(MmsDownloadResultReceiver::class.java.name, download.component?.className)
        assertFalse(
            context.packageManager.getReceiverInfo(
                ComponentName(context, MmsSendResultReceiver::class.java),
                0,
            ).exported,
        )
        assertFalse(
            context.packageManager.getReceiverInfo(
                ComponentName(context, MmsDownloadResultReceiver::class.java),
                0,
            ).exported,
        )
    }
}
