// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import org.aurorasms.core.model.ParticipantAddress

/** One validated, read-only address suggestion from the platform contacts provider. */
data class DiscoveredContact(
    val address: ParticipantAddress,
    val displayName: String?,
    val photoUri: String?,
) {
    init {
        require(RecipientSet.from(listOf(address)) is RecipientSet.CreationResult.Valid) {
            "Discovered contact address is not selectable"
        }
        require(displayName.isValidOptionalContactMetadata(MAXIMUM_CONTACT_DISPLAY_NAME_CHARACTERS)) {
            "Contact display metadata is invalid"
        }
        require(photoUri.isValidOptionalContactMetadata(MAXIMUM_CONTACT_PHOTO_URI_CHARACTERS)) {
            "Contact photo metadata is invalid"
        }
    }

    val displayNameOrAddress: String
        get() = displayName ?: address.value

    override fun toString(): String = "DiscoveredContact(REDACTED)"
}

sealed interface ContactDiscoveryResult {
    data class Available(
        val contacts: List<DiscoveredContact>,
        val truncated: Boolean,
    ) : ContactDiscoveryResult {
        init {
            require(contacts.size <= MAXIMUM_CONTACT_DISCOVERY_RESULTS) {
                "Contact discovery results exceed the reviewed bound"
            }
            val recipients = RecipientSet.from(contacts.map(DiscoveredContact::address))
            require(
                contacts.isEmpty() ||
                    (recipients as? RecipientSet.CreationResult.Valid)?.recipients?.size == contacts.size,
            ) {
                "Contact discovery results must use unique addresses"
            }
        }

        override fun toString(): String =
            "ContactDiscoveryResult.Available(contactCount=${contacts.size}, truncated=$truncated)"
    }

    /** The query or requested result limit was outside the public bounded contract. */
    data object InvalidRequest : ContactDiscoveryResult

    /** Contact access is absent or was revoked while the provider query was in flight. */
    data object PermissionDenied : ContactDiscoveryResult

    /** The platform contacts provider could not return one coherent bounded snapshot. */
    data object Unavailable : ContactDiscoveryResult
}

fun interface ContactDiscovery {
    /**
     * Searches phone rows without mutating contacts. Blank, control-bearing, oversized queries and
     * result limits outside `1..MAXIMUM_CONTACT_DISCOVERY_RESULTS` return [ContactDiscoveryResult.InvalidRequest].
     */
    suspend fun discover(
        query: String,
        resultLimit: Int,
    ): ContactDiscoveryResult
}

/** Safe dependency default for surfaces that do not support contact-provider discovery. */
data object UnavailableContactDiscovery : ContactDiscovery {
    override suspend fun discover(
        query: String,
        resultLimit: Int,
    ): ContactDiscoveryResult =
        if (normalizeContactDiscoveryQuery(query, resultLimit) == null) {
            ContactDiscoveryResult.InvalidRequest
        } else {
            ContactDiscoveryResult.Unavailable
        }
}

const val DEFAULT_CONTACT_DISCOVERY_RESULT_LIMIT: Int = 20
const val MAXIMUM_CONTACT_DISCOVERY_RESULTS: Int = 50
const val MAXIMUM_CONTACT_DISCOVERY_QUERY_CHARACTERS: Int = 100
const val MAXIMUM_CONTACT_DISPLAY_NAME_CHARACTERS: Int = 1_000
const val MAXIMUM_CONTACT_PHOTO_URI_CHARACTERS: Int = 2_048

internal fun normalizeContactDiscoveryQuery(query: String, resultLimit: Int): String? {
    if (query.length !in 1..MAXIMUM_CONTACT_DISCOVERY_QUERY_CHARACTERS) return null
    if (resultLimit !in 1..MAXIMUM_CONTACT_DISCOVERY_RESULTS) return null
    if (query.any(Char::isISOControl)) return null
    return query.trim().takeIf(String::isNotEmpty)
}

private fun String?.isValidOptionalContactMetadata(maxLength: Int): Boolean =
    this == null ||
        (
            isNotEmpty() &&
                this == trim() &&
                length <= maxLength &&
                none(Char::isISOControl)
            )
