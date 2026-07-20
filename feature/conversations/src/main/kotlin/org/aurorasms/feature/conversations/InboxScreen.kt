// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import org.aurorasms.core.designsystem.AuroraBackdrop
import org.aurorasms.core.designsystem.AuroraGlyph
import org.aurorasms.core.designsystem.AuroraIconAction
import org.aurorasms.core.designsystem.LocalAuroraMaterialProfile
import org.aurorasms.core.designsystem.LocalAuroraMaterialTokens
import org.aurorasms.core.designsystem.LocalAuroraVisualTokens
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
    onOpenSpamBlocked: () -> Unit = {},
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
    notificationReminderDelayMinutes: Int = 0,
    onSetNotificationReminderDelayMinutes: (Int) -> Unit = {},
    signaturesAvailable: Boolean = false,
    onOpenGlobalSignature: () -> Unit = {},
    spamIndicators: Map<ProviderThreadId, SpamSafetyIndicator> = emptyMap(),
) {
    val visuals = LocalAuroraVisualTokens.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .semantics { testTagsAsResourceId = true }
            .testTag(INBOX_SCREEN_TEST_TAG),
    ) {
        AuroraBackdrop(modifier = Modifier.fillMaxSize())
        CompositionLocalProvider(LocalContentColor provides visuals.onIncoming) {
            Column(modifier = Modifier.fillMaxSize()) {
                InboxSearchBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    onOpenSearch = onOpenSearch,
                    menu = {
                        InboxMoreMenu(
                            diagnosticsAvailable = diagnosticsAvailable,
                            contactsPermissionGranted = contactsPermissionGranted,
                            onOpenAppearance = onOpenAppearance,
                            onOpenSpamBlocked = onOpenSpamBlocked,
                            onOpenInboxAppearance = onOpenInboxAppearance,
                            onOpenConversationDefaults = onOpenConversationDefaults,
                            onOpenDiagnostics = onOpenDiagnostics,
                            onRequestContactsPermission = onRequestContactsPermission,
                            notificationReminderDelayMinutes = notificationReminderDelayMinutes,
                            onSetNotificationReminderDelayMinutes =
                                onSetNotificationReminderDelayMinutes,
                            signaturesAvailable = signaturesAvailable,
                            onOpenGlobalSignature = onOpenGlobalSignature,
                        )
                    },
                )
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
                        spamIndicators = spamIndicators,
                    )
                }
            }
        }
    }
}

@Composable
private fun InboxSearchBar(
    onOpenSearch: () -> Unit,
    menu: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visuals = LocalAuroraVisualTokens.current
    val searchLabel = stringResource(R.string.search)
    Surface(
        modifier = modifier
            .heightIn(min = INBOX_SEARCH_BAR_HEIGHT)
            .testTag(INBOX_SEARCH_ACTION_TEST_TAG)
            .clickable(onClick = onOpenSearch),
        shape = CircleShape,
        color = visuals.elevatedSurface.copy(alpha = SEARCH_SURFACE_ALPHA),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            width = SEARCH_OUTLINE_WIDTH,
            color = visuals.violet.copy(alpha = SEARCH_OUTLINE_ALPHA),
        ),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AuroraIconAction(
                glyph = AuroraGlyph.SEARCH,
                contentDescription = searchLabel,
                onClick = onOpenSearch,
                tint = visuals.violet,
            )
            Text(
                text = searchLabel,
                modifier = Modifier.weight(1f),
                color = visuals.lilacSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            menu()
        }
    }
}

@Composable
private fun InboxMoreMenu(
    diagnosticsAvailable: Boolean,
    contactsPermissionGranted: Boolean,
    onOpenAppearance: () -> Unit,
    onOpenSpamBlocked: () -> Unit,
    onOpenInboxAppearance: () -> Unit,
    onOpenConversationDefaults: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onRequestContactsPermission: () -> Unit,
    notificationReminderDelayMinutes: Int,
    onSetNotificationReminderDelayMinutes: (Int) -> Unit,
    signaturesAvailable: Boolean,
    onOpenGlobalSignature: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var reminderDialogOpen by remember { mutableStateOf(false) }
    val visuals = LocalAuroraVisualTokens.current
    val moreLabel = stringResource(R.string.more)
    Box {
        AuroraIconAction(
            glyph = AuroraGlyph.MORE,
            contentDescription = moreLabel,
            onClick = { expanded = true },
            modifier = Modifier.testTag(INBOX_MORE_ACTION_TEST_TAG),
            tint = visuals.cyan,
        )
        DropdownMenu(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                modifier = Modifier.testTag(INBOX_SPAM_BLOCKED_ACTION_TEST_TAG),
                text = { Text(stringResource(R.string.spam_and_blocked)) },
                onClick = {
                    expanded = false
                    onOpenSpamBlocked()
                },
            )
            DropdownMenuItem(
                modifier = Modifier.testTag(INBOX_APPEARANCE_ACTION_TEST_TAG),
                text = { Text(stringResource(R.string.appearance)) },
                onClick = {
                    expanded = false
                    onOpenAppearance()
                },
            )
            DropdownMenuItem(
                modifier = Modifier.testTag(INBOX_NOTIFICATION_REMINDER_ACTION_TEST_TAG),
                text = {
                    Text(
                        notificationReminderSummary(notificationReminderDelayMinutes),
                    )
                },
                onClick = {
                    expanded = false
                    reminderDialogOpen = true
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
            if (signaturesAvailable) {
                DropdownMenuItem(
                    modifier = Modifier.testTag(INBOX_SIGNATURE_ACTION_TEST_TAG),
                    text = { Text(stringResource(R.string.message_signature)) },
                    onClick = {
                        expanded = false
                        onOpenGlobalSignature()
                    },
                )
            }
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
        if (reminderDialogOpen) {
            AlertDialog(
                onDismissRequest = { reminderDialogOpen = false },
                title = { Text(stringResource(R.string.notification_reminders)) },
                text = {
                    Column {
                        REMINDER_DELAY_MINUTES.forEach { delay ->
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    reminderDialogOpen = false
                                    onSetNotificationReminderDelayMinutes(delay)
                                },
                            ) {
                                Text(
                                    notificationReminderOption(delay),
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { reminderDialogOpen = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun notificationReminderSummary(delayMinutes: Int): String = when (delayMinutes) {
    15 -> stringResource(R.string.notification_reminders_15_minutes)
    60 -> stringResource(R.string.notification_reminders_1_hour)
    180 -> stringResource(R.string.notification_reminders_3_hours)
    else -> stringResource(R.string.notification_reminders_off)
}

@Composable
private fun notificationReminderOption(delayMinutes: Int): String = when (delayMinutes) {
    15 -> stringResource(R.string.remind_after_15_minutes)
    60 -> stringResource(R.string.remind_after_1_hour)
    180 -> stringResource(R.string.remind_after_3_hours)
    else -> stringResource(R.string.reminders_disabled)
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
    spamIndicators: Map<ProviderThreadId, SpamSafetyIndicator>,
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
            val checkedMessages = state.coverage.generationCommittedCount
            val pluralCount = checkedMessages.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(INDEX_INCOMPLETE_NOTICE_TEST_TAG),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.index_incomplete_progress,
                            pluralCount,
                            checkedMessages,
                        ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
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
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(
                    count = items.size,
                    key = { index -> items[index].providerThreadId.value },
                ) { index ->
                    ConversationRow(
                        summary = items[index],
                        contacts = state.contacts,
                        spamIndicator = spamIndicators[items[index].providerThreadId],
                        onClick = { onOpenConversation(items[index].providerThreadId) },
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
    spamIndicator: SpamSafetyIndicator?,
    onClick: () -> Unit,
) {
    val profile = LocalAuroraMaterialProfile.current
    val tokens = LocalAuroraMaterialTokens.current
    val visuals = LocalAuroraVisualTokens.current
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
            .padding(
                horizontal = 16.dp,
                vertical = tokens.rowVerticalPadding.coerceAtLeast(MINIMUM_INBOX_ROW_VERTICAL_PADDING),
            ),
        horizontalArrangement = Arrangement.spacedBy(tokens.contentSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(tokens.avatarSize),
            shape = profile.avatarMask.toShape(),
            color = visuals.deepNight.copy(alpha = AVATAR_SURFACE_ALPHA),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(AVATAR_RING_WIDTH, visuals.avatarRing),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = title.take(1).uppercase(),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = visuals.onIncoming,
                    fontWeight = if (summary.indexedUnreadCount > 0L) FontWeight.Bold else FontWeight.Medium,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = formatTimestamp(summary.latestTimestampMillis),
                    color = visuals.lilacSecondary.copy(alpha = INBOX_METADATA_ALPHA),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(3.dp))
            if (spamIndicator?.warning == true) {
                Text(
                    text = spamIndicatorLabel(spamIndicator.reason),
                    modifier = Modifier.testTag(INBOX_SPAM_WARNING_TEST_TAG),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
            }
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
                color = visuals.lilacSecondary.copy(alpha = INBOX_SNIPPET_ALPHA),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (summary.indexedUnreadCount > 0L) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun spamIndicatorLabel(reason: SpamSafetyReason): String = when (reason) {
    SpamSafetyReason.USER_MARKED_SPAM -> stringResource(R.string.user_marked_spam_reason)
    SpamSafetyReason.USER_BLOCKED -> stringResource(R.string.user_blocked_reason)
    SpamSafetyReason.SUSPICIOUS_LINK_AND_REQUEST ->
        stringResource(R.string.suspicious_link_request_reason)
    SpamSafetyReason.USER_MARKED_NOT_SPAM -> stringResource(R.string.user_marked_not_spam_reason)
    SpamSafetyReason.SAVED_CONTACT -> stringResource(R.string.saved_contact_reason)
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
const val INDEX_INCOMPLETE_NOTICE_TEST_TAG: String = "aurora-index-incomplete-notice"
const val INBOX_SEARCH_ACTION_TEST_TAG: String = "aurora-inbox-search-action"
const val INBOX_MORE_ACTION_TEST_TAG: String = "aurora-inbox-more-action"
const val INBOX_SIGNATURE_ACTION_TEST_TAG: String = "aurora-inbox-signature-action"
const val INBOX_APPEARANCE_ACTION_TEST_TAG: String = "aurora-inbox-appearance-action"
const val INBOX_SPAM_BLOCKED_ACTION_TEST_TAG: String = "aurora-inbox-spam-blocked-action"
const val INBOX_SPAM_WARNING_TEST_TAG: String = "aurora-inbox-spam-warning"
const val INBOX_NOTIFICATION_REMINDER_ACTION_TEST_TAG: String =
    "aurora-inbox-notification-reminder-action"
const val INBOX_SCOPE_APPEARANCE_ACTION_TEST_TAG: String = "aurora-inbox-scope-appearance-action"
const val CONVERSATION_DEFAULTS_APPEARANCE_ACTION_TEST_TAG: String =
    "aurora-conversation-defaults-appearance-action"
const val INBOX_LIST_TEST_TAG: String = "aurora-inbox-list"
const val INBOX_ROW_TEST_TAG: String = "aurora-inbox-row"
private const val VIEWPORT_PREFETCH_ROWS: Int = 10
private val REMINDER_DELAY_MINUTES = listOf(0, 15, 60, 180)
private val INBOX_SEARCH_BAR_HEIGHT = 56.dp
private val SEARCH_OUTLINE_WIDTH = 1.5.dp
private val AVATAR_RING_WIDTH = 2.dp
private val MINIMUM_INBOX_ROW_VERTICAL_PADDING = 8.dp
private const val SEARCH_SURFACE_ALPHA: Float = 0.92f
private const val SEARCH_OUTLINE_ALPHA: Float = 0.92f
private const val AVATAR_SURFACE_ALPHA: Float = 0.90f
private const val INBOX_METADATA_ALPHA: Float = 0.82f
private const val INBOX_SNIPPET_ALPHA: Float = 0.88f
