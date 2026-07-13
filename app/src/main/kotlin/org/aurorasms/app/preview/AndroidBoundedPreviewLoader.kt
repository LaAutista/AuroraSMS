// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.os.StrictMode
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.LinkedHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.aurorasms.core.telephony.MmsAttachmentContent
import org.aurorasms.core.telephony.MmsAttachmentDescriptor
import org.aurorasms.core.telephony.MmsAttachmentId
import org.aurorasms.core.telephony.MmsAttachmentReadResult
import org.aurorasms.core.telephony.MmsAttachmentRepository
import org.aurorasms.feature.conversations.AttachmentPreviewResult
import org.aurorasms.feature.conversations.BoundedPreviewLoader
import org.aurorasms.feature.conversations.MAXIMUM_CONCURRENT_PREVIEW_DECODES
import org.aurorasms.feature.conversations.MAXIMUM_PREVIEW_CACHE_BYTES
import org.aurorasms.feature.conversations.MAXIMUM_PREVIEW_CACHE_ENTRIES
import org.aurorasms.feature.conversations.MAXIMUM_PREVIEW_EDGE_PIXELS
import org.aurorasms.feature.conversations.MAXIMUM_PREVIEW_ENCODED_BYTES
import org.aurorasms.feature.conversations.MAXIMUM_PREVIEW_PIXELS
import org.aurorasms.feature.conversations.MAXIMUM_PENDING_PREVIEW_LOADS
import org.aurorasms.feature.conversations.StaticAttachmentPreview

class AndroidBoundedPreviewLoader(
    private val repository: MmsAttachmentRepository,
    private val applicationScope: CoroutineScope,
) : BoundedPreviewLoader {
    private val decodePermits = Semaphore(MAXIMUM_CONCURRENT_PREVIEW_DECODES)
    private val mutex = Mutex()
    private val entries = LinkedHashMap<MmsAttachmentId, StaticAttachmentPreview>(4, 0.75f, true)
    private val inFlight = mutableMapOf<MmsAttachmentId, CompletableDeferred<AttachmentPreviewResult>>()
    private var retainedAllocationBytes = 0
    private var epoch = 0L

    override suspend fun load(descriptor: MmsAttachmentDescriptor): AttachmentPreviewResult {
        val id = descriptor.id
        var cached: StaticAttachmentPreview? = null
        var pending: Deferred<AttachmentPreviewResult>? = null
        var owned: CompletableDeferred<AttachmentPreviewResult>? = null
        var saturated = false
        val batchEpoch = mutex.withLock {
            cached = entries[id]
            if (cached == null) {
                pending = inFlight[id]
                if (pending == null) {
                    if (inFlight.size >= MAXIMUM_PENDING_PREVIEW_LOADS) {
                        saturated = true
                    } else {
                        owned = CompletableDeferred()
                        pending = owned
                        inFlight[id] = checkNotNull(owned)
                    }
                }
            }
            epoch
        }
        cached?.let { return AttachmentPreviewResult.Ready(it) }
        if (saturated) return AttachmentPreviewResult.Unavailable
        checkNotNull(pending)

        owned?.let { promise ->
            applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    val result = decodePermits.withPermit { decode(descriptor) }
                    complete(batchEpoch, id, promise, result)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    withContext(NonCancellable) {
                        complete(batchEpoch, id, promise, AttachmentPreviewResult.Unavailable)
                    }
                    throw cancelled
                }
            }
        }
        return checkNotNull(pending).await()
    }

    override suspend fun clear() {
        val stale = mutex.withLock {
            epoch += 1L
            entries.clear()
            retainedAllocationBytes = 0
            inFlight.values.toList().also { inFlight.clear() }
        }
        stale.forEach { it.complete(AttachmentPreviewResult.Unavailable) }
    }

    internal suspend fun retainedEntryCount(): Int = mutex.withLock { entries.size }

    internal suspend fun retainedBytes(): Int = mutex.withLock { retainedAllocationBytes }

    private suspend fun decode(descriptor: MmsAttachmentDescriptor): AttachmentPreviewResult =
        when (val result = repository.read(descriptor.id) { content -> decodeContent(content) }) {
            is MmsAttachmentReadResult.Success -> result.value
            MmsAttachmentReadResult.NotFound -> AttachmentPreviewResult.NotFound
            MmsAttachmentReadResult.RoleRequired -> AttachmentPreviewResult.RoleRequired
            MmsAttachmentReadResult.PermissionDenied -> AttachmentPreviewResult.PermissionDenied
            MmsAttachmentReadResult.UnsupportedType -> AttachmentPreviewResult.UnsupportedType
            MmsAttachmentReadResult.Unavailable -> AttachmentPreviewResult.Unavailable
        }

    private fun decodeContent(content: MmsAttachmentContent): AttachmentPreviewResult {
        StrictMode.noteSlowCall("aurora-static-preview-decode")
        val encodedLengthBytes = content.encodedLengthBytes
        if (encodedLengthBytes != null && encodedLengthBytes > MAXIMUM_PREVIEW_ENCODED_BYTES) {
            return AttachmentPreviewResult.EncodedInputTooLarge
        }
        val encoded = try {
            content.stream.readBounded(MAXIMUM_PREVIEW_ENCODED_BYTES)
                ?: return AttachmentPreviewResult.EncodedInputTooLarge
        } catch (_: IOException) {
            return AttachmentPreviewResult.Unavailable
        } catch (_: RuntimeException) {
            return AttachmentPreviewResult.Unavailable
        }
        val format = detectEncodedImageFormat(encoded) ?: return AttachmentPreviewResult.Malformed
        if (!declaredMimeMatchesFormat(content.descriptor.type.mimeType, format)) {
            return AttachmentPreviewResult.Malformed
        }

        val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return AttachmentPreviewResult.Malformed
        if (!sourceDimensionsAreAllowed(bounds.outWidth, bounds.outHeight)) {
            return AttachmentPreviewResult.SourceDimensionsTooLarge
        }

        val bitmap = try {
            if (Build.VERSION.SDK_INT >= 28) {
                decodeWithImageDecoder(encoded, bounds.outWidth, bounds.outHeight)
            } else {
                decodeWithBitmapFactory(encoded, bounds.outWidth, bounds.outHeight)
            }
        } catch (_: SourceDimensionsRejectedException) {
            return AttachmentPreviewResult.SourceDimensionsTooLarge
        } catch (_: IOException) {
            return AttachmentPreviewResult.Malformed
        } catch (_: IllegalArgumentException) {
            return AttachmentPreviewResult.Malformed
        } catch (_: RuntimeException) {
            return AttachmentPreviewResult.Malformed
        } catch (_: OutOfMemoryError) {
            return AttachmentPreviewResult.Unavailable
        } ?: return AttachmentPreviewResult.Malformed

        val allocationBytes = bitmap.allocationByteCount
        if (
            bitmap.width !in 1..MAXIMUM_PREVIEW_EDGE_PIXELS ||
            bitmap.height !in 1..MAXIMUM_PREVIEW_EDGE_PIXELS ||
            bitmap.width.toLong() * bitmap.height.toLong() > MAXIMUM_PREVIEW_PIXELS ||
            allocationBytes !in 1..MAXIMUM_PREVIEW_CACHE_BYTES
        ) {
            return AttachmentPreviewResult.SourceDimensionsTooLarge
        }
        return AttachmentPreviewResult.Ready(
            StaticAttachmentPreview(
                attachmentId = content.descriptor.id,
                image = bitmap.asImageBitmap(),
                width = bitmap.width,
                height = bitmap.height,
                allocationBytes = allocationBytes,
            ),
        )
    }

    private suspend fun complete(
        batchEpoch: Long,
        id: MmsAttachmentId,
        promise: CompletableDeferred<AttachmentPreviewResult>,
        result: AttachmentPreviewResult,
    ) {
        val accepted = mutex.withLock {
            val stillOwned = inFlight[id] === promise
            if (stillOwned) inFlight.remove(id)
            val mayPublish = stillOwned && batchEpoch == epoch
            if (mayPublish && result is AttachmentPreviewResult.Ready) {
                entries.put(id, result.preview)?.let { replaced ->
                    retainedAllocationBytes -= replaced.allocationBytes
                }
                retainedAllocationBytes += result.preview.allocationBytes
                trimCache()
            }
            mayPublish
        }
        promise.complete(if (accepted) result else AttachmentPreviewResult.Unavailable)
    }

    private fun trimCache() {
        while (
            entries.size > MAXIMUM_PREVIEW_CACHE_ENTRIES ||
            retainedAllocationBytes > MAXIMUM_PREVIEW_CACHE_BYTES
        ) {
            val iterator = entries.entries.iterator()
            if (!iterator.hasNext()) break
            val removed = iterator.next().value
            iterator.remove()
            retainedAllocationBytes -= removed.allocationBytes
        }
    }
}

private fun java.io.InputStream.readBounded(maximumBytes: Int): ByteArray? {
    val initialCapacity = minOf(maximumBytes, 32 * 1_024)
    val output = ByteArrayOutputStream(initialCapacity)
    val buffer = ByteArray(8 * 1_024)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > maximumBytes) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun decodeWithBitmapFactory(
    encoded: ByteArray,
    sourceWidth: Int,
    sourceHeight: Int,
): Bitmap? {
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = false
        inSampleSize = powerOfTwoSampleSize(sourceWidth, sourceHeight)
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inMutable = false
    }
    return BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)
}

@androidx.annotation.RequiresApi(28)
private fun decodeWithImageDecoder(
    encoded: ByteArray,
    sourceWidth: Int,
    sourceHeight: Int,
): Bitmap {
    val target = targetDimensions(sourceWidth, sourceHeight)
    return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(encoded))) { decoder, info, _ ->
        if (!sourceDimensionsAreAllowed(info.size.width, info.size.height)) {
            throw SourceDimensionsRejectedException()
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
        decoder.setTargetSize(target.width, target.height)
        decoder.setOnPartialImageListener { false }
    }
}

private class SourceDimensionsRejectedException : RuntimeException()
