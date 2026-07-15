// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.os.StrictMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.LinkedHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.aurorasms.core.telephony.MmsAttachmentContent
import org.aurorasms.core.telephony.MmsAttachmentDescriptor
import org.aurorasms.core.telephony.MmsAttachmentId
import org.aurorasms.core.telephony.MmsAttachmentReadResult
import org.aurorasms.core.telephony.MmsAttachmentRepository
import org.aurorasms.feature.conversations.AttachmentPreviewResult
import org.aurorasms.feature.conversations.BoundedPreviewLoader
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
    private val decodeGate: BoundedMediaDecodeGate = BoundedMediaDecodeGate(),
) : BoundedPreviewLoader {
    private val mutex = Mutex()
    private val entries = LinkedHashMap<MmsAttachmentId, StaticAttachmentPreview>(4, 0.75f, true)
    private val inFlight = mutableMapOf<MmsAttachmentId, InFlightPreviewLoad>()
    private var retainedAllocationBytes = 0
    @Volatile
    private var epoch = 0L

    override suspend fun load(descriptor: MmsAttachmentDescriptor): AttachmentPreviewResult {
        val id = descriptor.id
        var cached: StaticAttachmentPreview? = null
        var pending: InFlightPreviewLoad? = null
        var ownsPending = false
        var saturated = false
        mutex.withLock {
            cached = entries[id]
            if (cached == null) {
                pending = inFlight[id]
                if (pending == null) {
                    if (inFlight.size >= MAXIMUM_PENDING_PREVIEW_LOADS) {
                        saturated = true
                    } else {
                        val batchEpoch = epoch
                        val promise = CompletableDeferred<AttachmentPreviewResult>()
                        val producer = applicationScope.launch(start = CoroutineStart.LAZY) {
                            produce(batchEpoch, id, descriptor, promise)
                        }
                        pending = InFlightPreviewLoad(batchEpoch, promise, producer)
                        inFlight[id] = checkNotNull(pending)
                        ownsPending = true
                    }
                }
            }
        }
        cached?.let { return AttachmentPreviewResult.Ready(it) }
        if (saturated) return AttachmentPreviewResult.Unavailable
        val requested = checkNotNull(pending)
        if (ownsPending) requested.producer.start()
        val result = requested.promise.await()
        val remainsCurrent = mutex.withLock { requested.epoch == epoch }
        return if (remainsCurrent) result else AttachmentPreviewResult.Unavailable
    }

    override suspend fun clear() {
        val stale = mutex.withLock {
            epoch += 1L
            entries.clear()
            retainedAllocationBytes = 0
            val loads = inFlight.values.toList()
            inFlight.clear()
            loads
        }
        stale.forEach { load -> load.producer.cancel() }
        stale.forEach { load -> load.promise.complete(AttachmentPreviewResult.Unavailable) }
    }

    internal suspend fun retainedEntryCount(): Int = mutex.withLock { entries.size }

    internal suspend fun retainedBytes(): Int = mutex.withLock { retainedAllocationBytes }

    internal suspend fun pendingLoadCount(): Int = mutex.withLock { inFlight.size }

    private suspend fun produce(
        batchEpoch: Long,
        id: MmsAttachmentId,
        descriptor: MmsAttachmentDescriptor,
        promise: CompletableDeferred<AttachmentPreviewResult>,
    ) {
        try {
            val producerContext = currentCoroutineContext()
            val result = decodeGate.withPermit {
                producerContext.ensureActive()
                val stillCurrent = mutex.withLock {
                    inFlight[id]?.promise === promise && batchEpoch == epoch
                }
                if (stillCurrent) {
                    decode(descriptor) {
                        producerContext.ensureActive()
                        if (batchEpoch != epoch) {
                            throw kotlinx.coroutines.CancellationException("Obsolete preview generation")
                        }
                    }
                } else {
                    AttachmentPreviewResult.Unavailable
                }
            }
            val mayPublish = producerContext.isActive
            withContext(NonCancellable) {
                complete(batchEpoch, id, promise, result, mayPublish)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) {
                complete(
                    batchEpoch,
                    id,
                    promise,
                    AttachmentPreviewResult.Unavailable,
                    mayPublish = false,
                )
            }
            throw cancelled
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                complete(
                    batchEpoch,
                    id,
                    promise,
                    AttachmentPreviewResult.Unavailable,
                    mayPublish = false,
                )
            }
            throw failure
        }
    }

    private suspend fun decode(
        descriptor: MmsAttachmentDescriptor,
        ensureProducerActive: () -> Unit,
    ): AttachmentPreviewResult {
        return try {
            when (
                val result = repository.read(descriptor.id) { content ->
                    decodeContent(content, ensureProducerActive)
                }
            ) {
                is MmsAttachmentReadResult.Success -> result.value
                MmsAttachmentReadResult.NotFound -> AttachmentPreviewResult.NotFound
                MmsAttachmentReadResult.RoleRequired -> AttachmentPreviewResult.RoleRequired
                MmsAttachmentReadResult.PermissionDenied -> AttachmentPreviewResult.PermissionDenied
                MmsAttachmentReadResult.UnsupportedType -> AttachmentPreviewResult.UnsupportedType
                MmsAttachmentReadResult.Unavailable -> AttachmentPreviewResult.Unavailable
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            AttachmentPreviewResult.Unavailable
        } catch (_: SecurityException) {
            AttachmentPreviewResult.Unavailable
        } catch (_: RuntimeException) {
            AttachmentPreviewResult.Unavailable
        }
    }

    private fun decodeContent(
        content: MmsAttachmentContent,
        ensureProducerActive: () -> Unit,
    ): AttachmentPreviewResult {
        StrictMode.noteSlowCall("aurora-static-preview-decode")
        ensureProducerActive()
        val encodedLengthBytes = content.encodedLengthBytes
        if (encodedLengthBytes != null && encodedLengthBytes > MAXIMUM_PREVIEW_ENCODED_BYTES) {
            return AttachmentPreviewResult.EncodedInputTooLarge
        }
        val encoded = try {
            content.stream.readBounded(MAXIMUM_PREVIEW_ENCODED_BYTES, ensureProducerActive)
                ?: return AttachmentPreviewResult.EncodedInputTooLarge
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            return AttachmentPreviewResult.Unavailable
        } catch (_: RuntimeException) {
            return AttachmentPreviewResult.Unavailable
        }
        ensureProducerActive()
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

        ensureProducerActive()
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
            bitmap.recycle()
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
        mayPublish: Boolean,
    ) {
        val accepted = mutex.withLock {
            val stillOwned = inFlight[id]?.promise === promise
            if (stillOwned) inFlight.remove(id)
            val acceptedResult = mayPublish && stillOwned && batchEpoch == epoch
            if (acceptedResult && result is AttachmentPreviewResult.Ready) {
                entries.put(id, result.preview)?.let { replaced ->
                    retainedAllocationBytes -= replaced.allocationBytes
                }
                retainedAllocationBytes += result.preview.allocationBytes
                trimCache()
            }
            promise.complete(if (acceptedResult) result else AttachmentPreviewResult.Unavailable)
            acceptedResult
        }
        if (!accepted && result is AttachmentPreviewResult.Ready) {
            result.preview.image.asAndroidBitmap().recycle()
        }
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

private data class InFlightPreviewLoad(
    val epoch: Long,
    val promise: CompletableDeferred<AttachmentPreviewResult>,
    val producer: Job,
)

private fun java.io.InputStream.readBounded(
    maximumBytes: Int,
    ensureActive: () -> Unit,
): ByteArray? {
    val initialCapacity = minOf(maximumBytes, 32 * 1_024)
    val output = ByteArrayOutputStream(initialCapacity)
    val buffer = ByteArray(8 * 1_024)
    var total = 0
    while (true) {
        ensureActive()
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
