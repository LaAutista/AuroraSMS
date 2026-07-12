// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.testing

import org.aurorasms.core.notifications.InlineReplyDisposition
import org.aurorasms.core.notifications.InlineReplyHandler
import org.aurorasms.core.notifications.InlineReplyRequest
import org.aurorasms.core.notifications.NotificationConfig
import org.aurorasms.core.notifications.NotificationEntryPoint

class FakeInlineReplyHandler : InlineReplyHandler {
    val requests = mutableListOf<InlineReplyRequest>()
    var responder: (InlineReplyRequest) -> InlineReplyDisposition = {
        InlineReplyDisposition.ACCEPTED
    }

    override suspend fun handle(request: InlineReplyRequest): InlineReplyDisposition {
        requests += request
        return responder(request)
    }
}

class FakeNotificationEntryPoint(
    override val inlineReplyHandler: InlineReplyHandler = FakeInlineReplyHandler(),
    override val maximumInlineReplyCharacters: Int =
        NotificationConfig.DEFAULT_MAXIMUM_REPLY_CHARACTERS,
) : NotificationEntryPoint
