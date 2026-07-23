// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.notifications

/** Implemented by the application object and consumed only through this narrow contract. */
interface NotificationEntryPoint {
    val inlineReplyHandler: InlineReplyHandler

    val markConversationReadHandler: MarkConversationReadHandler

    val maximumInlineReplyCharacters: Int
        get() = NotificationConfig.DEFAULT_MAXIMUM_REPLY_CHARACTERS
}
