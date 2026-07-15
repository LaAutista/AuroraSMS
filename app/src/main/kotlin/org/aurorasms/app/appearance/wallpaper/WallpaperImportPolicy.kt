// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import java.security.MessageDigest
import java.util.zip.CRC32

internal const val MAXIMUM_WALLPAPER_SOURCE_BYTES: Int = 16 * 1_024 * 1_024
internal const val MAXIMUM_WALLPAPER_DERIVATIVE_BYTES: Int = 8 * 1_024 * 1_024
internal const val MAXIMUM_WALLPAPER_FILE_COUNT: Int = 128
internal const val MAXIMUM_WALLPAPER_TOTAL_BYTES: Long = 256L * 1_024L * 1_024L
internal const val MAXIMUM_WALLPAPER_STAGED_FILE_COUNT: Int = MAXIMUM_WALLPAPER_FILE_COUNT + 1
internal const val MAXIMUM_WALLPAPER_STAGED_TOTAL_BYTES: Long =
    MAXIMUM_WALLPAPER_TOTAL_BYTES + MAXIMUM_WALLPAPER_DERIVATIVE_BYTES
internal const val MAXIMUM_TRANSIENT_URI_BYTES: Int = 4_096
internal const val MAXIMUM_WALLPAPER_EDGE_PIXELS: Int = 2_048
internal const val MAXIMUM_WALLPAPER_PIXELS: Long = 4_194_304L
internal const val MAXIMUM_WALLPAPER_ALLOCATION_BYTES: Int = 16 * 1_024 * 1_024
internal const val MAXIMUM_WALLPAPER_PREVIEW_EDGE_PIXELS: Int = 512
internal const val MAXIMUM_WALLPAPER_PREVIEW_PIXELS: Long = 262_144L
internal const val MAXIMUM_WALLPAPER_PREVIEW_ALLOCATION_BYTES: Int = 1 * 1_024 * 1_024

internal const val WALLPAPER_MEDIA_ID_PREFIX: String = "sha256-v1:"
internal const val WALLPAPER_FILE_PREFIX: String = "v1-"
internal const val WALLPAPER_FILE_SUFFIX: String = ".webp"

internal enum class WallpaperSourceFormat {
    JPEG,
    PNG,
}

internal fun wallpaperSourceFormat(bytes: ByteArray): WallpaperSourceFormat? = when {
    bytes.startsWith(0xff, 0xd8, 0xff) && bytes.hasTerminalJpegEndMarker() ->
        WallpaperSourceFormat.JPEG
    bytes.startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) ->
        WallpaperSourceFormat.PNG.takeIf { bytes.isStructurallyValidStaticPng() }
    else -> null
}

internal fun wallpaperMimeMatches(
    declaredMimeType: String?,
    format: WallpaperSourceFormat,
): Boolean {
    val declared = declaredMimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?.takeIf(String::isNotEmpty)
        ?: return true
    return when (format) {
        WallpaperSourceFormat.JPEG -> declared == "image/jpeg" || declared == "image/jpg"
        WallpaperSourceFormat.PNG -> declared == "image/png"
    }
}

internal fun wallpaperDerivativeFileName(mediaId: String): String? {
    val digest = mediaId.removePrefix(WALLPAPER_MEDIA_ID_PREFIX)
    if (mediaId.length != WALLPAPER_MEDIA_ID_PREFIX.length + SHA_256_HEX_CHARACTERS) return null
    if (digest.length != SHA_256_HEX_CHARACTERS || !digest.isLowercaseHex()) return null
    return "$WALLPAPER_FILE_PREFIX$digest$WALLPAPER_FILE_SUFFIX"
}

internal fun wallpaperMediaId(bytes: ByteArray): String =
    WALLPAPER_MEDIA_ID_PREFIX + MessageDigest.getInstance("SHA-256").digest(bytes).toLowercaseHex()

internal fun wallpaperDerivativeMatches(mediaId: String, bytes: ByteArray): Boolean =
    bytes.isStaticWebp() && wallpaperMediaId(bytes) == mediaId

internal fun ByteArray.isStaticWebp(): Boolean =
    size >= 16 && asciiAt(0, "RIFF") && asciiAt(8, "WEBP")

private fun ByteArray.hasTerminalJpegEndMarker(): Boolean =
    size >= 4 && this[size - 2].unsigned() == 0xff && this[size - 1].unsigned() == 0xd9

private fun ByteArray.isStructurallyValidStaticPng(): Boolean {
    if (size < PNG_SIGNATURE_BYTES + PNG_CHUNK_OVERHEAD_BYTES) return false
    var offset = PNG_SIGNATURE_BYTES
    var firstChunk = true
    var sawImageData = false
    while (offset + PNG_CHUNK_OVERHEAD_BYTES <= size) {
        val length = readUnsignedInt(offset)
        if (length < 0L || length > Int.MAX_VALUE) return false
        val chunkEnd = offset.toLong() + PNG_CHUNK_OVERHEAD_BYTES + length
        if (chunkEnd > size.toLong()) return false
        val typeOffset = offset + 4
        val payloadEnd = offset + 8 + length.toInt()
        val storedCrc = readUnsignedInt(payloadEnd)
        val actualCrc = CRC32().apply {
            update(this@isStructurallyValidStaticPng, typeOffset, 4 + length.toInt())
        }.value
        if (storedCrc != actualCrc) return false
        if (firstChunk && (!asciiAt(typeOffset, "IHDR") || length != PNG_IHDR_BYTES)) return false
        if (!firstChunk && asciiAt(typeOffset, "IHDR")) return false
        firstChunk = false
        if (asciiAt(typeOffset, "acTL")) return false
        if (asciiAt(typeOffset, "IDAT")) sawImageData = true
        if (asciiAt(typeOffset, "IEND")) {
            return length == 0L && sawImageData && chunkEnd == size.toLong()
        }
        offset = chunkEnd.toInt()
    }
    return false
}

private fun ByteArray.readUnsignedInt(offset: Int): Long {
    if (offset < 0 || size < offset + 4) return -1L
    return ((this[offset].toLong() and 0xffL) shl 24) or
        ((this[offset + 1].toLong() and 0xffL) shl 16) or
        ((this[offset + 2].toLong() and 0xffL) shl 8) or
        (this[offset + 3].toLong() and 0xffL)
}

private fun ByteArray.startsWith(vararg expected: Int): Boolean =
    size >= expected.size && expected.indices.all { index ->
        this[index].toInt() and 0xff == expected[index]
    }

private fun ByteArray.asciiAt(offset: Int, value: String): Boolean =
    offset >= 0 && size >= offset + value.length && value.indices.all { index ->
        this[offset + index].toInt() == value[index].code
    }

private fun Byte.unsigned(): Int = toInt() and 0xff

private fun String.isLowercaseHex(): Boolean = all { it in '0'..'9' || it in 'a'..'f' }

private fun ByteArray.toLowercaseHex(): String = buildString(size * 2) {
    this@toLowercaseHex.forEach { byte ->
        val value = byte.toInt() and 0xff
        append(LOWERCASE_HEX[value ushr 4])
        append(LOWERCASE_HEX[value and 0x0f])
    }
}

private const val SHA_256_HEX_CHARACTERS: Int = 64
private const val PNG_SIGNATURE_BYTES: Int = 8
private const val PNG_CHUNK_OVERHEAD_BYTES: Int = 12
private const val PNG_IHDR_BYTES: Long = 13L
private val LOWERCASE_HEX: CharArray = "0123456789abcdef".toCharArray()
