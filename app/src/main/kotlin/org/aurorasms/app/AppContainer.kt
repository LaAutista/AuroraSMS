// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import android.app.Application
import android.annotation.SuppressLint
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import java.util.EnumSet
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.aurorasms.app.contacts.AppContactCacheController
import org.aurorasms.app.drafts.DraftEditorContent
import org.aurorasms.app.drafts.SerializedDraftWriter
import org.aurorasms.app.preview.AndroidBoundedPreviewLoader
import org.aurorasms.core.index.AnchorWindowResult
import org.aurorasms.core.index.IndexCoverage
import org.aurorasms.app.index.AppIndexCoordinator
import org.aurorasms.app.message.AppNotificationIntentFactory
import org.aurorasms.app.message.IncomingMessageOrchestrator
import org.aurorasms.app.message.InlineReplyOrchestrator
import org.aurorasms.app.message.ReplyTargetRegistry
import org.aurorasms.app.message.SharedPreferencesReplyReplayGuard
import org.aurorasms.app.message.SharedPreferencesReplyTargetStore
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.index.MessageIndex
import org.aurorasms.core.index.conversation.ConversationInvalidation
import org.aurorasms.core.index.conversation.ConversationLookupResult
import org.aurorasms.core.index.conversation.ConversationPageRequest
import org.aurorasms.core.index.conversation.ConversationPageResult
import org.aurorasms.core.index.conversation.ConversationRepository
import org.aurorasms.core.index.conversation.RoomConversationRepository
import org.aurorasms.core.index.SearchAnchor
import org.aurorasms.core.index.SearchRequest
import org.aurorasms.core.index.SearchResult
import org.aurorasms.core.index.search.RoomMessageIndex
import org.aurorasms.core.index.storage.AuroraIndexDatabase
import org.aurorasms.core.index.storage.IndexDatabaseFactory
import org.aurorasms.core.index.storage.IndexDatabaseOpenFailureReason
import org.aurorasms.core.index.storage.IndexDatabaseOpenResult
import org.aurorasms.core.index.sync.IndexSignal
import org.aurorasms.core.index.sync.IndexSyncOutcome
import org.aurorasms.core.index.sync.TelephonyIndexSynchronizer
import org.aurorasms.core.index.timeline.RoomThreadTimelineRepository
import org.aurorasms.core.index.timeline.ThreadTimelineRepository
import org.aurorasms.core.index.timeline.TimelinePageRequest
import org.aurorasms.core.index.timeline.TimelinePageResult
import org.aurorasms.core.index.timeline.TimelineContentResult
import org.aurorasms.core.notifications.AndroidMessageNotifier
import org.aurorasms.core.notifications.InlineReplyHandler
import org.aurorasms.core.notifications.MessageNotifier
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.ContactCache
import org.aurorasms.core.telephony.ContactCacheInvalidation
import org.aurorasms.core.telephony.EncodedMmsPdu
import org.aurorasms.core.telephony.IncomingMessageSink
import org.aurorasms.core.telephony.MessageTransport
import org.aurorasms.core.telephony.MmsAttachmentContentReader
import org.aurorasms.core.telephony.MmsAttachmentId
import org.aurorasms.core.telephony.MmsAttachmentListResult
import org.aurorasms.core.telephony.MmsAttachmentReadResult
import org.aurorasms.core.telephony.MmsAttachmentRepository
import org.aurorasms.core.telephony.ResolvedContact
import org.aurorasms.core.telephony.SmsProviderDataSource
import org.aurorasms.core.telephony.SmsProviderStatus
import org.aurorasms.core.telephony.SubscriptionRepository
import org.aurorasms.core.telephony.SubscriptionSnapshot
import org.aurorasms.core.telephony.internal.AndroidContactResolver
import org.aurorasms.core.telephony.internal.AndroidDefaultSmsRoleState
import org.aurorasms.core.telephony.internal.AndroidMmsProviderDataSource
import org.aurorasms.core.telephony.internal.AndroidMmsAttachmentRepository
import org.aurorasms.core.telephony.internal.AndroidMmsTransport
import org.aurorasms.core.telephony.internal.AndroidSmsProviderDataSource
import org.aurorasms.core.telephony.internal.AndroidSmsTransport
import org.aurorasms.core.telephony.internal.AndroidSubscriptionRepository
import org.aurorasms.core.telephony.internal.MmsPduStagingStore
import org.aurorasms.core.state.storage.AuroraStateDatabase
import org.aurorasms.core.state.Draft
import org.aurorasms.core.state.DraftId
import org.aurorasms.core.state.DraftIdentity
import org.aurorasms.core.state.DraftRepository
import org.aurorasms.core.state.DraftRepositoryResult
import org.aurorasms.core.state.DraftRevision
import org.aurorasms.core.state.DraftStorageOperation
import org.aurorasms.core.state.NewDraft
import org.aurorasms.core.state.storage.RoomDraftRepository
import org.aurorasms.core.state.storage.StateDatabaseFactory
import org.aurorasms.core.state.storage.StateDatabaseOpenFailureReason
import org.aurorasms.core.state.storage.StateDatabaseOpenResult
import org.aurorasms.feature.conversations.BoundedPreviewLoader

class AppContainer(
    val application: Application,
    private val syntheticIndexOnly: Boolean = false,
) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val replyTargets = ReplyTargetRegistry(
        maximumEntries = 4_096,
        targetStore = SharedPreferencesReplyTargetStore(application),
    )
    private val sentPartTracker = SentPartTracker()
    private val indexSignalLedger = application.getSharedPreferences(
        INDEX_SIGNAL_LEDGER_NAME,
        Context.MODE_PRIVATE,
    )

    val defaultSmsRoleState: DefaultSmsRoleState = AndroidDefaultSmsRoleState(application)
    val smsProviderDataSource: SmsProviderDataSource =
        AndroidSmsProviderDataSource(application, defaultSmsRoleState)
    val subscriptionRepository: SubscriptionRepository = if (syntheticIndexOnly) {
        UnavailableSubscriptionRepository
    } else {
        AndroidSubscriptionRepository(application)
    }
    private val contactCacheController = if (syntheticIndexOnly) {
        null
    } else {
        AppContactCacheController(
            context = application,
            resolver = AndroidContactResolver(application),
            applicationScope = applicationScope,
        )
    }
    val contactCache: ContactCache = contactCacheController?.cache ?: AddressOnlyContactCache
    val mmsProviderDataSource = AndroidMmsProviderDataSource(application, defaultSmsRoleState)
    val mmsAttachmentRepository: MmsAttachmentRepository = if (syntheticIndexOnly) {
        UnavailableMmsAttachmentRepository
    } else {
        AndroidMmsAttachmentRepository(application, defaultSmsRoleState)
    }
    val previewLoader: BoundedPreviewLoader = AndroidBoundedPreviewLoader(
        repository = mmsAttachmentRepository,
        applicationScope = applicationScope,
    )
    private val indexRuntimeState = MutableStateFlow<IndexRuntimeState>(IndexRuntimeState.Opening)
    private val indexOpenMutex = Mutex()
    private var indexRetryJob: Job? = null
    @Volatile
    private var activeIndexDatabase: AuroraIndexDatabase? = null
    val messageIndex: MessageIndex = DeferredMessageIndex(indexRuntimeState)
    val conversationRepository: ConversationRepository = DeferredConversationRepository(indexRuntimeState)
    val threadTimelineRepository: ThreadTimelineRepository = DeferredThreadTimelineRepository(indexRuntimeState)
    val indexCoordinator = AppIndexCoordinator(
        applicationScope = applicationScope,
        synchronize = { reasons ->
            val signalSequence = synchronized(indexSignalLock) { ambiguousSignalSequence }
            val outcome = awaitIndexRuntime().synchronizer.reconcile(reasons)
            if (
                outcome is IndexSyncOutcome.Complete &&
                reasons.any(IndexSignal::requiresDurableAmbiguousLedger)
            ) {
                clearAmbiguousSignalLedger(signalSequence)
            }
            outcome
        },
        markPendingChanges = { awaitIndexRuntime().synchronizer.markPendingChanges() },
    )

    private val _indexStorageStatus = MutableStateFlow<IndexStorageStatus>(IndexStorageStatus.Opening)
    val indexStorageStatus: StateFlow<IndexStorageStatus> = _indexStorageStatus.asStateFlow()

    private val _stateStorageStatus = MutableStateFlow<StateStorageStatus>(StateStorageStatus.Opening)
    val stateStorageStatus: StateFlow<StateStorageStatus> = _stateStorageStatus.asStateFlow()
    private val stateRuntimeState = MutableStateFlow<StateRuntimeState>(StateRuntimeState.Opening)
    val draftRepository: DraftRepository = DeferredDraftRepository(stateRuntimeState)
    @Volatile
    private var stateDatabase: AuroraStateDatabase? = null

    private val providerObserverRegistered = AtomicBoolean(false)
    private val indexSignalLock = Any()
    private val pendingIndexSignals: EnumSet<IndexSignal> = EnumSet.noneOf(IndexSignal::class.java)
    private val indexSignalWakeUps = Channel<Unit>(capacity = Channel.CONFLATED)
    private var ambiguousSignalLedgered: Boolean = !syntheticIndexOnly &&
        indexSignalLedger.getBoolean(INDEX_SIGNAL_LEDGER_KEY, false)
    private var ambiguousSignalSequence: Long = if (ambiguousSignalLedgered) 1L else 0L
    private val providerObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            enqueueIndexSignal(IndexSignal.CONTENT_OBSERVER_CHANGE)
        }
    }
    private val mmsStagingStore = MmsPduStagingStore(application)
    private val mmsTransport = AndroidMmsTransport(
        context = application,
        roleState = defaultSmsRoleState,
        subscriptions = subscriptionRepository,
        stagingStore = mmsStagingStore,
    )
    val messageTransport: MessageTransport = AndroidSmsTransport(
        context = application,
        roleState = defaultSmsRoleState,
        subscriptions = subscriptionRepository,
        smsProvider = smsProviderDataSource,
        mmsTransport = mmsTransport,
    )
    val messageNotifier: MessageNotifier = AndroidMessageNotifier(
        context = application,
        intentFactory = AppNotificationIntentFactory(application),
    )
    val incomingMessageSink: IncomingMessageSink = IncomingMessageOrchestrator(
        roleState = defaultSmsRoleState,
        smsProvider = smsProviderDataSource,
        contactResolver = contactCache,
        messageNotifier = messageNotifier,
        replyTargets = replyTargets,
        onProviderInsertComplete = { enqueueIndexSignal(IndexSignal.INCOMING_INSERT) },
    )
    val inlineReplyHandler: InlineReplyHandler = InlineReplyOrchestrator(
        roleState = defaultSmsRoleState,
        replyTargets = replyTargets,
        replayGuard = SharedPreferencesReplyReplayGuard(application),
        messageTransport = messageTransport,
        messageNotifier = messageNotifier,
    )

    private val _lastTransportResult = MutableStateFlow<TransportResult?>(null)
    val lastTransportResult: StateFlow<TransportResult?> = _lastTransportResult.asStateFlow()

    val lastIndexOutcome: StateFlow<IndexSyncOutcome?> = indexCoordinator.lastOutcome

    init {
        if (!syntheticIndexOnly) {
            applicationScope.launch {
                for (ignored in indexSignalWakeUps) {
                    val signals = takePendingIndexSignals()
                    val conservativeSignal = signals.singleOrNull()
                        ?: IndexSignal.EXTERNAL_PROVIDER_CHANGE
                    signalIndex(conservativeSignal)
                }
            }
        }
        if (ambiguousSignalLedgered) {
            enqueueIndexSignal(IndexSignal.EXTERNAL_PROVIDER_CHANGE)
        }
        requestIndexOpen()
        if (syntheticIndexOnly) {
            stateRuntimeState.value = StateRuntimeState.Ready(EmptyBenchmarkDraftRepository)
            _stateStorageStatus.value = StateStorageStatus.Ready
        } else {
            applicationScope.launch(Dispatchers.IO) {
                when (val result = StateDatabaseFactory.open(application)) {
                    is StateDatabaseOpenResult.Opened -> {
                        stateDatabase = result.database
                        stateRuntimeState.value = StateRuntimeState.Ready(RoomDraftRepository(result.database))
                        _stateStorageStatus.value = StateStorageStatus.Ready
                    }
                    is StateDatabaseOpenResult.Failed -> {
                        stateRuntimeState.value = StateRuntimeState.Failed(result.reason)
                        _stateStorageStatus.value = StateStorageStatus.Failed(result.reason)
                    }
                }
            }
        }
    }

    suspend fun onTransportResult(result: TransportResult) {
        _lastTransportResult.value = result
        var providerStateChanged = false
        when (result) {
            is TransportResult.Sent -> {
                val providerId = result.providerMessageId ?: return
                if (sentPartTracker.record(result)) {
                    smsProviderDataSource.updateStatus(providerId, SmsProviderStatus.COMPLETE)
                    providerStateChanged = true
                }
            }

            is TransportResult.Failed -> {
                sentPartTracker.forget(result.operationId)
                result.providerMessageId?.let { providerId ->
                    smsProviderDataSource.updateStatus(providerId, SmsProviderStatus.FAILED)
                    providerStateChanged = true
                }
            }

            is TransportResult.Delivered,
            is TransportResult.Downloaded,
            is TransportResult.Rejected,
            is TransportResult.Submitted -> Unit
        }
        if (providerStateChanged) enqueueIndexSignal(IndexSignal.CONTENT_OBSERVER_CHANGE)
    }

    fun onDownloadedMms(
        @Suppress("UNUSED_PARAMETER") operationId: MessageId,
        @Suppress("UNUSED_PARAMETER") pdu: EncodedMmsPdu,
    ) {
        // ADR 0001 intentionally forbids retaining or partially decoding this
        // payload until an audited codec is admitted.
    }

    suspend fun onDefaultSmsRoleChanged(isDefaultSmsApp: Boolean) {
        previewLoader.clear()
        if (!isDefaultSmsApp && !syntheticIndexOnly) {
            replyTargets.clear()
            sentPartTracker.clear()
        }
        if (!isDefaultSmsApp) {
            try {
                (indexRuntimeState.value as? IndexRuntimeState.Ready)
                    ?.runtime
                    ?.synchronizer
                    ?.pauseForRoleLoss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                // Role loss remains authoritative even if the disposable index
                // cannot persist its best-effort paused marker.
            }
        }
        if (isDefaultSmsApp) requestIndexOpen()
        enqueueIndexSignal(IndexSignal.ROLE_CHANGED)
    }

    suspend fun onExternalProviderChanged() {
        enqueueIndexSignal(IndexSignal.EXTERNAL_PROVIDER_CHANGE)
    }

    /** Retries observer registration and reconciliation after explicit role/permission UI success. */
    fun onMessagingEligibilityChanged() {
        applicationScope.launch { previewLoader.clear() }
        requestIndexOpen()
        if (!syntheticIndexOnly) {
            ensureProviderObserverRegistered()
            enqueueIndexSignal(IndexSignal.ROLE_CHANGED)
        }
    }

    fun onContactsPermissionChanged() {
        contactCacheController?.onContactsPermissionChanged()
    }

    fun createDraftWriter(
        identity: DraftIdentity,
        restoredUnacknowledged: DraftEditorContent?,
    ): SerializedDraftWriter = SerializedDraftWriter(
        repository = draftRepository,
        identity = identity,
        scope = applicationScope,
        restoredUnacknowledged = restoredUnacknowledged,
    )

    /** Gives the newest accepted edit a bounded acknowledgement window before release. */
    fun releaseDraftWriter(writer: SerializedDraftWriter) {
        applicationScope.launch {
            try {
                writer.flush()
            } finally {
                writer.close()
            }
        }
    }

    fun close() {
        runCatching { application.contentResolver.unregisterContentObserver(providerObserver) }
        providerObserverRegistered.set(false)
        indexSignalWakeUps.close()
        indexRetryJob?.cancel()
        indexCoordinator.close()
        activeIndexDatabase?.let(IndexDatabaseFactory::close)
        stateDatabase?.let(StateDatabaseFactory::close)
        contactCacheController?.close()
        applicationScope.cancel()
    }

    private suspend fun signalIndex(signal: IndexSignal) {
        try {
            indexCoordinator.signal(signal)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            // Index/state failures remain typed in their own stores and must
            // never break carrier message persistence or notification paths.
        }
    }

    private fun enqueueIndexSignal(signal: IndexSignal) {
        if (syntheticIndexOnly) return
        if (signal.requiresDurableAmbiguousLedger()) recordAmbiguousSignal()
        synchronized(indexSignalLock) {
            pendingIndexSignals.add(signal)
        }
        indexSignalWakeUps.trySend(Unit)
    }

    private fun recordAmbiguousSignal() {
        synchronized(indexSignalLock) {
            ambiguousSignalSequence += 1L
            if (ambiguousSignalLedgered) return
            ambiguousSignalLedgered = persistAmbiguousSignalLedger(value = true)
        }
    }

    private fun clearAmbiguousSignalLedger(expectedSequence: Long) {
        synchronized(indexSignalLock) {
            if (!ambiguousSignalLedgered || ambiguousSignalSequence != expectedSequence) return
            if (persistAmbiguousSignalLedger(value = false)) ambiguousSignalLedgered = false
        }
    }

    @SuppressLint("UseKtx")
    private fun persistAmbiguousSignalLedger(value: Boolean): Boolean =
        indexSignalLedger.edit().putBoolean(INDEX_SIGNAL_LEDGER_KEY, value).commit()

    private fun takePendingIndexSignals(): Set<IndexSignal> = synchronized(indexSignalLock) {
        pendingIndexSignals.toSet().also { pendingIndexSignals.clear() }
    }

    private fun requestIndexOpen() {
        applicationScope.launch(Dispatchers.IO) {
            indexOpenMutex.withLock {
                if (indexRuntimeState.value is IndexRuntimeState.Ready) return@withLock
                indexRuntimeState.value = IndexRuntimeState.Opening
                _indexStorageStatus.value = IndexStorageStatus.Opening
                when (val result = IndexDatabaseFactory.open(application)) {
                    is IndexDatabaseOpenResult.Opened -> {
                        val runtime = IndexRuntime(
                            messageIndex = RoomMessageIndex(result.database),
                            conversationRepository = RoomConversationRepository(result.database),
                            threadTimelineRepository = RoomThreadTimelineRepository(result.database),
                            synchronizer = TelephonyIndexSynchronizer(
                                database = result.database,
                                smsSource = smsProviderDataSource,
                                mmsSource = mmsProviderDataSource,
                                roleState = defaultSmsRoleState,
                            ),
                        )
                        activeIndexDatabase = result.database
                        indexRuntimeState.value = IndexRuntimeState.Ready(runtime)
                        _indexStorageStatus.value = IndexStorageStatus.Ready(result.recovered)
                        indexRetryJob?.cancel()
                        if (!syntheticIndexOnly) {
                            ensureProviderObserverRegistered()
                            if (synchronized(indexSignalLock) { ambiguousSignalLedgered }) {
                                enqueueIndexSignal(IndexSignal.EXTERNAL_PROVIDER_CHANGE)
                            }
                            indexCoordinator.start()
                        }
                    }
                    is IndexDatabaseOpenResult.Failed -> {
                        indexRuntimeState.value = IndexRuntimeState.Failed(result.reason)
                        _indexStorageStatus.value = IndexStorageStatus.Failed(result.reason)
                        scheduleIndexOpenRetry()
                    }
                }
            }
        }
    }

    private fun scheduleIndexOpenRetry() {
        if (indexRetryJob?.isActive == true) return
        indexRetryJob = applicationScope.launch {
            delay(AppIndexCoordinator.DEFAULT_PERIODIC_INTERVAL_MILLIS)
            requestIndexOpen()
        }
    }

    private suspend fun awaitIndexRuntime(): IndexRuntime = indexRuntimeState.awaitReadyRuntime()

    private fun ensureProviderObserverRegistered() {
        if (syntheticIndexOnly) return
        if (!providerObserverRegistered.compareAndSet(false, true)) return
        try {
            application.contentResolver.registerContentObserver(
                Telephony.MmsSms.CONTENT_URI,
                true,
                providerObserver,
            )
        } catch (_: SecurityException) {
            providerObserverRegistered.set(false)
        } catch (_: RuntimeException) {
            providerObserverRegistered.set(false)
        }
    }

    private companion object {
        const val INDEX_SIGNAL_LEDGER_NAME: String = "aurora_index_signal_ledger"
        const val INDEX_SIGNAL_LEDGER_KEY: String = "ambiguous_provider_change_pending"
    }
}

private object AddressOnlyContactCache : ContactCache {
    override val invalidations: Flow<ContactCacheInvalidation> = emptyFlow()

    override suspend fun resolve(addresses: List<ParticipantAddress>): List<ResolvedContact> =
        addresses.map { address -> ResolvedContact(address, displayName = null, photoUri = null) }

    override suspend fun invalidate() = Unit
}

private object UnavailableSubscriptionRepository : SubscriptionRepository {
    override suspend fun activeSubscriptions(): SubscriptionSnapshot =
        SubscriptionSnapshot.FeatureUnavailable
}

private object UnavailableMmsAttachmentRepository : MmsAttachmentRepository {
    override suspend fun listStaticImages(
        providerMessageId: org.aurorasms.core.model.ProviderMessageId,
    ): MmsAttachmentListResult = MmsAttachmentListResult.Unavailable

    override suspend fun <T> read(
        id: MmsAttachmentId,
        reader: MmsAttachmentContentReader<T>,
    ): MmsAttachmentReadResult<T> = MmsAttachmentReadResult.Unavailable
}

private object EmptyBenchmarkDraftRepository : DraftRepository {
    override suspend fun create(draft: NewDraft): DraftRepositoryResult<Draft> =
        DraftRepositoryResult.StorageFailure(DraftStorageOperation.CREATE)

    override suspend fun read(id: DraftId): DraftRepositoryResult<Draft> = DraftRepositoryResult.NotFound

    override suspend fun read(identity: DraftIdentity): DraftRepositoryResult<Draft> =
        DraftRepositoryResult.NotFound

    override suspend fun update(
        draft: Draft,
        expectedRevision: DraftRevision,
    ): DraftRepositoryResult<Draft> = DraftRepositoryResult.StorageFailure(DraftStorageOperation.UPDATE)

    override suspend fun delete(id: DraftId): DraftRepositoryResult<Unit> =
        DraftRepositoryResult.StorageFailure(DraftStorageOperation.DELETE)
}

sealed interface IndexStorageStatus {
    data object Opening : IndexStorageStatus
    data class Ready(val recovered: Boolean) : IndexStorageStatus
    data class Failed(val reason: IndexDatabaseOpenFailureReason) : IndexStorageStatus
}

sealed interface StateStorageStatus {
    data object Opening : StateStorageStatus
    data object Ready : StateStorageStatus
    data class Failed(val reason: StateDatabaseOpenFailureReason) : StateStorageStatus
}

private data class IndexRuntime(
    val messageIndex: MessageIndex,
    val conversationRepository: ConversationRepository,
    val threadTimelineRepository: ThreadTimelineRepository,
    val synchronizer: TelephonyIndexSynchronizer,
)

private sealed interface IndexRuntimeState {
    data object Opening : IndexRuntimeState
    data class Ready(val runtime: IndexRuntime) : IndexRuntimeState
    data class Failed(val reason: IndexDatabaseOpenFailureReason) : IndexRuntimeState
}

private class DeferredMessageIndex(
    private val runtimeState: StateFlow<IndexRuntimeState>,
) : MessageIndex {
    override suspend fun coverage(): IndexCoverage = runtimeState.awaitReadyRuntime().messageIndex.coverage()

    override suspend fun search(request: SearchRequest): SearchResult =
        runtimeState.awaitReadyRuntime().messageIndex.search(request)

    override suspend fun loadAnchor(
        anchor: SearchAnchor,
        halfWindow: Int,
    ): AnchorWindowResult = runtimeState.awaitReadyRuntime().messageIndex.loadAnchor(anchor, halfWindow)

    override fun toString(): String = "DeferredMessageIndex(content=REDACTED)"
}

private class DeferredConversationRepository(
    private val runtimeState: StateFlow<IndexRuntimeState>,
) : ConversationRepository {
    override val invalidations: Flow<ConversationInvalidation> = callbackFlow {
        val collector = launch {
            runtimeState.awaitReadyRuntime().conversationRepository.invalidations.collect { signal ->
                send(signal)
            }
        }
        awaitClose { collector.cancel() }
    }.conflate()

    override suspend fun loadInbox(request: ConversationPageRequest): ConversationPageResult =
        runtimeState.awaitReadyRuntime().conversationRepository.loadInbox(request)

    override suspend fun loadConversation(providerThreadId: ProviderThreadId): ConversationLookupResult =
        runtimeState.awaitReadyRuntime().conversationRepository.loadConversation(providerThreadId)

    override fun toString(): String = "DeferredConversationRepository(content=REDACTED)"
}

private class DeferredThreadTimelineRepository(
    private val runtimeState: StateFlow<IndexRuntimeState>,
) : ThreadTimelineRepository {
    override suspend fun load(request: TimelinePageRequest): TimelinePageResult =
        runtimeState.awaitReadyRuntime().threadTimelineRepository.load(request)

    override suspend fun loadContent(
        providerThreadId: ProviderThreadId,
        providerMessageId: org.aurorasms.core.model.ProviderMessageId,
    ): TimelineContentResult = runtimeState.awaitReadyRuntime()
        .threadTimelineRepository
        .loadContent(providerThreadId, providerMessageId)

    override fun toString(): String = "DeferredThreadTimelineRepository(content=REDACTED)"
}

private class IndexStorageUnavailableException : IllegalStateException("Index storage unavailable")

private sealed interface StateRuntimeState {
    data object Opening : StateRuntimeState
    data class Ready(val draftRepository: DraftRepository) : StateRuntimeState
    data class Failed(val reason: StateDatabaseOpenFailureReason) : StateRuntimeState
}

private class DeferredDraftRepository(
    private val runtimeState: StateFlow<StateRuntimeState>,
) : DraftRepository {
    override suspend fun create(draft: NewDraft): DraftRepositoryResult<Draft> =
        runtimeState.awaitReadyDraftRepository().create(draft)

    override suspend fun read(id: DraftId): DraftRepositoryResult<Draft> =
        runtimeState.awaitReadyDraftRepository().read(id)

    override suspend fun read(identity: DraftIdentity): DraftRepositoryResult<Draft> =
        runtimeState.awaitReadyDraftRepository().read(identity)

    override suspend fun update(
        draft: Draft,
        expectedRevision: DraftRevision,
    ): DraftRepositoryResult<Draft> = runtimeState.awaitReadyDraftRepository().update(draft, expectedRevision)

    override suspend fun delete(id: DraftId): DraftRepositoryResult<Unit> =
        runtimeState.awaitReadyDraftRepository().delete(id)

    override fun toString(): String = "DeferredDraftRepository(content=REDACTED)"
}

private class StateStorageUnavailableException : IllegalStateException("State storage unavailable")

private fun IndexSignal.requiresDurableAmbiguousLedger(): Boolean = when (this) {
    IndexSignal.STARTUP,
    IndexSignal.INCOMING_INSERT,
    IndexSignal.PERIODIC_RECONCILIATION,
    -> false
    IndexSignal.CONTENT_OBSERVER_CHANGE,
    IndexSignal.EXTERNAL_PROVIDER_CHANGE,
    IndexSignal.ROLE_CHANGED,
    -> true
}

private suspend fun StateFlow<IndexRuntimeState>.awaitReadyRuntime(): IndexRuntime {
    val state = when (val current = value) {
        IndexRuntimeState.Opening -> first { it !is IndexRuntimeState.Opening }
        else -> current
    }
    return when (state) {
        is IndexRuntimeState.Ready -> state.runtime
        is IndexRuntimeState.Failed -> throw IndexStorageUnavailableException()
        IndexRuntimeState.Opening -> error("Index runtime did not leave opening state")
    }
}

private suspend fun StateFlow<StateRuntimeState>.awaitReadyDraftRepository(): DraftRepository {
    val state = when (val current = value) {
        StateRuntimeState.Opening -> first { it !is StateRuntimeState.Opening }
        else -> current
    }
    return when (state) {
        is StateRuntimeState.Ready -> state.draftRepository
        is StateRuntimeState.Failed -> throw StateStorageUnavailableException()
        StateRuntimeState.Opening -> error("State runtime did not leave opening state")
    }
}

private class SentPartTracker {
    private val sentParts = mutableMapOf<MessageId, BooleanArray>()

    @Synchronized
    fun record(result: TransportResult.Sent): Boolean {
        val parts = sentParts[result.operationId]
            ?.takeIf { it.size == result.unitCount }
            ?: BooleanArray(result.unitCount).also { sentParts[result.operationId] = it }
        parts[result.unitIndex] = true
        val complete = parts.all { it }
        if (complete) sentParts.remove(result.operationId)
        return complete
    }

    @Synchronized
    fun forget(operationId: MessageId) {
        sentParts.remove(operationId)
    }

    @Synchronized
    fun clear() {
        sentParts.clear()
    }
}
