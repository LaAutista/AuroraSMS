// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import org.aurorasms.core.index.conversation.ConversationSummary
import org.aurorasms.core.index.timeline.TimelineMessage
import org.aurorasms.core.index.timeline.TimelineMessageContent
import org.aurorasms.core.designsystem.LocalAuroraMaterialTokens
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.telephony.MmsAttachmentListResult
import org.aurorasms.core.telephony.MmsAttachmentRepository
import org.aurorasms.core.telephony.ResolvedContact

@Composable
fun ThreadScreen(
    state: ThreadUiState,
    composer: ComposerUiState,
    attachmentRepository: MmsAttachmentRepository,
    previewLoader: BoundedPreviewLoader,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
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
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
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
    ) {
        Column(modifier = Modifier.imePadding()) {
            ThreadHeader(
                state = state,
                onBack = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    onBack()
                },
                onOpenSearch = onOpenSearch,
                isDialable = isDialable,
                onDial = onDial,
            )
            HorizontalDivider()
            Box(modifier = Modifier.weight(1f)) {
                when (state) {
                    ThreadUiState.Loading -> LoadingPane()
                    is ThreadUiState.Failed -> FailurePane(onRetry)
                    is ThreadUiState.Ready -> ThreadReady(
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
            Composer(
                state = composer,
                onBodyChanged = onDraftChanged,
                onFocusChanged = { composerFocused = it },
            )
        }
    }
}

@Composable
private fun ThreadHeader(
    state: ThreadUiState,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    isDialable: (ParticipantAddress) -> Boolean,
    onDial: (ParticipantAddress) -> Unit,
) {
    val ready = state as? ThreadUiState.Ready
    val summary = ready?.conversation
    val title = threadTitle(ready)
    val dialAddress = summary?.participants?.singleOrNull()?.takeIf {
        ready.coverage.verifiedComplete && !summary.participantsTruncated && isDialable(it)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            ready?.activeSubscription?.let { subscription ->
                Text(
                    text = if (subscription.displayLabel.isBlank()) {
                        stringResource(R.string.sim_number, subscription.slotIndex + 1)
                    } else {
                        stringResource(
                            R.string.sim_number_with_label,
                            subscription.slotIndex + 1,
                            subscription.displayLabel,
                        )
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (dialAddress != null) {
            TextButton(onClick = { onDial(dialAddress) }) { Text(stringResource(R.string.call)) }
        }
        TextButton(onClick = onOpenSearch) { Text(stringResource(R.string.search)) }
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
    val items = state.window.items
    val listState = rememberLazyListState()
    var initiallyPositioned by remember { mutableStateOf(false) }
    var shouldFollowNewest by remember { mutableStateOf(state.highlightedMessageId == null) }
    var visibleMessageIds by remember { mutableStateOf(emptySet<ProviderMessageId>()) }

    LaunchedEffect(listState, items, state.window.olderCursor, state.loadingOlder) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            VisibleThreadSnapshot(
                positions = visible.map { it.index to it.offset },
                canScrollForward = listState.canScrollForward,
            )
        }.distinctUntilChanged().collect { snapshot ->
            val atNewest = !snapshot.canScrollForward &&
                state.window.newerCursor == null &&
                !state.window.pendingNewer
            shouldFollowNewest = atNewest
            onAtNewestChanged(atNewest)
            if (snapshot.positions.isEmpty()) {
                visibleMessageIds = emptySet()
                onViewportChanged(items.take(MAXIMUM_VIEWPORT_THREAD_ROWS))
                return@collect
            }
            val firstIndex = snapshot.positions.minOf { it.first }
            val lastIndex = snapshot.positions.maxOf { it.first }
            visibleMessageIds = snapshot.positions.mapNotNullTo(LinkedHashSet()) { (index, _) ->
                items.getOrNull(index)?.providerMessageId
            }
            val viewportStart = (firstIndex - THREAD_VIEWPORT_PREFETCH_ROWS).coerceAtLeast(0)
            val viewportEnd = (lastIndex + THREAD_VIEWPORT_PREFETCH_ROWS + 1).coerceAtMost(items.size)
            onViewportChanged(items.subList(viewportStart, viewportEnd).take(MAXIMUM_VIEWPORT_THREAD_ROWS))
            if (state.window.olderCursor != null && !state.loadingOlder && firstIndex <= 3) {
                val position = snapshot.positions.firstOrNull { it.first == firstIndex } ?: return@collect
                val anchor = items.getOrNull(firstIndex) ?: return@collect
                onLoadOlder(WindowAnchor(anchor.providerMessageId, position.second))
            }
            if (
                state.window.newerCursor != null &&
                !state.loadingNewer &&
                lastIndex >= items.lastIndex - 3
            ) {
                val position = snapshot.positions.firstOrNull { it.first == firstIndex } ?: return@collect
                val anchor = items.getOrNull(firstIndex) ?: return@collect
                onLoadNewer(WindowAnchor(anchor.providerMessageId, position.second))
            }
        }
    }
    LaunchedEffect(items, state.highlightedMessageId) {
        if (initiallyPositioned || items.isEmpty()) return@LaunchedEffect
        val highlighted = state.highlightedMessageId
            ?.let { target -> items.indexOfFirst { it.providerMessageId == target } }
            ?.takeIf { it >= 0 }
        listState.scrollToItem(highlighted ?: items.lastIndex)
        initiallyPositioned = true
    }
    LaunchedEffect(state.restoreAnchor, items) {
        val anchor = state.restoreAnchor ?: return@LaunchedEffect
        val index = items.indexOfFirst { it.providerMessageId == anchor.stableKey }
        if (index >= 0) listState.scrollToItem(index, anchor.scrollOffsetPixels)
        onAnchorRestored()
    }
    LaunchedEffect(items.lastOrNull()?.providerMessageId) {
        if (initiallyPositioned && shouldFollowNewest && state.restoreAnchor == null && items.isNotEmpty()) {
            listState.scrollToItem(items.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!state.coverage.verifiedComplete) {
            Text(
                text = stringResource(R.string.index_incomplete),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.window.pendingNewer) {
            Button(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 6.dp),
                onClick = {
                    initiallyPositioned = false
                    shouldFollowNewest = true
                    onAcceptPending()
                },
            ) { Text(stringResource(R.string.new_messages)) }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_messages))
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
                        ) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
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
    val incoming = message.direction == MessageDirection.INCOMING
    val directionDescription = stringResource(
        if (incoming) R.string.incoming_message else R.string.outgoing_message,
    )
    val senderChanged = incoming && message.senderAddress != null &&
        message.senderAddress != previousMessage?.senderAddress
    val displayedSubject = if (expandedContent == null) message.subject else expandedContent.subject
    val displayedBody = if (expandedContent == null) message.bodyPreview else expandedContent.body
    val displayedBodyTruncated = expandedContent?.sourceTruncated ?: message.bodyTruncated
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalAlignment = if (incoming) Alignment.Start else Alignment.End,
    ) {
        if (senderChanged) {
            Text(
                text = contact?.displayNameOrAddress ?: checkNotNull(message.senderAddress).value,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Surface(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .semantics { stateDescription = directionDescription }
                .testTag(MESSAGE_BUBBLE_TEST_TAG),
            shape = RoundedCornerShape(tokens.bubbleCornerRadius),
            color = when {
                highlighted -> MaterialTheme.colorScheme.tertiaryContainer
                incoming -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.primaryContainer
            },
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
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (message.bodyTruncated) {
                    when {
                        expanding -> Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
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
                            )
                        }
                    }
                    if (expansionFailed) {
                        Text(
                            stringResource(R.string.full_message_unavailable),
                            color = MaterialTheme.colorScheme.error,
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
                    Text(formatTimestamp(message.timestampMillis), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun MessageDeliveryStatus(message: TimelineMessage) {
    val text = when {
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
        color = if (message.status == MessageStatus.FAILED || message.box == MessageBox.FAILED) {
            MaterialTheme.colorScheme.error
        } else {
            Color.Unspecified
        },
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
) {
    HorizontalDivider()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = state.body,
            onValueChange = { value ->
                if (value.length <= MAXIMUM_COMPOSER_CHARACTERS) onBodyChanged(value)
            },
            enabled = !state.failed,
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { onFocusChanged(it.isFocused) }
                .testTag(COMPOSER_TEST_TAG),
            minLines = 1,
            maxLines = 5,
            label = { Text(stringResource(R.string.message_draft)) },
            supportingText = {
                Text(
                    when {
                        state.failed -> stringResource(R.string.draft_failed)
                        state.saving -> stringResource(R.string.saving_draft)
                        else -> stringResource(R.string.draft_saved)
                    },
                )
            },
        )
        Button(onClick = {}, enabled = false) { Text(stringResource(R.string.send_unavailable)) }
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
    val positions: List<Pair<Int, Int>>,
    val canScrollForward: Boolean,
)

private fun ProviderMessageId.stableUiKey(): String = "${kind.name}:$value"

private const val MAXIMUM_HEADER_NAMES: Int = 3
private const val THREAD_VIEWPORT_PREFETCH_ROWS: Int = 10
private const val MAXIMUM_VIEWPORT_THREAD_ROWS: Int = 100
private const val MAXIMUM_COMPOSER_CHARACTERS: Int = 100_000
const val THREAD_SCREEN_TEST_TAG: String = "aurora-thread-screen"
const val THREAD_LIST_TEST_TAG: String = "aurora-thread-list"
const val MESSAGE_BUBBLE_TEST_TAG: String = "aurora-message-bubble"
const val COMPOSER_TEST_TAG: String = "aurora-composer"
