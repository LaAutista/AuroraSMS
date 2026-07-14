// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .semantics { testTagsAsResourceId = true }
            .testTag(SEARCH_SCREEN_TEST_TAG),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                Text(
                    text = stringResource(R.string.search_messages),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = { query ->
                    if (query.length <= MAXIMUM_SAVED_SEARCH_QUERY_CHARACTERS) onQueryChanged(query)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .onFocusChanged { queryFocused = it.isFocused }
                    .testTag(SEARCH_FIELD_TEST_TAG),
                singleLine = true,
                label = { Text(stringResource(R.string.search_hint)) },
            )
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

const val SEARCH_SCREEN_TEST_TAG: String = "aurora-search-screen"
const val SEARCH_FIELD_TEST_TAG: String = "aurora-search-field"
const val SEARCH_HIT_TEST_TAG: String = "aurora-search-hit"
private const val MAXIMUM_SAVED_SEARCH_QUERY_CHARACTERS: Int = 256

@Composable
private fun SearchResults(
    state: SearchUiState.Page,
    onOpenHit: (SearchHit) -> Unit,
    onOpenConversation: (ProviderThreadId) -> Unit,
    onLoadMore: () -> Unit,
) {
    if (state.items.isEmpty()) {
        SearchMessage(stringResource(R.string.search_no_results))
        return
    }
    val conversations = state.items.distinctBy(SearchHit::providerThreadId)
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "conversation-heading") {
            Text(
                text = stringResource(R.string.conversations_in_results),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        items(
            count = conversations.size,
            key = { index -> "conversation-${conversations[index].providerThreadId.value}" },
        ) { index ->
            val hit = conversations[index]
            Text(
                text = hit.senderAddress ?: stringResource(R.string.conversation),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenConversation(hit.providerThreadId) }
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        item(key = "message-heading") {
            HorizontalDivider()
            Text(
                text = stringResource(R.string.matching_messages),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        items(
            count = state.items.size,
            key = { index -> state.items[index].localRowId },
        ) { index ->
            val hit = state.items[index]
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SEARCH_HIT_TEST_TAG)
                    .clickable { onOpenHit(hit) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = hit.senderAddress ?: stringResource(R.string.conversation),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = hit.body ?: hit.subject.orEmpty(),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(formatTimestamp(hit.timestampMillis), style = MaterialTheme.typography.labelSmall)
            }
            HorizontalDivider()
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
