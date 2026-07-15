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
internal const val MAXIMUM_DECLARED_MIME_TYPE_CHARACTERS: Int = 256
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
    bytes.isPotentiallySupportedBaselineJpeg() ->
        WallpaperSourceFormat.JPEG
    bytes.startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) ->
        WallpaperSourceFormat.PNG.takeIf { bytes.isStructurallyValidStaticPng() }
    else -> null
}

internal fun wallpaperMimeMatches(
    declaredMimeType: String?,
    format: WallpaperSourceFormat,
): Boolean {
    if (declaredMimeType != null && declaredMimeType.length > MAXIMUM_DECLARED_MIME_TYPE_CHARACTERS) {
        return false
    }
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

internal fun ByteArray.isStaticWebp(): Boolean {
    if (size < WEBP_RIFF_HEADER_BYTES + WEBP_CHUNK_HEADER_BYTES) return false
    if (!asciiAt(0, "RIFF") || !asciiAt(8, "WEBP")) return false
    val declaredRiffBytes = readUnsignedIntLittleEndian(4) ?: return false
    if (declaredRiffBytes != size.toLong() - RIFF_SIZE_PREFIX_BYTES) return false

    var offset = WEBP_RIFF_HEADER_BYTES
    var sawExtendedHeader = false
    var sawImagePayload = false
    while (offset < size) {
        if (offset > size - WEBP_CHUNK_HEADER_BYTES) return false
        val payloadBytes = readUnsignedIntLittleEndian(offset + 4) ?: return false
        if (payloadBytes > Int.MAX_VALUE.toLong()) return false
        val payloadOffset = offset + WEBP_CHUNK_HEADER_BYTES
        val payloadEnd = payloadOffset.toLong() + payloadBytes
        val paddedEnd = payloadEnd + (payloadBytes and 1L)
        if (payloadEnd < payloadOffset.toLong() || paddedEnd > size.toLong()) return false

        when {
            asciiAt(offset, "VP8X") -> {
                if (
                    offset != WEBP_RIFF_HEADER_BYTES ||
                    sawExtendedHeader ||
                    payloadBytes != WEBP_VP8X_PAYLOAD_BYTES.toLong()
                ) {
                    return false
                }
                val featureFlags = this[payloadOffset].unsigned()
                if (
                    featureFlags and WEBP_VP8X_ANIMATION_FLAG != 0 ||
                    featureFlags and WEBP_VP8X_RESERVED_FLAGS != 0 ||
                    (1..3).any { index -> this[payloadOffset + index].unsigned() != 0 }
                ) {
                    return false
                }
                sawExtendedHeader = true
            }
            asciiAt(offset, "ANIM") || asciiAt(offset, "ANMF") -> return false
            asciiAt(offset, "VP8 ") || asciiAt(offset, "VP8L") -> {
                if (sawImagePayload) return false
                sawImagePayload = true
            }
        }
        offset = paddedEnd.toInt()
    }
    return sawImagePayload && offset == size
}

private fun ByteArray.isStructurallyValidStaticPng(): Boolean {
    if (size < PNG_SIGNATURE_BYTES + PNG_CHUNK_OVERHEAD_BYTES) return false
    var offset = PNG_SIGNATURE_BYTES
    var firstChunk = true
    var sawImageData = false
    var chunkCount = 0
    while (offset + PNG_CHUNK_OVERHEAD_BYTES <= size) {
        if (++chunkCount > MAXIMUM_PNG_CHUNKS) return false
        val length = readUnsignedInt(offset)
        if (length < 0L || length > Int.MAX_VALUE) return false
        val chunkEnd = offset.toLong() + PNG_CHUNK_OVERHEAD_BYTES + length
        if (chunkEnd > size.toLong()) return false
        val typeOffset = offset + 4
        if (!hasValidPngChunkType(typeOffset)) return false
        val payloadEnd = offset + 8 + length.toInt()
        val storedCrc = readUnsignedInt(payloadEnd)
        val actualCrc = CRC32().apply {
            update(this@isStructurallyValidStaticPng, typeOffset, 4 + length.toInt())
        }.value
        if (storedCrc != actualCrc) return false
        if (firstChunk && (!asciiAt(typeOffset, "IHDR") || length != PNG_IHDR_BYTES)) return false
        if (!firstChunk && asciiAt(typeOffset, "IHDR")) return false
        firstChunk = false
        if (
            asciiAt(typeOffset, "acTL") ||
            asciiAt(typeOffset, "fcTL") ||
            asciiAt(typeOffset, "fdAT") ||
            asciiAt(typeOffset, "iCCP") ||
            asciiAt(typeOffset, "zTXt") ||
            asciiAt(typeOffset, "iTXt")
        ) {
            return false
        }
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

private fun ByteArray.readUnsignedIntLittleEndian(offset: Int): Long? {
    if (offset < 0 || size < offset + 4) return null
    return (this[offset].unsigned().toLong()) or
        (this[offset + 1].unsigned().toLong() shl 8) or
        (this[offset + 2].unsigned().toLong() shl 16) or
        (this[offset + 3].unsigned().toLong() shl 24)
}

private fun ByteArray.startsWith(vararg expected: Int): Boolean =
    size >= expected.size && expected.indices.all { index ->
        this[index].toInt() and 0xff == expected[index]
    }

private fun ByteArray.asciiAt(offset: Int, value: String): Boolean =
    offset >= 0 && size >= offset + value.length && value.indices.all { index ->
        this[offset + index].toInt() == value[index].code
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
private const val MAXIMUM_PNG_CHUNKS: Int = 4_096
private const val PNG_TYPE_BYTES: Int = 4
private const val PNG_RESERVED_TYPE_INDEX: Int = 2
private const val RIFF_SIZE_PREFIX_BYTES: Int = 8
private const val WEBP_RIFF_HEADER_BYTES: Int = 12
private const val WEBP_CHUNK_HEADER_BYTES: Int = 8
private const val WEBP_VP8X_PAYLOAD_BYTES: Int = 10
private const val WEBP_VP8X_ANIMATION_FLAG: Int = 0x02
private const val WEBP_VP8X_RESERVED_FLAGS: Int = 0xc1
private val LOWERCASE_HEX: CharArray = "0123456789abcdef".toCharArray()
