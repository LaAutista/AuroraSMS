// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ParticipantAddressEquivalenceKey

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
            val unique = linkedMapOf<ParticipantAddressEquivalenceKey, ParticipantAddress>()
            addresses.forEachIndexed { index, address ->
                val key = ParticipantAddressEquivalenceKey.from(address)
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

    }
}
