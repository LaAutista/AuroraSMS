// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import org.aurorasms.core.index.conversation.ConversationSummary
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.telephony.ResolvedContact

enum class SpamSafetyReason {
    USER_MARKED_SPAM,
    USER_BLOCKED,
    SUSPICIOUS_LINK_AND_REQUEST,
    USER_MARKED_NOT_SPAM,
    SAVED_CONTACT,
}

data class SpamSafetyIndicator(
    val reason: SpamSafetyReason,
    val warning: Boolean,
) {
    override fun toString(): String = "SpamSafetyIndicator(reason=$reason, warning=$warning)"
}

data class ThreadSpamSafetyUiState(
    val storageAvailable: Boolean = false,
    val actionsAvailable: Boolean = false,
    val blockAvailable: Boolean = false,
    val classificationSpam: Boolean = false,
    val classificationNotSpam: Boolean = false,
    val blocked: Boolean = false,
    val warningReason: SpamSafetyReason? = null,
    val saving: Boolean = false,
) {
    init {
        require(!(classificationSpam && classificationNotSpam))
        require(!actionsAvailable || storageAvailable)
        require(!blockAvailable || actionsAvailable)
    }
}

data class SpamBlockedRow(
    val summary: ConversationSummary,
    val contacts: Map<org.aurorasms.core.model.ParticipantAddress, ResolvedContact>,
    val markedSpam: Boolean,
    val blocked: Boolean,
) {
    init { require(markedSpam || blocked) }
    override fun toString(): String =
        "SpamBlockedRow(markedSpam=$markedSpam, blocked=$blocked, REDACTED)"
}

sealed interface SpamBlockedUiState {
    data object Loading : SpamBlockedUiState
    data object Unavailable : SpamBlockedUiState
    data class Ready(val rows: List<SpamBlockedRow>) : SpamBlockedUiState {
        init {
            require(rows.size <= MAXIMUM_SPAM_BLOCKED_ROWS)
            require(rows.distinctBy { it.summary.providerThreadId }.size == rows.size)
        }
        override fun toString(): String = "SpamBlockedUiState.Ready(rowCount=${rows.size}, REDACTED)"
    }
}

const val MAXIMUM_SPAM_BLOCKED_ROWS = 256

typealias SpamBlockedAction = (ProviderThreadId) -> Unit
