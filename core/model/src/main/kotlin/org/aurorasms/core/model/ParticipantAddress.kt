// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.model

/**
 * An unredacted participant address kept only at the typed data boundary.
 *
 * It intentionally does not claim E.164 normalization. Carrier short codes,
 * alphanumeric senders, email-style MMS addresses, and national numbers are
 * valid platform inputs and must not be silently rewritten here.
 */
@JvmInline
value class ParticipantAddress(val value: String) {
    init {
        require(value.isNotBlank()) { "Participant addresses cannot be blank" }
        require(value == value.trim()) { "Participant addresses must be trimmed" }
        require(value.length <= MAX_ADDRESS_CHARACTERS) { "Participant address is too long" }
        require(value.none(Char::isISOControl)) { "Participant address contains a control character" }
    }

    override fun toString(): String = "ParticipantAddress(REDACTED)"

    companion object {
        const val MAX_ADDRESS_CHARACTERS: Int = 320
    }
}
