// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.testing

import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.telephony.IncomingMessage
import org.aurorasms.core.telephony.IncomingMessageSink
import org.aurorasms.core.telephony.IncomingPersistResult

class FakeIncomingMessageSink : IncomingMessageSink {
    val messages = mutableListOf<IncomingMessage>()
    private var nextProviderId = 1L

    var responder: (IncomingMessage) -> IncomingPersistResult = { message ->
        val kind = when (message) {
            is IncomingMessage.Sms -> ProviderKind.SMS
            is IncomingMessage.MmsWapPush -> ProviderKind.MMS
        }
        val value = nextProviderId++
        IncomingPersistResult.Persisted(
            providerId = ProviderMessageId(kind, value),
            conversationId = ConversationId(value),
        )
    }

    override suspend fun persist(message: IncomingMessage): IncomingPersistResult {
        messages += message
        return responder(message)
    }
}
