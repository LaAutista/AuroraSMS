// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import kotlinx.coroutines.flow.flowOf
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
import org.aurorasms.app.drafts.DraftWriteStatus
import org.aurorasms.core.index.SearchAnchor
import org.aurorasms.core.index.SearchHit
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.model.asConversationId
import org.aurorasms.core.model.asProviderThreadId
import org.aurorasms.core.designsystem.AuroraMaterialProfile
import org.aurorasms.core.designsystem.AuroraMaterialTheme
import org.aurorasms.core.state.DraftIdentity
import org.aurorasms.core.state.AppearanceParticipantSetKey
import org.aurorasms.core.state.AppearanceScope
import org.aurorasms.core.state.AppearanceScreenScope
import org.aurorasms.feature.conversations.ComposerUiState
import org.aurorasms.feature.conversations.InboxScreen
import org.aurorasms.feature.conversations.InboxStateHolder
import org.aurorasms.feature.conversations.InboxUiState
import org.aurorasms.feature.conversations.SearchScreen
import org.aurorasms.feature.conversations.SearchStateHolder
import org.aurorasms.feature.conversations.SearchUiState
import org.aurorasms.feature.conversations.ThreadScreen
import org.aurorasms.feature.conversations.ThreadStateHolder
import org.aurorasms.feature.conversations.ThreadUiState

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
        )
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
    LaunchedEffect(threadState) {
        if (threadState is ThreadUiState.Ready) {
            withFrameNanos { }
            PresentationTrace.end(
                if (route.anchor == null) PresentationTrace.THREAD_OPEN else PresentationTrace.EXACT_JUMP,
            )
        }
    }

    var savedBody by rememberSaveable(route.providerThreadId.value) { mutableStateOf<String?>(null) }
    val writer = remember(services, route.providerThreadId) {
        services.createDraftWriter(
            identity = DraftIdentity.ProviderThread(route.providerThreadId),
            restoredUnacknowledged = savedBody?.let { body ->
                DraftEditorContent(body = body.takeIf(String::isNotEmpty), subject = null)
            },
        )
    }
    DisposableEffect(writer) { onDispose { services.releaseDraftWriter(writer) } }
    val draftStatus by writer.status.collectAsStateWithLifecycle()
    LaunchedEffect(draftStatus) {
        when (val status = draftStatus) {
            DraftWriteStatus.Loading -> Unit
            is DraftWriteStatus.Active -> savedBody = status.latest.body.orEmpty()
            is DraftWriteStatus.Failed -> savedBody = status.latest.body.orEmpty()
        }
    }
    val composer = draftStatus.toComposerUiState(savedBody.orEmpty())
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
            attachmentRepository = services.mmsAttachmentRepository,
            previewLoader = services.previewLoader,
            onBack = onBack,
            onOpenSearch = onOpenSearch,
            conversationAppearanceAvailable = conversationScope != null,
            onOpenConversationAppearance = {
                openEditorTarget = privateRestorationKey.takeIf { conversationScope != null }
                wallpaperEditorOpen = false
            },
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
                if (writer.submit(content)) savedBody = body
            },
        )
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

private fun DraftWriteStatus.toComposerUiState(restoredBody: String): ComposerUiState = when (this) {
    DraftWriteStatus.Loading -> ComposerUiState(body = restoredBody, saving = true, failed = false)
    is DraftWriteStatus.Active -> ComposerUiState(
        body = latest.body.orEmpty(),
        saving = saving,
        failed = false,
    )
    is DraftWriteStatus.Failed -> ComposerUiState(
        body = latest.body.orEmpty(),
        saving = false,
        failed = true,
    )
}

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
private const val ROUTE_INBOX: String = "inbox"
private const val ROUTE_APPEARANCE: String = "appearance"
private const val ROUTE_SEARCH: String = "search"
private const val ROUTE_THREAD: String = "thread"
private const val INVALID_SAVED_ID: Long = -1L
