// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.receiver

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aurorasms.core.model.INLINE_REPLY_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.telephony.internal.AndroidSmsTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmsCallbackOriginIntentTest {
    @Test
    fun markerlessLegacyHighIdStaysUnmarkedAndExplicitMarkerRoundTrips() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val operationId = MessageId(
            ProviderKind.PENDING_OPERATION,
            INLINE_REPLY_OPERATION_ID_BOUNDARY + 31L,
        )
        val providerId = ProviderMessageId(ProviderKind.SMS, 41L)

        val legacy = SmsSentReceiver.createIntent(
            context = context,
            operationId = operationId,
            providerMessageId = providerId,
            unitIndex = 0,
            unitCount = 1,
        )
        val owned = SmsDeliveredReceiver.createIntent(
            context = context,
            operationId = operationId,
            providerMessageId = providerId,
            unitIndex = 0,
            unitCount = 1,
            operationOrigin = TransportResult.OperationOrigin.INLINE_REPLY,
        )

        assertEquals(TransportResult.OperationOrigin.UNMARKED, legacy.transportOperationOrigin())
        assertEquals(TransportResult.OperationOrigin.INLINE_REPLY, owned.transportOperationOrigin())
    }

    @Test
    fun collidingRequestCodesStillHaveDistinctPendingIntentIdentityData() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val firstOperation = MessageId(ProviderKind.PENDING_OPERATION, 1L)
        val collidingOperation = MessageId(ProviderKind.PENDING_OPERATION, 1L shl 32)
        val providerId = ProviderMessageId(ProviderKind.SMS, 42L)

        assertEquals(
            AndroidSmsTransport.requestCode(firstOperation.value, 0, 0x51),
            AndroidSmsTransport.requestCode(collidingOperation.value, 0, 0x51),
        )
        val first = SmsSentReceiver.createIntent(context, firstOperation, providerId, 0, 1)
        val colliding = SmsSentReceiver.createIntent(context, collidingOperation, providerId, 0, 1)

        assertNotEquals(first.data, colliding.data)
    }
}
