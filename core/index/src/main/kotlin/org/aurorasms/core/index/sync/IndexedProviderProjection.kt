// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.sync

import org.aurorasms.core.index.conversation.MAXIMUM_VERIFIED_CONVERSATION_PARTICIPANTS
import org.aurorasms.core.index.storage.IndexedMessageEntity
import org.aurorasms.core.model.ParticipantAddress

data class IndexedProviderProjection(
    val message: IndexedMessageEntity,
    val participantAddresses: List<String>,
    val participantsTruncated: Boolean,
) {
    init {
        require(participantAddresses.size <= MAXIMUM_INDEXED_CONVERSATION_PARTICIPANTS) {
            "One provider projection cannot exceed the participant bound"
        }
        require(participantAddresses == participantAddresses.distinct()) {
            "Provider projection participants must be unique"
        }
        participantAddresses.forEach(::ParticipantAddress)
    }

    override fun toString(): String =
        "IndexedProviderProjection(participantCount=${participantAddresses.size}, " +
            "participantsTruncated=$participantsTruncated, message=REDACTED)"

    companion object {
        fun fromMessageOnly(message: IndexedMessageEntity): IndexedProviderProjection = IndexedProviderProjection(
            message = message,
            participantAddresses = listOfNotNull(message.senderAddress),
            // This compatibility path has no bounded participant projection,
            // so it must never claim that conversation identity is complete.
            participantsTruncated = true,
        )
    }
}

const val MAXIMUM_INDEXED_CONVERSATION_PARTICIPANTS: Int =
    MAXIMUM_VERIFIED_CONVERSATION_PARTICIPANTS
