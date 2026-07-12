// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.aurorasms.core.index

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPipelineTest {
    @Test
    fun `latest request is searched exactly 150 milliseconds after input settles`() = runTest {
        val calls = mutableListOf<TimedRequest>()
        val results = mutableListOf<SearchResult>()
        val requests = MutableSharedFlow<SearchRequest>()
        val pipeline = SearchPipeline(
            RecordingMessageIndex { request ->
                calls += TimedRequest(request, testScheduler.currentTime)
                SearchResult.NoQuery
            },
        )
        backgroundScope.launch {
            pipeline.results(requests).collect(results::add)
        }
        runCurrent()

        val first = SearchRequest(rawQuery = "aurora")
        requests.emit(first)
        advanceTimeBy(SEARCH_DEBOUNCE_MILLIS - 1L)
        runCurrent()
        assertEquals(emptyList<TimedRequest>(), calls)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(TimedRequest(first, 150L)), calls)
        assertEquals(listOf(SearchResult.NoQuery), results)

        val obsolete = SearchRequest(rawQuery = "obsolete")
        val latest = SearchRequest(rawQuery = "latest")
        requests.emit(obsolete)
        advanceTimeBy(100L)
        requests.emit(latest)
        advanceTimeBy(SEARCH_DEBOUNCE_MILLIS - 1L)
        runCurrent()
        assertEquals(listOf(TimedRequest(first, 150L)), calls)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(
            listOf(
                TimedRequest(first, 150L),
                TimedRequest(latest, 400L),
            ),
            calls,
        )
        assertEquals(2, results.size)
    }

    @Test
    fun `new debounced request cancels obsolete search before starting replacement`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val results = mutableListOf<SearchResult>()
        val requests = MutableSharedFlow<SearchRequest>()
        val replacementResult = SearchResult.Invalid(SearchValidationFailure.UNMATCHED_QUOTE)
        val pipeline = SearchPipeline(
            RecordingMessageIndex { request ->
                events += "start:${request.rawQuery}"
                if (request.rawQuery == "first") {
                    firstStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        events += "cancel:first"
                        firstCancelled.complete(Unit)
                    }
                }
                replacementResult
            },
        )
        backgroundScope.launch {
            pipeline.results(requests).collect(results::add)
        }
        runCurrent()

        requests.emit(SearchRequest(rawQuery = "first"))
        advanceTimeBy(SEARCH_DEBOUNCE_MILLIS)
        runCurrent()
        assertTrue(firstStarted.isCompleted)
        assertEquals(listOf("start:first"), events)

        requests.emit(SearchRequest(rawQuery = "replacement"))
        advanceTimeBy(SEARCH_DEBOUNCE_MILLIS - 1L)
        runCurrent()
        assertFalse(firstCancelled.isCompleted)
        assertEquals(emptyList<SearchResult>(), results)

        advanceTimeBy(1L)
        runCurrent()
        assertTrue(firstCancelled.isCompleted)
        assertEquals(
            listOf("start:first", "cancel:first", "start:replacement"),
            events,
        )
        assertEquals(listOf(replacementResult), results)
    }

    @Test
    fun `collector cancellation propagates into active search`() = runTest {
        val searchStarted = CompletableDeferred<Unit>()
        val searchCancelled = CompletableDeferred<Unit>()
        val requests = MutableSharedFlow<SearchRequest>()
        val results = mutableListOf<SearchResult>()
        val pipeline = SearchPipeline(
            RecordingMessageIndex {
                searchStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    searchCancelled.complete(Unit)
                }
            },
        )
        val collector = backgroundScope.launch {
            pipeline.results(requests).collect(results::add)
        }
        runCurrent()

        requests.emit(SearchRequest(rawQuery = "cancel"))
        advanceTimeBy(SEARCH_DEBOUNCE_MILLIS)
        runCurrent()
        assertTrue(searchStarted.isCompleted)
        assertFalse(searchCancelled.isCompleted)

        collector.cancelAndJoin()

        assertTrue(collector.isCancelled)
        assertTrue(searchCancelled.isCompleted)
        assertEquals(emptyList<SearchResult>(), results)
    }
}

private data class TimedRequest(
    val request: SearchRequest,
    val atMillis: Long,
)

private class RecordingMessageIndex(
    private val onSearch: suspend (SearchRequest) -> SearchResult,
) : MessageIndex {
    override suspend fun coverage(): IndexCoverage = IndexCoverage.NOT_STARTED

    override suspend fun search(request: SearchRequest): SearchResult = onSearch(request)

    override suspend fun loadAnchor(
        anchor: SearchAnchor,
        halfWindow: Int,
    ): AnchorWindowResult = AnchorWindowResult.NotFound(IndexCoverage.NOT_STARTED)
}
