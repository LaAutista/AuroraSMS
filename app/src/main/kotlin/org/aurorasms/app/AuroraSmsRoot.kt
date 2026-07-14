// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import org.aurorasms.core.state.DraftIdentity
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
    pendingConversationId: ConversationId?,
    diagnosticsAvailable: Boolean,
    contactsPermissionGranted: Boolean,
    onOpenDiagnostics: () -> Unit,
    onRequestContactsPermission: () -> Unit,
    onPendingConversationConsumed: () -> Unit,
    onOpenedConversationChanged: (ConversationId?) -> Unit,
    onInboxReady: () -> Unit,
) {
    val initialStack = remember {
        buildList {
            add(AppRoute.Inbox)
            pendingConversationId?.let { id -> add(AppRoute.Thread(id.asProviderThreadId())) }
        }
    }
    var routes by rememberSaveable(stateSaver = APP_ROUTE_STACK_SAVER) {
        mutableStateOf(initialStack)
    }
    var inboxDrawReported by rememberSaveable { mutableStateOf(false) }
    val saveableScreens = rememberSaveableStateHolder()
    val route = routes.last()
    val context = LocalContext.current

    fun push(next: AppRoute) {
        routes = listOf(AppRoute.Inbox) +
            (routes.drop(1) + next).takeLast(MAXIMUM_RETAINED_ROUTES - 1)
    }

    fun pop() {
        if (routes.size > 1) routes = routes.dropLast(1)
    }

    fun replaceCurrent(next: AppRoute) {
        routes = routes.dropLast(1) + next
    }

    LaunchedEffect(pendingConversationId) {
        val pending = pendingConversationId ?: return@LaunchedEffect
        routes = listOf(AppRoute.Inbox, AppRoute.Thread(pending.asProviderThreadId()))
        onPendingConversationConsumed()
    }
    LaunchedEffect(route) {
        onOpenedConversationChanged(
            (route as? AppRoute.Thread)?.providerThreadId?.asConversationId(),
        )
    }

    saveableScreens.SaveableStateProvider(route.saveableScreenKey()) {
        when (route) {
            AppRoute.Inbox -> InboxRoute(
                container = container,
                diagnosticsAvailable = diagnosticsAvailable,
                contactsPermissionGranted = contactsPermissionGranted,
                onOpenConversation = {
                    PresentationTrace.begin(PresentationTrace.THREAD_OPEN)
                    push(AppRoute.Thread(it))
                },
                onOpenSearch = { push(AppRoute.Search()) },
                onOpenDiagnostics = onOpenDiagnostics,
                onRequestContactsPermission = onRequestContactsPermission,
                onReady = {
                    if (!inboxDrawReported) {
                        inboxDrawReported = true
                        onInboxReady()
                    }
                },
            )
            is AppRoute.Search -> SearchRoute(
                container = container,
                route = route,
                onQueryChanged = { replaceCurrent(AppRoute.Search(it)) },
                onOpenHit = { hit ->
                    PresentationTrace.begin(PresentationTrace.EXACT_JUMP)
                    push(
                        AppRoute.Thread(
                            providerThreadId = hit.providerThreadId,
                            anchor = SearchAnchor(
                                localRowId = hit.localRowId,
                                providerId = hit.providerId,
                                providerThreadId = hit.providerThreadId,
                            ),
                        ),
                    )
                },
                onOpenConversation = { push(AppRoute.Thread(it)) },
                onBack = ::pop,
            )
            is AppRoute.Thread -> ThreadRoute(
                container = container,
                route = route,
                context = context,
                onOpenSearch = { push(AppRoute.Search()) },
                onBack = ::pop,
            )
        }
    }
}

@Composable
private fun InboxRoute(
    container: AppContainer,
    diagnosticsAvailable: Boolean,
    contactsPermissionGranted: Boolean,
    onOpenConversation: (ProviderThreadId) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onRequestContactsPermission: () -> Unit,
    onReady: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val holder = remember(container, scope) {
        InboxStateHolder(
            repository = container.conversationRepository,
            contactCache = container.contactCache,
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
    InboxScreen(
        state = state,
        diagnosticsAvailable = diagnosticsAvailable,
        contactsPermissionGranted = contactsPermissionGranted,
        onOpenConversation = onOpenConversation,
        onOpenSearch = onOpenSearch,
        onOpenDiagnostics = onOpenDiagnostics,
        onRequestContactsPermission = onRequestContactsPermission,
        onRetry = holder::reload,
        onLoadOlder = holder::loadOlder,
        onAtNewestChanged = holder::markUserAtNewest,
        onAcceptPending = holder::acceptPendingNewer,
        onViewportChanged = holder::onViewportChanged,
        onAnchorRestored = holder::anchorRestored,
    )
}

@Composable
private fun SearchRoute(
    container: AppContainer,
    route: AppRoute.Search,
    onQueryChanged: (String) -> Unit,
    onOpenHit: (SearchHit) -> Unit,
    onOpenConversation: (ProviderThreadId) -> Unit,
    onBack: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val holder = remember(container, scope) {
        SearchStateHolder(messageIndex = container.messageIndex, scope = scope)
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
    container: AppContainer,
    route: AppRoute.Thread,
    context: Context,
    onOpenSearch: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val holder = remember(container, route.providerThreadId, route.anchor, scope) {
        ThreadStateHolder(
            providerThreadId = route.providerThreadId,
            repository = container.threadTimelineRepository,
            conversationRepository = container.conversationRepository,
            messageIndex = container.messageIndex,
            contactCache = container.contactCache,
            subscriptionRepository = container.subscriptionRepository,
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
    val writer = remember(container, route.providerThreadId) {
        container.createDraftWriter(
            identity = DraftIdentity.ProviderThread(route.providerThreadId),
            restoredUnacknowledged = savedBody?.let { body ->
                DraftEditorContent(body = body.takeIf(String::isNotEmpty), subject = null)
            },
        )
    }
    DisposableEffect(writer) { onDispose { container.releaseDraftWriter(writer) } }
    val draftStatus by writer.status.collectAsStateWithLifecycle()
    LaunchedEffect(draftStatus) {
        when (val status = draftStatus) {
            DraftWriteStatus.Loading -> Unit
            is DraftWriteStatus.Active -> savedBody = status.latest.body.orEmpty()
            is DraftWriteStatus.Failed -> savedBody = status.latest.body.orEmpty()
        }
    }
    val composer = draftStatus.toComposerUiState(savedBody.orEmpty())

    ThreadScreen(
        state = threadState,
        composer = composer,
        attachmentRepository = container.mmsAttachmentRepository,
        previewLoader = container.previewLoader,
        onBack = onBack,
        onOpenSearch = onOpenSearch,
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
    is AppRoute.Search -> "search"
    is AppRoute.Thread -> "thread-${providerThreadId.value}"
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
            ?.mapNotNull(::restoreRoute)
            ?.takeIf(List<AppRoute>::isNotEmpty)
    },
)

private fun saveRoute(route: AppRoute): Bundle = Bundle().apply {
    when (route) {
        AppRoute.Inbox -> putString(ROUTE_TYPE_KEY, ROUTE_INBOX)
        is AppRoute.Search -> {
            putString(ROUTE_TYPE_KEY, ROUTE_SEARCH)
            putString(ROUTE_QUERY_KEY, route.query)
        }
        is AppRoute.Thread -> {
            putString(ROUTE_TYPE_KEY, ROUTE_THREAD)
            putLong(ROUTE_THREAD_ID_KEY, route.providerThreadId.value)
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
            AppRoute.Thread(threadId, anchor)
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
private const val ROUTE_ANCHOR_ROW_KEY: String = "anchor_row"
private const val ROUTE_ANCHOR_KIND_KEY: String = "anchor_kind"
private const val ROUTE_ANCHOR_PROVIDER_ID_KEY: String = "anchor_provider_id"
private const val ROUTE_INBOX: String = "inbox"
private const val ROUTE_SEARCH: String = "search"
private const val ROUTE_THREAD: String = "thread"
private const val INVALID_SAVED_ID: Long = -1L
