// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.model

/** An Aurora-owned conversation identifier. */
@JvmInline
value class ConversationId(val value: Long) {
    init {
        require(value > 0L) { "Conversation IDs must be positive" }
    }

    override fun toString(): String = "ConversationId(REDACTED)"
}
