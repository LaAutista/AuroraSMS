// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.aurorasms.core.designsystem.AuroraBackdrop
import org.aurorasms.core.designsystem.AuroraGlyph
import org.aurorasms.core.designsystem.AuroraIconAction
import org.aurorasms.core.designsystem.LocalAuroraMaterialTokens
import org.aurorasms.core.designsystem.LocalAuroraVisualTokens
import org.aurorasms.core.model.ProviderThreadId

@Composable
fun SpamBlockedScreen(
    state: SpamBlockedUiState,
    onBack: () -> Unit,
    onOpenConversation: (ProviderThreadId) -> Unit,
    onMarkNotSpam: SpamBlockedAction,
    onUnblock: SpamBlockedAction,
) {
    val visuals = LocalAuroraVisualTokens.current
    Box(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .testTag(SPAM_BLOCKED_SCREEN_TEST_TAG),
    ) {
        AuroraBackdrop(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AuroraIconAction(
                    glyph = AuroraGlyph.BACK,
                    contentDescription = stringResource(R.string.back),
                    onClick = onBack,
                    tint = visuals.lilacSecondary,
                )
                Text(
                    text = stringResource(R.string.spam_and_blocked),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = visuals.onIncoming,
                )
            }
            HorizontalDivider(color = visuals.violet.copy(alpha = 0.4f))
            when (state) {
                SpamBlockedUiState.Loading -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                SpamBlockedUiState.Unavailable -> Box(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.spam_storage_unavailable)) }
                is SpamBlockedUiState.Ready -> if (state.rows.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(R.string.no_spam_or_blocked)) }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(
                            items = state.rows,
                            key = { it.summary.providerThreadId.value },
                        ) { row ->
                            SpamBlockedConversationRow(
                                row = row,
                                onOpen = { onOpenConversation(row.summary.providerThreadId) },
                                onMarkNotSpam = { onMarkNotSpam(row.summary.providerThreadId) },
                                onUnblock = { onUnblock(row.summary.providerThreadId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpamBlockedConversationRow(
    row: SpamBlockedRow,
    onOpen: () -> Unit,
    onMarkNotSpam: () -> Unit,
    onUnblock: () -> Unit,
) {
    val visuals = LocalAuroraVisualTokens.current
    val tokens = LocalAuroraMaterialTokens.current
    val title = row.summary.participants
        .map { row.contacts[it]?.displayNameOrAddress ?: it.value }
        .take(3)
        .joinToString()
        .ifBlank { stringResource(R.string.unknown_conversation) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .testTag(SPAM_BLOCKED_ROW_TEST_TAG),
        color = visuals.elevatedSurface.copy(alpha = 0.80f),
        border = BorderStroke(1.dp, visuals.violet.copy(alpha = 0.45f)),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = tokens.rowVerticalPadding),
        ) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                color = visuals.onIncoming,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    row.markedSpam && row.blocked -> stringResource(R.string.marked_spam_and_blocked)
                    row.blocked -> stringResource(R.string.user_blocked_reason)
                    else -> stringResource(R.string.user_marked_spam_reason)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (row.markedSpam) {
                    TextButton(onClick = onMarkNotSpam) {
                        Text(stringResource(R.string.mark_not_spam))
                    }
                }
                if (row.blocked) {
                    TextButton(onClick = onUnblock) { Text(stringResource(R.string.unblock_sender)) }
                }
            }
        }
    }
}

const val SPAM_BLOCKED_SCREEN_TEST_TAG = "aurora-spam-blocked-screen"
const val SPAM_BLOCKED_ROW_TEST_TAG = "aurora-spam-blocked-row"
