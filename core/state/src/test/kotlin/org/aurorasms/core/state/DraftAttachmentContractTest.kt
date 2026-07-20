// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftAttachmentContractTest {
    @Test
    fun admittedAttachmentDefensivelyCopiesContentAndRedactsDiagnostics() {
        val source = byteArrayOf(1, 2, 3)
        val attachment = validAttachment(DraftAttachment.IMAGE_JPEG, source)
        source[0] = 9

        val first = attachment.copyBytes()
        val second = attachment.copyBytes()
        first[1] = 8

        assertArrayEquals(byteArrayOf(1, 2, 3), second)
        assertNotSame(first, second)
        assertEquals(
            "DraftAttachment(contentType=image/jpeg, size=3, content=REDACTED)",
            attachment.toString(),
        )
    }

    @Test
    fun invalidTypeEmptyOversizeAndAggregateFailClosed() {
        assertTrue(
            DraftAttachment.create("image/gif", byteArrayOf(1)) is
                DraftAttachment.CreationResult.Rejected,
        )
        assertTrue(
            DraftAttachment.create(DraftAttachment.IMAGE_PNG, byteArrayOf()) is
                DraftAttachment.CreationResult.Rejected,
        )
        assertTrue(
            DraftAttachment.create(
                DraftAttachment.IMAGE_PNG,
                ByteArray(DraftAttachment.MAX_BYTES + 1),
            ) is DraftAttachment.CreationResult.Rejected,
        )
        val overAggregate = listOf(
            validAttachment(DraftAttachment.IMAGE_JPEG, ByteArray(500_000)),
            validAttachment(DraftAttachment.IMAGE_PNG, ByteArray(500_000)),
        )
        assertFalse(DraftAttachment.isValidSet(overAggregate))
    }

    private fun validAttachment(contentType: String, bytes: ByteArray): DraftAttachment =
        (DraftAttachment.create(contentType, bytes) as DraftAttachment.CreationResult.Valid).attachment
}
