// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.telephony.internal.RawAttachmentPart
import org.aurorasms.core.telephony.internal.projectStaticImageParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsAttachmentProjectionTest {
    @Test
    fun `projection admits only bounded static image MIME types`() {
        val rows = buildList {
            add(raw(1L, "text/plain", "body.txt"))
            add(raw(2L, "application/smil", "layout.smil"))
            add(raw(3L, "image/jpeg; charset=binary", "photo.jpg"))
            add(raw(4L, "image/png", "unsafe/path.png"))
            add(raw(5L, "video/mp4", "video.mp4"))
            add(raw(6L, "IMAGE/AVIF", "photo.avif"))
            add(raw(6L, "image/avif", "duplicate.avif"))
        }

        val result = projectStaticImageParts(MMS_MESSAGE_ID, rows)

        assertEquals(listOf(3L, 4L, 6L), result.items.map { it.id.providerPartId })
        assertEquals(listOf("image/jpeg", "image/png", "image/avif"), result.items.map { it.type.mimeType })
        assertEquals("photo.jpg", result.items[0].type.displayName)
        assertEquals(null, result.items[1].type.displayName)
        assertEquals("photo.avif", result.items[2].type.displayName)
        assertFalse(result.metadataTruncated)
    }

    @Test
    fun `projection caps retained metadata at 25 and reports overflow`() {
        val rows = (1L..30L).map { partId -> raw(partId, "image/webp", "image-$partId.webp") }

        val result = projectStaticImageParts(MMS_MESSAGE_ID, rows)

        assertEquals(MAXIMUM_VISIBLE_MMS_IMAGE_PARTS, result.items.size)
        assertTrue(result.metadataTruncated)
        assertEquals(1L, result.items.first().id.providerPartId)
        assertEquals(25L, result.items.last().id.providerPartId)
    }

    @Test
    fun `inspection overflow is explicit even when excess rows are unsupported`() {
        val rows = (1L..101L).map { partId -> raw(partId, "application/octet-stream", null) }

        val result = projectStaticImageParts(MMS_MESSAGE_ID, rows)

        assertTrue(result.items.isEmpty())
        assertTrue(result.metadataTruncated)
    }

    @Test
    fun `attachment identities and descriptors redact provider values and names`() {
        val descriptor = projectStaticImageParts(
            MMS_MESSAGE_ID,
            listOf(raw(99L, "image/heic", "private-name.heic")),
        ).items.single()

        assertEquals("MmsAttachmentId(REDACTED)", descriptor.id.toString())
        assertEquals("MmsAttachmentDescriptor(type=image/heic, REDACTED)", descriptor.toString())
    }
}

private val MMS_MESSAGE_ID = ProviderMessageId(ProviderKind.MMS, 7L)

private fun raw(
    partId: Long,
    contentType: String?,
    filename: String?,
): RawAttachmentPart = RawAttachmentPart(
    partId = partId,
    contentType = contentType,
    filename = filename,
    name = null,
    contentLocation = null,
)
