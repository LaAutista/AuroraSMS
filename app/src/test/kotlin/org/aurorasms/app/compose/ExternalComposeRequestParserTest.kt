// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.compose

import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.state.Draft
import org.aurorasms.core.telephony.RecipientSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalComposeRequestParserTest {
    @Test
    fun `all supported schemes are case insensitive and preserve requested transport`() {
        mapOf(
            "sms" to MessageTransportKind.SMS,
            "SMSTO" to MessageTransportKind.SMS,
            "mMs" to MessageTransportKind.MMS,
            "MMSTO" to MessageTransportKind.MMS,
        ).forEach { (scheme, expectedTransport) ->
            val request = parse(dataUri = "$scheme:+15550100001")

            assertNotNull(request)
            assertEquals(expectedTransport, request?.requestedTransport)
        }
    }

    @Test
    fun `only ACTION_SENDTO with an opaque supported URI is accepted`() {
        assertNull(parse(action = "android.intent.action.VIEW"))
        assertNull(parse(dataUri = "mailto:synthetic@example.invalid"))
        assertNull(parse(dataUri = "smsto://+15550100001"))
        assertNull(parse(dataUri = "smsto:+15550100001#fragment"))
    }

    @Test
    fun `comma semicolon and encoded separators produce one validated recipient set`() {
        val request = parse(
            dataUri =
                "smsto:%2B15550100001%2Csynthetic%40example.invalid;%2B15550100002",
        )

        assertEquals(
            listOf(
                ParticipantAddress("+15550100001"),
                ParticipantAddress("synthetic@example.invalid"),
                ParticipantAddress("+15550100002"),
            ),
            request?.recipients?.addresses,
        )
    }

    @Test
    fun `URI body is percent decoded and takes precedence over both extras`() {
        val request = parse(
            dataUri = "smsto:+15550100001?body=Aurora%20lights%20%26%20snow%20%E2%98%83",
            smsBody = "private sms extra",
            extraText = "private generic extra",
        )

        assertEquals("Aurora lights & snow ☃", request?.body)
    }

    @Test
    fun `an explicitly empty URI body still takes precedence over extras`() {
        val request = parse(
            dataUri = "smsto:+15550100001?body=",
            smsBody = "private sms extra",
            extraText = "private generic extra",
        )

        assertEquals("", request?.body)
    }

    @Test
    fun `sms body takes precedence over EXTRA_TEXT and missing bodies become empty`() {
        assertEquals(
            "sms extra",
            parse(smsBody = "sms extra", extraText = "generic extra")?.body,
        )
        assertEquals("generic extra", parse(extraText = "generic extra")?.body)
        assertEquals("", parse()?.body)
    }

    @Test
    fun `malformed percent escapes and UTF-8 fail closed`() {
        assertNull(parse(dataUri = "smsto:%"))
        assertNull(parse(dataUri = "smsto:%2"))
        assertNull(parse(dataUri = "smsto:%GG"))
        assertNull(parse(dataUri = "smsto:%C3%28"))
        assertNull(parse(dataUri = "smsto:+15550100001?body=%E2%28%A1"))
    }

    @Test
    fun `empty malformed and control-character recipients fail closed`() {
        assertNull(parse(dataUri = "smsto:"))
        assertNull(parse(dataUri = "smsto:+15550100001,"))
        assertNull(parse(dataUri = "smsto:+15550100001,,+15550100002"))
        assertNull(parse(dataUri = "smsto:%0A"))
        assertNull(parse(dataUri = "smsto:+"))
    }

    @Test
    fun `recipient length and count bounds are exact`() {
        val maximumAddress = "a".repeat(ParticipantAddress.MAX_ADDRESS_CHARACTERS)
        assertNotNull(parse(dataUri = "smsto:$maximumAddress"))
        assertNull(parse(dataUri = "smsto:${maximumAddress}a"))

        val maximumRecipients = List(RecipientSet.MAX_RECIPIENTS) { "recipient-$it" }
        assertEquals(
            RecipientSet.MAX_RECIPIENTS,
            parse(dataUri = "smsto:${maximumRecipients.joinToString(",")}")?.recipients?.size,
        )
        assertNull(
            parse(
                dataUri = "smsto:${(maximumRecipients + "one-too-many").joinToString(";")}",
            ),
        )
    }

    @Test
    fun `shared recipient helper combines existing recipients and canonicalizes duplicates`() {
        val result = parseComposeRecipientList(
            raw = "+1 (555) 010-0001;synthetic@example.invalid",
            existing = listOf(ParticipantAddress("+15550100001")),
        ) as RecipientSet.CreationResult.Valid

        assertEquals(
            listOf(
                ParticipantAddress("+15550100001"),
                ParticipantAddress("synthetic@example.invalid"),
            ),
            result.recipients.addresses,
        )
    }

    @Test
    fun `shared recipient helper rejects blank segments and an empty aggregate`() {
        assertTrue(
            parseComposeRecipientList("") is RecipientSet.CreationResult.Rejected,
        )
        assertTrue(
            parseComposeRecipientList("+15550100001,,+15550100002")
                is RecipientSet.CreationResult.Rejected,
        )
    }

    @Test
    fun `selected body must fit the durable draft boundary without truncation`() {
        val maximumBody = "x".repeat(Draft.MAX_BODY_CHARACTERS)
        assertEquals(maximumBody, parse(smsBody = maximumBody)?.body)
        assertNull(parse(smsBody = maximumBody + "x"))
        assertNull(parse(dataUri = "smsto:+15550100001?body=${maximumBody}x"))
    }

    @Test
    fun `a valid higher-precedence body shields an oversized unused extra`() {
        val oversized = "x".repeat(Draft.MAX_BODY_CHARACTERS + 1)

        assertEquals(
            "URI",
            parse(
                dataUri = "smsto:+15550100001?body=URI",
                smsBody = oversized,
                extraText = oversized,
            )?.body,
        )
        assertEquals(
            "SMS",
            parse(smsBody = "SMS", extraText = oversized)?.body,
        )
    }

    @Test
    fun `duplicate body parameters and malformed query separators fail closed`() {
        assertNull(parse(dataUri = "smsto:+15550100001?body=first&body=second"))
        assertNull(parse(dataUri = "smsto:+15550100001?body=first&&unused=second"))
        assertNull(parse(dataUri = "smsto:+15550100001?body=first&"))
    }

    @Test
    fun `explicit MMS survives a single-recipient request`() {
        val request = requireNotNull(parse(dataUri = "mmsto:+15550100001"))

        assertEquals(1, request.recipients.size)
        assertEquals(MessageTransportKind.MMS, request.requestedTransport)
        assertEquals(
            MessageTransportKind.MMS,
            request.recipients.requiredTransport(
                explicitlyRequestedMms = request.requestedTransport == MessageTransportKind.MMS,
            ),
        )
    }

    @Test
    fun `request string representation redacts recipients and body`() {
        val request = requireNotNull(
            parse(
                dataUri = "smsto:+15550100001",
                smsBody = "private synthetic body",
            ),
        )
        val rendered = request.toString()

        assertFalse(rendered.contains("+15550100001"))
        assertFalse(rendered.contains("private synthetic body"))
        assertTrue(rendered.contains("REDACTED"))
        assertTrue(rendered.contains("requestedTransport=SMS"))
    }

    private fun parse(
        action: String? = ACTION_SENDTO,
        dataUri: String? = "smsto:+15550100001",
        smsBody: CharSequence? = null,
        extraText: CharSequence? = null,
    ): ComposeRequest? = ExternalComposeRequestParser.parse(
        action = action,
        dataUri = dataUri,
        smsBody = smsBody,
        extraText = extraText,
    )

    private companion object {
        const val ACTION_SENDTO = "android.intent.action.SENDTO"
    }
}
