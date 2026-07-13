// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import org.aurorasms.core.index.timeline.TimelineCursor
import org.aurorasms.core.index.timeline.TimelineMessage
import org.aurorasms.core.index.timeline.TimelinePage
import org.aurorasms.core.index.timeline.TimelinePageDirection
import org.aurorasms.core.model.ProviderMessageId

data class BoundedThreadWindow(
    /** Chronological canonical order. */
    val items: List<TimelineMessage>,
    val olderCursor: TimelineCursor?,
    val newerCursor: TimelineCursor?,
    val pendingNewer: Boolean = false,
) {
    val retainedTextUnits: Int
        get() = items.sumOf(TimelineMessage::retainedTextUnits)

    init {
        require(items.size <= MAXIMUM_RETAINED_THREAD_ROWS) { "Thread windows must remain row-bounded" }
        require(items.sumOf(TimelineMessage::retainedTextUnits) <= MAXIMUM_RETAINED_THREAD_TEXT_UNITS) {
            "Thread windows must remain text-bounded"
        }
        require(items.map(TimelineMessage::providerMessageId).distinct().size == items.size) {
            "Thread windows cannot contain duplicate provider messages"
        }
    }

    fun prependOlder(
        page: TimelinePage,
        visibleAnchor: WindowAnchor<ProviderMessageId>,
    ): WindowMutation<BoundedThreadWindow, ProviderMessageId> {
        require(page.direction == TimelinePageDirection.OLDER) { "Thread prepend requires an older page" }
        val combined = distinctChronological(page.items + items).toMutableList()
        trimFromNewestPreserving(combined, visibleAnchor.stableKey)
        return WindowMutation(
            window = copy(items = combined, olderCursor = page.next),
            restoreAnchor = visibleAnchor.takeIf { anchor -> combined.any { it.providerMessageId == anchor.stableKey } },
        )
    }

    fun appendNewer(
        page: TimelinePage,
        visibleAnchor: WindowAnchor<ProviderMessageId>?,
    ): WindowMutation<BoundedThreadWindow, ProviderMessageId> {
        require(page.direction == TimelinePageDirection.NEWER) { "Thread append requires a newer page" }
        val combined = distinctChronological(items + page.items).toMutableList()
        trimFromOldestPreserving(combined, visibleAnchor?.stableKey)
        return WindowMutation(
            window = copy(items = combined, newerCursor = page.next, pendingNewer = page.next != null),
            restoreAnchor = visibleAnchor?.takeIf { anchor ->
                combined.any { it.providerMessageId == anchor.stableKey }
            },
        )
    }

    fun noteIncomingWhileAway(): BoundedThreadWindow = copy(pendingNewer = true)

    fun reserveAdditionalText(
        additionalTextUnits: Int,
        preservedMessageId: ProviderMessageId,
        generationId: Long,
    ): BoundedThreadWindow {
        require(additionalTextUnits in 0..MAXIMUM_RETAINED_THREAD_TEXT_UNITS)
        require(generationId > 0L)
        require(items.any { it.providerMessageId == preservedMessageId })
        val retained = items.toMutableList()
        var removedOldest = false
        var removedNewest = false
        while (
            retained.size > 1 &&
            retained.sumOf(TimelineMessage::retainedTextUnits) + additionalTextUnits >
            MAXIMUM_RETAINED_THREAD_TEXT_UNITS
        ) {
            val anchorIndex = retained.indexOfFirst { it.providerMessageId == preservedMessageId }
            if (anchorIndex < retained.size / 2) {
                retained.removeAt(retained.lastIndex)
                removedNewest = true
            } else {
                retained.removeAt(0)
                removedOldest = true
            }
        }
        check(
            retained.sumOf(TimelineMessage::retainedTextUnits) + additionalTextUnits <=
                MAXIMUM_RETAINED_THREAD_TEXT_UNITS,
        ) { "One expanded message must fit the retained thread text budget" }
        return copy(
            items = retained,
            olderCursor = if (removedOldest) retained.first().toTimelineCursor(generationId) else olderCursor,
            newerCursor = if (removedNewest) retained.last().toTimelineCursor(generationId) else newerCursor,
        )
    }

    fun replace(page: TimelinePage): BoundedThreadWindow {
        val bounded = distinctChronological(page.items).toMutableList()
        trimFromOldestPreserving(bounded, anchor = null)
        return BoundedThreadWindow(
            items = bounded,
            olderCursor = page.next.takeIf {
                page.direction == TimelinePageDirection.LATEST || page.direction == TimelinePageDirection.OLDER
            },
            newerCursor = page.next.takeIf { page.direction == TimelinePageDirection.NEWER },
            pendingNewer = false,
        )
    }

    companion object {
        val EMPTY: BoundedThreadWindow = BoundedThreadWindow(emptyList(), null, null)

        fun fromLatest(page: TimelinePage): BoundedThreadWindow {
            require(page.direction == TimelinePageDirection.LATEST)
            return EMPTY.replace(page)
        }
    }
}

private fun distinctChronological(items: List<TimelineMessage>): List<TimelineMessage> {
    val byIdentity = LinkedHashMap<ProviderMessageId, TimelineMessage>(items.size)
    items.forEach { message -> byIdentity[message.providerMessageId] = message }
    return byIdentity.values.sortedWith(
        compareBy<TimelineMessage>(TimelineMessage::timestampMillis)
            .thenBy(TimelineMessage::localRowId),
    )
}

private fun trimFromNewestPreserving(
    items: MutableList<TimelineMessage>,
    anchor: ProviderMessageId,
) {
    while (items.exceedsThreadBounds()) {
        if (items.last().providerMessageId == anchor) {
            items.removeAt(0)
        } else {
            items.removeAt(items.lastIndex)
        }
    }
}

private fun trimFromOldestPreserving(
    items: MutableList<TimelineMessage>,
    anchor: ProviderMessageId?,
) {
    while (items.exceedsThreadBounds()) {
        if (items.first().providerMessageId == anchor) {
            items.removeAt(items.lastIndex)
        } else {
            items.removeAt(0)
        }
    }
}

private fun List<TimelineMessage>.exceedsThreadBounds(): Boolean =
    size > MAXIMUM_RETAINED_THREAD_ROWS ||
        (size > 1 && sumOf(TimelineMessage::retainedTextUnits) > MAXIMUM_RETAINED_THREAD_TEXT_UNITS)

private fun TimelineMessage.retainedTextUnits(): Int =
    (bodyPreview?.length ?: 0) + (subject?.length ?: 0)

private fun TimelineMessage.toTimelineCursor(generationId: Long): TimelineCursor = TimelineCursor(
    generationId = generationId,
    providerThreadId = providerThreadId,
    timestampMillis = timestampMillis,
    localRowId = localRowId,
)

const val MAXIMUM_RETAINED_THREAD_ROWS: Int = 200
const val MAXIMUM_RETAINED_THREAD_TEXT_UNITS: Int = 1_048_576
