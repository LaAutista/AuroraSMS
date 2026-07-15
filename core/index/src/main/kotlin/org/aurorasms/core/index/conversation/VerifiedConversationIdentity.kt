// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.conversation

import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId

/**
 * One bounded participant identity proven against a verified-complete index generation.
 *
 * Participant values remain exact provider-derived addresses. They are sorted only to give
 * downstream one-way identity derivation deterministic input; this model does not claim E.164 or
 * any other address equivalence. It is ephemeral index output and must never be logged or stored
 * outside a privacy-reviewed identity boundary.
 */
data class VerifiedConversationIdentity(
    val providerThreadId: ProviderThreadId,
    val generationId: Long,
    val participants: List<ParticipantAddress>,
) {
    init {
        require(generationId > 0L) { "Verified conversation identities require a generation" }
        require(participants.size in 1..MAXIMUM_VERIFIED_CONVERSATION_PARTICIPANTS) {
            "Verified conversation identities require a bounded non-empty participant set"
        }
        require(participants.zipWithNext().all { (first, second) -> first.value < second.value }) {
            "Verified conversation participants must be distinct and canonically ordered"
        }
    }

    override fun toString(): String =
        "VerifiedConversationIdentity(participantCount=${participants.size}, REDACTED)"
}

const val MAXIMUM_VERIFIED_CONVERSATION_PARTICIPANTS: Int = 100
