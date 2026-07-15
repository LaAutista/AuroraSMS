// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.aurorasms.app.preview.BoundedMediaDecodeGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
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
}
