// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.timeline

interface ThreadTimelineRepository {
    suspend fun load(request: TimelinePageRequest): TimelinePageResult
}
