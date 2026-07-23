// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * A redacted, deterministic key for Aurora's deliberately conservative address equivalence.
 *
 * This is not an E.164 or platform-recipient normalizer. It only removes punctuation from
 * phone-like inputs, folds an email domain to lowercase, and otherwise preserves the address
 * exactly. Address kinds are tagged so values from different semantic domains cannot collide.
 */
class ParticipantAddressEquivalenceKey private constructor(
    private val kind: Kind,
    private val canonicalValue: String,
) : Comparable<ParticipantAddressEquivalenceKey> {
    override fun compareTo(other: ParticipantAddressEquivalenceKey): Int {
        val kindComparison = kind.tag.compareTo(other.kind.tag)
        return if (kindComparison != 0) kindComparison else canonicalValue.compareTo(other.canonicalValue)
    }

    override fun equals(other: Any?): Boolean =
        other is ParticipantAddressEquivalenceKey &&
            kind == other.kind &&
            canonicalValue == other.canonicalValue

    override fun hashCode(): Int = 31 * kind.hashCode() + canonicalValue.hashCode()

    /**
     * Feeds the tagged, length-delimited canonical value into a caller-owned digest.
     *
     * This permits deterministic set keys without publishing the unredacted canonical address.
     */
    fun updateDigest(digest: MessageDigest) {
        val bytes = canonicalValue.toByteArray(StandardCharsets.UTF_8)
        digest.update(kind.tag)
        digest.update((bytes.size ushr 24).toByte())
        digest.update((bytes.size ushr 16).toByte())
        digest.update((bytes.size ushr 8).toByte())
        digest.update(bytes.size.toByte())
        digest.update(bytes)
    }

    override fun toString(): String = "ParticipantAddressEquivalenceKey(REDACTED)"

    private enum class Kind(val tag: Byte) {
        PHONE(1),
        EMAIL(2),
        OPAQUE(3),
    }

    companion object {
        /** Returns null only when a phone-like input contains no digits. */
        fun from(address: ParticipantAddress): ParticipantAddressEquivalenceKey? {
            val value = address.value
            val phoneLike = value.all { character ->
                character.isDigit() || character in PHONE_PUNCTUATION
            }
            if (phoneLike) {
                val digits = value.filter(Char::isDigit)
                if (digits.isEmpty()) return null
                val prefix = if (value.trimStart().startsWith('+')) "+" else ""
                return ParticipantAddressEquivalenceKey(Kind.PHONE, prefix + digits)
            }

            val at = value.lastIndexOf('@')
            if (at > 0 && at < value.lastIndex) {
                return ParticipantAddressEquivalenceKey(
                    kind = Kind.EMAIL,
                    canonicalValue =
                        value.substring(0, at + 1) + value.substring(at + 1).lowercase(Locale.ROOT),
                )
            }
            return ParticipantAddressEquivalenceKey(Kind.OPAQUE, value)
        }

        private val PHONE_PUNCTUATION = charArrayOf('+', ' ', '-', '(', ')', '.')
    }
}
