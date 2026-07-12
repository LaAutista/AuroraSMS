// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index

data class SearchPage(
    val items: List<SearchHit>,
    val next: SearchCursor?,
    val coverage: IndexCoverage,
) {
    init {
        require(items.size <= MAXIMUM_SEARCH_PAGE_SIZE) { "Search pages must remain bounded" }
        require(next == null || items.isNotEmpty()) { "An empty search page cannot have a next cursor" }
    }

    override fun toString(): String =
        "SearchPage(itemCount=${items.size}, hasNext=${next != null}, coverage=$coverage)"
}

sealed interface SearchResult {
    data class Page(val page: SearchPage) : SearchResult {
        override fun toString(): String = "SearchResult.Page($page)"
    }
    data object NoQuery : SearchResult
    data class Invalid(val reason: SearchValidationFailure) : SearchResult
}
