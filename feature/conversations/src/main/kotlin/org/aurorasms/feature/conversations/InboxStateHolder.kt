// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.aurorasms.core.index.IndexCoverage
import org.aurorasms.core.index.conversation.ConversationPageRequest
import org.aurorasms.core.index.conversation.ConversationPageResult
import org.aurorasms.core.index.conversation.ConversationRepository
import org.aurorasms.core.index.conversation.ConversationSummary
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.telephony.ContactCache

class InboxStateHolder(
    private val repository: ConversationRepository,
    private val contactCache: ContactCache,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val _state = MutableStateFlow<InboxUiState>(InboxUiState.Loading)
    val state: StateFlow<InboxUiState> = _state.asStateFlow()
    private var pageJob: Job? = null
    private var contactJob: Job? = null
    private var replayInvalidationAfterPage = false
    private var reloadAfterPage = false
    private var closed = false
    private var userAtNewest = true
    private var visibleItems: List<ConversationSummary> = emptyList()
    private var lastContactRequest = emptyList<org.aurorasms.core.model.ParticipantAddress>()
    private val observerJobs: List<Job>

    init {
        observerJobs = listOf(
            scope.launch {
                repository.invalidations.collect {
                    val current = _state.value as? InboxUiState.Ready
                    if (pageJob?.isActive == true) {
                        replayInvalidationAfterPage = true
                        return@collect
                    }
                    if (current == null) return@collect
                    if (userAtNewest) {
                        refreshNewest(current, replayIfBusy = true)
                    } else {
                        publish(current.copy(window = current.window.noteIncomingWhileAway()))
                    }
                }
            },
            scope.launch {
                contactCache.invalidations.collect {
                    lastContactRequest = emptyList()
                    val current = _state.value as? InboxUiState.Ready
                    if (current != null) publish(current.copy(contacts = emptyMap()))
                    resolveVisibleContacts(force = true)
                }
            },
        )
        reload()
    }

    fun reload() {
        launchPageJob {
            _state.value = InboxUiState.Loading
            when (val result = safeLoad(ConversationPageRequest())) {
                is ConversationPageResult.Page -> publish(
                    InboxUiState.Ready(
                        window = BoundedInboxWindow.fromNewest(result.page),
                        coverage = result.page.coverage,
                        contacts = emptyMap(),
                        loadingOlder = false,
                    ),
                )
                is ConversationPageResult.StaleGeneration -> reloadAfterCurrentJob()
                is ConversationPageResult.StorageUnavailable ->
                    _state.value = InboxUiState.Failed(ConversationLoadFailure.STORAGE, result.coverage)
            }
        }
    }

    fun loadOlder(anchor: WindowAnchor<ProviderThreadId>) {
        val current = _state.value as? InboxUiState.Ready ?: return
        val cursor = current.window.olderCursor ?: return
        if (pageJob?.isActive == true) return
        publish(current.copy(loadingOlder = true, restoreAnchor = null))
        launchPageJob {
            when (val result = safeLoad(ConversationPageRequest(cursor = cursor))) {
                is ConversationPageResult.Page -> {
                    val mutation = current.window.appendOlder(result.page, anchor)
                    val latest = _state.value as? InboxUiState.Ready ?: current
                    publish(
                        latest.copy(
                            window = mutation.window,
                            coverage = result.page.coverage,
                            loadingOlder = false,
                            restoreAnchor = mutation.restoreAnchor,
                        ),
                    )
                }
                is ConversationPageResult.StaleGeneration -> reloadAfterCurrentJob()
                is ConversationPageResult.StorageUnavailable ->
                    _state.value = InboxUiState.Failed(ConversationLoadFailure.STORAGE, result.coverage)
            }
        }
    }

    fun markUserAtNewest(atNewest: Boolean) {
        userAtNewest = atNewest
    }

    fun acceptPendingNewer() {
        userAtNewest = true
        val current = _state.value as? InboxUiState.Ready ?: return
        refreshNewest(current, replayIfBusy = true)
    }

    fun onViewportChanged(items: List<ConversationSummary>) {
        visibleItems = items.take(MAXIMUM_VIEWPORT_CONVERSATIONS)
        resolveVisibleContacts(force = false)
    }

    fun anchorRestored() {
        val current = _state.value as? InboxUiState.Ready ?: return
        if (current.restoreAnchor != null) publish(current.copy(restoreAnchor = null))
    }

    override fun close() {
        closed = true
        replayInvalidationAfterPage = false
        reloadAfterPage = false
        pageJob?.cancel()
        contactJob?.cancel()
        observerJobs.forEach(Job::cancel)
    }

    private fun refreshNewest(
        current: InboxUiState.Ready,
        replayIfBusy: Boolean = false,
    ) {
        if (pageJob?.isActive == true) {
            if (replayIfBusy) replayInvalidationAfterPage = true
            return
        }
        launchPageJob {
            when (val result = safeLoad(ConversationPageRequest())) {
                is ConversationPageResult.Page -> publish(
                    (_state.value as? InboxUiState.Ready ?: current).copy(
                        window = current.window.refreshNewest(result.page),
                        coverage = result.page.coverage,
                        loadingOlder = false,
                    ),
                )
                is ConversationPageResult.StaleGeneration -> reloadAfterCurrentJob()
                is ConversationPageResult.StorageUnavailable ->
                    _state.value = InboxUiState.Failed(ConversationLoadFailure.STORAGE, result.coverage)
            }
        }
    }

    private fun resolveVisibleContacts(force: Boolean) {
        val requested = conversationAddresses(visibleItems)
        if (!force && requested == lastContactRequest) return
        lastContactRequest = requested
        contactJob?.cancel()
        if (requested.isEmpty()) {
            val current = _state.value as? InboxUiState.Ready ?: return
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
            val current = _state.value as? InboxUiState.Ready ?: return@launch
            publish(current.copy(contacts = resolved.associateBy { it.address }))
        }
    }

    private suspend fun safeLoad(request: ConversationPageRequest): ConversationPageResult = try {
        repository.loadInbox(request)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        ConversationPageResult.StorageUnavailable(
            (_state.value as? InboxUiState.Ready)?.coverage ?: IndexCoverage.NOT_STARTED,
        )
    }

    private fun publish(value: InboxUiState.Ready) {
        _state.value = value
    }

    private fun reloadAfterCurrentJob() {
        if (pageJob?.isActive == true) {
            reloadAfterPage = true
        } else {
            reload()
        }
    }

    /** Owns the page slot before starting so even immediate jobs complete through one fence. */
    private fun launchPageJob(block: suspend () -> Unit): Boolean {
        if (closed || pageJob?.isActive == true) return false
        lateinit var owner: Job
        owner = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                finishPageJob(owner)
            }
        }
        pageJob = owner
        owner.start()
        return true
    }

    private fun finishPageJob(owner: Job) {
        if (pageJob !== owner) return
        pageJob = null
        if (closed) return
        if (reloadAfterPage) {
            reloadAfterPage = false
            replayInvalidationAfterPage = false
            reload()
            return
        }
        if (!replayInvalidationAfterPage) return
        replayInvalidationAfterPage = false
        val current = _state.value as? InboxUiState.Ready
        if (current == null) {
            reload()
        } else if (userAtNewest) {
            refreshNewest(current, replayIfBusy = true)
        } else {
            publish(current.copy(window = current.window.noteIncomingWhileAway()))
        }
    }
}
