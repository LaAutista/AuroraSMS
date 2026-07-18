// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.receiver

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aurorasms.core.model.COMPOSER_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.INLINE_REPLY_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.telephony.internal.AndroidSmsTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmsCallbackOriginIntentTest {
    @Test
    fun explicitOriginsRoundTripAndLegacyInlineMarkerRemainsReadable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val operationId = MessageId(
            ProviderKind.PENDING_OPERATION,
            INLINE_REPLY_OPERATION_ID_BOUNDARY + 31L,
        )
        val providerId = ProviderMessageId(ProviderKind.SMS, 41L)

        val explicitInline = SmsDeliveredReceiver.createIntent(
            context = context,
            operationId = operationId,
            providerMessageId = providerId,
            unitIndex = 0,
            unitCount = 1,
            operationOrigin = TransportResult.OperationOrigin.INLINE_REPLY,
        )
        val composer = SmsSentReceiver.createIntent(
            context = context,
            operationId = MessageId(
                ProviderKind.PENDING_OPERATION,
                COMPOSER_OPERATION_ID_BOUNDARY,
            ),
            providerMessageId = providerId,
            unitIndex = 0,
            unitCount = 1,
            operationOrigin = TransportResult.OperationOrigin.COMPOSER,
        )
        val legacyUnmarked = Intent(context, SmsSentReceiver::class.java)
        val legacyInline = Intent(context, SmsSentReceiver::class.java)
            .putExtra(SmsSentReceiver.EXTRA_INLINE_REPLY_OWNED, true)

        assertEquals(
            TransportResult.OperationOrigin.INLINE_REPLY,
            explicitInline.transportOperationOrigin(),
        )
        assertEquals(TransportResult.OperationOrigin.COMPOSER, composer.transportOperationOrigin())
        assertEquals(
            TransportResult.OperationOrigin.UNMARKED,
            legacyUnmarked.transportOperationOrigin(),
        )
        assertEquals(
            TransportResult.OperationOrigin.INLINE_REPLY,
            legacyInline.transportOperationOrigin(),
        )

        // An explicit current marker takes precedence over the compatibility boolean.
        composer.putExtra(SmsSentReceiver.EXTRA_INLINE_REPLY_OWNED, true)
        assertEquals(TransportResult.OperationOrigin.COMPOSER, composer.transportOperationOrigin())
    }

    @Test
    fun providerConversationRoundTripsWhileLegacyIntentRemainsNullable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val conversationId = ConversationId(81L)
        val current = SmsSentReceiver.createIntent(
            context = context,
            operationId = MessageId(ProviderKind.PENDING_OPERATION, 71L),
            providerMessageId = ProviderMessageId(ProviderKind.SMS, 72L),
            unitIndex = 0,
            unitCount = 1,
            providerConversationId = conversationId,
        )
        val legacy = Intent(context, SmsSentReceiver::class.java)

        assertEquals(conversationId, current.smsProviderConversationIdOrNull())
        assertNull(legacy.smsProviderConversationIdOrNull())
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

    @Test
    fun pendingIntentIdentityIncludesOriginButExcludesProviderAndConversationIds() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val operation = MessageId(
            ProviderKind.PENDING_OPERATION,
            COMPOSER_OPERATION_ID_BOUNDARY + 5L,
        )
        val provider = ProviderMessageId(ProviderKind.SMS, 9_999_991L)
        val conversation = ConversationId(9_999_992L)
        val unmarked = SmsSentReceiver.createIntent(
            context,
            operation,
            provider,
            0,
            1,
            TransportResult.OperationOrigin.UNMARKED,
            conversation,
        )
        val composer = SmsSentReceiver.createIntent(
            context,
            operation,
            provider,
            0,
            1,
            TransportResult.OperationOrigin.COMPOSER,
            conversation,
        )

        assertNotEquals(unmarked.data, composer.data)
        val identity = requireNotNull(composer.data).toString()
        assertEquals(false, identity.contains(provider.value.toString()))
        assertEquals(false, identity.contains(conversation.value.toString()))
    }
}
