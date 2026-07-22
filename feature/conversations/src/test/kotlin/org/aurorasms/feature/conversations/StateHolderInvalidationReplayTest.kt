// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aurorasms.core.index.AnchorWindowResult
import org.aurorasms.core.index.IndexCoverage
import org.aurorasms.core.index.IndexRunState
import org.aurorasms.core.index.MessageIndex
import org.aurorasms.core.index.SearchAnchor
import org.aurorasms.core.index.SearchRequest
import org.aurorasms.core.index.SearchResult
import org.aurorasms.core.index.conversation.ConversationInvalidation
import org.aurorasms.core.index.conversation.ConversationLookupResult
import org.aurorasms.core.index.conversation.ConversationPage
import org.aurorasms.core.index.conversation.ConversationPageDirection
import org.aurorasms.core.index.conversation.ConversationPageRequest
import org.aurorasms.core.index.conversation.ConversationPageResult
import org.aurorasms.core.index.conversation.ConversationRepository
import org.aurorasms.core.index.conversation.ConversationSummary
import org.aurorasms.core.index.timeline.ThreadTimelineRepository
import org.aurorasms.core.index.timeline.TimelineContentResult
import org.aurorasms.core.index.timeline.TimelineMessage
import org.aurorasms.core.index.timeline.TimelinePage
import org.aurorasms.core.index.timeline.TimelinePageDirection
import org.aurorasms.core.index.timeline.TimelinePageRequest
import org.aurorasms.core.index.timeline.TimelinePageResult
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.telephony.ContactCache
import org.aurorasms.core.telephony.ContactCacheInvalidation
import org.aurorasms.core.telephony.ResolvedContact
import org.aurorasms.core.telephony.SubscriptionRepository
import org.aurorasms.core.telephony.SubscriptionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class StateHolderInvalidationReplayTest {
    @Test
    fun `inbox conflates invalidations received during a page load into one replay`() = runBlocking {
        val repository = BlockingInboxRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val holder = InboxStateHolder(repository, ReplayEmptyContactCache, scope)

        try {
            holder.awaitInboxRow(1L)

            repository.invalidate()
            withTimeout(REPLAY_TIMEOUT_MILLIS) { repository.blockedLoadStarted.await() }
            repository.invalidate()
            repository.invalidate()
            repository.releaseBlockedLoad.complete(Unit)

            val ready = holder.awaitInboxRow(3L)

            assertEquals(listOf(3L, 2L, 1L), ready.window.items.map { it.providerThreadId.value })
            assertEquals(3, repository.loadCount)
            assertEquals(1, repository.maximumConcurrentLoads)
        } finally {
            holder.close()
            scope.cancel()
        }
    }

    @Test
    fun `thread conflates invalidations received during a page load into one replay`() = runBlocking {
        val conversations = ReplayConversationRepository()
        val timeline = BlockingTimelineRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val holder = ThreadStateHolder(
            providerThreadId = REPLAY_THREAD_ID,
            repository = timeline,
            conversationRepository = conversations,
            messageIndex = ReplayMessageIndex,
            contactCache = ReplayEmptyContactCache,
            subscriptionRepository = ReplayEmptySubscriptionRepository,
            scope = scope,
        )

        try {
            holder.awaitThreadRow(1L)

            conversations.invalidate()
            withTimeout(REPLAY_TIMEOUT_MILLIS) { timeline.blockedLoadStarted.await() }
            conversations.invalidate()
            conversations.invalidate()
            timeline.releaseBlockedLoad.complete(Unit)

            val ready = holder.awaitThreadRow(3L)

            assertEquals(listOf(1L, 2L, 3L), ready.window.items.map(TimelineMessage::localRowId))
            assertEquals(
                listOf(
                    TimelinePageDirection.LATEST,
                    TimelinePageDirection.NEWER,
                    TimelinePageDirection.NEWER,
                ),
                timeline.directions,
            )
            assertEquals(3, timeline.loadCount)
            assertEquals(1, timeline.maximumConcurrentLoads)
        } finally {
            holder.close()
            scope.cancel()
        }
    }
}

private suspend fun InboxStateHolder.awaitInboxRow(row: Long): InboxUiState.Ready =
    withTimeout(REPLAY_TIMEOUT_MILLIS) {
        state.first { value ->
            value is InboxUiState.Ready && value.window.items.firstOrNull()?.providerThreadId?.value == row
        } as InboxUiState.Ready
    }

private suspend fun ThreadStateHolder.awaitThreadRow(row: Long): ThreadUiState.Ready =
    withTimeout(REPLAY_TIMEOUT_MILLIS) {
        state.first { value ->
            value is ThreadUiState.Ready && value.window.items.lastOrNull()?.localRowId == row
        } as ThreadUiState.Ready
    }

private class BlockingInboxRepository : ConversationRepository {
    private val signals = MutableSharedFlow<ConversationInvalidation>()
    val blockedLoadStarted = CompletableDeferred<Unit>()
    val releaseBlockedLoad = CompletableDeferred<Unit>()
    var loadCount: Int = 0
        private set
    var maximumConcurrentLoads: Int = 0
        private set
    private var activeLoads: Int = 0

    override val invalidations: Flow<ConversationInvalidation> = signals

    suspend fun invalidate() {
        signals.emit(ConversationInvalidation)
    }

    override suspend fun loadInbox(request: ConversationPageRequest): ConversationPageResult {
        check(request.cursor == null)
        loadCount += 1
        activeLoads += 1
        maximumConcurrentLoads = maxOf(maximumConcurrentLoads, activeLoads)
        try {
            val row = when (loadCount) {
                1 -> 1L
                2 -> {
                    blockedLoadStarted.complete(Unit)
                    releaseBlockedLoad.await()
                    2L
                }
                3 -> 3L
                else -> throw AssertionError("inbox invalidations were not conflated")
            }
            return ConversationPageResult.Page(
                ConversationPage(
                    items = listOf(replayConversation(row)),
                    next = null,
                    direction = ConversationPageDirection.OLDER,
                    coverage = REPLAY_COVERAGE,
                ),
            )
        } finally {
            activeLoads -= 1
        }
    }

    override suspend fun loadConversation(
        providerThreadId: ProviderThreadId,
    ): ConversationLookupResult = throw AssertionError("unexpected conversation lookup")
}

private class ReplayConversationRepository : ConversationRepository {
    private val signals = MutableSharedFlow<ConversationInvalidation>()

    override val invalidations: Flow<ConversationInvalidation> = signals

    suspend fun invalidate() {
        signals.emit(ConversationInvalidation)
    }

    override suspend fun loadInbox(request: ConversationPageRequest): ConversationPageResult =
        throw AssertionError("unexpected inbox load")

    override suspend fun loadConversation(
        providerThreadId: ProviderThreadId,
    ): ConversationLookupResult {
        check(providerThreadId == REPLAY_THREAD_ID)
        return ConversationLookupResult.Missing(REPLAY_COVERAGE)
    }
}

private class BlockingTimelineRepository : ThreadTimelineRepository {
    val blockedLoadStarted = CompletableDeferred<Unit>()
    val releaseBlockedLoad = CompletableDeferred<Unit>()
    private val requests = mutableListOf<TimelinePageRequest>()
    val directions: List<TimelinePageDirection>
        get() = requests.map(TimelinePageRequest::direction)
    var loadCount: Int = 0
        private set
    var maximumConcurrentLoads: Int = 0
        private set
    private var activeLoads: Int = 0

    override suspend fun load(request: TimelinePageRequest): TimelinePageResult {
        check(request.providerThreadId == REPLAY_THREAD_ID)
        requests += request
        loadCount += 1
        activeLoads += 1
        maximumConcurrentLoads = maxOf(maximumConcurrentLoads, activeLoads)
        try {
            val row = when (loadCount) {
                1 -> 1L
                2 -> {
                    blockedLoadStarted.complete(Unit)
                    releaseBlockedLoad.await()
                    2L
                }
                3 -> 3L
                else -> throw AssertionError("thread invalidations were not conflated")
            }
            return TimelinePageResult.Page(
                TimelinePage(
                    items = listOf(replayMessage(row)),
                    next = null,
                    direction = request.direction,
                    coverage = REPLAY_COVERAGE,
                ),
            )
        } finally {
            activeLoads -= 1
        }
    }

    override suspend fun loadContent(
        providerThreadId: ProviderThreadId,
        providerMessageId: ProviderMessageId,
    ): TimelineContentResult = TimelineContentResult.Missing(REPLAY_COVERAGE)
}

private object ReplayMessageIndex : MessageIndex {
    override suspend fun coverage(): IndexCoverage = REPLAY_COVERAGE

    override suspend fun search(request: SearchRequest): SearchResult = SearchResult.NoQuery

    override suspend fun loadAnchor(
        anchor: SearchAnchor,
        halfWindow: Int,
    ): AnchorWindowResult = AnchorWindowResult.NotFound(REPLAY_COVERAGE)
}

private object ReplayEmptyContactCache : ContactCache {
    override val invalidations: Flow<ContactCacheInvalidation> = emptyFlow()

    override suspend fun resolve(addresses: List<ParticipantAddress>): List<ResolvedContact> = emptyList()

    override suspend fun invalidate() = Unit
}

private object ReplayEmptySubscriptionRepository : SubscriptionRepository {
    override suspend fun activeSubscriptions(): SubscriptionSnapshot =
        SubscriptionSnapshot.Available(emptyList())
}

private fun replayConversation(row: Long): ConversationSummary = ConversationSummary(
    providerThreadId = ProviderThreadId(row),
    latestLocalRowId = row,
    latestProviderMessageId = replayMessageId(row),
    latestTimestampMillis = row,
    latestSentTimestampMillis = null,
    latestDirection = MessageDirection.INCOMING,
    latestBox = MessageBox.INBOX,
    latestStatus = MessageStatus.COMPLETE,
    latestSubscriptionId = null,
    latestSenderAddress = null,
    latestSnippet = "row-$row",
    latestAttachmentCount = 0,
    latestAttachmentTypeSummary = "",
    latestRead = true,
    indexedMessageCount = 1L,
    indexedUnreadCount = 0L,
    participants = emptyList(),
    indexedParticipantCount = 0,
    participantsTruncated = false,
)

private fun replayMessage(row: Long): TimelineMessage = TimelineMessage(
    localRowId = row,
    providerMessageId = replayMessageId(row),
    providerThreadId = REPLAY_THREAD_ID,
    timestampMillis = row,
    sentTimestampMillis = null,
    direction = MessageDirection.INCOMING,
    box = MessageBox.INBOX,
    status = MessageStatus.COMPLETE,
    subscriptionId = null,
    senderAddress = null,
    bodyPreview = "row-$row",
    bodyTruncated = false,
    subject = null,
    attachmentCount = 0,
    attachmentTypeSummary = "",
    read = true,
    seen = true,
    locked = false,
)

private fun replayMessageId(row: Long): ProviderMessageId = ProviderMessageId(ProviderKind.SMS, row)

private val REPLAY_THREAD_ID = ProviderThreadId(77L)
private val REPLAY_COVERAGE = IndexCoverage(
    generationId = 71L,
    state = IndexRunState.COMPLETE,
    indexedMessageCount = 3L,
    smsExhausted = true,
    mmsExhausted = true,
    pendingChanges = false,
    generationCommittedCount = 3L,
    smsCheckpointCommittedCount = 3L,
)
private const val REPLAY_TIMEOUT_MILLIS = 5_000L
