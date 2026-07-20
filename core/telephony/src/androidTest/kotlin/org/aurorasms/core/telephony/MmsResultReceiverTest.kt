// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.telephony.receiver.MmsDownloadResultReceiver
import org.aurorasms.core.telephony.receiver.MmsSendResultReceiver
import org.aurorasms.core.telephony.receiver.OutgoingMmsProviderIntentIdentity
import org.aurorasms.core.telephony.receiver.outgoingMmsProviderIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    @Test
    fun outgoingProviderIdentityIsEitherAnExactPairOrAbsent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val operation = MessageId(ProviderKind.PENDING_OPERATION, 93L)
        val provider = ProviderMessageId(ProviderKind.MMS, 95L)
        val conversation = ConversationId(97L)
        val uri = Uri.parse(
            "content://${context.packageName}.mms-pdu/mms_send/00000000-0000-0000-0000-000000000001.pdu",
        )

        val exact = MmsSendResultReceiver.createIntent(
            context,
            operation,
            uri,
            provider,
            conversation,
        )
        val absent = MmsSendResultReceiver.createIntent(context, operation, uri)

        assertEquals(
            OutgoingMmsProviderIntentIdentity.Exact(provider, conversation),
            exact.outgoingMmsProviderIdentity(),
        )
        assertEquals(
            OutgoingMmsProviderIntentIdentity.Absent,
            absent.outgoingMmsProviderIdentity(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            MmsSendResultReceiver.createIntent(
                context,
                operation,
                uri,
                providerId = provider,
                conversationId = null,
            )
        }
    }

    @Test
    fun malformedOutgoingProviderIdentityNeverBecomesExact() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val operation = MessageId(ProviderKind.PENDING_OPERATION, 99L)
        val uri = Uri.parse(
            "content://${context.packageName}.mms-pdu/mms_send/00000000-0000-0000-0000-000000000002.pdu",
        )
        val providerOnly = MmsSendResultReceiver.createIntent(context, operation, uri)
            .putExtra(MmsSendResultReceiver.EXTRA_PROVIDER_ID, 101L)
        val nonPositivePair = MmsSendResultReceiver.createIntent(context, operation, uri)
            .putExtra(MmsSendResultReceiver.EXTRA_PROVIDER_ID, 0L)
            .putExtra(MmsSendResultReceiver.EXTRA_CONVERSATION_ID, 103L)

        assertEquals(
            OutgoingMmsProviderIntentIdentity.Malformed,
            providerOnly.outgoingMmsProviderIdentity(),
        )
        assertEquals(
            OutgoingMmsProviderIntentIdentity.Malformed,
            nonPositivePair.outgoingMmsProviderIdentity(),
        )
    }
}
