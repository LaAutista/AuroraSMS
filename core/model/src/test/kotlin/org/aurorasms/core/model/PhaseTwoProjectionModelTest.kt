// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PhaseTwoProjectionModelTest {
    @Test
    fun `address subscription conversation and provider IDs render redacted`() {
        assertEquals("ParticipantAddress(REDACTED)", ParticipantAddress("+15550000042").toString())
        assertEquals("AuroraSubscriptionId(REDACTED)", AuroraSubscriptionId(2).toString())
        assertEquals("ConversationId(REDACTED)", ConversationId(42L).toString())
        assertEquals(
            "ProviderMessageId(kind=SMS, value=REDACTED)",
            ProviderMessageId(ProviderKind.SMS, 42L).toString(),
        )
    }

    @Test
    fun `provider thread IDs are positive and redacted`() {
        assertEquals(27L, ProviderThreadId(27L).value)
        assertEquals("ProviderThreadId(REDACTED)", ProviderThreadId(27L).toString())
        assertThrows(IllegalArgumentException::class.java) { ProviderThreadId(0L) }
        assertThrows(IllegalArgumentException::class.java) { ProviderThreadId(-1L) }
    }

    @Test
    fun `message box storage codes are explicit stable values`() {
        val expected = mapOf(
            MessageBox.INBOX to "inbox",
            MessageBox.SENT to "sent",
            MessageBox.DRAFT to "draft",
            MessageBox.OUTBOX to "outbox",
            MessageBox.FAILED to "failed",
            MessageBox.QUEUED to "queued",
            MessageBox.UNKNOWN to "unknown",
        )

        assertEquals(expected, MessageBox.entries.associateWith(MessageBox::toStorageCode))
        expected.forEach { (box, code) -> assertEquals(box, MessageBox.fromStorageCode(code)) }
        assertThrows(IllegalArgumentException::class.java) {
            MessageBox.fromStorageCode("0")
        }
    }

    @Test
    fun `message status storage codes are explicit stable values`() {
        val expected = mapOf(
            MessageStatus.NONE to "none",
            MessageStatus.PENDING to "pending",
            MessageStatus.COMPLETE to "complete",
            MessageStatus.FAILED to "failed",
            MessageStatus.UNKNOWN to "unknown",
        )

        assertEquals(expected, MessageStatus.entries.associateWith(MessageStatus::toStorageCode))
        expected.forEach { (status, code) ->
            assertEquals(status, MessageStatus.fromStorageCode(code))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MessageStatus.fromStorageCode("COMPLETE")
        }
    }

    @Test
    fun `sync fingerprint is immutable fixed format and redacted`() {
        val digest = ByteArray(MessageSyncFingerprint.SHA_256_BYTES) { (it + 1).toByte() }
        val fingerprint = MessageSyncFingerprint.fromSha256(digest)
        val storageToken = fingerprint.toStorageToken()
        digest.fill(0)

        assertEquals(64, storageToken.length)
        assertEquals(fingerprint, MessageSyncFingerprint.fromStorageToken(storageToken))
        assertNotEquals(
            fingerprint,
            MessageSyncFingerprint.fromSha256(ByteArray(MessageSyncFingerprint.SHA_256_BYTES)),
        )
        assertEquals("MessageSyncFingerprint(REDACTED)", fingerprint.toString())
        assertFalse(fingerprint.toString().contains(storageToken))
        assertThrows(IllegalArgumentException::class.java) {
            MessageSyncFingerprint.fromSha256(ByteArray(31))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MessageSyncFingerprint.fromStorageToken(storageToken.uppercase())
        }
    }

    @Test
    fun `attachment metadata is normalized bounded copied and content safe`() {
        val original = mutableListOf(
            MmsAttachmentType(" IMAGE/JPEG ", " portrait.jpg "),
            MmsAttachmentType("application/pdf"),
        )
        val summary = MmsAttachmentSummary(
            attachmentCount = 2,
            totalBytes = 4_096L,
            contentTypes = original,
            metadataTruncated = false,
        )
        original.clear()

        assertEquals(2, summary.contentTypes.size)
        assertEquals("image/jpeg", summary.contentTypes.first().mimeType)
        assertEquals("portrait.jpg", summary.contentTypes.first().displayName)
        assertFalse(summary.contentTypes.first().toString().contains("portrait.jpg"))
        assertFalse(summary.toString().contains("portrait.jpg"))
        assertEquals(
            summary,
            MmsAttachmentSummary(
                attachmentCount = 2,
                totalBytes = 4_096L,
                contentTypes = listOf(
                    MmsAttachmentType("image/jpeg", "portrait.jpg"),
                    MmsAttachmentType("application/pdf"),
                ),
                metadataTruncated = false,
            ),
        )
    }

    @Test
    fun `attachment metadata rejects unbounded and path-bearing values`() {
        assertThrows(IllegalArgumentException::class.java) {
            MmsAttachmentType("not-a-mime-type")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MmsAttachmentType("image/jpeg", "private/photos/picture.jpg")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MmsAttachmentSummary(
                attachmentCount = MmsAttachmentSummary.MAX_ATTACHMENT_COUNT + 1,
                totalBytes = 1L,
                contentTypes = emptyList(),
                metadataTruncated = true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MmsAttachmentSummary(
                attachmentCount = 0,
                totalBytes = -1L,
                contentTypes = emptyList(),
                metadataTruncated = false,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MmsAttachmentSummary(
                attachmentCount = 0,
                totalBytes = null,
                contentTypes = listOf(MmsAttachmentType("image/jpeg")),
                metadataTruncated = false,
            )
        }
    }
}
