// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.preview

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt
import org.aurorasms.feature.conversations.MAXIMUM_PREVIEW_EDGE_PIXELS
import org.aurorasms.feature.conversations.MAXIMUM_PREVIEW_PIXELS
import org.aurorasms.feature.conversations.MAXIMUM_PREVIEW_SOURCE_EDGE_PIXELS
import org.aurorasms.feature.conversations.MAXIMUM_PREVIEW_SOURCE_PIXELS

internal enum class EncodedImageFormat {
    JPEG,
    PNG,
    WEBP,
    GIF,
    HEIF,
    AVIF,
}

internal data class TargetDimensions(
    val width: Int,
    val height: Int,
)

internal fun detectEncodedImageFormat(bytes: ByteArray): EncodedImageFormat? = when {
    bytes.startsWith(0xff, 0xd8, 0xff) -> EncodedImageFormat.JPEG
    bytes.startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> EncodedImageFormat.PNG
    bytes.asciiAt(0, "GIF87a") || bytes.asciiAt(0, "GIF89a") -> EncodedImageFormat.GIF
    bytes.asciiAt(0, "RIFF") && bytes.asciiAt(8, "WEBP") -> EncodedImageFormat.WEBP
    else -> detectIsoBaseMediaFormat(bytes)
}

internal fun declaredMimeMatchesFormat(
    declaredMimeType: String,
    format: EncodedImageFormat,
): Boolean = when (format) {
    EncodedImageFormat.JPEG -> declaredMimeType == "image/jpeg" || declaredMimeType == "image/jpg"
    EncodedImageFormat.PNG -> declaredMimeType == "image/png"
    EncodedImageFormat.WEBP -> declaredMimeType == "image/webp"
    EncodedImageFormat.GIF -> declaredMimeType == "image/gif"
    EncodedImageFormat.HEIF -> declaredMimeType == "image/heif" || declaredMimeType == "image/heic"
    EncodedImageFormat.AVIF -> declaredMimeType == "image/avif"
}

internal fun sourceDimensionsAreAllowed(width: Int, height: Int): Boolean =
    width in 1..MAXIMUM_PREVIEW_SOURCE_EDGE_PIXELS &&
        height in 1..MAXIMUM_PREVIEW_SOURCE_EDGE_PIXELS &&
        width.toLong() * height.toLong() <= MAXIMUM_PREVIEW_SOURCE_PIXELS

internal fun targetDimensions(width: Int, height: Int): TargetDimensions {
    require(sourceDimensionsAreAllowed(width, height)) { "Source dimensions exceed the reviewed bound" }
    val edgeScale = MAXIMUM_PREVIEW_EDGE_PIXELS.toDouble() / maxOf(width, height).toDouble()
    val pixelScale = sqrt(MAXIMUM_PREVIEW_PIXELS.toDouble() / (width.toLong() * height.toLong()).toDouble())
    val scale = min(1.0, min(edgeScale, pixelScale))
    return TargetDimensions(
        width = maxOf(1, floor(width * scale).toInt()),
        height = maxOf(1, floor(height * scale).toInt()),
    )
}

internal fun powerOfTwoSampleSize(width: Int, height: Int): Int {
    require(sourceDimensionsAreAllowed(width, height)) { "Source dimensions exceed the reviewed bound" }
    var sampleSize = 1
    while (
        divideRoundUp(width, sampleSize) > MAXIMUM_PREVIEW_EDGE_PIXELS ||
        divideRoundUp(height, sampleSize) > MAXIMUM_PREVIEW_EDGE_PIXELS ||
        divideRoundUp(width, sampleSize).toLong() * divideRoundUp(height, sampleSize).toLong() >
        MAXIMUM_PREVIEW_PIXELS
    ) {
        sampleSize *= 2
    }
    return sampleSize
}

private fun detectIsoBaseMediaFormat(bytes: ByteArray): EncodedImageFormat? {
    if (bytes.size < 16 || !bytes.asciiAt(4, "ftyp")) return null
    val declaredBoxSize = bytes.readUnsignedInt(0)
    if (declaredBoxSize < 16L) return null
    val inspectedEnd = min(bytes.size.toLong(), declaredBoxSize).toInt()
    val brands = buildSet {
        add(bytes.asciiString(8, 4) ?: return null)
        var offset = 16
        while (offset + 4 <= inspectedEnd) {
            bytes.asciiString(offset, 4)?.let(::add)
            offset += 4
        }
    }
    return when {
        brands.any { it == "avif" || it == "avis" } -> EncodedImageFormat.AVIF
        brands.any { it in HEIF_BRANDS } -> EncodedImageFormat.HEIF
        else -> null
    }
}

private fun ByteArray.startsWith(vararg expected: Int): Boolean =
    size >= expected.size && expected.indices.all { index -> this[index].toInt() and 0xff == expected[index] }

private fun ByteArray.asciiAt(offset: Int, value: String): Boolean =
    size >= offset + value.length && value.indices.all { index -> this[offset + index].toInt() == value[index].code }

private fun ByteArray.asciiString(offset: Int, length: Int): String? {
    if (offset < 0 || length < 0 || size < offset + length) return null
    if ((offset until offset + length).any { this[it].toInt() !in 0x20..0x7e }) return null
    return String(this, offset, length, Charsets.US_ASCII)
}

private fun ByteArray.readUnsignedInt(offset: Int): Long {
    if (size < offset + 4) return -1L
    return ((this[offset].toLong() and 0xffL) shl 24) or
        ((this[offset + 1].toLong() and 0xffL) shl 16) or
        ((this[offset + 2].toLong() and 0xffL) shl 8) or
        (this[offset + 3].toLong() and 0xffL)
}

private fun divideRoundUp(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

private val HEIF_BRANDS = setOf(
    "heic",
    "heix",
    "hevc",
    "hevx",
    "heim",
    "heis",
    "mif1",
    "msf1",
)
