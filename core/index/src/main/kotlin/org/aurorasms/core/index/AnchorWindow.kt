// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index

sealed interface AnchorWindowResult {
    data class Found(
        val messages: List<SearchHit>,
        val highlightedLocalRowId: Long,
        val anchorPosition: Int,
        val reResolvedAfterRebuild: Boolean,
        val coverage: IndexCoverage,
    ) : AnchorWindowResult {
        init {
            require(messages.isNotEmpty()) { "A found anchor window cannot be empty" }
            require(messages.size <= MAXIMUM_ANCHOR_WINDOW_SIZE) { "Anchor windows must remain bounded" }
            require(anchorPosition in messages.indices) { "Anchor position must identify a returned row" }
            require(messages[anchorPosition].localRowId == highlightedLocalRowId) {
                "Anchor position and highlighted row must agree"
            }
        }

        override fun toString(): String =
            "AnchorWindowResult.Found(messageCount=${messages.size}, anchorPosition=$anchorPosition, " +
                "reResolvedAfterRebuild=$reResolvedAfterRebuild, coverage=$coverage)"
    }

    data class NotFound(val coverage: IndexCoverage) : AnchorWindowResult
}

const val DEFAULT_ANCHOR_HALF_WINDOW: Int = 25
const val MAXIMUM_ANCHOR_HALF_WINDOW: Int = 50
const val MAXIMUM_ANCHOR_WINDOW_SIZE: Int = (MAXIMUM_ANCHOR_HALF_WINDOW * 2) + 1
