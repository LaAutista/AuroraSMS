// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.testing

import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.telephony.ContactResolver
import org.aurorasms.core.telephony.ResolvedContact

class FakeContactResolver(
    private val contacts: MutableMap<ParticipantAddress, ResolvedContact> = linkedMapOf(),
) : ContactResolver {
    val requests = mutableListOf<List<ParticipantAddress>>()

    override suspend fun resolve(addresses: List<ParticipantAddress>): List<ResolvedContact> {
        requests += addresses.toList()
        return addresses.map { address ->
            contacts[address] ?: ResolvedContact(address, displayName = null, photoUri = null)
        }
    }

    fun put(contact: ResolvedContact) {
        contacts[contact.address] = contact
    }
}
