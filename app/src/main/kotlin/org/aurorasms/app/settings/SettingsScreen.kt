// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.settings

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.aurorasms.app.R
import org.aurorasms.core.designsystem.AuroraBackdrop
import org.aurorasms.core.designsystem.AuroraGlyph
import org.aurorasms.core.designsystem.AuroraIconAction
import org.aurorasms.core.designsystem.LocalAuroraVisualTokens

@Composable
internal fun SettingsScreen(
    onOpenAppearance: () -> Unit,
    onOpenSpamBlocked: () -> Unit,
    onOpenBackupRestore: () -> Unit,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val rows = listOf(
        SettingsRow(
            title = stringResource(R.string.settings_appearance),
            description = stringResource(R.string.settings_appearance_summary),
            tag = SETTINGS_APPEARANCE_TEST_TAG,
            onClick = onOpenAppearance,
        ),
        SettingsRow(
            title = stringResource(R.string.settings_spam_blocked),
            description = stringResource(R.string.settings_spam_blocked_summary),
            tag = SETTINGS_SPAM_BLOCKED_TEST_TAG,
            onClick = onOpenSpamBlocked,
        ),
        SettingsRow(
            title = stringResource(R.string.settings_backup_restore),
            description = stringResource(R.string.settings_backup_restore_summary),
            tag = SETTINGS_BACKUP_RESTORE_TEST_TAG,
            onClick = onOpenBackupRestore,
        ),
    )
    val normalized = query.trim()
    val visibleRows = if (normalized.isEmpty()) {
        rows
    } else {
        rows.filter { row ->
            row.title.contains(normalized, ignoreCase = true) ||
                row.description.contains(normalized, ignoreCase = true)
        }
    }
    val visuals = LocalAuroraVisualTokens.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
            .testTag(SETTINGS_SCREEN_TEST_TAG),
    ) {
        AuroraBackdrop(Modifier.fillMaxSize())
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                top = 12.dp,
                end = 16.dp,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AuroraIconAction(
                        glyph = AuroraGlyph.BACK,
                        contentDescription = stringResource(R.string.settings_back),
                        onClick = onBack,
                        modifier = Modifier.testTag(SETTINGS_BACK_TEST_TAG),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.settings_summary),
                            color = visuals.lilacSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { value ->
                        if (value.length <= MAXIMUM_SETTINGS_QUERY_CHARACTERS) query = value
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SETTINGS_SEARCH_TEST_TAG),
                    label = { Text(stringResource(R.string.settings_search)) },
                    singleLine = true,
                )
            }
            if (visibleRows.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        color = visuals.elevatedSurface.copy(alpha = SETTINGS_SURFACE_ALPHA),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_no_results),
                            modifier = Modifier.padding(20.dp),
                            color = visuals.lilacSecondary,
                        )
                    }
                }
            } else {
                items(visibleRows, key = SettingsRow::tag) { row ->
                    SettingsDestination(row)
                }
            }
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_local_only_note),
                    color = visuals.lilacSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SettingsDestination(row: SettingsRow) {
    val visuals = LocalAuroraVisualTokens.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(row.tag)
            .clickable(onClick = row.onClick),
        shape = RoundedCornerShape(22.dp),
        color = visuals.elevatedSurface.copy(alpha = SETTINGS_SURFACE_ALPHA),
        border = BorderStroke(1.dp, visuals.violet.copy(alpha = SETTINGS_BORDER_ALPHA)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = row.description,
                    color = visuals.lilacSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = "›",
                modifier = Modifier.padding(start = 12.dp),
                color = visuals.cyan,
                style = MaterialTheme.typography.headlineSmall,
            )
        }
    }
}

private data class SettingsRow(
    val title: String,
    val description: String,
    val tag: String,
    val onClick: () -> Unit,
)

internal const val SETTINGS_SCREEN_TEST_TAG = "aurora-settings-screen"
internal const val SETTINGS_BACK_TEST_TAG = "aurora-settings-back"
internal const val SETTINGS_SEARCH_TEST_TAG = "aurora-settings-search"
internal const val SETTINGS_APPEARANCE_TEST_TAG = "aurora-settings-appearance"
internal const val SETTINGS_SPAM_BLOCKED_TEST_TAG = "aurora-settings-spam-blocked"
internal const val SETTINGS_BACKUP_RESTORE_TEST_TAG = "aurora-settings-backup-restore"
private const val MAXIMUM_SETTINGS_QUERY_CHARACTERS = 128
private const val SETTINGS_SURFACE_ALPHA = 0.94f
private const val SETTINGS_BORDER_ALPHA = 0.62f
