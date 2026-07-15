// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

internal enum class WallpaperQuotaLimit(
    val maximumFileCount: Long,
    val maximumTotalBytes: Long,
) {
    DURABLE(
        maximumFileCount = MAXIMUM_WALLPAPER_FILE_COUNT.toLong(),
        maximumTotalBytes = MAXIMUM_WALLPAPER_TOTAL_BYTES,
    ),
    STAGED(
        maximumFileCount = MAXIMUM_WALLPAPER_STAGED_FILE_COUNT.toLong(),
        maximumTotalBytes = MAXIMUM_WALLPAPER_STAGED_TOTAL_BYTES,
    ),
}

internal enum class WallpaperQuotaResult {
    WITHIN_LIMIT,
    LIMIT_EXCEEDED,
    INVALID_STATE,
}

internal fun evaluateWallpaperQuota(
    limit: WallpaperQuotaLimit,
    currentFileCount: Long,
    currentTotalBytes: Long,
    additionalFileCount: Long,
    additionalBytes: Long,
): WallpaperQuotaResult {
    if (
        currentFileCount < 0L ||
        currentTotalBytes < 0L ||
        additionalFileCount < 0L ||
        additionalBytes < 0L
    ) {
        return WallpaperQuotaResult.INVALID_STATE
    }
    if (
        currentFileCount > Long.MAX_VALUE - additionalFileCount ||
        currentTotalBytes > Long.MAX_VALUE - additionalBytes
    ) {
        return WallpaperQuotaResult.INVALID_STATE
    }

    val prospectiveFileCount = currentFileCount + additionalFileCount
    val prospectiveTotalBytes = currentTotalBytes + additionalBytes
    return if (
        prospectiveFileCount > limit.maximumFileCount ||
        prospectiveTotalBytes > limit.maximumTotalBytes
    ) {
        WallpaperQuotaResult.LIMIT_EXCEEDED
    } else {
        WallpaperQuotaResult.WITHIN_LIMIT
    }
}
