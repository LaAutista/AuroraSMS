// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.aurorasms.core.designsystem.AuroraBackdrop
import org.aurorasms.core.designsystem.AuroraGlyph
import org.aurorasms.core.designsystem.AuroraIconAction
import org.aurorasms.core.designsystem.LocalAuroraVisualTokens
import org.aurorasms.core.model.ParticipantAddress

/** Presentation-only recipient information. Parsing and persistence remain app-owned. */
data class NewMessageRecipientUiItem(
    val address: ParticipantAddress,
    val displayLabel: String = address.value,
) {
    init {
        require(displayLabel.isNotBlank()) { "A recipient label cannot be blank" }
        require(displayLabel == displayLabel.trim()) { "A recipient label must be trimmed" }
        require(displayLabel.length <= MAXIMUM_RECIPIENT_LABEL_CHARACTERS) {
            "A recipient label is too long"
        }
        require(displayLabel.none(Char::isISOControl)) {
            "A recipient label contains a control character"
        }
    }

    override fun toString(): String = "NewMessageRecipientUiItem(REDACTED)"
}

/** One bounded, presentation-only result returned by app-owned contact discovery. */
data class NewMessageContactResultUiItem(
    val address: ParticipantAddress,
    val displayLabel: String = address.value,
) {
    init {
        requireValidContactResultLabel(displayLabel)
    }

    override fun toString(): String = "NewMessageContactResultUiItem(REDACTED)"
}

/**
 * Controlled contact-discovery presentation. Contact access, searching, and permission decisions
 * stay outside this feature module; query values are redacted from diagnostic strings.
 */
sealed interface NewMessageContactDiscoveryUiState {
    data object Closed : NewMessageContactDiscoveryUiState

    sealed interface Open : NewMessageContactDiscoveryUiState {
        val query: String
    }

    data class Empty(
        override val query: String = "",
        val truncated: Boolean = false,
    ) : Open {
        init {
            requireValidContactDiscoveryQuery(query)
            require(!truncated || query.isNotBlank()) {
                "A truncated empty contact result requires a nonblank query"
            }
        }

        override fun toString(): String =
            "NewMessageContactDiscoveryUiState.Empty(truncated=$truncated, REDACTED)"
    }

    data class Loading(override val query: String) : Open {
        init {
            requireValidContactDiscoveryQuery(query)
        }

        override fun toString(): String = "NewMessageContactDiscoveryUiState.Loading(REDACTED)"
    }

    data class Unavailable(override val query: String = "") : Open {
        init {
            requireValidContactDiscoveryQuery(query)
        }

        override fun toString(): String =
            "NewMessageContactDiscoveryUiState.Unavailable(REDACTED)"
    }

    data class Error(override val query: String) : Open {
        init {
            requireValidContactDiscoveryQuery(query)
        }

        override fun toString(): String = "NewMessageContactDiscoveryUiState.Error(REDACTED)"
    }

    data class Results(
        override val query: String,
        val items: List<NewMessageContactResultUiItem>,
        val truncated: Boolean = false,
    ) : Open {
        init {
            requireValidContactDiscoveryQuery(query)
            require(query.isNotBlank()) { "Contact results require a nonblank query" }
            require(items.isNotEmpty()) { "Contact results cannot use an empty results state" }
            require(items.size <= MAXIMUM_NEW_MESSAGE_CONTACT_RESULTS) {
                "Contact discovery results must remain bounded"
            }
            require(items.map { it.address }.distinct().size == items.size) {
                "Contact discovery results must have unique addresses"
            }
        }

        override fun toString(): String =
            "NewMessageContactDiscoveryUiState.Results(" +
                "itemCount=${items.size}, truncated=$truncated, REDACTED)"
    }
}

enum class NewMessageRecipientError {
    EMPTY,
    INVALID,
    DUPLICATE,
    TOO_MANY,
}

enum class NewMessageDraftStatus {
    WAITING_FOR_RECIPIENT,
    READY,
    LOADING,
    SAVING,
    SAVED,
    REVIEW_ONLY,
    FAILED,
}

/**
 * Controlled first-contact surface. This composable does not parse recipients, persist drafts, or
 * send messages; those decisions intentionally stay at the app boundary.
 */
@Composable
fun NewMessageScreen(
    recipientInput: String,
    committedRecipients: List<NewMessageRecipientUiItem>,
    body: String,
    onBack: () -> Unit,
    onRecipientInputChanged: (String) -> Unit,
    onCommitRecipients: (String) -> Unit,
    onRemoveRecipient: (ParticipantAddress) -> Unit,
    onBodyChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
    contactDiscoveryState: NewMessageContactDiscoveryUiState =
        NewMessageContactDiscoveryUiState.Closed,
    onOpenContactDiscovery: () -> Unit = {},
    onCloseContactDiscovery: () -> Unit = {},
    onContactDiscoveryQueryChanged: (String) -> Unit = {},
    onRetryContactDiscovery: () -> Unit = {},
    onSelectDiscoveredContact: (ParticipantAddress) -> Unit = {},
    recipientError: NewMessageRecipientError? = null,
    draftStatus: NewMessageDraftStatus = NewMessageDraftStatus.WAITING_FOR_RECIPIENT,
    recipientEditingEnabled: Boolean = true,
    bodyEditingEnabled: Boolean = true,
    externalPrefillConflict: Boolean = false,
    explicitMmsRequested: Boolean = false,
) {
    val visuals = LocalAuroraVisualTokens.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var editorFocused by remember { mutableStateOf(false) }
    val storageFailed = draftStatus == NewMessageDraftStatus.FAILED
    val editingBlocked = storageFailed || draftStatus == NewMessageDraftStatus.LOADING

    BackHandler {
        if (editorFocused) {
            focusManager.clearFocus()
            keyboard?.hide()
        } else if (contactDiscoveryState is NewMessageContactDiscoveryUiState.Open) {
            onCloseContactDiscovery()
        } else {
            onBack()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .semantics { testTagsAsResourceId = true }
            .testTag(NEW_MESSAGE_SCREEN_TEST_TAG),
        color = visuals.nearBlack,
        contentColor = visuals.onIncoming,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AuroraBackdrop(modifier = Modifier.fillMaxSize())
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
            ) {
                NewMessageHeader(
                    onBack = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        if (contactDiscoveryState is NewMessageContactDiscoveryUiState.Open) {
                            onCloseContactDiscovery()
                        } else {
                            onBack()
                        }
                    },
                )
                HorizontalDivider(color = visuals.violet.copy(alpha = 0.4f))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    RecipientEditor(
                        recipientInput = recipientInput,
                        committedRecipients = committedRecipients,
                        recipientError = recipientError,
                        enabled = !editingBlocked && recipientEditingEnabled,
                        lockedByDraft = !recipientEditingEnabled,
                        onRecipientInputChanged = onRecipientInputChanged,
                        onCommitRecipients = onCommitRecipients,
                        onRemoveRecipient = onRemoveRecipient,
                        contactDiscoveryOpen =
                            contactDiscoveryState is NewMessageContactDiscoveryUiState.Open,
                        onOpenContactDiscovery = onOpenContactDiscovery,
                        onCloseContactDiscovery = onCloseContactDiscovery,
                        onFocusChanged = { editorFocused = it },
                    )
                    if (externalPrefillConflict) {
                        NewMessageNotice(
                            text = stringResource(R.string.new_message_external_conflict),
                            error = true,
                            modifier = Modifier.testTag(NEW_MESSAGE_EXTERNAL_CONFLICT_TEST_TAG),
                        )
                    }
                    if (explicitMmsRequested) {
                        NewMessageNotice(
                            text = stringResource(R.string.new_message_explicit_mms_notice),
                            error = false,
                            modifier = Modifier.testTag(NEW_MESSAGE_EXPLICIT_MMS_TEST_TAG),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (contactDiscoveryState) {
                            NewMessageContactDiscoveryUiState.Closed -> {
                                Text(
                                    text = stringResource(
                                        if (committedRecipients.isEmpty()) {
                                            R.string.new_message_choose_recipient_hint
                                        } else {
                                            R.string.new_message_draft_hint
                                        },
                                    ),
                                    modifier = Modifier.padding(horizontal = 32.dp),
                                    color = visuals.lilacSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }

                            is NewMessageContactDiscoveryUiState.Open -> {
                                ContactDiscoveryPanel(
                                    state = contactDiscoveryState,
                                    enabled = !editingBlocked && recipientEditingEnabled,
                                    onQueryChanged = onContactDiscoveryQueryChanged,
                                    onRetry = onRetryContactDiscovery,
                                    onSelect = onSelectDiscoveredContact,
                                    onFocusChanged = { editorFocused = it },
                                )
                            }
                        }
                    }
                    NewMessageNotice(
                        text = stringResource(R.string.new_message_send_disabled_notice),
                        error = false,
                        modifier = Modifier.testTag(NEW_MESSAGE_SEND_DISABLED_NOTICE_TEST_TAG),
                    )
                }
                MessageComposer(
                    state = ComposerUiState(
                        body = body,
                        saving = draftStatus == NewMessageDraftStatus.SAVING,
                        failed = storageFailed,
                    ),
                    onBodyChanged = onBodyChanged,
                    controls = MessageComposerControls.NEW_MESSAGE_DRAFT,
                    editingEnabled = !editingBlocked &&
                        bodyEditingEnabled &&
                        committedRecipients.isNotEmpty(),
                    supportingTextOverride = newMessageDraftStatusText(draftStatus),
                    supportingTextIsError = storageFailed,
                    onFocusChanged = { editorFocused = it },
                )
            }
        }
    }
}

@Composable
private fun NewMessageHeader(onBack: () -> Unit) {
    val visuals = LocalAuroraVisualTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuroraIconAction(
            glyph = AuroraGlyph.BACK,
            contentDescription = stringResource(R.string.back),
            onClick = onBack,
            modifier = Modifier.testTag(NEW_MESSAGE_BACK_ACTION_TEST_TAG),
            tint = visuals.lilacSecondary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.new_message_title),
                color = visuals.onIncoming,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.new_message_subtitle),
                color = visuals.lilacSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun RecipientEditor(
    recipientInput: String,
    committedRecipients: List<NewMessageRecipientUiItem>,
    recipientError: NewMessageRecipientError?,
    enabled: Boolean,
    lockedByDraft: Boolean,
    onRecipientInputChanged: (String) -> Unit,
    onCommitRecipients: (String) -> Unit,
    onRemoveRecipient: (ParticipantAddress) -> Unit,
    contactDiscoveryOpen: Boolean,
    onOpenContactDiscovery: () -> Unit,
    onCloseContactDiscovery: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    val visuals = LocalAuroraVisualTokens.current
    val canCommit = enabled && recipientInput.isNotBlank()
    val commit = {
        if (canCommit) onCommitRecipients(recipientInput)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        shape = RoundedCornerShape(24.dp),
        color = visuals.elevatedSurface.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, visuals.violet.copy(alpha = 0.58f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (committedRecipients.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(NEW_MESSAGE_RECIPIENT_LIST_TEST_TAG),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = committedRecipients,
                        key = { it.address.value },
                    ) { recipient ->
                        val removeLabel = stringResource(
                            R.string.remove_new_message_recipient,
                            recipient.displayLabel,
                        )
                        InputChip(
                            selected = true,
                            enabled = enabled,
                            onClick = { onRemoveRecipient(recipient.address) },
                            modifier = Modifier
                                .testTag(
                                    "$NEW_MESSAGE_RECIPIENT_CHIP_TEST_TAG_PREFIX-" +
                                        committedRecipients.indexOf(recipient),
                                )
                                .semantics { contentDescription = removeLabel },
                            label = {
                                Text(
                                    text = recipient.displayLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingIcon = {
                                Text(
                                    text = "×",
                                    modifier = Modifier.size(18.dp),
                                    fontWeight = FontWeight.Bold,
                                )
                            },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = recipientInput,
                    onValueChange = { value ->
                        if (value.length <= MAXIMUM_RECIPIENT_INPUT_CHARACTERS) {
                            onRecipientInputChanged(value)
                        }
                    },
                    enabled = enabled,
                    isError = recipientError != null,
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { onFocusChanged(it.isFocused) }
                        .testTag(NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG),
                    singleLine = true,
                    label = { Text(stringResource(R.string.new_message_recipient_label)) },
                    placeholder = { Text(stringResource(R.string.new_message_recipient_placeholder)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = visuals.onIncoming,
                        unfocusedTextColor = visuals.onIncoming,
                        disabledTextColor = visuals.lilacSecondary,
                        focusedContainerColor = visuals.nearBlack.copy(alpha = 0.96f),
                        unfocusedContainerColor = visuals.nearBlack.copy(alpha = 0.9f),
                        disabledContainerColor = visuals.nearBlack.copy(alpha = 0.78f),
                        cursorColor = visuals.cyan,
                        focusedBorderColor = visuals.cyan,
                        unfocusedBorderColor = visuals.violet.copy(alpha = 0.68f),
                        errorBorderColor = MaterialTheme.colorScheme.error,
                    ),
                )
                TextButton(
                    onClick = commit,
                    enabled = canCommit,
                    modifier = Modifier.testTag(NEW_MESSAGE_COMMIT_RECIPIENT_TEST_TAG),
                ) {
                    Text(stringResource(R.string.add_recipient))
                }
            }
            Text(
                text = recipientError?.let { newMessageRecipientErrorText(it) }
                    ?: stringResource(
                        if (lockedByDraft) {
                            R.string.new_message_recipient_locked
                        } else {
                            R.string.new_message_recipient_help
                        },
                    ),
                modifier = Modifier.testTag(NEW_MESSAGE_RECIPIENT_SUPPORT_TEST_TAG),
                color = if (recipientError != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    visuals.lilacSecondary
                },
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = if (contactDiscoveryOpen) {
                    onCloseContactDiscovery
                } else {
                    onOpenContactDiscovery
                },
                enabled = enabled || contactDiscoveryOpen,
                modifier = Modifier.testTag(NEW_MESSAGE_CONTACT_DISCOVERY_ACTION_TEST_TAG),
            ) {
                Text(
                    stringResource(
                        if (contactDiscoveryOpen) {
                            R.string.close_contact_discovery
                        } else {
                            R.string.find_contacts
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun ContactDiscoveryPanel(
    state: NewMessageContactDiscoveryUiState.Open,
    enabled: Boolean,
    onQueryChanged: (String) -> Unit,
    onRetry: () -> Unit,
    onSelect: (ParticipantAddress) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    val visuals = LocalAuroraVisualTokens.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(NEW_MESSAGE_CONTACT_DISCOVERY_PANEL_TEST_TAG),
        shape = RoundedCornerShape(24.dp),
        color = visuals.elevatedSurface.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, visuals.cyan.copy(alpha = 0.58f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.contact_discovery_title),
                color = visuals.onIncoming,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = state.query,
                onValueChange = { query ->
                    if (query.length <= MAXIMUM_NEW_MESSAGE_CONTACT_QUERY_CHARACTERS &&
                        query.none(Char::isISOControl)
                    ) {
                        onQueryChanged(query)
                    }
                },
                enabled = enabled && state !is NewMessageContactDiscoveryUiState.Unavailable,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .onFocusChanged { onFocusChanged(it.isFocused) }
                    .testTag(NEW_MESSAGE_CONTACT_DISCOVERY_QUERY_TEST_TAG),
                singleLine = true,
                label = { Text(stringResource(R.string.contact_discovery_query_label)) },
                placeholder = {
                    Text(stringResource(R.string.contact_discovery_query_placeholder))
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = visuals.onIncoming,
                    unfocusedTextColor = visuals.onIncoming,
                    disabledTextColor = visuals.lilacSecondary,
                    focusedContainerColor = visuals.nearBlack.copy(alpha = 0.96f),
                    unfocusedContainerColor = visuals.nearBlack.copy(alpha = 0.9f),
                    disabledContainerColor = visuals.nearBlack.copy(alpha = 0.78f),
                    cursorColor = visuals.cyan,
                    focusedBorderColor = visuals.cyan,
                    unfocusedBorderColor = visuals.violet.copy(alpha = 0.68f),
                ),
            )
            when (state) {
                is NewMessageContactDiscoveryUiState.Empty -> {
                    ContactDiscoveryStatus(
                        text = stringResource(
                            if (state.query.isBlank()) {
                                R.string.contact_discovery_empty_prompt
                            } else if (state.truncated) {
                                R.string.contact_discovery_truncated_empty
                            } else {
                                R.string.contact_discovery_no_results
                            },
                        ),
                    )
                }

                is NewMessageContactDiscoveryUiState.Loading -> {
                    ContactDiscoveryStatus(
                        text = stringResource(R.string.contact_discovery_loading),
                        progress = true,
                    )
                }

                is NewMessageContactDiscoveryUiState.Unavailable -> {
                    ContactDiscoveryStatus(
                        text = stringResource(R.string.contact_discovery_unavailable),
                        error = false,
                        actionText = stringResource(R.string.manage_contact_access),
                        actionEnabled = enabled,
                        onAction = onRetry,
                    )
                }

                is NewMessageContactDiscoveryUiState.Error -> {
                    ContactDiscoveryStatus(
                        text = stringResource(R.string.contact_discovery_error),
                        error = true,
                        actionText = stringResource(R.string.retry),
                        actionEnabled = enabled,
                        onAction = onRetry,
                    )
                }

                is NewMessageContactDiscoveryUiState.Results -> {
                    if (state.truncated) {
                        Text(
                            text = stringResource(R.string.contact_discovery_truncated),
                            modifier = Modifier.testTag(
                                NEW_MESSAGE_CONTACT_DISCOVERY_TRUNCATED_TEST_TAG,
                            ),
                            color = visuals.lilacSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(NEW_MESSAGE_CONTACT_DISCOVERY_RESULTS_TEST_TAG),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        state.items.forEachIndexed { index, item ->
                            key(item.address.value) {
                                TextButton(
                                    onClick = { onSelect(item.address) },
                                    enabled = enabled,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag(
                                            "$NEW_MESSAGE_CONTACT_DISCOVERY_RESULT_TEST_TAG_PREFIX-" +
                                                index,
                                        ),
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text(
                                            text = item.displayLabel,
                                            color = visuals.onIncoming,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (item.displayLabel != item.address.value) {
                                            Text(
                                                text = item.address.value,
                                                color = visuals.lilacSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactDiscoveryStatus(
    text: String,
    progress: Boolean = false,
    error: Boolean = false,
    actionText: String? = null,
    actionEnabled: Boolean = false,
    onAction: () -> Unit = {},
) {
    val visuals = LocalAuroraVisualTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 16.dp)
            .testTag(NEW_MESSAGE_CONTACT_DISCOVERY_STATUS_TEST_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (progress) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = visuals.cyan,
            )
        }
        Text(
            text = text,
            color = if (error) MaterialTheme.colorScheme.error else visuals.lilacSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (actionText != null) {
            TextButton(
                onClick = onAction,
                enabled = actionEnabled,
                modifier = Modifier.testTag(NEW_MESSAGE_CONTACT_DISCOVERY_RETRY_TEST_TAG),
            ) {
                Text(actionText)
            }
        }
    }
}

@Composable
private fun NewMessageNotice(
    text: String,
    error: Boolean,
    modifier: Modifier = Modifier,
) {
    val visuals = LocalAuroraVisualTokens.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = visuals.nearBlack.copy(alpha = 0.94f),
        border = BorderStroke(
            1.dp,
            if (error) MaterialTheme.colorScheme.error else visuals.violet.copy(alpha = 0.45f),
        ),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = if (error) MaterialTheme.colorScheme.error else visuals.lilacSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun newMessageRecipientErrorText(error: NewMessageRecipientError): String =
    stringResource(
        when (error) {
            NewMessageRecipientError.EMPTY -> R.string.new_message_recipient_empty
            NewMessageRecipientError.INVALID -> R.string.new_message_recipient_invalid
            NewMessageRecipientError.DUPLICATE -> R.string.new_message_recipient_duplicate
            NewMessageRecipientError.TOO_MANY -> R.string.new_message_recipient_too_many
        },
    )

@Composable
private fun newMessageDraftStatusText(status: NewMessageDraftStatus): String =
    stringResource(
        when (status) {
            NewMessageDraftStatus.WAITING_FOR_RECIPIENT ->
                R.string.new_message_draft_waiting_for_recipient
            NewMessageDraftStatus.READY -> R.string.new_message_draft_ready
            NewMessageDraftStatus.LOADING -> R.string.new_message_draft_loading
            NewMessageDraftStatus.SAVING -> R.string.saving_draft
            NewMessageDraftStatus.SAVED -> R.string.draft_saved
            NewMessageDraftStatus.REVIEW_ONLY -> R.string.new_message_external_review_only
            NewMessageDraftStatus.FAILED -> R.string.draft_failed
        },
    )

const val NEW_MESSAGE_SCREEN_TEST_TAG: String = "aurora-new-message-screen"
const val NEW_MESSAGE_BACK_ACTION_TEST_TAG: String = "aurora-new-message-back-action"
const val NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG: String = "aurora-new-message-recipient-input"
const val NEW_MESSAGE_COMMIT_RECIPIENT_TEST_TAG: String = "aurora-new-message-commit-recipient"
const val NEW_MESSAGE_RECIPIENT_LIST_TEST_TAG: String = "aurora-new-message-recipient-list"
const val NEW_MESSAGE_RECIPIENT_CHIP_TEST_TAG_PREFIX: String = "aurora-new-message-recipient-chip"
const val NEW_MESSAGE_RECIPIENT_SUPPORT_TEST_TAG: String = "aurora-new-message-recipient-support"
const val NEW_MESSAGE_EXTERNAL_CONFLICT_TEST_TAG: String = "aurora-new-message-external-conflict"
const val NEW_MESSAGE_EXPLICIT_MMS_TEST_TAG: String = "aurora-new-message-explicit-mms"
const val NEW_MESSAGE_SEND_DISABLED_NOTICE_TEST_TAG: String = "aurora-new-message-send-disabled"
const val NEW_MESSAGE_CONTACT_DISCOVERY_ACTION_TEST_TAG: String =
    "aurora-new-message-contact-discovery-action"
const val NEW_MESSAGE_CONTACT_DISCOVERY_PANEL_TEST_TAG: String =
    "aurora-new-message-contact-discovery-panel"
const val NEW_MESSAGE_CONTACT_DISCOVERY_QUERY_TEST_TAG: String =
    "aurora-new-message-contact-discovery-query"
const val NEW_MESSAGE_CONTACT_DISCOVERY_STATUS_TEST_TAG: String =
    "aurora-new-message-contact-discovery-status"
const val NEW_MESSAGE_CONTACT_DISCOVERY_RESULTS_TEST_TAG: String =
    "aurora-new-message-contact-discovery-results"
const val NEW_MESSAGE_CONTACT_DISCOVERY_RESULT_TEST_TAG_PREFIX: String =
    "aurora-new-message-contact-discovery-result"
const val NEW_MESSAGE_CONTACT_DISCOVERY_RETRY_TEST_TAG: String =
    "aurora-new-message-contact-discovery-retry"
const val NEW_MESSAGE_CONTACT_DISCOVERY_TRUNCATED_TEST_TAG: String =
    "aurora-new-message-contact-discovery-truncated"

const val MAXIMUM_NEW_MESSAGE_CONTACT_RESULTS: Int = 20
const val MAXIMUM_NEW_MESSAGE_CONTACT_QUERY_CHARACTERS: Int = 100

private const val MAXIMUM_RECIPIENT_INPUT_CHARACTERS: Int = 32_099
private const val MAXIMUM_RECIPIENT_LABEL_CHARACTERS: Int = 320
private const val MAXIMUM_CONTACT_RESULT_LABEL_CHARACTERS: Int = 1_000

private fun requireValidContactResultLabel(label: String) {
    require(label.isNotBlank()) { "A contact result label cannot be blank" }
    require(label == label.trim()) { "A contact result label must be trimmed" }
    require(label.length <= MAXIMUM_CONTACT_RESULT_LABEL_CHARACTERS) {
        "A contact result label is too long"
    }
    require(label.none(Char::isISOControl)) {
        "A contact result label contains a control character"
    }
}

private fun requireValidContactDiscoveryQuery(query: String) {
    require(query.length <= MAXIMUM_NEW_MESSAGE_CONTACT_QUERY_CHARACTERS) {
        "A contact discovery query is too long"
    }
    require(query.none(Char::isISOControl)) {
        "A contact discovery query contains a control character"
    }
}
