// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingGeneralMmsTest {
    @Test
    fun attachmentCopiesBytesAndRejectsUnsupportedOrOversizedInput() {
        val source = byteArrayOf(1, 2, 3)
        val attachment = validAttachment(OutgoingMmsAttachment.IMAGE_JPEG, source)
        source[0] = 9
        val copy = attachment.copyBytes()
        copy[1] = 8

        assertArrayEquals(byteArrayOf(1, 2, 3), attachment.copyBytes())
        assertFalse(attachment.toString().contains("1, 2, 3"))
        assertEquals(
            OutgoingMmsAttachment.CreationResult.Reason.UNSUPPORTED_CONTENT_TYPE,
            (
                OutgoingMmsAttachment.create("application/octet-stream", byteArrayOf(1)) as
                    OutgoingMmsAttachment.CreationResult.Rejected
                ).reason,
        )
        assertEquals(
            OutgoingMmsAttachment.CreationResult.Reason.TOO_LARGE,
            (
                OutgoingMmsAttachment.create(
                    OutgoingMmsAttachment.IMAGE_PNG,
                    ByteArray(OutgoingMmsAttachment.MAX_BYTES + 1),
                ) as OutgoingMmsAttachment.CreationResult.Rejected
                ).reason,
        )
    }

    @Test
    fun messageCopiesAttachmentListAndRedactsContent() {
        val mutable = mutableListOf(validAttachment(OutgoingMmsAttachment.IMAGE_PNG, byteArrayOf(4, 5)))
        val payload = OutgoingMmsPayload.Message(
            text = "Synthetic private body",
            subject = "Synthetic private subject",
            attachments = mutable,
        )
        mutable.clear()

        assertEquals(1, payload.attachments.size)
        assertFalse(payload.toString().contains("Synthetic private body"))
        assertFalse(payload.toString().contains("Synthetic private subject"))
        assertTrue(payload.toString().contains("attachmentCount=1"))
    }

    @Test
    fun messageRejectsEmptyContentAndAggregateOverflow() {
        assertThrows(IllegalArgumentException::class.java) {
            OutgoingMmsPayload.Message(null, null, emptyList())
        }
        val first = validAttachment(
            OutgoingMmsAttachment.IMAGE_JPEG,
            ByteArray(OutgoingMmsAttachment.MAX_BYTES),
        )
        val second = validAttachment(
            OutgoingMmsAttachment.IMAGE_PNG,
            ByteArray(OutgoingMmsPayload.Message.MAX_ATTACHMENT_BYTES_TOTAL.toInt() - first.size + 1),
        )
        assertThrows(IllegalArgumentException::class.java) {
            OutgoingMmsPayload.Message(null, null, listOf(first, second))
        }
    }

    private fun validAttachment(contentType: String, bytes: ByteArray): OutgoingMmsAttachment =
        (
            OutgoingMmsAttachment.create(contentType, bytes) as
                OutgoingMmsAttachment.CreationResult.Valid
            ).attachment
}
