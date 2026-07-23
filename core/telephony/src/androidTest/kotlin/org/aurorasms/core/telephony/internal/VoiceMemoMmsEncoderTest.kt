// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.security.MessageDigest
import org.aurorasms.core.telephony.OutgoingMmsPayload
import org.aurorasms.core.telephony.OutgoingVoiceMemo
import org.aurorasms.core.telephony.RecipientSet
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class VoiceMemoMmsEncoderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun deterministicSyntheticVoiceMemoMatchesReviewedGoldenPdu() {
        val encoder = deterministicEncoder()
        val payload = payload(
            bytes = byteArrayOf(0x00, 0x01, 0x7f, 0xff.toByte()),
            durationMillis = 4_321L,
            text = "Synthetic hello \u2603",
            subject = "Synthetic subject",
        )

        val first = encoder.encode(singleRecipient(), payload).encodedBytes()
        val second = encoder.encode(singleRecipient(), payload).encodedBytes()

        assertArrayEquals(first, second)
        assertEquals(EXPECTED_GOLDEN_SIZE, first.size)
        assertEquals(EXPECTED_GOLDEN_SHA256, first.sha256())
    }

    @Test
    fun groupVoiceMemoFailsClosedWithoutEncodingOrFanout() {
        val recipients = validRecipients("+15551230000", "+15551230001")

        val result = deterministicEncoder().encode(
            recipients,
            payload(byteArrayOf(1, 2, 3), durationMillis = 1_000L),
        )

        assertEquals(
            VoiceMemoMmsEncodingFailure.UNSUPPORTED_RECIPIENT_SET,
            (result as VoiceMemoMmsEncodingResult.Rejected).reason,
        )
    }

    @Test
    fun boundedSyntheticCorpusAlwaysProducesOneBoundedPdu() {
        val encoder = deterministicEncoder()
        val sizes = listOf(1, 2, 3, 31, 127, 128, 255, 1_024, 65_535, OutgoingVoiceMemo.MAX_BYTES)

        sizes.forEachIndexed { index, size ->
            val bytes = ByteArray(size) { offset -> ((offset * 31 + index) and 0xff).toByte() }
            val result = encoder.encode(
                singleRecipient(),
                payload(
                    bytes = bytes,
                    durationMillis = (index + 1L) * 1_000L,
                    text = if (index % 2 == 0) "Synthetic corpus $index" else null,
                ),
            )
            val encoded = (result as VoiceMemoMmsEncodingResult.Encoded).pdu
            assertTrue(encoded.size > size)
            assertTrue(encoded.size <= org.aurorasms.core.telephony.EncodedMmsPdu.MAX_ENCODED_BYTES)
        }
    }

    @Test
    fun invalidClockAndTransactionMetadataFailClosed() {
        val payload = payload(byteArrayOf(1), durationMillis = 1L)
        val invalidClock = VoiceMemoMmsEncoder(
            context,
            nowMillis = { -1L },
            transactionId = { "T0123456789abcdef" },
        )
        val invalidTransaction = VoiceMemoMmsEncoder(
            context,
            nowMillis = { FIXED_NOW_MILLIS },
            transactionId = { "bad\u0000transaction" },
        )

        assertEquals(
            VoiceMemoMmsEncodingFailure.INVALID_METADATA,
            (invalidClock.encode(singleRecipient(), payload) as VoiceMemoMmsEncodingResult.Rejected).reason,
        )
        assertEquals(
            VoiceMemoMmsEncodingFailure.INVALID_METADATA,
            (invalidTransaction.encode(singleRecipient(), payload) as VoiceMemoMmsEncodingResult.Rejected).reason,
        )
    }

    private fun deterministicEncoder(): VoiceMemoMmsEncoder = VoiceMemoMmsEncoder(
        context,
        nowMillis = { FIXED_NOW_MILLIS },
        transactionId = { "T0123456789abcdef" },
    )

    private fun singleRecipient(): RecipientSet = validRecipients("+15551230000")

    private fun validRecipients(vararg values: String): RecipientSet =
        (RecipientSet.parse(values.asIterable()) as RecipientSet.CreationResult.Valid).recipients

    private fun payload(
        bytes: ByteArray,
        durationMillis: Long,
        text: String? = null,
        subject: String? = null,
    ): OutgoingMmsPayload.VoiceMemo = OutgoingMmsPayload.VoiceMemo(
        text = text,
        subject = subject,
        memo = (
            OutgoingVoiceMemo.create(bytes, durationMillis) as
                OutgoingVoiceMemo.CreationResult.Valid
            ).memo,
    )

    private fun VoiceMemoMmsEncodingResult.encodedBytes(): ByteArray =
        (this as VoiceMemoMmsEncodingResult.Encoded).pdu.copyBytes()

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        private const val FIXED_NOW_MILLIS = 1_720_000_000_000L
        private const val EXPECTED_GOLDEN_SIZE = 539
        private const val EXPECTED_GOLDEN_SHA256 =
            "e8abd80ab558cc9ba2179519cb928b131889f78d35b7258ba419cc6a0bd87867"
    }
}
