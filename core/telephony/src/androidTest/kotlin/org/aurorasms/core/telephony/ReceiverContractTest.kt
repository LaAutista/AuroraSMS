// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import android.Manifest
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.aurorasms.core.telephony.receiver.MmsDownloadResultReceiver
import org.aurorasms.core.telephony.receiver.MmsSendResultReceiver
import org.aurorasms.core.telephony.receiver.MmsWapPushReceiver
import org.aurorasms.core.telephony.receiver.SmsDeliverReceiver
import org.aurorasms.core.telephony.receiver.SmsDeliveredReceiver
import org.aurorasms.core.telephony.receiver.SmsSentReceiver
import org.aurorasms.core.telephony.service.RespondViaMessageService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverContractTest {
    @Test
    fun platformEntryPointsHaveOfficialGuardsAndResultReceiversStayPrivate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageManager = context.packageManager
        val sms = packageManager.getReceiverInfo(ComponentName(context, SmsDeliverReceiver::class.java), 0)
        val mms = packageManager.getReceiverInfo(ComponentName(context, MmsWapPushReceiver::class.java), 0)
        val respond = packageManager.getServiceInfo(ComponentName(context, RespondViaMessageService::class.java), 0)

        assertTrue(sms.exported)
        assertEquals(Manifest.permission.BROADCAST_SMS, sms.permission)
        assertTrue(mms.exported)
        assertEquals(Manifest.permission.BROADCAST_WAP_PUSH, mms.permission)
        assertTrue(respond.exported)
        assertEquals(Manifest.permission.SEND_RESPOND_VIA_MESSAGE, respond.permission)

        listOf(
            SmsSentReceiver::class.java,
            SmsDeliveredReceiver::class.java,
            MmsSendResultReceiver::class.java,
            MmsDownloadResultReceiver::class.java,
        ).forEach { receiver ->
            assertFalse(packageManager.getReceiverInfo(ComponentName(context, receiver), 0).exported)
        }
    }
}
