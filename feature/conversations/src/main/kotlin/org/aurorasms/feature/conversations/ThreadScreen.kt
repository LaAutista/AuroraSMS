// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import org.aurorasms.core.designsystem.AuroraGlyph
import org.aurorasms.core.designsystem.AuroraIconAction
import org.aurorasms.core.designsystem.LocalAuroraVisualTokens
import org.aurorasms.core.index.conversation.ConversationSummary
import org.aurorasms.core.index.timeline.TimelineMessage
import org.aurorasms.core.index.timeline.TimelineMessageContent
import org.aurorasms.core.designsystem.LocalAuroraMaterialTokens
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.telephony.MmsAttachmentListResult
import org.aurorasms.core.telephony.MmsAttachmentRepository
import org.aurorasms.core.telephony.ResolvedContact
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun ThreadScreen(
    state: ThreadUiState,
    composer: ComposerUiState,
    subscriptionSelection: ConversationSubscriptionUiState = ConversationSubscriptionUiState(),
    attachmentRepository: MmsAttachmentRepository,
    previewLoader: BoundedPreviewLoader,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    conversationAppearanceAvailable: Boolean,
    onOpenConversationAppearance: () -> Unit,
    isDialable: (ParticipantAddress) -> Boolean,
    onDial: (ParticipantAddress) -> Unit,
    onRetry: () -> Unit,
    onLoadOlder: (WindowAnchor<ProviderMessageId>) -> Unit,
    onLoadNewer: (WindowAnchor<ProviderMessageId>) -> Unit,
    onAtNewestChanged: (Boolean) -> Unit,
    onAcceptPending: () -> Unit,
    onViewportChanged: (List<TimelineMessage>) -> Unit,
    onAnchorRestored: () -> Unit,
    onToggleMessageExpansion: (ProviderMessageId) -> Unit,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit = {},
    onSchedule: () -> Unit = {},
    onCancelSchedule: () -> Unit = {},
    onRequestExactAlarmAccess: () -> Unit = {},
    onSelectSubscription: (AuroraSubscriptionId) -> Unit = {},
    onAcknowledgeSubmissionUnknown: () -> Unit = {},
    timelineBackground: @Composable BoxScope.() -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val visualTokens = LocalAuroraVisualTokens.current
    var composerFocused by remember { mutableStateOf(false) }
    BackHandler {
        if (composerFocused) {
            focusManager.clearFocus()
            keyboard?.hide()
        } else {
            onBack()
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .semantics { testTagsAsResourceId = true }
            .testTag(THREAD_SCREEN_TEST_TAG),
        color = visualTokens.nearBlack,
        contentColor = visualTokens.onIncoming,
    ) {
        Column(modifier = Modifier.imePadding()) {
            ThreadHeader(
                state = state,
                subscriptionSelection = subscriptionSelection,
                onBack = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    onBack()
                },
                onOpenSearch = onOpenSearch,
                conversationAppearanceAvailable = conversationAppearanceAvailable,
                onOpenConversationAppearance = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    onOpenConversationAppearance()
                },
                isDialable = isDialable,
                onDial = onDial,
                onSelectSubscription = onSelectSubscription,
            )
            HorizontalDivider(color = visualTokens.violet.copy(alpha = 0.4f))
            Box(modifier = Modifier.weight(1f)) {
                when (state) {
                    ThreadUiState.Loading -> LoadingPane()
                    is ThreadUiState.Failed -> FailurePane(onRetry)
                    is ThreadUiState.Ready -> {
                        timelineBackground()
                        ThreadReady(
                            state = state,
                            attachmentRepository = attachmentRepository,
                            previewLoader = previewLoader,
                            onLoadOlder = onLoadOlder,
                            onLoadNewer = onLoadNewer,
                            onAtNewestChanged = onAtNewestChanged,
                            onAcceptPending = onAcceptPending,
                            onViewportChanged = onViewportChanged,
                            onAnchorRestored = onAnchorRestored,
                            onToggleMessageExpansion = onToggleMessageExpansion,
                        )
                    }
                }
            }
            Composer(
                state = composer,
                onBodyChanged = onDraftChanged,
                onFocusChanged = { composerFocused = it },
                onSend = onSend,
                onSchedule = onSchedule,
                onCancelSchedule = onCancelSchedule,
                onRequestExactAlarmAccess = onRequestExactAlarmAccess,
                onAcknowledgeSubmissionUnknown = onAcknowledgeSubmissionUnknown,
            )
        }
    }
}

@Composable
private fun ThreadHeader(
    state: ThreadUiState,
    subscriptionSelection: ConversationSubscriptionUiState,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    conversationAppearanceAvailable: Boolean,
    onOpenConversationAppearance: () -> Unit,
    isDialable: (ParticipantAddress) -> Boolean,
    onDial: (ParticipantAddress) -> Unit,
    onSelectSubscription: (AuroraSubscriptionId) -> Unit,
) {
    val visualTokens = LocalAuroraVisualTokens.current
    val ready = state as? ThreadUiState.Ready
    val summary = ready?.conversation
    val title = threadTitle(ready)
    val dialAddress = summary?.participants?.singleOrNull()?.takeIf {
        ready.coverage.verifiedComplete && !summary.participantsTruncated && isDialable(it)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(visualTokens.nearBlack.copy(alpha = 0.98f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuroraIconAction(
            glyph = AuroraGlyph.BACK,
            contentDescription = stringResource(R.string.back),
            onClick = onBack,
            tint = visualTokens.lilacSecondary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = visualTokens.onIncoming,
            )
            SubscriptionSelector(
                state = subscriptionSelection,
                onSelectSubscription = onSelectSubscription,
            )
        }
        if (dialAddress != null) {
            AuroraIconAction(
                glyph = AuroraGlyph.CALL,
                contentDescription = stringResource(R.string.call),
                onClick = { onDial(dialAddress) },
                tint = visualTokens.cyan,
            )
        }
        AuroraIconAction(
            glyph = AuroraGlyph.SEARCH,
            contentDescription = stringResource(R.string.search),
            onClick = onOpenSearch,
            tint = visualTokens.lilacSecondary,
        )
        if (conversationAppearanceAvailable) {
            ThreadMoreMenu(onOpenConversationAppearance = onOpenConversationAppearance)
        }
    }
}

@Composable
private fun SubscriptionSelector(
    state: ConversationSubscriptionUiState,
    onSelectSubscription: (AuroraSubscriptionId) -> Unit,
) {
    val visualTokens = LocalAuroraVisualTokens.current
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = state.selected?.let { subscription ->
        subscriptionLabel(subscription)
    }
    val label = when {
        state.loading -> stringResource(R.string.loading_sim_selection)
        state.saving -> stringResource(R.string.saving_sim_selection)
        state.storageFailed -> stringResource(R.string.sim_selection_unavailable)
        state.rememberedSelectionUnavailable -> stringResource(R.string.remembered_sim_unavailable)
        selectedLabel != null -> selectedLabel
        state.options.isNotEmpty() -> stringResource(R.string.choose_sim)
        else -> stringResource(R.string.no_sms_sim_available)
    }
    Box {
        TextButton(
            modifier = Modifier.testTag(THREAD_SIM_SELECTOR_TEST_TAG),
            enabled = !state.loading && !state.saving && !state.storageFailed &&
                state.options.isNotEmpty(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            onClick = { expanded = true },
        ) {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = if (state.rememberedSelectionUnavailable || state.storageFailed) {
                    MaterialTheme.colorScheme.error
                } else {
                    visualTokens.lilacSecondary
                },
            )
        }
        DropdownMenu(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = visualTokens.menuSurface,
        ) {
            state.options.forEach { subscription ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = subscriptionLabel(subscription),
                            color = visualTokens.onIncoming,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelectSubscription(subscription.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun subscriptionLabel(subscription: org.aurorasms.core.telephony.ActiveSubscription): String =
    if (subscription.displayLabel.isBlank()) {
        stringResource(R.string.sim_number, subscription.slotIndex + 1)
    } else {
        stringResource(
            R.string.sim_number_with_label,
            subscription.slotIndex + 1,
            subscription.displayLabel,
        )
    }

@Composable
private fun ThreadMoreMenu(
    onOpenConversationAppearance: () -> Unit,
) {
    val visualTokens = LocalAuroraVisualTokens.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        AuroraIconAction(
            glyph = AuroraGlyph.MORE,
            contentDescription = stringResource(R.string.more),
            modifier = Modifier.testTag(THREAD_MORE_ACTION_TEST_TAG),
            onClick = { expanded = true },
            tint = visualTokens.lilacSecondary,
        )
        DropdownMenu(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = visualTokens.menuSurface,
        ) {
            DropdownMenuItem(
                modifier = Modifier.testTag(THREAD_APPEARANCE_ACTION_TEST_TAG),
                text = {
                    Text(
                        stringResource(R.string.conversation_appearance),
                        color = visualTokens.onIncoming,
                    )
                },
                onClick = {
                    expanded = false
                    onOpenConversationAppearance()
                },
            )
        }
    }
}

@Composable
private fun threadTitle(state: ThreadUiState.Ready?): String {
    val summary = state?.conversation ?: return stringResource(R.string.conversation)
    val trustworthyParticipants = state.coverage.verifiedComplete && !summary.participantsTruncated
    val addresses = if (trustworthyParticipants) summary.participants else {
        listOfNotNull(summary.latestSenderAddress ?: summary.participants.firstOrNull())
    }
    val names = addresses.map { address -> state.contacts[address]?.displayNameOrAddress ?: address.value }
    if (names.isEmpty()) return stringResource(R.string.unknown_conversation)
    val visible = names.take(MAXIMUM_HEADER_NAMES).joinToString()
    val hidden = names.size - MAXIMUM_HEADER_NAMES
    return if (hidden > 0) stringResource(R.string.participant_overflow, visible, hidden) else visible
}

@Composable
private fun ThreadReady(
    state: ThreadUiState.Ready,
    attachmentRepository: MmsAttachmentRepository,
    previewLoader: BoundedPreviewLoader,
    onLoadOlder: (WindowAnchor<ProviderMessageId>) -> Unit,
    onLoadNewer: (WindowAnchor<ProviderMessageId>) -> Unit,
    onAtNewestChanged: (Boolean) -> Unit,
    onAcceptPending: () -> Unit,
    onViewportChanged: (List<TimelineMessage>) -> Unit,
    onAnchorRestored: () -> Unit,
    onToggleMessageExpansion: (ProviderMessageId) -> Unit,
) {
    val visualTokens = LocalAuroraVisualTokens.current
    val items = state.window.items
    val leadingItemCount = if (state.loadingOlder) 1 else 0
    val listState = rememberLazyListState()
    var initiallyPositioned by remember { mutableStateOf(false) }
    var shouldFollowNewest by rememberSaveable {
        mutableStateOf(state.highlightedMessageId == null)
    }
    var savedVisibleMessageKind by rememberSaveable { mutableStateOf<String?>(null) }
    var savedVisibleMessageValue by rememberSaveable { mutableStateOf<Long?>(null) }
    var savedVisibleScrollOffset by rememberSaveable { mutableStateOf(0) }
    var visibleMessageIds by remember { mutableStateOf(emptySet<ProviderMessageId>()) }
    val messageIndexByUiKey: Map<Any, Int> = remember(items) {
        items.mapIndexed { index, message -> message.providerMessageId.stableUiKey() to index }.toMap()
    }

    LaunchedEffect(
        listState,
        items,
        state.window.olderCursor,
        state.window.newerCursor,
        state.loadingOlder,
        state.loadingNewer,
        initiallyPositioned,
    ) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            VisibleThreadSnapshot(
                items = visible.map { VisibleThreadItem(key = it.key, offset = it.offset) },
                canScrollForward = listState.canScrollForward,
                firstVisibleKey = visible.firstOrNull {
                    it.index == listState.firstVisibleItemIndex
                }?.key,
                firstVisibleScrollOffset = listState.firstVisibleItemScrollOffset,
            )
        }.distinctUntilChanged().collect { snapshot ->
            val atNewest = !snapshot.canScrollForward &&
                state.window.newerCursor == null &&
                !state.window.pendingNewer
            shouldFollowNewest = atNewest
            onAtNewestChanged(atNewest)
            if (snapshot.items.isEmpty()) {
                visibleMessageIds = emptySet()
                onViewportChanged(items.take(MAXIMUM_VIEWPORT_THREAD_ROWS))
                return@collect
            }
            val visibleMessageIndices = snapshot.items.mapNotNull { visibleItem ->
                messageIndexByUiKey[visibleItem.key]
            }
            if (visibleMessageIndices.isEmpty()) {
                visibleMessageIds = emptySet()
                onViewportChanged(items.take(MAXIMUM_VIEWPORT_THREAD_ROWS))
                return@collect
            }
            val firstIndex = visibleMessageIndices.min()
            val lastIndex = visibleMessageIndices.max()
            val firstVisibleMessageIndex = messageIndexByUiKey[snapshot.firstVisibleKey]
            if (initiallyPositioned && !state.loadingOlder && firstVisibleMessageIndex != null) {
                items.getOrNull(firstVisibleMessageIndex)?.providerMessageId?.let { visibleId ->
                    savedVisibleMessageKind = visibleId.kind.name
                    savedVisibleMessageValue = visibleId.value
                    savedVisibleScrollOffset = snapshot.firstVisibleScrollOffset
                }
            }
            visibleMessageIds = visibleMessageIndices.mapNotNullTo(LinkedHashSet()) { index ->
                items.getOrNull(index)?.providerMessageId
            }
            val viewportStart = (firstIndex - THREAD_VIEWPORT_PREFETCH_ROWS).coerceAtLeast(0)
            val viewportEnd = (lastIndex + THREAD_VIEWPORT_PREFETCH_ROWS + 1).coerceAtMost(items.size)
            onViewportChanged(items.subList(viewportStart, viewportEnd).take(MAXIMUM_VIEWPORT_THREAD_ROWS))
            if (
                initiallyPositioned &&
                state.window.olderCursor != null &&
                !state.loadingOlder &&
                firstIndex <= 3
            ) {
                val anchorIndex = firstVisibleMessageIndex ?: return@collect
                val anchor = items.getOrNull(anchorIndex) ?: return@collect
                onLoadOlder(WindowAnchor(anchor.providerMessageId, snapshot.firstVisibleScrollOffset))
            }
            if (
                initiallyPositioned &&
                state.window.newerCursor != null &&
                !state.loadingNewer &&
                lastIndex >= items.lastIndex - 3
            ) {
                val anchorIndex = firstVisibleMessageIndex ?: return@collect
                val anchor = items.getOrNull(anchorIndex) ?: return@collect
                onLoadNewer(WindowAnchor(anchor.providerMessageId, snapshot.firstVisibleScrollOffset))
            }
        }
    }
    LaunchedEffect(items, state.highlightedMessageId, leadingItemCount) {
        if (initiallyPositioned || items.isEmpty()) return@LaunchedEffect
        val savedVisibleMessageId = savedVisibleMessageKind
            ?.let { savedKind -> ProviderKind.entries.firstOrNull { it.name == savedKind } }
            ?.let { savedKind ->
                savedVisibleMessageValue?.let { savedValue -> ProviderMessageId(savedKind, savedValue) }
            }
        val savedIndex = if (shouldFollowNewest) {
            null
        } else {
            savedVisibleMessageId
                ?.let { target -> items.indexOfFirst { it.providerMessageId == target } }
                ?.takeIf { it >= 0 }
        }
        val highlighted = state.highlightedMessageId
            ?.let { target -> items.indexOfFirst { it.providerMessageId == target } }
            ?.takeIf { it >= 0 }
        withFrameNanos { }
        listState.scrollToItem(
            index = (savedIndex ?: highlighted ?: items.lastIndex) + leadingItemCount,
            scrollOffset = if (savedIndex != null) savedVisibleScrollOffset.coerceAtLeast(0) else 0,
        )
        initiallyPositioned = true
    }
    LaunchedEffect(state.restoreAnchor, items) {
        val anchor = state.restoreAnchor ?: return@LaunchedEffect
        val index = items.indexOfFirst { it.providerMessageId == anchor.stableKey }
        if (index >= 0) {
            withFrameNanos { }
            listState.scrollToItem(index + leadingItemCount, anchor.scrollOffsetPixels)
        }
        onAnchorRestored()
    }
    LaunchedEffect(items.lastOrNull()?.providerMessageId) {
        if (initiallyPositioned && shouldFollowNewest && state.restoreAnchor == null && items.isNotEmpty()) {
            withFrameNanos { }
            listState.scrollToItem(items.lastIndex + leadingItemCount)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!state.coverage.verifiedComplete) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = visualTokens.nearBlack.copy(alpha = 0.94f),
                border = BorderStroke(1.dp, visualTokens.violet.copy(alpha = 0.32f)),
            ) {
                Text(
                    text = stringResource(R.string.index_incomplete),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = visualTokens.lilacSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (state.window.pendingNewer) {
            Button(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 6.dp),
                onClick = {
                    savedVisibleMessageKind = null
                    savedVisibleMessageValue = null
                    savedVisibleScrollOffset = 0
                    initiallyPositioned = false
                    shouldFollowNewest = true
                    onAcceptPending()
                },
            ) { Text(stringResource(R.string.new_messages)) }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = visualTokens.nearBlack.copy(alpha = 0.94f),
                    border = BorderStroke(1.dp, visualTokens.violet.copy(alpha = 0.55f)),
                ) {
                    Text(
                        text = stringResource(R.string.no_messages),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        color = visualTokens.onIncoming,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(THREAD_LIST_TEST_TAG),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (state.loadingOlder) {
                    item(key = "thread-loading-older", contentType = "progress") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = visualTokens.nearBlack.copy(alpha = 0.92f),
                                border = BorderStroke(1.dp, visualTokens.violet.copy(alpha = 0.48f)),
                            ) {
                                Box(
                                    modifier = Modifier.size(48.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                }
                items(
                    count = items.size,
                    key = { index -> items[index].providerMessageId.stableUiKey() },
                    contentType = { "message" },
                ) { index ->
                    val message = items[index]
                    MessageBubble(
                        message = message,
                        previousMessage = items.getOrNull(index - 1),
                        contact = message.senderAddress?.let(state.contacts::get),
                        highlighted = message.providerMessageId == state.highlightedMessageId,
                        expandedContent = state.expandedContent.takeIf {
                            state.expandedMessageId == message.providerMessageId
                        },
                        expanding = state.expandingMessage &&
                            state.expandedMessageId == message.providerMessageId,
                        expansionFailed = state.expansionFailed &&
                            state.expandedMessageId == message.providerMessageId,
                        onToggleExpansion = { onToggleMessageExpansion(message.providerMessageId) },
                        previewVisible = message.providerMessageId in visibleMessageIds,
                        attachmentRepository = attachmentRepository,
                        previewLoader = previewLoader,
                    )
                }
                item(key = "thread-bottom-space", contentType = "spacing") { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: TimelineMessage,
    previousMessage: TimelineMessage?,
    contact: ResolvedContact?,
    highlighted: Boolean,
    expandedContent: TimelineMessageContent?,
    expanding: Boolean,
    expansionFailed: Boolean,
    onToggleExpansion: () -> Unit,
    previewVisible: Boolean,
    attachmentRepository: MmsAttachmentRepository,
    previewLoader: BoundedPreviewLoader,
) {
    val tokens = LocalAuroraMaterialTokens.current
    val visualTokens = LocalAuroraVisualTokens.current
    val incoming = message.direction == MessageDirection.INCOMING
    val directionDescription = stringResource(
        if (incoming) R.string.incoming_message else R.string.outgoing_message,
    )
    val senderChanged = incoming && message.senderAddress != null &&
        message.senderAddress != previousMessage?.senderAddress
    val displayedSubject = if (expandedContent == null) message.subject else expandedContent.subject
    val displayedBody = if (expandedContent == null) message.bodyPreview else expandedContent.body
    val displayedBodyTruncated = expandedContent?.sourceTruncated ?: message.bodyTruncated
    val bubbleShape = RoundedCornerShape(tokens.bubbleCornerRadius)
    val showDateChip = previousMessage == null ||
        localThreadDate(previousMessage.timestampMillis) != localThreadDate(message.timestampMillis)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalAlignment = if (incoming) Alignment.Start else Alignment.End,
    ) {
        if (showDateChip) {
            ThreadDateChip(
                timestampMillis = message.timestampMillis,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        if (senderChanged) {
            Surface(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                shape = RoundedCornerShape(18.dp),
                color = visualTokens.nearBlack.copy(alpha = 0.94f),
                border = BorderStroke(1.dp, visualTokens.violet.copy(alpha = 0.5f)),
            ) {
                Text(
                    text = contact?.displayNameOrAddress ?: checkNotNull(message.senderAddress).value,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    color = visualTokens.lilacSecondary,
                )
            }
        }
        val bubbleModifier = Modifier
            .widthIn(max = 360.dp)
            .then(
                if (incoming) {
                    Modifier.background(
                        color = if (highlighted) visualTokens.elevatedSurface else visualTokens.incomingFill,
                        shape = bubbleShape,
                    )
                } else {
                    Modifier.background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                visualTokens.outgoingGradientStart,
                                visualTokens.outgoingGradientEnd,
                            ),
                        ),
                        shape = bubbleShape,
                    )
                },
            )
            .border(
                width = if (highlighted) 2.dp else 1.dp,
                color = if (highlighted) {
                    visualTokens.cyan
                } else if (incoming) {
                    visualTokens.incomingOutline
                } else {
                    visualTokens.violet.copy(alpha = 0.82f)
                },
                shape = bubbleShape,
            )
            .clip(bubbleShape)
            .semantics { stateDescription = directionDescription }
            .testTag(MESSAGE_BUBBLE_TEST_TAG)
        val bubbleContentColor = if (incoming) {
            visualTokens.onIncoming
        } else {
            visualTokens.onOutgoing
        }
        val bubbleMetadataColor = if (incoming) {
            visualTokens.lilacSecondary
        } else {
            visualTokens.onOutgoing
        }
        Box(
            modifier = Modifier
                .then(bubbleModifier),
        ) {
            CompositionLocalProvider(
                LocalContentColor provides bubbleContentColor,
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    displayedSubject?.takeIf(String::isNotBlank)?.let { subject ->
                        Text(subject, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(3.dp))
                    }
                    displayedBody?.takeIf(String::isNotEmpty)?.let { Text(it) }
                    if (displayedBodyTruncated) {
                        Text(
                            stringResource(R.string.message_truncated),
                            color = bubbleMetadataColor,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (message.bodyTruncated) {
                        when {
                            expanding -> Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = bubbleContentColor,
                                )
                                Text(stringResource(R.string.loading_full_message))
                            }
                            else -> TextButton(onClick = onToggleExpansion) {
                                Text(
                                    stringResource(
                                        if (expandedContent == null) {
                                            R.string.show_full_message
                                        } else {
                                            R.string.show_less
                                        },
                                    ),
                                    color = bubbleContentColor,
                                )
                            }
                        }
                        if (expansionFailed) {
                            Text(
                                stringResource(R.string.full_message_unavailable),
                                color = if (incoming) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    bubbleContentColor
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    if (
                        previewVisible &&
                        message.attachmentCount > 0 &&
                        message.providerMessageId.kind == ProviderKind.MMS
                    ) {
                        Spacer(Modifier.height(8.dp))
                        AttachmentPreview(
                            messageId = message.providerMessageId,
                            repository = attachmentRepository,
                            previewLoader = previewLoader,
                        )
                    } else if (message.attachmentCount > 0) {
                        Text(
                            pluralStringResource(
                                R.plurals.attachment_summary,
                                message.attachmentCount,
                                message.attachmentCount,
                                message.attachmentTypeSummary,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!incoming) MessageDeliveryStatus(message)
                        Text(
                            formatTimestamp(message.timestampMillis),
                            color = bubbleMetadataColor,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadDateChip(
    timestampMillis: Long,
    modifier: Modifier = Modifier,
) {
    val visualTokens = LocalAuroraVisualTokens.current
    Surface(
        modifier = modifier.padding(vertical = 10.dp),
        shape = RoundedCornerShape(50),
        color = visualTokens.nearBlack.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, visualTokens.violet.copy(alpha = 0.72f)),
    ) {
        Text(
            text = formatThreadDate(timestampMillis),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
            color = visualTokens.lilacSecondary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun MessageDeliveryStatus(message: TimelineMessage) {
    val text = when {
        message.box == MessageBox.SENT && message.status == MessageStatus.FAILED ->
            stringResource(R.string.delivery_sent_failed)
        message.box == MessageBox.FAILED || message.status == MessageStatus.FAILED ->
            stringResource(R.string.delivery_failed)
        message.status == MessageStatus.PENDING ||
            message.box == MessageBox.OUTBOX ||
            message.box == MessageBox.QUEUED -> stringResource(R.string.delivery_pending)
        message.status == MessageStatus.COMPLETE || message.box == MessageBox.SENT ->
            stringResource(R.string.delivery_sent)
        else -> stringResource(R.string.delivery_unknown)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun AttachmentPreview(
    messageId: ProviderMessageId,
    repository: MmsAttachmentRepository,
    previewLoader: BoundedPreviewLoader,
) {
    val state by produceState<AttachmentUiState>(AttachmentUiState.Loading, messageId) {
        value = when (val listed = repository.listStaticImages(messageId)) {
            is MmsAttachmentListResult.Success -> {
                val descriptor = listed.value.items.firstOrNull()
                    ?: return@produceState run { value = AttachmentUiState.Unavailable }
                when (val loaded = previewLoader.load(descriptor)) {
                    is AttachmentPreviewResult.Ready -> AttachmentUiState.Ready(
                        preview = loaded.preview,
                        additionalImages = (listed.value.items.size - 1).coerceAtLeast(0),
                        metadataTruncated = listed.value.metadataTruncated,
                    )
                    else -> AttachmentUiState.Unavailable
                }
            }
            MmsAttachmentListResult.InvalidMessageKind,
            MmsAttachmentListResult.PermissionDenied,
            MmsAttachmentListResult.RoleRequired,
            MmsAttachmentListResult.Unavailable,
            -> AttachmentUiState.Unavailable
        }
    }
    when (val current = state) {
        AttachmentUiState.Loading -> Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.attachment_loading), style = MaterialTheme.typography.bodySmall)
        }
        AttachmentUiState.Unavailable ->
            Text(stringResource(R.string.attachment_unavailable), style = MaterialTheme.typography.bodySmall)
        is AttachmentUiState.Ready -> Column {
            Image(
                bitmap = current.preview.image,
                contentDescription = stringResource(R.string.image_attachment),
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .aspectRatio(current.preview.width.toFloat() / current.preview.height.toFloat())
                    .background(MaterialTheme.colorScheme.surface),
                contentScale = ContentScale.Fit,
            )
            if (current.additionalImages > 0 || current.metadataTruncated) {
                Text(
                    text = if (current.metadataTruncated) {
                        stringResource(R.string.more_attachments_truncated)
                    } else {
                        pluralStringResource(
                            R.plurals.more_attachments,
                            current.additionalImages,
                            current.additionalImages,
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun Composer(
    state: ComposerUiState,
    onBodyChanged: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onSend: () -> Unit,
    onSchedule: () -> Unit,
    onCancelSchedule: () -> Unit,
    onRequestExactAlarmAccess: () -> Unit,
    onAcknowledgeSubmissionUnknown: () -> Unit,
) {
    val visualTokens = LocalAuroraVisualTokens.current
    var showUnknownConfirmation by remember { mutableStateOf(false) }
    var showScheduleDetails by remember { mutableStateOf(false) }
    LaunchedEffect(state.sendState) {
        if (state.sendState != ComposerSendState.SUBMISSION_UNKNOWN) {
            showUnknownConfirmation = false
        }
    }
    LaunchedEffect(state.scheduleState) {
        if (
            state.scheduleState is ComposerScheduleState.None ||
            state.scheduleState is ComposerScheduleState.Loading
        ) {
            showScheduleDetails = false
        }
    }
    if (showUnknownConfirmation) {
        AlertDialog(
            onDismissRequest = { showUnknownConfirmation = false },
            title = { Text(stringResource(R.string.send_status_unknown_title)) },
            text = { Text(stringResource(R.string.send_status_unknown_explanation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnknownConfirmation = false
                        onAcknowledgeSubmissionUnknown()
                    },
                ) { Text(stringResource(R.string.keep_as_draft)) }
            },
            dismissButton = {
                TextButton(onClick = { showUnknownConfirmation = false }) {
                    Text(stringResource(R.string.wait_for_send_status))
                }
            },
        )
    }
    val pendingSchedule = state.scheduleState as? ComposerScheduleState.Pending
    if (showScheduleDetails && state.scheduleState !is ComposerScheduleState.None) {
        AlertDialog(
            onDismissRequest = { showScheduleDetails = false },
            title = { Text(stringResource(R.string.scheduled_message_title)) },
            text = {
                Column {
                    Text(
                        when (val schedule = state.scheduleState) {
                            is ComposerScheduleState.Pending -> {
                                val time = formatScheduledTime(schedule.dueTimestampMillis)
                                if (schedule.exact) {
                                    stringResource(R.string.message_scheduled_exact, time)
                                } else {
                                    stringResource(R.string.message_scheduled_inexact, time)
                                }
                            }
                            is ComposerScheduleState.Dispatching ->
                                stringResource(R.string.scheduled_message_dispatching)
                            is ComposerScheduleState.ReviewRequired ->
                                stringResource(R.string.scheduled_message_review_required)
                            ComposerScheduleState.Loading,
                            ComposerScheduleState.None,
                            -> ""
                        },
                    )
                    if (pendingSchedule?.exact == false) {
                        TextButton(
                            onClick = {
                                showScheduleDetails = false
                                onRequestExactAlarmAccess()
                            },
                        ) { Text(stringResource(R.string.allow_exact_timing)) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showScheduleDetails = false }) {
                    Text(stringResource(R.string.keep_schedule))
                }
            },
            dismissButton = {
                if (state.scheduleState !is ComposerScheduleState.Dispatching) {
                    TextButton(
                        onClick = {
                            showScheduleDetails = false
                            onCancelSchedule()
                        },
                    ) { Text(stringResource(R.string.cancel_schedule)) }
                }
            },
        )
    }
    val supportingText = when {
        state.scheduleState is ComposerScheduleState.Loading ->
            stringResource(R.string.checking_scheduled_message)
        state.scheduleState is ComposerScheduleState.Pending -> {
            val schedule = state.scheduleState
            val time = formatScheduledTime(schedule.dueTimestampMillis)
            if (schedule.exact) {
                stringResource(R.string.message_scheduled_exact, time)
            } else {
                stringResource(R.string.message_scheduled_inexact, time)
            }
        }
        state.scheduleState is ComposerScheduleState.Dispatching ->
            stringResource(R.string.scheduled_message_dispatching)
        state.scheduleState is ComposerScheduleState.ReviewRequired ->
            stringResource(R.string.scheduled_message_review_required)
        state.failed -> stringResource(R.string.draft_failed)
        state.saving -> stringResource(R.string.saving_draft)
        state.sendState == ComposerSendState.SENDING ->
            stringResource(R.string.submitting_message)
        state.sendState == ComposerSendState.KNOWN_UNSENT ->
            stringResource(R.string.message_not_sent_draft_preserved)
        state.sendState == ComposerSendState.SUBMISSION_UNKNOWN ->
            stringResource(R.string.send_status_unknown_supporting)
        state.unavailableReason == ComposerUnavailableReason.CONVERSATION_UNVERIFIED ->
            stringResource(R.string.verifying_conversation_for_send)
        state.unavailableReason == ComposerUnavailableReason.GROUP_REQUIRES_MMS ->
            stringResource(R.string.group_send_requires_mms)
        state.unavailableReason == ComposerUnavailableReason.SUBSCRIPTION_UNAVAILABLE ->
            stringResource(R.string.conversation_sim_unavailable)
        state.unavailableReason == ComposerUnavailableReason.MULTIPART_UNAVAILABLE ->
            stringResource(
                R.string.multipart_send_unavailable,
                state.segmentCount ?: 2,
            )
        state.unavailableReason == ComposerUnavailableReason.RECOVERY_PENDING ->
            stringResource(R.string.finishing_send_recovery)
        state.unavailableReason == ComposerUnavailableReason.MESSAGING_UNAVAILABLE ->
            stringResource(R.string.messaging_send_unavailable)
        state.unavailableReason == ComposerUnavailableReason.EMPTY_MESSAGE ->
            stringResource(R.string.type_message_to_send)
        state.unavailableReason == ComposerUnavailableReason.DRAFT_NOT_DURABLE ->
            stringResource(R.string.saving_draft)
        state.sendState == ComposerSendState.READY ->
            stringResource(R.string.draft_saved_one_sms)
        else -> stringResource(R.string.draft_saved)
    }
    val actionLabel = stringResource(
        when (state.sendState) {
            ComposerSendState.READY -> R.string.send
            ComposerSendState.SENDING -> R.string.sending
            ComposerSendState.KNOWN_UNSENT -> R.string.retry_send
            ComposerSendState.SUBMISSION_UNKNOWN -> R.string.review_send
            ComposerSendState.UNAVAILABLE -> R.string.send_unavailable
        },
    )
    val actionGlyph = when (state.sendState) {
        ComposerSendState.KNOWN_UNSENT -> AuroraGlyph.RETRY
        ComposerSendState.SUBMISSION_UNKNOWN -> AuroraGlyph.REVIEW
        else -> AuroraGlyph.SEND
    }
    val scheduleActive = state.scheduleState is ComposerScheduleState.Pending ||
        state.scheduleState is ComposerScheduleState.Dispatching ||
        state.scheduleState is ComposerScheduleState.ReviewRequired
    val scheduleLoading = state.scheduleState is ComposerScheduleState.Loading
    val actionEnabled = !scheduleActive && (state.sendState == ComposerSendState.READY ||
        state.sendState == ComposerSendState.KNOWN_UNSENT ||
        state.sendState == ComposerSendState.SUBMISSION_UNKNOWN)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(visualTokens.deepNight.copy(alpha = 0.98f)),
    ) {
        HorizontalDivider(color = visualTokens.violet.copy(alpha = 0.5f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.body,
                onValueChange = { value ->
                    if (value.length <= MAXIMUM_COMPOSER_CHARACTERS) onBodyChanged(value)
                },
                enabled = !state.failed &&
                    !scheduleActive &&
                    !scheduleLoading &&
                    state.sendState != ComposerSendState.SENDING &&
                    state.sendState != ComposerSendState.SUBMISSION_UNKNOWN &&
                    state.unavailableReason != ComposerUnavailableReason.RECOVERY_PENDING,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { onFocusChanged(it.isFocused) }
                    .testTag(COMPOSER_TEST_TAG),
                minLines = 1,
                maxLines = 5,
                label = { Text(stringResource(R.string.message_draft)) },
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = visualTokens.onIncoming,
                    unfocusedTextColor = visualTokens.onIncoming,
                    disabledTextColor = visualTokens.lilacSecondary,
                    focusedContainerColor = visualTokens.nearBlack.copy(alpha = 0.96f),
                    unfocusedContainerColor = visualTokens.nearBlack.copy(alpha = 0.9f),
                    disabledContainerColor = visualTokens.nearBlack.copy(alpha = 0.78f),
                    cursorColor = visualTokens.cyan,
                    focusedBorderColor = visualTokens.cyan,
                    unfocusedBorderColor = visualTokens.violet.copy(alpha = 0.68f),
                    disabledBorderColor = visualTokens.violet.copy(alpha = 0.3f),
                    focusedLabelColor = visualTokens.cyan,
                    unfocusedLabelColor = visualTokens.lilacSecondary,
                    disabledLabelColor = visualTokens.lilacSecondary.copy(alpha = 0.6f),
                ),
            )
            val scheduleLabel = stringResource(
                if (scheduleActive) R.string.cancel_scheduled_message else R.string.schedule_message,
            )
            AuroraIconAction(
                glyph = AuroraGlyph.SCHEDULE,
                contentDescription = scheduleLabel,
                onClick = if (scheduleActive) {
                    { showScheduleDetails = true }
                } else {
                    onSchedule
                },
                enabled = scheduleActive ||
                    (!scheduleLoading && state.sendState == ComposerSendState.READY),
                modifier = Modifier
                    .testTag(COMPOSER_SCHEDULE_TEST_TAG)
                    .semantics { text = AnnotatedString(scheduleLabel) },
                tint = if (scheduleActive) MaterialTheme.colorScheme.error else visualTokens.violet,
            )
            AuroraIconAction(
                glyph = actionGlyph,
                contentDescription = actionLabel,
                onClick = {
                    if (state.sendState == ComposerSendState.SUBMISSION_UNKNOWN) {
                        showUnknownConfirmation = true
                    } else {
                        onSend()
                    }
                },
                enabled = actionEnabled,
                modifier = Modifier
                    .testTag(COMPOSER_SEND_TEST_TAG)
                    .semantics { text = AnnotatedString(actionLabel) },
                tint = if (actionEnabled) visualTokens.cyan else visualTokens.lilacSecondary,
            )
        }
        Text(
            text = supportingText,
            modifier = Modifier.padding(start = 16.dp, end = 64.dp, bottom = 8.dp),
            color = if (
                state.failed ||
                state.sendState == ComposerSendState.KNOWN_UNSENT ||
                state.sendState == ComposerSendState.SUBMISSION_UNKNOWN
            ) {
                MaterialTheme.colorScheme.error
            } else {
                visualTokens.lilacSecondary
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private sealed interface AttachmentUiState {
    data object Loading : AttachmentUiState
    data object Unavailable : AttachmentUiState

    data class Ready(
        val preview: StaticAttachmentPreview,
        val additionalImages: Int,
        val metadataTruncated: Boolean,
    ) : AttachmentUiState
}

private data class VisibleThreadSnapshot(
    val items: List<VisibleThreadItem>,
    val canScrollForward: Boolean,
    val firstVisibleKey: Any?,
    val firstVisibleScrollOffset: Int,
)

private data class VisibleThreadItem(
    val key: Any,
    val offset: Int,
)

private fun ProviderMessageId.stableUiKey(): String = "${kind.name}:$value"

private fun localThreadDate(timestampMillis: Long) =
    Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()).toLocalDate()

private fun formatThreadDate(timestampMillis: Long): String =
    DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(localThreadDate(timestampMillis))

private fun formatScheduledTime(timestampMillis: Long): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()))

private const val MAXIMUM_HEADER_NAMES: Int = 3
private const val THREAD_VIEWPORT_PREFETCH_ROWS: Int = 10
private const val MAXIMUM_VIEWPORT_THREAD_ROWS: Int = 100
private const val MAXIMUM_COMPOSER_CHARACTERS: Int = 100_000
const val THREAD_SCREEN_TEST_TAG: String = "aurora-thread-screen"
const val THREAD_LIST_TEST_TAG: String = "aurora-thread-list"
const val MESSAGE_BUBBLE_TEST_TAG: String = "aurora-message-bubble"
const val COMPOSER_TEST_TAG: String = "aurora-composer"
const val COMPOSER_SEND_TEST_TAG: String = "aurora-composer-send"
const val COMPOSER_SCHEDULE_TEST_TAG: String = "aurora-composer-schedule"
const val THREAD_MORE_ACTION_TEST_TAG: String = "aurora-thread-more-action"
const val THREAD_APPEARANCE_ACTION_TEST_TAG: String = "aurora-thread-appearance-action"
const val THREAD_SIM_SELECTOR_TEST_TAG: String = "aurora-thread-sim-selector"
