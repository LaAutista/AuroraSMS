// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import org.aurorasms.core.index.conversation.ConversationCursor
import org.aurorasms.core.index.conversation.ConversationPage
import org.aurorasms.core.index.conversation.ConversationPageDirection
import org.aurorasms.core.index.conversation.ConversationSummary
import org.aurorasms.core.model.ProviderThreadId

data class BoundedInboxWindow(
    /** Newest-first canonical order. */
    val items: List<ConversationSummary>,
    val olderCursor: ConversationCursor?,
    val newerCursor: ConversationCursor? = null,
    val pendingNewer: Boolean = false,
) {
    init {
        require(items.size <= MAXIMUM_RETAINED_INBOX_ROWS) { "Inbox windows must remain bounded" }
        require(items.map(ConversationSummary::providerThreadId).distinct().size == items.size) {
            "Inbox windows cannot contain duplicate threads"
        }
    }

    fun appendOlder(
        page: ConversationPage,
        visibleAnchor: WindowAnchor<ProviderThreadId>,
    ): WindowMutation<BoundedInboxWindow, ProviderThreadId> {
        require(page.direction == ConversationPageDirection.OLDER) { "Older inbox loads require an older page" }
        val combined = distinctNewestFirst(items + page.items).toMutableList()
        trimFromNewestPreserving(combined, visibleAnchor.stableKey)
        return WindowMutation(
            window = copy(items = combined, olderCursor = page.next),
            restoreAnchor = visibleAnchor.takeIf { anchor -> combined.any { it.providerThreadId == anchor.stableKey } },
        )
    }

    fun replaceNewest(page: ConversationPage): BoundedInboxWindow {
        require(page.direction == ConversationPageDirection.OLDER) { "Newest replacement starts with the first older page" }
        return BoundedInboxWindow(
            items = distinctNewestFirst(page.items).take(MAXIMUM_RETAINED_INBOX_ROWS),
            olderCursor = page.next,
            newerCursor = null,
            pendingNewer = false,
        )
    }

    fun refreshNewest(page: ConversationPage): BoundedInboxWindow {
        require(page.direction == ConversationPageDirection.OLDER) { "Inbox refresh starts at newest" }
        val merged = distinctNewestFirst(page.items + items).take(MAXIMUM_RETAINED_INBOX_ROWS)
        return copy(
            items = merged,
            // A first paint can occur before the index has discovered enough
            // conversations to expose an older page. Admit the cursor once a
            // later invalidation proves that older rows now exist; otherwise a
            // nonempty window with a null cursor can never page into them.
            olderCursor = olderCursor ?: page.next,
            newerCursor = null,
            pendingNewer = false,
        )
    }

    fun prependNewer(
        page: ConversationPage,
        userAtNewest: Boolean,
        visibleAnchor: WindowAnchor<ProviderThreadId>?,
    ): WindowMutation<BoundedInboxWindow, ProviderThreadId> {
        require(page.direction == ConversationPageDirection.NEWER) { "Newer inbox loads require a newer page" }
        if (!userAtNewest) {
            return WindowMutation(
                window = copy(pendingNewer = true),
                restoreAnchor = visibleAnchor,
            )
        }

        val combined = distinctNewestFirst(page.items + items).toMutableList()
        trimFromOldestPreserving(combined, visibleAnchor?.stableKey)
        return WindowMutation(
            window = copy(
                items = combined,
                newerCursor = page.next,
                pendingNewer = page.next != null,
            ),
            restoreAnchor = visibleAnchor?.takeIf { anchor ->
                combined.any { it.providerThreadId == anchor.stableKey }
            },
        )
    }

    fun noteIncomingWhileAway(): BoundedInboxWindow = copy(pendingNewer = true)

    companion object {
        val EMPTY: BoundedInboxWindow = BoundedInboxWindow(emptyList(), null)

        fun fromNewest(page: ConversationPage): BoundedInboxWindow = EMPTY.replaceNewest(page)
    }
}

private fun distinctNewestFirst(items: List<ConversationSummary>): List<ConversationSummary> {
    val byThread = LinkedHashMap<ProviderThreadId, ConversationSummary>(items.size)
    items.forEach { summary -> byThread.putIfAbsent(summary.providerThreadId, summary) }
    return byThread.values.toList()
}

private fun trimFromNewestPreserving(
    items: MutableList<ConversationSummary>,
    anchor: ProviderThreadId,
) {
    while (items.size > MAXIMUM_RETAINED_INBOX_ROWS) {
        if (items.first().providerThreadId == anchor) {
            items.removeAt(items.lastIndex)
        } else {
            items.removeAt(0)
        }
    }
}

private fun trimFromOldestPreserving(
    items: MutableList<ConversationSummary>,
    anchor: ProviderThreadId?,
) {
    while (items.size > MAXIMUM_RETAINED_INBOX_ROWS) {
        if (items.last().providerThreadId == anchor) {
            items.removeAt(0)
        } else {
            items.removeAt(items.lastIndex)
        }
    }
}

const val MAXIMUM_RETAINED_INBOX_ROWS: Int = 200
