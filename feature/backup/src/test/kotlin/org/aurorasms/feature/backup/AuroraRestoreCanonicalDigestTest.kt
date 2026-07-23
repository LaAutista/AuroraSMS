// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuroraRestoreCanonicalDigestTest {
    @Test
    fun smsOwnershipDigestExcludesOnlyArchiveIdentityAndHistoricalBox() {
        val inbox = sms(1, AuroraBackupMessageBox.INBOX)
        val sent = inbox.copy(archiveMessageId = 99, box = AuroraBackupMessageBox.SENT)
        assertEquals(
            AuroraRestoreCanonicalDigest.sms(inbox, includeHistoricalBox = false),
            AuroraRestoreCanonicalDigest.sms(sent, includeHistoricalBox = false),
        )
        assertNotEquals(
            AuroraRestoreCanonicalDigest.sms(inbox, includeHistoricalBox = true),
            AuroraRestoreCanonicalDigest.sms(sent, includeHistoricalBox = true),
        )
        assertNotEquals(
            AuroraRestoreCanonicalDigest.sms(inbox, includeHistoricalBox = false),
            AuroraRestoreCanonicalDigest.sms(inbox.copy(body = "changed"), includeHistoricalBox = false),
        )
    }

    @Test
    fun binaryPartIsStreamedOnceToItsDestinationAndBoundIntoTheMmsDigest() {
        val bytes = ByteArray(196_608) { index -> (index % 251).toByte() }
        val record = part()
        val destination = ByteArrayOutputStream()
        val firstPart = AuroraRestoreCanonicalDigest.mmsPart(
            record,
            AuroraBackupDecodedPartPayload.Binary(ByteArrayInputStream(bytes)),
            destination,
        )
        val secondPart = AuroraRestoreCanonicalDigest.mmsPart(
            record,
            AuroraBackupDecodedPartPayload.Binary(ByteArrayInputStream(bytes)),
        )
        assertTrue(bytes.contentEquals(destination.toByteArray()))
        assertEquals(firstPart, secondPart)

        val first = AuroraRestoreCanonicalDigest.beginMms(mms(1), includeHistoricalBox = false)
        val second = AuroraRestoreCanonicalDigest.beginMms(
            mms(9).copy(box = AuroraBackupMessageBox.SENT),
            includeHistoricalBox = false,
        )
        first.accept(firstPart)
        second.accept(secondPart)
        assertEquals(first.finish(), second.finish())
    }

    @Test
    fun payloadKindAndPartMetadataAreAuthenticated() {
        val record = part()
        val text = AuroraRestoreCanonicalDigest.mmsPart(
            record,
            AuroraBackupDecodedPartPayload.Text("same bytes"),
        )
        val binary = AuroraRestoreCanonicalDigest.mmsPart(
            record,
            AuroraBackupDecodedPartPayload.Binary(ByteArrayInputStream("same bytes".encodeToByteArray())),
        )
        val renamed = AuroraRestoreCanonicalDigest.mmsPart(
            record.copy(filename = "changed.bin"),
            AuroraBackupDecodedPartPayload.Text("same bytes"),
        )
        assertNotEquals(text, binary)
        assertNotEquals(text, renamed)
        assertEquals("AuroraRestoreMmsPartDigest(REDACTED)", text.toString())
    }

    private fun sms(id: Long, box: AuroraBackupMessageBox) = AuroraBackupSmsRecord(
        archiveMessageId = id,
        box = box,
        address = "+15550000000",
        body = "synthetic",
        timestampMillis = 1_700_000_000_000L,
        sentTimestampMillis = 1_699_999_999_000L,
        read = true,
        seen = true,
        locked = false,
        status = -1,
        errorCode = 0,
        protocol = 0,
        replyPathPresent = 0,
        subject = null,
        serviceCenter = "+15559999999",
        subscriptionId = 1,
    )

    private fun mms(id: Long) = AuroraBackupMmsRecord(
        archiveMessageId = id,
        box = AuroraBackupMessageBox.INBOX,
        timestampMillis = 1_700_000_000_000L,
        sentTimestampMillis = 1_699_999_999_000L,
        read = false,
        seen = true,
        locked = false,
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
        messageSizeBytes = 196_608L,
        expiryMillis = 86_400_000L,
        deliveryTimeMillis = 0L,
        subject = "subject",
        subjectCharset = 106,
        contentType = "application/vnd.wap.multipart.related",
        contentLocation = "location",
        messageClass = "personal",
        transactionId = "transaction",
        addresses = listOf(AuroraBackupMmsAddress(137, "+15550000000", 106)),
    )

    private fun part() = AuroraBackupDecodedMmsPart(
        parentArchiveMessageId = 1,
        sequence = 0,
        contentType = "application/octet-stream",
        charset = null,
        name = "payload.bin",
        contentDisposition = "attachment",
        filename = "payload.bin",
        contentId = "<payload>",
        contentLocation = "payload.bin",
    )
}
