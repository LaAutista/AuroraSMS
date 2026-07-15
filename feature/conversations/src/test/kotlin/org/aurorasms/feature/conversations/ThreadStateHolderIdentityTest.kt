// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import java.util.ArrayDeque
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
import org.aurorasms.core.index.conversation.ConversationPageRequest
import org.aurorasms.core.index.conversation.ConversationPageResult
import org.aurorasms.core.index.conversation.ConversationRepository
import org.aurorasms.core.index.conversation.ConversationSummary
import org.aurorasms.core.index.conversation.VerifiedConversationIdentity
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadStateHolderIdentityTest {
    @Test
    fun `verified lookup identity reaches Ready after the bounded timeline page`() = runBlocking {
        val conversations = ScriptedConversationRepository().apply {
            enqueue(found(FIRST_IDENTITY))
        }
        val timeline = SyntheticTimelineRepository()
        val fixture = holderFixture(conversations, timeline)

        try {
            val ready = fixture.holder.awaitReadyIdentity(FIRST_IDENTITY)

            assertEquals(FIRST_IDENTITY, ready.verifiedConversationIdentity)
            assertTrue(ready.verifiedConversationIdentityResolved)
            assertEquals(listOf(TimelinePageDirection.LATEST), timeline.directions)
            assertTrue(timeline.requests.all { it.limit in 1..100 })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `initial timeline ready remains unresolved until delayed metadata completes`() = runBlocking {
        val initialLookup = CompletableDeferred<ConversationLookupResult>()
        val conversations = ScriptedConversationRepository().apply {
            enqueue { initialLookup.await() }
        }
        val fixture = holderFixture(conversations, SyntheticTimelineRepository())

        try {
            val unresolved = withTimeout(STATE_TIMEOUT_MILLIS) {
                fixture.holder.state.first { state ->
                    state is ThreadUiState.Ready && !state.verifiedConversationIdentityResolved
                }
            } as ThreadUiState.Ready
            assertNull(unresolved.verifiedConversationIdentity)

            initialLookup.complete(found(FIRST_IDENTITY))

            val resolved = fixture.holder.awaitReadyIdentity(FIRST_IDENTITY)
            assertTrue(resolved.verifiedConversationIdentityResolved)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `invalidation clears identity before a later verified lookup may restore it`() = runBlocking {
        val refreshedLookup = CompletableDeferred<ConversationLookupResult>()
        val conversations = ScriptedConversationRepository().apply {
            enqueue(found(FIRST_IDENTITY))
            enqueue { refreshedLookup.await() }
        }
        val timeline = SyntheticTimelineRepository()
        val fixture = holderFixture(conversations, timeline)

        try {
            fixture.holder.awaitReadyIdentity(FIRST_IDENTITY)

            conversations.invalidate()

            val invalidated = withTimeout(STATE_TIMEOUT_MILLIS) {
                fixture.holder.state.first { state ->
                    state is ThreadUiState.Ready && state.verifiedConversationIdentity == null
                }
            } as ThreadUiState.Ready
            assertNull(invalidated.verifiedConversationIdentity)
            assertTrue(invalidated.verifiedConversationIdentityResolved)

            refreshedLookup.complete(found(SECOND_IDENTITY))

            val restored = fixture.holder.awaitReadyIdentity(SECOND_IDENTITY)
            assertEquals(SECOND_IDENTITY, restored.verifiedConversationIdentity)
            assertTrue(restored.verifiedConversationIdentityResolved)
            assertEquals(
                listOf(TimelinePageDirection.LATEST, TimelinePageDirection.NEWER),
                timeline.directions,
            )
            assertTrue(timeline.requests.all { request ->
                request.providerThreadId == THREAD_ID && request.limit in 1..100
            })
        } finally {
            fixture.close()
        }
    }
}

private suspend fun ThreadStateHolder.awaitReadyIdentity(
    expected: VerifiedConversationIdentity,
): ThreadUiState.Ready = withTimeout(STATE_TIMEOUT_MILLIS) {
    state.first { value ->
        value is ThreadUiState.Ready && value.verifiedConversationIdentity == expected
    } as ThreadUiState.Ready
}

private fun holderFixture(
    conversations: ScriptedConversationRepository,
    timeline: SyntheticTimelineRepository,
): HolderFixture {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    return HolderFixture(
        holder = ThreadStateHolder(
            providerThreadId = THREAD_ID,
            repository = timeline,
            conversationRepository = conversations,
            messageIndex = ContentFreeMessageIndex,
            contactCache = EmptyContactCache,
            subscriptionRepository = EmptySubscriptionRepository,
            scope = scope,
        ),
        scope = scope,
    )
}

private data class HolderFixture(
    val holder: ThreadStateHolder,
    val scope: CoroutineScope,
) {
    fun close() {
        holder.close()
        scope.cancel()
    }
}

private class ScriptedConversationRepository : ConversationRepository {
    private val signals = MutableSharedFlow<ConversationInvalidation>()
    private val lookups = ArrayDeque<suspend () -> ConversationLookupResult>()

    override val invalidations: Flow<ConversationInvalidation> = signals

    fun enqueue(result: ConversationLookupResult) {
        enqueue { result }
    }

    fun enqueue(block: suspend () -> ConversationLookupResult) {
        lookups.addLast(block)
    }

    suspend fun invalidate() {
        signals.emit(ConversationInvalidation)
    }

    override suspend fun loadInbox(request: ConversationPageRequest): ConversationPageResult =
        throw AssertionError(UNEXPECTED_INBOX_LOAD)

    override suspend fun loadConversation(
        providerThreadId: ProviderThreadId,
    ): ConversationLookupResult {
        if (providerThreadId != THREAD_ID || lookups.isEmpty()) {
            throw AssertionError(UNEXPECTED_CONVERSATION_LOAD)
        }
        return lookups.removeFirst().invoke()
    }
}

private class SyntheticTimelineRepository : ThreadTimelineRepository {
    val requests = mutableListOf<TimelinePageRequest>()
    val directions: List<TimelinePageDirection>
        get() = requests.map(TimelinePageRequest::direction)

    override suspend fun load(request: TimelinePageRequest): TimelinePageResult {
        if (request.providerThreadId != THREAD_ID || request.limit !in 1..100) {
            throw AssertionError(UNBOUNDED_TIMELINE_REQUEST)
        }
        requests += request
        val items = when (request.direction) {
            TimelinePageDirection.LATEST -> listOf(SYNTHETIC_MESSAGE)
            TimelinePageDirection.NEWER -> emptyList()
            TimelinePageDirection.OLDER -> throw AssertionError(UNEXPECTED_OLDER_LOAD)
        }
        return TimelinePageResult.Page(
            TimelinePage(
                items = items,
                next = null,
                direction = request.direction,
                coverage = TEST_COVERAGE,
            ),
        )
    }

    override suspend fun loadContent(
        providerThreadId: ProviderThreadId,
        providerMessageId: ProviderMessageId,
    ): TimelineContentResult = TimelineContentResult.Missing(TEST_COVERAGE)
}

private object ContentFreeMessageIndex : MessageIndex {
    override suspend fun coverage(): IndexCoverage = TEST_COVERAGE

    override suspend fun search(request: SearchRequest): SearchResult = SearchResult.NoQuery

    override suspend fun loadAnchor(
        anchor: SearchAnchor,
        halfWindow: Int,
    ): AnchorWindowResult = AnchorWindowResult.NotFound(TEST_COVERAGE)
}

private object EmptyContactCache : ContactCache {
    override val invalidations: Flow<ContactCacheInvalidation> = emptyFlow()

    override suspend fun resolve(addresses: List<ParticipantAddress>): List<ResolvedContact> = emptyList()

    override suspend fun invalidate() = Unit
}

private object EmptySubscriptionRepository : SubscriptionRepository {
    override suspend fun activeSubscriptions(): SubscriptionSnapshot =
        SubscriptionSnapshot.Available(emptyList())
}

private fun found(identity: VerifiedConversationIdentity): ConversationLookupResult.Found =
    ConversationLookupResult.Found(
        summary = syntheticConversation(identity.participants),
        coverage = TEST_COVERAGE,
        verifiedIdentity = identity,
    )

private fun syntheticConversation(
    participants: List<ParticipantAddress>,
): ConversationSummary = ConversationSummary(
    providerThreadId = THREAD_ID,
    latestLocalRowId = 1L,
    latestProviderMessageId = MESSAGE_ID,
    latestTimestampMillis = 1L,
    latestSentTimestampMillis = null,
    latestDirection = MessageDirection.INCOMING,
    latestBox = MessageBox.INBOX,
    latestStatus = MessageStatus.COMPLETE,
    latestSubscriptionId = null,
    latestSenderAddress = null,
    latestSnippet = null,
    latestAttachmentCount = 0,
    latestAttachmentTypeSummary = "",
    latestRead = true,
    indexedMessageCount = 1L,
    indexedUnreadCount = 0L,
    participants = participants,
    indexedParticipantCount = participants.size,
    participantsTruncated = false,
)

private val TEST_COVERAGE = IndexCoverage(
    generationId = 41L,
    state = IndexRunState.COMPLETE,
    indexedMessageCount = 1L,
    smsExhausted = true,
    mmsExhausted = true,
    pendingChanges = false,
)
private val THREAD_ID = ProviderThreadId(7L)
private val MESSAGE_ID = ProviderMessageId(ProviderKind.SMS, 1L)
private val FIRST_PARTICIPANTS = listOf(ParticipantAddress("synthetic-alpha"))
private val SECOND_PARTICIPANTS = listOf(ParticipantAddress("synthetic-beta"))
private val FIRST_IDENTITY = VerifiedConversationIdentity(
    providerThreadId = THREAD_ID,
    generationId = 41L,
    participants = FIRST_PARTICIPANTS,
)
private val SECOND_IDENTITY = VerifiedConversationIdentity(
    providerThreadId = THREAD_ID,
    generationId = 41L,
    participants = SECOND_PARTICIPANTS,
)
private val SYNTHETIC_MESSAGE = TimelineMessage(
    localRowId = 1L,
    providerMessageId = MESSAGE_ID,
    providerThreadId = THREAD_ID,
    timestampMillis = 1L,
    sentTimestampMillis = null,
    direction = MessageDirection.INCOMING,
    box = MessageBox.INBOX,
    status = MessageStatus.COMPLETE,
    subscriptionId = null,
    senderAddress = null,
    bodyPreview = null,
    bodyTruncated = false,
    subject = null,
    attachmentCount = 0,
    attachmentTypeSummary = "",
    read = true,
    seen = true,
    locked = false,
)

private const val STATE_TIMEOUT_MILLIS = 5_000L
private const val UNEXPECTED_INBOX_LOAD = "unexpected inbox load"
private const val UNEXPECTED_CONVERSATION_LOAD = "unexpected conversation lookup"
private const val UNBOUNDED_TIMELINE_REQUEST = "timeline request escaped its reviewed bound"
private const val UNEXPECTED_OLDER_LOAD = "unexpected older timeline load"
