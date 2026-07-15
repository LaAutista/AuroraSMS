// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegCompletenessPolicyTest {
    @Test
    fun completeSingleScanGrayscaleBaselineIsAccepted() {
        val encoded = minimalBaselineJpeg()

        assertTrue(encoded.isPotentiallySupportedBaselineJpeg())
        assertEquals(JpegCompleteness.COMPLETE, encoded.jpegCompleteness())
        assertEquals(WallpaperSourceFormat.JPEG, wallpaperSourceFormat(encoded))
    }

    @Test
    fun completeRestartCodedBaselineIsAccepted() {
        val encoded = minimalBaselineJpeg(
            width = 16,
            restartInterval = 1,
            entropy = byteArrayOf(0x3f, 0xff.toByte(), 0xd0.toByte(), 0x3f),
        )

        assertEquals(JpegCompleteness.COMPLETE, encoded.jpegCompleteness())
    }

    @Test
    fun unsupportedFrameProcessesAndPrecisionAreRejectedBeforeDecode() {
        listOf(0xc1, 0xc2, 0xc3, 0xc5, 0xc9).forEach { marker ->
            val encoded = minimalBaselineJpeg(frameMarker = marker)
            assertFalse("marker 0x${marker.toString(16)}", encoded.isPotentiallySupportedBaselineJpeg())
            assertNull("marker 0x${marker.toString(16)}", wallpaperSourceFormat(encoded))
        }

        val nonEightBit = minimalBaselineJpeg(precision = 12)
        assertFalse(nonEightBit.isPotentiallySupportedBaselineJpeg())
        assertNull(wallpaperSourceFormat(nonEightBit))
    }

    @Test
    fun forgedEndMarkerCannotHidePrematureEntropyTermination() {
        val encoded = minimalBaselineJpeg(width = 16, entropy = byteArrayOf(0x3f))

        assertTrue(encoded.isPotentiallySupportedBaselineJpeg())
        assertEquals(WallpaperSourceFormat.JPEG, wallpaperSourceFormat(encoded))
        assertEquals(JpegCompleteness.INVALID, encoded.jpegCompleteness())
    }

    @Test
    fun paddingExtraEntropyAndRestartCadenceAreExact() {
        val zeroPadding = minimalBaselineJpeg(entropy = byteArrayOf(0x00))
        val extraEntropy = minimalBaselineJpeg(entropy = byteArrayOf(0x3f, 0x00))
        val missingRestart = minimalBaselineJpeg(
            width = 16,
            restartInterval = 1,
            entropy = byteArrayOf(0x3f, 0x3f),
        )
        val wrongRestart = minimalBaselineJpeg(
            width = 16,
            restartInterval = 1,
            entropy = byteArrayOf(0x3f, 0xff.toByte(), 0xd1.toByte(), 0x3f),
        )

        listOf(zeroPadding, extraEntropy, missingRestart, wrongRestart).forEach { encoded ->
            assertEquals(JpegCompleteness.INVALID, encoded.jpegCompleteness())
        }
    }

    @Test
    fun malformedOrOversubscribedHuffmanTablesAreRejected() {
        val encoded = minimalBaselineJpeg().copyOf()
        val dht = encoded.indexOfMarker(0xc4)
        encoded[dht + 5] = 2
        val valid = minimalBaselineJpeg()
        val emptyQuantization = valid.insertBeforeMarker(0xda, segment(0xdb, byteArrayOf()))
        val emptyHuffman = valid.insertBeforeMarker(0xda, segment(0xc4, byteArrayOf()))
        val oversizedCanonicalHuffman = valid.insertBeforeMarker(
            0xc4,
            segment(0xc4, oversizedCanonicalHuffmanPayload()),
        )

        assertEquals(JpegCompleteness.INVALID, encoded.jpegCompleteness())
        assertEquals(JpegCompleteness.INVALID, emptyQuantization.jpegCompleteness())
        assertEquals(JpegCompleteness.INVALID, emptyHuffman.jpegCompleteness())
        assertEquals(JpegCompleteness.INVALID, oversizedCanonicalHuffman.jpegCompleteness())
    }

    @Test
    fun dimensionsOutsideTheReviewedPolicyRemainTyped() {
        val encoded = minimalBaselineJpeg(width = 8_193)

        assertTrue(encoded.isPotentiallySupportedBaselineJpeg())
        assertEquals(JpegCompleteness.SOURCE_DIMENSIONS_TOO_LARGE, encoded.jpegCompleteness())
    }

    private fun minimalBaselineJpeg(
        width: Int = 1,
        height: Int = 1,
        frameMarker: Int = 0xc0,
        precision: Int = 8,
        restartInterval: Int? = null,
        entropy: ByteArray = byteArrayOf(0x3f),
    ): ByteArray {
        val quantization = byteArrayOf(0) + ByteArray(64) { 1 }
        val oneBitCodeCounts = byteArrayOf(1) + ByteArray(15)
        val huffman = concat(
            byteArrayOf(0x00),
            oneBitCodeCounts,
            byteArrayOf(0x00),
            byteArrayOf(0x10),
            oneBitCodeCounts,
            byteArrayOf(0x00),
        )
        val frame = byteArrayOf(
            precision.toByte(),
            (height ushr 8).toByte(),
            height.toByte(),
            (width ushr 8).toByte(),
            width.toByte(),
            1,
            1,
            0x11,
            0,
        )
        val restart = restartInterval?.let {
            segment(0xdd, byteArrayOf((it ushr 8).toByte(), it.toByte()))
        } ?: byteArrayOf()
        val scan = byteArrayOf(1, 1, 0, 0, 63, 0)
        return concat(
            byteArrayOf(0xff.toByte(), 0xd8.toByte()),
            segment(0xdb, quantization),
            segment(frameMarker, frame),
            segment(0xc4, huffman),
            restart,
            segment(0xda, scan),
            entropy,
            byteArrayOf(0xff.toByte(), 0xd9.toByte()),
        )
    }

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return concat(
            byteArrayOf(
                0xff.toByte(),
                marker.toByte(),
                (length ushr 8).toByte(),
                length.toByte(),
            ),
            payload,
        )
    }

    private fun ByteArray.indexOfMarker(marker: Int): Int {
        for (index in 0 until lastIndex) {
            if (this[index].toInt() and 0xff == 0xff && this[index + 1].toInt() and 0xff == marker) {
                return index
            }
        }
        error("marker not found")
    }

    private fun ByteArray.insertBeforeMarker(marker: Int, insertion: ByteArray): ByteArray {
        val index = indexOfMarker(marker)
        return concat(copyOfRange(0, index), insertion, copyOfRange(index, size))
    }

    private fun oversizedCanonicalHuffmanPayload(): ByteArray {
        val counts = ByteArray(16).apply {
            this[14] = 2
            this[15] = 255.toByte()
        }
        return concat(byteArrayOf(0), counts, ByteArray(257))
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val result = ByteArray(parts.sumOf(ByteArray::size))
        var offset = 0
        parts.forEach { part ->
            part.copyInto(result, destinationOffset = offset)
            offset += part.size
        }
        return result
    }
}
