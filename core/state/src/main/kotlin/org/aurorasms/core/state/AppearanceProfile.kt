// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import java.text.Normalizer
import java.util.Locale

const val CURRENT_APPEARANCE_PROFILE_SCHEMA: Int = 1
const val MAX_APPEARANCE_PROFILE_COUNT: Int = 32
const val MAX_APPEARANCE_PROFILE_NAME_CODE_POINTS: Int = 64
const val MAX_APPEARANCE_PROFILE_NAME_UTF8_BYTES: Int = 256
const val MINIMUM_APPEARANCE_HUE_DEGREES: Int = 0
const val MAXIMUM_APPEARANCE_HUE_DEGREES: Int = 359
const val MINIMUM_APPEARANCE_WALLPAPER_DIM_PERMILL: Int = 350
const val MAXIMUM_APPEARANCE_WALLPAPER_DIM_PERMILL: Int = 900
const val MINIMUM_APPEARANCE_FOCAL_PERMILL: Int = 0
const val MAXIMUM_APPEARANCE_FOCAL_PERMILL: Int = 1_000

enum class AppearancePalette(val storageCode: String) {
    AURORA_DARK("aurora_dark"),
    AMOLED_BLACK("amoled_black"),
    LIGHT("light"),
    SYSTEM_DYNAMIC("system_dynamic"),
    ;

    companion object {
        fun fromStorageCode(code: String): AppearancePalette? = entries.singleOrNull {
            it.storageCode == code
        }
    }
}

enum class AppearanceRowDensity(val storageCode: String) {
    COMPACT("compact"),
    COMFORTABLE("comfortable"),
    SPACIOUS("spacious"),
    ;

    companion object {
        fun fromStorageCode(code: String): AppearanceRowDensity? = entries.singleOrNull {
            it.storageCode == code
        }
    }
}

enum class AppearanceAvatarMask(val storageCode: String) {
    CIRCLE("circle"),
    ROUNDED_SQUARE("rounded_square"),
    SQUIRCLE("squircle"),
    HEXAGON("hexagon"),
    ;

    companion object {
        fun fromStorageCode(code: String): AppearanceAvatarMask? = entries.singleOrNull {
            it.storageCode == code
        }
    }
}

enum class AppearanceNavigationStyle(val storageCode: String) {
    CLASSIC("classic"),
    BOTTOM_BAR("bottom_bar"),
    ADAPTIVE_RAIL("adaptive_rail"),
    ;

    companion object {
        fun fromStorageCode(code: String): AppearanceNavigationStyle? = entries.singleOrNull {
            it.storageCode == code
        }
    }
}

enum class AppearanceBubbleGeometry(val storageCode: String) {
    COMPACT("compact"),
    ROUNDED("rounded"),
    EXPRESSIVE("expressive"),
    ;

    companion object {
        fun fromStorageCode(code: String): AppearanceBubbleGeometry? = entries.singleOrNull {
            it.storageCode == code
        }
    }
}

/** A normalized, bounded user-facing profile name with a stable uniqueness key. */
class AppearanceProfileName private constructor(
    val value: String,
    internal val normalizedKey: String,
) {
    override fun equals(other: Any?): Boolean =
        other is AppearanceProfileName && value == other.value && normalizedKey == other.normalizedKey

    override fun hashCode(): Int = 31 * value.hashCode() + normalizedKey.hashCode()

    override fun toString(): String = "AppearanceProfileName(REDACTED)"

    companion object {
        fun from(rawValue: String): AppearanceProfileName {
            val value = Normalizer.normalize(rawValue.trim(), Normalizer.Form.NFC)
            require(value.isNotEmpty()) { "An appearance profile name cannot be empty" }
            require(
                value.codePointCount(0, value.length) <= MAX_APPEARANCE_PROFILE_NAME_CODE_POINTS,
            ) { "An appearance profile name has too many characters" }
            require(value.encodeToByteArray().size <= MAX_APPEARANCE_PROFILE_NAME_UTF8_BYTES) {
                "An appearance profile name is too large"
            }
            require(value.codePoints().noneMatch(::isForbiddenProfileNameCodePoint)) {
                "An appearance profile name contains a forbidden control character"
            }
            val normalizedKey = Normalizer.normalize(
                value.lowercase(Locale.ROOT),
                Normalizer.Form.NFKC,
            )
            require(
                normalizedKey.encodeToByteArray().size <= MAX_APPEARANCE_PROFILE_NAME_UTF8_BYTES,
            ) { "An appearance profile normalized name is too large" }
            require(normalizedKey.codePoints().noneMatch(::isForbiddenProfileNameCodePoint)) {
                "An appearance profile normalized name contains a forbidden control character"
            }
            return AppearanceProfileName(value = value, normalizedKey = normalizedKey)
        }

        internal fun fromStored(value: String, normalizedKey: String): AppearanceProfileName {
            val validated = from(value)
            require(validated.value == value && validated.normalizedKey == normalizedKey) {
                "A stored appearance profile name is not canonical"
            }
            return validated
        }
    }
}

/** Complete schema-v1 appearance values. Media references deliberately live outside this slice. */
data class AppearanceProfileValues(
    val schemaVersion: Int = CURRENT_APPEARANCE_PROFILE_SCHEMA,
    val palette: AppearancePalette = AppearancePalette.AURORA_DARK,
    val hueDegrees: Int = 174,
    val rowDensity: AppearanceRowDensity = AppearanceRowDensity.COMFORTABLE,
    val avatarMask: AppearanceAvatarMask = AppearanceAvatarMask.CIRCLE,
    val navigationStyle: AppearanceNavigationStyle = AppearanceNavigationStyle.CLASSIC,
    val bubbleGeometry: AppearanceBubbleGeometry = AppearanceBubbleGeometry.ROUNDED,
    val reducedMotion: Boolean = false,
    val highContrast: Boolean = false,
    val wallpaperDimPermill: Int = 520,
    val focalXPermill: Int = 500,
    val focalYPermill: Int = 500,
) {
    init {
        require(schemaVersion == CURRENT_APPEARANCE_PROFILE_SCHEMA) {
            "Unsupported appearance profile schema"
        }
        require(hueDegrees in MINIMUM_APPEARANCE_HUE_DEGREES..MAXIMUM_APPEARANCE_HUE_DEGREES) {
            "Appearance hue must use the closed 0..359 degree range"
        }
        require(
            wallpaperDimPermill in
                MINIMUM_APPEARANCE_WALLPAPER_DIM_PERMILL..MAXIMUM_APPEARANCE_WALLPAPER_DIM_PERMILL,
        ) { "Appearance wallpaper dim is outside the accessible range" }
        require(
            focalXPermill in MINIMUM_APPEARANCE_FOCAL_PERMILL..MAXIMUM_APPEARANCE_FOCAL_PERMILL,
        ) { "Appearance focal X is outside the normalized range" }
        require(
            focalYPermill in MINIMUM_APPEARANCE_FOCAL_PERMILL..MAXIMUM_APPEARANCE_FOCAL_PERMILL,
        ) { "Appearance focal Y is outside the normalized range" }
    }

    companion object {
        val CanonicalDefault: AppearanceProfileValues = AppearanceProfileValues()
    }
}

@JvmInline
value class AppearanceProfileId(val value: Long) {
    init {
        require(value > 0L) { "Appearance profile IDs must be positive" }
    }

    override fun toString(): String = "AppearanceProfileId(REDACTED)"
}

@JvmInline
value class AppearanceRevision(val value: Long) {
    init {
        require(value > 0L) { "Appearance revisions must be positive" }
    }

    override fun toString(): String = "AppearanceRevision(REDACTED)"
}

data class NewAppearanceProfile(
    val name: AppearanceProfileName,
    val values: AppearanceProfileValues,
    val createdTimestampMillis: Long,
    val updatedTimestampMillis: Long = createdTimestampMillis,
) {
    init {
        require(createdTimestampMillis >= 0L) {
            "An appearance profile creation timestamp cannot be negative"
        }
        require(updatedTimestampMillis >= createdTimestampMillis) {
            "An appearance profile update timestamp cannot precede creation"
        }
    }

    override fun toString(): String = "NewAppearanceProfile(REDACTED)"
}

data class AppearanceProfileEdit(
    val id: AppearanceProfileId,
    val name: AppearanceProfileName,
    val values: AppearanceProfileValues,
    val updatedTimestampMillis: Long,
) {
    init {
        require(updatedTimestampMillis >= 0L) {
            "An appearance profile update timestamp cannot be negative"
        }
    }

    override fun toString(): String = "AppearanceProfileEdit(REDACTED)"
}

data class AppearanceProfile(
    val id: AppearanceProfileId,
    val name: AppearanceProfileName,
    val values: AppearanceProfileValues,
    val revision: AppearanceRevision,
    val createdTimestampMillis: Long,
    val updatedTimestampMillis: Long,
) {
    init {
        require(createdTimestampMillis >= 0L) {
            "A stored appearance profile creation timestamp cannot be negative"
        }
        require(updatedTimestampMillis >= createdTimestampMillis) {
            "A stored appearance profile update timestamp cannot precede creation"
        }
    }

    override fun toString(): String = "AppearanceProfile(REDACTED)"
}

/** One immutable, bounded state image. No profile means use [AppearanceProfileValues.CanonicalDefault]. */
data class AppearanceSnapshot(
    val profiles: List<AppearanceProfile>,
    val activeProfileId: AppearanceProfileId?,
    val revision: Long,
) {
    init {
        require(revision >= 0L) { "An appearance snapshot revision cannot be negative" }
        require(profiles.size <= MAX_APPEARANCE_PROFILE_COUNT) {
            "An appearance snapshot exceeds the profile limit"
        }
        require(profiles.map { it.id }.toSet().size == profiles.size) {
            "An appearance snapshot contains duplicate profile IDs"
        }
        require(profiles.map { it.name.normalizedKey }.toSet().size == profiles.size) {
            "An appearance snapshot contains duplicate normalized names"
        }
        require(activeProfileId == null || profiles.any { it.id == activeProfileId }) {
            "An appearance snapshot references a missing active profile"
        }
        require(revision != 0L || profiles.isEmpty() && activeProfileId == null) {
            "Only the immediate empty snapshot may use revision zero"
        }
    }

    val activeProfile: AppearanceProfile?
        get() = activeProfileId?.let { id -> profiles.single { it.id == id } }

    val activeValues: AppearanceProfileValues
        get() = activeProfile?.values ?: AppearanceProfileValues.CanonicalDefault

    override fun toString(): String =
        "AppearanceSnapshot(profileCount=${profiles.size}, hasActiveProfile=${activeProfileId != null}, " +
            "revision=$revision, REDACTED)"

    companion object {
        val Empty: AppearanceSnapshot = AppearanceSnapshot(
            profiles = emptyList(),
            activeProfileId = null,
            revision = 0L,
        )
    }
}

private fun isForbiddenProfileNameCodePoint(codePoint: Int): Boolean =
    Character.isISOControl(codePoint) ||
        codePoint in 0xD800..0xDFFF ||
        codePoint == 0x061C ||
        codePoint == 0x200E ||
        codePoint == 0x200F ||
        codePoint in 0x202A..0x202E ||
        codePoint in 0x2066..0x2069
