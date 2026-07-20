// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state

import java.nio.charset.CharacterCodingException

/** Bounded user-authored text appended to an outgoing message at the send boundary. */
class MessageSignature private constructor(
    val value: String,
) {
    override fun equals(other: Any?): Boolean =
        other is MessageSignature && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "MessageSignature(length=${value.length}, REDACTED)"

    companion object {
        const val MAX_CHARACTERS: Int = 160
        const val MAX_LINES: Int = 4

        /**
         * Normalizes editor line endings and surrounding whitespace. Blank input disables a
         * signature. Invalid or over-bound input is rejected rather than truncated.
         */
        fun fromUserInput(input: String): MessageSignature? {
            val normalized = input.replace("\r\n", "\n").replace('\r', '\n').trim()
            if (normalized.isEmpty()) return null
            return fromValidatedValue(normalized)
        }

        internal fun fromStorageValue(value: String): MessageSignature =
            requireNotNull(fromValidatedValue(value)) { "Stored message signature is invalid" }

        private fun fromValidatedValue(value: String): MessageSignature? =
            value.takeIf {
                it.length in 1..MAX_CHARACTERS &&
                    it.trim() == it &&
                    it.count { character -> character == '\n' } < MAX_LINES &&
                    it.none { character -> character.isISOControl() && character != '\n' } &&
                    it.hasValidUnicode()
            }?.let(::MessageSignature)

        private fun String.hasValidUnicode(): Boolean = try {
            encodeToByteArray(throwOnInvalidSequence = true)
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }
}

const val MESSAGE_SIGNATURE_SEPARATOR: String = "\n-- \n"

/** Returns the exact body to count and submit, or null if the bounded draft limit is exceeded. */
fun resolveOutgoingBody(body: String, signature: MessageSignature?): String? {
    if (body.length > Draft.MAX_BODY_CHARACTERS) return null
    if (signature == null) return body
    val additionalCharacters = MESSAGE_SIGNATURE_SEPARATOR.length + signature.value.length
    if (body.length > Draft.MAX_BODY_CHARACTERS - additionalCharacters) return null
    return body + MESSAGE_SIGNATURE_SEPARATOR + signature.value
}
