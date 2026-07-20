// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSignatureTest {
    @Test
    fun editorNormalizationIsBoundedAndNeverTruncates() {
        assertNull(MessageSignature.fromUserInput("  \r\n "))
        assertEquals("first\nsecond", MessageSignature.fromUserInput(" first\r\nsecond ")?.value)
        assertNull(MessageSignature.fromUserInput("x".repeat(MessageSignature.MAX_CHARACTERS + 1)))
        assertNull(MessageSignature.fromUserInput("a\nb\nc\nd\ne"))
        assertNull(MessageSignature.fromUserInput("visible\u0000hidden"))
        assertNull(MessageSignature.fromUserInput("invalid\uD800unicode"))
    }

    @Test
    fun outgoingResolutionAppendsExactVisibleSeparatorWithoutChangingDraft() {
        val body = "Synthetic draft"
        val signature = checkNotNull(MessageSignature.fromUserInput("Aurora\nSMS"))

        assertEquals("Synthetic draft\n-- \nAurora\nSMS", resolveOutgoingBody(body, signature))
        assertEquals("Synthetic draft", body)
        assertEquals(body, resolveOutgoingBody(body, null))
    }

    @Test
    fun outgoingResolutionFailsClosedAtDraftBound() {
        val signature = checkNotNull(MessageSignature.fromUserInput("signature"))
        assertNull(resolveOutgoingBody("x".repeat(Draft.MAX_BODY_CHARACTERS), signature))
    }

    @Test
    fun storedValueMustAlreadyBeCanonical() {
        assertEquals("canonical", MessageSignature.fromStorageValue("canonical").value)
        assertThrows(IllegalArgumentException::class.java) {
            MessageSignature.fromStorageValue(" canonical ")
        }
    }

    @Test
    fun diagnosticStringRedactsContent() {
        val signature = checkNotNull(MessageSignature.fromUserInput("Private signature"))
        assertTrue(signature.toString().contains("REDACTED"))
        assertFalse(signature.toString().contains("Private"))
    }
}
