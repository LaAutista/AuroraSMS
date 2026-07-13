// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.aurorasms.core.index.MessageIndex
import org.aurorasms.core.index.SearchPipeline
import org.aurorasms.core.index.SearchRequest
import org.aurorasms.core.index.SearchResult

class SearchStateHolder(
    private val messageIndex: MessageIndex,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val requests = MutableStateFlow(SearchRequest(""))
    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Empty())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var loadMoreJob: Job? = null
    private val collector = scope.launch {
        SearchPipeline(messageIndex).results(requests).collect { result ->
            val query = requests.value.rawQuery
            _state.value = result.toUiState(query)
        }
    }

    fun updateQuery(query: String) {
        _state.value = if (query.isBlank()) SearchUiState.Empty(query) else SearchUiState.Searching(query)
        requests.value = SearchRequest(query)
    }

    fun loadMore() {
        val current = _state.value as? SearchUiState.Page ?: return
        val cursor = current.next ?: return
        if (current.items.size >= MAXIMUM_RETAINED_SEARCH_ROWS || loadMoreJob?.isActive == true) return
        _state.value = current.copy(loadingMore = true)
        loadMoreJob = scope.launch {
            val result = try {
                messageIndex.search(
                    SearchRequest(
                        rawQuery = current.query,
                        limit = minOf(50, MAXIMUM_RETAINED_SEARCH_ROWS - current.items.size),
                        cursor = cursor,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                _state.value = current.copy(loadingMore = false)
                return@launch
            }
            _state.value = when (result) {
                is SearchResult.Page -> {
                    val merged = (current.items + result.page.items)
                        .distinctBy { it.localRowId }
                        .take(MAXIMUM_RETAINED_SEARCH_ROWS)
                    current.copy(
                        items = merged,
                        next = result.page.next.takeIf { merged.size < MAXIMUM_RETAINED_SEARCH_ROWS },
                        coverage = result.page.coverage,
                        loadingMore = false,
                    )
                }
                SearchResult.NoQuery -> SearchUiState.Empty(current.query)
                is SearchResult.Invalid -> SearchUiState.Invalid(current.query, result.reason)
            }
        }
    }

    override fun close() {
        collector.cancel()
        loadMoreJob?.cancel()
    }
}

private fun SearchResult.toUiState(query: String): SearchUiState = when (this) {
    is SearchResult.Page -> SearchUiState.Page(
        query = query,
        items = page.items,
        next = page.next,
        coverage = page.coverage,
    )
    SearchResult.NoQuery -> SearchUiState.Empty(query)
    is SearchResult.Invalid -> SearchUiState.Invalid(query, reason)
}
