// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

/**
 * Classification of a direct child of the managed wallpaper directory.
 *
 * The fixed [toString] implementations are intentional: filenames, digests, media IDs, and
 * staging IDs are private storage identifiers and must not be disclosed through diagnostics.
 */
internal sealed interface ManagedWallpaperFileClassification {
    class Final(
        val mediaId: String,
    ) : ManagedWallpaperFileClassification {
        override fun toString(): String = "ManagedWallpaperFileClassification.Final(<redacted>)"
    }

    class Pending(
        val stagingId: String,
    ) : ManagedWallpaperFileClassification {
        override fun toString(): String = "ManagedWallpaperFileClassification.Pending(<redacted>)"
    }

    data object Unexpected : ManagedWallpaperFileClassification {
        override fun toString(): String = "ManagedWallpaperFileClassification.Unexpected"
    }
}

/**
 * Classifies only canonical, whole managed filenames. Near matches remain caller-owned files.
 */
internal fun classifyManagedWallpaperFileName(
    fileName: String,
): ManagedWallpaperFileClassification {
    if (fileName.length == FINAL_FILE_NAME_CHARACTERS) {
        if (
            fileName.startsWith(WALLPAPER_FILE_PREFIX) &&
            fileName.endsWith(WALLPAPER_FILE_SUFFIX) &&
            fileName.hasLowercaseHex(
                startIndex = WALLPAPER_FILE_PREFIX.length,
                characterCount = SHA_256_HEX_CHARACTERS,
            )
        ) {
            val digestStart = WALLPAPER_FILE_PREFIX.length
            val digestEnd = digestStart + SHA_256_HEX_CHARACTERS
            return ManagedWallpaperFileClassification.Final(
                mediaId = WALLPAPER_MEDIA_ID_PREFIX + fileName.substring(digestStart, digestEnd),
            )
        }
        return ManagedWallpaperFileClassification.Unexpected
    }

    if (fileName.length == PENDING_FILE_NAME_CHARACTERS) {
        if (
            fileName.startsWith(PENDING_FILE_PREFIX) &&
            fileName.hasCanonicalLowercaseUuid(startIndex = PENDING_FILE_PREFIX.length)
        ) {
            return ManagedWallpaperFileClassification.Pending(
                stagingId = fileName.substring(PENDING_FILE_PREFIX.length),
            )
        }
    }

    return ManagedWallpaperFileClassification.Unexpected
}

private fun String.hasLowercaseHex(
    startIndex: Int,
    characterCount: Int,
): Boolean {
    val endIndex = startIndex + characterCount
    if (startIndex < 0 || characterCount < 0 || endIndex < startIndex || endIndex > length) {
        return false
    }
    for (index in startIndex until endIndex) {
        if (!this[index].isLowercaseHexCharacter()) return false
    }
    return true
}

private fun String.hasCanonicalLowercaseUuid(startIndex: Int): Boolean {
    if (startIndex < 0 || length - startIndex != UUID_CHARACTERS) return false
    for (uuidIndex in 0 until UUID_CHARACTERS) {
        val character = this[startIndex + uuidIndex]
        if (uuidIndex.isUuidHyphenIndex()) {
            if (character != '-') return false
        } else if (!character.isLowercaseHexCharacter()) {
            return false
        }
    }
    return true
}

private fun Char.isLowercaseHexCharacter(): Boolean = this in '0'..'9' || this in 'a'..'f'

private fun Int.isUuidHyphenIndex(): Boolean =
    this == UUID_FIRST_HYPHEN_INDEX ||
        this == UUID_SECOND_HYPHEN_INDEX ||
        this == UUID_THIRD_HYPHEN_INDEX ||
        this == UUID_FOURTH_HYPHEN_INDEX

private const val SHA_256_HEX_CHARACTERS: Int = 64
private const val PENDING_FILE_PREFIX: String = ".pending-"
private const val UUID_CHARACTERS: Int = 36
private const val UUID_FIRST_HYPHEN_INDEX: Int = 8
private const val UUID_SECOND_HYPHEN_INDEX: Int = 13
private const val UUID_THIRD_HYPHEN_INDEX: Int = 18
private const val UUID_FOURTH_HYPHEN_INDEX: Int = 23
private const val FINAL_FILE_NAME_CHARACTERS: Int =
    WALLPAPER_FILE_PREFIX.length + SHA_256_HEX_CHARACTERS + WALLPAPER_FILE_SUFFIX.length
private const val PENDING_FILE_NAME_CHARACTERS: Int = PENDING_FILE_PREFIX.length + UUID_CHARACTERS
