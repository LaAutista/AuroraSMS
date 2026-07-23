// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.aurorasms.core.designsystem.AuroraBackdrop
import org.aurorasms.core.designsystem.AuroraGlyph
import org.aurorasms.core.designsystem.AuroraIconAction
import org.aurorasms.core.designsystem.LocalAuroraMaterialProfile
import org.aurorasms.core.designsystem.LocalAuroraMaterialTokens
import org.aurorasms.core.designsystem.LocalAuroraVisualTokens
import org.aurorasms.core.designsystem.toShape
import org.aurorasms.core.index.SearchHit
import org.aurorasms.core.model.ProviderThreadId

@Composable
fun SearchScreen(
    state: SearchUiState,
    onQueryChanged: (String) -> Unit,
    onOpenHit: (SearchHit) -> Unit,
    onOpenConversation: (ProviderThreadId) -> Unit,
    onLoadMore: () -> Unit,
    onBack: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var queryFocused by remember { mutableStateOf(false) }
    BackHandler {
        if (queryFocused) {
            focusManager.clearFocus()
            keyboard?.hide()
        } else {
            onBack()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .semantics { testTagsAsResourceId = true }
            .testTag(SEARCH_SCREEN_TEST_TAG),
    ) {
        val visuals = LocalAuroraVisualTokens.current
        AuroraBackdrop(modifier = Modifier.fillMaxSize())
        CompositionLocalProvider(LocalContentColor provides visuals.onIncoming) {
            Column(modifier = Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { query ->
                        if (query.length <= MAXIMUM_SAVED_SEARCH_QUERY_CHARACTERS) onQueryChanged(query)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .heightIn(min = SEARCH_FIELD_MINIMUM_HEIGHT)
                        .onFocusChanged { queryFocused = it.isFocused }
                        .testTag(SEARCH_FIELD_TEST_TAG),
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.search_hint),
                            color = visuals.lilacSecondary,
                        )
                    },
                    leadingIcon = {
                        AuroraIconAction(
                            glyph = AuroraGlyph.BACK,
                            contentDescription = stringResource(R.string.back),
                            onClick = onBack,
                            tint = visuals.violet,
                        )
                    },
                    shape = CircleShape,
                    textStyle = MaterialTheme.typography.titleMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = visuals.cyan,
                        focusedBorderColor = visuals.violet,
                        unfocusedBorderColor = visuals.violet.copy(alpha = SEARCH_UNFOCUSED_OUTLINE_ALPHA),
                        focusedContainerColor = visuals.elevatedSurface.copy(alpha = SEARCH_FIELD_SURFACE_ALPHA),
                        unfocusedContainerColor = visuals.elevatedSurface.copy(alpha = SEARCH_FIELD_SURFACE_ALPHA),
                    ),
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    color = visuals.nearBlack.copy(alpha = SEARCH_RESULTS_SURFACE_ALPHA),
                    contentColor = visuals.onIncoming,
                    tonalElevation = 0.dp,
                ) {
                    when (state) {
                        is SearchUiState.Empty -> SearchMessage(stringResource(R.string.search_empty))
                        is SearchUiState.Searching -> SearchMessage(
                            stringResource(R.string.searching),
                            progress = true,
                        )
                        is SearchUiState.Invalid -> SearchMessage(stringResource(R.string.search_invalid))
                        is SearchUiState.Page -> SearchResults(
                            state = state,
                            onOpenHit = onOpenHit,
                            onOpenConversation = onOpenConversation,
                            onLoadMore = onLoadMore,
                        )
                    }
                }
            }
        }
    }
}

const val SEARCH_SCREEN_TEST_TAG: String = "aurora-search-screen"
const val SEARCH_FIELD_TEST_TAG: String = "aurora-search-field"
const val SEARCH_HIT_TEST_TAG: String = "aurora-search-hit"
private const val MAXIMUM_SAVED_SEARCH_QUERY_CHARACTERS: Int = 256
private val SEARCH_FIELD_MINIMUM_HEIGHT = 56.dp
private val SEARCH_AVATAR_RING_WIDTH = 2.dp
private const val SEARCH_UNFOCUSED_OUTLINE_ALPHA: Float = 0.82f
private const val SEARCH_FIELD_SURFACE_ALPHA: Float = 0.94f
private const val SEARCH_RESULTS_SURFACE_ALPHA: Float = 0.94f
private const val SEARCH_AVATAR_SURFACE_ALPHA: Float = 0.90f
private const val SEARCH_TIMESTAMP_ALPHA: Float = 0.82f
private const val SEARCH_SNIPPET_ALPHA: Float = 0.90f

@Composable
private fun SearchResults(
    state: SearchUiState.Page,
    onOpenHit: (SearchHit) -> Unit,
    onOpenConversation: (ProviderThreadId) -> Unit,
    onLoadMore: () -> Unit,
) {
    val visuals = LocalAuroraVisualTokens.current
    if (state.items.isEmpty()) {
        SearchMessage(stringResource(R.string.search_no_results))
        return
    }
    val conversations = state.items.distinctBy(SearchHit::providerThreadId)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item(key = "conversation-heading") {
            Text(
                text = stringResource(R.string.conversations_in_results),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                color = visuals.cyan,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        items(
            count = conversations.size,
            key = { index -> "conversation-${conversations[index].providerThreadId.value}" },
        ) { index ->
            val hit = conversations[index]
            SearchResultRow(
                title = hit.senderAddress ?: stringResource(R.string.conversation),
                supportingText = null,
                timestampMillis = hit.timestampMillis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenConversation(hit.providerThreadId) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item(key = "message-heading") {
            Text(
                text = stringResource(R.string.matching_messages),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                color = visuals.cyan,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        items(
            count = state.items.size,
            key = { index -> state.items[index].localRowId },
        ) { index ->
            val hit = state.items[index]
            SearchResultRow(
                title = hit.senderAddress ?: stringResource(R.string.conversation),
                supportingText = hit.body ?: hit.subject.orEmpty(),
                timestampMillis = hit.timestampMillis,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SEARCH_HIT_TEST_TAG)
                    .clickable { onOpenHit(hit) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (state.next != null) {
            item(key = "load-more") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.loadingMore) {
                        CircularProgressIndicator()
                    } else {
                        Button(onClick = onLoadMore) { Text(stringResource(R.string.load_more)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    title: String,
    supportingText: String?,
    timestampMillis: Long,
    modifier: Modifier = Modifier,
) {
    val profile = LocalAuroraMaterialProfile.current
    val tokens = LocalAuroraMaterialTokens.current
    val visuals = LocalAuroraVisualTokens.current
    Row(
        modifier = modifier.heightIn(min = tokens.rowMinimumHeight),
        horizontalArrangement = Arrangement.spacedBy(tokens.contentSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(tokens.avatarSize),
            shape = profile.avatarMask.toShape(),
            color = visuals.deepNight.copy(alpha = SEARCH_AVATAR_SURFACE_ALPHA),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(SEARCH_AVATAR_RING_WIDTH, visuals.avatarRing),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = title.take(1).uppercase(),
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
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = formatTimestamp(timestampMillis),
                    color = visuals.lilacSecondary.copy(alpha = SEARCH_TIMESTAMP_ALPHA),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            supportingText?.takeIf(String::isNotBlank)?.let { text ->
                Text(
                    text = text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = visuals.lilacSecondary.copy(alpha = SEARCH_SNIPPET_ALPHA),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun SearchMessage(text: String, progress: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (progress) CircularProgressIndicator()
            Text(text, modifier = Modifier.padding(24.dp))
        }
    }
}
