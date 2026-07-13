// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.timeline

import org.aurorasms.core.index.IndexCoverage
import org.aurorasms.core.model.ProviderThreadId

enum class TimelinePageDirection {
    LATEST,
    OLDER,
    NEWER,
}

data class TimelineCursor(
    val generationId: Long,
    val providerThreadId: ProviderThreadId,
    val timestampMillis: Long,
    val localRowId: Long,
) {
    init {
        require(generationId > 0L) { "Timeline cursors require a positive generation" }
        require(timestampMillis >= 0L) { "Timeline cursor timestamps cannot be negative" }
        require(localRowId > 0L) { "Timeline cursor rows must be positive" }
    }

    override fun toString(): String = "TimelineCursor(REDACTED)"
}

data class TimelinePageRequest(
    val providerThreadId: ProviderThreadId,
    val limit: Int = DEFAULT_TIMELINE_PAGE_SIZE,
    val cursor: TimelineCursor? = null,
    val direction: TimelinePageDirection = TimelinePageDirection.LATEST,
) {
    init {
        require(limit in 1..MAXIMUM_TIMELINE_PAGE_SIZE) { "Timeline page size is outside the reviewed bound" }
        require((direction == TimelinePageDirection.LATEST) == (cursor == null)) {
            "Latest timeline requests have no cursor; older/newer requests require one"
        }
        require(cursor == null || cursor.providerThreadId == providerThreadId) {
            "Timeline cursors cannot cross provider threads"
        }
    }
}

data class TimelinePage(
    /** Chronological canonical thread order. */
    val items: List<TimelineMessage>,
    val next: TimelineCursor?,
    val direction: TimelinePageDirection,
    val coverage: IndexCoverage,
) {
    init {
        require(items.size <= MAXIMUM_TIMELINE_PAGE_SIZE) { "Timeline pages must remain bounded" }
        require(next == null || items.isNotEmpty()) { "An empty page cannot continue" }
    }

    override fun toString(): String =
        "TimelinePage(itemCount=${items.size}, hasNext=${next != null}, direction=$direction, coverage=$coverage)"
}

sealed interface TimelinePageResult {
    data class Page(val page: TimelinePage) : TimelinePageResult
    data class StaleGeneration(val coverage: IndexCoverage) : TimelinePageResult
    data class MissingThread(val coverage: IndexCoverage) : TimelinePageResult
    data class StorageUnavailable(val coverage: IndexCoverage) : TimelinePageResult
}

const val DEFAULT_TIMELINE_PAGE_SIZE: Int = 50
const val MAXIMUM_TIMELINE_PAGE_SIZE: Int = 100
