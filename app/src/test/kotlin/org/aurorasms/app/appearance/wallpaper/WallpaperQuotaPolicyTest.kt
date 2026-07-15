// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperQuotaPolicyTest {
    @Test
    fun durableQuotaAcceptsExactBoundariesAndRejectsOneOver() {
        assertQuota(
            expected = WallpaperQuotaResult.WITHIN_LIMIT,
            limit = WallpaperQuotaLimit.DURABLE,
            currentFileCount = MAXIMUM_WALLPAPER_FILE_COUNT.toLong(),
            currentTotalBytes = MAXIMUM_WALLPAPER_TOTAL_BYTES,
        )
        assertQuota(
            expected = WallpaperQuotaResult.LIMIT_EXCEEDED,
            limit = WallpaperQuotaLimit.DURABLE,
            currentFileCount = MAXIMUM_WALLPAPER_FILE_COUNT.toLong(),
            currentTotalBytes = MAXIMUM_WALLPAPER_TOTAL_BYTES,
            additionalFileCount = 1L,
        )
        assertQuota(
            expected = WallpaperQuotaResult.LIMIT_EXCEEDED,
            limit = WallpaperQuotaLimit.DURABLE,
            currentFileCount = MAXIMUM_WALLPAPER_FILE_COUNT.toLong(),
            currentTotalBytes = MAXIMUM_WALLPAPER_TOTAL_BYTES,
            additionalBytes = 1L,
        )
    }

    @Test
    fun stagedQuotaAllowsExactlyOneCandidateDerivative() {
        assertQuota(
            expected = WallpaperQuotaResult.WITHIN_LIMIT,
            limit = WallpaperQuotaLimit.STAGED,
            currentFileCount = MAXIMUM_WALLPAPER_FILE_COUNT.toLong(),
            currentTotalBytes = MAXIMUM_WALLPAPER_TOTAL_BYTES,
            additionalFileCount = 1L,
            additionalBytes = MAXIMUM_WALLPAPER_DERIVATIVE_BYTES.toLong(),
        )
        assertQuota(
            expected = WallpaperQuotaResult.LIMIT_EXCEEDED,
            limit = WallpaperQuotaLimit.STAGED,
            currentFileCount = MAXIMUM_WALLPAPER_FILE_COUNT.toLong(),
            currentTotalBytes = MAXIMUM_WALLPAPER_TOTAL_BYTES,
            additionalFileCount = 2L,
            additionalBytes = MAXIMUM_WALLPAPER_DERIVATIVE_BYTES.toLong(),
        )
        assertQuota(
            expected = WallpaperQuotaResult.LIMIT_EXCEEDED,
            limit = WallpaperQuotaLimit.STAGED,
            currentFileCount = MAXIMUM_WALLPAPER_FILE_COUNT.toLong(),
            currentTotalBytes = MAXIMUM_WALLPAPER_TOTAL_BYTES,
            additionalFileCount = 1L,
            additionalBytes = MAXIMUM_WALLPAPER_DERIVATIVE_BYTES.toLong() + 1L,
        )
    }

    @Test
    fun sameHashShapeAddsNoFileOrBytes() {
        assertQuota(
            expected = WallpaperQuotaResult.WITHIN_LIMIT,
            limit = WallpaperQuotaLimit.DURABLE,
            currentFileCount = MAXIMUM_WALLPAPER_FILE_COUNT.toLong(),
            currentTotalBytes = MAXIMUM_WALLPAPER_TOTAL_BYTES,
            additionalFileCount = 0L,
            additionalBytes = 0L,
        )
    }

    @Test
    fun negativeInputsAreInvalidState() {
        listOf(
            longArrayOf(-1L, 0L, 0L, 0L),
            longArrayOf(0L, -1L, 0L, 0L),
            longArrayOf(0L, 0L, -1L, 0L),
            longArrayOf(0L, 0L, 0L, -1L),
        ).forEach { values ->
            assertQuota(
                expected = WallpaperQuotaResult.INVALID_STATE,
                limit = WallpaperQuotaLimit.STAGED,
                currentFileCount = values[0],
                currentTotalBytes = values[1],
                additionalFileCount = values[2],
                additionalBytes = values[3],
            )
        }
    }

    @Test
    fun longOverflowIsInvalidState() {
        assertQuota(
            expected = WallpaperQuotaResult.INVALID_STATE,
            limit = WallpaperQuotaLimit.STAGED,
            currentFileCount = Long.MAX_VALUE,
            currentTotalBytes = 0L,
            additionalFileCount = 1L,
        )
        assertQuota(
            expected = WallpaperQuotaResult.INVALID_STATE,
            limit = WallpaperQuotaLimit.STAGED,
            currentFileCount = 0L,
            currentTotalBytes = Long.MAX_VALUE,
            additionalBytes = 1L,
        )
    }

    private fun assertQuota(
        expected: WallpaperQuotaResult,
        limit: WallpaperQuotaLimit,
        currentFileCount: Long,
        currentTotalBytes: Long,
        additionalFileCount: Long = 0L,
        additionalBytes: Long = 0L,
    ) {
        assertEquals(
            expected,
            evaluateWallpaperQuota(
                limit = limit,
                currentFileCount = currentFileCount,
                currentTotalBytes = currentTotalBytes,
                additionalFileCount = additionalFileCount,
                additionalBytes = additionalBytes,
            ),
        )
    }
}
