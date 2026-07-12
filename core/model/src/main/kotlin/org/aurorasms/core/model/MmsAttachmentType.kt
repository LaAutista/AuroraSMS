// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.model

import java.util.Locale

/**
 * Bounded metadata for one MMS attachment.
 *
 * This type deliberately has no URI, filesystem path, or attachment bytes.
 * The optional display name is private message content and is redacted from
 * string output.
 */
class MmsAttachmentType(
    mimeType: String,
    displayName: String? = null,
) {
    val mimeType: String = normalizeMimeType(mimeType)
    val displayName: String? = normalizeDisplayName(displayName)

    override fun equals(other: Any?): Boolean =
        other is MmsAttachmentType &&
            mimeType == other.mimeType &&
            displayName == other.displayName

    override fun hashCode(): Int = 31 * mimeType.hashCode() + (displayName?.hashCode() ?: 0)

    override fun toString(): String =
        "MmsAttachmentType(mimeType=$mimeType, displayName=${if (displayName == null) "null" else "REDACTED"})"

    companion object {
        const val MAX_MIME_TYPE_CHARACTERS: Int = 127
        const val MAX_DISPLAY_NAME_CHARACTERS: Int = 255

        private val MIME_TYPE_PATTERN = Regex(
            "[a-z0-9][a-z0-9!#$%&'*+.^_`|~-]*/[a-z0-9][a-z0-9!#$%&'*+.^_`|~-]*",
        )

        private fun normalizeMimeType(value: String): String {
            require(value.length <= MAX_MIME_TYPE_CHARACTERS) { "MMS MIME type is too long" }
            val normalized = value.trim().lowercase(Locale.ROOT)
            require(MIME_TYPE_PATTERN.matches(normalized)) { "Invalid MMS MIME type" }
            return normalized
        }

        private fun normalizeDisplayName(value: String?): String? {
            if (value == null) return null
            require(value.length <= MAX_DISPLAY_NAME_CHARACTERS) { "MMS display name is too long" }
            val normalized = value.trim()
            require(normalized.isNotEmpty()) { "MMS display name cannot be blank" }
            require(normalized.none(Char::isISOControl)) {
                "MMS display name contains a control character"
            }
            require('/' !in normalized && '\\' !in normalized) {
                "MMS display name cannot contain a path"
            }
            return normalized
        }
    }
}
