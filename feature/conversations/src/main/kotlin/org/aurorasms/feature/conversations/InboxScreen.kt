// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.distinctUntilChanged
import org.aurorasms.core.index.conversation.ConversationSummary
import org.aurorasms.core.designsystem.LocalAuroraMaterialProfile
import org.aurorasms.core.designsystem.LocalAuroraMaterialTokens
import org.aurorasms.core.designsystem.toShape
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.telephony.ResolvedContact

@Composable
fun InboxScreen(
    state: InboxUiState,
    diagnosticsAvailable: Boolean,
    contactsPermissionGranted: Boolean,
    onOpenConversation: (ProviderThreadId) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenInboxAppearance: () -> Unit,
    onOpenConversationDefaults: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onRequestContactsPermission: () -> Unit,
    onRetry: () -> Unit,
    onLoadOlder: (WindowAnchor<ProviderThreadId>) -> Unit,
    onAtNewestChanged: (Boolean) -> Unit,
    onAcceptPending: () -> Unit,
    onViewportChanged: (List<ConversationSummary>) -> Unit,
    onAnchorRestored: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .semantics { testTagsAsResourceId = true }
            .testTag(INBOX_SCREEN_TEST_TAG),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.inbox_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                )
                TextButton(
                    modifier = Modifier.testTag(INBOX_SEARCH_ACTION_TEST_TAG),
                    onClick = onOpenSearch,
                ) {
                    Text(stringResource(R.string.search))
                }
                InboxMoreMenu(
                    diagnosticsAvailable = diagnosticsAvailable,
                    contactsPermissionGranted = contactsPermissionGranted,
                    onOpenAppearance = onOpenAppearance,
                    onOpenInboxAppearance = onOpenInboxAppearance,
                    onOpenConversationDefaults = onOpenConversationDefaults,
                    onOpenDiagnostics = onOpenDiagnostics,
                    onRequestContactsPermission = onRequestContactsPermission,
                )
            }
            HorizontalDivider()
            when (state) {
                InboxUiState.Loading -> LoadingPane()
                is InboxUiState.Failed -> FailurePane(onRetry)
                is InboxUiState.Ready -> InboxReady(
                    state = state,
                    onOpenConversation = onOpenConversation,
                    onLoadOlder = onLoadOlder,
                    onAtNewestChanged = onAtNewestChanged,
                    onAcceptPending = onAcceptPending,
                    onViewportChanged = onViewportChanged,
                    onAnchorRestored = onAnchorRestored,
                )
            }
        }
    }
}

@Composable
private fun InboxMoreMenu(
    diagnosticsAvailable: Boolean,
    contactsPermissionGranted: Boolean,
    onOpenAppearance: () -> Unit,
    onOpenInboxAppearance: () -> Unit,
    onOpenConversationDefaults: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onRequestContactsPermission: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            modifier = Modifier.testTag(INBOX_MORE_ACTION_TEST_TAG),
            onClick = { expanded = true },
        ) {
            Text(stringResource(R.string.more))
        }
        DropdownMenu(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                modifier = Modifier.testTag(INBOX_APPEARANCE_ACTION_TEST_TAG),
                text = { Text(stringResource(R.string.appearance)) },
                onClick = {
                    expanded = false
                    onOpenAppearance()
                },
            )
            DropdownMenuItem(
                modifier = Modifier.testTag(INBOX_SCOPE_APPEARANCE_ACTION_TEST_TAG),
                text = { Text(stringResource(R.string.inbox_appearance)) },
                onClick = {
                    expanded = false
                    onOpenInboxAppearance()
                },
            )
            DropdownMenuItem(
                modifier = Modifier.testTag(CONVERSATION_DEFAULTS_APPEARANCE_ACTION_TEST_TAG),
                text = { Text(stringResource(R.string.conversation_defaults)) },
                onClick = {
                    expanded = false
                    onOpenConversationDefaults()
                },
            )
            if (!contactsPermissionGranted) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.use_contacts)) },
                    onClick = {
                        expanded = false
                        onRequestContactsPermission()
                    },
                )
            }
            if (diagnosticsAvailable) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.diagnostics)) },
                    onClick = {
                        expanded = false
                        onOpenDiagnostics()
                    },
                )
            }
        }
    }
}

@Composable
private fun InboxReady(
    state: InboxUiState.Ready,
    onOpenConversation: (ProviderThreadId) -> Unit,
    onLoadOlder: (WindowAnchor<ProviderThreadId>) -> Unit,
    onAtNewestChanged: (Boolean) -> Unit,
    onAcceptPending: () -> Unit,
    onViewportChanged: (List<ConversationSummary>) -> Unit,
    onAnchorRestored: () -> Unit,
) {
    val tokens = LocalAuroraMaterialTokens.current
    val listState = rememberLazyListState()
    val items = state.window.items
    LaunchedEffect(listState, items) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.map { it.index to it.offset }
        }.distinctUntilChanged().collect { visible ->
            val first = visible.firstOrNull()
            onAtNewestChanged(first == null || (first.first == 0 && first.second == 0))
            if (visible.isEmpty()) {
                onViewportChanged(items.take(20))
                return@collect
            }
            val start = (visible.minOf { it.first } - VIEWPORT_PREFETCH_ROWS).coerceAtLeast(0)
            val end = (visible.maxOf { it.first } + VIEWPORT_PREFETCH_ROWS + 1).coerceAtMost(items.size)
            onViewportChanged(items.subList(start, end).take(MAXIMUM_VIEWPORT_CONVERSATIONS))
            if (state.window.olderCursor != null && visible.maxOf { it.first } >= items.lastIndex - 4) {
                val anchorIndex = first?.first ?: return@collect
                val anchor = items.getOrNull(anchorIndex) ?: return@collect
                onLoadOlder(WindowAnchor(anchor.providerThreadId, first.second))
            }
        }
    }
    LaunchedEffect(state.restoreAnchor, items) {
        val anchor = state.restoreAnchor ?: return@LaunchedEffect
        val index = items.indexOfFirst { it.providerThreadId == anchor.stableKey }
        if (index >= 0) listState.scrollToItem(index, anchor.scrollOffsetPixels)
        onAnchorRestored()
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
                    .padding(8.dp),
                onClick = onAcceptPending,
            ) {
                Text(stringResource(R.string.new_messages))
            }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_conversations))
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(INBOX_LIST_TEST_TAG),
                state = listState,
            ) {
                items(
                    count = items.size,
                    key = { index -> items[index].providerThreadId.value },
                ) { index ->
                    ConversationRow(
                        summary = items[index],
                        contacts = state.contacts,
                        onClick = { onOpenConversation(items[index].providerThreadId) },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(
                            start = 16.dp + tokens.avatarSize + tokens.contentSpacing,
                        ),
                    )
                }
                if (state.loadingOlder) {
                    item(key = "inbox-loading-older") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    summary: ConversationSummary,
    contacts: Map<ParticipantAddress, ResolvedContact>,
    onClick: () -> Unit,
) {
    val profile = LocalAuroraMaterialProfile.current
    val tokens = LocalAuroraMaterialTokens.current
    val title = summary.participants
        .map { address -> contacts[address]?.displayNameOrAddress ?: address.value }
        .take(3)
        .joinToString()
        .ifBlank {
            summary.latestSenderAddress?.let { contacts[it]?.displayNameOrAddress ?: it.value }
                ?: stringResource(R.string.unknown_conversation)
        }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = tokens.rowMinimumHeight)
            .testTag(INBOX_ROW_TEST_TAG)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = tokens.rowVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(tokens.contentSpacing),
    ) {
        Surface(
            modifier = Modifier.size(tokens.avatarSize),
            shape = profile.avatarMask.toShape(),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(title.take(1).uppercase(), fontWeight = FontWeight.Bold)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (summary.indexedUnreadCount > 0L) FontWeight.Bold else FontWeight.Medium,
                )
                Text(
                    text = formatTimestamp(summary.latestTimestampMillis),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = summary.latestSnippet.orEmpty().ifBlank {
                    if (summary.latestAttachmentCount > 0) {
                        pluralStringResource(
                            R.plurals.attachment_summary,
                            summary.latestAttachmentCount,
                            summary.latestAttachmentCount,
                            summary.latestAttachmentTypeSummary,
                        )
                    } else {
                        " "
                    }
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (summary.indexedUnreadCount > 0L) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
internal fun LoadingPane() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.loading_messages))
        }
    }
}

@Composable
internal fun FailurePane(onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.load_failed))
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable
internal fun formatTimestamp(timestampMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestampMillis))

const val INBOX_SCREEN_TEST_TAG: String = "aurora-inbox-screen"
const val INBOX_SEARCH_ACTION_TEST_TAG: String = "aurora-inbox-search-action"
const val INBOX_MORE_ACTION_TEST_TAG: String = "aurora-inbox-more-action"
const val INBOX_APPEARANCE_ACTION_TEST_TAG: String = "aurora-inbox-appearance-action"
const val INBOX_SCOPE_APPEARANCE_ACTION_TEST_TAG: String = "aurora-inbox-scope-appearance-action"
const val CONVERSATION_DEFAULTS_APPEARANCE_ACTION_TEST_TAG: String =
    "aurora-conversation-defaults-appearance-action"
const val INBOX_LIST_TEST_TAG: String = "aurora-inbox-list"
const val INBOX_ROW_TEST_TAG: String = "aurora-inbox-row"
private const val VIEWPORT_PREFETCH_ROWS: Int = 10
