// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index

import org.aurorasms.core.model.ProviderThreadId

data class SearchRequest(
    val rawQuery: String,
    val threadId: ProviderThreadId? = null,
    val limit: Int = DEFAULT_SEARCH_PAGE_SIZE,
    val cursor: SearchCursor? = null,
) {
    init {
        require(limit in 1..MAXIMUM_SEARCH_PAGE_SIZE) {
            "Search page size must be in 1..$MAXIMUM_SEARCH_PAGE_SIZE"
        }
    }

    override fun toString(): String =
        "SearchRequest(REDACTED, threadScoped=${threadId != null}, limit=$limit, hasCursor=${cursor != null})"
}

data class SearchCursor(
    val timestampMillis: Long,
    val localRowId: Long,
    val queryFingerprint: String,
) {
    init {
        require(timestampMillis >= 0L) { "Search timestamps cannot be negative" }
        require(localRowId > 0L) { "Search row IDs must be positive" }
        require(queryFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Search cursors require a fixed lowercase SHA-256 fingerprint"
        }
    }

    override fun toString(): String = "SearchCursor(REDACTED)"
}

enum class SearchValidationFailure {
    CONTROL_CHARACTER,
    QUERY_TOO_LONG,
    TOO_MANY_TERMS,
    TERM_TOO_LONG,
    TOO_MANY_PHRASES,
    PHRASE_TOO_LONG,
    EMPTY_PHRASE,
    UNMATCHED_QUOTE,
    CURSOR_MISMATCH,
}

const val DEFAULT_SEARCH_PAGE_SIZE: Int = 50
const val MAXIMUM_SEARCH_PAGE_SIZE: Int = 100
