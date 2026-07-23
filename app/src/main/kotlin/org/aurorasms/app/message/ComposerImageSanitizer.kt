// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.aurorasms.core.telephony.OutgoingMmsAttachment

internal sealed interface ComposerImageSanitizationResult {
    data class Ready(val attachment: OutgoingMmsAttachment) : ComposerImageSanitizationResult

    data object Unreadable : ComposerImageSanitizationResult
    data object Unsupported : ComposerImageSanitizationResult
    data object TooLarge : ComposerImageSanitizationResult
}

/**
 * Admits one user-selected image only after bounded decode and metadata-stripping re-encode.
 * The source URI, filename, EXIF, color profile, and container metadata never enter the payload.
 */
internal object ComposerImageSanitizer {
    suspend fun sanitize(
        resolver: ContentResolver,
        uri: Uri,
    ): ComposerImageSanitizationResult = withContext(Dispatchers.IO) {
        val input = try {
            resolver.openInputStream(uri)
                ?: return@withContext ComposerImageSanitizationResult.Unreadable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            return@withContext ComposerImageSanitizationResult.Unreadable
        } catch (_: IOException) {
            return@withContext ComposerImageSanitizationResult.Unreadable
        } catch (_: RuntimeException) {
            return@withContext ComposerImageSanitizationResult.Unreadable
        }
        val source = try {
            input.use(::readBoundedSource)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            return@withContext ComposerImageSanitizationResult.Unreadable
        } catch (_: RuntimeException) {
            return@withContext ComposerImageSanitizationResult.Unreadable
        }
        source ?: return@withContext ComposerImageSanitizationResult.TooLarge
        try {
            sanitizeBytes(source)
        } catch (_: RuntimeException) {
            ComposerImageSanitizationResult.Unsupported
        }
    }

    internal fun sanitizeBytes(source: ByteArray): ComposerImageSanitizationResult {
        if (source.isEmpty() || source.size > MAXIMUM_SOURCE_BYTES) {
            return ComposerImageSanitizationResult.TooLarge
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        } catch (_: RuntimeException) {
            return ComposerImageSanitizationResult.Unsupported
        }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return ComposerImageSanitizationResult.Unsupported
        if (
            width > MAXIMUM_SOURCE_DIMENSION ||
            height > MAXIMUM_SOURCE_DIMENSION ||
            width.toLong() * height.toLong() > MAXIMUM_SOURCE_PIXELS
        ) {
            return ComposerImageSanitizationResult.TooLarge
        }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(width, height)
        }
        val decoded = try {
            BitmapFactory.decodeByteArray(source, 0, source.size, options)
        } catch (_: RuntimeException) {
            null
        } ?: return ComposerImageSanitizationResult.Unsupported
        return try {
            encodeBounded(decoded)
        } finally {
            decoded.recycle()
        }
    }

    private fun encodeBounded(decoded: Bitmap): ComposerImageSanitizationResult {
        var current = scaleToMaximumDimension(decoded)
        try {
            repeat(MAXIMUM_ENCODE_ATTEMPTS) { attempt ->
                val alpha = current.hasAlpha()
                val format = if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                val quality = if (alpha) 100 else (JPEG_INITIAL_QUALITY - attempt * JPEG_QUALITY_STEP)
                    .coerceAtLeast(JPEG_MINIMUM_QUALITY)
                val encoded = ByteArrayOutputStream().use { output ->
                    if (!current.compress(format, quality, output)) return@use null
                    output.toByteArray()
                }
                if (encoded != null && encoded.size <= OutgoingMmsAttachment.MAX_BYTES) {
                    val contentType = if (alpha) {
                        OutgoingMmsAttachment.IMAGE_PNG
                    } else {
                        OutgoingMmsAttachment.IMAGE_JPEG
                    }
                    return when (val admitted = OutgoingMmsAttachment.create(contentType, encoded)) {
                        is OutgoingMmsAttachment.CreationResult.Valid ->
                            ComposerImageSanitizationResult.Ready(admitted.attachment)
                        else -> ComposerImageSanitizationResult.TooLarge
                    }
                }
                val nextWidth = (current.width * SCALE_RETRY_FACTOR).toInt().coerceAtLeast(1)
                val nextHeight = (current.height * SCALE_RETRY_FACTOR).toInt().coerceAtLeast(1)
                if (nextWidth == current.width && nextHeight == current.height) {
                    return ComposerImageSanitizationResult.TooLarge
                }
                val scaled = current.scale(nextWidth, nextHeight)
                if (current !== decoded) current.recycle()
                current = scaled
            }
            return ComposerImageSanitizationResult.TooLarge
        } finally {
            if (current !== decoded) current.recycle()
        }
    }

    private fun scaleToMaximumDimension(bitmap: Bitmap): Bitmap {
        val largest = max(bitmap.width, bitmap.height)
        if (largest <= MAXIMUM_OUTPUT_DIMENSION) return bitmap
        val ratio = MAXIMUM_OUTPUT_DIMENSION.toFloat() / largest.toFloat()
        return bitmap.scale(
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
        )
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (max(width / sample, height / sample) > MAXIMUM_DECODE_DIMENSION) {
            sample *= 2
        }
        return sample
    }

    private fun readBoundedSource(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) {
                val single = input.read()
                if (single < 0) break
                total += 1
                if (total > MAXIMUM_SOURCE_BYTES) return null
                output.write(single)
                continue
            }
            total += read
            if (total > MAXIMUM_SOURCE_BYTES) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private const val MAXIMUM_SOURCE_BYTES: Int = 16 * 1024 * 1024
    private const val MAXIMUM_SOURCE_DIMENSION: Int = 16_384
    private const val MAXIMUM_SOURCE_PIXELS: Long = 64_000_000L
    private const val MAXIMUM_DECODE_DIMENSION: Int = 4_096
    private const val MAXIMUM_OUTPUT_DIMENSION: Int = 2_048
    private const val MAXIMUM_ENCODE_ATTEMPTS: Int = 8
    private const val JPEG_INITIAL_QUALITY: Int = 88
    private const val JPEG_QUALITY_STEP: Int = 6
    private const val JPEG_MINIMUM_QUALITY: Int = 52
    private const val SCALE_RETRY_FACTOR: Float = 0.82f
    private const val READ_BUFFER_BYTES: Int = 16 * 1024
}
