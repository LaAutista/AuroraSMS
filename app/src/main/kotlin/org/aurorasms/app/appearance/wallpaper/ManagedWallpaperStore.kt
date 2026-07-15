// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.StrictMode
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.aurorasms.app.preview.BoundedMediaDecodeGate
import org.aurorasms.app.preview.sourceDimensionsAreAllowed

internal enum class WallpaperMediaFailure {
    INVALID_SOURCE,
    UNAVAILABLE,
    UNSUPPORTED_TYPE,
    MIME_MISMATCH,
    INPUT_TOO_LARGE,
    SOURCE_DIMENSIONS_TOO_LARGE,
    MALFORMED,
    OUTPUT_TOO_LARGE,
    QUOTA_EXCEEDED,
    STORAGE_FAILURE,
}

internal sealed interface WallpaperInspectionResult {
    class Ready(
        val source: Uri,
        val preview: ImageBitmap,
        val width: Int,
        val height: Int,
    ) : WallpaperInspectionResult {
        fun release() {
            preview.recycleWallpaperBitmap()
        }

        override fun toString(): String =
            "WallpaperInspectionResult.Ready(width=$width, height=$height, source=REDACTED)"
    }

    data class Failed(val reason: WallpaperMediaFailure) : WallpaperInspectionResult
}

internal sealed interface WallpaperImportResult {
    data class Ready(
        val mediaId: String,
        val created: Boolean,
    ) : WallpaperImportResult {
        override fun toString(): String = "WallpaperImportResult.Ready(created=$created, media=REDACTED)"
    }

    data class Failed(val reason: WallpaperMediaFailure) : WallpaperImportResult
}

internal sealed interface WallpaperLoadResult {
    data class Ready(
        val mediaId: String,
        val image: ImageBitmap,
        val width: Int,
        val height: Int,
    ) : WallpaperLoadResult {
        fun release() {
            image.recycleWallpaperBitmap()
        }

        override fun toString(): String =
            "WallpaperLoadResult.Ready(width=$width, height=$height, media=REDACTED)"
    }

    data object Unavailable : WallpaperLoadResult
}

internal enum class DurableWallpaperQuotaResult {
    WITHIN_LIMIT,
    LIMIT_EXCEEDED,
    INVALID_STATE,
}

internal interface WallpaperMediaStore {
    suspend fun inspect(source: Uri): WallpaperInspectionResult

    suspend fun import(source: Uri): WallpaperImportResult

    suspend fun load(mediaId: String, preview: Boolean = false): WallpaperLoadResult

    suspend fun delete(mediaId: String): Boolean

    suspend fun reconcile(referencedMediaIds: Set<String>): Boolean

    suspend fun validateDurableQuota(
        prospectiveMediaIds: Set<String>,
    ): DurableWallpaperQuotaResult
}

/** Owns the sanitized, no-backup static wallpaper directory and never retains picker URIs. */
internal class ManagedWallpaperStore(
    context: Context,
    private val decodeGate: BoundedMediaDecodeGate,
) : WallpaperMediaStore {
    private val resolver = context.applicationContext.contentResolver
    private val directory = File(context.applicationContext.noBackupFilesDir, WALLPAPER_DIRECTORY)
    private val mutex = Mutex()

    override suspend fun inspect(source: Uri): WallpaperInspectionResult = mutex.withLock {
        var ownedBitmap: Bitmap? = null
        try {
            val result = withContext(Dispatchers.IO) {
            val content = when (val result = readSource(source)) {
                is SourceReadResult.Ready -> result
                is SourceReadResult.Failed -> return@withContext WallpaperInspectionResult.Failed(result.reason)
            }
            decodeGate.withPermit {
                when (val decoded = decodeSource(content.bytes, content.format, preview = true)) {
                    is DecodeResult.Ready -> {
                        ownedBitmap = decoded.bitmap
                        WallpaperInspectionResult.Ready(
                            source = source,
                            preview = decoded.bitmap.asImageBitmap(),
                            width = decoded.bitmap.width,
                            height = decoded.bitmap.height,
                        )
                    }
                    is DecodeResult.Failed -> WallpaperInspectionResult.Failed(decoded.reason)
                }
            }
            }
            ownedBitmap = null
            result
        } catch (cancelled: CancellationException) {
            ownedBitmap?.recycle()
            throw cancelled
        }
    }

    override suspend fun import(source: Uri): WallpaperImportResult = mutex.withLock {
        var cancellationOwnedFile: WallpaperImportResult.Ready? = null
        try {
            val result = withContext(Dispatchers.IO) {
            val content = when (val result = readSource(source)) {
                is SourceReadResult.Ready -> result
                is SourceReadResult.Failed -> return@withContext WallpaperImportResult.Failed(result.reason)
            }
            decodeGate.withPermit {
                val decoded = when (val result = decodeSource(content.bytes, content.format, preview = false)) {
                    is DecodeResult.Ready -> result.bitmap
                    is DecodeResult.Failed -> return@withPermit WallpaperImportResult.Failed(result.reason)
                }
                try {
                    val derivative = when (val encoded = encodeDerivative(decoded)) {
                        is EncodeResult.Ready -> encoded.bytes
                        is EncodeResult.Failed -> {
                            return@withPermit WallpaperImportResult.Failed(encoded.reason)
                        }
                    }
                    persistDerivative(derivative).also { persisted ->
                        if (persisted is WallpaperImportResult.Ready && persisted.created) {
                            cancellationOwnedFile = persisted
                        }
                    }
                } finally {
                    decoded.recycle()
                }
            }
            }
            cancellationOwnedFile = null
            result
        } catch (cancelled: CancellationException) {
            cancellationOwnedFile?.let { orphan ->
                withContext(NonCancellable + Dispatchers.IO) {
                    deleteManagedFile(orphan.mediaId)
                }
            }
            throw cancelled
        }
    }

    override suspend fun load(
        mediaId: String,
        preview: Boolean,
    ): WallpaperLoadResult = mutex.withLock {
        var ownedBitmap: Bitmap? = null
        try {
            val result = withContext(Dispatchers.IO) {
            val fileName = wallpaperDerivativeFileName(mediaId) ?: return@withContext WallpaperLoadResult.Unavailable
            val root = validatedDirectory(create = false) ?: return@withContext WallpaperLoadResult.Unavailable
            val file = File(root, fileName)
            if (!file.isSafeRegularChildOf(root)) return@withContext WallpaperLoadResult.Unavailable
            val length = file.length()
            if (length !in 1..MAXIMUM_WALLPAPER_DERIVATIVE_BYTES.toLong()) {
                return@withContext WallpaperLoadResult.Unavailable
            }
            val bytes = try {
                file.inputStream().use { it.readBounded(MAXIMUM_WALLPAPER_DERIVATIVE_BYTES) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: RuntimeException) {
                null
            } ?: return@withContext WallpaperLoadResult.Unavailable
            if (!wallpaperDerivativeMatches(mediaId, bytes)) {
                return@withContext WallpaperLoadResult.Unavailable
            }
            decodeGate.withPermit {
                val decoded = decodeTrustedDerivative(bytes, preview)
                    ?: return@withPermit WallpaperLoadResult.Unavailable
                ownedBitmap = decoded
                WallpaperLoadResult.Ready(
                    mediaId = mediaId,
                    image = decoded.asImageBitmap(),
                    width = decoded.width,
                    height = decoded.height,
                )
            }
            }
            ownedBitmap = null
            result
        } catch (cancelled: CancellationException) {
            ownedBitmap?.recycle()
            throw cancelled
        }
    }

    override suspend fun delete(mediaId: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) { deleteManagedFile(mediaId) }
    }

    /** Reconciles only after the caller has obtained a validated, complete durable reference set. */
    override suspend fun reconcile(referencedMediaIds: Set<String>): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (referencedMediaIds.size > MAXIMUM_WALLPAPER_FILE_COUNT) return@withContext false
            val expectedNames = referencedMediaIds.mapTo(HashSet()) { mediaId ->
                wallpaperDerivativeFileName(mediaId) ?: return@withContext false
            }
            val root = validatedDirectory(create = false) ?: return@withContext true
            val children = root.listFiles() ?: return@withContext false
            var complete = true
            children.forEach { child ->
                coroutineContext.ensureActive()
                if (Files.isSymbolicLink(child.toPath())) return@forEach
                if (!child.isFile) return@forEach
                if (child.name.startsWith(PENDING_FILE_PREFIX) || child.name !in expectedNames) {
                    val deleted = runCatching { child.delete() }.getOrDefault(false)
                    if (!deleted && child.exists()) complete = false
                }
            }
            complete
        }
    }

    override suspend fun validateDurableQuota(
        prospectiveMediaIds: Set<String>,
    ): DurableWallpaperQuotaResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (prospectiveMediaIds.size > MAXIMUM_WALLPAPER_FILE_COUNT) {
                return@withContext DurableWallpaperQuotaResult.LIMIT_EXCEEDED
            }
            if (prospectiveMediaIds.isEmpty()) {
                return@withContext DurableWallpaperQuotaResult.WITHIN_LIMIT
            }
            val root = validatedDirectory(create = false)
                ?: return@withContext DurableWallpaperQuotaResult.INVALID_STATE
            var totalBytes = 0L
            prospectiveMediaIds.forEach { mediaId ->
                coroutineContext.ensureActive()
                val fileName = wallpaperDerivativeFileName(mediaId)
                    ?: return@withContext DurableWallpaperQuotaResult.INVALID_STATE
                val file = File(root, fileName)
                if (!file.isSafeRegularChildOf(root)) {
                    return@withContext DurableWallpaperQuotaResult.INVALID_STATE
                }
                val length = file.length()
                if (
                    length !in 1..MAXIMUM_WALLPAPER_DERIVATIVE_BYTES.toLong() ||
                    totalBytes > MAXIMUM_WALLPAPER_TOTAL_BYTES - length
                ) {
                    return@withContext DurableWallpaperQuotaResult.LIMIT_EXCEEDED
                }
                val bytes = try {
                    file.inputStream().use { input ->
                        input.readBounded(MAXIMUM_WALLPAPER_DERIVATIVE_BYTES)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    null
                } catch (_: SecurityException) {
                    null
                } catch (_: RuntimeException) {
                    null
                } ?: return@withContext DurableWallpaperQuotaResult.INVALID_STATE
                if (bytes.size.toLong() != length || !wallpaperDerivativeMatches(mediaId, bytes)) {
                    return@withContext DurableWallpaperQuotaResult.INVALID_STATE
                }
                totalBytes += bytes.size
                if (totalBytes > MAXIMUM_WALLPAPER_TOTAL_BYTES) {
                    return@withContext DurableWallpaperQuotaResult.LIMIT_EXCEEDED
                }
            }
            DurableWallpaperQuotaResult.WITHIN_LIMIT
        }
    }

    private suspend fun readSource(source: Uri): SourceReadResult {
        val serialized = try {
            source.toString()
        } catch (_: OutOfMemoryError) {
            return SourceReadResult.Failed(WallpaperMediaFailure.UNAVAILABLE)
        }
        if (
            serialized.length > MAXIMUM_TRANSIENT_URI_BYTES ||
            source.scheme != android.content.ContentResolver.SCHEME_CONTENT ||
            source.authority.isNullOrBlank()
        ) {
            return SourceReadResult.Failed(WallpaperMediaFailure.INVALID_SOURCE)
        }
        val serializedBytes = try {
            serialized.encodeToByteArray()
        } catch (_: OutOfMemoryError) {
            return SourceReadResult.Failed(WallpaperMediaFailure.UNAVAILABLE)
        }
        if (serializedBytes.size > MAXIMUM_TRANSIENT_URI_BYTES) {
            return SourceReadResult.Failed(WallpaperMediaFailure.INVALID_SOURCE)
        }
        val declaredMime = try {
            resolver.getType(source)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            return SourceReadResult.Failed(WallpaperMediaFailure.UNAVAILABLE)
        } catch (_: RuntimeException) {
            return SourceReadResult.Failed(WallpaperMediaFailure.UNAVAILABLE)
        } catch (_: OutOfMemoryError) {
            return SourceReadResult.Failed(WallpaperMediaFailure.UNAVAILABLE)
        }
        if (declaredMime != null && declaredMime.length > MAXIMUM_DECLARED_MIME_TYPE_CHARACTERS) {
            return SourceReadResult.Failed(WallpaperMediaFailure.MIME_MISMATCH)
        }
        val stream = try {
            resolver.openInputStream(source)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            return SourceReadResult.Failed(WallpaperMediaFailure.UNAVAILABLE)
        } catch (_: SecurityException) {
            return SourceReadResult.Failed(WallpaperMediaFailure.UNAVAILABLE)
        } catch (_: RuntimeException) {
            return SourceReadResult.Failed(WallpaperMediaFailure.UNAVAILABLE)
        } catch (_: OutOfMemoryError) {
            return SourceReadResult.Failed(WallpaperMediaFailure.UNAVAILABLE)
        } ?: return SourceReadResult.Failed(WallpaperMediaFailure.UNAVAILABLE)
        val bytes = try {
            stream.use { input -> input.readBounded(MAXIMUM_WALLPAPER_SOURCE_BYTES) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            return SourceReadResult.Failed(WallpaperMediaFailure.UNAVAILABLE)
        } catch (_: IOException) {
            return SourceReadResult.Failed(WallpaperMediaFailure.UNAVAILABLE)
        } catch (_: RuntimeException) {
            return SourceReadResult.Failed(WallpaperMediaFailure.UNAVAILABLE)
        } catch (_: OutOfMemoryError) {
            return SourceReadResult.Failed(WallpaperMediaFailure.UNAVAILABLE)
        } ?: return SourceReadResult.Failed(WallpaperMediaFailure.INPUT_TOO_LARGE)
        val format = try {
            wallpaperSourceFormat(bytes)
        } catch (_: RuntimeException) {
            null
        } catch (_: OutOfMemoryError) {
            return SourceReadResult.Failed(WallpaperMediaFailure.UNAVAILABLE)
        } ?: return SourceReadResult.Failed(WallpaperMediaFailure.UNSUPPORTED_TYPE)
        val mimeMatches = try {
            wallpaperMimeMatches(declaredMime, format)
        } catch (_: RuntimeException) {
            false
        } catch (_: OutOfMemoryError) {
            return SourceReadResult.Failed(WallpaperMediaFailure.UNAVAILABLE)
        }
        if (!mimeMatches) {
            return SourceReadResult.Failed(WallpaperMediaFailure.MIME_MISMATCH)
        }
        return SourceReadResult.Ready(bytes, format)
    }

    private fun decodeSource(
        encoded: ByteArray,
        format: WallpaperSourceFormat,
        preview: Boolean,
    ): DecodeResult {
        StrictMode.noteSlowCall("aurora-wallpaper-source-decode")
        try {
            when (format) {
                WallpaperSourceFormat.JPEG -> when (encoded.jpegCompleteness()) {
                    JpegCompleteness.COMPLETE -> Unit
                    JpegCompleteness.SOURCE_DIMENSIONS_TOO_LARGE -> {
                        return DecodeResult.Failed(WallpaperMediaFailure.SOURCE_DIMENSIONS_TOO_LARGE)
                    }
                    JpegCompleteness.INVALID -> {
                        return DecodeResult.Failed(WallpaperMediaFailure.MALFORMED)
                    }
                }
                WallpaperSourceFormat.PNG -> when (encoded.pngCompleteness()) {
                    PngCompleteness.COMPLETE -> Unit
                    PngCompleteness.SOURCE_DIMENSIONS_TOO_LARGE -> {
                        return DecodeResult.Failed(WallpaperMediaFailure.SOURCE_DIMENSIONS_TOO_LARGE)
                    }
                    PngCompleteness.INVALID -> {
                        return DecodeResult.Failed(WallpaperMediaFailure.MALFORMED)
                    }
                }
            }
        } catch (_: RuntimeException) {
            return DecodeResult.Failed(WallpaperMediaFailure.MALFORMED)
        } catch (_: OutOfMemoryError) {
            return DecodeResult.Failed(WallpaperMediaFailure.UNAVAILABLE)
        }
        val bounds = try {
            BitmapFactory.Options().also {
                it.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(encoded, 0, encoded.size, it)
            }
        } catch (_: RuntimeException) {
            return DecodeResult.Failed(WallpaperMediaFailure.MALFORMED)
        } catch (_: OutOfMemoryError) {
            return DecodeResult.Failed(WallpaperMediaFailure.UNAVAILABLE)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return DecodeResult.Failed(WallpaperMediaFailure.MALFORMED)
        }
        if (!sourceDimensionsAreAllowed(bounds.outWidth, bounds.outHeight)) {
            return DecodeResult.Failed(WallpaperMediaFailure.SOURCE_DIMENSIONS_TOO_LARGE)
        }
        val orientation = when (format) {
            WallpaperSourceFormat.JPEG -> jpegExifOrientation(encoded)
            WallpaperSourceFormat.PNG -> pngExifOrientation(encoded)
        }
        val swapsAxes = orientation in EXIF_ORIENTATION_TRANSPOSE..EXIF_ORIENTATION_ROTATE_270
        val orientedWidth = if (swapsAxes) bounds.outHeight else bounds.outWidth
        val orientedHeight = if (swapsAxes) bounds.outWidth else bounds.outHeight
        val target = boundedTarget(
            width = orientedWidth,
            height = orientedHeight,
            maximumEdge = if (preview) {
                MAXIMUM_WALLPAPER_PREVIEW_EDGE_PIXELS
            } else {
                MAXIMUM_WALLPAPER_EDGE_PIXELS
            },
            maximumPixels = if (preview) {
                MAXIMUM_WALLPAPER_PREVIEW_PIXELS
            } else {
                MAXIMUM_WALLPAPER_PIXELS
            },
        )
        val rawTarget = if (swapsAxes) {
            BitmapTarget(width = target.height, height = target.width)
        } else {
            target
        }
        var ownedDuringDecode: Bitmap? = null
        val bitmap = try {
            val decoded = if (Build.VERSION.SDK_INT >= 28 && format == WallpaperSourceFormat.JPEG) {
                decodeWithImageDecoder(encoded, target)
            } else {
                decodeWithBitmapFactory(encoded, rawTarget, orientation)
            }
            ownedDuringDecode = decoded
            decoded?.toSrgbArgb8888()?.also { normalized ->
                ownedDuringDecode = normalized
            }
        } catch (_: IOException) {
            ownedDuringDecode?.recycle()
            null
        } catch (_: IllegalArgumentException) {
            ownedDuringDecode?.recycle()
            null
        } catch (_: RuntimeException) {
            ownedDuringDecode?.recycle()
            null
        } catch (_: OutOfMemoryError) {
            ownedDuringDecode?.recycle()
            return DecodeResult.Failed(WallpaperMediaFailure.UNAVAILABLE)
        } ?: return DecodeResult.Failed(WallpaperMediaFailure.MALFORMED)
        ownedDuringDecode = null
        val maximumAllocation = if (preview) {
            MAXIMUM_WALLPAPER_PREVIEW_ALLOCATION_BYTES
        } else {
            MAXIMUM_WALLPAPER_ALLOCATION_BYTES
        }
        val maximumEdge = if (preview) {
            MAXIMUM_WALLPAPER_PREVIEW_EDGE_PIXELS
        } else {
            MAXIMUM_WALLPAPER_EDGE_PIXELS
        }
        val maximumPixels = if (preview) {
            MAXIMUM_WALLPAPER_PREVIEW_PIXELS
        } else {
            MAXIMUM_WALLPAPER_PIXELS
        }
        if (
            bitmap.width !in 1..maximumEdge ||
            bitmap.height !in 1..maximumEdge ||
            bitmap.width.toLong() * bitmap.height.toLong() > maximumPixels ||
            bitmap.allocationByteCount !in 1..maximumAllocation
        ) {
            bitmap.recycle()
            return DecodeResult.Failed(WallpaperMediaFailure.SOURCE_DIMENSIONS_TOO_LARGE)
        }
        return DecodeResult.Ready(bitmap)
    }

    private fun encodeDerivative(bitmap: Bitmap): EncodeResult {
        StrictMode.noteSlowCall("aurora-wallpaper-derivative-encode")
        return try {
            val output = ByteArrayOutputStream(
                minOf(MAXIMUM_WALLPAPER_DERIVATIVE_BYTES, 64 * 1_024),
            )
            val format = if (Build.VERSION.SDK_INT >= 30) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            if (!bitmap.compress(format, WALLPAPER_WEBP_QUALITY, BoundedOutputStream(output))) {
                return EncodeResult.Failed(WallpaperMediaFailure.OUTPUT_TOO_LARGE)
            }
            val bytes = output.toByteArray()
            if (bytes.size !in 1..MAXIMUM_WALLPAPER_DERIVATIVE_BYTES || !bytes.isStaticWebp()) {
                EncodeResult.Failed(WallpaperMediaFailure.OUTPUT_TOO_LARGE)
            } else {
                EncodeResult.Ready(bytes)
            }
        } catch (_: SizeLimitExceededException) {
            EncodeResult.Failed(WallpaperMediaFailure.OUTPUT_TOO_LARGE)
        } catch (_: RuntimeException) {
            EncodeResult.Failed(WallpaperMediaFailure.OUTPUT_TOO_LARGE)
        } catch (_: OutOfMemoryError) {
            EncodeResult.Failed(WallpaperMediaFailure.UNAVAILABLE)
        }
    }

    private suspend fun persistDerivative(bytes: ByteArray): WallpaperImportResult {
        val mediaId = wallpaperMediaId(bytes)
        val fileName = wallpaperDerivativeFileName(mediaId)
            ?: return WallpaperImportResult.Failed(WallpaperMediaFailure.STORAGE_FAILURE)
        val root = validatedDirectory(create = true)
            ?: return WallpaperImportResult.Failed(WallpaperMediaFailure.STORAGE_FAILURE)
        val finalFile = File(root, fileName)
        if (finalFile.exists()) {
            if (!finalFile.isSafeRegularChildOf(root)) {
                return WallpaperImportResult.Failed(WallpaperMediaFailure.STORAGE_FAILURE)
            }
            val existing = try {
                finalFile.inputStream().use { it.readBounded(MAXIMUM_WALLPAPER_DERIVATIVE_BYTES) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: RuntimeException) {
                null
            }
            return if (existing != null && wallpaperDerivativeMatches(mediaId, existing)) {
                WallpaperImportResult.Ready(mediaId = mediaId, created = false)
            } else {
                WallpaperImportResult.Failed(WallpaperMediaFailure.STORAGE_FAILURE)
            }
        }
        val children = root.listFiles()
            ?: return WallpaperImportResult.Failed(WallpaperMediaFailure.STORAGE_FAILURE)
        val regularFiles = children.filter { child ->
            !Files.isSymbolicLink(child.toPath()) && child.isFile &&
                !child.name.startsWith(PENDING_FILE_PREFIX)
        }
        var totalBytes = 0L
        regularFiles.forEach { file ->
            val length = file.length()
            if (
                length !in 1..MAXIMUM_WALLPAPER_DERIVATIVE_BYTES.toLong() ||
                totalBytes > MAXIMUM_WALLPAPER_STAGED_TOTAL_BYTES - length
            ) {
                return WallpaperImportResult.Failed(WallpaperMediaFailure.STORAGE_FAILURE)
            }
            totalBytes += length
        }
        if (
            regularFiles.size >= MAXIMUM_WALLPAPER_STAGED_FILE_COUNT ||
            totalBytes > MAXIMUM_WALLPAPER_STAGED_TOTAL_BYTES - bytes.size
        ) {
            return WallpaperImportResult.Failed(WallpaperMediaFailure.QUOTA_EXCEEDED)
        }

        val pending = File(root, "$PENDING_FILE_PREFIX${UUID.randomUUID()}")
        try {
            FileOutputStream(pending).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            val verifiedPending = pending.inputStream().use { input ->
                input.readBounded(MAXIMUM_WALLPAPER_DERIVATIVE_BYTES)
            }
            if (
                verifiedPending == null ||
                !verifiedPending.contentEquals(bytes) ||
                !wallpaperDerivativeMatches(mediaId, verifiedPending)
            ) {
                return WallpaperImportResult.Failed(WallpaperMediaFailure.STORAGE_FAILURE)
            }
            if (!pending.renameTo(finalFile)) {
                return WallpaperImportResult.Failed(WallpaperMediaFailure.STORAGE_FAILURE)
            }
            if (!syncDirectory(root)) {
                runCatching { finalFile.delete() }
                return WallpaperImportResult.Failed(WallpaperMediaFailure.STORAGE_FAILURE)
            }
            return WallpaperImportResult.Ready(mediaId = mediaId, created = true)
        } catch (_: IOException) {
            return WallpaperImportResult.Failed(WallpaperMediaFailure.STORAGE_FAILURE)
        } catch (_: SecurityException) {
            return WallpaperImportResult.Failed(WallpaperMediaFailure.STORAGE_FAILURE)
        } finally {
            if (pending.exists()) runCatching { pending.delete() }
        }
    }

    private fun deleteManagedFile(mediaId: String): Boolean {
        val fileName = wallpaperDerivativeFileName(mediaId) ?: return false
        val root = validatedDirectory(create = false) ?: return false
        val file = File(root, fileName)
        if (!file.isSafeRegularChildOf(root)) return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    private fun validatedDirectory(create: Boolean): File? {
        if (!directory.exists() && create && !directory.mkdirs()) return null
        if (!directory.exists()) return null
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) return null
        val noBackupRoot = directory.parentFile?.parentFile ?: return null
        return directory.takeIf { candidate ->
            runCatching { candidate.canonicalFile.toPath().startsWith(noBackupRoot.canonicalFile.toPath()) }
                .getOrDefault(false)
        }
    }
}

private sealed interface SourceReadResult {
    data class Ready(
        val bytes: ByteArray,
        val format: WallpaperSourceFormat,
    ) : SourceReadResult

    data class Failed(val reason: WallpaperMediaFailure) : SourceReadResult
}

private sealed interface DecodeResult {
    data class Ready(val bitmap: Bitmap) : DecodeResult
    data class Failed(val reason: WallpaperMediaFailure) : DecodeResult
}

private sealed interface EncodeResult {
    data class Ready(val bytes: ByteArray) : EncodeResult
    data class Failed(val reason: WallpaperMediaFailure) : EncodeResult
}

private data class BitmapTarget(val width: Int, val height: Int)

private fun boundedTarget(
    width: Int,
    height: Int,
    maximumEdge: Int,
    maximumPixels: Long,
): BitmapTarget {
    val edgeScale = maximumEdge.toDouble() / maxOf(width, height).toDouble()
    val pixelScale = sqrt(maximumPixels.toDouble() / (width.toLong() * height.toLong()).toDouble())
    val scale = min(1.0, min(edgeScale, pixelScale))
    return BitmapTarget(
        width = maxOf(1, floor(width * scale).toInt()),
        height = maxOf(1, floor(height * scale).toInt()),
    )
}

@androidx.annotation.RequiresApi(28)
private fun decodeWithImageDecoder(encoded: ByteArray, target: BitmapTarget): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(encoded))) { decoder, info, _ ->
        if (!sourceDimensionsAreAllowed(info.size.width, info.size.height)) {
            throw IllegalArgumentException("Wallpaper source dimensions changed during decode")
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        decoder.setTargetSize(target.width, target.height)
        decoder.setOnPartialImageListener { false }
    }

private fun decodeWithBitmapFactory(
    encoded: ByteArray,
    target: BitmapTarget,
    orientation: Int,
): Bitmap? {
    val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
    var sample = 1
    while (
        divideRoundUp(bounds.outWidth, sample) > target.width ||
        divideRoundUp(bounds.outHeight, sample) > target.height
    ) {
        sample *= 2
    }
    val decoded = BitmapFactory.decodeByteArray(
        encoded,
        0,
        encoded.size,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        },
    ) ?: return null
    var owned: Bitmap? = decoded
    try {
        val oriented = orientLegacyBitmap(
            bitmap = decoded,
            orientation = orientation,
        )
        owned = oriented
        val orientedTarget = boundedTarget(
            width = oriented.width,
            height = oriented.height,
            maximumEdge = maxOf(target.width, target.height),
            maximumPixels = target.width.toLong() * target.height.toLong(),
        )
        val scaled = if (
            oriented.width == orientedTarget.width &&
            oriented.height == orientedTarget.height
        ) {
            oriented
        } else {
            oriented.scale(orientedTarget.width, orientedTarget.height, filter = true).also {
                if (oriented !== decoded) oriented.recycle()
                decoded.recycle()
            }
        }
        owned = null
        return scaled
    } catch (failure: RuntimeException) {
        owned?.recycle()
        throw failure
    } catch (failure: OutOfMemoryError) {
        owned?.recycle()
        throw failure
    }
}

internal fun orientLegacyBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        EXIF_ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        EXIF_ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        EXIF_ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        EXIF_ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        EXIF_ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        EXIF_ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        EXIF_ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
        if (it !== bitmap) bitmap.recycle()
    }
}

/** Reads only the bounded TIFF orientation scalar; no offsets escape the APP1 segment. */
internal fun jpegExifOrientation(encoded: ByteArray): Int {
    if (encoded.size < 4 || encoded[0].unsigned() != 0xff || encoded[1].unsigned() != 0xd8) {
        return EXIF_ORIENTATION_NORMAL
    }
    var offset = 2
    while (offset + 4 <= encoded.size) {
        while (offset < encoded.size && encoded[offset].unsigned() == 0xff) offset += 1
        if (offset >= encoded.size) break
        val marker = encoded[offset].unsigned()
        offset += 1
        if (marker == JPEG_MARKER_EOI || marker == JPEG_MARKER_SOS) break
        if (marker == JPEG_MARKER_TEM || marker in JPEG_MARKER_RST_FIRST..JPEG_MARKER_RST_LAST) {
            continue
        }
        if (offset + 2 > encoded.size) break
        val segmentLength = encoded.readUnsignedShortBigEndian(offset)
        if (segmentLength < 2) break
        val payloadOffset = offset + 2
        val segmentEnd = offset.toLong() + segmentLength.toLong()
        if (segmentEnd > encoded.size.toLong()) break
        if (marker == JPEG_MARKER_APP1) {
            readExifOrientationFromApp1(
                encoded = encoded,
                payloadOffset = payloadOffset,
                payloadEnd = segmentEnd.toInt(),
            )?.let { return it }
        }
        offset = segmentEnd.toInt()
    }
    return EXIF_ORIENTATION_NORMAL
}

private fun readExifOrientationFromApp1(
    encoded: ByteArray,
    payloadOffset: Int,
    payloadEnd: Int,
): Int? {
    if (payloadOffset < 0 || payloadEnd > encoded.size || payloadEnd - payloadOffset < 14) return null
    if (!encoded.asciiAt(payloadOffset, "Exif") || encoded[payloadOffset + 4] != 0.toByte() ||
        encoded[payloadOffset + 5] != 0.toByte()
    ) {
        return null
    }
    return readTiffOrientation(encoded, payloadOffset + 6, payloadEnd)
}

/** Reads a PNG eXIf TIFF payload without admitting any other metadata surface. */
internal fun pngExifOrientation(encoded: ByteArray): Int {
    if (
        encoded.size < PNG_SIGNATURE_BYTES ||
        !encoded.copyOfRange(0, PNG_SIGNATURE_BYTES).contentEquals(PNG_SIGNATURE)
    ) {
        return EXIF_ORIENTATION_NORMAL
    }
    var offset = PNG_SIGNATURE_BYTES
    while (offset + PNG_CHUNK_OVERHEAD_BYTES <= encoded.size) {
        val length = encoded.readUnsignedIntBigEndian(offset)
        if (length == null || length > Int.MAX_VALUE.toLong()) return EXIF_ORIENTATION_NORMAL
        val payloadOffset = offset + 8
        val payloadEndLong = payloadOffset.toLong() + length
        val chunkEndLong = payloadEndLong + 4L
        if (payloadEndLong < payloadOffset || chunkEndLong > encoded.size.toLong()) {
            return EXIF_ORIENTATION_NORMAL
        }
        val payloadEnd = payloadEndLong.toInt()
        when {
            encoded.asciiAt(offset + 4, "eXIf") -> {
                return readTiffOrientation(encoded, payloadOffset, payloadEnd)
                    ?: EXIF_ORIENTATION_NORMAL
            }
            encoded.asciiAt(offset + 4, "IEND") -> return EXIF_ORIENTATION_NORMAL
        }
        offset = chunkEndLong.toInt()
    }
    return EXIF_ORIENTATION_NORMAL
}

private fun readTiffOrientation(
    encoded: ByteArray,
    tiff: Int,
    payloadEnd: Int,
): Int? {
    if (tiff < 0 || payloadEnd > encoded.size || tiff + 8 > payloadEnd) return null
    val littleEndian = when {
        encoded.asciiAt(tiff, "II") -> true
        encoded.asciiAt(tiff, "MM") -> false
        else -> return null
    }
    if (encoded.readUnsignedShort(tiff + 2, littleEndian, payloadEnd) != TIFF_MAGIC) return null
    val ifdRelative = encoded.readUnsignedInt(tiff + 4, littleEndian, payloadEnd) ?: return null
    if (ifdRelative > Int.MAX_VALUE.toLong()) return null
    val ifdOffsetLong = tiff.toLong() + ifdRelative
    if (ifdOffsetLong < tiff.toLong() || ifdOffsetLong + 2L > payloadEnd.toLong()) return null
    val ifdOffset = ifdOffsetLong.toInt()
    val entryCount = encoded.readUnsignedShort(ifdOffset, littleEndian, payloadEnd)
    val maximumEntriesInSegment = ((payloadEnd - (ifdOffset + 2)) / TIFF_IFD_ENTRY_BYTES)
        .coerceAtLeast(0)
    if (entryCount !in 0..maximumEntriesInSegment) return null
    var entryOffset = ifdOffset + 2
    repeat(entryCount) {
        if (entryOffset.toLong() + TIFF_IFD_ENTRY_BYTES > payloadEnd.toLong()) return null
        val tag = encoded.readUnsignedShort(entryOffset, littleEndian, payloadEnd)
        val type = encoded.readUnsignedShort(entryOffset + 2, littleEndian, payloadEnd)
        val count = encoded.readUnsignedInt(entryOffset + 4, littleEndian, payloadEnd) ?: return null
        if (tag == TIFF_ORIENTATION_TAG && type == TIFF_TYPE_SHORT && count == 1L) {
            val orientation = encoded.readUnsignedShort(entryOffset + 8, littleEndian, payloadEnd)
            return orientation.takeIf { it in EXIF_ORIENTATION_NORMAL..EXIF_ORIENTATION_ROTATE_270 }
        }
        entryOffset += TIFF_IFD_ENTRY_BYTES
    }
    return null
}

private fun ByteArray.readUnsignedIntBigEndian(offset: Int): Long? {
    if (offset < 0 || offset + 4 > size) return null
    return (this[offset].unsigned().toLong() shl 24) or
        (this[offset + 1].unsigned().toLong() shl 16) or
        (this[offset + 2].unsigned().toLong() shl 8) or
        this[offset + 3].unsigned().toLong()
}

private fun ByteArray.readUnsignedShortBigEndian(offset: Int): Int =
    (this[offset].unsigned() shl 8) or this[offset + 1].unsigned()

private fun ByteArray.readUnsignedShort(
    offset: Int,
    littleEndian: Boolean,
    endExclusive: Int,
): Int {
    if (offset < 0 || offset + 2 > endExclusive || endExclusive > size) return -1
    return if (littleEndian) {
        this[offset].unsigned() or (this[offset + 1].unsigned() shl 8)
    } else {
        (this[offset].unsigned() shl 8) or this[offset + 1].unsigned()
    }
}

private fun ByteArray.readUnsignedInt(
    offset: Int,
    littleEndian: Boolean,
    endExclusive: Int,
): Long? {
    if (offset < 0 || offset + 4 > endExclusive || endExclusive > size) return null
    return if (littleEndian) {
        this[offset].unsigned().toLong() or
            (this[offset + 1].unsigned().toLong() shl 8) or
            (this[offset + 2].unsigned().toLong() shl 16) or
            (this[offset + 3].unsigned().toLong() shl 24)
    } else {
        (this[offset].unsigned().toLong() shl 24) or
            (this[offset + 1].unsigned().toLong() shl 16) or
            (this[offset + 2].unsigned().toLong() shl 8) or
            this[offset + 3].unsigned().toLong()
    }
}

private fun ByteArray.asciiAt(offset: Int, value: String): Boolean =
    offset >= 0 && size >= offset + value.length && value.indices.all { index ->
        this[offset + index].unsigned() == value[index].code
    }

private fun Byte.unsigned(): Int = toInt() and 0xff

private fun Bitmap.toSrgbArgb8888(): Bitmap {
    if (config == Bitmap.Config.ARGB_8888 && colorSpace?.isSrgb == true) return this
    val normalized = createBitmap(width, height, Bitmap.Config.ARGB_8888)
    return try {
        Canvas(normalized).drawBitmap(this, 0f, 0f, null)
        recycle()
        normalized
    } catch (failure: RuntimeException) {
        normalized.recycle()
        throw failure
    } catch (failure: OutOfMemoryError) {
        normalized.recycle()
        throw failure
    }
}

private fun decodeTrustedDerivative(encoded: ByteArray, preview: Boolean): Bitmap? {
    StrictMode.noteSlowCall("aurora-wallpaper-render-decode")
    val bounds = try {
        BitmapFactory.Options().also {
            it.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(encoded, 0, encoded.size, it)
        }
    } catch (_: RuntimeException) {
        return null
    } catch (_: OutOfMemoryError) {
        return null
    }
    if (
        bounds.outWidth !in 1..MAXIMUM_WALLPAPER_EDGE_PIXELS ||
        bounds.outHeight !in 1..MAXIMUM_WALLPAPER_EDGE_PIXELS ||
        bounds.outWidth.toLong() * bounds.outHeight.toLong() > MAXIMUM_WALLPAPER_PIXELS
    ) {
        return null
    }
    val sampleSize = if (preview) {
        var sample = 1
        while (
            divideRoundUp(bounds.outWidth, sample) > MAXIMUM_WALLPAPER_PREVIEW_EDGE_PIXELS ||
            divideRoundUp(bounds.outHeight, sample) > MAXIMUM_WALLPAPER_PREVIEW_EDGE_PIXELS ||
            divideRoundUp(bounds.outWidth, sample).toLong() *
            divideRoundUp(bounds.outHeight, sample).toLong() > MAXIMUM_WALLPAPER_PREVIEW_PIXELS
        ) {
            sample *= 2
        }
        sample
    } else {
        1
    }
    val bitmap = try {
        BitmapFactory.decodeByteArray(
            encoded,
            0,
            encoded.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = false
            },
        )
    } catch (_: RuntimeException) {
        null
    } catch (_: OutOfMemoryError) {
        null
    } ?: return null
    val maximumEdge = if (preview) {
        MAXIMUM_WALLPAPER_PREVIEW_EDGE_PIXELS
    } else {
        MAXIMUM_WALLPAPER_EDGE_PIXELS
    }
    val maximumPixels = if (preview) {
        MAXIMUM_WALLPAPER_PREVIEW_PIXELS
    } else {
        MAXIMUM_WALLPAPER_PIXELS
    }
    val maximumAllocation = if (preview) {
        MAXIMUM_WALLPAPER_PREVIEW_ALLOCATION_BYTES
    } else {
        MAXIMUM_WALLPAPER_ALLOCATION_BYTES
    }
    if (
        bitmap.width !in 1..maximumEdge ||
        bitmap.height !in 1..maximumEdge ||
        bitmap.width.toLong() * bitmap.height.toLong() > maximumPixels ||
        bitmap.allocationByteCount !in 1..maximumAllocation
    ) {
        bitmap.recycle()
        return null
    }
    return bitmap
}

private suspend fun java.io.InputStream.readBounded(maximumBytes: Int): ByteArray? {
    val output = ByteArrayOutputStream(minOf(maximumBytes, 32 * 1_024))
    val buffer = ByteArray(8 * 1_024)
    var total = 0
    while (true) {
        coroutineContext.ensureActive()
        val read = read(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > maximumBytes) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private class BoundedOutputStream(
    delegate: OutputStream,
) : FilterOutputStream(delegate) {
    private var count = 0

    override fun write(value: Int) {
        reserve(1)
        super.write(value)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        reserve(length)
        out.write(buffer, offset, length)
    }

    private fun reserve(length: Int) {
        if (length < 0 || count > MAXIMUM_WALLPAPER_DERIVATIVE_BYTES - length) {
            throw SizeLimitExceededException()
        }
        count += length
    }
}

private class SizeLimitExceededException : IOException()

private fun File.isSafeRegularChildOf(root: File): Boolean =
    isFile &&
        !Files.isSymbolicLink(toPath()) &&
        runCatching { canonicalFile.parentFile == root.canonicalFile }.getOrDefault(false)

private fun syncDirectory(directory: File): Boolean {
    var descriptor: java.io.FileDescriptor? = null
    return try {
        descriptor = Os.open(
            directory.absolutePath,
            OsConstants.O_RDONLY,
            0,
        )
        Os.fsync(descriptor)
        true
    } catch (_: ErrnoException) {
        false
    } catch (_: SecurityException) {
        false
    } finally {
        descriptor?.let { opened -> runCatching { Os.close(opened) } }
    }
}

private fun divideRoundUp(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

internal fun ImageBitmap.recycleWallpaperBitmap() {
    runCatching {
        asAndroidBitmap().takeUnless(Bitmap::isRecycled)?.recycle()
    }
}

private const val WALLPAPER_DIRECTORY: String = "appearance/wallpapers"
private const val PENDING_FILE_PREFIX: String = ".pending-"
private const val WALLPAPER_WEBP_QUALITY: Int = 95
private const val JPEG_MARKER_TEM: Int = 0x01
private const val JPEG_MARKER_RST_FIRST: Int = 0xd0
private const val JPEG_MARKER_RST_LAST: Int = 0xd7
private const val JPEG_MARKER_SOS: Int = 0xda
private const val JPEG_MARKER_EOI: Int = 0xd9
private const val JPEG_MARKER_APP1: Int = 0xe1
private const val TIFF_MAGIC: Int = 42
private const val TIFF_ORIENTATION_TAG: Int = 0x0112
private const val TIFF_TYPE_SHORT: Int = 3
private const val TIFF_IFD_ENTRY_BYTES: Int = 12
private const val EXIF_ORIENTATION_NORMAL: Int = 1
private const val EXIF_ORIENTATION_FLIP_HORIZONTAL: Int = 2
private const val EXIF_ORIENTATION_ROTATE_180: Int = 3
private const val EXIF_ORIENTATION_FLIP_VERTICAL: Int = 4
private const val EXIF_ORIENTATION_TRANSPOSE: Int = 5
private const val EXIF_ORIENTATION_ROTATE_90: Int = 6
private const val EXIF_ORIENTATION_TRANSVERSE: Int = 7
private const val EXIF_ORIENTATION_ROTATE_270: Int = 8
private const val PNG_SIGNATURE_BYTES: Int = 8
private const val PNG_CHUNK_OVERHEAD_BYTES: Int = 12
private val PNG_SIGNATURE: ByteArray = byteArrayOf(
    0x89.toByte(),
    0x50,
    0x4e,
    0x47,
    0x0d,
    0x0a,
    0x1a,
    0x0a,
)
