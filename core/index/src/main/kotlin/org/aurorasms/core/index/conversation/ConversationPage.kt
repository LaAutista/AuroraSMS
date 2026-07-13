// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.conversation

import org.aurorasms.core.index.IndexCoverage

enum class ConversationPageDirection {
    OLDER,
    NEWER,
}

data class ConversationCursor(
    val generationId: Long,
    val latestTimestampMillis: Long,
    val latestLocalRowId: Long,
) {
    init {
        require(generationId > 0L) { "Conversation cursors require a positive generation" }
        require(latestTimestampMillis >= 0L) { "Conversation cursor timestamps cannot be negative" }
        require(latestLocalRowId > 0L) { "Conversation cursor rows must be positive" }
    }

    override fun toString(): String = "ConversationCursor(REDACTED)"
}

data class ConversationPageRequest(
    val limit: Int = DEFAULT_CONVERSATION_PAGE_SIZE,
    val cursor: ConversationCursor? = null,
    val direction: ConversationPageDirection = ConversationPageDirection.OLDER,
) {
    init {
        require(limit in 1..MAXIMUM_CONVERSATION_PAGE_SIZE) { "Conversation page size is outside the reviewed bound" }
        require(cursor != null || direction == ConversationPageDirection.OLDER) {
            "The first inbox page starts at the newest boundary"
        }
    }
}

data class ConversationPage(
    /** Newest-first canonical inbox order. */
    val items: List<ConversationSummary>,
    val next: ConversationCursor?,
    val direction: ConversationPageDirection,
    val coverage: IndexCoverage,
) {
    init {
        require(items.size <= MAXIMUM_CONVERSATION_PAGE_SIZE) { "Conversation pages must remain bounded" }
        require(next == null || items.isNotEmpty()) { "An empty page cannot continue" }
        require(coverage.generationId != null || items.isEmpty()) { "Conversation rows require generation coverage" }
    }

    override fun toString(): String =
        "ConversationPage(itemCount=${items.size}, hasNext=${next != null}, direction=$direction, coverage=$coverage)"
}

sealed interface ConversationPageResult {
    data class Page(val page: ConversationPage) : ConversationPageResult
    data class StaleGeneration(val coverage: IndexCoverage) : ConversationPageResult
    data class StorageUnavailable(val coverage: IndexCoverage) : ConversationPageResult
}

const val DEFAULT_CONVERSATION_PAGE_SIZE: Int = 50
const val MAXIMUM_CONVERSATION_PAGE_SIZE: Int = 100
