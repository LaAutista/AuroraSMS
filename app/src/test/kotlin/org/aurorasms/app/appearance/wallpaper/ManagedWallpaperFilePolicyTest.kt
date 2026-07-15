// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedWallpaperFilePolicyTest {
    @Test
    fun exactFinalNamesReturnCanonicalMediaIdsAtHexBoundaries() {
        val zeroDigest = "0".repeat(64)
        val maximumDigest = "f".repeat(64)

        assertFinal("v1-$zeroDigest.webp", "sha256-v1:$zeroDigest")
        assertFinal("v1-$maximumDigest.webp", "sha256-v1:$maximumDigest")
        assertFinal(
            "v1-0123456789abcdef".repeatDigestToFileName(),
            "sha256-v1:${"0123456789abcdef".repeat(4)}",
        )
    }

    @Test
    fun finalNamesRejectWrongCaseAndDigestLength() {
        val digest = "a".repeat(64)

        assertUnexpected("v1-${"a".repeat(63)}.webp")
        assertUnexpected("v1-${"a".repeat(65)}.webp")
        assertUnexpected("v1-${"A" + "a".repeat(63)}.webp")
        assertUnexpected("V1-$digest.webp")
        assertUnexpected("v1-$digest.WEBP")
    }

    @Test
    fun finalNamesRejectPrefixSuffixAndPathSmuggling() {
        val canonical = "v1-${"b".repeat(64)}.webp"

        assertUnexpected("x$canonical")
        assertUnexpected("$canonical.webp")
        assertUnexpected("$canonical.pending")
        assertUnexpected("$canonical\u0000")
        assertUnexpected("../$canonical")
        assertUnexpected("directory/$canonical")
    }

    @Test
    fun exactPendingNamesAcceptCanonicalLowercaseUuidBoundaries() {
        assertPending(
            ".pending-00000000-0000-0000-0000-000000000000",
            "00000000-0000-0000-0000-000000000000",
        )
        assertPending(
            ".pending-ffffffff-ffff-ffff-ffff-ffffffffffff",
            "ffffffff-ffff-ffff-ffff-ffffffffffff",
        )
        assertPending(
            ".pending-01234567-89ab-cdef-0123-456789abcdef",
            "01234567-89ab-cdef-0123-456789abcdef",
        )
    }

    @Test
    fun pendingNamesRejectUuidCaseHyphenAndLengthVariants() {
        val canonical = ".pending-01234567-89ab-cdef-0123-456789abcdef"

        assertUnexpected(".pending-01234567-89ab-cdeF-0123-456789abcdef")
        assertUnexpected(".pending-0123456789ab-cdef-0123-456789abcdef")
        assertUnexpected(".pending-01234567_89ab-cdef-0123-456789abcdef")
        assertUnexpected(".pending-01234567-89ab-cdef-0123-456789abcde")
        assertUnexpected(".pending-01234567-89ab-cdef-0123-456789abcdef0")
        assertUnexpected(".PENDING-01234567-89ab-cdef-0123-456789abcdef")
        assertUnexpected("x$canonical")
        assertUnexpected("$canonical.tmp")
    }

    @Test
    fun everyClassificationToStringIsRedacted() {
        val digest = "deadbeef".repeat(8)
        val uuid = "01234567-89ab-cdef-0123-456789abcdef"
        val final = classifyManagedWallpaperFileName("v1-$digest.webp")
        val pending = classifyManagedWallpaperFileName(".pending-$uuid")
        val unexpected = classifyManagedWallpaperFileName("private-user-file")

        listOf(final, pending, unexpected).forEach { classification ->
            val rendered = classification.toString()
            assertFalse(rendered.contains(digest))
            assertFalse(rendered.contains("sha256-v1:"))
            assertFalse(rendered.contains(uuid))
            assertFalse(rendered.contains("private-user-file"))
            assertFalse(rendered.contains(".webp"))
            assertFalse(rendered.contains(".pending-"))
        }
        assertTrue(final.toString().contains("<redacted>"))
        assertTrue(pending.toString().contains("<redacted>"))
        assertEquals("ManagedWallpaperFileClassification.Unexpected", unexpected.toString())
    }

    @Test
    fun candidateLeaseToStringDoesNotDiscloseItsMediaId() {
        val digest = "c0ffee12".repeat(8)
        val candidate = WallpaperImportResult.Ready(
            mediaId = "sha256-v1:$digest",
            created = true,
        )

        val rendered = candidate.toString()
        assertFalse(rendered.contains(digest))
        assertFalse(rendered.contains("sha256-v1:"))
        assertTrue(rendered.contains("media=REDACTED"))
        assertTrue(rendered.contains("created=true"))
    }

    private fun assertFinal(fileName: String, expectedMediaId: String) {
        val classification = classifyManagedWallpaperFileName(fileName)
        assertTrue(classification is ManagedWallpaperFileClassification.Final)
        assertEquals(
            expectedMediaId,
            (classification as ManagedWallpaperFileClassification.Final).mediaId,
        )
    }

    private fun assertPending(fileName: String, expectedStagingId: String) {
        val classification = classifyManagedWallpaperFileName(fileName)
        assertTrue(classification is ManagedWallpaperFileClassification.Pending)
        assertEquals(
            expectedStagingId,
            (classification as ManagedWallpaperFileClassification.Pending).stagingId,
        )
    }

    private fun assertUnexpected(fileName: String) {
        assertSame(
            ManagedWallpaperFileClassification.Unexpected,
            classifyManagedWallpaperFileName(fileName),
        )
    }

    private fun String.repeatDigestToFileName(): String {
        val prefix = "v1-"
        val digestSeed = removePrefix(prefix)
        return "$prefix${digestSeed.repeat(4)}.webp"
    }
}
