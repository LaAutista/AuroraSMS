// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.aurorasms.core.telephony.OutgoingMmsAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposerImageSanitizerTest {
    @Test
    fun opaqueImageIsReencodedWithinBoundsAndTrailingMetadataIsRemoved() {
        val marker = "AURORA-PRIVATE-GPS-MARKER".encodeToByteArray()
        val source = createBitmap(hasAlpha = false).let { bitmap ->
            try {
                ByteArrayOutputStream().use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output))
                    output.write(marker)
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }

        val result = ComposerImageSanitizer.sanitizeBytes(source)
        assertTrue(result is ComposerImageSanitizationResult.Ready)
        val attachment = (result as ComposerImageSanitizationResult.Ready).attachment
        assertEquals(OutgoingMmsAttachment.IMAGE_JPEG, attachment.contentType)
        assertTrue(attachment.size in 1..OutgoingMmsAttachment.MAX_BYTES)
        assertFalse(attachment.copyBytes().containsSubsequence(marker))
        val decoded = BitmapFactory.decodeByteArray(attachment.copyBytes(), 0, attachment.size)
        assertNotNull(decoded)
        decoded?.recycle()
    }

    @Test
    fun alphaImageUsesMetadataFreePngPayload() {
        val bitmap = createBitmap(hasAlpha = true)
        val source = try {
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }

        val result = ComposerImageSanitizer.sanitizeBytes(source)
        assertTrue(result is ComposerImageSanitizationResult.Ready)
        assertEquals(
            OutgoingMmsAttachment.IMAGE_PNG,
            (result as ComposerImageSanitizationResult.Ready).attachment.contentType,
        )
    }

    @Test
    fun undecodableInputIsRejectedWithoutPayload() {
        assertEquals(
            ComposerImageSanitizationResult.Unsupported,
            ComposerImageSanitizer.sanitizeBytes(byteArrayOf(1, 2, 3, 4)),
        )
    }

    private fun createBitmap(hasAlpha: Boolean): Bitmap =
        Bitmap.createBitmap(96, 72, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(hasAlpha)
            eraseColor(if (hasAlpha) Color.argb(128, 80, 30, 210) else Color.rgb(80, 30, 210))
        }
}

private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    return indices.any { start ->
        start + needle.size <= size && needle.indices.all { offset ->
            this[start + offset] == needle[offset]
        }
    }
}
