// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

/** The only private wallpaper-media representation admitted by this bounded slice. */
enum class AppearanceWallpaperMediaKind(val storageCode: String) {
    STATIC_RASTER_V1("static_raster_v1"),
    ;

    companion object {
        fun fromStorageCode(code: String): AppearanceWallpaperMediaKind? = entries.singleOrNull {
            it.storageCode == code
        }
    }
}

/**
 * Opaque content identity for one normalized app-private wallpaper derivative.
 *
 * The application hashes the final private bytes before persistence. This value is neither a URI
 * nor a path and must still remain private because it can correlate assignments that share media.
 */
class AppearanceWallpaperMediaId private constructor(
    private val storageValue: String,
) {
    /** Returns the private token only for state persistence and app-private file lookup. */
    fun toPrivateStorageToken(): String = storageValue

    internal fun toStorageValue(): String = storageValue

    override fun equals(other: Any?): Boolean =
        other is AppearanceWallpaperMediaId && storageValue == other.storageValue

    override fun hashCode(): Int = storageValue.hashCode()

    override fun toString(): String = "AppearanceWallpaperMediaId(REDACTED)"

    companion object {
        private const val STORAGE_PREFIX: String = "sha256-v1:"
        internal const val STORAGE_CHARACTERS: Int = STORAGE_PREFIX.length + 64

        /** Admits only the exact versioned lowercase SHA-256 token produced by the importer. */
        fun fromPrivateStorageToken(value: String): AppearanceWallpaperMediaId {
            require(value.length == STORAGE_CHARACTERS) {
                "A wallpaper media ID has an invalid length"
            }
            require(value.startsWith(STORAGE_PREFIX)) {
                "A wallpaper media ID has an unsupported format"
            }
            require(value.drop(STORAGE_PREFIX.length).all { it in '0'..'9' || it in 'a'..'f' }) {
                "A wallpaper media ID is not lowercase SHA-256"
            }
            return AppearanceWallpaperMediaId(value)
        }

        internal fun fromStorageValue(value: String): AppearanceWallpaperMediaId =
            fromPrivateStorageToken(value)
    }
}

@JvmInline
value class AppearanceWallpaperRevision(val value: Long) {
    init {
        require(value > 0L) { "Appearance wallpaper revisions must be positive" }
    }

    override fun toString(): String = "AppearanceWallpaperRevision(REDACTED)"
}

data class AppearanceWallpaperAssignment(
    val scope: AppearanceScope,
    val mediaKind: AppearanceWallpaperMediaKind,
    val mediaId: AppearanceWallpaperMediaId,
    val dimPermill: Int,
    val focalXPermill: Int,
    val focalYPermill: Int,
    val revision: AppearanceWallpaperRevision,
) {
    init {
        require(
            scope !is AppearanceScope.Screen || scope.screen == AppearanceScreenScope.GLOBAL_THREAD,
        ) { "Only the global thread wallpaper screen scope is currently supported" }
        require(mediaKind == AppearanceWallpaperMediaKind.STATIC_RASTER_V1) {
            "Unsupported appearance wallpaper media kind"
        }
        require(
            dimPermill in
                MINIMUM_APPEARANCE_WALLPAPER_DIM_PERMILL..MAXIMUM_APPEARANCE_WALLPAPER_DIM_PERMILL,
        ) { "Appearance wallpaper dim is outside the accessible range" }
        require(
            focalXPermill in MINIMUM_APPEARANCE_FOCAL_PERMILL..MAXIMUM_APPEARANCE_FOCAL_PERMILL,
        ) { "Appearance wallpaper focal X is outside the normalized range" }
        require(
            focalYPermill in MINIMUM_APPEARANCE_FOCAL_PERMILL..MAXIMUM_APPEARANCE_FOCAL_PERMILL,
        ) { "Appearance wallpaper focal Y is outside the normalized range" }
    }

    override fun toString(): String = "AppearanceWallpaperAssignment(REDACTED)"
}

/** One committed assignment result plus a private file that may now be safely garbage-collected. */
data class AppearanceWallpaperMutation(
    val assignment: AppearanceWallpaperAssignment?,
    val mediaIdNowUnreferenced: AppearanceWallpaperMediaId?,
) {
    override fun toString(): String = "AppearanceWallpaperMutation(REDACTED)"
}

const val MAX_APPEARANCE_WALLPAPER_MEDIA_IDS: Int = 128
internal const val APPEARANCE_WALLPAPER_MEDIA_ENUMERATION_LIMIT: Int =
    MAX_APPEARANCE_WALLPAPER_MEDIA_IDS + 1
