// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.notifications

import org.aurorasms.core.model.ConversationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class InlineReplyValidationTest {
    private val conversationId = ConversationId(41)
    private val notificationId = notificationIdForConversation(conversationId)

    @Test
    fun validInput_isTrimmedAndTyped() {
        val request = validatedInlineReplyRequest(
            conversationValue = conversationId.value,
            notificationId = notificationId,
            requestId = "SMS:9001",
            reply = "  On my way.  ",
            maximumCharacters = 100,
        )

        assertEquals(conversationId, request?.conversationId)
        assertEquals("On my way.", request?.text)
        assertEquals("SMS:9001", request?.replyRequestId)
    }

    @Test
    fun mismatchedNotificationId_isRejected() {
        assertNull(
            validatedInlineReplyRequest(
                conversationValue = conversationId.value,
                notificationId = notificationId + 1,
                requestId = "SMS:9001",
                reply = "On my way.",
                maximumCharacters = 100,
            ),
        )
    }

    @Test
    fun malformedReplayToken_isRejected() {
        assertNull(
            validatedInlineReplyRequest(
                conversationValue = conversationId.value,
                notificationId = notificationId,
                requestId = "../../../reply",
                reply = "On my way.",
                maximumCharacters = 100,
            ),
        )
    }

    @Test
    fun blankOversizedOrControlText_isRejected() {
        assertNull(valid("   ", maximumCharacters = 100))
        assertNull(valid("x".repeat(101), maximumCharacters = 100))
        assertNull(valid("unsafe\u0000text", maximumCharacters = 100))
    }

    @Test
    fun requestToString_doesNotExposeReplyOrIdentifiers() {
        val request = requireNotNull(valid("On my way.", maximumCharacters = 100))

        val rendered = request.toString()
        assertFalse(rendered.contains("On my way."))
        assertFalse(rendered.contains("MMS:42"))
        assertFalse(rendered.contains(conversationId.value.toString()))
    }

    private fun valid(reply: String, maximumCharacters: Int): InlineReplyRequest? =
        validatedInlineReplyRequest(
            conversationValue = conversationId.value,
            notificationId = notificationId,
            requestId = "MMS:42",
            reply = reply,
            maximumCharacters = maximumCharacters,
        )
}
