// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.charset.StandardCharsets
import org.aurorasms.core.telephony.OutgoingMmsAttachment
import org.aurorasms.core.telephony.OutgoingMmsPayload
import org.aurorasms.core.telephony.RecipientSet
import org.aurorasms.core.telephony.codec.aosp.pdu.PduParser
import org.aurorasms.core.telephony.codec.aosp.pdu.SendReq
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeneralMmsEncoderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun deterministicGroupMessageProducesOneSendReqWithEveryRecipientAndPart() {
        val payload = message()
        val encoder = encoder()

        val first = encoder.encode(group(), payload) as GeneralMmsEncodingResult.Encoded
        val second = encoder.encode(group(), payload) as GeneralMmsEncodingResult.Encoded
        val parsed = PduParser(first.pdu.copyBytes()).parse() as SendReq

        assertArrayEquals(first.pdu.copyBytes(), second.pdu.copyBytes())
        assertEquals(listOf(RECIPIENT_ONE, RECIPIENT_TWO), parsed.to.map { it.textString.utf8() })
        assertEquals(SUBJECT, parsed.subject.textString.utf8())
        assertEquals(4, parsed.body.partsNum)
        assertEquals(
            listOf("application/smil", "text/plain", "image/png", "audio/mp4"),
            (0 until parsed.body.partsNum).map { parsed.body.getPart(it).contentType.ascii() },
        )
        assertTrue(first.pdu.size <= org.aurorasms.core.telephony.EncodedMmsPdu.MAX_ENCODED_BYTES)
    }

    @Test
    fun invalidMetadataAndOversizedEncodedPayloadFailClosed() {
        val invalidClock = GeneralMmsEncoder(
            context,
            nowMillis = { -1L },
            transactionId = { TRANSACTION },
        )
        val invalidTransaction = GeneralMmsEncoder(
            context,
            nowMillis = { NOW },
            transactionId = { "bad\u0000transaction" },
        )
        val oversized = OutgoingMmsPayload.Message(
            text = "\u2603".repeat(100_000),
            subject = null,
            attachments = listOf(
                attachment(OutgoingMmsAttachment.VIDEO_MP4, ByteArray(OutgoingMmsAttachment.MAX_BYTES)),
            ),
        )

        assertEquals(
            GeneralMmsEncodingFailure.INVALID_METADATA,
            (invalidClock.encode(group(), message()) as GeneralMmsEncodingResult.Rejected).reason,
        )
        assertEquals(
            GeneralMmsEncodingFailure.INVALID_METADATA,
            (invalidTransaction.encode(group(), message()) as GeneralMmsEncodingResult.Rejected).reason,
        )
        assertEquals(
            GeneralMmsEncodingFailure.PAYLOAD_TOO_LARGE,
            (encoder().encode(group(), oversized) as GeneralMmsEncodingResult.Rejected).reason,
        )
    }

    @Test
    fun diagnosticsDoNotExposeTextSubjectOrAttachmentBytes() {
        val payload = message()
        val encoded = encoder().encode(group(), payload)
        val diagnostics = listOf(payload.toString(), encoded.toString()).joinToString()

        assertFalse(diagnostics.contains(TEXT))
        assertFalse(diagnostics.contains(SUBJECT))
        assertFalse(diagnostics.contains("11, 12, 13"))
    }

    private fun encoder(): GeneralMmsEncoder = GeneralMmsEncoder(
        context,
        nowMillis = { NOW },
        transactionId = { TRANSACTION },
    )

    private fun group(): RecipientSet =
        (RecipientSet.parse(listOf(RECIPIENT_ONE, RECIPIENT_TWO)) as RecipientSet.CreationResult.Valid).recipients

    private fun message(): OutgoingMmsPayload.Message = OutgoingMmsPayload.Message(
        text = TEXT,
        subject = SUBJECT,
        attachments = listOf(
            attachment(OutgoingMmsAttachment.IMAGE_PNG, byteArrayOf(1, 2, 3)),
            attachment(OutgoingMmsAttachment.AUDIO_MP4, byteArrayOf(11, 12, 13)),
        ),
    )

    private fun attachment(contentType: String, bytes: ByteArray): OutgoingMmsAttachment =
        (
            OutgoingMmsAttachment.create(contentType, bytes) as
                OutgoingMmsAttachment.CreationResult.Valid
            ).attachment

    private fun ByteArray.ascii(): String = toString(StandardCharsets.US_ASCII)
    private fun ByteArray.utf8(): String = toString(StandardCharsets.UTF_8)

    private companion object {
        const val NOW = 1_720_000_000_000L
        const val TRANSACTION = "Tgeneral012345678"
        const val RECIPIENT_ONE = "+15551230001"
        const val RECIPIENT_TWO = "+15551230002"
        const val TEXT = "Synthetic general MMS body"
        const val SUBJECT = "Synthetic general MMS subject"
    }
}
