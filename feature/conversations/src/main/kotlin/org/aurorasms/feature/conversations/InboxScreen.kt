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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.distinctUntilChanged
import org.aurorasms.core.index.conversation.ConversationSummary
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
    onOpenDiagnostics: () -> Unit,
    onRequestContactsPermission: () -> Unit,
    onRetry: () -> Unit,
    onLoadOlder: (WindowAnchor<ProviderThreadId>) -> Unit,
    onAtNewestChanged: (Boolean) -> Unit,
    onAcceptPending: () -> Unit,
    onViewportChanged: (List<ConversationSummary>) -> Unit,
    onAnchorRestored: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
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
                if (diagnosticsAvailable) {
                    TextButton(onClick = onOpenDiagnostics) {
                        Text(stringResource(R.string.diagnostics))
                    }
                }
                if (!contactsPermissionGranted) {
                    TextButton(onClick = onRequestContactsPermission) {
                        Text(stringResource(R.string.use_contacts))
                    }
                }
                TextButton(onClick = onOpenSearch) {
                    Text(stringResource(R.string.search))
                }
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
private fun InboxReady(
    state: InboxUiState.Ready,
    onOpenConversation: (ProviderThreadId) -> Unit,
    onLoadOlder: (WindowAnchor<ProviderThreadId>) -> Unit,
    onAtNewestChanged: (Boolean) -> Unit,
    onAcceptPending: () -> Unit,
    onViewportChanged: (List<ConversationSummary>) -> Unit,
    onAnchorRestored: () -> Unit,
) {
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
                modifier = Modifier.fillMaxSize(),
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
                    HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
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
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = MaterialTheme.shapes.extraLarge,
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

private const val VIEWPORT_PREFETCH_ROWS: Int = 10
