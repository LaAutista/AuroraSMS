// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import org.aurorasms.core.model.ParticipantAddress

data class ResolvedContact(
    val address: ParticipantAddress,
    val displayName: String?,
    val photoUri: String?,
) {
    val displayNameOrAddress: String
        get() = displayName?.takeIf(String::isNotBlank) ?: address.value

    override fun toString(): String = "ResolvedContact(REDACTED)"
}

interface ContactResolver {
    /** Always returns one item per requested address; denial degrades to address-only rows. */
    suspend fun resolve(addresses: List<ParticipantAddress>): List<ResolvedContact>
}
