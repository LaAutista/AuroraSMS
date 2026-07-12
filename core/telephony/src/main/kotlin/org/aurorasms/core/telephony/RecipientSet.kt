// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import java.util.Locale
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ParticipantAddress

/** Canonical recipients and the single source of the ordinary-group policy. */
class RecipientSet private constructor(
    val addresses: List<ParticipantAddress>,
) {
    val size: Int
        get() = addresses.size

    val isGroup: Boolean
        get() = size > 1

    fun requiredTransport(
        hasAttachments: Boolean = false,
        explicitlyRequestedMms: Boolean = false,
    ): MessageTransportKind =
        if (isGroup || hasAttachments || explicitlyRequestedMms) {
            MessageTransportKind.MMS
        } else {
            MessageTransportKind.SMS
        }

    /** The only legal SMS destination. A group intentionally returns null. */
    fun singleSmsRecipientOrNull(): ParticipantAddress? = addresses.singleOrNull()

    override fun equals(other: Any?): Boolean = other is RecipientSet && addresses == other.addresses

    override fun hashCode(): Int = addresses.hashCode()

    override fun toString(): String = "RecipientSet(size=$size)"

    sealed interface CreationResult {
        data class Valid(val recipients: RecipientSet) : CreationResult
        data class Rejected(val reason: RejectionReason, val inputIndex: Int? = null) : CreationResult
    }

    enum class RejectionReason {
        EMPTY,
        TOO_MANY,
        BLANK,
        TOO_LONG,
        CONTROL_CHARACTER,
        INVALID_ADDRESS,
    }

    companion object {
        const val MAX_RECIPIENTS: Int = 100

        fun parse(rawAddresses: Iterable<String>): CreationResult {
            val parsed = mutableListOf<ParticipantAddress>()
            rawAddresses.forEachIndexed { index, raw ->
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) {
                    return CreationResult.Rejected(RejectionReason.BLANK, index)
                }
                if (trimmed.length > ParticipantAddress.MAX_ADDRESS_CHARACTERS) {
                    return CreationResult.Rejected(RejectionReason.TOO_LONG, index)
                }
                if (trimmed.any(Char::isISOControl)) {
                    return CreationResult.Rejected(RejectionReason.CONTROL_CHARACTER, index)
                }
                parsed += ParticipantAddress(trimmed)
                if (parsed.size > MAX_RECIPIENTS) {
                    return CreationResult.Rejected(RejectionReason.TOO_MANY)
                }
            }
            return from(parsed)
        }

        fun from(addresses: Iterable<ParticipantAddress>): CreationResult {
            val unique = linkedMapOf<String, ParticipantAddress>()
            addresses.forEachIndexed { index, address ->
                val key = canonicalKey(address.value)
                    ?: return CreationResult.Rejected(RejectionReason.INVALID_ADDRESS, index)
                unique.putIfAbsent(key, address)
                if (unique.size > MAX_RECIPIENTS) {
                    return CreationResult.Rejected(RejectionReason.TOO_MANY)
                }
            }
            if (unique.isEmpty()) {
                return CreationResult.Rejected(RejectionReason.EMPTY)
            }
            return CreationResult.Valid(RecipientSet(unique.values.toList()))
        }

        private fun canonicalKey(value: String): String? {
            val phoneLike = value.all { character ->
                character.isDigit() || character in charArrayOf('+', ' ', '-', '(', ')', '.')
            }
            if (phoneLike) {
                val prefix = if (value.trimStart().startsWith('+')) "+" else ""
                val digits = value.filter(Char::isDigit)
                return (prefix + digits).takeIf { digits.isNotEmpty() }
            }
            val at = value.lastIndexOf('@')
            return if (at > 0 && at < value.lastIndex) {
                value.substring(0, at + 1) + value.substring(at + 1).lowercase(Locale.ROOT)
            } else {
                value
            }
        }
    }
}
