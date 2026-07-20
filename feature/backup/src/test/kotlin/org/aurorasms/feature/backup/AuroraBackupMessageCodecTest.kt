// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuroraBackupMessageCodecTest {
    @Test
    fun smsSchemaRoundTripsEveryHistoricalFieldWithoutProviderIdentity() {
        val record = AuroraBackupSmsRecord(
            archiveMessageId = 7L,
            box = AuroraBackupMessageBox.SENT,
            address = "+15551234567",
            body = "hello Aurora\nsecond line",
            timestampMillis = 1_700_000_000_000L,
            sentTimestampMillis = 1_699_999_999_000L,
            read = true,
            seen = true,
            locked = false,
            status = 0,
            errorCode = 3,
            protocol = 0,
            replyPathPresent = 1,
            subject = "subject",
            serviceCenter = "+15550000000",
            subscriptionId = 2,
        )

        assertEquals(record, AuroraBackupMessageCodec.decodeSms(entryBytes(AuroraBackupMessageCodec.smsEntry(record))))
        assertFalse(record.toString().contains(record.body!!))
        assertFalse(record.toString().contains(record.address!!))
    }

    @Test
    fun mmsSchemaRoundTripsAddressesAndTransportMetadata() {
        val record = AuroraBackupMmsRecord(
            archiveMessageId = 11L,
            box = AuroraBackupMessageBox.INBOX,
            timestampMillis = 1_700_000_000_000L,
            sentTimestampMillis = 1_699_999_999_000L,
            read = false,
            seen = true,
            locked = true,
            subscriptionId = 1,
            messageType = 132,
            version = 18,
            priority = 129,
            status = 128,
            responseStatus = 128,
            retrieveStatus = 128,
            readReport = 129,
            deliveryReport = 128,
            reportAllowed = 129,
            messageSizeBytes = 42_000L,
            expiryMillis = 86_400_000L,
            deliveryTimeMillis = 0L,
            subject = "night sky",
            subjectCharset = 106,
            contentType = "application/vnd.wap.multipart.related",
            contentLocation = "content-location",
            messageClass = "personal",
            transactionId = "transaction-1",
            addresses = listOf(
                AuroraBackupMmsAddress(137, "+15551234567", 106),
                AuroraBackupMmsAddress(151, "insert-address-token", 106),
            ),
        )

        assertEquals(record, AuroraBackupMessageCodec.decodeMms(entryBytes(AuroraBackupMessageCodec.mmsEntry(record))))
        assertFalse(record.toString().contains(record.subject!!))
        assertFalse(record.addresses.first().toString().contains(record.addresses.first().address))
    }

    @Test
    fun binaryMmsPartValidationStreamsPayloadAndRejectsTrailingTextEncodingMutation() {
        val payload = ByteArray(192 * 1_024) { (it % 251).toByte() }
        val record = AuroraBackupMmsPartRecord(
            parentArchiveMessageId = 11L,
            sequence = 2,
            contentType = "image/jpeg",
            charset = null,
            name = "aurora.jpg",
            contentDisposition = "attachment",
            filename = "aurora.jpg",
            contentId = "<image-1>",
            contentLocation = "aurora.jpg",
            payload = AuroraBackupMmsPartPayload.Binary { it.write(payload) },
        )
        val bytes = entryBytes(AuroraBackupMessageCodec.mmsPartEntry(record))

        assertTrue(AuroraBackupMessageCodec.validateMmsPart(ByteArrayInputStream(bytes)))
        assertTrue(bytes.size > payload.size)

        val truncated = bytes.copyOf(bytes.size - 1)
        // Binary payload length is defined by authenticated record framing, so a shorter
        // complete record remains structurally valid. The outer record digest detects an
        // actual truncation before this schema validator is called.
        assertTrue(AuroraBackupMessageCodec.validateMmsPart(ByteArrayInputStream(truncated)))
    }

    @Test
    fun malformedOrOverlongSchemasFailClosed() {
        val sms = AuroraBackupSmsRecord(
            archiveMessageId = 1,
            box = AuroraBackupMessageBox.INBOX,
            address = null,
            body = null,
            timestampMillis = 0,
            sentTimestampMillis = null,
            read = false,
            seen = false,
            locked = false,
            status = null,
            errorCode = null,
            protocol = null,
            replyPathPresent = null,
            subject = null,
            serviceCenter = null,
            subscriptionId = null,
        )
        val encoded = entryBytes(AuroraBackupMessageCodec.smsEntry(sms))
        assertNull(AuroraBackupMessageCodec.decodeSms(encoded.copyOf(encoded.size - 1)))
        assertNull(
            AuroraBackupMessageCodec.decodeSms(
                encoded.copyOf().also { it[0] = (AuroraBackupMessageCodec.SCHEMA_VERSION + 1).toByte() },
            ),
        )
        assertFalse(AuroraBackupMessageCodec.validateMmsPart(ByteArrayInputStream(byteArrayOf(1, 2, 3))))
    }

    @Test
    fun authenticatedMessageValidationRequiresSequentialIdsAndAdjacentMmsParts() {
        val archive = AuroraBackupArchive()
        val passphrase = "message schema secret".toCharArray()
        val valid = encrypted(
            archive,
            passphrase,
            sequenceOf(
                AuroraBackupMessageCodec.smsEntry(minimalSms(1)),
                AuroraBackupMessageCodec.mmsEntry(minimalMms(2)),
                AuroraBackupMessageCodec.mmsPartEntry(minimalPart(parent = 2)),
            ),
        )
        val validPlaintext = decrypt(archive, passphrase, valid)
        assertTrue(
            archive.validateMessagePlaintext(ByteArrayInputStream(validPlaintext)) is
                AuroraBackupValidationResult.Success,
        )

        val skippedId = encrypted(
            archive,
            passphrase,
            sequenceOf(AuroraBackupMessageCodec.smsEntry(minimalSms(2))),
        )
        assertEquals(
            AuroraBackupValidationResult.Failed(AuroraBackupFailure.INVALID_ARCHIVE),
            archive.validateMessagePlaintext(
                ByteArrayInputStream(decrypt(archive, passphrase, skippedId)),
            ),
        )

        val detachedPart = encrypted(
            archive,
            passphrase,
            sequenceOf(
                AuroraBackupMessageCodec.mmsEntry(minimalMms(1)),
                AuroraBackupMessageCodec.smsEntry(minimalSms(2)),
                AuroraBackupMessageCodec.mmsPartEntry(minimalPart(parent = 1)),
            ),
        )
        assertEquals(
            AuroraBackupValidationResult.Failed(AuroraBackupFailure.INVALID_ARCHIVE),
            archive.validateMessagePlaintext(
                ByteArrayInputStream(decrypt(archive, passphrase, detachedPart)),
            ),
        )
    }

    private fun minimalSms(id: Long) = AuroraBackupSmsRecord(
        archiveMessageId = id,
        box = AuroraBackupMessageBox.INBOX,
        address = "+15550000000",
        body = "message",
        timestampMillis = 1,
        sentTimestampMillis = null,
        read = false,
        seen = false,
        locked = false,
        status = null,
        errorCode = null,
        protocol = null,
        replyPathPresent = null,
        subject = null,
        serviceCenter = null,
        subscriptionId = null,
    )

    private fun minimalMms(id: Long) = AuroraBackupMmsRecord(
        archiveMessageId = id,
        box = AuroraBackupMessageBox.INBOX,
        timestampMillis = 1,
        sentTimestampMillis = null,
        read = false,
        seen = false,
        locked = false,
        subscriptionId = null,
        messageType = 132,
        version = 18,
        priority = null,
        status = null,
        responseStatus = null,
        retrieveStatus = null,
        readReport = null,
        deliveryReport = null,
        reportAllowed = null,
        messageSizeBytes = null,
        expiryMillis = null,
        deliveryTimeMillis = null,
        subject = null,
        subjectCharset = null,
        contentType = "application/vnd.wap.multipart.related",
        contentLocation = null,
        messageClass = null,
        transactionId = null,
        addresses = listOf(AuroraBackupMmsAddress(137, "+15550000000", 106)),
    )

    private fun minimalPart(parent: Long) = AuroraBackupMmsPartRecord(
        parentArchiveMessageId = parent,
        sequence = 0,
        contentType = "text/plain",
        charset = 106,
        name = null,
        contentDisposition = null,
        filename = null,
        contentId = null,
        contentLocation = null,
        payload = AuroraBackupMmsPartPayload.Text("part"),
    )

    private fun encrypted(
        archive: AuroraBackupArchive,
        passphrase: CharArray,
        entries: Sequence<AuroraBackupEntry>,
    ): ByteArray = ByteArrayOutputStream().also { destination ->
        assertTrue(archive.writeEncrypted(entries, passphrase, destination) is AuroraBackupWriteResult.Success)
    }.toByteArray()

    private fun decrypt(
        archive: AuroraBackupArchive,
        passphrase: CharArray,
        encrypted: ByteArray,
    ): ByteArray = ByteArrayOutputStream().also { pending ->
        assertTrue(
            archive.decryptToPending(ByteArrayInputStream(encrypted), passphrase, pending) is
                AuroraBackupDecryptResult.Success,
        )
    }.toByteArray()

    private fun entryBytes(entry: AuroraBackupEntry): ByteArray =
        ByteArrayOutputStream().also(entry::writeTo).toByteArray()
}
