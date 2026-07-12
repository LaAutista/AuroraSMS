// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.coroutines.FlowPreview::class,
)

package org.aurorasms.core.index

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest

/**
 * Applies the UI-facing timing and cancellation policy to direct index calls.
 *
 * [MessageIndex] deliberately remains a suspend interface so non-UI callers
 * can query it immediately. This adapter retains only debounce's latest input
 * and lets `mapLatest` cancel an obsolete database call through structured
 * coroutine cancellation; it owns no scope, channel, or additional queue.
 */
class SearchPipeline(
    private val messageIndex: MessageIndex,
) {
    fun results(requests: Flow<SearchRequest>): Flow<SearchResult> =
        requests
            .debounce(SEARCH_DEBOUNCE_MILLIS)
            .mapLatest { request -> messageIndex.search(request) }
}

const val SEARCH_DEBOUNCE_MILLIS: Long = 150L
