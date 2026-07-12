// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index

interface MessageIndex {
    suspend fun coverage(): IndexCoverage

    suspend fun search(request: SearchRequest): SearchResult

    suspend fun loadAnchor(
        anchor: SearchAnchor,
        halfWindow: Int = DEFAULT_ANCHOR_HALF_WINDOW,
    ): AnchorWindowResult
}
