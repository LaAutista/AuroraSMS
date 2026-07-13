// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticImagePolicyTest {
    @Test
    fun `header detection covers the complete static allowlist`() {
        assertEquals(EncodedImageFormat.JPEG, detectEncodedImageFormat(bytes(0xff, 0xd8, 0xff)))
        assertEquals(
            EncodedImageFormat.PNG,
            detectEncodedImageFormat(bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)),
        )
        assertEquals(EncodedImageFormat.GIF, detectEncodedImageFormat("GIF89a".encodeToByteArray()))
        assertEquals(
            EncodedImageFormat.WEBP,
            detectEncodedImageFormat("RIFF0000WEBP".encodeToByteArray()),
        )
        assertEquals(EncodedImageFormat.HEIF, detectEncodedImageFormat(ftyp("heic", "mif1")))
        assertEquals(EncodedImageFormat.AVIF, detectEncodedImageFormat(ftyp("avif", "mif1")))
    }

    @Test
    fun `truncated or unknown headers are rejected`() {
        assertNull(detectEncodedImageFormat(byteArrayOf()))
        assertNull(detectEncodedImageFormat("RIFF".encodeToByteArray()))
        assertNull(detectEncodedImageFormat(ftyp("mp42")))
        assertNull(detectEncodedImageFormat(byteArrayOf(0xff.toByte(), 0xd8.toByte())))
    }

    @Test
    fun `declared MIME must agree with decoded header`() {
        assertTrue(declaredMimeMatchesFormat("image/jpeg", EncodedImageFormat.JPEG))
        assertTrue(declaredMimeMatchesFormat("image/heic", EncodedImageFormat.HEIF))
        assertTrue(declaredMimeMatchesFormat("image/heif", EncodedImageFormat.HEIF))
        assertFalse(declaredMimeMatchesFormat("image/jpeg", EncodedImageFormat.PNG))
        assertFalse(declaredMimeMatchesFormat("image/heif", EncodedImageFormat.AVIF))
    }

    @Test
    fun `dimension policy rejects hostile sources and computes bounded targets`() {
        assertTrue(sourceDimensionsAreAllowed(8_000, 5_000))
        assertFalse(sourceDimensionsAreAllowed(8_001, 5_000))
        assertFalse(sourceDimensionsAreAllowed(8_193, 1))
        assertFalse(sourceDimensionsAreAllowed(0, 100))

        assertEquals(TargetDimensions(2_048, 1_280), targetDimensions(8_000, 5_000))
        assertEquals(TargetDimensions(2_048, 2_048), targetDimensions(4_096, 4_096))
        assertEquals(TargetDimensions(320, 200), targetDimensions(320, 200))
        assertEquals(4, powerOfTwoSampleSize(8_000, 5_000))
        assertEquals(2, powerOfTwoSampleSize(4_096, 4_096))
        assertEquals(1, powerOfTwoSampleSize(320, 200))
    }
}

private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { index -> values[index].toByte() }

private fun ftyp(majorBrand: String, vararg compatibleBrands: String): ByteArray {
    val size = 16 + compatibleBrands.size * 4
    return ByteArray(size).also { output ->
        output[0] = (size ushr 24).toByte()
        output[1] = (size ushr 16).toByte()
        output[2] = (size ushr 8).toByte()
        output[3] = size.toByte()
        "ftyp".encodeToByteArray().copyInto(output, destinationOffset = 4)
        majorBrand.encodeToByteArray().copyInto(output, destinationOffset = 8)
        compatibleBrands.forEachIndexed { index, brand ->
            brand.encodeToByteArray().copyInto(output, destinationOffset = 16 + index * 4)
        }
    }
}
