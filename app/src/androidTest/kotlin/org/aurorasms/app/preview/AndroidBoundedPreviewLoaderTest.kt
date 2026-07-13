// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.preview

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aurorasms.core.model.MmsAttachmentType
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.telephony.MmsAttachmentContent
import org.aurorasms.core.telephony.MmsAttachmentContentReader
import org.aurorasms.core.telephony.MmsAttachmentDescriptor
import org.aurorasms.core.telephony.MmsAttachmentId
import org.aurorasms.core.telephony.MmsAttachmentListResult
import org.aurorasms.core.telephony.MmsAttachmentReadResult
import org.aurorasms.core.telephony.MmsAttachmentRepository
import org.aurorasms.feature.conversations.AttachmentPreviewResult
import org.aurorasms.feature.conversations.MAXIMUM_PREVIEW_ENCODED_BYTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidBoundedPreviewLoaderTest {
    @Test
    fun validPng_decodesOnceAndIsRetainedWithinBothCacheBounds() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val descriptor = descriptor(1L, "image/png")
        val repository = ByteArrayAttachmentRepository(descriptor, syntheticPng())
        val loader = AndroidBoundedPreviewLoader(repository, scope)
        try {
            val first = loader.load(descriptor) as AttachmentPreviewResult.Ready
            val second = loader.load(descriptor) as AttachmentPreviewResult.Ready

            assertEquals(40, first.preview.width)
            assertEquals(20, first.preview.height)
            assertEquals(first.preview, second.preview)
            assertEquals(1, repository.readCount.get())
            assertEquals(1, loader.retainedEntryCount())
            assertTrue(loader.retainedBytes() in 1..(16 * 1_024 * 1_024))

            loader.clear()
            assertEquals(0, loader.retainedEntryCount())
            assertEquals(0, loader.retainedBytes())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun MIME_mismatchAndBothEncodedLengthPathsFailClosed() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val jpegDescriptor = descriptor(2L, "image/jpeg")
            val mismatch = AndroidBoundedPreviewLoader(
                ByteArrayAttachmentRepository(jpegDescriptor, syntheticPng()),
                scope,
            )
            assertEquals(AttachmentPreviewResult.Malformed, mismatch.load(jpegDescriptor))

            val pngDescriptor = descriptor(3L, "image/png")
            val knownOversize = AndroidBoundedPreviewLoader(
                ByteArrayAttachmentRepository(
                    pngDescriptor,
                    syntheticPng(),
                    reportedLength = MAXIMUM_PREVIEW_ENCODED_BYTES.toLong() + 1L,
                ),
                scope,
            )
            assertEquals(AttachmentPreviewResult.EncodedInputTooLarge, knownOversize.load(pngDescriptor))

            val unknownOversize = AndroidBoundedPreviewLoader(
                GeneratedAttachmentRepository(
                    descriptor = descriptor(4L, "image/png"),
                    byteCount = MAXIMUM_PREVIEW_ENCODED_BYTES + 1,
                ),
                scope,
            )
            assertEquals(
                AttachmentPreviewResult.EncodedInputTooLarge,
                unknownOversize.load(descriptor(4L, "image/png")),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun atMostTwoProviderReadsCanEnterTheDecodeBoundary() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = BlockingUnavailableRepository()
        val loader = AndroidBoundedPreviewLoader(repository, scope)
        try {
            val results = (10L..12L).map { partId ->
                async(Dispatchers.Default) { loader.load(descriptor(partId, "image/png")) }
            }
            withTimeout(5_000L) {
                while (repository.active.get() < 2) delay(10L)
            }
            assertEquals(2, repository.active.get())
            assertEquals(2, repository.maximumActive.get())

            repository.release.complete(Unit)

            assertTrue(results.awaitAll().all { it == AttachmentPreviewResult.Unavailable })
            assertEquals(3, repository.totalReads.get())
            assertEquals(2, repository.maximumActive.get())
        } finally {
            scope.cancel()
        }
    }
}

private class ByteArrayAttachmentRepository(
    private val descriptor: MmsAttachmentDescriptor,
    private val bytes: ByteArray,
    private val reportedLength: Long? = bytes.size.toLong(),
) : MmsAttachmentRepository {
    val readCount = AtomicInteger(0)

    override suspend fun listStaticImages(providerMessageId: ProviderMessageId): MmsAttachmentListResult =
        error("Not used")

    override suspend fun <T> read(
        id: MmsAttachmentId,
        reader: MmsAttachmentContentReader<T>,
    ): MmsAttachmentReadResult<T> {
        readCount.incrementAndGet()
        return ByteArrayInputStream(bytes).use { stream ->
            MmsAttachmentReadResult.Success(
                reader.read(MmsAttachmentContent(descriptor, reportedLength, stream)),
            )
        }
    }
}

private class GeneratedAttachmentRepository(
    private val descriptor: MmsAttachmentDescriptor,
    private val byteCount: Int,
) : MmsAttachmentRepository {
    override suspend fun listStaticImages(providerMessageId: ProviderMessageId): MmsAttachmentListResult =
        error("Not used")

    override suspend fun <T> read(
        id: MmsAttachmentId,
        reader: MmsAttachmentContentReader<T>,
    ): MmsAttachmentReadResult<T> = MmsAttachmentReadResult.Success(
        reader.read(MmsAttachmentContent(descriptor, null, RepeatingInputStream(byteCount))),
    )
}

private class BlockingUnavailableRepository : MmsAttachmentRepository {
    val active = AtomicInteger(0)
    val maximumActive = AtomicInteger(0)
    val totalReads = AtomicInteger(0)
    val release = CompletableDeferred<Unit>()

    override suspend fun listStaticImages(providerMessageId: ProviderMessageId): MmsAttachmentListResult =
        error("Not used")

    override suspend fun <T> read(
        id: MmsAttachmentId,
        reader: MmsAttachmentContentReader<T>,
    ): MmsAttachmentReadResult<T> {
        totalReads.incrementAndGet()
        val current = active.incrementAndGet()
        maximumActive.updateAndGet { previous -> maxOf(previous, current) }
        return try {
            release.await()
            MmsAttachmentReadResult.Unavailable
        } finally {
            active.decrementAndGet()
        }
    }
}

private class RepeatingInputStream(
    private var remaining: Int,
) : InputStream() {
    override fun read(): Int = if (remaining <= 0) -1 else 0.also { remaining -= 1 }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0) return -1
        val read = minOf(length, remaining)
        buffer.fill(0, offset, offset + read)
        remaining -= read
        return read
    }
}

private fun descriptor(partId: Long, mimeType: String): MmsAttachmentDescriptor =
    MmsAttachmentDescriptor(
        id = MmsAttachmentId(
            providerMessageId = ProviderMessageId(ProviderKind.MMS, 1L),
            providerPartId = partId,
        ),
        type = MmsAttachmentType(mimeType, "synthetic-$partId"),
    )

private fun syntheticPng(): ByteArray {
    val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
    return ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        output.toByteArray()
    }
}
