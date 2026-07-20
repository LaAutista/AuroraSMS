// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import android.app.Application
import android.annotation.SuppressLint
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import java.util.EnumSet
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.aurorasms.app.contacts.AppContactCacheController
import org.aurorasms.app.appearance.AppearanceController
import org.aurorasms.app.appearance.wallpaper.ManagedWallpaperStore
import org.aurorasms.app.appearance.wallpaper.WallpaperController
import org.aurorasms.app.drafts.DraftRestorationToken
import org.aurorasms.app.drafts.SerializedDraftWriter
import org.aurorasms.app.index.ForegroundIndexReadGate
import org.aurorasms.app.preview.AndroidBoundedPreviewLoader
import org.aurorasms.app.preview.BoundedMediaDecodeGate
import org.aurorasms.app.voice.VoiceMemoController
import org.aurorasms.core.index.AnchorWindowResult
import org.aurorasms.core.index.IndexCoverage
import org.aurorasms.app.index.AppIndexCoordinator
import org.aurorasms.app.message.AppNotificationIntentFactory
import org.aurorasms.app.message.IncomingMessageOrchestrator
import org.aurorasms.app.message.IncomingNotificationRecoveryResult
import org.aurorasms.app.message.InlineReplyOrchestrator
import org.aurorasms.app.message.InlineReplyProviderUpdateCoordinator
import org.aurorasms.app.message.InlineReplyTransportDisposition
import org.aurorasms.app.message.InlineReplyTransportResultHandler
import org.aurorasms.app.message.ReplyOperationRegistry
import org.aurorasms.app.message.ReplyOperationFailureKind
import org.aurorasms.app.message.ReplyOperationPendingFailuresResult
import org.aurorasms.app.message.ReplyOperationRecoveryResult
import org.aurorasms.app.message.ReplyTargetRegistry
import org.aurorasms.app.message.SharedPreferencesReplyOperationStore
import org.aurorasms.app.message.SharedPreferencesReplyReplayGuard
import org.aurorasms.app.message.SharedPreferencesReplyTargetStore
import org.aurorasms.app.message.DeferredThreadSmsSendController
import org.aurorasms.app.message.ThreadSmsSendController
import org.aurorasms.app.message.ThreadSmsSendCoordinator
import org.aurorasms.app.message.AndroidScheduledSmsAlarmDriver
import org.aurorasms.app.message.DeferredScheduledSmsController
import org.aurorasms.app.message.ScheduledSmsController
import org.aurorasms.app.message.ScheduledSmsCoordinator
import org.aurorasms.app.message.ScheduledSmsRecoveryReason
import org.aurorasms.app.message.UnavailableScheduledSmsController
import org.aurorasms.app.message.UnavailableThreadSmsSendController
import org.aurorasms.app.message.AndroidSendDelayAlarmDriver
import org.aurorasms.app.message.DeferredSendDelayController
import org.aurorasms.app.message.SendDelayController
import org.aurorasms.app.message.SendDelayCoordinator
import org.aurorasms.app.message.SendDelayRecoveryReason
import org.aurorasms.app.message.SharedPreferencesSendDelayPreferenceStore
import org.aurorasms.app.message.UnavailableSendDelayController
import org.aurorasms.app.message.AndroidPermanentDeletionAlarmDriver
import org.aurorasms.app.message.DeferredPermanentDeletionController
import org.aurorasms.app.message.PermanentDeletionController
import org.aurorasms.app.message.PermanentDeletionCoordinator
import org.aurorasms.app.message.PermanentDeletionRecoveryReason
import org.aurorasms.app.message.UnavailablePermanentDeletionController
import org.aurorasms.app.message.AndroidNotificationReminderAlarmDriver
import org.aurorasms.app.message.NotificationReminderController
import org.aurorasms.app.message.NotificationMarkReadCoordinator
import org.aurorasms.app.message.NotificationReminderCoordinator
import org.aurorasms.app.message.NotificationReminderId
import org.aurorasms.app.message.NotificationReminderRecoveryReason
import org.aurorasms.app.message.SharedPreferencesNotificationReminderPreferenceStore
import org.aurorasms.app.message.SharedPreferencesMessageSignaturePreferenceStore
import org.aurorasms.app.message.SharedPreferencesNotificationReminderStore
import org.aurorasms.app.message.requiresFollowUp
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.PendingOperationNamespace
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.model.TransportResult
import org.aurorasms.core.model.pendingOperationNamespaceOrNull
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
import org.aurorasms.core.notifications.MarkConversationReadHandler
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.ContactCache
import org.aurorasms.core.telephony.ContactCacheInvalidation
import org.aurorasms.core.telephony.EncodedMmsPdu
import org.aurorasms.core.telephony.IncomingMessageSink
import org.aurorasms.core.telephony.IncomingMmsDownloadResult
import org.aurorasms.core.telephony.IncomingMmsRecoveryResult
import org.aurorasms.core.telephony.IncomingPersistResult
import org.aurorasms.core.telephony.MessageTransport
import org.aurorasms.core.telephony.MmsAttachmentContentReader
import org.aurorasms.core.telephony.MmsAttachmentId
import org.aurorasms.core.telephony.MmsAttachmentListResult
import org.aurorasms.core.telephony.MmsAttachmentReadResult
import org.aurorasms.core.telephony.MmsAttachmentRepository
import org.aurorasms.core.telephony.MmsStagedPduDisposition
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.OutgoingSmsStatusUpdateOutcome
import org.aurorasms.core.telephony.ResolvedContact
import org.aurorasms.core.telephony.SmsProviderDataSource
import org.aurorasms.core.telephony.SmsProviderStatus
import org.aurorasms.core.telephony.SubscriptionRepository
import org.aurorasms.core.telephony.SubscriptionSnapshot
import org.aurorasms.core.telephony.followUpRequired
import org.aurorasms.core.telephony.internal.AndroidContactResolver
import org.aurorasms.core.telephony.internal.AndroidDefaultSmsRoleState
import org.aurorasms.core.telephony.internal.AndroidMmsProviderDataSource
import org.aurorasms.core.telephony.internal.AndroidMmsAttachmentRepository
import org.aurorasms.core.telephony.internal.AndroidMmsTransport
import org.aurorasms.core.telephony.internal.AndroidSmsProviderDataSource
import org.aurorasms.core.telephony.internal.AndroidSmsTransport
import org.aurorasms.core.telephony.internal.AndroidSubscriptionRepository
import org.aurorasms.core.telephony.internal.MmsPduStagingStore
import org.aurorasms.core.telephony.internal.OutgoingMmsCallbackDisposition
import org.aurorasms.core.state.storage.AuroraStateDatabase
import org.aurorasms.core.state.AppearanceProfile
import org.aurorasms.core.state.AppearanceProfileEdit
import org.aurorasms.core.state.AppearanceProfileId
import org.aurorasms.core.state.AppearanceProfileRepository
import org.aurorasms.core.state.AppearanceOverride
import org.aurorasms.core.state.AppearanceOverrideRevision
import org.aurorasms.core.state.AppearanceRepositoryResult
import org.aurorasms.core.state.AppearanceRevision
import org.aurorasms.core.state.AppearanceScope
import org.aurorasms.core.state.AppearanceScreenScope
import org.aurorasms.core.state.AppearanceSnapshot
import org.aurorasms.core.state.AppearanceStorageOperation
import org.aurorasms.core.state.AppearanceWallpaperAssignment
import org.aurorasms.core.state.AppearanceWallpaperMediaId
import org.aurorasms.core.state.AppearanceWallpaperMutation
import org.aurorasms.core.state.AppearanceWallpaperRepository
import org.aurorasms.core.state.AppearanceWallpaperRevision
import org.aurorasms.core.state.ConversationSubscriptionPreference
import org.aurorasms.core.state.ConversationSubscriptionPreferenceRepository
import org.aurorasms.core.state.ConversationSubscriptionRepositoryResult
import org.aurorasms.core.state.ConversationSubscriptionRevision
import org.aurorasms.core.state.ConversationSubscriptionScope
import org.aurorasms.core.state.ConversationSubscriptionStorageOperation
import org.aurorasms.core.state.Draft
import org.aurorasms.core.state.DraftAttachment
import org.aurorasms.core.state.DraftAttachmentRepository
import org.aurorasms.core.state.DraftId
import org.aurorasms.core.state.DraftIdentity
import org.aurorasms.core.state.DraftRepository
import org.aurorasms.core.state.DraftRepositoryResult
import org.aurorasms.core.state.DraftRevision
import org.aurorasms.core.state.DraftStorageOperation
import org.aurorasms.core.state.NewDraft
import org.aurorasms.core.state.NewAppearanceProfile
import org.aurorasms.core.state.storage.RoomAppearanceProfileRepository
import org.aurorasms.core.state.storage.RoomComposerSmsOperationRepository
import org.aurorasms.core.state.storage.RoomConversationSubscriptionPreferenceRepository
import org.aurorasms.core.state.storage.RoomDraftRepository
import org.aurorasms.core.state.storage.RoomDraftAttachmentRepository
import org.aurorasms.core.state.storage.RoomScheduledSmsRepository
import org.aurorasms.core.state.storage.RoomSendDelayRepository
import org.aurorasms.core.state.storage.RoomPermanentDeletionRepository
import org.aurorasms.core.state.storage.StateDatabaseFactory
import org.aurorasms.core.state.storage.StateDatabaseOpenFailureReason
import org.aurorasms.core.state.storage.StateDatabaseOpenResult
import org.aurorasms.core.state.ScheduledSmsId
import org.aurorasms.core.state.SendDelayId
import org.aurorasms.core.state.PermanentDeletionId
import org.aurorasms.core.state.SpamClassification
import org.aurorasms.core.state.SpamSafetyDecision
import org.aurorasms.core.state.SpamSafetyRepository
import org.aurorasms.core.state.SpamSafetyRepositoryResult
import org.aurorasms.core.state.SpamSafetyRevision
import org.aurorasms.core.state.SpamSafetyScope
import org.aurorasms.core.state.SpamSafetySnapshot
import org.aurorasms.core.state.SpamSafetyStorageOperation
import org.aurorasms.core.state.storage.RoomSpamSafetyRepository
import org.aurorasms.core.telephony.internal.AndroidPermanentDeletionProvider
import org.aurorasms.feature.conversations.BoundedPreviewLoader
import org.aurorasms.feature.backup.AuroraBackupDocumentController
import org.aurorasms.feature.backup.AuroraBackupStartupRecoveryResult

class AppContainer(
    val application: Application,
    private val syntheticIndexOnly: Boolean = false,
) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val replyTargets = ReplyTargetRegistry(
        maximumEntries = 4_096,
        targetStore = SharedPreferencesReplyTargetStore(application),
    )
    private val replyOperations = ReplyOperationRegistry(
        store = SharedPreferencesReplyOperationStore(application),
    )
    private var pendingInitialReplyOperationRecovery: ReplyOperationRecoveryResult? =
        replyOperations.recoverInheritedOperations()
    private val messagingRecoveryLock = Any()
    private var messagingRecoveryJob: Job? = null
    private var messagingRecoveryFollowUpJob: Job? = null
    private var messagingRecoveryRequested = false
    private var messagingRecoveryRoleEnabled = true
    private var replyColdCallbackGraceApplied = false
    private var scheduledStartupRecoveryPending = true
    private val sentPartTracker = SentPartTracker()
    private val indexSignalLedger = application.getSharedPreferences(
        INDEX_SIGNAL_LEDGER_NAME,
        Context.MODE_PRIVATE,
    )

    val defaultSmsRoleState: DefaultSmsRoleState = AndroidDefaultSmsRoleState(application)
    private val defaultSmsRoleLifecycleFence = DefaultSmsRoleLifecycleFence(
        currentRoleHeld = defaultSmsRoleState::isRoleHeld,
    )
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
    private val permanentDeletionProvider = AndroidPermanentDeletionProvider(
        context = application,
        roleState = defaultSmsRoleState,
        sms = smsProviderDataSource,
        mms = mmsProviderDataSource,
    )
    val mmsAttachmentRepository: MmsAttachmentRepository = if (syntheticIndexOnly) {
        UnavailableMmsAttachmentRepository
    } else {
        AndroidMmsAttachmentRepository(application, defaultSmsRoleState)
    }
    private val boundedMediaDecodeGate = BoundedMediaDecodeGate()
    val previewLoader: BoundedPreviewLoader = AndroidBoundedPreviewLoader(
        repository = mmsAttachmentRepository,
        applicationScope = applicationScope,
        decodeGate = boundedMediaDecodeGate,
    )
    private val indexRuntimeState = MutableStateFlow<IndexRuntimeState>(IndexRuntimeState.Opening)
    private val indexOpenMutex = Mutex()
    private val foregroundIndexReadGate = ForegroundIndexReadGate()
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
                reconciliationCoversAmbiguousSignalLedger(reasons)
            ) {
                clearAmbiguousSignalLedger(signalSequence)
            }
            outcome
        },
        markPendingChanges = { awaitIndexRuntime().synchronizer.markPendingChanges() },
        shouldContinuePending = {
            defaultSmsRoleState.isRoleHeld() &&
                foregroundIndexReadGate.isProviderReadPermitted()
        },
    )

    private val _indexStorageStatus = MutableStateFlow<IndexStorageStatus>(IndexStorageStatus.Opening)
    val indexStorageStatus: StateFlow<IndexStorageStatus> = _indexStorageStatus.asStateFlow()

    private val _stateStorageStatus = MutableStateFlow<StateStorageStatus>(StateStorageStatus.Opening)
    val stateStorageStatus: StateFlow<StateStorageStatus> = _stateStorageStatus.asStateFlow()
    private val stateRuntimeState = MutableStateFlow<StateRuntimeState>(StateRuntimeState.Opening)
    val draftRepository: DraftRepository = DeferredDraftRepository(stateRuntimeState)
    val draftAttachmentRepository: DraftAttachmentRepository =
        DeferredDraftAttachmentRepository(stateRuntimeState)
    val appearanceProfileRepository: AppearanceProfileRepository =
        DeferredAppearanceProfileRepository(stateRuntimeState)
    private val appearanceWallpaperRepository: AppearanceWallpaperRepository =
        DeferredAppearanceWallpaperRepository(stateRuntimeState)
    val conversationSubscriptionPreferenceRepository:
        ConversationSubscriptionPreferenceRepository =
        DeferredConversationSubscriptionPreferenceRepository(stateRuntimeState)
    val spamSafetyRepository: SpamSafetyRepository = DeferredSpamSafetyRepository(stateRuntimeState)
    val appearanceController = AppearanceController(
        repository = appearanceProfileRepository,
        scope = applicationScope,
    )
    internal val wallpaperController = WallpaperController(
        repository = appearanceWallpaperRepository,
        store = ManagedWallpaperStore(application, boundedMediaDecodeGate),
    )
    internal val backupDocumentController = AuroraBackupDocumentController(application)
    private val _backupStartupRecovery = MutableStateFlow<AuroraBackupStartupRecoveryResult?>(null)
    internal val backupStartupRecovery: StateFlow<AuroraBackupStartupRecoveryResult?> =
        _backupStartupRecovery.asStateFlow()
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
        provider = mmsProviderDataSource,
    )
    private val androidSmsTransport = AndroidSmsTransport(
        context = application,
        roleState = defaultSmsRoleState,
        subscriptions = subscriptionRepository,
        smsProvider = smsProviderDataSource,
        mmsTransport = mmsTransport,
    )
    val messageTransport: MessageTransport = androidSmsTransport
    internal val voiceMemoController = VoiceMemoController(
        context = application,
        transport = messageTransport,
        scope = applicationScope,
    )
    private val deferredThreadSmsSendController = DeferredThreadSmsSendController()
    internal val threadSmsSendController: ThreadSmsSendController = deferredThreadSmsSendController
    private val deferredScheduledSmsController = DeferredScheduledSmsController()
    internal val scheduledSmsController: ScheduledSmsController = deferredScheduledSmsController
    private val deferredSendDelayController = DeferredSendDelayController()
    internal val sendDelayController: SendDelayController = deferredSendDelayController
    private val deferredPermanentDeletionController = DeferredPermanentDeletionController()
    internal val permanentDeletionController: PermanentDeletionController =
        deferredPermanentDeletionController
    internal val sendDelayPreferenceStore = SharedPreferencesSendDelayPreferenceStore(application)
    internal val messageSignaturePreferenceStore =
        SharedPreferencesMessageSignaturePreferenceStore(application)
    private val notificationReminderPreferenceStore =
        SharedPreferencesNotificationReminderPreferenceStore(application)
    val messageNotifier: MessageNotifier = AndroidMessageNotifier(
        context = application,
        intentFactory = AppNotificationIntentFactory(application),
    )
    internal val notificationReminderController: NotificationReminderController =
        NotificationReminderCoordinator(
            roleState = defaultSmsRoleState,
            smsProvider = smsProviderDataSource,
            notifier = messageNotifier,
            preferences = notificationReminderPreferenceStore,
            store = SharedPreferencesNotificationReminderStore(application),
            alarms = AndroidNotificationReminderAlarmDriver(application),
        )
    val markConversationReadHandler: MarkConversationReadHandler =
        NotificationMarkReadCoordinator(
            roleState = defaultSmsRoleState,
            smsProvider = smsProviderDataSource,
            notifier = messageNotifier,
            reminders = notificationReminderController,
            onProviderChanged = {
                enqueueIndexSignal(IndexSignal.CONTENT_OBSERVER_CHANGE)
            },
        )
    private val inlineReplyTransportResultHandler = InlineReplyTransportResultHandler(
        replyOperations = replyOperations,
        messageNotifier = messageNotifier,
        userVisibleEffectsAllowed = defaultSmsRoleState::isRoleHeld,
    )
    private val inlineReplyProviderUpdateCoordinator = InlineReplyProviderUpdateCoordinator(
        replyOperations = replyOperations,
        smsProvider = smsProviderDataSource,
    )
    private val incomingMessageOrchestrator = IncomingMessageOrchestrator(
        roleState = defaultSmsRoleState,
        smsProvider = smsProviderDataSource,
        contactResolver = contactCache,
        messageNotifier = messageNotifier,
        replyTargets = replyTargets,
        isSenderBlocked = { sender ->
            (spamSafetyRepository.isSenderBlocked(sender) as? SpamSafetyRepositoryResult.Success)
                ?.value == true
        },
        persistMms = mmsTransport::submitIncomingNotification,
        acknowledgeMms = mmsTransport::acknowledgeDownloadedMms,
        onProviderInsertComplete = {
            enqueueIndexSignal(IndexSignal.INCOMING_INSERT)
            retryPendingInlineReplyOperations()
        },
        onIncomingNotificationCommitted = notificationReminderController::schedule,
    )
    val incomingMessageSink: IncomingMessageSink = incomingMessageOrchestrator
    val inlineReplyHandler: InlineReplyHandler = InlineReplyOrchestrator(
        roleState = defaultSmsRoleState,
        replyTargets = replyTargets,
        replayGuard = SharedPreferencesReplyReplayGuard(application),
        replyOperations = replyOperations,
        transportResultHandler = inlineReplyTransportResultHandler,
        messageTransport = messageTransport,
        messageNotifier = messageNotifier,
        reconcileProviderUpdate = { operationId ->
            if (inlineReplyProviderUpdateCoordinator.reconcile(operationId)) {
                enqueueIndexSignal(IndexSignal.CONTENT_OBSERVER_CHANGE)
            }
        },
    )

    private val _lastTransportResult = MutableStateFlow<TransportResult?>(null)
    val lastTransportResult: StateFlow<TransportResult?> = _lastTransportResult.asStateFlow()

    val lastIndexOutcome: StateFlow<IndexSyncOutcome?> = indexCoordinator.lastOutcome

    init {
        retryPendingInlineReplyOperations()
        if (!syntheticIndexOnly) {
            applicationScope.launch(Dispatchers.IO) {
                notificationReminderController.recover(
                    NotificationReminderRecoveryReason.APP_STARTUP,
                )
            }
            applicationScope.launch(Dispatchers.IO) {
                _backupStartupRecovery.value = backupDocumentController.recoverStartup()
            }
        }
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
            deferredThreadSmsSendController.install(UnavailableThreadSmsSendController)
            deferredScheduledSmsController.install(UnavailableScheduledSmsController)
            deferredSendDelayController.install(UnavailableSendDelayController)
            deferredPermanentDeletionController.install(UnavailablePermanentDeletionController)
            stateRuntimeState.value = StateRuntimeState.Ready(
                draftRepository = EmptyBenchmarkDraftRepository,
                draftAttachmentRepository = EmptyBenchmarkDraftAttachmentRepository,
                appearanceProfileRepository = EmptyBenchmarkAppearanceProfileRepository,
                appearanceWallpaperRepository = EmptyBenchmarkAppearanceWallpaperRepository,
                conversationSubscriptionPreferenceRepository =
                    EmptyBenchmarkConversationSubscriptionPreferenceRepository,
                spamSafetyRepository = EmptyBenchmarkSpamSafetyRepository,
            )
            _stateStorageStatus.value = StateStorageStatus.Ready
        } else {
            applicationScope.launch(Dispatchers.IO) {
                when (val result = StateDatabaseFactory.open(application)) {
                    is StateDatabaseOpenResult.Opened -> {
                        stateDatabase = result.database
                        val appearanceRepository = RoomAppearanceProfileRepository(result.database)
                        val composerRepository = RoomComposerSmsOperationRepository(result.database)
                        deferredThreadSmsSendController.install(
                            ThreadSmsSendCoordinator(
                                applicationScope = applicationScope,
                                roleState = defaultSmsRoleState,
                                conversations = conversationRepository,
                                subscriptions = subscriptionRepository,
                                operations = composerRepository,
                                transport = messageTransport,
                                smsProvider = smsProviderDataSource,
                                mmsProvider = mmsProviderDataSource,
                                subscriptionPreferences =
                                    RoomConversationSubscriptionPreferenceRepository(result.database),
                            ),
                        )
                        val scheduledController = ScheduledSmsCoordinator(
                            roleState = defaultSmsRoleState,
                            conversations = conversationRepository,
                            subscriptions = subscriptionRepository,
                            subscriptionPreferences =
                                RoomConversationSubscriptionPreferenceRepository(result.database),
                            repository = RoomScheduledSmsRepository(result.database),
                            alarms = AndroidScheduledSmsAlarmDriver(application),
                            sender = threadSmsSendController,
                        )
                        deferredScheduledSmsController.install(scheduledController)
                        deferredSendDelayController.install(
                            SendDelayCoordinator(
                                applicationScope = applicationScope,
                                roleState = defaultSmsRoleState,
                                conversations = conversationRepository,
                                subscriptions = subscriptionRepository,
                                subscriptionPreferences =
                                    RoomConversationSubscriptionPreferenceRepository(result.database),
                                repository = RoomSendDelayRepository(result.database),
                                alarms = AndroidSendDelayAlarmDriver(application),
                                sender = threadSmsSendController,
                            ),
                        )
                        deferredPermanentDeletionController.install(
                            PermanentDeletionCoordinator(
                                applicationScope = applicationScope,
                                roleState = defaultSmsRoleState,
                                repository = RoomPermanentDeletionRepository(result.database),
                                provider = permanentDeletionProvider,
                                alarms = AndroidPermanentDeletionAlarmDriver(application),
                                onProviderChanged = {
                                    enqueueIndexSignal(IndexSignal.CONTENT_OBSERVER_CHANGE)
                                },
                            ),
                        )
                        stateRuntimeState.value = StateRuntimeState.Ready(
                            draftRepository = RoomDraftRepository(result.database),
                            draftAttachmentRepository =
                                RoomDraftAttachmentRepository(result.database),
                            appearanceProfileRepository = appearanceRepository,
                            appearanceWallpaperRepository = appearanceRepository,
                            conversationSubscriptionPreferenceRepository =
                                RoomConversationSubscriptionPreferenceRepository(result.database),
                            spamSafetyRepository = RoomSpamSafetyRepository(result.database),
                        )
                        wallpaperController.reconcileManagedFiles()
                        _stateStorageStatus.value = StateStorageStatus.Ready
                    }
                    is StateDatabaseOpenResult.Failed -> {
                        deferredThreadSmsSendController.install(UnavailableThreadSmsSendController)
                        deferredScheduledSmsController.install(UnavailableScheduledSmsController)
                        deferredSendDelayController.install(UnavailableSendDelayController)
                        deferredPermanentDeletionController.install(
                            UnavailablePermanentDeletionController,
                        )
                        stateRuntimeState.value = StateRuntimeState.Failed(result.reason)
                        _stateStorageStatus.value = StateStorageStatus.Failed(result.reason)
                    }
                }
            }
        }
    }

    suspend fun onTransportResult(result: TransportResult) {
        _lastTransportResult.value = result
        if (
            result is TransportResult.Failed &&
            result.transport == MessageTransportKind.MMS &&
            result.stage == TransportResult.FailureStage.DOWNLOAD_CALLBACK &&
            result.operationId.pendingOperationNamespaceOrNull() == PendingOperationNamespace.INCOMING_MMS
        ) {
            return
        }
        if (
            result.transport == MessageTransportKind.MMS &&
            (result is TransportResult.Sent ||
                (result is TransportResult.Failed &&
                    result.stage == TransportResult.FailureStage.SENT_CALLBACK))
        ) {
            val disposition = mmsTransport.reconcileTransportResult(result)
            if (disposition == OutgoingMmsCallbackDisposition.APPLIED) {
                enqueueIndexSignal(IndexSignal.CONTENT_OBSERVER_CHANGE)
            }
            if (disposition.authenticated) voiceMemoController.handleTransportResult(result)
            if (
                disposition.authenticated &&
                result.operationOrigin == TransportResult.OperationOrigin.COMPOSER
            ) {
                val composerOwned = threadSmsSendController.handleTransportResult(result)
                if (composerOwned) {
                    enqueueIndexSignal(IndexSignal.CONTENT_OBSERVER_CHANGE)
                    scheduledSmsController.reconcileDispatches()
                    sendDelayController.reconcileDispatches()
                }
            }
            // A provider/role failure leaves the authenticated callback in the
            // content-free journal for the ordinary recovery pass.
            retryPendingInlineReplyOperations()
            return
        }
        if (result.operationOrigin == TransportResult.OperationOrigin.COMPOSER) {
            val composerOwned = threadSmsSendController.handleTransportResult(result)
            if (composerOwned) {
                enqueueIndexSignal(IndexSignal.CONTENT_OBSERVER_CHANGE)
                scheduledSmsController.reconcileDispatches()
                sendDelayController.reconcileDispatches()
            }
            // Re-run durable recovery even when the immediate provider write
            // failed or the operation lookup was temporarily unavailable.
            retryPendingInlineReplyOperations()
            // An explicitly composer-owned callback must never fall through to
            // the legacy or inline-reply mutation paths, even when its durable
            // operation cannot be recovered.
            return
        }
        var providerStateChanged = false
        when (result) {
            is TransportResult.Sent -> {
                val inlineReplyDisposition = inlineReplyTransportResultHandler.handle(result)
                if (inlineReplyDisposition == InlineReplyTransportDisposition.Untracked) {
                    if (
                        sentPartTracker.record(result) &&
                        updateExactOutgoingStatus(
                            providerId = result.providerMessageId,
                            providerConversationId = result.providerConversationId,
                            status = SmsProviderStatus.COMPLETE,
                        )
                    ) {
                        providerStateChanged = true
                    }
                } else {
                    providerStateChanged =
                        inlineReplyProviderUpdateCoordinator.reconcile(result.operationId)
                }
            }

            is TransportResult.Failed -> {
                val inlineReplyDisposition = inlineReplyTransportResultHandler.handle(result)
                if (inlineReplyDisposition == InlineReplyTransportDisposition.Untracked) {
                    result.smsProviderFailureStatus()?.let { providerStatus ->
                        sentPartTracker.forget(result.operationId)
                        if (
                            updateExactOutgoingStatus(
                                providerId = result.providerMessageId,
                                providerConversationId = result.providerConversationId,
                                status = providerStatus,
                            )
                        ) {
                            providerStateChanged = true
                        }
                    }
                } else {
                    sentPartTracker.forget(result.operationId)
                    providerStateChanged =
                        inlineReplyProviderUpdateCoordinator.reconcile(result.operationId)
                }
            }

            is TransportResult.Delivered -> {
                if (
                    inlineReplyTransportResultHandler.handle(result) !=
                    InlineReplyTransportDisposition.Untracked
                ) {
                    providerStateChanged =
                        inlineReplyProviderUpdateCoordinator.reconcile(result.operationId)
                }
            }

            is TransportResult.Rejected -> inlineReplyTransportResultHandler.handle(result)
            is TransportResult.Downloaded,
            is TransportResult.Submitted -> Unit
        }
        if (providerStateChanged) enqueueIndexSignal(IndexSignal.CONTENT_OBSERVER_CHANGE)
    }

    private suspend fun updateExactOutgoingStatus(
        providerId: org.aurorasms.core.model.ProviderMessageId?,
        providerConversationId: org.aurorasms.core.model.ConversationId?,
        status: SmsProviderStatus,
    ): Boolean {
        if (providerId == null || providerConversationId == null) return false
        return when (
            val update = smsProviderDataSource.updateOutgoingStatus(
                id = providerId,
                conversationId = providerConversationId,
                status = status,
            )
        ) {
            is ProviderAccessResult.Success ->
                update.value == OutgoingSmsStatusUpdateOutcome.APPLIED
            else -> false
        }
    }

    suspend fun onDownloadedMms(
        operationId: MessageId,
        stagedUri: Uri,
        pdu: EncodedMmsPdu,
    ): MmsStagedPduDisposition = when (
        val result = mmsTransport.reconcileDownloadedMms(operationId, stagedUri, pdu)
    ) {
        is IncomingMmsDownloadResult.ReadyForNotification -> {
            when (incomingMessageOrchestrator.notifyDownloadedMms(result.delivery)) {
                is IncomingPersistResult.Persisted -> {
                    _lastTransportResult.value = TransportResult.Downloaded(
                        operationId = operationId,
                        transport = MessageTransportKind.MMS,
                        platformResultCode = android.app.Activity.RESULT_OK,
                        byteCount = pdu.size,
                    )
                    MmsStagedPduDisposition.CLEANUP
                }
                is IncomingPersistResult.Duplicate,
                is IncomingPersistResult.Pending,
                is IncomingPersistResult.Rejected,
                -> MmsStagedPduDisposition.RETAIN
            }
        }
        IncomingMmsDownloadResult.TerminalRejected -> {
            _lastTransportResult.value = TransportResult.Failed(
                operationId = operationId,
                transport = MessageTransportKind.MMS,
                reason = TransportResult.FailureReason.PLATFORM_REJECTED,
                retryable = false,
                platformResultCode = android.app.Activity.RESULT_OK,
                stage = TransportResult.FailureStage.DOWNLOAD_CALLBACK,
            )
            MmsStagedPduDisposition.CLEANUP
        }
        IncomingMmsDownloadResult.Deferred,
        IncomingMmsDownloadResult.Ignored,
        -> MmsStagedPduDisposition.RETAIN
    }

    suspend fun onFailedMmsDownload(
        operationId: MessageId,
        stagedUri: Uri,
        result: TransportResult.Failed,
    ): MmsStagedPduDisposition {
        if (
            result.operationId != operationId ||
            operationId.pendingOperationNamespaceOrNull() != PendingOperationNamespace.INCOMING_MMS
        ) {
            return MmsStagedPduDisposition.RETAIN
        }
        _lastTransportResult.value = result
        return mmsTransport.reconcileFailedMmsDownload(operationId, stagedUri)
    }

    suspend fun onDefaultSmsRoleChanged(
        @Suppress("UNUSED_PARAMETER") isDefaultSmsApp: Boolean,
    ) = defaultSmsRoleLifecycleFence.reconcile { roleHeld ->
        reconcileMessagingRecoveryForRole(roleHeld)
        previewLoader.clear()
        if (!roleHeld && !syntheticIndexOnly) {
            if (!defaultSmsRoleState.isRoleHeld()) sentPartTracker.clear()
        }
        if (!roleHeld && !defaultSmsRoleState.isRoleHeld()) {
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
        if (defaultSmsRoleState.isRoleHeld()) requestIndexOpen()
        enqueueIndexSignal(IndexSignal.ROLE_CHANGED)
    }

    suspend fun onExternalProviderChanged() {
        enqueueIndexSignal(IndexSignal.EXTERNAL_PROVIDER_CHANGE)
    }

    suspend fun onScheduledSmsAlarm(id: ScheduledSmsId) {
        scheduledSmsController.handleAlarm(id)
    }

    suspend fun onSendDelayAlarm(id: SendDelayId) {
        sendDelayController.handleAlarm(id)
    }

    suspend fun onPermanentDeletionAlarm(id: PermanentDeletionId) {
        permanentDeletionController.handleAlarm(id)
    }

    internal suspend fun onNotificationReminderAlarm(id: NotificationReminderId) {
        notificationReminderController.handleAlarm(id)
    }

    fun onConversationOpened(conversationId: org.aurorasms.core.model.ConversationId?) {
        if (conversationId == null) return
        applicationScope.launch(Dispatchers.IO) {
            notificationReminderController.cancelConversation(conversationId)
        }
    }

    internal suspend fun onScheduledSmsRecovery(reason: ScheduledSmsRecoveryReason) {
        scheduledSmsController.recover(reason)
        sendDelayController.recover(reason.toSendDelayRecoveryReason())
        permanentDeletionController.recover(reason.toPermanentDeletionRecoveryReason())
        notificationReminderController.recover(reason.toNotificationReminderRecoveryReason())
    }

    /** Retries observer registration and reconciliation after explicit role/permission UI success. */
    fun onMessagingEligibilityChanged() {
        applicationScope.launch {
            previewLoader.clear()
            defaultSmsRoleLifecycleFence.reconcile { roleHeld ->
                reconcileMessagingRecoveryForRole(roleHeld)
            }
        }
        requestIndexOpen()
        if (!syntheticIndexOnly) {
            ensureProviderObserverRegistered()
            enqueueIndexSignal(IndexSignal.ROLE_CHANGED)
        }
    }

    fun onContactsPermissionChanged() {
        contactCacheController?.onContactsPermissionChanged()
    }

    fun onMessagingActivityStarted() {
        retryPendingInlineReplyOperations()
        if (foregroundIndexReadGate.onActivityStarted()) {
            indexCoordinator.resumeAfterForeground()
        }
    }

    fun onMessagingActivityStopped() {
        applicationScope.launch(Dispatchers.IO) {
            backupDocumentController.cancelRestore()
        }
        foregroundIndexReadGate.onActivityStopped()
    }

    private suspend fun reconcileMessagingRecoveryForRole(roleHeld: Boolean) {
        synchronized(messagingRecoveryLock) {
            messagingRecoveryRoleEnabled = roleHeld
        }
        if (!roleHeld && !syntheticIndexOnly) {
            threadSmsSendController.fence()
            scheduledSmsController.fence()
            sendDelayController.fence()
            permanentDeletionController.fence()
            notificationReminderController.fence()
            cancelAndJoinPendingMessagingRecovery()
            incomingMessageOrchestrator.onRoleLost()
        }
        if (roleHeld && !syntheticIndexOnly) {
            retryPendingInlineReplyOperations()
        }
    }

    private fun retryPendingInlineReplyOperations() {
        if (syntheticIndexOnly || !defaultSmsRoleState.isRoleHeld()) return
        synchronized(messagingRecoveryLock) {
            if (!messagingRecoveryRoleEnabled || !defaultSmsRoleState.isRoleHeld()) return
            messagingRecoveryRequested = true
            if (messagingRecoveryJob?.isActive == true) return
            val job = applicationScope.launch(
                context = Dispatchers.IO,
                start = CoroutineStart.LAZY,
            ) {
                drainMessagingRecoveryRequests()
            }
            messagingRecoveryJob = job
            job.start()
        }
    }

    private suspend fun drainMessagingRecoveryRequests() {
        val currentJob = currentCoroutineContext()[Job]
        try {
            do {
                synchronized(messagingRecoveryLock) {
                    messagingRecoveryRequested = false
                }
                runMessagingRecoveryPass()
            } while (synchronized(messagingRecoveryLock) { messagingRecoveryRequested })
        } finally {
            val restart = synchronized(messagingRecoveryLock) {
                if (messagingRecoveryJob === currentJob) {
                    messagingRecoveryJob = null
                    messagingRecoveryRequested &&
                        messagingRecoveryRoleEnabled &&
                        defaultSmsRoleState.isRoleHeld()
                } else {
                    false
                }
            }
            if (restart) {
                currentJob?.invokeOnCompletion {
                    retryPendingInlineReplyOperations()
                }
            }
        }
    }

    private suspend fun runMessagingRecoveryPass() {
        val initialRecovery = synchronized(messagingRecoveryLock) {
            pendingInitialReplyOperationRecovery.also {
                pendingInitialReplyOperationRecovery = null
            }
        }
        val replyRecovery = initialRecovery
            ?.takeIf { it is ReplyOperationRecoveryResult.Recovered }
            ?: replyOperations.recoverInheritedOperations()
        if (
            !replyColdCallbackGraceApplied &&
            replyRecoveryNeedsColdCallbackGrace(replyRecovery)
        ) {
            replyColdCallbackGraceApplied = true
            delay(INLINE_REPLY_COLD_CALLBACK_GRACE_MILLIS)
        }
        if (!defaultSmsRoleState.isRoleHeld()) return

        val transportOwnedRecovery =
            androidSmsTransport.recoverTransportOwnedSubmissions()
        val outgoingMmsRecovery = mmsTransport.recoverOutgoingSubmissions()
        val incomingMmsRecovery = try {
            mmsTransport.recoverIncomingDownloads()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            IncomingMmsRecoveryResult.JournalBlocked
        }
        var incomingMmsFollowUp = when (incomingMmsRecovery) {
            is IncomingMmsRecoveryResult.Available ->
                incomingMmsRecovery.deferredCount > 0 || incomingMmsRecovery.unknownSubmissionCount > 0
            IncomingMmsRecoveryResult.JournalBlocked -> true
        }
        if (incomingMmsRecovery is IncomingMmsRecoveryResult.Available) {
            for (delivery in incomingMmsRecovery.pendingNotifications) {
                if (!defaultSmsRoleState.isRoleHeld()) return
                val notified = try {
                    incomingMessageOrchestrator.notifyDownloadedMms(delivery)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: RuntimeException) {
                    null
                }
                if (notified !is IncomingPersistResult.Persisted) incomingMmsFollowUp = true
            }
        }
        val composerRecovery = threadSmsSendController.recover()
        if (scheduledStartupRecoveryPending) {
            scheduledSmsController.recover(ScheduledSmsRecoveryReason.APP_STARTUP)
            sendDelayController.recover(SendDelayRecoveryReason.APP_STARTUP)
            permanentDeletionController.recover(PermanentDeletionRecoveryReason.APP_STARTUP)
            scheduledStartupRecoveryPending = false
        } else {
            scheduledSmsController.reconcileDispatches()
            sendDelayController.reconcileDispatches()
            permanentDeletionController.recover(PermanentDeletionRecoveryReason.APP_STARTUP)
        }
        inlineReplyTransportResultHandler.reconcilePendingOperations()
        var retryDelayMillis = INCOMING_NOTIFICATION_RECOVERY_INITIAL_RETRY_MILLIS
        var followUpRequired = transportOwnedRecovery.followUpRequired ||
            outgoingMmsRecovery.followUpRequired ||
            incomingMmsFollowUp ||
            composerRecovery.requiresFollowUp
        for (attempt in 0 until INCOMING_NOTIFICATION_RECOVERY_MAXIMUM_ATTEMPTS) {
            if (!defaultSmsRoleState.isRoleHeld()) return
            val result = try {
                incomingMessageOrchestrator.recoverPendingNotifications()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                null
            }
            if (result is IncomingNotificationRecoveryResult.Complete) {
                break
            }
            if (attempt == INCOMING_NOTIFICATION_RECOVERY_MAXIMUM_ATTEMPTS - 1) {
                followUpRequired = true
                break
            }
            val madeProgress =
                (result as? IncomingNotificationRecoveryResult.Deferred)
                    ?.recoveredCount
                    ?.let { it > 0 } == true
            if (!madeProgress) {
                delay(retryDelayMillis)
                retryDelayMillis = (retryDelayMillis * 2L)
                    .coerceAtMost(INCOMING_NOTIFICATION_RECOVERY_MAXIMUM_RETRY_MILLIS)
            }
        }
        if (!defaultSmsRoleState.isRoleHeld()) return
        val providerStateChanged = inlineReplyProviderUpdateCoordinator.reconcilePending()
        if (providerStateChanged) {
            enqueueIndexSignal(IndexSignal.CONTENT_OBSERVER_CHANGE)
        }
        if (followUpRequired && defaultSmsRoleState.isRoleHeld()) {
            scheduleMessagingRecoveryFollowUp()
        }
    }

    private fun replyRecoveryNeedsColdCallbackGrace(
        recovery: ReplyOperationRecoveryResult,
    ): Boolean = recovery.unknownSubmissionMayStillCallback() ||
        when (val pending = replyOperations.pendingFailures()) {
            is ReplyOperationPendingFailuresResult.Available ->
                pending.operations.any { operation ->
                    operation.failureKind == ReplyOperationFailureKind.SUBMISSION_UNKNOWN
                }
            ReplyOperationPendingFailuresResult.PersistenceFailure -> false
        }

    private fun scheduleMessagingRecoveryFollowUp() {
        if (syntheticIndexOnly || !defaultSmsRoleState.isRoleHeld()) return
        synchronized(messagingRecoveryLock) {
            if (!messagingRecoveryRoleEnabled || !defaultSmsRoleState.isRoleHeld()) return
            if (messagingRecoveryFollowUpJob?.isActive == true) return
            val job = applicationScope.launch(
                context = Dispatchers.IO,
                start = CoroutineStart.LAZY,
            ) {
                runMessagingRecoveryFollowUp()
            }
            messagingRecoveryFollowUpJob = job
            job.start()
        }
    }

    private suspend fun runMessagingRecoveryFollowUp() {
        val currentJob = currentCoroutineContext()[Job]
        try {
            delay(INCOMING_NOTIFICATION_RECOVERY_FOLLOW_UP_DELAY_MILLIS)
            val shouldRequest = synchronized(messagingRecoveryLock) {
                if (messagingRecoveryFollowUpJob === currentJob) {
                    messagingRecoveryFollowUpJob = null
                    messagingRecoveryRoleEnabled
                } else {
                    false
                }
            }
            if (shouldRequest && defaultSmsRoleState.isRoleHeld()) {
                retryPendingInlineReplyOperations()
            }
        } finally {
            synchronized(messagingRecoveryLock) {
                if (messagingRecoveryFollowUpJob === currentJob) {
                    messagingRecoveryFollowUpJob = null
                }
            }
        }
    }

    private suspend fun cancelAndJoinPendingMessagingRecovery() {
        val jobs = synchronized(messagingRecoveryLock) {
            messagingRecoveryRequested = false
            pendingInitialReplyOperationRecovery = null
            listOfNotNull(messagingRecoveryJob, messagingRecoveryFollowUpJob).distinct()
        }
        jobs.forEach { it.cancel() }
        jobs.forEach { it.join() }
        synchronized(messagingRecoveryLock) {
            if (messagingRecoveryJob in jobs) messagingRecoveryJob = null
            if (messagingRecoveryFollowUpJob in jobs) messagingRecoveryFollowUpJob = null
            messagingRecoveryRequested = false
        }
    }

    private fun cancelPendingMessagingRecoveryForClose() {
        val jobs = synchronized(messagingRecoveryLock) {
            messagingRecoveryRoleEnabled = false
            messagingRecoveryRequested = false
            pendingInitialReplyOperationRecovery = null
            listOfNotNull(messagingRecoveryJob, messagingRecoveryFollowUpJob)
                .distinct()
                .also {
                    messagingRecoveryJob = null
                    messagingRecoveryFollowUpJob = null
                }
        }
        jobs.forEach { it.cancel() }
    }

    fun createDraftWriter(
        identity: DraftIdentity,
        restorationToken: DraftRestorationToken?,
    ): SerializedDraftWriter = SerializedDraftWriter(
        repository = draftRepository,
        identity = identity,
        scope = applicationScope,
        restorationToken = restorationToken,
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
        cancelPendingMessagingRecoveryForClose()
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
                                isProviderReadPermitted =
                                    foregroundIndexReadGate::isProviderReadPermitted,
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
        const val INLINE_REPLY_COLD_CALLBACK_GRACE_MILLIS = 1_000L
        const val INCOMING_NOTIFICATION_RECOVERY_INITIAL_RETRY_MILLIS = 1_000L
        const val INCOMING_NOTIFICATION_RECOVERY_MAXIMUM_RETRY_MILLIS = 8_000L
        const val INCOMING_NOTIFICATION_RECOVERY_MAXIMUM_ATTEMPTS = 6
        const val INCOMING_NOTIFICATION_RECOVERY_FOLLOW_UP_DELAY_MILLIS = 30_000L
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

private object EmptyBenchmarkDraftAttachmentRepository : DraftAttachmentRepository {
    override suspend fun read(
        draftId: DraftId,
    ): DraftRepositoryResult<List<DraftAttachment>> = DraftRepositoryResult.NotFound

    override suspend fun replace(
        draftId: DraftId,
        expectedRevision: DraftRevision,
        attachments: List<DraftAttachment>,
    ): DraftRepositoryResult<List<DraftAttachment>> =
        DraftRepositoryResult.StorageFailure(DraftStorageOperation.UPDATE)
}

private object EmptyBenchmarkAppearanceProfileRepository : AppearanceProfileRepository {
    override val snapshots: Flow<AppearanceSnapshot> = flowOf(AppearanceSnapshot.Empty)

    override suspend fun create(
        profile: NewAppearanceProfile,
        activate: Boolean,
    ): AppearanceRepositoryResult<AppearanceProfile> =
        AppearanceRepositoryResult.StorageFailure(AppearanceStorageOperation.CREATE)

    override suspend fun update(
        edit: AppearanceProfileEdit,
        expectedRevision: AppearanceRevision,
        activate: Boolean,
    ): AppearanceRepositoryResult<AppearanceProfile> =
        AppearanceRepositoryResult.StorageFailure(AppearanceStorageOperation.UPDATE)

    override suspend fun activate(id: AppearanceProfileId): AppearanceRepositoryResult<Unit> =
        AppearanceRepositoryResult.StorageFailure(AppearanceStorageOperation.ACTIVATE)

    override suspend fun resetActive(): AppearanceRepositoryResult<Unit> =
        AppearanceRepositoryResult.StorageFailure(AppearanceStorageOperation.RESET_ACTIVE)

    override suspend fun delete(
        id: AppearanceProfileId,
        expectedRevision: AppearanceRevision,
    ): AppearanceRepositoryResult<Unit> =
        AppearanceRepositoryResult.StorageFailure(AppearanceStorageOperation.DELETE)

    override fun observeOverride(scope: AppearanceScope): Flow<AppearanceOverride?> = flowOf(null)

    override suspend fun setOverride(
        scope: AppearanceScope,
        profileId: AppearanceProfileId,
        expectedRevision: AppearanceOverrideRevision?,
    ): AppearanceRepositoryResult<AppearanceOverride> =
        AppearanceRepositoryResult.StorageFailure(AppearanceStorageOperation.SET_OVERRIDE)

    override suspend fun resetOverride(
        scope: AppearanceScope,
        expectedRevision: AppearanceOverrideRevision?,
    ): AppearanceRepositoryResult<Unit> =
        AppearanceRepositoryResult.StorageFailure(AppearanceStorageOperation.RESET_OVERRIDE)
}

private object EmptyBenchmarkAppearanceWallpaperRepository : AppearanceWallpaperRepository {
    override fun observeWallpaper(scope: AppearanceScope): Flow<AppearanceWallpaperAssignment?> =
        flowOf(null)

    override suspend fun setWallpaper(
        scope: AppearanceScope,
        mediaId: AppearanceWallpaperMediaId,
        dimPermill: Int,
        focalXPermill: Int,
        focalYPermill: Int,
        expectedRevision: AppearanceWallpaperRevision?,
    ): AppearanceRepositoryResult<AppearanceWallpaperMutation> =
        if (scope.isUnsupportedWallpaperScreen()) {
            AppearanceRepositoryResult.CorruptData
        } else {
            AppearanceRepositoryResult.StorageFailure(AppearanceStorageOperation.SET_WALLPAPER)
        }

    override suspend fun resetWallpaper(
        scope: AppearanceScope,
        expectedRevision: AppearanceWallpaperRevision?,
    ): AppearanceRepositoryResult<AppearanceWallpaperMutation> =
        if (scope.isUnsupportedWallpaperScreen()) {
            AppearanceRepositoryResult.CorruptData
        } else {
            AppearanceRepositoryResult.StorageFailure(AppearanceStorageOperation.RESET_WALLPAPER)
        }

    override suspend fun prospectiveMediaIdsForSet(
        scope: AppearanceScope,
        mediaId: AppearanceWallpaperMediaId,
        expectedRevision: AppearanceWallpaperRevision?,
    ): AppearanceRepositoryResult<Set<AppearanceWallpaperMediaId>> =
        if (scope.isUnsupportedWallpaperScreen()) {
            AppearanceRepositoryResult.CorruptData
        } else {
            AppearanceRepositoryResult.StorageFailure(
                AppearanceStorageOperation.WALLPAPER_MEDIA_REFERENCES,
            )
        }

    override suspend fun referencedMediaIds(): AppearanceRepositoryResult<Set<AppearanceWallpaperMediaId>> =
        AppearanceRepositoryResult.StorageFailure(AppearanceStorageOperation.WALLPAPER_MEDIA_REFERENCES)
}

private object EmptyBenchmarkConversationSubscriptionPreferenceRepository :
    ConversationSubscriptionPreferenceRepository {
    override suspend fun read(
        scope: ConversationSubscriptionScope,
    ): ConversationSubscriptionRepositoryResult<ConversationSubscriptionPreference> =
        ConversationSubscriptionRepositoryResult.NotFound

    override suspend fun set(
        scope: ConversationSubscriptionScope,
        subscriptionId: org.aurorasms.core.model.AuroraSubscriptionId,
        expectedRevision: ConversationSubscriptionRevision?,
        updatedTimestampMillis: Long,
    ): ConversationSubscriptionRepositoryResult<ConversationSubscriptionPreference> =
        ConversationSubscriptionRepositoryResult.StorageFailure(
            ConversationSubscriptionStorageOperation.SET,
        )
}

private object EmptyBenchmarkSpamSafetyRepository : SpamSafetyRepository {
    override val snapshots: Flow<SpamSafetySnapshot> = flowOf(SpamSafetySnapshot.Empty)

    override suspend fun read(
        scope: SpamSafetyScope,
    ): SpamSafetyRepositoryResult<SpamSafetyDecision> = SpamSafetyRepositoryResult.NotFound

    override suspend fun set(
        scope: SpamSafetyScope,
        classification: SpamClassification,
        blocked: Boolean,
        expectedRevision: SpamSafetyRevision?,
        updatedTimestampMillis: Long,
    ): SpamSafetyRepositoryResult<SpamSafetyDecision?> =
        SpamSafetyRepositoryResult.StorageFailure(SpamSafetyStorageOperation.WRITE)

    override suspend fun isSenderBlocked(
        sender: ParticipantAddress,
    ): SpamSafetyRepositoryResult<Boolean> = SpamSafetyRepositoryResult.Success(false)
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
    data class Ready(
        val draftRepository: DraftRepository,
        val draftAttachmentRepository: DraftAttachmentRepository,
        val appearanceProfileRepository: AppearanceProfileRepository,
        val appearanceWallpaperRepository: AppearanceWallpaperRepository,
        val conversationSubscriptionPreferenceRepository:
            ConversationSubscriptionPreferenceRepository,
        val spamSafetyRepository: SpamSafetyRepository,
    ) : StateRuntimeState
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

private class DeferredDraftAttachmentRepository(
    private val runtimeState: StateFlow<StateRuntimeState>,
) : DraftAttachmentRepository {
    override suspend fun read(
        draftId: DraftId,
    ): DraftRepositoryResult<List<DraftAttachment>> =
        runtimeState.awaitReadyDraftAttachmentRepository().read(draftId)

    override suspend fun replace(
        draftId: DraftId,
        expectedRevision: DraftRevision,
        attachments: List<DraftAttachment>,
    ): DraftRepositoryResult<List<DraftAttachment>> =
        runtimeState.awaitReadyDraftAttachmentRepository()
            .replace(draftId, expectedRevision, attachments)

    override fun toString(): String = "DeferredDraftAttachmentRepository(content=REDACTED)"
}

private class DeferredConversationSubscriptionPreferenceRepository(
    private val runtimeState: StateFlow<StateRuntimeState>,
) : ConversationSubscriptionPreferenceRepository {
    override suspend fun read(
        scope: ConversationSubscriptionScope,
    ): ConversationSubscriptionRepositoryResult<ConversationSubscriptionPreference> =
        runtimeState.awaitReadyConversationSubscriptionPreferenceRepository()
            ?.read(scope)
            ?: ConversationSubscriptionRepositoryResult.StorageFailure(
                ConversationSubscriptionStorageOperation.READ,
            )

    override suspend fun set(
        scope: ConversationSubscriptionScope,
        subscriptionId: org.aurorasms.core.model.AuroraSubscriptionId,
        expectedRevision: ConversationSubscriptionRevision?,
        updatedTimestampMillis: Long,
    ): ConversationSubscriptionRepositoryResult<ConversationSubscriptionPreference> =
        runtimeState.awaitReadyConversationSubscriptionPreferenceRepository()
            ?.set(scope, subscriptionId, expectedRevision, updatedTimestampMillis)
            ?: ConversationSubscriptionRepositoryResult.StorageFailure(
                ConversationSubscriptionStorageOperation.SET,
            )

    override fun toString(): String =
        "DeferredConversationSubscriptionPreferenceRepository(content=REDACTED)"
}

@OptIn(ExperimentalCoroutinesApi::class)
private class DeferredSpamSafetyRepository(
    private val runtimeState: StateFlow<StateRuntimeState>,
) : SpamSafetyRepository {
    override val snapshots: Flow<SpamSafetySnapshot> = runtimeState
        .flatMapLatest { state ->
            when (state) {
                is StateRuntimeState.Ready -> state.spamSafetyRepository.snapshots
                StateRuntimeState.Opening,
                is StateRuntimeState.Failed,
                -> flowOf(SpamSafetySnapshot.Unavailable)
            }
        }
        .conflate()

    override suspend fun read(
        scope: SpamSafetyScope,
    ): SpamSafetyRepositoryResult<SpamSafetyDecision> = runtimeState
        .awaitReadySpamSafetyRepository()
        ?.read(scope)
        ?: SpamSafetyRepositoryResult.StorageFailure(SpamSafetyStorageOperation.READ)

    override suspend fun set(
        scope: SpamSafetyScope,
        classification: SpamClassification,
        blocked: Boolean,
        expectedRevision: SpamSafetyRevision?,
        updatedTimestampMillis: Long,
    ): SpamSafetyRepositoryResult<SpamSafetyDecision?> = runtimeState
        .awaitReadySpamSafetyRepository()
        ?.set(scope, classification, blocked, expectedRevision, updatedTimestampMillis)
        ?: SpamSafetyRepositoryResult.StorageFailure(SpamSafetyStorageOperation.WRITE)

    override suspend fun isSenderBlocked(
        sender: ParticipantAddress,
    ): SpamSafetyRepositoryResult<Boolean> = runtimeState.awaitReadySpamSafetyRepository()
        ?.isSenderBlocked(sender)
        ?: SpamSafetyRepositoryResult.StorageFailure(SpamSafetyStorageOperation.BLOCK_LOOKUP)

    override fun toString(): String = "DeferredSpamSafetyRepository(content=REDACTED)"
}

@OptIn(ExperimentalCoroutinesApi::class)
private class DeferredAppearanceProfileRepository(
    private val runtimeState: StateFlow<StateRuntimeState>,
) : AppearanceProfileRepository {
    override val snapshots: Flow<AppearanceSnapshot> = runtimeState
        .flatMapLatest { state ->
            when (state) {
                is StateRuntimeState.Ready -> state.appearanceProfileRepository.snapshots
                StateRuntimeState.Opening,
                is StateRuntimeState.Failed,
                -> flowOf(AppearanceSnapshot.Empty)
            }
        }
        .conflate()

    override suspend fun create(
        profile: NewAppearanceProfile,
        activate: Boolean,
    ): AppearanceRepositoryResult<AppearanceProfile> =
        runtimeState.awaitReadyAppearanceProfileRepository()
            ?.create(profile, activate)
            ?: AppearanceRepositoryResult.StorageFailure(AppearanceStorageOperation.CREATE)

    override suspend fun update(
        edit: AppearanceProfileEdit,
        expectedRevision: AppearanceRevision,
        activate: Boolean,
    ): AppearanceRepositoryResult<AppearanceProfile> =
        runtimeState.awaitReadyAppearanceProfileRepository()
            ?.update(edit, expectedRevision, activate)
            ?: AppearanceRepositoryResult.StorageFailure(AppearanceStorageOperation.UPDATE)

    override suspend fun activate(id: AppearanceProfileId): AppearanceRepositoryResult<Unit> =
        runtimeState.awaitReadyAppearanceProfileRepository()
            ?.activate(id)
            ?: AppearanceRepositoryResult.StorageFailure(AppearanceStorageOperation.ACTIVATE)

    override suspend fun resetActive(): AppearanceRepositoryResult<Unit> =
        runtimeState.awaitReadyAppearanceProfileRepository()
            ?.resetActive()
            ?: AppearanceRepositoryResult.StorageFailure(AppearanceStorageOperation.RESET_ACTIVE)

    override suspend fun delete(
        id: AppearanceProfileId,
        expectedRevision: AppearanceRevision,
    ): AppearanceRepositoryResult<Unit> =
        runtimeState.awaitReadyAppearanceProfileRepository()
            ?.delete(id, expectedRevision)
            ?: AppearanceRepositoryResult.StorageFailure(AppearanceStorageOperation.DELETE)

    override fun observeOverride(scope: AppearanceScope): Flow<AppearanceOverride?> = runtimeState
        .flatMapLatest { state ->
            when (state) {
                is StateRuntimeState.Ready -> state.appearanceProfileRepository.observeOverride(scope)
                StateRuntimeState.Opening,
                is StateRuntimeState.Failed,
                -> emptyFlow()
            }
        }
        .conflate()

    override suspend fun setOverride(
        scope: AppearanceScope,
        profileId: AppearanceProfileId,
        expectedRevision: AppearanceOverrideRevision?,
    ): AppearanceRepositoryResult<AppearanceOverride> =
        runtimeState.awaitReadyAppearanceProfileRepository()
            ?.setOverride(scope, profileId, expectedRevision)
            ?: AppearanceRepositoryResult.StorageFailure(AppearanceStorageOperation.SET_OVERRIDE)

    override suspend fun resetOverride(
        scope: AppearanceScope,
        expectedRevision: AppearanceOverrideRevision?,
    ): AppearanceRepositoryResult<Unit> =
        runtimeState.awaitReadyAppearanceProfileRepository()
            ?.resetOverride(scope, expectedRevision)
            ?: AppearanceRepositoryResult.StorageFailure(AppearanceStorageOperation.RESET_OVERRIDE)

    override fun toString(): String = "DeferredAppearanceProfileRepository(content=REDACTED)"
}

@OptIn(ExperimentalCoroutinesApi::class)
private class DeferredAppearanceWallpaperRepository(
    private val runtimeState: StateFlow<StateRuntimeState>,
) : AppearanceWallpaperRepository {
    override fun observeWallpaper(scope: AppearanceScope): Flow<AppearanceWallpaperAssignment?> =
        if (scope.isUnsupportedWallpaperScreen()) {
            flowOf(null)
        } else {
            runtimeState.flatMapLatest { state ->
                when (state) {
                    is StateRuntimeState.Ready ->
                        state.appearanceWallpaperRepository.observeWallpaper(scope)
                    StateRuntimeState.Opening,
                    is StateRuntimeState.Failed,
                    -> emptyFlow()
                }
            }
                .conflate()
        }

    override suspend fun setWallpaper(
        scope: AppearanceScope,
        mediaId: AppearanceWallpaperMediaId,
        dimPermill: Int,
        focalXPermill: Int,
        focalYPermill: Int,
        expectedRevision: AppearanceWallpaperRevision?,
    ): AppearanceRepositoryResult<AppearanceWallpaperMutation> =
        if (scope.isUnsupportedWallpaperScreen()) {
            AppearanceRepositoryResult.CorruptData
        } else {
            runtimeState.awaitReadyAppearanceWallpaperRepository()
            ?.setWallpaper(
                scope = scope,
                mediaId = mediaId,
                dimPermill = dimPermill,
                focalXPermill = focalXPermill,
                focalYPermill = focalYPermill,
                expectedRevision = expectedRevision,
            )
            ?: AppearanceRepositoryResult.StorageFailure(AppearanceStorageOperation.SET_WALLPAPER)
        }

    override suspend fun resetWallpaper(
        scope: AppearanceScope,
        expectedRevision: AppearanceWallpaperRevision?,
    ): AppearanceRepositoryResult<AppearanceWallpaperMutation> =
        if (scope.isUnsupportedWallpaperScreen()) {
            AppearanceRepositoryResult.CorruptData
        } else {
            runtimeState.awaitReadyAppearanceWallpaperRepository()
            ?.resetWallpaper(scope, expectedRevision)
            ?: AppearanceRepositoryResult.StorageFailure(AppearanceStorageOperation.RESET_WALLPAPER)
        }

    override suspend fun referencedMediaIds(): AppearanceRepositoryResult<Set<AppearanceWallpaperMediaId>> =
        runtimeState.awaitReadyAppearanceWallpaperRepository()
            ?.referencedMediaIds()
            ?: AppearanceRepositoryResult.StorageFailure(
                AppearanceStorageOperation.WALLPAPER_MEDIA_REFERENCES,
            )

    override suspend fun prospectiveMediaIdsForSet(
        scope: AppearanceScope,
        mediaId: AppearanceWallpaperMediaId,
        expectedRevision: AppearanceWallpaperRevision?,
    ): AppearanceRepositoryResult<Set<AppearanceWallpaperMediaId>> =
        if (scope.isUnsupportedWallpaperScreen()) {
            AppearanceRepositoryResult.CorruptData
        } else {
            runtimeState.awaitReadyAppearanceWallpaperRepository()
                ?.prospectiveMediaIdsForSet(scope, mediaId, expectedRevision)
                ?: AppearanceRepositoryResult.StorageFailure(
                    AppearanceStorageOperation.WALLPAPER_MEDIA_REFERENCES,
                )
        }

    override fun toString(): String = "DeferredAppearanceWallpaperRepository(content=REDACTED)"
}

private class StateStorageUnavailableException : IllegalStateException("State storage unavailable")

private fun IndexSignal.requiresDurableAmbiguousLedger(): Boolean = when (this) {
    IndexSignal.FOREGROUND_RESUME,
    IndexSignal.STARTUP,
    IndexSignal.ROLE_CHANGED,
    IndexSignal.INCOMING_INSERT,
    IndexSignal.PERIODIC_RECONCILIATION,
    -> false
    IndexSignal.CONTENT_OBSERVER_CHANGE,
    IndexSignal.EXTERNAL_PROVIDER_CHANGE,
    -> true
}

/**
 * A foreground resume can complete provider work that originally started from
 * a durable ambiguous signal and was deferred at the background read gate. It
 * may therefore clear an unchanged ledger after a complete reconciliation,
 * while still never creating that ledger or marking provider state dirty.
 */
internal fun reconciliationCoversAmbiguousSignalLedger(reasons: Set<IndexSignal>): Boolean =
    reasons.any { signal ->
        signal == IndexSignal.FOREGROUND_RESUME || signal.requiresDurableAmbiguousLedger()
    }

private fun ReplyOperationRecoveryResult.unknownSubmissionMayStillCallback(): Boolean =
    this is ReplyOperationRecoveryResult.Recovered && submissionUnknownCount > 0

private fun ScheduledSmsRecoveryReason.toSendDelayRecoveryReason(): SendDelayRecoveryReason =
    when (this) {
        ScheduledSmsRecoveryReason.APP_STARTUP -> SendDelayRecoveryReason.APP_STARTUP
        ScheduledSmsRecoveryReason.BOOT_COMPLETED -> SendDelayRecoveryReason.BOOT_COMPLETED
        ScheduledSmsRecoveryReason.WALL_CLOCK_CHANGED -> SendDelayRecoveryReason.TIME_CHANGED
        ScheduledSmsRecoveryReason.TIMEZONE_CHANGED,
        ScheduledSmsRecoveryReason.PACKAGE_REPLACED,
        ScheduledSmsRecoveryReason.EXACT_ACCESS_CHANGED,
        -> SendDelayRecoveryReason.PACKAGE_REPLACED
    }

private fun ScheduledSmsRecoveryReason.toPermanentDeletionRecoveryReason():
    PermanentDeletionRecoveryReason = when (this) {
    ScheduledSmsRecoveryReason.APP_STARTUP -> PermanentDeletionRecoveryReason.APP_STARTUP
    ScheduledSmsRecoveryReason.BOOT_COMPLETED -> PermanentDeletionRecoveryReason.BOOT_COMPLETED
    ScheduledSmsRecoveryReason.WALL_CLOCK_CHANGED -> PermanentDeletionRecoveryReason.TIME_CHANGED
    ScheduledSmsRecoveryReason.TIMEZONE_CHANGED,
    ScheduledSmsRecoveryReason.PACKAGE_REPLACED,
    ScheduledSmsRecoveryReason.EXACT_ACCESS_CHANGED,
    -> PermanentDeletionRecoveryReason.PACKAGE_REPLACED
}

private fun ScheduledSmsRecoveryReason.toNotificationReminderRecoveryReason():
    NotificationReminderRecoveryReason = when (this) {
    ScheduledSmsRecoveryReason.APP_STARTUP -> NotificationReminderRecoveryReason.APP_STARTUP
    ScheduledSmsRecoveryReason.BOOT_COMPLETED ->
        NotificationReminderRecoveryReason.BOOT_COMPLETED
    ScheduledSmsRecoveryReason.WALL_CLOCK_CHANGED ->
        NotificationReminderRecoveryReason.WALL_CLOCK_CHANGED
    ScheduledSmsRecoveryReason.TIMEZONE_CHANGED ->
        NotificationReminderRecoveryReason.TIMEZONE_CHANGED
    ScheduledSmsRecoveryReason.PACKAGE_REPLACED ->
        NotificationReminderRecoveryReason.PACKAGE_REPLACED
    ScheduledSmsRecoveryReason.EXACT_ACCESS_CHANGED ->
        NotificationReminderRecoveryReason.EXACT_ACCESS_CHANGED
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

private suspend fun StateFlow<StateRuntimeState>.awaitReadyDraftAttachmentRepository():
    DraftAttachmentRepository {
    val state = when (val current = value) {
        StateRuntimeState.Opening -> first { it !is StateRuntimeState.Opening }
        else -> current
    }
    return when (state) {
        is StateRuntimeState.Ready -> state.draftAttachmentRepository
        is StateRuntimeState.Failed -> throw StateStorageUnavailableException()
        StateRuntimeState.Opening -> error("State runtime did not leave opening state")
    }
}

private suspend fun StateFlow<StateRuntimeState>.awaitReadyAppearanceProfileRepository():
    AppearanceProfileRepository? {
    val state = when (val current = value) {
        StateRuntimeState.Opening -> first { it !is StateRuntimeState.Opening }
        else -> current
    }
    return (state as? StateRuntimeState.Ready)?.appearanceProfileRepository
}

private suspend fun StateFlow<StateRuntimeState>.awaitReadyAppearanceWallpaperRepository():
    AppearanceWallpaperRepository? {
    val state = when (val current = value) {
        StateRuntimeState.Opening -> first { it !is StateRuntimeState.Opening }
        else -> current
    }
    return (state as? StateRuntimeState.Ready)?.appearanceWallpaperRepository
}

private suspend fun StateFlow<StateRuntimeState>.awaitReadyConversationSubscriptionPreferenceRepository():
    ConversationSubscriptionPreferenceRepository? {
    val state = when (val current = value) {
        StateRuntimeState.Opening -> first { it !is StateRuntimeState.Opening }
        else -> current
    }
    return (state as? StateRuntimeState.Ready)?.conversationSubscriptionPreferenceRepository
}

private suspend fun StateFlow<StateRuntimeState>.awaitReadySpamSafetyRepository():
    SpamSafetyRepository? {
    val state = when (val current = value) {
        StateRuntimeState.Opening -> first { it !is StateRuntimeState.Opening }
        else -> current
    }
    return (state as? StateRuntimeState.Ready)?.spamSafetyRepository
}

private fun AppearanceScope.isUnsupportedWallpaperScreen(): Boolean =
    this is AppearanceScope.Screen && screen != AppearanceScreenScope.GLOBAL_THREAD

internal fun TransportResult.Failed.smsProviderFailureStatus(): SmsProviderStatus? {
    if (transport != MessageTransportKind.SMS) return null
    return when (stage) {
        TransportResult.FailureStage.SUBMISSION,
        TransportResult.FailureStage.SENT_CALLBACK -> SmsProviderStatus.FAILED
        TransportResult.FailureStage.DELIVERY_CALLBACK -> SmsProviderStatus.DELIVERY_FAILED
        TransportResult.FailureStage.SUBMISSION_UNKNOWN,
        TransportResult.FailureStage.DOWNLOAD_CALLBACK -> null
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
