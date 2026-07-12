// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.testing

import org.aurorasms.core.model.ParticipantAddress

data class SyntheticPerson(
    val displayName: String,
    val address: ParticipantAddress,
)

/** Clearly fictional identities using NANP's reserved 555-01xx range or .invalid. */
object SyntheticPeople {
    val NOVA = SyntheticPerson(
        displayName = "Nova Reed",
        address = ParticipantAddress("+12025550101"),
    )
    val MILO = SyntheticPerson(
        displayName = "Milo Chen",
        address = ParticipantAddress("+12025550102"),
    )
    val ORBITAL_CLUB = SyntheticPerson(
        displayName = "Orbital Club",
        address = ParticipantAddress("orbital-club@example.invalid"),
    )

    val all: List<SyntheticPerson> = listOf(NOVA, MILO, ORBITAL_CLUB)
}
