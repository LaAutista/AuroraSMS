// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.aurorasms.app.preview.BoundedMediaDecodeGate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedWallpaperStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = ManagedWallpaperStore(context, BoundedMediaDecodeGate())

    @Test
    fun validPngIsPreviewedSanitizedDeduplicatedAndLoaded() = runBlocking {
        store.reconcile(emptySet())
        val source = testUri("valid.png")

        val inspection = store.inspect(source) as WallpaperInspectionResult.Ready
        assertEquals(40, inspection.width)
        assertEquals(20, inspection.height)
        assertEquals(source, inspection.source)

        val first = store.import(source) as WallpaperImportResult.Ready
        val second = store.import(source) as WallpaperImportResult.Ready
        assertTrue(first.created)
        assertFalse(second.created)
        assertEquals(first.mediaId, second.mediaId)

        val loaded = store.load(first.mediaId) as WallpaperLoadResult.Ready
        assertEquals(first.mediaId, loaded.mediaId)
        assertEquals(40, loaded.width)
        assertEquals(20, loaded.height)

        assertTrue(store.reconcile(setOf(first.mediaId)))
        assertTrue(store.load(first.mediaId) is WallpaperLoadResult.Ready)
        assertTrue(store.reconcile(emptySet()))
        assertEquals(WallpaperLoadResult.Unavailable, store.load(first.mediaId))
    }

    @Test
    fun derivativeUsesExactNoBackupNameAndStripsSourceMetadata() = runBlocking {
        store.reconcile(emptySet())
        val source = testUri("metadata.jpeg")
        val sourceBytes = context.contentResolver.openInputStream(source)!!.use { it.readBytes() }
        assertTrue(sourceBytes.containsAscii(WallpaperTestContentProvider.METADATA_SENTINEL))
        assertTrue(sourceBytes.containsAscii("Exif"))
        assertTrue(sourceBytes.containsAscii("http://ns.adobe.com/xap/1.0/"))

        val imported = store.import(source) as WallpaperImportResult.Ready
        val expectedName = wallpaperDerivativeFileName(imported.mediaId)!!
        val expectedDirectory = File(context.noBackupFilesDir, "appearance/wallpapers")
        val derivative = File(expectedDirectory, expectedName)
        assertTrue(derivative.isFile)
        assertEquals(expectedDirectory.canonicalFile, derivative.parentFile!!.canonicalFile)
        assertEquals("v1-${imported.mediaId.removePrefix(WALLPAPER_MEDIA_ID_PREFIX)}.webp", derivative.name)
        assertEquals(setOf(expectedName), expectedDirectory.list()!!.toSet())

        val bytes = derivative.readBytes()
        assertTrue(bytes.size in 1..MAXIMUM_WALLPAPER_DERIVATIVE_BYTES)
        assertTrue(bytes.isStaticWebp())
        assertEquals(imported.mediaId, wallpaperMediaId(bytes))
        assertTrue(wallpaperDerivativeMatches(imported.mediaId, bytes))
        assertFalse(bytes.containsAscii(WallpaperTestContentProvider.METADATA_SENTINEL))
        assertFalse(bytes.containsAscii("Exif"))
        assertFalse(bytes.containsAscii("http://ns.adobe.com/xap/1.0/"))
        assertFalse(webpChunkTypes(bytes).any { it == "EXIF" || it == "XMP " || it == "ANIM" || it == "ANMF" })

        val loaded = store.load(imported.mediaId) as WallpaperLoadResult.Ready
        try {
            assertEquals(40, loaded.width)
            assertEquals(20, loaded.height)
        } finally {
            loaded.release()
            store.reconcile(emptySet())
        }
    }

    @Test
    fun loadRejectsStaticContainerHeaderHashAndAnimatedTampering() = runBlocking {
        store.reconcile(emptySet())
        val malformedHeader = encodedWebp(width = 4, height = 4).apply {
            this[4] = (this[4].toInt() xor 0x01).toByte()
        }
        val malformedId = installManagedBytes(malformedHeader)
        assertFalse(malformedHeader.isStaticWebp())
        assertEquals(WallpaperLoadResult.Unavailable, store.load(malformedId))

        store.reconcile(emptySet())
        val animated = animatedWebpEnvelope()
        val animatedId = installManagedBytes(animated)
        assertFalse(animated.isStaticWebp())
        assertEquals(WallpaperLoadResult.Unavailable, store.load(animatedId))

        store.reconcile(emptySet())
        val imported = store.import(testUri("valid.png")) as WallpaperImportResult.Ready
        val derivative = managedFile(imported.mediaId)
        val originalBytes = derivative.readBytes()
        val hashTampered = originalBytes.copyOf().apply {
            this[lastIndex] = (this[lastIndex].toInt() xor 0x01).toByte()
        }
        assertTrue(hashTampered.isStaticWebp())
        assertNotEquals(imported.mediaId, wallpaperMediaId(hashTampered))
        derivative.writeBytes(hashTampered)
        assertEquals(WallpaperLoadResult.Unavailable, store.load(imported.mediaId))
        store.reconcile(emptySet())
        Unit
    }

    @Test
    fun trustedDerivativeRejectsOnePixelEdgeAndAllocationOverages() = runBlocking {
        store.reconcile(emptySet())
        val edgeOverage = encodedWebp(width = MAXIMUM_WALLPAPER_EDGE_PIXELS + 1, height = 1)
        assertTrue(edgeOverage.isStaticWebp())
        assertTrue(edgeOverage.size <= MAXIMUM_WALLPAPER_DERIVATIVE_BYTES)
        val edgeMediaId = installManagedBytes(edgeOverage)
        assertEquals(WallpaperLoadResult.Unavailable, store.load(edgeMediaId))

        store.reconcile(emptySet())
        val allocationHeight = MAXIMUM_WALLPAPER_EDGE_PIXELS + 1
        val allocationOverage = encodedWebp(
            width = MAXIMUM_WALLPAPER_EDGE_PIXELS,
            height = allocationHeight,
        )
        assertTrue(
            MAXIMUM_WALLPAPER_EDGE_PIXELS.toLong() * allocationHeight * Int.SIZE_BYTES >
                MAXIMUM_WALLPAPER_ALLOCATION_BYTES,
        )
        assertTrue(allocationOverage.isStaticWebp())
        assertTrue(allocationOverage.size <= MAXIMUM_WALLPAPER_DERIVATIVE_BYTES)
        val allocationMediaId = installManagedBytes(allocationOverage)
        assertEquals(WallpaperLoadResult.Unavailable, store.load(allocationMediaId))
        store.reconcile(emptySet())
        Unit
    }

    @Test
    fun maximumPixelEntropyImportExercisesTheRealEncoderWithinEveryOutputBound() = runBlocking {
        store.reconcile(emptySet())
        val imported = store.import(testUri("maximum-noise.png")) as WallpaperImportResult.Ready
        val derivative = managedFile(imported.mediaId)
        assertTrue(derivative.length() > 1L * 1_024L * 1_024L)
        assertTrue(derivative.length() <= MAXIMUM_WALLPAPER_DERIVATIVE_BYTES)
        assertTrue(derivative.readBytes().isStaticWebp())

        val loaded = store.load(imported.mediaId) as WallpaperLoadResult.Ready
        try {
            val bitmap = loaded.image.asAndroidBitmap()
            assertEquals(MAXIMUM_WALLPAPER_EDGE_PIXELS, loaded.width)
            assertEquals(MAXIMUM_WALLPAPER_EDGE_PIXELS, loaded.height)
            assertEquals(MAXIMUM_WALLPAPER_PIXELS, loaded.width.toLong() * loaded.height)
            assertEquals(MAXIMUM_WALLPAPER_ALLOCATION_BYTES, bitmap.allocationByteCount)
        } finally {
            loaded.release()
            store.reconcile(emptySet())
        }
    }

    @Test
    fun exactEightMibStaticContainerPassesQuotaAndSmallestValidOverageFails() = runBlocking {
        store.reconcile(emptySet())
        val encoded = encodedWebp(width = 8, height = 8)
        val exactBoundary = padStaticWebp(encoded, MAXIMUM_WALLPAPER_DERIVATIVE_BYTES)
        assertEquals(MAXIMUM_WALLPAPER_DERIVATIVE_BYTES, exactBoundary.size)
        assertTrue(exactBoundary.isStaticWebp())
        val exactMediaId = installManagedBytes(exactBoundary)
        assertEquals(
            DurableWallpaperQuotaResult.WITHIN_LIMIT,
            store.validateDurableQuota(setOf(exactMediaId)),
        )
        val exactLoad = store.load(exactMediaId)
        assertTrue(exactLoad is WallpaperLoadResult.Ready)
        (exactLoad as WallpaperLoadResult.Ready).release()

        store.reconcile(emptySet())
        val firstStructurallyValidOverage = padStaticWebp(
            encoded,
            MAXIMUM_WALLPAPER_DERIVATIVE_BYTES + 2,
        )
        assertTrue(firstStructurallyValidOverage.isStaticWebp())
        val overMediaId = installManagedBytes(firstStructurallyValidOverage)
        assertEquals(
            DurableWallpaperQuotaResult.LIMIT_EXCEEDED,
            store.validateDurableQuota(setOf(overMediaId)),
        )
        assertEquals(WallpaperLoadResult.Unavailable, store.load(overMediaId))
        store.reconcile(emptySet())
        Unit
    }

    @Test
    fun invalidSchemeMimeMismatchAndAnimatedInputsFailClosed() = runBlocking {
        assertEquals(
            WallpaperMediaFailure.INVALID_SOURCE,
            (store.inspect(Uri.parse("file:///tmp/private.png")) as WallpaperInspectionResult.Failed).reason,
        )
        assertEquals(
            WallpaperMediaFailure.MIME_MISMATCH,
            (store.inspect(testUri("mismatch.png")) as WallpaperInspectionResult.Failed).reason,
        )
        assertEquals(
            WallpaperMediaFailure.UNSUPPORTED_TYPE,
            (store.inspect(testUri("animated.png")) as WallpaperInspectionResult.Failed).reason,
        )
        assertEquals(
            WallpaperMediaFailure.UNSUPPORTED_TYPE,
            (store.inspect(testUri("animated.gif")) as WallpaperInspectionResult.Failed).reason,
        )
        assertEquals(
            WallpaperMediaFailure.UNSUPPORTED_TYPE,
            (store.inspect(testUri("truncated.jpeg")) as WallpaperInspectionResult.Failed).reason,
        )
        assertEquals(
            WallpaperMediaFailure.UNSUPPORTED_TYPE,
            (store.inspect(testUri("truncated.png")) as WallpaperInspectionResult.Failed).reason,
        )
    }

    @Test
    fun jpegExifOrientationIsAppliedBeforeTheManagedDerivativeIsPersisted() = runBlocking {
        store.reconcile(emptySet())

        val inspection = store.inspect(testUri("rotated.jpeg")) as WallpaperInspectionResult.Ready
        try {
            assertEquals(20, inspection.width)
            assertEquals(40, inspection.height)
            assertEquals(Bitmap.Config.ARGB_8888, inspection.preview.asAndroidBitmap().config)
            assertTrue(inspection.preview.asAndroidBitmap().colorSpace?.isSrgb == true)
        } finally {
            inspection.release()
        }

        val imported = store.import(testUri("rotated.jpeg")) as WallpaperImportResult.Ready
        val loaded = store.load(imported.mediaId) as WallpaperLoadResult.Ready
        try {
            assertEquals(20, loaded.width)
            assertEquals(40, loaded.height)
            assertEquals(Bitmap.Config.ARGB_8888, loaded.image.asAndroidBitmap().config)
            assertTrue(loaded.image.asAndroidBitmap().colorSpace?.isSrgb == true)
        } finally {
            loaded.release()
            store.reconcile(emptySet())
        }
    }

    @Test
    fun pngExifOrientationIsAppliedExactlyOnceBeforePersistence() = runBlocking {
        store.reconcile(emptySet())

        val inspection = store.inspect(testUri("rotated.png")) as WallpaperInspectionResult.Ready
        try {
            assertEquals(3, inspection.width)
            assertEquals(2, inspection.height)
            assertEquals(Bitmap.Config.ARGB_8888, inspection.preview.asAndroidBitmap().config)
            val actual = IntArray(6)
            inspection.preview.asAndroidBitmap().getPixels(actual, 0, 3, 0, 0, 3, 2)
            assertArrayEquals(
                intArrayOf(
                    Color.CYAN,
                    Color.BLUE,
                    Color.RED,
                    Color.MAGENTA,
                    Color.YELLOW,
                    Color.GREEN,
                ),
                actual,
            )
        } finally {
            inspection.release()
        }

        val imported = store.import(testUri("rotated.png")) as WallpaperImportResult.Ready
        val loaded = store.load(imported.mediaId) as WallpaperLoadResult.Ready
        try {
            assertEquals(3, loaded.width)
            assertEquals(2, loaded.height)
            assertEquals(Bitmap.Config.ARGB_8888, loaded.image.asAndroidBitmap().config)
            assertTrue(loaded.image.asAndroidBitmap().colorSpace?.isSrgb == true)
        } finally {
            loaded.release()
            store.reconcile(emptySet())
        }
    }

    @Test
    fun legacyExifTransformMapsEveryOrientationWithoutRetainingTheSourceBitmap() {
        val a = Color.RED
        val b = Color.GREEN
        val c = Color.BLUE
        val d = Color.YELLOW
        val e = Color.CYAN
        val f = Color.MAGENTA
        val sourcePixels = intArrayOf(a, b, c, d, e, f)
        val expected = mapOf(
            1 to intArrayOf(a, b, c, d, e, f),
            2 to intArrayOf(b, a, d, c, f, e),
            3 to intArrayOf(f, e, d, c, b, a),
            4 to intArrayOf(e, f, c, d, a, b),
            5 to intArrayOf(a, c, e, b, d, f),
            6 to intArrayOf(e, c, a, f, d, b),
            7 to intArrayOf(f, d, b, e, c, a),
            8 to intArrayOf(b, d, f, a, c, e),
        )

        expected.forEach { (orientation, expectedPixels) ->
            val source = Bitmap.createBitmap(sourcePixels, 2, 3, Bitmap.Config.ARGB_8888)
            val transformed = orientLegacyBitmap(source, orientation)
            try {
                assertEquals(if (orientation >= 5) 3 else 2, transformed.width)
                assertEquals(if (orientation >= 5) 2 else 3, transformed.height)
                val actual = IntArray(transformed.width * transformed.height)
                transformed.getPixels(actual, 0, transformed.width, 0, 0, transformed.width, transformed.height)
                assertArrayEquals("orientation=$orientation", expectedPixels, actual)
            } finally {
                if (!transformed.isRecycled) transformed.recycle()
                if (!source.isRecycled) source.recycle()
            }
        }
    }

    private fun testUri(path: String): Uri =
        Uri.parse("content://org.aurorasms.app.wallpaper.testprovider/$path")

    private fun managedFile(mediaId: String): File = File(
        File(context.noBackupFilesDir, "appearance/wallpapers"),
        wallpaperDerivativeFileName(mediaId)!!,
    )

    private fun installManagedBytes(bytes: ByteArray): String {
        val mediaId = wallpaperMediaId(bytes)
        val file = managedFile(mediaId)
        assertTrue(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        file.writeBytes(bytes)
        return mediaId
    }

    private fun encodedWebp(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { output ->
                val format = if (Build.VERSION.SDK_INT >= 30) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                assertTrue(bitmap.compress(format, 95, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun animatedWebpEnvelope(): ByteArray = webpEnvelope(
        webpChunk("VP8X", byteArrayOf(0x02) + ByteArray(9)),
        webpChunk("ANIM", ByteArray(6)),
        webpChunk("ANMF", ByteArray(16)),
    )

    private fun webpEnvelope(vararg chunks: ByteArray): ByteArray {
        val payload = "WEBP".encodeToByteArray() + chunks.fold(byteArrayOf()) { bytes, chunk ->
            bytes + chunk
        }
        return "RIFF".encodeToByteArray() + littleEndianInt(payload.size) + payload
    }

    private fun webpChunk(type: String, payload: ByteArray): ByteArray =
        type.encodeToByteArray() + littleEndianInt(payload.size) + payload +
            if (payload.size % 2 == 0) byteArrayOf() else byteArrayOf(0)

    private fun padStaticWebp(encoded: ByteArray, targetBytes: Int): ByteArray {
        assertTrue(encoded.isStaticWebp())
        val remaining = targetBytes - encoded.size
        require(remaining >= 8 && remaining % 2 == 0)
        return encoded.copyOf(targetBytes).apply {
            "JUNK".encodeToByteArray().copyInto(this, encoded.size)
            littleEndianInt(remaining - 8).copyInto(this, encoded.size + 4)
            littleEndianInt(targetBytes - 8).copyInto(this, 4)
        }
    }

    private fun webpChunkTypes(bytes: ByteArray): List<String> {
        val result = mutableListOf<String>()
        var offset = 12
        while (offset + 8 <= bytes.size) {
            result += bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)
            val payloadBytes = bytes.readLittleEndianInt(offset + 4)
            offset += 8 + payloadBytes + (payloadBytes and 1)
        }
        return result
    }

    private fun littleEndianInt(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )

    private fun ByteArray.readLittleEndianInt(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)

    private fun ByteArray.containsAscii(value: String): Boolean {
        val needle = value.encodeToByteArray()
        return indices.any { offset ->
            offset <= size - needle.size && needle.indices.all { index ->
                this[offset + index] == needle[index]
            }
        }
    }
}
