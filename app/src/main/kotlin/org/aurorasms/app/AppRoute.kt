// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import org.aurorasms.core.index.SearchAnchor
import org.aurorasms.core.model.ProviderThreadId

internal sealed interface AppRoute {
    data object Inbox : AppRoute

    data object Appearance : AppRoute

    data object SpamBlocked : AppRoute

    data class Search(val query: String = "") : AppRoute {
        override fun toString(): String = "AppRoute.Search(queryLength=${query.length}, REDACTED)"
    }

    data class Thread(
        val providerThreadId: ProviderThreadId,
        val anchor: SearchAnchor? = null,
        val stateEntryId: Long = 0L,
    ) : AppRoute {
        init {
            require(anchor == null || anchor.providerThreadId == providerThreadId) {
                "A thread route cannot contain an anchor from another thread"
            }
            require(stateEntryId >= 0L) { "A thread route state entry ID cannot be negative" }
        }

        override fun toString(): String = "AppRoute.Thread(hasAnchor=${anchor != null}, REDACTED)"
    }
}
