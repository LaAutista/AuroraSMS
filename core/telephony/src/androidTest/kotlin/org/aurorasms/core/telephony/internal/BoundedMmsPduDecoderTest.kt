// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.random.Random
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.telephony.EncodedMmsPdu
import org.aurorasms.core.telephony.codec.aosp.pdu.CharacterSets
import org.aurorasms.core.telephony.codec.aosp.pdu.EncodedStringValue
import org.aurorasms.core.telephony.codec.aosp.pdu.PduBody
import org.aurorasms.core.telephony.codec.aosp.pdu.PduComposer
import org.aurorasms.core.telephony.codec.aosp.pdu.PduHeaders
import org.aurorasms.core.telephony.codec.aosp.pdu.PduPart
import org.aurorasms.core.telephony.codec.aosp.pdu.SendReq
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BoundedMmsPduDecoderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val decoder = BoundedMmsPduDecoder()

    @Test
    fun decodesBoundedNotificationWithoutExposingContentInDiagnostics() {
        val result = decoder.decode(
            encoded(notificationPdu()),
        ) as BoundedMmsDecodeResult.Decoded
        val notification = result.pdu as BoundedMmsPdu.Notification

        assertEquals(TRANSACTION_ID, notification.transactionId)
        assertEquals(CONTENT_LOCATION, notification.contentLocation)
        assertEquals(ParticipantAddress(SENDER), notification.sender)
        assertEquals(SUBJECT, notification.subject)
        assertEquals(4_096L, notification.messageSizeBytes)
        assertTrue(notification.expiryTimestampMillis > 0L)
        assertEquals("personal", notification.messageClass)

        val diagnostic = result.toString()
        listOf(TRANSACTION_ID, CONTENT_LOCATION, SENDER, SUBJECT).forEach { secret ->
            assertFalse(diagnostic.contains(secret))
        }
    }

    @Test
    fun decodesSyntheticGroupRetrieveConfAndKeepsPartBytesImmutable() {
        val result = decoder.decode(encoded(retrieveConfPdu())) as BoundedMmsDecodeResult.Decoded
        val retrieved = result.pdu as BoundedMmsPdu.Retrieved

        assertEquals(ParticipantAddress(SENDER), retrieved.sender)
        assertEquals(
            listOf(ParticipantAddress(RECIPIENT_ONE), ParticipantAddress(RECIPIENT_TWO)),
            retrieved.to,
        )
        assertEquals(
            listOf(ParticipantAddress(SENDER), ParticipantAddress(RECIPIENT_ONE), ParticipantAddress(RECIPIENT_TWO)),
            retrieved.participants,
        )
        assertEquals(SUBJECT, retrieved.subject)
        assertEquals(FIXED_DATE_SECONDS * 1_000L, retrieved.sentTimestampMillis)
        assertEquals(TRANSACTION_ID, retrieved.transactionId)
        assertEquals(2, retrieved.parts.size)
        assertEquals(TEXT, retrieved.parts.first().decodedText)
        assertEquals("image/png", retrieved.parts.last().contentType)

        val imagePart = retrieved.parts.last()
        val copy = imagePart.copyBytes()
        copy.fill(0)
        assertArrayEquals(IMAGE_BYTES, imagePart.copyBytes())

        val diagnostic = result.toString()
        listOf(SENDER, RECIPIENT_ONE, RECIPIENT_TWO, SUBJECT, TEXT, TRANSACTION_ID).forEach { secret ->
            assertFalse(diagnostic.contains(secret))
        }
    }

    @Test
    fun notificationRejectsUnsafeTransportLocationAndOversizedAdvertisement() {
        val unsafe = decoder.decode(
            encoded(notificationPdu(contentLocation = "ftp://fixtures.example.invalid/mms")),
        ) as BoundedMmsDecodeResult.Rejected
        val oversized = decoder.decode(
            encoded(notificationPdu(messageSize = EncodedMmsPdu.MAX_ENCODED_BYTES.toLong() + 1L)),
        ) as BoundedMmsDecodeResult.Rejected

        assertEquals(BoundedMmsDecodeFailure.UNSAFE_METADATA, unsafe.reason)
        assertEquals(BoundedMmsDecodeFailure.LIMIT_EXCEEDED, oversized.reason)
    }

    @Test
    fun truncationAndDeterministicMutationCorpusAlwaysFailClosedWithoutThrowing() {
        val valid = listOf(notificationPdu(), retrieveConfPdu())
        valid.forEach { bytes ->
            for (size in 1 until bytes.size) {
                assertTrue(decoder.decode(encoded(bytes.copyOf(size))) is BoundedMmsDecodeResult.Rejected)
            }
        }

        val random = Random(0x4155524f)
        repeat(1_024) { index ->
            val bytes = ByteArray((index % 511) + 1)
            random.nextBytes(bytes)
            decoder.decode(encoded(bytes))
        }
    }

    @Test
    fun moreThanTwentyFivePartsIsRejectedBeforeProjection() {
        val result = decoder.decode(encoded(retrieveConfPdu(partCount = 26)))

        assertTrue(result is BoundedMmsDecodeResult.Rejected)
    }

    private fun retrieveConfPdu(partCount: Int = 2): ByteArray {
        val textPart = part(
            contentType = "text/plain",
            location = "text.txt",
            data = TEXT.toByteArray(StandardCharsets.UTF_8),
            charset = CharacterSets.UTF_8,
        )
        val imagePart = part(
            contentType = "image/png",
            location = "image.png",
            data = IMAGE_BYTES,
        )
        val body = PduBody().apply {
            if (partCount == 2) {
                addPart(textPart)
                addPart(imagePart)
            } else {
                repeat(partCount) { index ->
                    addPart(
                        part(
                            contentType = "text/plain",
                            location = "part-$index.txt",
                            data = byteArrayOf(index.toByte()),
                            charset = CharacterSets.UTF_8,
                        ),
                    )
                }
            }
        }
        val request = SendReq(
            "application/vnd.wap.multipart.related".ascii(),
            EncodedStringValue(CharacterSets.UTF_8, SENDER.utf8()),
            PduHeaders.CURRENT_MMS_VERSION,
            TRANSACTION_ID.ascii(),
        ).apply {
            setTo(
                arrayOf(
                    EncodedStringValue(CharacterSets.UTF_8, RECIPIENT_ONE.utf8()),
                    EncodedStringValue(CharacterSets.UTF_8, RECIPIENT_TWO.utf8()),
                ),
            )
            setDate(FIXED_DATE_SECONDS)
            setSubject(EncodedStringValue(CharacterSets.UTF_8, SUBJECT.utf8()))
            setMessageClass(PduHeaders.MESSAGE_CLASS_PERSONAL_STR.ascii())
            setBody(body)
        }
        val bytes = requireNotNull(PduComposer(context, request).make())
        check(bytes.size >= 2 && bytes[0].toInt() and 0xff == PduHeaders.MESSAGE_TYPE)
        check(bytes[1].toInt() and 0xff == PduHeaders.MESSAGE_TYPE_SEND_REQ)
        bytes[1] = PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF.toByte()
        return bytes
    }

    private fun part(
        contentType: String,
        location: String,
        data: ByteArray,
        charset: Int = 0,
    ): PduPart = PduPart().apply {
        setContentType(contentType.ascii())
        setContentLocation(location.ascii())
        setName(location.ascii())
        setFilename(location.ascii())
        setContentId(location.substringBefore('.').ascii())
        if (charset != 0) setCharset(charset)
        setData(data)
    }

    private fun notificationPdu(
        contentLocation: String = CONTENT_LOCATION,
        messageSize: Long = 4_096L,
    ): ByteArray = ByteArrayOutputStream().apply {
        header(PduHeaders.MESSAGE_TYPE)
        octet(PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND)
        header(PduHeaders.TRANSACTION_ID)
        text(TRANSACTION_ID)
        header(PduHeaders.MMS_VERSION)
        octet(PduHeaders.CURRENT_MMS_VERSION or 0x80)
        header(PduHeaders.FROM)
        val from = ByteArrayOutputStream().apply {
            octet(PduHeaders.FROM_ADDRESS_PRESENT_TOKEN)
            text(SENDER)
        }.toByteArray()
        valueLength(from.size)
        write(from)
        header(PduHeaders.SUBJECT)
        text(SUBJECT)
        header(PduHeaders.MESSAGE_CLASS)
        octet(PduHeaders.MESSAGE_CLASS_PERSONAL)
        header(PduHeaders.MESSAGE_SIZE)
        longInteger(messageSize)
        header(PduHeaders.EXPIRY)
        val expiry = ByteArrayOutputStream().apply {
            octet(PduHeaders.VALUE_RELATIVE_TOKEN)
            longInteger(7L * 24L * 60L * 60L)
        }.toByteArray()
        valueLength(expiry.size)
        write(expiry)
        header(PduHeaders.CONTENT_LOCATION)
        text(contentLocation)
    }.toByteArray()

    private fun ByteArrayOutputStream.header(value: Int) = octet(value)

    private fun ByteArrayOutputStream.octet(value: Int) {
        write(value and 0xff)
    }

    private fun ByteArrayOutputStream.text(value: String) {
        write(value.ascii())
        write(0)
    }

    private fun ByteArrayOutputStream.valueLength(value: Int) {
        require(value in 0..30)
        write(value)
    }

    private fun ByteArrayOutputStream.longInteger(value: Long) {
        require(value >= 0L)
        var remaining = value
        val reversed = ByteArray(8)
        var count = 0
        do {
            reversed[count++] = (remaining and 0xff).toByte()
            remaining = remaining ushr 8
        } while (remaining != 0L)
        write(count)
        for (index in count - 1 downTo 0) write(reversed[index].toInt() and 0xff)
    }

    private fun encoded(bytes: ByteArray): EncodedMmsPdu =
        (EncodedMmsPdu.create(bytes) as EncodedMmsPdu.CreationResult.Valid).pdu

    private fun String.ascii(): ByteArray = toByteArray(StandardCharsets.US_ASCII)
    private fun String.utf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)

    companion object {
        private const val FIXED_DATE_SECONDS = 1_720_000_000L
        private const val TRANSACTION_ID = "Tsynthetic123"
        private const val CONTENT_LOCATION = "https://fixtures.example.invalid/mms/Tsynthetic123"
        private const val SENDER = "+15551230000"
        private const val RECIPIENT_ONE = "+15551230001"
        private const val RECIPIENT_TWO = "+15551230002"
        private const val SUBJECT = "Synthetic group subject"
        private const val TEXT = "Synthetic group body"
        private val IMAGE_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x00, 0x01)
    }
}
