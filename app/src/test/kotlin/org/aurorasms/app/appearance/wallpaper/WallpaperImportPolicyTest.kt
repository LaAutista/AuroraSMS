// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperImportPolicyTest {
    @Test
    fun sourcePolicyAcceptsOnlyStaticJpegAndPng() {
        assertEquals(
            WallpaperSourceFormat.JPEG,
            wallpaperSourceFormat(
                byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()),
            ),
        )
        assertNull(wallpaperSourceFormat(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())))
        assertEquals(WallpaperSourceFormat.PNG, wallpaperSourceFormat(staticPngEnvelope()))
        assertNull(wallpaperSourceFormat(animatedPngEnvelope()))
        assertNull(wallpaperSourceFormat("GIF89a".encodeToByteArray()))
        assertNull(wallpaperSourceFormat("RIFF0000WEBPVP8 ".encodeToByteArray()))
    }

    @Test
    fun declaredMimeMustNotContradictMagic() {
        assertTrue(wallpaperMimeMatches(null, WallpaperSourceFormat.JPEG))
        assertTrue(wallpaperMimeMatches("image/jpeg", WallpaperSourceFormat.JPEG))
        assertTrue(wallpaperMimeMatches("IMAGE/PNG; charset=binary", WallpaperSourceFormat.PNG))
        assertFalse(wallpaperMimeMatches("image/png", WallpaperSourceFormat.JPEG))
        assertFalse(wallpaperMimeMatches("application/octet-stream", WallpaperSourceFormat.PNG))
    }

    @Test
    fun derivativeIdentityIsStrictAndRedactedAtTheBoundary() {
        val bytes = staticWebpEnvelope()
        val mediaId = wallpaperMediaId(bytes)
        assertTrue(wallpaperDerivativeMatches(mediaId, bytes))
        assertEquals(
            "v1-${mediaId.removePrefix(WALLPAPER_MEDIA_ID_PREFIX)}.webp",
            wallpaperDerivativeFileName(mediaId),
        )
        assertNull(wallpaperDerivativeFileName("sha256-v1:ABC"))
        assertFalse(wallpaperDerivativeMatches(mediaId, bytes + 0x01))
    }

    @Test
    fun derivativeContainerRejectsAnimationAndMalformedRiff() {
        assertTrue(staticWebpEnvelope().isStaticWebp())
        assertFalse(
            webpEnvelope(
                webpChunk("VP8X", byteArrayOf(0x02) + ByteArray(9)),
                webpChunk("VP8 ", byteArrayOf(0x01)),
            ).isStaticWebp(),
        )
        assertFalse(
            webpEnvelope(webpChunk("ANIM", ByteArray(6)), webpChunk("VP8 ", byteArrayOf(0x01)))
                .isStaticWebp(),
        )
        assertFalse(
            webpEnvelope(webpChunk("ANMF", ByteArray(16)), webpChunk("VP8 ", byteArrayOf(0x01)))
                .isStaticWebp(),
        )
        assertFalse((staticWebpEnvelope() + 0x00).isStaticWebp())

        val oversizedChunk = staticWebpEnvelope().copyOf().apply {
            this[16] = 0xff.toByte()
            this[17] = 0xff.toByte()
            this[18] = 0xff.toByte()
            this[19] = 0x7f
        }
        assertFalse(oversizedChunk.isStaticWebp())
    }

    @Test
    fun boundedJpegExifReaderAcceptsOnlyAnInSegmentOrientationScalar() {
        (1..8).forEach { orientation ->
            assertEquals(orientation, jpegExifOrientation(jpegWithLittleEndianOrientation(orientation)))
        }
        assertEquals(8, jpegExifOrientation(jpegWithBigEndianOrientation(8)))
        assertEquals(1, jpegExifOrientation(jpegWithLittleEndianOrientation(0)))
        assertEquals(1, jpegExifOrientation(jpegWithLittleEndianOrientation(9)))
        assertEquals(1, jpegExifOrientation(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())))

        val escapingIfd = littleEndianTiff(6).apply {
            this[4] = 0xff.toByte()
            this[5] = 0xff.toByte()
            this[6] = 0xff.toByte()
            this[7] = 0x7f
        }
        assertEquals(1, jpegExifOrientation(jpegWithApp1(EXIF_PREFIX + escapingIfd)))

        val oversizedEntryCount = littleEndianTiff(6).apply {
            this[8] = 0xff.toByte()
            this[9] = 0x7f
        }
        assertEquals(1, jpegExifOrientation(jpegWithApp1(EXIF_PREFIX + oversizedEntryCount)))
    }

    @Test
    fun boundedPngExifReaderNormalizesTheLegacyPngPath() {
        val tiff = littleEndianTiff(3)
        val png = PNG_SIGNATURE + pngChunk("eXIf", tiff) + pngChunk("IEND")
        assertEquals(3, pngExifOrientation(png))
        assertEquals(1, pngExifOrientation(PNG_SIGNATURE + pngChunk("eXIf", byteArrayOf(0, 1))))
    }

    private fun staticPngEnvelope(): ByteArray =
        PNG_SIGNATURE + pngChunk("IHDR", minimalPngHeader()) +
            pngChunk("IDAT", byteArrayOf(0x78, 0x01)) + pngChunk("IEND")

    private fun animatedPngEnvelope(): ByteArray =
        PNG_SIGNATURE + pngChunk("IHDR", minimalPngHeader()) +
            pngChunk("acTL") + pngChunk("IDAT", byteArrayOf(0x78, 0x01)) + pngChunk("IEND")

    private fun staticWebpEnvelope(): ByteArray =
        webpEnvelope(webpChunk("VP8 ", byteArrayOf(0x01, 0x02)))

    private fun webpEnvelope(vararg chunks: ByteArray): ByteArray {
        val payload = "WEBP".encodeToByteArray() + chunks.fold(byteArrayOf()) { bytes, chunk ->
            bytes + chunk
        }
        return "RIFF".encodeToByteArray() + littleEndianInt(payload.size) + payload
    }

    private fun webpChunk(type: String, payload: ByteArray): ByteArray =
        type.encodeToByteArray() + littleEndianInt(payload.size) + payload +
            if (payload.size % 2 == 0) byteArrayOf() else byteArrayOf(0)

    private fun littleEndianInt(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )

    private fun minimalPngHeader(): ByteArray = byteArrayOf(
        0, 0, 0, 1,
        0, 0, 0, 1,
        8, 6, 0, 0, 0,
    )

    private fun pngChunk(type: String, payload: ByteArray = byteArrayOf()): ByteArray {
        val body = type.encodeToByteArray() + payload
        val crc = CRC32().apply { update(body) }.value
        return byteArrayOf(
            (payload.size ushr 24).toByte(),
            (payload.size ushr 16).toByte(),
            (payload.size ushr 8).toByte(),
            payload.size.toByte(),
        ) + body + byteArrayOf(
            (crc ushr 24).toByte(),
            (crc ushr 16).toByte(),
            (crc ushr 8).toByte(),
            crc.toByte(),
        )
    }

    private fun jpegWithLittleEndianOrientation(orientation: Int): ByteArray {
        val app1Payload = EXIF_PREFIX + littleEndianTiff(orientation)
        return jpegWithApp1(app1Payload)
    }

    private fun jpegWithBigEndianOrientation(orientation: Int): ByteArray {
        val tiff = byteArrayOf(
            'M'.code.toByte(), 'M'.code.toByte(),
            0x00, 0x2a,
            0x00, 0x00, 0x00, 0x08,
            0x00, 0x01,
            0x01, 0x12,
            0x00, 0x03,
            0x00, 0x00, 0x00, 0x01,
            0x00, orientation.toByte(), 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        return jpegWithApp1(EXIF_PREFIX + tiff)
    }

    private fun littleEndianTiff(orientation: Int): ByteArray = byteArrayOf(
        'I'.code.toByte(), 'I'.code.toByte(),
        0x2a, 0x00,
        0x08, 0x00, 0x00, 0x00,
        0x01, 0x00,
        0x12, 0x01,
        0x03, 0x00,
        0x01, 0x00, 0x00, 0x00,
        orientation.toByte(), 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
    )

    private fun jpegWithApp1(app1Payload: ByteArray): ByteArray {
        val segmentLength = app1Payload.size + 2
        return byteArrayOf(
            0xff.toByte(), 0xd8.toByte(),
            0xff.toByte(), 0xe1.toByte(),
            (segmentLength ushr 8).toByte(), segmentLength.toByte(),
        ) + app1Payload + byteArrayOf(0xff.toByte(), 0xd9.toByte())
    }
}

private val EXIF_PREFIX = byteArrayOf(
    'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
)

private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(),
    0x50,
    0x4e,
    0x47,
    0x0d,
    0x0a,
    0x1a,
    0x0a,
)
