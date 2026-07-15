// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import java.util.zip.DataFormatException
import java.util.zip.Inflater
import org.aurorasms.app.preview.sourceDimensionsAreAllowed

internal enum class PngCompleteness {
    COMPLETE,
    SOURCE_DIMENSIONS_TOO_LARGE,
    INVALID,
}

internal fun ByteArray.pngCompleteness(): PngCompleteness {
    val parsed = parseStaticPngForCompleteness() ?: return PngCompleteness.INVALID
    if (!sourceDimensionsAreAllowed(parsed.width, parsed.height)) {
        return PngCompleteness.SOURCE_DIMENSIONS_TOO_LARGE
    }
    return if (inflatePngRows(parsed)) PngCompleteness.COMPLETE else PngCompleteness.INVALID
}

private fun ByteArray.parseStaticPngForCompleteness(): ParsedPng? {
    if (!startsWithPngSignature()) return null
    var offset = PNG_SIGNATURE_BYTES
    var header: PngHeader? = null
    var sawPalette = false
    var paletteEntries = 0
    var sawImageData = false
    var imageDataEnded = false
    var chunkCount = 0
    val imageData = ArrayList<PngDataRange>()

    while (offset <= size - PNG_CHUNK_OVERHEAD_BYTES) {
        if (++chunkCount > MAXIMUM_PNG_CHUNKS) return null
        val payloadBytes = readUnsignedInt(offset) ?: return null
        if (payloadBytes > Int.MAX_VALUE.toLong()) return null
        val typeOffset = offset + PNG_LENGTH_BYTES
        val payloadStart = typeOffset + PNG_TYPE_BYTES
        val payloadEndLong = payloadStart.toLong() + payloadBytes
        val chunkEndLong = payloadEndLong + PNG_CRC_BYTES
        if (payloadEndLong > Int.MAX_VALUE || chunkEndLong > size.toLong()) return null
        val payloadEnd = payloadEndLong.toInt()
        val chunkEnd = chunkEndLong.toInt()
        if (!hasValidPngChunkType(typeOffset)) return null
        val type = asciiType(typeOffset)

        when (type) {
            "IHDR" -> {
                if (header != null || offset != PNG_SIGNATURE_BYTES || payloadBytes != PNG_IHDR_BYTES) {
                    return null
                }
                header = parsePngHeader(payloadStart) ?: return null
            }
            "PLTE" -> {
                val currentHeader = header ?: return null
                if (
                    sawPalette ||
                    sawImageData ||
                    currentHeader.colorType == PNG_COLOR_GRAYSCALE ||
                    currentHeader.colorType == PNG_COLOR_GRAYSCALE_ALPHA ||
                    payloadBytes !in PNG_MINIMUM_PALETTE_BYTES..PNG_MAXIMUM_PALETTE_BYTES ||
                    payloadBytes % PNG_PALETTE_ENTRY_BYTES != 0L ||
                    (
                        currentHeader.colorType == PNG_COLOR_INDEXED &&
                            payloadBytes / PNG_PALETTE_ENTRY_BYTES >
                            (1 shl currentHeader.bitDepth).toLong()
                    )
                ) {
                    return null
                }
                sawPalette = true
                paletteEntries = (payloadBytes / PNG_PALETTE_ENTRY_BYTES).toInt()
            }
            "IDAT" -> {
                val currentHeader = header ?: return null
                if (imageDataEnded) return null
                if (currentHeader.colorType == PNG_COLOR_INDEXED && !sawPalette) return null
                sawImageData = true
                if (payloadBytes > 0L) {
                    imageData += PngDataRange(payloadStart, payloadBytes.toInt())
                }
            }
            "IEND" -> {
                val currentHeader = header ?: return null
                if (
                    payloadBytes != 0L ||
                    !sawImageData ||
                    chunkEnd != size ||
                    imageData.isEmpty()
                ) {
                    return null
                }
                return ParsedPng(
                    width = currentHeader.width,
                    height = currentHeader.height,
                    bitsPerPixel = currentHeader.bitsPerPixel,
                    bitDepth = currentHeader.bitDepth,
                    colorType = currentHeader.colorType,
                    paletteEntries = paletteEntries,
                    interlaced = currentHeader.interlaced,
                    imageData = imageData,
                )
            }
            "acTL", "fcTL", "fdAT", "iCCP", "zTXt", "iTXt" -> return null
            else -> {
                if (type.isEmpty() || type[0] in 'A'..'Z') return null
                if (sawImageData) imageDataEnded = true
            }
        }
        if (type != "IDAT" && sawImageData) imageDataEnded = true
        offset = chunkEnd
    }
    return null
}

private fun ByteArray.parsePngHeader(payloadStart: Int): PngHeader? {
    val width = readUnsignedInt(payloadStart) ?: return null
    val height = readUnsignedInt(payloadStart + 4) ?: return null
    if (width !in 1..Int.MAX_VALUE.toLong() || height !in 1..Int.MAX_VALUE.toLong()) return null
    val bitDepth = this[payloadStart + 8].unsigned()
    val colorType = this[payloadStart + 9].unsigned()
    val compression = this[payloadStart + 10].unsigned()
    val filter = this[payloadStart + 11].unsigned()
    val interlace = this[payloadStart + 12].unsigned()
    val channels = when (colorType) {
        PNG_COLOR_GRAYSCALE -> if (bitDepth in PNG_GRAYSCALE_BIT_DEPTHS) 1 else return null
        PNG_COLOR_TRUECOLOR -> if (bitDepth in PNG_TRUECOLOR_BIT_DEPTHS) 3 else return null
        PNG_COLOR_INDEXED -> if (bitDepth in PNG_INDEXED_BIT_DEPTHS) 1 else return null
        PNG_COLOR_GRAYSCALE_ALPHA -> if (bitDepth in PNG_ALPHA_BIT_DEPTHS) 2 else return null
        PNG_COLOR_TRUECOLOR_ALPHA -> if (bitDepth in PNG_ALPHA_BIT_DEPTHS) 4 else return null
        else -> return null
    }
    if (
        compression != PNG_STANDARD_COMPRESSION ||
        filter != PNG_STANDARD_FILTER ||
        interlace !in PNG_INTERLACE_NONE..PNG_INTERLACE_ADAM7
    ) {
        return null
    }
    return PngHeader(
        width = width.toInt(),
        height = height.toInt(),
        colorType = colorType,
        bitDepth = bitDepth,
        bitsPerPixel = bitDepth * channels,
        interlaced = interlace == PNG_INTERLACE_ADAM7,
    )
}

private fun ByteArray.inflatePngRows(parsed: ParsedPng): Boolean {
    val passes = if (parsed.interlaced) PNG_ADAM7_PASSES else listOf(PngPass(0, 0, 1, 1))
    val maximumRowBytes = passes.maxOf { pass ->
        val passWidth = pass.extent(parsed.width, horizontal = true)
        PNG_FILTER_BYTES + packedBytes(passWidth, parsed.bitsPerPixel)
    }
    if (maximumRowBytes <= PNG_FILTER_BYTES) return false
    val row = ByteArray(maximumRowBytes)
    var previousIndexedRow = ByteArray(maximumRowBytes - PNG_FILTER_BYTES)
    var currentIndexedRow = ByteArray(maximumRowBytes - PNG_FILTER_BYTES)
    val compressedBytes = ByteArray(parsed.imageData.sumOf(PngDataRange::byteCount))
    var compressedOffset = 0
    parsed.imageData.forEach { range ->
        copyInto(
            destination = compressedBytes,
            destinationOffset = compressedOffset,
            startIndex = range.offset,
            endIndex = range.offset + range.byteCount,
        )
        compressedOffset += range.byteCount
    }
    val inflater = Inflater().apply { setInput(compressedBytes) }
    return try {
        passes.forEach { pass ->
            previousIndexedRow.fill(0)
            val passWidth = pass.extent(parsed.width, horizontal = true)
            val passHeight = pass.extent(parsed.height, horizontal = false)
            if (passWidth > 0 && passHeight > 0) {
                val rowBytes = PNG_FILTER_BYTES + packedBytes(passWidth, parsed.bitsPerPixel)
                repeat(passHeight) {
                    if (!inflater.inflateExactly(row, rowBytes)) return false
                    val filter = row[0].unsigned()
                    if (filter !in PNG_FILTER_NONE..PNG_FILTER_PAETH) return false
                    if (parsed.colorType == PNG_COLOR_INDEXED) {
                        val dataBytes = rowBytes - PNG_FILTER_BYTES
                        unfilterIndexedRow(
                            encoded = row,
                            dataBytes = dataBytes,
                            filter = filter,
                            previous = previousIndexedRow,
                            destination = currentIndexedRow,
                        )
                        if (
                            !indexedPixelsUsePalette(
                                row = currentIndexedRow,
                                width = passWidth,
                                bitDepth = parsed.bitDepth,
                                paletteEntries = parsed.paletteEntries,
                            )
                        ) {
                            return false
                        }
                        val swap = previousIndexedRow
                        previousIndexedRow = currentIndexedRow
                        currentIndexedRow = swap
                    }
                }
            }
        }
        if (!inflater.finishWithoutOutput()) return false
        inflater.remaining == 0
    } catch (_: DataFormatException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    } finally {
        inflater.end()
    }
}

private fun unfilterIndexedRow(
    encoded: ByteArray,
    dataBytes: Int,
    filter: Int,
    previous: ByteArray,
    destination: ByteArray,
) {
    repeat(dataBytes) { index ->
        val raw = encoded[index + PNG_FILTER_BYTES].unsigned()
        val left = if (index > 0) destination[index - 1].unsigned() else 0
        val above = previous[index].unsigned()
        val upperLeft = if (index > 0) previous[index - 1].unsigned() else 0
        val predictor = when (filter) {
            PNG_FILTER_NONE -> 0
            PNG_FILTER_SUB -> left
            PNG_FILTER_UP -> above
            PNG_FILTER_AVERAGE -> (left + above) / 2
            PNG_FILTER_PAETH -> paethPredictor(left, above, upperLeft)
            else -> error("validated PNG filter changed")
        }
        destination[index] = (raw + predictor).toByte()
    }
}

private fun indexedPixelsUsePalette(
    row: ByteArray,
    width: Int,
    bitDepth: Int,
    paletteEntries: Int,
): Boolean {
    if (paletteEntries <= 0) return false
    val mask = (1 shl bitDepth) - 1
    repeat(width) { pixel ->
        val bitOffset = pixel * bitDepth
        val byte = row[bitOffset / Byte.SIZE_BITS].unsigned()
        val shift = Byte.SIZE_BITS - bitDepth - bitOffset % Byte.SIZE_BITS
        if ((byte ushr shift) and mask >= paletteEntries) return false
    }
    return true
}

private fun paethPredictor(left: Int, above: Int, upperLeft: Int): Int {
    val estimate = left + above - upperLeft
    val leftDistance = kotlin.math.abs(estimate - left)
    val aboveDistance = kotlin.math.abs(estimate - above)
    val upperLeftDistance = kotlin.math.abs(estimate - upperLeft)
    return when {
        leftDistance <= aboveDistance && leftDistance <= upperLeftDistance -> left
        aboveDistance <= upperLeftDistance -> above
        else -> upperLeft
    }
}

@Throws(DataFormatException::class)
private fun Inflater.inflateExactly(
    destination: ByteArray,
    byteCount: Int,
): Boolean {
    var written = 0
    while (written < byteCount) {
        val count = inflate(destination, written, byteCount - written)
        if (count > 0) {
            written += count
            continue
        }
        if (finished() || needsDictionary()) return false
        return false
    }
    return true
}

@Throws(DataFormatException::class)
private fun Inflater.finishWithoutOutput(): Boolean {
    val extra = ByteArray(1)
    while (!finished()) {
        val count = inflate(extra)
        if (count > 0 || needsDictionary()) return false
        return false
    }
    return true
}

private data class ParsedPng(
    val width: Int,
    val height: Int,
    val bitsPerPixel: Int,
    val bitDepth: Int,
    val colorType: Int,
    val paletteEntries: Int,
    val interlaced: Boolean,
    val imageData: List<PngDataRange>,
)

private data class PngHeader(
    val width: Int,
    val height: Int,
    val colorType: Int,
    val bitDepth: Int,
    val bitsPerPixel: Int,
    val interlaced: Boolean,
)

private data class PngDataRange(
    val offset: Int,
    val byteCount: Int,
)

private data class PngPass(
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

private fun packedBytes(width: Int, bitsPerPixel: Int): Int =
    ((width.toLong() * bitsPerPixel + Byte.SIZE_BITS - 1L) / Byte.SIZE_BITS).toInt()

private fun ByteArray.startsWithPngSignature(): Boolean =
    size >= PNG_SIGNATURE_BYTES && PNG_SIGNATURE.indices.all { index ->
        this[index].unsigned() == PNG_SIGNATURE[index]
    }

private fun ByteArray.readUnsignedInt(offset: Int): Long? {
    if (offset < 0 || offset > size - 4) return null
    return ((this[offset].unsigned().toLong()) shl 24) or
        ((this[offset + 1].unsigned().toLong()) shl 16) or
        ((this[offset + 2].unsigned().toLong()) shl 8) or
        this[offset + 3].unsigned().toLong()
}

private fun ByteArray.asciiType(offset: Int): String {
    if (offset < 0 || offset > size - PNG_TYPE_BYTES) return ""
    return buildString(PNG_TYPE_BYTES) {
        repeat(PNG_TYPE_BYTES) { append(this@asciiType[offset + it].unsigned().toChar()) }
    }
}

private fun ByteArray.hasValidPngChunkType(offset: Int): Boolean {
    if (offset < 0 || offset > size - PNG_TYPE_BYTES) return false
    repeat(PNG_TYPE_BYTES) { index ->
        val value = this[offset + index].unsigned()
        if (value !in 'A'.code..'Z'.code && value !in 'a'.code..'z'.code) return false
    }
    return this[offset + PNG_RESERVED_TYPE_INDEX].unsigned() in 'A'.code..'Z'.code
}

private fun Byte.unsigned(): Int = toInt() and 0xff

private const val PNG_SIGNATURE_BYTES: Int = 8
private const val PNG_LENGTH_BYTES: Int = 4
private const val PNG_TYPE_BYTES: Int = 4
private const val PNG_CRC_BYTES: Int = 4
private const val PNG_CHUNK_OVERHEAD_BYTES: Int = PNG_LENGTH_BYTES + PNG_TYPE_BYTES + PNG_CRC_BYTES
private const val PNG_IHDR_BYTES: Long = 13L
private const val PNG_FILTER_BYTES: Int = 1
private const val PNG_MINIMUM_PALETTE_BYTES: Long = 3L
private const val PNG_MAXIMUM_PALETTE_BYTES: Long = 768L
private const val PNG_PALETTE_ENTRY_BYTES: Long = 3L
private const val PNG_COLOR_GRAYSCALE: Int = 0
private const val PNG_COLOR_TRUECOLOR: Int = 2
private const val PNG_COLOR_INDEXED: Int = 3
private const val PNG_COLOR_GRAYSCALE_ALPHA: Int = 4
private const val PNG_COLOR_TRUECOLOR_ALPHA: Int = 6
private const val PNG_STANDARD_COMPRESSION: Int = 0
private const val PNG_STANDARD_FILTER: Int = 0
private const val PNG_INTERLACE_NONE: Int = 0
private const val PNG_INTERLACE_ADAM7: Int = 1
private const val PNG_FILTER_NONE: Int = 0
private const val PNG_FILTER_SUB: Int = 1
private const val PNG_FILTER_UP: Int = 2
private const val PNG_FILTER_AVERAGE: Int = 3
private const val PNG_FILTER_PAETH: Int = 4
private const val PNG_RESERVED_TYPE_INDEX: Int = 2
private const val MAXIMUM_PNG_CHUNKS: Int = 4_096
private val PNG_GRAYSCALE_BIT_DEPTHS: Set<Int> = setOf(1, 2, 4, 8, 16)
private val PNG_TRUECOLOR_BIT_DEPTHS: Set<Int> = setOf(8, 16)
private val PNG_INDEXED_BIT_DEPTHS: Set<Int> = setOf(1, 2, 4, 8)
private val PNG_ALPHA_BIT_DEPTHS: Set<Int> = setOf(8, 16)
private val PNG_SIGNATURE: IntArray = intArrayOf(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
private val PNG_ADAM7_PASSES: List<PngPass> = listOf(
    PngPass(0, 0, 8, 8),
    PngPass(4, 0, 8, 8),
    PngPass(0, 4, 4, 8),
    PngPass(2, 0, 4, 4),
    PngPass(0, 2, 2, 4),
    PngPass(1, 0, 2, 2),
    PngPass(0, 1, 1, 2),
)
