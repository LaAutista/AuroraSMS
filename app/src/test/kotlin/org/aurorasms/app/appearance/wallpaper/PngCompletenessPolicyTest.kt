// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class PngCompletenessPolicyTest {
    @Test
    fun completeNonInterlacedAndAdam7StreamsAreAccepted() {
        val row = byteArrayOf(0, 0x10, 0x20, 0x30, 0xff.toByte())
        val nonInterlaced = png(width = 1, height = 1, rawRows = row)
        val adam7 = png(
            width = 5,
            height = 5,
            interlace = 1,
            rawRows = adam7Rows(width = 5, height = 5, bitsPerPixel = 32),
        )

        listOf(nonInterlaced, adam7).forEach { encoded ->
            assertEquals(WallpaperSourceFormat.PNG, wallpaperSourceFormat(encoded))
            assertEquals(PngCompleteness.COMPLETE, encoded.pngCompleteness())
        }
    }

    @Test
    fun splitIdatAndPackedGrayscaleRowsAreCountedExactly() {
        val grayscale = png(
            width = 9,
            height = 1,
            bitDepth = 1,
            colorType = 0,
            rawRows = byteArrayOf(0, 0x55, 0x00),
            splitImageData = true,
        )
        val rgba16 = png(
            width = 1,
            height = 1,
            bitDepth = 16,
            rawRows = ByteArray(9),
        )
        val indexed = png(
            width = 2,
            height = 1,
            bitDepth = 1,
            colorType = 3,
            rawRows = byteArrayOf(0, 0x40),
            beforeImageData = chunk("PLTE", byteArrayOf(0, 0, 0, -1, -1, -1)),
        )

        listOf(grayscale, rgba16, indexed).forEach { encoded ->
            assertEquals(PngCompleteness.COMPLETE, encoded.pngCompleteness())
        }
        val palette = chunk("PLTE", byteArrayOf(0, 0, 0, -1, -1, -1))
        listOf(
            png(2, 1, 8, 3, rawRows = byteArrayOf(1, 0, 1), beforeImageData = palette),
            png(
                2,
                2,
                8,
                3,
                rawRows = byteArrayOf(0, 0, 1, 2, 1, -1),
                beforeImageData = palette,
            ),
            png(2, 1, 8, 3, rawRows = byteArrayOf(3, 0, 1), beforeImageData = palette),
            png(
                2,
                2,
                8,
                3,
                rawRows = byteArrayOf(0, 0, 1, 4, 1, -1),
                beforeImageData = palette,
            ),
        ).forEach { filteredIndexed ->
            assertEquals(PngCompleteness.COMPLETE, filteredIndexed.pngCompleteness())
        }

        val compressed = deflate(byteArrayOf(0, 0x10, 0x20, 0x30, 0xff.toByte()))
        (1 until compressed.size).forEach { splitAt ->
            val split = pngWithCompressedImageData(
                width = 1,
                height = 1,
                compressed = compressed,
                splitAt = splitAt,
            )
            assertEquals("split at $splitAt", PngCompleteness.COMPLETE, split.pngCompleteness())
        }
    }

    @Test
    fun corruptTruncatedOrOverlongZlibStreamsAreRejected() {
        val row = byteArrayOf(0, 0x10, 0x20, 0x30, 0xff.toByte())
        val compressed = deflate(row)
        val truncated = pngWithCompressedImageData(
            width = 1,
            height = 1,
            compressed = compressed.copyOf(compressed.size - 2),
        )
        val trailing = pngWithCompressedImageData(
            width = 1,
            height = 1,
            compressed = compressed + byteArrayOf(0),
        )
        val extraRow = png(width = 1, height = 1, rawRows = row + row)

        listOf(truncated, trailing, extraRow).forEach { encoded ->
            assertEquals(WallpaperSourceFormat.PNG, wallpaperSourceFormat(encoded))
            assertEquals(PngCompleteness.INVALID, encoded.pngCompleteness())
        }
    }

    @Test
    fun invalidFilterCriticalAndMalformedChunkTypesAreRejected() {
        val invalidFilter = png(
            width = 1,
            height = 1,
            rawRows = byteArrayOf(5, 0, 0, 0, 0),
        )
        val unknownCritical = png(
            width = 1,
            height = 1,
            rawRows = byteArrayOf(0, 0, 0, 0, 0),
            beforeImageData = chunk("ABCD", byteArrayOf()),
        )
        val lowercaseReservedBit = png(
            width = 1,
            height = 1,
            rawRows = byteArrayOf(0, 0, 0, 0, 0),
            beforeImageData = chunk("abca", byteArrayOf()),
        )
        val nonLetterType = png(
            width = 1,
            height = 1,
            rawRows = byteArrayOf(0, 0, 0, 0, 0),
            beforeImageData = chunk("a1CD", byteArrayOf()),
        )
        val outOfPaletteIndex = png(
            width = 1,
            height = 1,
            bitDepth = 1,
            colorType = 3,
            rawRows = byteArrayOf(0, 0x80.toByte()),
            beforeImageData = chunk("PLTE", byteArrayOf(0, 0, 0)),
        )
        val filteredOutOfPaletteIndex = png(
            width = 2,
            height = 1,
            bitDepth = 8,
            colorType = 3,
            rawRows = byteArrayOf(1, 0, 2),
            beforeImageData = chunk("PLTE", byteArrayOf(0, 0, 0, -1, -1, -1)),
        )
        val oversizedPalette = png(
            width = 1,
            height = 1,
            bitDepth = 1,
            colorType = 3,
            rawRows = byteArrayOf(0, 0),
            beforeImageData = chunk("PLTE", ByteArray(9)),
        )
        val tooManyChunks = png(
            width = 1,
            height = 1,
            rawRows = byteArrayOf(0, 0, 0, 0, 0),
            beforeImageData = repeatedChunks("ruSt", 4_097),
        )
        val compressedAncillary = listOf("iCCP", "zTXt", "iTXt").map { type ->
            png(
                width = 1,
                height = 1,
                rawRows = byteArrayOf(0, 0, 0, 0, 0),
                beforeImageData = chunk(type, byteArrayOf()),
            )
        }

        listOf(
            invalidFilter,
            unknownCritical,
            lowercaseReservedBit,
            nonLetterType,
            outOfPaletteIndex,
            filteredOutOfPaletteIndex,
            oversizedPalette,
            tooManyChunks,
        ).forEach { encoded ->
            assertEquals(PngCompleteness.INVALID, encoded.pngCompleteness())
        }
        compressedAncillary.forEach { encoded ->
            assertEquals(PngCompleteness.INVALID, encoded.pngCompleteness())
            assertEquals(null, wallpaperSourceFormat(encoded))
        }
        assertEquals(null, wallpaperSourceFormat(lowercaseReservedBit))
        assertEquals(null, wallpaperSourceFormat(nonLetterType))
        assertEquals(null, wallpaperSourceFormat(tooManyChunks))
    }

    @Test
    fun dimensionsOutsideTheReviewedPolicyRemainTypedWithoutInflation() {
        val encoded = pngWithCompressedImageData(
            width = 8_193,
            height = 1,
            compressed = byteArrayOf(0x78, 0x01, 0, 0, 0, 0),
        )

        assertEquals(WallpaperSourceFormat.PNG, wallpaperSourceFormat(encoded))
        assertEquals(PngCompleteness.SOURCE_DIMENSIONS_TOO_LARGE, encoded.pngCompleteness())
    }

    private fun png(
        width: Int,
        height: Int,
        bitDepth: Int = 8,
        colorType: Int = 6,
        interlace: Int = 0,
        rawRows: ByteArray,
        splitImageData: Boolean = false,
        beforeImageData: ByteArray = byteArrayOf(),
    ): ByteArray {
        val compressed = deflate(rawRows)
        return pngWithCompressedImageData(
            width = width,
            height = height,
            bitDepth = bitDepth,
            colorType = colorType,
            interlace = interlace,
            compressed = compressed,
            splitAt = if (splitImageData) compressed.size / 2 else null,
            beforeImageData = beforeImageData,
        )
    }

    private fun pngWithCompressedImageData(
        width: Int,
        height: Int,
        bitDepth: Int = 8,
        colorType: Int = 6,
        interlace: Int = 0,
        compressed: ByteArray,
        splitAt: Int? = null,
        beforeImageData: ByteArray = byteArrayOf(),
    ): ByteArray {
        val header = concat(
            bigEndianInt(width),
            bigEndianInt(height),
            byteArrayOf(bitDepth.toByte(), colorType.toByte(), 0, 0, interlace.toByte()),
        )
        val imageData = if (splitAt != null && splitAt in 1 until compressed.size) {
            concat(
                chunk("IDAT", compressed.copyOfRange(0, splitAt)),
                chunk("IDAT", compressed.copyOfRange(splitAt, compressed.size)),
            )
        } else {
            chunk("IDAT", compressed)
        }
        return concat(
            PNG_SIGNATURE,
            chunk("IHDR", header),
            beforeImageData,
            imageData,
            chunk("IEND", byteArrayOf()),
        )
    }

    private fun deflate(source: ByteArray): ByteArray = ByteArrayOutputStream().use { bytes ->
        DeflaterOutputStream(bytes).use { it.write(source) }
        bytes.toByteArray()
    }

    private fun adam7Rows(width: Int, height: Int, bitsPerPixel: Int): ByteArray {
        val output = ByteArrayOutputStream()
        ADAM7_PASSES.forEach { pass ->
            val passWidth = pass.extent(width, horizontal = true)
            val passHeight = pass.extent(height, horizontal = false)
            if (passWidth > 0 && passHeight > 0) {
                val rowBytes = 1 + ((passWidth * bitsPerPixel + 7) / 8)
                repeat(passHeight) { output.write(ByteArray(rowBytes)) }
            }
        }
        return output.toByteArray()
    }

    private fun chunk(type: String, payload: ByteArray): ByteArray {
        val typeBytes = type.encodeToByteArray()
        val crc = CRC32().apply {
            update(typeBytes)
            update(payload)
        }
        return concat(
            bigEndianInt(payload.size),
            typeBytes,
            payload,
            bigEndianInt(crc.value.toInt()),
        )
    }

    private fun repeatedChunks(type: String, count: Int): ByteArray =
        ByteArrayOutputStream().use { output ->
            val encoded = chunk(type, byteArrayOf())
            repeat(count) { output.write(encoded) }
            output.toByteArray()
        }

    private fun bigEndianInt(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun concat(vararg parts: ByteArray): ByteArray {
        val result = ByteArray(parts.sumOf(ByteArray::size))
        var offset = 0
        parts.forEach { part ->
            part.copyInto(result, destinationOffset = offset)
            offset += part.size
        }
        return result
    }

    private companion object {
        val PNG_SIGNATURE: ByteArray = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4e,
            0x47,
            0x0d,
            0x0a,
            0x1a,
            0x0a,
        )
        val ADAM7_PASSES: List<TestPass> = listOf(
            TestPass(0, 0, 8, 8),
            TestPass(4, 0, 8, 8),
            TestPass(0, 4, 4, 8),
            TestPass(2, 0, 4, 4),
            TestPass(0, 2, 2, 4),
            TestPass(1, 0, 2, 2),
            TestPass(0, 1, 1, 2),
        )
    }

    private data class TestPass(
        val xStart: Int,
        val yStart: Int,
        val xStep: Int,
        val yStep: Int,
    ) {
        fun extent(fullExtent: Int, horizontal: Boolean): Int {
            val start = if (horizontal) xStart else yStart
            val step = if (horizontal) xStep else yStep
            return if (fullExtent <= start) 0 else (fullExtent - start + step - 1) / step
        }
    }
}
