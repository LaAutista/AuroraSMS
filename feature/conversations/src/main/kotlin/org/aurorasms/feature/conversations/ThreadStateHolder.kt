// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.aurorasms.core.index.AnchorWindowResult
import org.aurorasms.core.index.IndexCoverage
import org.aurorasms.core.index.MessageIndex
import org.aurorasms.core.index.SearchAnchor
import org.aurorasms.core.index.SearchHit
import org.aurorasms.core.index.conversation.ConversationLookupResult
import org.aurorasms.core.index.conversation.ConversationRepository
import org.aurorasms.core.index.conversation.ConversationSummary
import org.aurorasms.core.index.timeline.MAXIMUM_TIMELINE_BODY_PREVIEW_CHARACTERS
import org.aurorasms.core.index.timeline.ThreadTimelineRepository
import org.aurorasms.core.index.timeline.TimelineContentResult
import org.aurorasms.core.index.timeline.TimelineCursor
import org.aurorasms.core.index.timeline.TimelineMessage
import org.aurorasms.core.index.timeline.TimelinePageDirection
import org.aurorasms.core.index.timeline.TimelinePageRequest
import org.aurorasms.core.index.timeline.TimelinePageResult
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.telephony.ActiveSubscription
import org.aurorasms.core.telephony.ContactCache
import org.aurorasms.core.telephony.SubscriptionRepository

class ThreadStateHolder(
    private val providerThreadId: ProviderThreadId,
    private val repository: ThreadTimelineRepository,
    private val conversationRepository: ConversationRepository,
    private val messageIndex: MessageIndex,
    private val contactCache: ContactCache,
    private val subscriptionRepository: SubscriptionRepository,
    private val scope: CoroutineScope,
    private val initialAnchor: SearchAnchor? = null,
) : AutoCloseable {
    private val _state = MutableStateFlow<ThreadUiState>(ThreadUiState.Loading)
    val state: StateFlow<ThreadUiState> = _state.asStateFlow()
    private var pageJob: Job? = null
    private var contactJob: Job? = null
    private var metadataJob: Job? = null
    private var contentJob: Job? = null
    private var userAtNewest = initialAnchor == null
    private var visibleItems: List<TimelineMessage> = emptyList()
    private var lastContactRequest = emptyList<ParticipantAddress>()
    private var conversationSummary: ConversationSummary? = null
    private var activeSubscription: ActiveSubscription? = null
    private val observerJobs: List<Job>

    init {
        observerJobs = listOf(
            scope.launch {
                conversationRepository.invalidations.collect {
                    loadMetadata()
                    val current = _state.value as? ThreadUiState.Ready ?: return@collect
                    if (userAtNewest) requestNewer(current, visibleAnchor = null, allowTailCursor = true) else publish(
                        current.copy(window = current.window.noteIncomingWhileAway()),
                    )
                }
            },
            scope.launch {
                contactCache.invalidations.collect {
                    lastContactRequest = emptyList()
                    val current = _state.value as? ThreadUiState.Ready
                    if (current != null) publish(current.copy(contacts = emptyMap()))
                    resolveVisibleContacts(force = true)
                }
            },
        )
        loadMetadata()
        if (initialAnchor == null) loadLatest() else loadExactAnchor(initialAnchor)
    }

    fun loadLatest() {
        if (pageJob?.isActive == true) return
        pageJob = scope.launch {
            _state.value = ThreadUiState.Loading
            when (val result = safeLoad(TimelinePageRequest(providerThreadId))) {
                is TimelinePageResult.Page -> publish(
                    ThreadUiState.Ready(
                        window = BoundedThreadWindow.fromLatest(result.page),
                        coverage = result.page.coverage,
                        conversation = conversationSummary,
                        activeSubscription = activeSubscription,
                        contacts = emptyMap(),
                        loadingOlder = false,
                        loadingNewer = false,
                    ),
                )
                is TimelinePageResult.StaleGeneration -> reloadAfterCurrentJob()
                is TimelinePageResult.MissingThread ->
                    _state.value = ThreadUiState.Failed(ConversationLoadFailure.MISSING_THREAD, result.coverage)
                is TimelinePageResult.StorageUnavailable ->
                    _state.value = ThreadUiState.Failed(ConversationLoadFailure.STORAGE, result.coverage)
            }
        }
    }

    fun loadOlder(anchor: WindowAnchor<ProviderMessageId>) {
        var current = _state.value as? ThreadUiState.Ready ?: return
        current = collapseExpansion(current)
        val cursor = current.window.olderCursor ?: return
        if (pageJob?.isActive == true) return
        publish(current.copy(loadingOlder = true, restoreAnchor = null))
        pageJob = scope.launch {
            when (
                val result = safeLoad(
                    TimelinePageRequest(
                        providerThreadId = providerThreadId,
                        cursor = cursor,
                        direction = TimelinePageDirection.OLDER,
                    ),
                )
            ) {
                is TimelinePageResult.Page -> {
                    val mutation = current.window.prependOlder(result.page, anchor)
                    val latest = _state.value as? ThreadUiState.Ready ?: current
                    publish(
                        latest.copy(
                            window = mutation.window,
                            coverage = result.page.coverage,
                            loadingOlder = false,
                            restoreAnchor = mutation.restoreAnchor,
                        ),
                    )
                }
                is TimelinePageResult.StaleGeneration -> reloadAfterCurrentJob()
                is TimelinePageResult.MissingThread ->
                    _state.value = ThreadUiState.Failed(ConversationLoadFailure.MISSING_THREAD, result.coverage)
                is TimelinePageResult.StorageUnavailable ->
                    _state.value = ThreadUiState.Failed(ConversationLoadFailure.STORAGE, result.coverage)
            }
        }
    }

    fun loadNewer(anchor: WindowAnchor<ProviderMessageId>) {
        var current = _state.value as? ThreadUiState.Ready ?: return
        current = collapseExpansion(current)
        if (current.window.newerCursor == null) return
        requestNewer(current, visibleAnchor = anchor, allowTailCursor = false)
    }

    fun toggleMessageExpansion(providerMessageId: ProviderMessageId) {
        val current = _state.value as? ThreadUiState.Ready ?: return
        val message = current.window.items.firstOrNull { it.providerMessageId == providerMessageId } ?: return
        if (current.expandedMessageId == providerMessageId) {
            contentJob?.cancel()
            publish(current.withoutExpansion())
            return
        }
        if (!message.bodyTruncated) return
        contentJob?.cancel()
        publish(
            current.copy(
                expandedMessageId = providerMessageId,
                expandedContent = null,
                expandingMessage = true,
                expansionFailed = false,
            ),
        )
        contentJob = scope.launch {
            val result = try {
                repository.loadContent(providerThreadId, providerMessageId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                TimelineContentResult.StorageUnavailable(current.coverage)
            }
            val latest = _state.value as? ThreadUiState.Ready ?: return@launch
            if (latest.expandedMessageId != providerMessageId) return@launch
            when (result) {
                is TimelineContentResult.Found -> {
                    val additionalTextUnits =
                        (result.content.body?.length ?: 0) + (result.content.subject?.length ?: 0)
                    val generationId = latest.coverage.generationId
                    if (generationId == null) {
                        publish(
                            latest.copy(
                                expandedContent = null,
                                expandingMessage = false,
                                expansionFailed = true,
                            ),
                        )
                    } else {
                        publish(
                            latest.copy(
                                window = latest.window.reserveAdditionalText(
                                    additionalTextUnits = additionalTextUnits,
                                    preservedMessageId = providerMessageId,
                                    generationId = generationId,
                                ),
                                expandedContent = result.content,
                                expandingMessage = false,
                                expansionFailed = false,
                            ),
                        )
                    }
                }
                is TimelineContentResult.Missing,
                is TimelineContentResult.StorageUnavailable,
                -> publish(
                    latest.copy(
                        expandedContent = null,
                        expandingMessage = false,
                        expansionFailed = true,
                    ),
                )
            }
        }
    }

    fun markUserAtNewest(atNewest: Boolean) {
        userAtNewest = atNewest
    }

    fun acceptPendingNewer() {
        userAtNewest = true
        pageJob?.cancel()
        pageJob = null
        loadLatest()
    }

    fun onViewportChanged(items: List<TimelineMessage>) {
        visibleItems = items.take(MAXIMUM_RETAINED_THREAD_ROWS)
        resolveVisibleContacts(force = false)
    }

    fun anchorRestored() {
        val current = _state.value as? ThreadUiState.Ready ?: return
        if (current.restoreAnchor != null) publish(current.copy(restoreAnchor = null))
    }

    override fun close() {
        pageJob?.cancel()
        contactJob?.cancel()
        metadataJob?.cancel()
        contentJob?.cancel()
        observerJobs.forEach(Job::cancel)
    }

    private fun loadExactAnchor(anchor: SearchAnchor) {
        pageJob = scope.launch {
            val result = try {
                messageIndex.loadAnchor(anchor)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                _state.value = ThreadUiState.Failed(ConversationLoadFailure.STORAGE, IndexCoverage.NOT_STARTED)
                return@launch
            }
            when (result) {
                is AnchorWindowResult.Found -> {
                    val items = result.messages
                        .filter { it.providerThreadId == providerThreadId }
                        .map(SearchHit::toTimelineMessage)
                    if (items.isEmpty() || result.coverage.generationId == null) {
                        reloadAfterCurrentJob()
                        return@launch
                    }
                    val generationId = checkNotNull(result.coverage.generationId)
                    publish(
                        ThreadUiState.Ready(
                            window = BoundedThreadWindow(
                                items = items,
                                olderCursor = items.first().toCursor(generationId),
                                newerCursor = items.last().toCursor(generationId),
                            ),
                            coverage = result.coverage,
                            conversation = conversationSummary,
                            activeSubscription = activeSubscription,
                            contacts = emptyMap(),
                            loadingOlder = false,
                            loadingNewer = false,
                            highlightedMessageId = anchor.providerId,
                        ),
                    )
                }
                is AnchorWindowResult.NotFound -> reloadAfterCurrentJob()
            }
        }
    }

    private fun requestNewer(
        suppliedCurrent: ThreadUiState.Ready,
        visibleAnchor: WindowAnchor<ProviderMessageId>?,
        allowTailCursor: Boolean,
    ) {
        val current = collapseExpansion(suppliedCurrent)
        if (pageJob?.isActive == true || current.window.items.isEmpty()) return
        val generationId = current.coverage.generationId ?: run {
            reloadAfterCurrentJob()
            return
        }
        val cursor = current.window.newerCursor ?: if (allowTailCursor) {
            current.window.items.last().toCursor(generationId)
        } else {
            return
        }
        publish(current.copy(loadingNewer = true))
        pageJob = scope.launch {
            when (
                val result = safeLoad(
                    TimelinePageRequest(
                        providerThreadId = providerThreadId,
                        cursor = cursor,
                        direction = TimelinePageDirection.NEWER,
                    ),
                )
            ) {
                is TimelinePageResult.Page -> {
                    val mutation = current.window.appendNewer(result.page, visibleAnchor = visibleAnchor)
                    val latest = _state.value as? ThreadUiState.Ready ?: current
                    publish(
                        latest.copy(
                            window = mutation.window,
                            coverage = result.page.coverage,
                            loadingNewer = false,
                            restoreAnchor = mutation.restoreAnchor,
                        ),
                    )
                }
                is TimelinePageResult.StaleGeneration -> reloadAfterCurrentJob()
                is TimelinePageResult.MissingThread ->
                    _state.value = ThreadUiState.Failed(ConversationLoadFailure.MISSING_THREAD, result.coverage)
                is TimelinePageResult.StorageUnavailable ->
                    _state.value = ThreadUiState.Failed(ConversationLoadFailure.STORAGE, result.coverage)
            }
        }
    }

    private fun resolveVisibleContacts(force: Boolean) {
        val requested = sequence {
            conversationSummary?.participants?.forEach { yield(it) }
            conversationSummary?.latestSenderAddress?.let { yield(it) }
            visibleItems.forEach { message -> message.senderAddress?.let { yield(it) } }
        }.distinct().take(MAXIMUM_VIEWPORT_CONTACTS).toList()
        if (!force && requested == lastContactRequest) return
        lastContactRequest = requested
        contactJob?.cancel()
        if (requested.isEmpty()) {
            val current = _state.value as? ThreadUiState.Ready ?: return
            publish(current.copy(contacts = emptyMap()))
            return
        }
        contactJob = scope.launch {
            val resolved = try {
                contactCache.resolve(requested)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                emptyList()
            }
            val current = _state.value as? ThreadUiState.Ready ?: return@launch
            publish(current.copy(contacts = resolved.associateBy { it.address }))
        }
    }

    private fun loadMetadata() {
        metadataJob?.cancel()
        metadataJob = scope.launch {
            val summary = try {
                when (val result = conversationRepository.loadConversation(providerThreadId)) {
                    is ConversationLookupResult.Found -> result.summary
                    is ConversationLookupResult.Missing,
                    is ConversationLookupResult.StorageUnavailable,
                    -> null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                null
            }
            val subscription = try {
                summary?.latestSubscriptionId?.let { subscriptionRepository.findActive(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                null
            }
            conversationSummary = summary
            activeSubscription = subscription
            lastContactRequest = emptyList()
            val current = _state.value as? ThreadUiState.Ready
            if (current != null) {
                publish(
                    current.copy(
                        conversation = summary,
                        activeSubscription = subscription,
                    ),
                )
                resolveVisibleContacts(force = true)
            }
        }
    }

    private suspend fun safeLoad(request: TimelinePageRequest): TimelinePageResult = try {
        repository.load(request)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        TimelinePageResult.StorageUnavailable(
            (_state.value as? ThreadUiState.Ready)?.coverage ?: IndexCoverage.NOT_STARTED,
        )
    }

    private fun reloadAfterCurrentJob() {
        pageJob = null
        loadLatest()
    }

    private fun collapseExpansion(current: ThreadUiState.Ready): ThreadUiState.Ready {
        if (
            current.expandedMessageId == null &&
            current.expandedContent == null &&
            !current.expandingMessage &&
            !current.expansionFailed
        ) {
            return current
        }
        contentJob?.cancel()
        val collapsed = current.withoutExpansion()
        publish(collapsed)
        return collapsed
    }

    private fun publish(value: ThreadUiState.Ready) {
        _state.value = value
    }
}

private fun ThreadUiState.Ready.withoutExpansion(): ThreadUiState.Ready = copy(
    expandedMessageId = null,
    expandedContent = null,
    expandingMessage = false,
    expansionFailed = false,
)

private fun SearchHit.toTimelineMessage(): TimelineMessage {
    val boundedBody = body?.take(MAXIMUM_TIMELINE_BODY_PREVIEW_CHARACTERS)
    val sender = senderAddress?.let { raw ->
        try {
            ParticipantAddress(raw)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    return TimelineMessage(
        localRowId = localRowId,
        providerMessageId = providerId,
        providerThreadId = providerThreadId,
        timestampMillis = timestampMillis,
        sentTimestampMillis = sentTimestampMillis,
        direction = direction,
        box = box,
        status = status,
        subscriptionId = subscriptionId,
        senderAddress = sender,
        bodyPreview = boundedBody,
        bodyTruncated = body != boundedBody,
        subject = subject,
        attachmentCount = attachmentCount,
        attachmentTypeSummary = attachmentTypeSummary,
        read = read,
        seen = seen,
        locked = locked,
    )
}

private fun TimelineMessage.toCursor(generationId: Long): TimelineCursor = TimelineCursor(
    generationId = generationId,
    providerThreadId = providerThreadId,
    timestampMillis = timestampMillis,
    localRowId = localRowId,
)
