// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.timeline

import org.aurorasms.core.index.IndexCoverage
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId

interface ThreadTimelineRepository {
    suspend fun load(request: TimelinePageRequest): TimelinePageResult

    suspend fun loadContent(
        providerThreadId: ProviderThreadId,
        providerMessageId: ProviderMessageId,
    ): TimelineContentResult
}

data class TimelineMessageContent(
    val providerMessageId: ProviderMessageId,
    val body: String?,
    val subject: String?,
    val sourceTruncated: Boolean,
) {
    init {
        require(body == null || body.length <= MAXIMUM_TIMELINE_FULL_BODY_CHARACTERS)
        require(subject == null || subject.length <= MAXIMUM_TIMELINE_FULL_SUBJECT_CHARACTERS)
    }

    override fun toString(): String = "TimelineMessageContent(sourceTruncated=$sourceTruncated, REDACTED)"
}

sealed interface TimelineContentResult {
    data class Found(
        val content: TimelineMessageContent,
        val coverage: IndexCoverage,
    ) : TimelineContentResult {
        override fun toString(): String = "TimelineContentResult.Found(coverage=$coverage, REDACTED)"
    }

    data class Missing(val coverage: IndexCoverage) : TimelineContentResult
    data class StorageUnavailable(val coverage: IndexCoverage) : TimelineContentResult
}

const val MAXIMUM_TIMELINE_FULL_BODY_CHARACTERS: Int = 100_000
const val MAXIMUM_TIMELINE_FULL_SUBJECT_CHARACTERS: Int = 1_000
