// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import org.aurorasms.app.preview.sourceDimensionsAreAllowed

/**
 * The Android JPEG decoders can return pixels after an entropy stream was truncated and a new
 * end-of-image marker was appended. The wallpaper importer therefore admits only the bounded
 * Huffman baseline process that can be proven complete without retaining coefficient data.
 */
internal enum class JpegCompleteness {
    COMPLETE,
    SOURCE_DIMENSIONS_TOO_LARGE,
    INVALID,
}

internal fun ByteArray.isPotentiallySupportedBaselineJpeg(): Boolean {
    if (!hasJpegEnvelope()) return false
    var offset = JPEG_SOI_BYTES
    repeat(MAXIMUM_JPEG_HEADER_MARKERS) {
        val marker = readJpegMarker(offset) ?: return true
        offset = marker.nextOffset
        when {
            marker.code == JPEG_MARKER_SOS || marker.code == JPEG_MARKER_EOI -> return true
            marker.code == JPEG_MARKER_SOF0 -> {
                val segment = readJpegSegment(offset) ?: return true
                if (segment.payloadStart >= segment.endOffset) return true
                if (this[segment.payloadStart].unsigned() != JPEG_BASELINE_PRECISION) return false
                offset = segment.endOffset
            }
            marker.code.isJpegStartOfFrame() || marker.code in JPEG_EXCLUDED_PROCESS_MARKERS -> {
                return false
            }
            marker.code.isAllowedJpegHeaderSegment() -> {
                val segment = readJpegSegment(offset) ?: return true
                offset = segment.endOffset
            }
            else -> return true
        }
    }
    return true
}

internal fun ByteArray.jpegCompleteness(): JpegCompleteness {
    if (!hasJpegEnvelope()) return JpegCompleteness.INVALID
    return BaselineJpegValidator(this).validate()
}

private class BaselineJpegValidator(
    private val bytes: ByteArray,
) {
    private var offset: Int = JPEG_SOI_BYTES
    private var frame: JpegFrame? = null
    private val quantizationTables = BooleanArray(JPEG_TABLE_COUNT)
    private val dcTables = arrayOfNulls<JpegHuffmanTable>(JPEG_TABLE_COUNT)
    private val acTables = arrayOfNulls<JpegHuffmanTable>(JPEG_TABLE_COUNT)
    private val scannedComponents = BooleanArray(MAXIMUM_JPEG_COMPONENTS)
    private var restartInterval: Int = 0
    private var sawScan: Boolean = false
    private var headerMarkers: Int = 0

    fun validate(): JpegCompleteness {
        while (headerMarkers++ < MAXIMUM_JPEG_HEADER_MARKERS) {
            val marker = bytes.readJpegMarker(offset) ?: return JpegCompleteness.INVALID
            offset = marker.nextOffset
            when (marker.code) {
                JPEG_MARKER_EOI -> {
                    val currentFrame = frame ?: return JpegCompleteness.INVALID
                    if (!sawScan || offset != bytes.size) return JpegCompleteness.INVALID
                    if (currentFrame.components.indices.any { !scannedComponents[it] }) {
                        return JpegCompleteness.INVALID
                    }
                    return JpegCompleteness.COMPLETE
                }
                JPEG_MARKER_SOF0 -> {
                    if (frame != null || sawScan) return JpegCompleteness.INVALID
                    val segment = bytes.readJpegSegment(offset) ?: return JpegCompleteness.INVALID
                    val parsed = parseFrame(segment) ?: return JpegCompleteness.INVALID
                    frame = parsed
                    offset = segment.endOffset
                    if (!sourceDimensionsAreAllowed(parsed.width, parsed.height)) {
                        return JpegCompleteness.SOURCE_DIMENSIONS_TOO_LARGE
                    }
                }
                JPEG_MARKER_DQT -> {
                    if (!parseQuantizationTables(nextSegment())) return JpegCompleteness.INVALID
                }
                JPEG_MARKER_DHT -> {
                    if (!parseHuffmanTables(nextSegment())) return JpegCompleteness.INVALID
                }
                JPEG_MARKER_DRI -> {
                    val segment = nextSegment()
                    if (segment == null || segment.payloadBytes != 2) {
                        return JpegCompleteness.INVALID
                    }
                    restartInterval = bytes.readUnsignedShort(segment.payloadStart)
                        ?: return JpegCompleteness.INVALID
                }
                JPEG_MARKER_SOS -> {
                    val currentFrame = frame ?: return JpegCompleteness.INVALID
                    val segment = nextSegment() ?: return JpegCompleteness.INVALID
                    val scan = parseScan(segment, currentFrame) ?: return JpegCompleteness.INVALID
                    val entropyEnd = decodeScan(currentFrame, scan) ?: return JpegCompleteness.INVALID
                    scan.components.forEach { scannedComponents[it.frameIndex] = true }
                    offset = entropyEnd
                    sawScan = true
                }
                in JPEG_APP_MARKER_RANGE, JPEG_MARKER_COM -> {
                    if (nextSegment() == null) return JpegCompleteness.INVALID
                }
                else -> return JpegCompleteness.INVALID
            }
        }
        return JpegCompleteness.INVALID
    }

    private fun nextSegment(): JpegSegment? = bytes.readJpegSegment(offset)?.also {
        offset = it.endOffset
    }

    private fun parseFrame(segment: JpegSegment): JpegFrame? {
        if (segment.payloadBytes < JPEG_FRAME_FIXED_PAYLOAD_BYTES) return null
        var cursor = segment.payloadStart
        val precision = bytes[cursor++].unsigned()
        val height = bytes.readUnsignedShort(cursor) ?: return null
        cursor += 2
        val width = bytes.readUnsignedShort(cursor) ?: return null
        cursor += 2
        val componentCount = bytes[cursor++].unsigned()
        if (
            precision != JPEG_BASELINE_PRECISION ||
            width == 0 ||
            height == 0 ||
            componentCount !in 1..MAXIMUM_JPEG_COMPONENTS ||
            segment.payloadBytes != JPEG_FRAME_FIXED_PAYLOAD_BYTES +
            componentCount * JPEG_FRAME_COMPONENT_BYTES
        ) {
            return null
        }

        val components = ArrayList<JpegFrameComponent>(componentCount)
        repeat(componentCount) {
            val id = bytes[cursor++].unsigned()
            val sampling = bytes[cursor++].unsigned()
            val horizontalSampling = sampling ushr 4
            val verticalSampling = sampling and 0x0f
            val quantizationTable = bytes[cursor++].unsigned()
            if (
                components.any { it.id == id } ||
                horizontalSampling !in 1..MAXIMUM_JPEG_SAMPLING_FACTOR ||
                verticalSampling !in 1..MAXIMUM_JPEG_SAMPLING_FACTOR ||
                quantizationTable !in 0 until JPEG_TABLE_COUNT
            ) {
                return null
            }
            components += JpegFrameComponent(
                id = id,
                horizontalSampling = horizontalSampling,
                verticalSampling = verticalSampling,
                quantizationTable = quantizationTable,
            )
        }
        val maximumHorizontal = components.maxOf(JpegFrameComponent::horizontalSampling)
        val maximumVertical = components.maxOf(JpegFrameComponent::verticalSampling)
        if (
            components.sumOf { it.horizontalSampling * it.verticalSampling } >
            MAXIMUM_JPEG_BLOCKS_PER_MCU ||
            components.any {
                maximumHorizontal % it.horizontalSampling != 0 ||
                    maximumVertical % it.verticalSampling != 0
            }
        ) {
            return null
        }
        return JpegFrame(
            width = width,
            height = height,
            maximumHorizontalSampling = maximumHorizontal,
            maximumVerticalSampling = maximumVertical,
            components = components,
        )
    }

    private fun parseQuantizationTables(segment: JpegSegment?): Boolean {
        segment ?: return false
        if (segment.payloadBytes == 0) return false
        var cursor = segment.payloadStart
        while (cursor < segment.endOffset) {
            val descriptor = bytes[cursor++].unsigned()
            val precision = descriptor ushr 4
            val tableId = descriptor and 0x0f
            if (
                precision != JPEG_BASELINE_QUANTIZATION_PRECISION ||
                tableId !in 0 until JPEG_TABLE_COUNT ||
                cursor > segment.endOffset - JPEG_QUANTIZATION_VALUES
            ) {
                return false
            }
            repeat(JPEG_QUANTIZATION_VALUES) {
                if (bytes[cursor++].unsigned() == 0) return false
            }
            quantizationTables[tableId] = true
        }
        return cursor == segment.endOffset
    }

    private fun parseHuffmanTables(segment: JpegSegment?): Boolean {
        segment ?: return false
        if (segment.payloadBytes == 0) return false
        var cursor = segment.payloadStart
        while (cursor < segment.endOffset) {
            if (cursor > segment.endOffset - 1 - JPEG_HUFFMAN_CODE_LENGTHS) return false
            val descriptor = bytes[cursor++].unsigned()
            val tableClass = descriptor ushr 4
            val tableId = descriptor and 0x0f
            if (tableClass !in 0..1 || tableId !in 0 until JPEG_TABLE_COUNT) return false
            val counts = IntArray(JPEG_HUFFMAN_CODE_LENGTHS) { bytes[cursor++].unsigned() }
            val symbolCount = counts.sum()
            if (
                symbolCount !in 1..MAXIMUM_JPEG_HUFFMAN_SYMBOLS ||
                cursor > segment.endOffset - symbolCount
            ) {
                return false
            }
            val symbols = IntArray(symbolCount) { bytes[cursor++].unsigned() }
            val table = JpegHuffmanTable.create(counts, symbols, tableClass) ?: return false
            if (tableClass == JPEG_HUFFMAN_DC_CLASS) {
                dcTables[tableId] = table
            } else {
                acTables[tableId] = table
            }
        }
        return cursor == segment.endOffset
    }

    private fun parseScan(segment: JpegSegment, currentFrame: JpegFrame): JpegScan? {
        if (segment.payloadBytes < JPEG_SCAN_FIXED_PAYLOAD_BYTES) return null
        var cursor = segment.payloadStart
        val componentCount = bytes[cursor++].unsigned()
        if (
            componentCount !in 1..currentFrame.components.size ||
            segment.payloadBytes != JPEG_SCAN_FIXED_PAYLOAD_BYTES +
            componentCount * JPEG_SCAN_COMPONENT_BYTES
        ) {
            return null
        }
        val components = ArrayList<JpegScanComponent>(componentCount)
        repeat(componentCount) {
            val selector = bytes[cursor++].unsigned()
            val frameIndex = currentFrame.components.indexOfFirst { it.id == selector }
            val tables = bytes[cursor++].unsigned()
            val dcTableId = tables ushr 4
            val acTableId = tables and 0x0f
            if (
                frameIndex < 0 ||
                scannedComponents[frameIndex] ||
                components.any { it.frameIndex == frameIndex } ||
                dcTableId !in 0 until JPEG_TABLE_COUNT ||
                acTableId !in 0 until JPEG_TABLE_COUNT
            ) {
                return null
            }
            val frameComponent = currentFrame.components[frameIndex]
            if (!quantizationTables[frameComponent.quantizationTable]) return null
            components += JpegScanComponent(
                frameIndex = frameIndex,
                dcTable = dcTables[dcTableId] ?: return null,
                acTable = acTables[acTableId] ?: return null,
            )
        }
        val spectralStart = bytes[cursor++].unsigned()
        val spectralEnd = bytes[cursor++].unsigned()
        val approximation = bytes[cursor].unsigned()
        if (
            spectralStart != JPEG_BASELINE_SPECTRAL_START ||
            spectralEnd != JPEG_BASELINE_SPECTRAL_END ||
            approximation != JPEG_BASELINE_APPROXIMATION
        ) {
            return null
        }
        return JpegScan(components)
    }

    private fun decodeScan(currentFrame: JpegFrame, scan: JpegScan): Int? {
        val interleaved = scan.components.size > 1
        val mcuColumns: Long
        val mcuRows: Long
        if (interleaved) {
            mcuColumns = divideRoundUp(
                currentFrame.width.toLong(),
                JPEG_BLOCK_EDGE * currentFrame.maximumHorizontalSampling.toLong(),
            )
            mcuRows = divideRoundUp(
                currentFrame.height.toLong(),
                JPEG_BLOCK_EDGE * currentFrame.maximumVerticalSampling.toLong(),
            )
        } else {
            val component = currentFrame.components[scan.components.single().frameIndex]
            mcuColumns = divideRoundUp(
                currentFrame.width.toLong() * component.horizontalSampling,
                JPEG_BLOCK_EDGE * currentFrame.maximumHorizontalSampling.toLong(),
            )
            mcuRows = divideRoundUp(
                currentFrame.height.toLong() * component.verticalSampling,
                JPEG_BLOCK_EDGE * currentFrame.maximumVerticalSampling.toLong(),
            )
        }
        val mcuCount = mcuColumns * mcuRows
        if (mcuCount <= 0L || mcuCount > MAXIMUM_JPEG_MCU_COUNT) return null

        val reader = JpegEntropyReader(bytes, offset)
        var expectedRestart = 0
        var mcuIndex = 0L
        while (mcuIndex < mcuCount) {
            scan.components.forEach { scanComponent ->
                val frameComponent = currentFrame.components[scanComponent.frameIndex]
                val blockCount = if (interleaved) {
                    frameComponent.horizontalSampling * frameComponent.verticalSampling
                } else {
                    1
                }
                repeat(blockCount) {
                    if (!decodeBlock(reader, scanComponent)) return null
                }
            }
            mcuIndex++
            if (
                restartInterval > 0 &&
                mcuIndex < mcuCount &&
                mcuIndex % restartInterval.toLong() == 0L
            ) {
                if (!reader.consumeRestart(expectedRestart)) return null
                expectedRestart = (expectedRestart + 1) and 0x07
            }
        }
        return reader.finishScan()
    }

    private fun decodeBlock(reader: JpegEntropyReader, component: JpegScanComponent): Boolean {
        val dcBits = component.dcTable.decode(reader) ?: return false
        if (!reader.skipBits(dcBits)) return false
        var coefficient = 1
        while (coefficient < JPEG_BLOCK_COEFFICIENTS) {
            val symbol = component.acTable.decode(reader) ?: return false
            val zeroRun = symbol ushr 4
            val valueBits = symbol and 0x0f
            when {
                valueBits == 0 && zeroRun == 0 -> return true
                valueBits == 0 && zeroRun == JPEG_ZERO_RUN_LENGTH -> {
                    coefficient += JPEG_ZERO_RUN_COEFFICIENTS
                    if (coefficient > JPEG_BLOCK_COEFFICIENTS) return false
                }
                valueBits == 0 -> return false
                else -> {
                    coefficient += zeroRun
                    if (coefficient >= JPEG_BLOCK_COEFFICIENTS) return false
                    if (!reader.skipBits(valueBits)) return false
                    coefficient++
                }
            }
        }
        return true
    }
}

private data class JpegFrame(
    val width: Int,
    val height: Int,
    val maximumHorizontalSampling: Int,
    val maximumVerticalSampling: Int,
    val components: List<JpegFrameComponent>,
)

private data class JpegFrameComponent(
    val id: Int,
    val horizontalSampling: Int,
    val verticalSampling: Int,
    val quantizationTable: Int,
)

private data class JpegScan(
    val components: List<JpegScanComponent>,
)

private data class JpegScanComponent(
    val frameIndex: Int,
    val dcTable: JpegHuffmanTable,
    val acTable: JpegHuffmanTable,
)

private class JpegHuffmanTable private constructor(
    private val counts: IntArray,
    private val symbols: IntArray,
    private val firstCodes: IntArray,
    private val firstSymbols: IntArray,
) {
    fun decode(reader: JpegEntropyReader): Int? {
        var code = 0
        counts.indices.forEach { index ->
            code = (code shl 1) or (reader.readBit() ?: return null)
            val relative = code - firstCodes[index]
            if (relative in 0 until counts[index]) {
                return symbols[firstSymbols[index] + relative]
            }
        }
        return null
    }

    companion object {
        fun create(counts: IntArray, symbols: IntArray, tableClass: Int): JpegHuffmanTable? {
            val firstCodes = IntArray(JPEG_HUFFMAN_CODE_LENGTHS)
            val firstSymbols = IntArray(JPEG_HUFFMAN_CODE_LENGTHS)
            var code = 0
            var symbolOffset = 0
            counts.indices.forEach { index ->
                val bitLength = index + 1
                val allOnesCode = (1 shl bitLength) - 1
                if (code + counts[index] > allOnesCode) return null
                firstCodes[index] = code
                firstSymbols[index] = symbolOffset
                symbolOffset += counts[index]
                code = (code + counts[index]) shl 1
            }
            if (symbolOffset != symbols.size) return null
            if (tableClass == JPEG_HUFFMAN_DC_CLASS) {
                if (symbols.any { it !in 0..JPEG_MAXIMUM_DC_CATEGORY }) return null
            } else if (symbols.any { symbol ->
                    val run = symbol ushr 4
                    val size = symbol and 0x0f
                    size > JPEG_MAXIMUM_AC_CATEGORY ||
                        (size == 0 && run != 0 && run != JPEG_ZERO_RUN_LENGTH)
                }
            ) {
                return null
            }
            return JpegHuffmanTable(counts, symbols, firstCodes, firstSymbols)
        }
    }
}

private class JpegEntropyReader(
    private val bytes: ByteArray,
    startOffset: Int,
) {
    var position: Int = startOffset
        private set
    private var currentByte: Int = 0
    private var remainingBits: Int = 0

    fun readBit(): Int? {
        if (remainingBits == 0 && !readEntropyByte()) return null
        remainingBits--
        return (currentByte ushr remainingBits) and 1
    }

    fun skipBits(count: Int): Boolean {
        repeat(count) {
            if (readBit() == null) return false
        }
        return true
    }

    fun consumeRestart(expectedIndex: Int): Boolean {
        if (!discardAllOnesPadding()) return false
        val marker = bytes.readJpegMarker(position) ?: return false
        if (marker.code != JPEG_MARKER_RST0 + expectedIndex) return false
        position = marker.nextOffset
        return true
    }

    fun finishScan(): Int? = position.takeIf { discardAllOnesPadding() }

    private fun readEntropyByte(): Boolean {
        if (position >= bytes.size) return false
        val value = bytes[position].unsigned()
        if (value != JPEG_MARKER_PREFIX) {
            position++
            currentByte = value
            remainingBits = Byte.SIZE_BITS
            return true
        }
        if (
            position + 1 >= bytes.size ||
            bytes[position + 1].unsigned() != JPEG_STUFFED_ZERO
        ) {
            return false
        }
        position += 2
        currentByte = JPEG_MARKER_PREFIX
        remainingBits = Byte.SIZE_BITS
        return true
    }

    private fun discardAllOnesPadding(): Boolean {
        if (remainingBits > 0) {
            val mask = (1 shl remainingBits) - 1
            if (currentByte and mask != mask) return false
        }
        remainingBits = 0
        return true
    }
}

private data class JpegMarker(
    val code: Int,
    val nextOffset: Int,
)

private data class JpegSegment(
    val payloadStart: Int,
    val endOffset: Int,
) {
    val payloadBytes: Int = endOffset - payloadStart
}

private fun ByteArray.hasJpegEnvelope(): Boolean =
    size >= JPEG_MINIMUM_BYTES &&
        this[0].unsigned() == JPEG_MARKER_PREFIX &&
        this[1].unsigned() == JPEG_MARKER_SOI &&
        this[size - 2].unsigned() == JPEG_MARKER_PREFIX &&
        this[size - 1].unsigned() == JPEG_MARKER_EOI

private fun ByteArray.readJpegMarker(startOffset: Int): JpegMarker? {
    if (startOffset !in indices || this[startOffset].unsigned() != JPEG_MARKER_PREFIX) return null
    var cursor = startOffset
    while (cursor < size && this[cursor].unsigned() == JPEG_MARKER_PREFIX) cursor++
    if (cursor >= size) return null
    val code = this[cursor].unsigned()
    if (code == JPEG_STUFFED_ZERO) return null
    return JpegMarker(code = code, nextOffset = cursor + 1)
}

private fun ByteArray.readJpegSegment(lengthOffset: Int): JpegSegment? {
    val length = readUnsignedShort(lengthOffset) ?: return null
    if (length < JPEG_SEGMENT_LENGTH_BYTES) return null
    val endOffset = lengthOffset.toLong() + length
    if (endOffset > size.toLong()) return null
    return JpegSegment(
        payloadStart = lengthOffset + JPEG_SEGMENT_LENGTH_BYTES,
        endOffset = endOffset.toInt(),
    )
}

private fun ByteArray.readUnsignedShort(offset: Int): Int? {
    if (offset < 0 || offset > size - 2) return null
    return (this[offset].unsigned() shl 8) or this[offset + 1].unsigned()
}

private fun Int.isJpegStartOfFrame(): Boolean =
    this in JPEG_SOF_MARKER_RANGE &&
        this != JPEG_MARKER_DHT &&
        this != JPEG_MARKER_JPG &&
        this != JPEG_MARKER_DAC

private fun Int.isAllowedJpegHeaderSegment(): Boolean =
    this == JPEG_MARKER_DQT ||
        this == JPEG_MARKER_DHT ||
        this == JPEG_MARKER_DRI ||
        this in JPEG_APP_MARKER_RANGE ||
        this == JPEG_MARKER_COM

private fun Byte.unsigned(): Int = toInt() and 0xff

private fun divideRoundUp(value: Long, divisor: Long): Long = (value + divisor - 1L) / divisor

private const val JPEG_MARKER_PREFIX: Int = 0xff
private const val JPEG_STUFFED_ZERO: Int = 0x00
private const val JPEG_MARKER_SOF0: Int = 0xc0
private const val JPEG_MARKER_DHT: Int = 0xc4
private const val JPEG_MARKER_JPG: Int = 0xc8
private const val JPEG_MARKER_DAC: Int = 0xcc
private const val JPEG_MARKER_SOI: Int = 0xd8
private const val JPEG_MARKER_EOI: Int = 0xd9
private const val JPEG_MARKER_SOS: Int = 0xda
private const val JPEG_MARKER_DQT: Int = 0xdb
private const val JPEG_MARKER_DNL: Int = 0xdc
private const val JPEG_MARKER_DRI: Int = 0xdd
private const val JPEG_MARKER_DHP: Int = 0xde
private const val JPEG_MARKER_EXP: Int = 0xdf
private const val JPEG_MARKER_RST0: Int = 0xd0
private const val JPEG_MARKER_COM: Int = 0xfe
private const val JPEG_SOI_BYTES: Int = 2
private const val JPEG_MINIMUM_BYTES: Int = 4
private const val JPEG_SEGMENT_LENGTH_BYTES: Int = 2
private const val JPEG_BASELINE_PRECISION: Int = 8
private const val JPEG_BASELINE_QUANTIZATION_PRECISION: Int = 0
private const val JPEG_BASELINE_SPECTRAL_START: Int = 0
private const val JPEG_BASELINE_SPECTRAL_END: Int = 63
private const val JPEG_BASELINE_APPROXIMATION: Int = 0
private const val JPEG_FRAME_FIXED_PAYLOAD_BYTES: Int = 6
private const val JPEG_FRAME_COMPONENT_BYTES: Int = 3
private const val JPEG_SCAN_FIXED_PAYLOAD_BYTES: Int = 4
private const val JPEG_SCAN_COMPONENT_BYTES: Int = 2
private const val JPEG_HUFFMAN_CODE_LENGTHS: Int = 16
private const val MAXIMUM_JPEG_HUFFMAN_SYMBOLS: Int = 256
private const val JPEG_HUFFMAN_DC_CLASS: Int = 0
private const val JPEG_MAXIMUM_DC_CATEGORY: Int = 11
private const val JPEG_MAXIMUM_AC_CATEGORY: Int = 10
private const val JPEG_ZERO_RUN_LENGTH: Int = 15
private const val JPEG_ZERO_RUN_COEFFICIENTS: Int = 16
private const val JPEG_QUANTIZATION_VALUES: Int = 64
private const val JPEG_TABLE_COUNT: Int = 4
private const val MAXIMUM_JPEG_COMPONENTS: Int = 4
private const val MAXIMUM_JPEG_SAMPLING_FACTOR: Int = 4
private const val MAXIMUM_JPEG_BLOCKS_PER_MCU: Int = 10
private const val JPEG_BLOCK_EDGE: Long = 8L
private const val JPEG_BLOCK_COEFFICIENTS: Int = 64
private const val MAXIMUM_JPEG_HEADER_MARKERS: Int = 4_096
private const val MAXIMUM_JPEG_MCU_COUNT: Long = 650_000L
private val JPEG_SOF_MARKER_RANGE: IntRange = 0xc0..0xcf
private val JPEG_APP_MARKER_RANGE: IntRange = 0xe0..0xef
private val JPEG_EXCLUDED_PROCESS_MARKERS: Set<Int> = setOf(
    JPEG_MARKER_DAC,
    JPEG_MARKER_DNL,
    JPEG_MARKER_DHP,
    JPEG_MARKER_EXP,
)
