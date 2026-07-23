// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.provider.Telephony
import org.aurorasms.core.telephony.SmsProviderStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SmsProviderStatusProjectionTest {
    @Test
    fun deliveryFailureKeepsMessageInSentBox() {
        val projection = SmsProviderStatus.DELIVERY_FAILED.toSmsProviderStatusProjection()

        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, projection.messageType)
        assertEquals(Telephony.Sms.STATUS_FAILED, projection.rawStatus)
    }

    @Test
    fun everyProjectionRoundTripsToItsStatus() {
        SmsProviderStatus.entries.forEach { status ->
            val projection = status.toSmsProviderStatusProjection()

            assertEquals(
                status,
                smsProviderStatusFromRaw(projection.messageType, projection.rawStatus),
            )
        }
    }

    @Test
    fun unknownTypeAndStatusPairsFailClosed() {
        assertEquals(
            null,
            smsProviderStatusFromRaw(
                Telephony.Sms.MESSAGE_TYPE_INBOX,
                Telephony.Sms.STATUS_COMPLETE,
            ),
        )
        assertEquals(
            null,
            smsProviderStatusFromRaw(
                Telephony.Sms.MESSAGE_TYPE_SENT,
                Telephony.Sms.STATUS_PENDING,
            ),
        )
    }
}
