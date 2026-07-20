// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aurorasms.app.appearance.AppAppearanceOverrideObservation
import org.aurorasms.app.appearance.AppAppearanceState
import org.aurorasms.app.appearance.AppearanceController
import org.aurorasms.app.appearance.ScopedAppearanceDialog
import org.aurorasms.app.appearance.ScopedAppearanceKind
import org.aurorasms.app.appearance.ThemeStudioRoute
import org.aurorasms.app.appearance.profileFor
import org.aurorasms.app.appearance.profileNameFor
import org.aurorasms.app.appearance.wallpaper.AppWallpaperObservation
import org.aurorasms.app.appearance.wallpaper.ManagedWallpaperSurface
import org.aurorasms.app.appearance.wallpaper.ScopedWallpaperDialog
import org.aurorasms.app.appearance.wallpaper.WallpaperApplyControllerResult
import org.aurorasms.app.appearance.wallpaper.WallpaperControllerError
import org.aurorasms.app.appearance.wallpaper.WallpaperRenderRequestEpoch
import org.aurorasms.app.appearance.wallpaper.resolveWallpaperCandidates
import org.aurorasms.app.drafts.DraftEditorContent
import org.aurorasms.app.drafts.DraftRestorationToken
import org.aurorasms.app.drafts.DraftUnfreezeReason
import org.aurorasms.app.drafts.DraftWriteStatus
import org.aurorasms.app.drafts.FrozenDraftSnapshot
import org.aurorasms.app.message.ThreadSmsSendAttempt
import org.aurorasms.app.message.ThreadSmsSendCommand
import org.aurorasms.app.message.ThreadSmsSendObservation
import org.aurorasms.app.message.ThreadSmsSendPhase
import org.aurorasms.app.message.ScheduledSmsAttempt
import org.aurorasms.app.message.ScheduledSmsCommand
import org.aurorasms.app.message.ScheduledSmsObservation
import org.aurorasms.app.message.SendDelayAttempt
import org.aurorasms.app.message.SendDelayCommand
import org.aurorasms.app.message.SendDelayObservation
import org.aurorasms.app.message.ConversationMessageSignatureDialog
import org.aurorasms.app.message.ConversationSignatureOverride
import org.aurorasms.app.message.GlobalMessageSignatureDialog
import org.aurorasms.app.message.MessageSignatureConversationKey
import org.aurorasms.app.message.PermanentDeletionCommand
import org.aurorasms.app.message.PermanentDeletionObservation
import org.aurorasms.app.message.PermanentDeletionRecoveryReason
import org.aurorasms.app.message.PermanentDeletionTargetKind
import org.aurorasms.core.index.SearchAnchor
import org.aurorasms.core.index.SearchHit
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.model.asConversationId
import org.aurorasms.core.model.asProviderThreadId
import org.aurorasms.core.designsystem.AuroraMaterialProfile
import org.aurorasms.core.designsystem.AuroraMaterialTheme
import org.aurorasms.core.state.DraftIdentity
import org.aurorasms.core.state.DraftId
import org.aurorasms.core.state.DraftRevision
import org.aurorasms.core.state.AppearanceParticipantSetKey
import org.aurorasms.core.state.AppearanceScope
import org.aurorasms.core.state.AppearanceScreenScope
import org.aurorasms.core.state.ConversationSubscriptionParticipantSetKey
import org.aurorasms.core.state.ConversationSubscriptionPreference
import org.aurorasms.core.state.ConversationSubscriptionRepositoryResult
import org.aurorasms.core.state.ConversationSubscriptionScope
import org.aurorasms.core.state.ScheduledSmsPrecision
import org.aurorasms.core.state.resolveOutgoingBody
import org.aurorasms.feature.conversations.ComposerScheduleState
import org.aurorasms.feature.conversations.ComposerUiState
import org.aurorasms.feature.conversations.ComposerSendState
import org.aurorasms.feature.conversations.ComposerUnavailableReason
import org.aurorasms.feature.conversations.InboxScreen
import org.aurorasms.feature.conversations.InboxStateHolder
import org.aurorasms.feature.conversations.InboxUiState
import org.aurorasms.feature.conversations.SearchScreen
import org.aurorasms.feature.conversations.SearchStateHolder
import org.aurorasms.feature.conversations.SearchUiState
import org.aurorasms.feature.conversations.ThreadScreen
import org.aurorasms.feature.conversations.ThreadStateHolder
import org.aurorasms.feature.conversations.ThreadUiState
import org.aurorasms.feature.conversations.ConversationSubscriptionUiState
import org.aurorasms.feature.conversations.PermanentDeletionTargetUiKind
import org.aurorasms.feature.conversations.PermanentDeletionUiState

@Composable
internal fun AuroraSmsRoot(
    container: AppContainer,
    appearance: AppAppearanceState,
    appearanceController: AppearanceController,
    pendingConversationId: ConversationId?,
    diagnosticsAvailable: Boolean,
    contactsPermissionGranted: Boolean,
    onOpenDiagnostics: () -> Unit,
    onRequestContactsPermission: () -> Unit,
    onPendingConversationConsumed: (ConversationId) -> Unit,
    onOpenedConversationChanged: (ConversationId?) -> Unit,
    onInboxReady: () -> Unit,
) {
    val services = remember(container) { AppContainerAuroraSmsRootServices(container) }
    AuroraSmsRoot(
        services = services,
        appearance = appearance,
        appearanceController = appearanceController,
        pendingConversationId = pendingConversationId,
        diagnosticsAvailable = diagnosticsAvailable,
        contactsPermissionGranted = contactsPermissionGranted,
        onOpenDiagnostics = onOpenDiagnostics,
        onRequestContactsPermission = onRequestContactsPermission,
        onPendingConversationConsumed = onPendingConversationConsumed,
        onOpenedConversationChanged = onOpenedConversationChanged,
        onInboxReady = onInboxReady,
    )
}

@Composable
internal fun AuroraSmsRoot(
    services: AuroraSmsRootServices,
    appearance: AppAppearanceState,
    appearanceController: AppearanceController,
    pendingConversationId: ConversationId?,
    diagnosticsAvailable: Boolean,
    contactsPermissionGranted: Boolean,
    onOpenDiagnostics: () -> Unit,
    onRequestContactsPermission: () -> Unit,
    onPendingConversationConsumed: (ConversationId) -> Unit,
    onOpenedConversationChanged: (ConversationId?) -> Unit,
    onInboxReady: () -> Unit,
) {
    val initialStack = remember {
        buildList {
            add(AppRoute.Inbox)
            pendingConversationId?.let { id ->
                add(
                    AppRoute.Thread(
                        providerThreadId = id.asProviderThreadId(),
                        stateEntryId = 1L,
                    ),
                )
            }
        }
    }
    var routes by rememberSaveable(stateSaver = APP_ROUTE_STACK_SAVER) {
        mutableStateOf(initialStack)
    }
    var inboxDrawReported by rememberSaveable { mutableStateOf(false) }
    var previewProfile by remember { mutableStateOf<AuroraMaterialProfile?>(null) }
    var scopedEditorDismissalGeneration by rememberSaveable { mutableStateOf(0L) }
    val saveableScreens = rememberSaveableStateHolder()
    val route = routes.last()
    val context = LocalContext.current

    fun newThreadRoute(
        providerThreadId: ProviderThreadId,
        anchor: SearchAnchor? = null,
    ): AppRoute.Thread = AppRoute.Thread(
        providerThreadId = providerThreadId,
        anchor = anchor,
        stateEntryId = nextThreadStateEntryId(routes),
    )

    fun setRoutes(updated: List<AppRoute>) {
        val retainedStateKeys = updated.mapTo(HashSet()) { it.saveableScreenKey() }
        routes.asSequence()
            .map { it.saveableScreenKey() }
            .distinct()
            .filterNot(retainedStateKeys::contains)
            .forEach(saveableScreens::removeState)
        routes = updated
    }

    fun push(next: AppRoute) {
        setRoutes(
            listOf(AppRoute.Inbox) +
                (routes.drop(1) + next).takeLast(MAXIMUM_RETAINED_ROUTES - 1),
        )
    }

    fun pop() {
        if (routes.size > 1) setRoutes(routes.dropLast(1))
    }

    fun replaceCurrent(next: AppRoute) {
        setRoutes(routes.dropLast(1) + next)
    }

    LaunchedEffect(pendingConversationId) {
        val pending = pendingConversationId ?: return@LaunchedEffect
        val currentThread = route as? AppRoute.Thread
        if (currentThread?.providerThreadId != pending.asProviderThreadId()) {
            scopedEditorDismissalGeneration += 1L
        }
        if (route == AppRoute.Appearance) {
            previewProfile = null
            saveableScreens.removeState(AppRoute.Appearance.saveableScreenKey())
        }
        if (
            currentThread?.providerThreadId != pending.asProviderThreadId() ||
            currentThread.anchor != null ||
            routes.size != 2
        ) {
            setRoutes(listOf(AppRoute.Inbox, newThreadRoute(pending.asProviderThreadId())))
        }
        onPendingConversationConsumed(pending)
    }
    LaunchedEffect(route) {
        onOpenedConversationChanged(
            (route as? AppRoute.Thread)?.providerThreadId?.asConversationId(),
        )
    }
    BackHandler(enabled = routes.size > 1) {
        if (route == AppRoute.Appearance) {
            previewProfile = null
            saveableScreens.removeState(AppRoute.Appearance.saveableScreenKey())
        }
        pop()
    }

    val rootProfile = if (route == AppRoute.Appearance) {
        previewProfile ?: appearance.activeProfile
    } else {
        appearance.activeProfile
    }
    AuroraMaterialTheme(profile = rootProfile) {
        saveableScreens.SaveableStateProvider(route.saveableScreenKey()) {
            when (route) {
            AppRoute.Inbox -> InboxRoute(
                services = services,
                appearance = appearance,
                appearanceController = appearanceController,
                scopedEditorDismissalGeneration = scopedEditorDismissalGeneration,
                diagnosticsAvailable = diagnosticsAvailable,
                contactsPermissionGranted = contactsPermissionGranted,
                onOpenConversation = {
                    PresentationTrace.begin(PresentationTrace.THREAD_OPEN)
                    push(newThreadRoute(it))
                },
                onOpenSearch = { push(AppRoute.Search()) },
                onOpenAppearance = { push(AppRoute.Appearance) },
                onOpenDiagnostics = onOpenDiagnostics,
                onRequestContactsPermission = onRequestContactsPermission,
                onReady = {
                    if (!inboxDrawReported) {
                        inboxDrawReported = true
                        onInboxReady()
                    }
                },
            )
            AppRoute.Appearance -> ThemeStudioRoute(
                appearance = appearance,
                controller = appearanceController,
                onPreviewProfileChange = { previewProfile = it },
                onClose = {
                    saveableScreens.removeState(AppRoute.Appearance.saveableScreenKey())
                    pop()
                },
            )
            is AppRoute.Search -> SearchRoute(
                services = services,
                route = route,
                onQueryChanged = { replaceCurrent(AppRoute.Search(it)) },
                onOpenHit = { hit ->
                    PresentationTrace.begin(PresentationTrace.EXACT_JUMP)
                    push(
                        newThreadRoute(
                            providerThreadId = hit.providerThreadId,
                            anchor = SearchAnchor(
                                localRowId = hit.localRowId,
                                providerId = hit.providerId,
                                providerThreadId = hit.providerThreadId,
                            ),
                        ),
                    )
                },
                onOpenConversation = { push(newThreadRoute(it)) },
                onBack = ::pop,
            )
            is AppRoute.Thread -> ThreadRoute(
                services = services,
                appearance = appearance,
                appearanceController = appearanceController,
                scopedEditorDismissalGeneration = scopedEditorDismissalGeneration,
                route = route,
                context = context,
                onOpenSearch = { push(AppRoute.Search()) },
                onBack = ::pop,
            )
            }
        }
    }
}

@Composable
private fun InboxRoute(
    services: AuroraSmsRootServices,
    appearance: AppAppearanceState,
    appearanceController: AppearanceController,
    scopedEditorDismissalGeneration: Long,
    diagnosticsAvailable: Boolean,
    contactsPermissionGranted: Boolean,
    onOpenConversation: (ProviderThreadId) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onRequestContactsPermission: () -> Unit,
    onReady: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    val holder = remember(services, scope) {
        InboxStateHolder(
            repository = services.conversationRepository,
            contactCache = services.contactCache,
            scope = scope,
        )
    }
    DisposableEffect(holder) { onDispose(holder::close) }
    val state by holder.state.collectAsStateWithLifecycle()
    LaunchedEffect(state is InboxUiState.Ready) {
        if (state is InboxUiState.Ready) {
            withFrameNanos { }
            onReady()
        }
    }
    val inboxScope = remember { AppearanceScope.Screen(AppearanceScreenScope.INBOX) }
    val globalThreadScope = remember { AppearanceScope.Screen(AppearanceScreenScope.GLOBAL_THREAD) }
    val inboxOverrideObservation by remember(appearanceController, inboxScope) {
        appearanceController.observeOverride(inboxScope)
    }.collectAsStateWithLifecycle(
        initialValue = AppAppearanceOverrideObservation.loading(inboxScope),
    )
    val globalThreadOverrideObservation by remember(appearanceController, globalThreadScope) {
        appearanceController.observeOverride(globalThreadScope)
    }.collectAsStateWithLifecycle(
        initialValue = AppAppearanceOverrideObservation.loading(globalThreadScope),
    )
    val inboxOverride = inboxOverrideObservation.overrideFor(inboxScope)
    val globalThreadOverride = globalThreadOverrideObservation.overrideFor(globalThreadScope)
    val wallpaperController = services.wallpaperController
    val globalWallpaperFlow = remember(wallpaperController, globalThreadScope) {
        wallpaperController?.observe(globalThreadScope)
            ?: flowOf(AppWallpaperObservation.unavailable())
    }
    val globalWallpaperObservation by globalWallpaperFlow.collectAsStateWithLifecycle(
        initialValue = wallpaperController
            ?.let { AppWallpaperObservation.loading(globalThreadScope) }
            ?: AppWallpaperObservation.unavailable(),
    )
    var editorScopeCode by rememberSaveable { mutableStateOf<String?>(null) }
    var wallpaperEditorScopeCode by rememberSaveable { mutableStateOf<String?>(null) }
    var observedDismissalGeneration by rememberSaveable {
        mutableStateOf(scopedEditorDismissalGeneration)
    }
    LaunchedEffect(scopedEditorDismissalGeneration) {
        if (shouldDismissScopedEditor(observedDismissalGeneration, scopedEditorDismissalGeneration)) {
            editorScopeCode = null
            wallpaperEditorScopeCode = null
            observedDismissalGeneration = scopedEditorDismissalGeneration
        }
    }
    val editorScope = when (editorScopeCode) {
        AppearanceScreenScope.INBOX.storageCode -> inboxScope
        AppearanceScreenScope.GLOBAL_THREAD.storageCode -> globalThreadScope
        else -> null
    }
    val canonicalName = stringResource(R.string.appearance_default_profile)
    val activeName = appearance.activeProfileName(canonicalName)
    val inboxProfile = appearance.profileFor(inboxOverride, appearance.activeProfile)
    val notificationReminderController = services.notificationReminderController
    val notificationReminderDelayMinutes by remember(notificationReminderController) {
        notificationReminderController?.delayMinutes ?: flowOf(0)
    }.collectAsStateWithLifecycle(
        initialValue = notificationReminderController?.delayMinutes?.value ?: 0,
    )
    val signatureStore = services.messageSignaturePreferenceStore
    val signatureSnapshot by signatureStore.snapshot.collectAsStateWithLifecycle()
    var globalSignatureEditorOpen by rememberSaveable { mutableStateOf(false) }

    AuroraMaterialTheme(profile = inboxProfile) {
        InboxScreen(
            state = state,
            diagnosticsAvailable = diagnosticsAvailable,
            contactsPermissionGranted = contactsPermissionGranted,
            onOpenConversation = onOpenConversation,
            onOpenSearch = onOpenSearch,
            onOpenAppearance = onOpenAppearance,
            onOpenInboxAppearance = {
                editorScopeCode = AppearanceScreenScope.INBOX.storageCode
                wallpaperEditorScopeCode = null
            },
            onOpenConversationDefaults = {
                editorScopeCode = AppearanceScreenScope.GLOBAL_THREAD.storageCode
                wallpaperEditorScopeCode = null
            },
            onOpenDiagnostics = onOpenDiagnostics,
            onRequestContactsPermission = onRequestContactsPermission,
            onRetry = holder::reload,
            onLoadOlder = holder::loadOlder,
            onAtNewestChanged = holder::markUserAtNewest,
            onAcceptPending = holder::acceptPendingNewer,
            onViewportChanged = holder::onViewportChanged,
            onAnchorRestored = holder::anchorRestored,
            notificationReminderDelayMinutes = notificationReminderDelayMinutes,
            onSetNotificationReminderDelayMinutes = { delayMinutes ->
                notificationReminderController?.let { controller ->
                    scope.launch { controller.setDelayMinutes(delayMinutes) }
                }
            },
            signaturesAvailable = signatureSnapshot.available,
            onOpenGlobalSignature = { globalSignatureEditorOpen = true },
        )
        if (globalSignatureEditorOpen && signatureSnapshot.available) {
            GlobalMessageSignatureDialog(
                current = signatureSnapshot.global,
                onSave = { signature ->
                    signatureStore.setGlobal(signature).also { saved ->
                        if (!saved) {
                            Toast.makeText(
                                context,
                                R.string.signature_save_failed,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
                onDismiss = { globalSignatureEditorOpen = false },
            )
        }
        editorScope?.let { target ->
            val currentObservation = when (target.screen) {
                AppearanceScreenScope.INBOX -> inboxOverrideObservation
                AppearanceScreenScope.GLOBAL_THREAD -> globalThreadOverrideObservation
                AppearanceScreenScope.ARCHIVE,
                AppearanceScreenScope.SETTINGS,
                AppearanceScreenScope.SPAM_BLOCKED,
                -> AppAppearanceOverrideObservation.unavailable()
            }
            val currentOverride = currentObservation.overrideFor(target)
            val kind = when (target.screen) {
                    AppearanceScreenScope.INBOX -> ScopedAppearanceKind.INBOX
                    AppearanceScreenScope.GLOBAL_THREAD -> ScopedAppearanceKind.GLOBAL_THREADS
                    AppearanceScreenScope.ARCHIVE,
                    AppearanceScreenScope.SETTINGS,
                    AppearanceScreenScope.SPAM_BLOCKED,
                    -> return@let
                }
            val wallpaperPageOpen = wallpaperEditorScopeCode == target.screen.storageCode
            if (wallpaperPageOpen && target.screen == AppearanceScreenScope.GLOBAL_THREAD) {
                val currentWallpaper = globalWallpaperObservation.assignmentFor(target)
                ScopedWallpaperDialog(
                    privateRestorationKey = target.privateScopedAppearanceRestorationKey(),
                    currentAssignment = currentWallpaper,
                    assignmentSnapshotReady = globalWallpaperObservation.isReadyFor(target),
                    controller = wallpaperController ?: return@let,
                    onApply = { source, dim, focalX, focalY, expectedRevision ->
                        if (wallpaperEditorScopeCode != target.screen.storageCode) {
                            WallpaperApplyControllerResult.Failed(
                                WallpaperControllerError.STALE_ASSIGNMENT,
                            )
                        } else if (source != null) {
                            wallpaperController.apply(
                                scope = target,
                                source = source,
                                dimPermill = dim,
                                focalXPermill = focalX,
                                focalYPermill = focalY,
                                expectedRevision = expectedRevision,
                            )
                        } else {
                            val existingMediaId = currentWallpaper?.mediaId
                                ?: return@ScopedWallpaperDialog WallpaperApplyControllerResult.Failed(
                                    WallpaperControllerError.SAVE_FAILED,
                                )
                            wallpaperController.applyExisting(
                                scope = target,
                                mediaIdToken = existingMediaId,
                                dimPermill = dim,
                                focalXPermill = focalX,
                                focalYPermill = focalY,
                                expectedRevision = expectedRevision,
                            )
                        }
                    },
                    onReset = { expectedRevision ->
                        if (wallpaperEditorScopeCode != target.screen.storageCode) {
                            WallpaperApplyControllerResult.Failed(
                                WallpaperControllerError.STALE_ASSIGNMENT,
                            )
                        } else {
                            wallpaperController.reset(target, expectedRevision)
                        }
                    },
                    onBack = { wallpaperEditorScopeCode = null },
                    onDismiss = {
                        wallpaperEditorScopeCode = null
                        editorScopeCode = null
                    },
                )
            } else {
                ScopedAppearanceDialog(
                    kind = kind,
                    privateRestorationKey = target.privateScopedAppearanceRestorationKey(),
                    profiles = appearance.profiles,
                    profileSnapshotReady = appearance.profileSnapshotReady,
                    overrideSnapshotReady = currentObservation.isReadyFor(target),
                    inheritedProfile = appearance.activeProfile,
                    inheritedName = activeName,
                    currentOverride = currentOverride,
                    onApply = { profileId, expectedRevision ->
                        appearanceController.applyOverride(target, profileId, expectedRevision)
                    },
                    onOpenWallpaper = if (
                        target.screen == AppearanceScreenScope.GLOBAL_THREAD && wallpaperController != null
                    ) {
                        { wallpaperEditorScopeCode = target.screen.storageCode }
                    } else {
                        null
                    },
                    onDismiss = {
                        wallpaperEditorScopeCode = null
                        editorScopeCode = null
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchRoute(
    services: AuroraSmsRootServices,
    route: AppRoute.Search,
    onQueryChanged: (String) -> Unit,
    onOpenHit: (SearchHit) -> Unit,
    onOpenConversation: (ProviderThreadId) -> Unit,
    onBack: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val holder = remember(services, scope) {
        SearchStateHolder(messageIndex = services.messageIndex, scope = scope)
    }
    DisposableEffect(holder) { onDispose(holder::close) }
    LaunchedEffect(holder) {
        if (route.query.isNotEmpty()) {
            PresentationTrace.begin(PresentationTrace.SEARCH_RESULTS)
            holder.updateQuery(route.query)
        }
    }
    val state by holder.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        if (state is SearchUiState.Page || state is SearchUiState.Invalid) {
            withFrameNanos { }
            PresentationTrace.end(PresentationTrace.SEARCH_RESULTS)
        }
    }
    SearchScreen(
        state = state,
        onQueryChanged = { query ->
            if (query.isNotBlank() && state.query.isBlank()) {
                PresentationTrace.begin(PresentationTrace.SEARCH_RESULTS)
            }
            holder.updateQuery(query)
            onQueryChanged(query)
        },
        onOpenHit = onOpenHit,
        onOpenConversation = onOpenConversation,
        onLoadMore = holder::loadMore,
        onBack = onBack,
    )
}

@Composable
private fun ThreadRoute(
    services: AuroraSmsRootServices,
    appearance: AppAppearanceState,
    appearanceController: AppearanceController,
    scopedEditorDismissalGeneration: Long,
    route: AppRoute.Thread,
    context: Context,
    onOpenSearch: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val holder = remember(services, route.providerThreadId, route.anchor, route.stateEntryId, scope) {
        ThreadStateHolder(
            providerThreadId = route.providerThreadId,
            repository = services.threadTimelineRepository,
            conversationRepository = services.conversationRepository,
            messageIndex = services.messageIndex,
            contactCache = services.contactCache,
            subscriptionRepository = services.subscriptionRepository,
            scope = scope,
            initialAnchor = route.anchor,
        )
    }
    DisposableEffect(holder) { onDispose(holder::close) }
    val threadState by holder.state.collectAsStateWithLifecycle()
    val signatureStore = services.messageSignaturePreferenceStore
    val signatureSnapshot by signatureStore.snapshot.collectAsStateWithLifecycle()
    val signatureConversationKey = remember(route.providerThreadId, threadState) {
        trustedMessageSignatureConversationKey(route.providerThreadId, threadState)
    }
    val effectiveSignature = signatureSnapshot.resolve(signatureConversationKey)
    val conversationSignatureOverride = signatureConversationKey
        ?.let(signatureSnapshot.conversations::get)
        ?: ConversationSignatureOverride.Inherit
    var conversationSignatureEditorOpen by rememberSaveable(route.providerThreadId.value) {
        mutableStateOf(false)
    }
    LaunchedEffect(signatureConversationKey, signatureSnapshot.available) {
        if (signatureConversationKey == null || !signatureSnapshot.available) {
            conversationSignatureEditorOpen = false
        }
    }
    val conversationSubscriptionScope = remember(route.providerThreadId, threadState) {
        trustedConversationSubscriptionScope(route.providerThreadId, threadState)
    }
    var subscriptionPreferenceState by remember(conversationSubscriptionScope) {
        mutableStateOf<AppConversationSubscriptionPreferenceState>(
            if (conversationSubscriptionScope == null) {
                AppConversationSubscriptionPreferenceState.Unavailable
            } else {
                AppConversationSubscriptionPreferenceState.Loading
            },
        )
    }
    var subscriptionSelectionInFlight by remember(conversationSubscriptionScope) {
        mutableStateOf(false)
    }
    LaunchedEffect(services, conversationSubscriptionScope) {
        val preferenceScope = conversationSubscriptionScope
        if (preferenceScope == null) {
            subscriptionPreferenceState = AppConversationSubscriptionPreferenceState.Unavailable
            return@LaunchedEffect
        }
        subscriptionPreferenceState = AppConversationSubscriptionPreferenceState.Loading
        subscriptionPreferenceState = services.conversationSubscriptionPreferenceRepository
            .read(preferenceScope)
            .toAppPreferenceState()
    }
    val subscriptionSelection = resolveConversationSubscriptionUiState(
        ready = threadState as? ThreadUiState.Ready,
        preferenceState = subscriptionPreferenceState,
        saving = subscriptionSelectionInFlight,
    )
    LaunchedEffect(threadState) {
        if (threadState is ThreadUiState.Ready) {
            withFrameNanos { }
            PresentationTrace.end(
                if (route.anchor == null) PresentationTrace.THREAD_OPEN else PresentationTrace.EXACT_JUMP,
            )
        }
    }

    val sendController = services.threadSmsSendController
    val sendObservationFlow = remember(sendController, route.providerThreadId) {
        sendController.observe(route.providerThreadId)
    }
    val sendObservation by sendObservationFlow.collectAsStateWithLifecycle(
        initialValue = ThreadSmsSendObservation(ThreadSmsSendPhase.RECOVERY_PENDING),
    )
    val scheduleController = services.scheduledSmsController
    val scheduleObservationFlow = remember(scheduleController, route.providerThreadId) {
        scheduleController.observe(route.providerThreadId)
    }
    val scheduleObservation by scheduleObservationFlow.collectAsStateWithLifecycle(
        initialValue = ScheduledSmsObservation.Loading,
    )
    val sendDelayController = services.sendDelayController
    val sendDelayObservationFlow = remember(sendDelayController, route.providerThreadId) {
        sendDelayController.observe(route.providerThreadId)
    }
    val sendDelayObservation by sendDelayObservationFlow.collectAsStateWithLifecycle(
        initialValue = SendDelayObservation.Loading,
    )
    val sendDelaySeconds by services.sendDelayPreferenceStore.delaySeconds
        .collectAsStateWithLifecycle()
    val deletionController = services.permanentDeletionController
    val deletionObservationFlow = remember(deletionController, route.providerThreadId) {
        deletionController.observe(route.providerThreadId)
    }
    val deletionObservation by deletionObservationFlow.collectAsStateWithLifecycle(
        initialValue = PermanentDeletionObservation.Loading,
    )
    val deletion = deletionObservation.toDeletionUiState()
    val deletionLocksComposer = deletionObservation !is PermanentDeletionObservation.None &&
        deletionObservation !is PermanentDeletionObservation.Completed
    var observedDeletionCompletionEpoch by remember(route.providerThreadId) {
        mutableStateOf(0L)
    }
    LaunchedEffect(deletionObservation) {
        val completed = deletionObservation as? PermanentDeletionObservation.Completed
            ?: return@LaunchedEffect
        if (completed.epoch <= observedDeletionCompletionEpoch) return@LaunchedEffect
        observedDeletionCompletionEpoch = completed.epoch
        deletionController.acknowledgeCompletion(route.providerThreadId, completed.epoch)
        if (completed.targetKind == PermanentDeletionTargetKind.THREAD) {
            onBack()
        } else {
            holder.loadLatest()
        }
    }
    var savedDraftRestoration by rememberSaveable(
        route.providerThreadId.value,
        stateSaver = SAVED_DRAFT_RESTORATION_SAVER,
    ) {
        mutableStateOf(SavedDraftRestoration())
    }
    var writerGeneration by rememberSaveable(route.providerThreadId.value) { mutableStateOf(0L) }
    // Controller epochs are process-local. This observer must reset with the
    // process too, otherwise a restored larger value could hide a later send.
    var observedCompletionEpoch by remember(route.providerThreadId) {
        mutableStateOf(0L)
    }
    var observedUnknownAcknowledgementEpoch by remember(route.providerThreadId) {
        mutableStateOf(0L)
    }
    LaunchedEffect(sendObservation.completionEpoch) {
        if (sendObservation.completionEpoch > observedCompletionEpoch) {
            observedCompletionEpoch = sendObservation.completionEpoch
            savedDraftRestoration = SavedDraftRestoration()
            writerGeneration += 1L
        }
    }
    val writerCreationGeneration = writerGeneration
    val writer = remember(services, route.providerThreadId, writerCreationGeneration) {
        services.createDraftWriter(
            identity = DraftIdentity.ProviderThread(route.providerThreadId),
            restorationToken = savedDraftRestoration.token,
        )
    }
    DisposableEffect(writer) { onDispose { services.releaseDraftWriter(writer) } }
    val draftStatus by writer.status.collectAsStateWithLifecycle()
    LaunchedEffect(writer, draftStatus) {
        if (writerCreationGeneration != writerGeneration) return@LaunchedEffect
        when (val status = draftStatus) {
            DraftWriteStatus.Loading -> Unit
            is DraftWriteStatus.Active -> {
                savedDraftRestoration = SavedDraftRestoration(status.toRestorationToken())
            }
            is DraftWriteStatus.Failed -> {
                savedDraftRestoration = SavedDraftRestoration(status.toRestorationToken())
            }
        }
    }
    LaunchedEffect(writer, sendObservation.phase) {
        if (sendObservation.phase == ThreadSmsSendPhase.KNOWN_UNSENT) {
            writer.unfreezeAfterSendSettled(DraftUnfreezeReason.KNOWN_UNSENT)
        }
    }
    LaunchedEffect(writer, sendObservation.unknownAcknowledgementEpoch) {
        if (
            sendObservation.unknownAcknowledgementEpoch >
            observedUnknownAcknowledgementEpoch
        ) {
            observedUnknownAcknowledgementEpoch = sendObservation.unknownAcknowledgementEpoch
            writer.unfreezeAfterSendSettled(
                DraftUnfreezeReason.SUBMISSION_UNKNOWN_ACKNOWLEDGED,
            )
        }
    }
    var sendAttemptInFlight by remember(route.providerThreadId) { mutableStateOf(false) }
    var scheduleAttemptInFlight by remember(route.providerThreadId) { mutableStateOf(false) }
    var sendDelayAttemptInFlight by remember(route.providerThreadId) { mutableStateOf(false) }
    val visibleBody = when (val status = draftStatus) {
        // Saved-state text is untrusted until the writer compares its exact
        // base token with Room. Avoid even a transient post-send resurrection.
        DraftWriteStatus.Loading -> ""
        is DraftWriteStatus.Active -> status.latest.body.orEmpty()
        is DraftWriteStatus.Failed -> status.latest.body.orEmpty()
    }
    val unsignedSegmentCount = visibleBody.takeIf(String::isNotBlank)
        ?.let(services::countSmsSegments)
    val resolvedOutgoingBody = visibleBody.takeIf(String::isNotBlank)
        ?.let { body -> resolveOutgoingBody(body, effectiveSignature) }
    val segmentCount = resolvedOutgoingBody?.let(services::countSmsSegments)
    val composer = draftStatus.toComposerUiState(
        restoredBody = "",
        threadState = threadState,
        sendObservation = sendObservation,
        sendAttemptInFlight = sendAttemptInFlight,
        segmentCount = segmentCount,
        unsignedSegmentCount = unsignedSegmentCount,
        signatureApplied = effectiveSignature != null && resolvedOutgoingBody != null,
        signatureStateAllowsSend = signatureSnapshot.sendAllowed,
        selectedSubscriptionAvailable = subscriptionSelection.selected?.smsCapable == true,
        scheduleObservation = scheduleObservation,
        scheduleAttemptInFlight = scheduleAttemptInFlight,
        sendDelayObservation = sendDelayObservation,
        sendDelayAttemptInFlight = sendDelayAttemptInFlight,
        permanentDeletionActive = deletionLocksComposer,
    )
    val globalThreadScope = remember { AppearanceScope.Screen(AppearanceScreenScope.GLOBAL_THREAD) }
    val globalThreadOverrideObservation by remember(appearanceController, globalThreadScope) {
        appearanceController.observeOverride(globalThreadScope)
    }.collectAsStateWithLifecycle(
        initialValue = AppAppearanceOverrideObservation.loading(globalThreadScope),
    )
    val globalThreadOverride = globalThreadOverrideObservation.overrideFor(globalThreadScope)
    val wallpaperController = services.wallpaperController
    val globalWallpaperFlow = remember(wallpaperController, globalThreadScope) {
        wallpaperController?.observe(globalThreadScope)
            ?: flowOf(AppWallpaperObservation.unavailable())
    }
    val globalWallpaperObservation by globalWallpaperFlow.collectAsStateWithLifecycle(
        initialValue = wallpaperController
            ?.let { AppWallpaperObservation.loading(globalThreadScope) }
            ?: AppWallpaperObservation.unavailable(),
    )
    val conversationScope = remember(route.providerThreadId, threadState) {
        trustedConversationAppearanceScope(route.providerThreadId, threadState)
    }
    val canonicalName = stringResource(R.string.appearance_default_profile)
    val activeName = appearance.activeProfileName(canonicalName)
    val globalThreadProfile = appearance.profileFor(globalThreadOverride, appearance.activeProfile)
    val globalThreadName = appearance.profileNameFor(globalThreadOverride, activeName)
    val conversationOverrideFlow = remember(appearanceController, conversationScope) {
        conversationScope?.let(appearanceController::observeOverride)
            ?: flowOf(AppAppearanceOverrideObservation.unavailable())
    }
    val conversationOverrideObservation by conversationOverrideFlow.collectAsStateWithLifecycle(
        initialValue = conversationScope
            ?.let(AppAppearanceOverrideObservation::loading)
            ?: AppAppearanceOverrideObservation.unavailable(),
    )
    val conversationOverride = conversationScope?.let(conversationOverrideObservation::overrideFor)
    val conversationOverrideReady = conversationScope?.let(
        conversationOverrideObservation::isReadyFor,
    ) == true
    val privateRestorationKey = conversationScope?.privateScopedAppearanceRestorationKey()
    val conversationWallpaperFlow = remember(wallpaperController, conversationScope) {
        if (wallpaperController != null && conversationScope != null) {
            wallpaperController.observe(conversationScope)
        } else {
            flowOf(AppWallpaperObservation.unavailable())
        }
    }
    val conversationWallpaperObservation by conversationWallpaperFlow.collectAsStateWithLifecycle(
        initialValue = conversationScope
            ?.takeIf { wallpaperController != null }
            ?.let(AppWallpaperObservation::loading)
            ?: AppWallpaperObservation.unavailable(),
    )
    var openEditorTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var wallpaperEditorOpen by rememberSaveable(privateRestorationKey) { mutableStateOf(false) }
    val latestConversationScope by rememberUpdatedState(conversationScope)
    val latestPrivateRestorationKey by rememberUpdatedState(privateRestorationKey)
    val latestOpenEditorTarget by rememberUpdatedState(openEditorTarget)
    val appearanceEditorOpen = privateRestorationKey != null && openEditorTarget == privateRestorationKey
    LaunchedEffect(threadState, privateRestorationKey) {
        if (
            shouldClearConversationScopedEditorTarget(
                openEditorTarget = openEditorTarget,
                currentPrivateRestorationKey = privateRestorationKey,
                identityLookupComplete = isConversationIdentityLookupComplete(threadState),
            )
        ) {
            openEditorTarget = null
            wallpaperEditorOpen = false
        }
    }
    var observedDismissalGeneration by rememberSaveable(privateRestorationKey) {
        mutableStateOf(scopedEditorDismissalGeneration)
    }
    LaunchedEffect(scopedEditorDismissalGeneration) {
        if (shouldDismissScopedEditor(observedDismissalGeneration, scopedEditorDismissalGeneration)) {
            openEditorTarget = null
            wallpaperEditorOpen = false
            observedDismissalGeneration = scopedEditorDismissalGeneration
        }
    }
    val conversationProfile = appearance.profileFor(conversationOverride, globalThreadProfile)
    val effectiveProfileReadyForWallpaper = appearance.profileSnapshotReady &&
        globalThreadOverrideObservation.isReadyFor(globalThreadScope) &&
        (
            conversationScope == null ||
                conversationOverrideObservation.isReadyFor(conversationScope)
            )
    val wallpaperCandidates = resolveWallpaperCandidates(
        conversationScope = conversationScope,
        conversationObservation = conversationWallpaperObservation,
        globalScope = globalThreadScope,
        globalObservation = globalWallpaperObservation,
        highContrast = !effectiveProfileReadyForWallpaper || conversationProfile.highContrast,
    )
    val wallpaperRenderRequestEpoch = remember(route.stateEntryId, privateRestorationKey) {
        WallpaperRenderRequestEpoch()
    }

    AuroraMaterialTheme(profile = conversationProfile) {
        ThreadScreen(
            state = threadState,
            composer = composer,
            subscriptionSelection = subscriptionSelection,
            attachmentRepository = services.mmsAttachmentRepository,
            previewLoader = services.previewLoader,
            onBack = onBack,
            onOpenSearch = onOpenSearch,
            conversationAppearanceAvailable = conversationScope != null,
            onOpenConversationAppearance = {
                openEditorTarget = privateRestorationKey.takeIf { conversationScope != null }
                wallpaperEditorOpen = false
            },
            conversationSignatureAvailable = signatureSnapshot.available &&
                signatureConversationKey != null,
            onOpenConversationSignature = { conversationSignatureEditorOpen = true },
            timelineBackground = {
                wallpaperController?.let { controller ->
                    ManagedWallpaperSurface(
                        controller = controller,
                        requestEpoch = wallpaperRenderRequestEpoch,
                        candidates = wallpaperCandidates,
                    )
                }
            },
            isDialable = ::isConservativeDialAddress,
            onDial = { address -> launchSystemDialer(context, address) },
            onRetry = holder::loadLatest,
            onLoadOlder = holder::loadOlder,
            onLoadNewer = holder::loadNewer,
            onAtNewestChanged = holder::markUserAtNewest,
            onAcceptPending = holder::acceptPendingNewer,
            onViewportChanged = holder::onViewportChanged,
            onAnchorRestored = holder::anchorRestored,
            onToggleMessageExpansion = holder::toggleMessageExpansion,
            onDraftChanged = { body ->
                val content = DraftEditorContent(body = body.takeIf(String::isNotEmpty), subject = null)
                if (writer.submit(content)) {
                    val token = (writer.status.value as? DraftWriteStatus.Active)
                        ?.toRestorationToken()
                    if (token != null) savedDraftRestoration = SavedDraftRestoration(token)
                }
            },
            onSend = {
                if (!sendAttemptInFlight && !sendDelayAttemptInFlight) {
                    sendAttemptInFlight = true
                    scope.launch {
                        var coordinatorEntered = false
                        var sendAttempt: ThreadSmsSendAttempt? = null
                        var sendDelayEntered = false
                        var sendDelayAttempt: SendDelayAttempt? = null
                        try {
                            val frozen = writer.freezeForSend() ?: return@launch
                            // Close the base-free SavedState ABA window before
                            // the durable coordinator can clear this revision.
                            savedDraftRestoration = SavedDraftRestoration(
                                frozen.toExactRestorationToken(),
                            )
                            if (frozen.content.body.isNullOrBlank()) return@launch
                            val ready = threadState as? ThreadUiState.Ready ?: return@launch
                            val identity = ready.verifiedConversationIdentity ?: return@launch
                            val subscription = subscriptionSelection.selected
                                ?.takeIf { it.smsCapable }
                                ?: return@launch
                            if (
                                identity.providerThreadId != route.providerThreadId ||
                                identity.participants.size != 1 ||
                                resolveOutgoingBody(
                                    frozen.content.body.orEmpty(),
                                    effectiveSignature,
                                )?.let(services::countSmsSegments) != 1
                            ) {
                                return@launch
                            }
                            // Once control enters the durable coordinator, a
                            // cancellation or unexpected exception is not proof
                            // that submission was refused. Keep the writer
                            // frozen until recovery classifies the operation.
                            if (sendDelaySeconds > 0) {
                                sendDelayEntered = true
                                sendDelayAttempt = withContext(NonCancellable) {
                                    sendDelayController.enqueue(
                                        SendDelayCommand(
                                            identity = identity,
                                            subscriptionId = subscription.id,
                                            draftId = frozen.draftId,
                                            draftRevision = frozen.revision,
                                            delayMillis = sendDelaySeconds * 1_000L,
                                            frozenSignature = effectiveSignature,
                                        ),
                                    )
                                }
                            } else {
                                coordinatorEntered = true
                                sendAttempt = withContext(NonCancellable) {
                                    sendController.send(
                                        ThreadSmsSendCommand(
                                            identity = identity,
                                            subscriptionId = subscription.id,
                                            draftId = frozen.draftId,
                                            draftRevision = frozen.revision,
                                            frozenSignature = effectiveSignature,
                                        ),
                                    )
                                }
                            }
                        } finally {
                            if (
                                shouldUnfreezeComposerAsRefused(coordinatorEntered, sendAttempt) &&
                                (!sendDelayEntered || sendDelayAttempt == SendDelayAttempt.REFUSED)
                            ) {
                                writer.unfreezeAfterSendSettled(DraftUnfreezeReason.SEND_REFUSED)
                            }
                            sendAttemptInFlight = false
                        }
                    }
                }
            },
            onUndoSend = {
                if (!sendDelayAttemptInFlight) {
                    sendDelayAttemptInFlight = true
                    scope.launch {
                        try {
                            if (sendDelayController.undo(route.providerThreadId)) {
                                writer.unfreezeAfterSendSettled(
                                    DraftUnfreezeReason.SEND_DELAY_UNDONE,
                                )
                            }
                        } finally {
                            sendDelayAttemptInFlight = false
                        }
                    }
                }
            },
            sendDelaySeconds = sendDelaySeconds,
            onSetSendDelaySeconds = { seconds ->
                services.sendDelayPreferenceStore.setDelaySeconds(seconds)
            },
            onSchedule = {
                if (!scheduleAttemptInFlight && scheduleObservation == ScheduledSmsObservation.None) {
                    showScheduledSmsDateTimePicker(context) { dueTimestampMillis ->
                        scheduleAttemptInFlight = true
                        scope.launch {
                            var accepted = false
                            try {
                                val frozen = writer.freezeForSend() ?: return@launch
                                savedDraftRestoration = SavedDraftRestoration(
                                    frozen.toExactRestorationToken(),
                                )
                                if (frozen.content.body.isNullOrBlank()) return@launch
                                val ready = threadState as? ThreadUiState.Ready ?: return@launch
                                val identity = ready.verifiedConversationIdentity ?: return@launch
                                val subscription = subscriptionSelection.selected
                                    ?.takeIf { it.smsCapable } ?: return@launch
                                if (
                                    identity.providerThreadId != route.providerThreadId ||
                                    identity.participants.size != 1 ||
                                    resolveOutgoingBody(
                                        frozen.content.body.orEmpty(),
                                        effectiveSignature,
                                    )?.let(services::countSmsSegments) != 1
                                ) {
                                    return@launch
                                }
                                accepted = withContext(NonCancellable) {
                                    scheduleController.schedule(
                                        ScheduledSmsCommand(
                                            identity = identity,
                                            subscriptionId = subscription.id,
                                            draftId = frozen.draftId,
                                            draftRevision = frozen.revision,
                                            dueTimestampMillis = dueTimestampMillis,
                                            frozenSignature = effectiveSignature,
                                        ),
                                    )
                                } == ScheduledSmsAttempt.ACCEPTED
                            } finally {
                                if (!accepted) {
                                    writer.unfreezeAfterSendSettled(
                                        DraftUnfreezeReason.SCHEDULE_REFUSED,
                                    )
                                }
                                scheduleAttemptInFlight = false
                            }
                        }
                    }
                }
            },
            onCancelSchedule = {
                if (!scheduleAttemptInFlight) {
                    scheduleAttemptInFlight = true
                    scope.launch {
                        try {
                            if (scheduleController.cancel(route.providerThreadId)) {
                                writer.unfreezeAfterSendSettled(
                                    DraftUnfreezeReason.SCHEDULE_CANCELLED,
                                )
                            }
                        } finally {
                            scheduleAttemptInFlight = false
                        }
                    }
                }
            },
            onRequestExactAlarmAccess = {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    Toast.makeText(
                        context,
                        R.string.exact_alarm_settings_unavailable,
                        Toast.LENGTH_LONG,
                    ).show()
                    return@ThreadScreen
                }
                val intent = Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    "package:${context.packageName}".toUri(),
                )
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(
                        context,
                        R.string.exact_alarm_settings_unavailable,
                        Toast.LENGTH_LONG,
                    ).show()
                } catch (_: SecurityException) {
                    Toast.makeText(
                        context,
                        R.string.exact_alarm_settings_unavailable,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
            onAcknowledgeSubmissionUnknown = {
                scope.launch {
                    if (sendController.acknowledgeSubmissionUnknown(route.providerThreadId)) {
                        writer.unfreezeAfterSendSettled(
                            DraftUnfreezeReason.SUBMISSION_UNKNOWN_ACKNOWLEDGED,
                        )
                    }
                }
            },
            deletion = deletion,
            onRequestDeleteMessage = { message ->
                val fingerprint = message.syncFingerprint
                if (fingerprint != null) {
                    scope.launch {
                        deletionController.request(
                            PermanentDeletionCommand.Message(
                                providerMessageId = message.providerMessageId,
                                providerThreadId = route.providerThreadId,
                                syncFingerprint = fingerprint,
                            ),
                        )
                    }
                }
            },
            onRequestDeleteThread = {
                scope.launch {
                    deletionController.request(
                        PermanentDeletionCommand.Thread(route.providerThreadId),
                    )
                }
            },
            onUndoDeletion = {
                scope.launch { deletionController.undo(route.providerThreadId) }
            },
            onRetryDeletionStatus = {
                scope.launch {
                    deletionController.recover(PermanentDeletionRecoveryReason.APP_STARTUP)
                }
            },
            onSelectSubscription = { subscriptionId ->
                val preferenceScope = conversationSubscriptionScope
                val option = subscriptionSelection.options.singleOrNull {
                    it.id == subscriptionId && it.smsCapable
                }
                if (
                    preferenceScope != null &&
                    option != null &&
                    !subscriptionSelectionInFlight &&
                    (subscriptionPreferenceState as?
                        AppConversationSubscriptionPreferenceState.Stored)
                        ?.preference?.subscriptionId != subscriptionId
                ) {
                    subscriptionSelectionInFlight = true
                    scope.launch {
                        val current = subscriptionPreferenceState
                            as? AppConversationSubscriptionPreferenceState.Stored
                        val now = System.currentTimeMillis().coerceAtLeast(
                            (current?.preference?.updatedTimestampMillis ?: -1L) + 1L,
                        )
                        val result = services.conversationSubscriptionPreferenceRepository.set(
                            scope = preferenceScope,
                            subscriptionId = subscriptionId,
                            expectedRevision = current?.preference?.revision,
                            updatedTimestampMillis = now,
                        )
                        subscriptionPreferenceState = when (result) {
                            is ConversationSubscriptionRepositoryResult.Success ->
                                AppConversationSubscriptionPreferenceState.Stored(result.value)
                            ConversationSubscriptionRepositoryResult.StaleWrite ->
                                services.conversationSubscriptionPreferenceRepository
                                    .read(preferenceScope)
                                    .toAppPreferenceState()
                            ConversationSubscriptionRepositoryResult.NotFound,
                            ConversationSubscriptionRepositoryResult.CorruptData,
                            ConversationSubscriptionRepositoryResult.InvalidTimestamp,
                            is ConversationSubscriptionRepositoryResult.StorageFailure,
                            -> AppConversationSubscriptionPreferenceState.Failed
                        }
                        subscriptionSelectionInFlight = false
                    }
                }
            },
        )
        if (
            conversationSignatureEditorOpen &&
            signatureSnapshot.available &&
            signatureConversationKey != null
        ) {
            ConversationMessageSignatureDialog(
                inherited = signatureSnapshot.global,
                current = conversationSignatureOverride,
                onSave = { override ->
                    signatureStore.setConversation(
                        signatureConversationKey,
                        override,
                    ).also { saved ->
                        if (!saved) {
                            Toast.makeText(
                                context,
                                R.string.signature_save_failed,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
                onDismiss = { conversationSignatureEditorOpen = false },
            )
        }
        if (appearanceEditorOpen && !wallpaperEditorOpen) {
            ScopedAppearanceDialog(
                kind = ScopedAppearanceKind.CONVERSATION,
                privateRestorationKey = privateRestorationKey,
                profiles = appearance.profiles,
                profileSnapshotReady = appearance.profileSnapshotReady,
                overrideSnapshotReady = conversationOverrideReady,
                inheritedProfile = globalThreadProfile,
                inheritedName = globalThreadName,
                currentOverride = conversationOverride,
                onApply = { profileId, expectedRevision ->
                    appearanceController.applyOverride(
                        conversationScope,
                        profileId,
                        expectedRevision,
                    )
                },
                onOpenWallpaper = wallpaperController?.let {
                    { wallpaperEditorOpen = true }
                },
                onDismiss = {
                    wallpaperEditorOpen = false
                    openEditorTarget = null
                },
            )
        }
        if (appearanceEditorOpen && wallpaperEditorOpen && wallpaperController != null) {
            val editorScope = conversationScope
            val currentWallpaper = conversationWallpaperObservation.assignmentFor(editorScope)
            ScopedWallpaperDialog(
                privateRestorationKey = privateRestorationKey,
                currentAssignment = currentWallpaper,
                assignmentSnapshotReady = conversationWallpaperObservation.isReadyFor(editorScope),
                controller = wallpaperController,
                onApply = { source, dim, focalX, focalY, expectedRevision ->
                    val currentScope = conversationScope
                    if (
                        currentScope != editorScope ||
                        openEditorTarget != privateRestorationKey
                    ) {
                        WallpaperApplyControllerResult.Failed(
                            WallpaperControllerError.STALE_ASSIGNMENT,
                        )
                    } else if (source != null) {
                        wallpaperController.apply(
                            scope = currentScope,
                            source = source,
                            dimPermill = dim,
                            focalXPermill = focalX,
                            focalYPermill = focalY,
                            expectedRevision = expectedRevision,
                            targetStillCurrent = {
                                latestConversationScope == editorScope &&
                                    latestPrivateRestorationKey == privateRestorationKey &&
                                    latestOpenEditorTarget == privateRestorationKey
                            },
                        )
                    } else {
                        val existingMediaId = currentWallpaper?.mediaId
                            ?: return@ScopedWallpaperDialog WallpaperApplyControllerResult.Failed(
                                WallpaperControllerError.SAVE_FAILED,
                            )
                        wallpaperController.applyExisting(
                            scope = currentScope,
                            mediaIdToken = existingMediaId,
                            dimPermill = dim,
                            focalXPermill = focalX,
                            focalYPermill = focalY,
                            expectedRevision = expectedRevision,
                            targetStillCurrent = {
                                latestConversationScope == editorScope &&
                                    latestPrivateRestorationKey == privateRestorationKey &&
                                    latestOpenEditorTarget == privateRestorationKey
                            },
                        )
                    }
                },
                onReset = { expectedRevision ->
                    val currentScope = conversationScope
                    if (
                        currentScope != editorScope ||
                        openEditorTarget != privateRestorationKey
                    ) {
                        WallpaperApplyControllerResult.Failed(
                            WallpaperControllerError.STALE_ASSIGNMENT,
                        )
                    } else {
                        wallpaperController.reset(
                            scope = currentScope,
                            expectedRevision = expectedRevision,
                            targetStillCurrent = {
                                latestConversationScope == editorScope &&
                                    latestPrivateRestorationKey == privateRestorationKey &&
                                    latestOpenEditorTarget == privateRestorationKey
                            },
                        )
                    }
                },
                onBack = { wallpaperEditorOpen = false },
                onDismiss = {
                    wallpaperEditorOpen = false
                    openEditorTarget = null
                },
            )
        }
    }
}

internal fun trustedConversationAppearanceScope(
    providerThreadId: ProviderThreadId,
    state: ThreadUiState,
): AppearanceScope.Conversation? {
    val ready = state as? ThreadUiState.Ready ?: return null
    if (!ready.verifiedConversationIdentityResolved) return null
    val identity = ready.verifiedConversationIdentity ?: return null
    if (
        !ready.coverage.verifiedComplete ||
        identity.providerThreadId != providerThreadId ||
        identity.generationId != ready.coverage.generationId
    ) {
        return null
    }
    return runCatching {
        AppearanceScope.Conversation(
            participantSetKey = AppearanceParticipantSetKey.fromParticipants(identity.participants),
            providerThreadId = identity.providerThreadId,
        )
    }.getOrNull()
}

internal fun trustedConversationSubscriptionScope(
    providerThreadId: ProviderThreadId,
    state: ThreadUiState,
): ConversationSubscriptionScope? {
    val ready = state as? ThreadUiState.Ready ?: return null
    if (!ready.verifiedConversationIdentityResolved) return null
    val identity = ready.verifiedConversationIdentity ?: return null
    if (
        !ready.coverage.verifiedComplete ||
        identity.providerThreadId != providerThreadId ||
        identity.generationId != ready.coverage.generationId
    ) {
        return null
    }
    return runCatching {
        ConversationSubscriptionScope(
            participantSetKey = ConversationSubscriptionParticipantSetKey.fromParticipants(
                identity.participants,
            ),
            providerThreadId = identity.providerThreadId,
        )
    }.getOrNull()
}

internal fun trustedMessageSignatureConversationKey(
    providerThreadId: ProviderThreadId,
    state: ThreadUiState,
): MessageSignatureConversationKey? {
    val ready = state as? ThreadUiState.Ready ?: return null
    if (!ready.verifiedConversationIdentityResolved) return null
    val identity = ready.verifiedConversationIdentity ?: return null
    if (
        !ready.coverage.verifiedComplete ||
        identity.providerThreadId != providerThreadId ||
        identity.generationId != ready.coverage.generationId
    ) return null
    return runCatching {
        MessageSignatureConversationKey.fromParticipants(identity.participants)
    }.getOrNull()
}

private sealed interface AppConversationSubscriptionPreferenceState {
    data object Loading : AppConversationSubscriptionPreferenceState
    data object NoPreference : AppConversationSubscriptionPreferenceState
    data class Stored(
        val preference: ConversationSubscriptionPreference,
    ) : AppConversationSubscriptionPreferenceState
    data object Failed : AppConversationSubscriptionPreferenceState
    data object Unavailable : AppConversationSubscriptionPreferenceState
}

private fun ConversationSubscriptionRepositoryResult<ConversationSubscriptionPreference>
    .toAppPreferenceState(): AppConversationSubscriptionPreferenceState = when (this) {
    is ConversationSubscriptionRepositoryResult.Success ->
        AppConversationSubscriptionPreferenceState.Stored(value)
    ConversationSubscriptionRepositoryResult.NotFound ->
        AppConversationSubscriptionPreferenceState.NoPreference
    ConversationSubscriptionRepositoryResult.StaleWrite,
    ConversationSubscriptionRepositoryResult.CorruptData,
    ConversationSubscriptionRepositoryResult.InvalidTimestamp,
    is ConversationSubscriptionRepositoryResult.StorageFailure,
    -> AppConversationSubscriptionPreferenceState.Failed
}

private fun resolveConversationSubscriptionUiState(
    ready: ThreadUiState.Ready?,
    preferenceState: AppConversationSubscriptionPreferenceState,
    saving: Boolean,
): ConversationSubscriptionUiState {
    val options = ready?.activeSubscriptions.orEmpty()
        .filter { it.smsCapable }
        .distinctBy { it.id }
    val stored = preferenceState as? AppConversationSubscriptionPreferenceState.Stored
    val selected = when (preferenceState) {
        AppConversationSubscriptionPreferenceState.NoPreference -> ready?.activeSubscription
            ?.takeIf { associated -> options.any { it.id == associated.id } }
        is AppConversationSubscriptionPreferenceState.Stored -> options.singleOrNull {
            it.id == preferenceState.preference.subscriptionId
        }
        AppConversationSubscriptionPreferenceState.Failed,
        AppConversationSubscriptionPreferenceState.Loading,
        AppConversationSubscriptionPreferenceState.Unavailable,
        -> null
    }
    return ConversationSubscriptionUiState(
        options = options,
        selected = selected,
        loading = preferenceState == AppConversationSubscriptionPreferenceState.Loading ||
            preferenceState == AppConversationSubscriptionPreferenceState.Unavailable,
        saving = saving,
        rememberedSelectionUnavailable = stored != null && selected == null,
        storageFailed = preferenceState == AppConversationSubscriptionPreferenceState.Failed,
    )
}

private fun AppAppearanceState.activeProfileName(canonicalName: String): String = activeProfileId
    ?.let { activeId -> profiles.firstOrNull { it.id == activeId } }
    ?.name
    ?: canonicalName

internal fun shouldDismissScopedEditor(
    observedGeneration: Long,
    currentGeneration: Long,
): Boolean = observedGeneration != currentGeneration

internal fun shouldClearConversationScopedEditorTarget(
    openEditorTarget: String?,
    currentPrivateRestorationKey: String?,
    identityLookupComplete: Boolean,
): Boolean = identityLookupComplete &&
    openEditorTarget != null &&
    openEditorTarget != currentPrivateRestorationKey

internal fun isConversationIdentityLookupComplete(state: ThreadUiState): Boolean = when (state) {
    ThreadUiState.Loading -> false
    is ThreadUiState.Failed -> true
    is ThreadUiState.Ready -> state.verifiedConversationIdentityResolved
}

/** App-private SavedState key only. The returned value must never be logged, displayed, or exported. */
internal fun AppearanceScope.privateScopedAppearanceRestorationKey(): String = when (this) {
    is AppearanceScope.Screen -> "screen:${screen.storageCode}"
    is AppearanceScope.Conversation ->
        "conversation:${providerThreadId.value}:${participantSetKey.toPrivateStorageToken()}"
}

private data class SavedDraftRestoration(
    val token: DraftRestorationToken? = null,
)

private val SAVED_DRAFT_RESTORATION_SAVER: Saver<SavedDraftRestoration, Bundle> = Saver(
    save = { state ->
        Bundle().apply {
            val token = state.token
            putBoolean(SAVED_DRAFT_TOKEN_PRESENT_KEY, token != null)
            if (token != null) {
                putString(SAVED_DRAFT_BODY_KEY, token.content.body)
                putString(SAVED_DRAFT_SUBJECT_KEY, token.content.subject)
                val hasBase = token.expectedDraftId != null
                putBoolean(SAVED_DRAFT_BASE_PRESENT_KEY, hasBase)
                if (hasBase) {
                    putLong(SAVED_DRAFT_ID_KEY, checkNotNull(token.expectedDraftId).value)
                    putLong(
                        SAVED_DRAFT_REVISION_KEY,
                        checkNotNull(token.expectedRevision).updatedTimestampMillis,
                    )
                }
            }
        }
    },
    restore = { bundle ->
        if (!bundle.getBoolean(SAVED_DRAFT_TOKEN_PRESENT_KEY)) {
            SavedDraftRestoration()
        } else {
            runCatching {
                val hasBase = bundle.getBoolean(SAVED_DRAFT_BASE_PRESENT_KEY)
                SavedDraftRestoration(
                    DraftRestorationToken(
                        content = DraftEditorContent(
                            body = bundle.getString(SAVED_DRAFT_BODY_KEY),
                            subject = bundle.getString(SAVED_DRAFT_SUBJECT_KEY),
                        ),
                        expectedDraftId = if (hasBase) {
                            DraftId(bundle.getLong(SAVED_DRAFT_ID_KEY))
                        } else {
                            null
                        },
                        expectedRevision = if (hasBase) {
                            DraftRevision(bundle.getLong(SAVED_DRAFT_REVISION_KEY))
                        } else {
                            null
                        },
                    ),
                )
            }.getOrDefault(SavedDraftRestoration())
        }
    },
)

private fun DraftWriteStatus.Active.toRestorationToken(): DraftRestorationToken =
    DraftRestorationToken(
        content = latest,
        expectedDraftId = acknowledgedDraftId,
        expectedRevision = acknowledgedRevision,
    )

private fun DraftWriteStatus.Failed.toRestorationToken(): DraftRestorationToken =
    DraftRestorationToken(
        content = latest,
        expectedDraftId = acknowledgedDraftId,
        expectedRevision = acknowledgedRevision,
    )

internal fun FrozenDraftSnapshot.toExactRestorationToken(): DraftRestorationToken =
    DraftRestorationToken(
        content = content,
        expectedDraftId = draftId,
        expectedRevision = revision,
    )

private fun DraftWriteStatus.toComposerUiState(
    restoredBody: String,
    threadState: ThreadUiState,
    sendObservation: ThreadSmsSendObservation,
    sendAttemptInFlight: Boolean,
    segmentCount: Int?,
    unsignedSegmentCount: Int? = segmentCount,
    signatureApplied: Boolean = false,
    signatureStateAllowsSend: Boolean = true,
    selectedSubscriptionAvailable: Boolean,
    scheduleObservation: ScheduledSmsObservation = ScheduledSmsObservation.None,
    scheduleAttemptInFlight: Boolean = false,
    sendDelayObservation: SendDelayObservation = SendDelayObservation.None,
    sendDelayAttemptInFlight: Boolean = false,
    permanentDeletionActive: Boolean = false,
): ComposerUiState {
    val body = when (this) {
        DraftWriteStatus.Loading -> restoredBody
        is DraftWriteStatus.Active -> latest.body.orEmpty()
        is DraftWriteStatus.Failed -> latest.body.orEmpty()
    }
    val saving = this is DraftWriteStatus.Loading ||
        (this is DraftWriteStatus.Active && this.saving)
    val failed = this is DraftWriteStatus.Failed
    val ready = threadState as? ThreadUiState.Ready
    val verifiedIdentity = ready?.verifiedConversationIdentity
    val unavailableReason = when {
        sendObservation.phase == ThreadSmsSendPhase.RECOVERY_PENDING ->
            ComposerUnavailableReason.RECOVERY_PENDING
        sendDelayObservation is SendDelayObservation.Loading ->
            ComposerUnavailableReason.RECOVERY_PENDING
        permanentDeletionActive -> ComposerUnavailableReason.PERMANENT_DELETION_ACTIVE
        failed || saving ->
            ComposerUnavailableReason.DRAFT_NOT_DURABLE
        body.isBlank() -> ComposerUnavailableReason.EMPTY_MESSAGE
        (this as? DraftWriteStatus.Active)?.acknowledgedRevision == null ->
            ComposerUnavailableReason.DRAFT_NOT_DURABLE
        !signatureStateAllowsSend -> ComposerUnavailableReason.SIGNATURE_STATE_UNAVAILABLE
        ready == null || !ready.verifiedConversationIdentityResolved || verifiedIdentity == null ->
            ComposerUnavailableReason.CONVERSATION_UNVERIFIED
        verifiedIdentity.participants.size != 1 ->
            ComposerUnavailableReason.GROUP_REQUIRES_MMS
        !selectedSubscriptionAvailable ->
            ComposerUnavailableReason.SUBSCRIPTION_UNAVAILABLE
        segmentCount == null -> ComposerUnavailableReason.MESSAGING_UNAVAILABLE
        segmentCount != 1 -> ComposerUnavailableReason.MULTIPART_UNAVAILABLE
        else -> null
    }
    val sendState = when {
        scheduleAttemptInFlight || scheduleObservation !is ScheduledSmsObservation.None ->
            ComposerSendState.UNAVAILABLE
        sendDelayObservation is SendDelayObservation.Pending ->
            ComposerSendState.DELAY_PENDING
        sendDelayObservation is SendDelayObservation.ReviewRequired ->
            ComposerSendState.DELAY_REVIEW
        sendAttemptInFlight || sendDelayAttemptInFlight ||
            sendDelayObservation is SendDelayObservation.Dispatching ||
            sendObservation.phase == ThreadSmsSendPhase.SENDING ->
            ComposerSendState.SENDING
        sendObservation.phase == ThreadSmsSendPhase.SUBMISSION_UNKNOWN ->
            ComposerSendState.SUBMISSION_UNKNOWN
        (this as? DraftWriteStatus.Active)?.frozenForSend == true ->
            ComposerSendState.SENDING
        unavailableReason != null -> ComposerSendState.UNAVAILABLE
        sendObservation.phase == ThreadSmsSendPhase.KNOWN_UNSENT ->
            ComposerSendState.KNOWN_UNSENT
        else -> ComposerSendState.READY
    }
    return ComposerUiState(
        body = body,
        saving = saving,
        failed = failed,
        sendState = sendState,
        unavailableReason = unavailableReason.takeIf { sendState == ComposerSendState.UNAVAILABLE },
        segmentCount = segmentCount,
        unsignedSegmentCount = unsignedSegmentCount,
        signatureApplied = signatureApplied,
        scheduleState = scheduleObservation.toComposerScheduleState(),
        sendDelayDueTimestampMillis = when (sendState) {
            ComposerSendState.DELAY_PENDING ->
                (sendDelayObservation as SendDelayObservation.Pending).dueTimestampMillis
            ComposerSendState.DELAY_REVIEW ->
                (sendDelayObservation as SendDelayObservation.ReviewRequired).dueTimestampMillis
            else -> null
        },
    )
}

private fun ScheduledSmsObservation.toComposerScheduleState(): ComposerScheduleState = when (this) {
    ScheduledSmsObservation.Loading -> ComposerScheduleState.Loading
    ScheduledSmsObservation.None -> ComposerScheduleState.None
    is ScheduledSmsObservation.Pending -> ComposerScheduleState.Pending(
        dueTimestampMillis = dueTimestampMillis,
        exact = precision == ScheduledSmsPrecision.EXACT,
    )
    is ScheduledSmsObservation.Dispatching ->
        ComposerScheduleState.Dispatching(dueTimestampMillis)
    is ScheduledSmsObservation.ReviewRequired ->
        ComposerScheduleState.ReviewRequired(dueTimestampMillis)
}

private fun PermanentDeletionObservation.toDeletionUiState(): PermanentDeletionUiState = when (this) {
    PermanentDeletionObservation.Loading -> PermanentDeletionUiState.Loading
    PermanentDeletionObservation.None,
    is PermanentDeletionObservation.Completed,
    -> PermanentDeletionUiState.None
    is PermanentDeletionObservation.Pending -> PermanentDeletionUiState.Pending(
        targetKind = targetKind.toDeletionUiKind(),
        providerMessageId = providerMessageId,
        dueTimestampMillis = dueTimestampMillis,
    )
    is PermanentDeletionObservation.Committing -> PermanentDeletionUiState.Committing(
        targetKind = targetKind.toDeletionUiKind(),
    )
    is PermanentDeletionObservation.ReviewRequired -> PermanentDeletionUiState.ReviewRequired(
        targetKind = targetKind.toDeletionUiKind(),
        commitMayHaveStarted = commitMayHaveStarted,
    )
}

private fun PermanentDeletionTargetKind.toDeletionUiKind(): PermanentDeletionTargetUiKind =
    when (this) {
        PermanentDeletionTargetKind.MESSAGE -> PermanentDeletionTargetUiKind.MESSAGE
        PermanentDeletionTargetKind.THREAD -> PermanentDeletionTargetUiKind.THREAD
    }

private fun showScheduledSmsDateTimePicker(
    context: Context,
    onSelected: (Long) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val initial = Instant.ofEpochMilli(
        System.currentTimeMillis() + DEFAULT_SCHEDULE_LEAD_MILLIS,
    ).atZone(zone)
    DatePickerDialog(
        context,
        { _, year, month, day ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    val local = LocalDateTime.of(
                        LocalDate.of(year, month + 1, day),
                        LocalTime.of(hour, minute),
                    )
                    val offsets = zone.rules.getValidOffsets(local)
                    val due = offsets.singleOrNull()?.let { offset ->
                        local.toInstant(offset).toEpochMilli()
                    }
                    if (
                        due == null ||
                        due < System.currentTimeMillis() + MINIMUM_PICKER_LEAD_MILLIS
                    ) {
                        Toast.makeText(context, R.string.schedule_time_invalid, Toast.LENGTH_LONG).show()
                    } else {
                        onSelected(due)
                    }
                },
                initial.hour,
                initial.minute,
                DateFormat.is24HourFormat(context),
            ).show()
        },
        initial.year,
        initial.monthValue - 1,
        initial.dayOfMonth,
    ).apply {
        datePicker.minDate = System.currentTimeMillis()
        datePicker.maxDate = System.currentTimeMillis() + MAXIMUM_PICKER_LEAD_MILLIS
    }.show()
}

internal fun shouldUnfreezeComposerAsRefused(
    coordinatorEntered: Boolean,
    attempt: ThreadSmsSendAttempt?,
): Boolean = !coordinatorEntered || attempt == ThreadSmsSendAttempt.REFUSED

private fun isConservativeDialAddress(address: ParticipantAddress): Boolean {
    val value = address.value
    return value.length <= MAXIMUM_DIAL_ADDRESS_CHARACTERS &&
        value.any(Char::isDigit) &&
        value.all { character -> character.isDigit() || character in DIAL_PUNCTUATION }
}

private fun launchSystemDialer(context: Context, address: ParticipantAddress) {
    if (!isConservativeDialAddress(address)) return
    val intent = Intent(
        Intent.ACTION_DIAL,
        Uri.fromParts("tel", address.value, null),
    )
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // A missing system dialer leaves the conversation unchanged.
    } catch (_: SecurityException) {
        // ACTION_DIAL should not need CALL_PHONE; fail closed on unusual builds.
    }
}

private fun AppRoute.saveableScreenKey(): String = when (this) {
    AppRoute.Inbox -> "inbox"
    AppRoute.Appearance -> "appearance"
    is AppRoute.Search -> "search"
    is AppRoute.Thread -> "thread-entry-$stateEntryId"
}

private val APP_ROUTE_STACK_SAVER: Saver<List<AppRoute>, Bundle> = Saver(
    save = { routes ->
        Bundle().apply {
            putParcelableArrayList(ROUTES_KEY, ArrayList(routes.map(::saveRoute)))
        }
    },
    restore = { bundle ->
        @Suppress("DEPRECATION")
        bundle.getParcelableArrayList<Bundle>(ROUTES_KEY)
            ?.take(MAXIMUM_RETAINED_ROUTES)
            ?.map(::restoreRoute)
            ?.takeIf { routes -> routes.none { it == null } }
            ?.filterNotNull()
            ?.let(::normalizeRestoredRoutes)
            ?.let(::repairThreadStateEntryIds)
    },
)

internal fun repairThreadStateEntryIds(routes: List<AppRoute>): List<AppRoute> {
    val retainedIds = HashSet<Long>()
    val keepExisting = routes.map { route ->
        route !is AppRoute.Thread ||
            (route.stateEntryId > 0L && retainedIds.add(route.stateEntryId))
    }
    val allocatedIds = HashSet(retainedIds)
    var candidate = 1L
    return routes.mapIndexed { index, route ->
        if (route !is AppRoute.Thread || keepExisting[index]) return@mapIndexed route
        while (candidate in allocatedIds) {
            check(candidate < Long.MAX_VALUE) { "Thread route state entry IDs are exhausted" }
            candidate += 1L
        }
        route.copy(stateEntryId = candidate).also {
            allocatedIds += candidate
        }
    }
}

private fun nextThreadStateEntryId(routes: List<AppRoute>): Long {
    val allocatedIds = routes.asSequence()
        .filterIsInstance<AppRoute.Thread>()
        .mapTo(HashSet()) { it.stateEntryId }
    var candidate = 1L
    while (candidate in allocatedIds) {
        check(candidate < Long.MAX_VALUE) { "Thread route state entry IDs are exhausted" }
        candidate += 1L
    }
    return candidate
}

internal fun normalizeRestoredRoutes(routes: List<AppRoute>): List<AppRoute>? = routes
    .takeIf { candidate ->
        candidate.isNotEmpty() &&
            candidate.first() == AppRoute.Inbox &&
            candidate.drop(1).none { it == AppRoute.Inbox }
    }
    ?.take(MAXIMUM_RETAINED_ROUTES)

private fun saveRoute(route: AppRoute): Bundle = Bundle().apply {
    when (route) {
        AppRoute.Inbox -> putString(ROUTE_TYPE_KEY, ROUTE_INBOX)
        AppRoute.Appearance -> putString(ROUTE_TYPE_KEY, ROUTE_APPEARANCE)
        is AppRoute.Search -> {
            putString(ROUTE_TYPE_KEY, ROUTE_SEARCH)
            putString(ROUTE_QUERY_KEY, route.query)
        }
        is AppRoute.Thread -> {
            putString(ROUTE_TYPE_KEY, ROUTE_THREAD)
            putLong(ROUTE_THREAD_ID_KEY, route.providerThreadId.value)
            putLong(ROUTE_THREAD_STATE_ENTRY_KEY, route.stateEntryId)
            route.anchor?.let { anchor ->
                putLong(ROUTE_ANCHOR_ROW_KEY, anchor.localRowId)
                putString(ROUTE_ANCHOR_KIND_KEY, anchor.providerId.kind.name)
                putLong(ROUTE_ANCHOR_PROVIDER_ID_KEY, anchor.providerId.value)
            }
        }
    }
}

private fun restoreRoute(bundle: Bundle): AppRoute? = try {
    when (bundle.getString(ROUTE_TYPE_KEY)) {
        ROUTE_INBOX -> AppRoute.Inbox
        ROUTE_APPEARANCE -> AppRoute.Appearance
        ROUTE_SEARCH -> AppRoute.Search(bundle.getString(ROUTE_QUERY_KEY).orEmpty())
        ROUTE_THREAD -> {
            val threadId = ProviderThreadId(bundle.getLong(ROUTE_THREAD_ID_KEY))
            val anchorRow = bundle.getLong(ROUTE_ANCHOR_ROW_KEY, INVALID_SAVED_ID)
            val anchor = if (anchorRow > 0L) {
                SearchAnchor(
                    localRowId = anchorRow,
                    providerId = ProviderMessageId(
                        kind = ProviderKind.valueOf(checkNotNull(bundle.getString(ROUTE_ANCHOR_KIND_KEY))),
                        value = bundle.getLong(ROUTE_ANCHOR_PROVIDER_ID_KEY),
                    ),
                    providerThreadId = threadId,
                )
            } else {
                null
            }
            AppRoute.Thread(
                providerThreadId = threadId,
                anchor = anchor,
                stateEntryId = bundle.getLong(ROUTE_THREAD_STATE_ENTRY_KEY),
            )
        }
        else -> null
    }
} catch (_: IllegalArgumentException) {
    null
} catch (_: NullPointerException) {
    null
}

private val DIAL_PUNCTUATION: Set<Char> = setOf('+', '*', '#', '(', ')', '-', '.', ' ')
private const val MAXIMUM_DIAL_ADDRESS_CHARACTERS: Int = 64
private const val MAXIMUM_RETAINED_ROUTES: Int = 16
private const val ROUTES_KEY: String = "routes"
private const val ROUTE_TYPE_KEY: String = "type"
private const val ROUTE_QUERY_KEY: String = "query"
private const val ROUTE_THREAD_ID_KEY: String = "thread_id"
private const val ROUTE_THREAD_STATE_ENTRY_KEY: String = "thread_state_entry_id"
private const val ROUTE_ANCHOR_ROW_KEY: String = "anchor_row"
private const val ROUTE_ANCHOR_KIND_KEY: String = "anchor_kind"
private const val ROUTE_ANCHOR_PROVIDER_ID_KEY: String = "anchor_provider_id"
private const val SAVED_DRAFT_TOKEN_PRESENT_KEY: String = "draft_token_present"
private const val SAVED_DRAFT_BODY_KEY: String = "draft_body"
private const val SAVED_DRAFT_SUBJECT_KEY: String = "draft_subject"
private const val SAVED_DRAFT_BASE_PRESENT_KEY: String = "draft_base_present"
private const val SAVED_DRAFT_ID_KEY: String = "draft_id"
private const val SAVED_DRAFT_REVISION_KEY: String = "draft_revision"
private const val ROUTE_INBOX: String = "inbox"
private const val ROUTE_APPEARANCE: String = "appearance"
private const val ROUTE_SEARCH: String = "search"
private const val ROUTE_THREAD: String = "thread"
private const val INVALID_SAVED_ID: Long = -1L
private const val DEFAULT_SCHEDULE_LEAD_MILLIS: Long = 15L * 60L * 1_000L
private const val MINIMUM_PICKER_LEAD_MILLIS: Long = 2L * 60L * 1_000L
private const val MAXIMUM_PICKER_LEAD_MILLIS: Long = 365L * 24L * 60L * 60L * 1_000L
