// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
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
    conversationSignatureAvailable: Boolean = false,
    onOpenConversationSignature: () -> Unit = {},
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
    onDraftSubjectChanged: (String) -> Unit = {},
    onAddAttachment: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    voiceMemo: VoiceMemoUiState = VoiceMemoUiState(),
    onRecordVoiceMemo: () -> Unit = {},
    onStopVoiceMemo: () -> Unit = {},
    onCancelVoiceMemo: () -> Unit = {},
    onSendVoiceMemo: () -> Unit = {},
    onSend: () -> Unit = {},
    onUndoSend: () -> Unit = {},
    sendDelaySeconds: Int = 0,
    onSetSendDelaySeconds: (Int) -> Unit = {},
    onSchedule: () -> Unit = {},
    onCancelSchedule: () -> Unit = {},
    onRequestExactAlarmAccess: () -> Unit = {},
    onSelectSubscription: (AuroraSubscriptionId) -> Unit = {},
    onAcknowledgeSubmissionUnknown: () -> Unit = {},
    deletion: PermanentDeletionUiState = PermanentDeletionUiState.None,
    onRequestDeleteMessage: (TimelineMessage) -> Unit = {},
    onRequestDeleteThread: () -> Unit = {},
    onUndoDeletion: () -> Unit = {},
    onRetryDeletionStatus: () -> Unit = {},
    spamSafety: ThreadSpamSafetyUiState = ThreadSpamSafetyUiState(),
    onMarkSpam: () -> Unit = {},
    onMarkNotSpam: () -> Unit = {},
    onBlockSender: () -> Unit = {},
    onUnblockSender: () -> Unit = {},
    timelineBackground: @Composable BoxScope.() -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val visualTokens = LocalAuroraVisualTokens.current
    var composerFocused by remember { mutableStateOf(false) }
    var messagePendingConfirmation by remember { mutableStateOf<TimelineMessage?>(null) }
    var messageActionTarget by remember { mutableStateOf<MessageActionTarget?>(null) }
    var textSelectionTarget by remember { mutableStateOf<MessageActionTarget?>(null) }
    var messageDetailsTarget by remember { mutableStateOf<TimelineMessage?>(null) }
    var threadConfirmationStep by remember { mutableStateOf(0) }
    var blockConfirmationOpen by remember { mutableStateOf(false) }
    val deletionActive = deletion !is PermanentDeletionUiState.None
    LaunchedEffect(deletion) {
        if (deletionActive) {
            messagePendingConfirmation = null
            messageActionTarget = null
            textSelectionTarget = null
            messageDetailsTarget = null
            threadConfirmationStep = 0
        }
    }
    messageActionTarget?.let { target ->
        MessageActionsDialog(
            target = target,
            deleteAvailable = target.message.syncFingerprint != null && !deletionActive,
            onDismiss = { messageActionTarget = null },
            onSelectText = {
                messageActionTarget = null
                textSelectionTarget = target
            },
            onShowDetails = {
                messageActionTarget = null
                messageDetailsTarget = target.message
            },
            onDelete = {
                messageActionTarget = null
                messagePendingConfirmation = target.message
            },
        )
    }
    textSelectionTarget?.let { target ->
        MessageTextSelectionDialog(
            target = target,
            onDismiss = { textSelectionTarget = null },
        )
    }
    messageDetailsTarget?.let { message ->
        MessageDetailsDialog(
            message = message,
            onDismiss = { messageDetailsTarget = null },
        )
    }
    messagePendingConfirmation?.let { message ->
        AlertDialog(
            onDismissRequest = { messagePendingConfirmation = null },
            title = { Text(stringResource(R.string.delete_message_title)) },
            text = { Text(stringResource(R.string.delete_message_explanation)) },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag(CONFIRM_DELETE_MESSAGE_TEST_TAG),
                    onClick = {
                        messagePendingConfirmation = null
                        onRequestDeleteMessage(message)
                    },
                ) { Text(stringResource(R.string.delete_permanently)) }
            },
            dismissButton = {
                TextButton(onClick = { messagePendingConfirmation = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (threadConfirmationStep == 1) {
        AlertDialog(
            onDismissRequest = { threadConfirmationStep = 0 },
            title = { Text(stringResource(R.string.delete_conversation_title)) },
            text = { Text(stringResource(R.string.delete_conversation_explanation)) },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag(CONTINUE_DELETE_THREAD_TEST_TAG),
                    onClick = { threadConfirmationStep = 2 },
                ) { Text(stringResource(R.string.delete_permanently)) }
            },
            dismissButton = {
                TextButton(onClick = { threadConfirmationStep = 0 }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (threadConfirmationStep == 2) {
        AlertDialog(
            onDismissRequest = { threadConfirmationStep = 0 },
            title = { Text(stringResource(R.string.delete_conversation_last_chance_title)) },
            text = { Text(stringResource(R.string.delete_conversation_last_chance_explanation)) },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag(CONFIRM_DELETE_THREAD_TEST_TAG),
                    onClick = {
                        threadConfirmationStep = 0
                        onRequestDeleteThread()
                    },
                ) { Text(stringResource(R.string.delete_conversation_permanently)) }
            },
            dismissButton = {
                TextButton(onClick = { threadConfirmationStep = 0 }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (blockConfirmationOpen) {
        AlertDialog(
            onDismissRequest = { blockConfirmationOpen = false },
            title = { Text(stringResource(R.string.block_sender_title)) },
            text = { Text(stringResource(R.string.block_sender_explanation)) },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag(CONFIRM_BLOCK_SENDER_TEST_TAG),
                    onClick = {
                        blockConfirmationOpen = false
                        onBlockSender()
                    },
                ) { Text(stringResource(R.string.block_sender)) }
            },
            dismissButton = {
                TextButton(onClick = { blockConfirmationOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
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
                conversationSignatureAvailable = conversationSignatureAvailable,
                onOpenConversationSignature = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    onOpenConversationSignature()
                },
                isDialable = isDialable,
                onDial = onDial,
                onSelectSubscription = onSelectSubscription,
                sendDelaySeconds = sendDelaySeconds,
                onSetSendDelaySeconds = onSetSendDelaySeconds,
                deleteConversationAvailable = state is ThreadUiState.Ready &&
                    state.coverage.verifiedComplete && state.window.items.isNotEmpty() &&
                    !deletionActive,
                onRequestDeleteConversation = { threadConfirmationStep = 1 },
                spamSafety = spamSafety,
                onMarkSpam = onMarkSpam,
                onMarkNotSpam = onMarkNotSpam,
                onBlockSender = { blockConfirmationOpen = true },
                onUnblockSender = onUnblockSender,
            )
            HorizontalDivider(color = visualTokens.violet.copy(alpha = 0.4f))
            PermanentDeletionBanner(
                state = deletion,
                onUndo = onUndoDeletion,
                onRetryStatus = onRetryDeletionStatus,
            )
            SpamSafetyBanner(spamSafety)
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
                            deletionActive = deletionActive,
                            onOpenMessageActions = { message, body, truncated ->
                                messageActionTarget = MessageActionTarget(message, body, truncated)
                            },
                            onRequestDeleteMessage = { messagePendingConfirmation = it },
                        )
                    }
                }
            }
            Composer(
                state = composer,
                voiceMemo = voiceMemo,
                onBodyChanged = onDraftChanged,
                onSubjectChanged = onDraftSubjectChanged,
                onAddAttachment = onAddAttachment,
                onRemoveAttachment = onRemoveAttachment,
                onFocusChanged = { composerFocused = it },
                onRecordVoiceMemo = onRecordVoiceMemo,
                onStopVoiceMemo = onStopVoiceMemo,
                onCancelVoiceMemo = onCancelVoiceMemo,
                onSendVoiceMemo = onSendVoiceMemo,
                onSend = onSend,
                onUndoSend = onUndoSend,
                onSchedule = onSchedule,
                onCancelSchedule = onCancelSchedule,
                onRequestExactAlarmAccess = onRequestExactAlarmAccess,
                onAcknowledgeSubmissionUnknown = onAcknowledgeSubmissionUnknown,
            )
        }
    }
}

@Composable
private fun MessageActionsDialog(
    target: MessageActionTarget,
    deleteAvailable: Boolean,
    onDismiss: () -> Unit,
    onSelectText: () -> Unit,
    onShowDetails: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag(MESSAGE_ACTIONS_DIALOG_TEST_TAG),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.message_actions)) },
        text = {
            Column {
                TextButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SELECT_MESSAGE_TEXT_ACTION_TEST_TAG),
                    enabled = !target.displayedBody.isNullOrEmpty(),
                    onClick = onSelectText,
                ) { Text(stringResource(R.string.select_text)) }
                TextButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(MESSAGE_DETAILS_ACTION_TEST_TAG),
                    onClick = onShowDetails,
                ) { Text(stringResource(R.string.message_details)) }
                if (deleteAvailable) {
                    TextButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(DELETE_MESSAGE_ACTION_TEST_TAG),
                        onClick = onDelete,
                    ) { Text(stringResource(R.string.delete_message)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun MessageTextSelectionDialog(
    target: MessageActionTarget,
    onDismiss: () -> Unit,
) {
    val displayedText = target.displayedBody.orEmpty()
    var fieldValue by remember(target.message.providerMessageId, displayedText) {
        mutableStateOf(TextFieldValue(displayedText))
    }
    val selectedText = selectedMessageTextOrNull(
        displayedText = displayedText,
        selectionStart = fieldValue.selection.start,
        selectionEnd = fieldValue.selection.end,
    )
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copying by remember(target.message.providerMessageId) { mutableStateOf(false) }
    var copyFailed by remember(target.message.providerMessageId) { mutableStateOf(false) }
    AlertDialog(
        modifier = Modifier.testTag(MESSAGE_TEXT_SELECTION_DIALOG_TEST_TAG),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_text)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (target.displayedBodyTruncated) {
                    Text(
                        text = stringResource(R.string.select_text_truncated_notice),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(MESSAGE_TEXT_SELECTION_FIELD_TEST_TAG),
                    value = fieldValue,
                    onValueChange = { updated ->
                        if (updated.text == displayedText) fieldValue = updated
                    },
                    readOnly = true,
                    maxLines = 12,
                    label = { Text(stringResource(R.string.message_text)) },
                )
                Text(
                    text = stringResource(R.string.select_text_instruction),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (copyFailed) {
                    Text(
                        text = stringResource(R.string.copy_selected_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag(COPY_SELECTED_TEXT_TEST_TAG),
                enabled = selectedText != null && !copying,
                onClick = {
                    val exactSelection = selectedText ?: return@TextButton
                    copying = true
                    copyFailed = false
                    scope.launch {
                        try {
                            clipboard.setClipEntry(
                                ClipEntry(
                                    ClipData.newPlainText(
                                        "AuroraSMS selected text",
                                        exactSelection,
                                    ),
                                ),
                            )
                            onDismiss()
                        } catch (_: SecurityException) {
                            copying = false
                            copyFailed = true
                        }
                    }
                },
            ) { Text(stringResource(R.string.copy_selected)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun MessageDetailsDialog(
    message: TimelineMessage,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag(MESSAGE_DETAILS_DIALOG_TEST_TAG),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.message_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                MessageDetailRow(
                    label = stringResource(R.string.message_type),
                    value = stringResource(
                        if (message.providerMessageId.kind == ProviderKind.SMS) {
                            R.string.message_type_sms
                        } else {
                            R.string.message_type_mms
                        },
                    ),
                )
                MessageDetailRow(
                    label = stringResource(R.string.message_direction),
                    value = stringResource(
                        if (message.direction == MessageDirection.INCOMING) {
                            R.string.incoming_message
                        } else {
                            R.string.outgoing_message
                        },
                    ),
                )
                MessageDetailRow(
                    label = stringResource(R.string.message_date_and_time),
                    value = formatMessageDetailsTimestamp(message.timestampMillis),
                )
                MessageDetailRow(
                    label = stringResource(R.string.message_status),
                    value = messageDetailsStatus(message),
                )
                MessageDetailRow(
                    label = stringResource(R.string.message_subscription),
                    value = message.subscriptionId?.let {
                        stringResource(R.string.subscription_id, it.value)
                    } ?: stringResource(R.string.not_available),
                )
                MessageDetailRow(
                    label = stringResource(R.string.message_attachments),
                    value = if (message.attachmentCount == 0) {
                        stringResource(R.string.no_attachments)
                    } else {
                        pluralStringResource(
                            R.plurals.attachment_count,
                            message.attachmentCount,
                            message.attachmentCount,
                        )
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun MessageDetailRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            color = LocalAuroraVisualTokens.current.lilacSecondary,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
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
    conversationSignatureAvailable: Boolean,
    onOpenConversationSignature: () -> Unit,
    isDialable: (ParticipantAddress) -> Boolean,
    onDial: (ParticipantAddress) -> Unit,
    onSelectSubscription: (AuroraSubscriptionId) -> Unit,
    sendDelaySeconds: Int,
    onSetSendDelaySeconds: (Int) -> Unit,
    deleteConversationAvailable: Boolean,
    onRequestDeleteConversation: () -> Unit,
    spamSafety: ThreadSpamSafetyUiState,
    onMarkSpam: () -> Unit,
    onMarkNotSpam: () -> Unit,
    onBlockSender: () -> Unit,
    onUnblockSender: () -> Unit,
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
        ThreadMoreMenu(
            conversationAppearanceAvailable = conversationAppearanceAvailable,
            onOpenConversationAppearance = onOpenConversationAppearance,
            conversationSignatureAvailable = conversationSignatureAvailable,
            onOpenConversationSignature = onOpenConversationSignature,
            sendDelaySeconds = sendDelaySeconds,
            onSetSendDelaySeconds = onSetSendDelaySeconds,
            deleteConversationAvailable = deleteConversationAvailable,
            onRequestDeleteConversation = onRequestDeleteConversation,
            spamSafety = spamSafety,
            onMarkSpam = onMarkSpam,
            onMarkNotSpam = onMarkNotSpam,
            onBlockSender = onBlockSender,
            onUnblockSender = onUnblockSender,
        )
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
    conversationAppearanceAvailable: Boolean,
    onOpenConversationAppearance: () -> Unit,
    conversationSignatureAvailable: Boolean,
    onOpenConversationSignature: () -> Unit,
    sendDelaySeconds: Int,
    onSetSendDelaySeconds: (Int) -> Unit,
    deleteConversationAvailable: Boolean,
    onRequestDeleteConversation: () -> Unit,
    spamSafety: ThreadSpamSafetyUiState,
    onMarkSpam: () -> Unit,
    onMarkNotSpam: () -> Unit,
    onBlockSender: () -> Unit,
    onUnblockSender: () -> Unit,
) {
    val visualTokens = LocalAuroraVisualTokens.current
    var expanded by remember { mutableStateOf(false) }
    var showSendDelay by remember { mutableStateOf(false) }
    if (showSendDelay) {
        AlertDialog(
            onDismissRequest = { showSendDelay = false },
            title = { Text(stringResource(R.string.send_delay)) },
            text = {
                Column {
                    SEND_DELAY_SECOND_OPTIONS.forEach { seconds ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                onSetSendDelaySeconds(seconds)
                                showSendDelay = false
                            },
                        ) {
                            Text(
                                if (seconds == 0) {
                                    stringResource(R.string.send_immediately)
                                } else {
                                    pluralStringResource(
                                        R.plurals.send_delay_seconds,
                                        seconds,
                                        seconds,
                                    )
                                },
                                color = if (seconds == sendDelaySeconds) {
                                    visualTokens.cyan
                                } else {
                                    visualTokens.onIncoming
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSendDelay = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
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
            if (spamSafety.actionsAvailable) {
                DropdownMenuItem(
                    modifier = Modifier.testTag(THREAD_SPAM_ACTION_TEST_TAG),
                    enabled = !spamSafety.saving,
                    text = {
                        Text(
                            stringResource(
                                if (spamSafety.classificationSpam) {
                                    R.string.mark_not_spam
                                } else {
                                    R.string.mark_as_spam
                                },
                            ),
                            color = visualTokens.onIncoming,
                        )
                    },
                    onClick = {
                        expanded = false
                        if (spamSafety.classificationSpam) onMarkNotSpam() else onMarkSpam()
                    },
                )
            }
            if (spamSafety.blockAvailable) {
                DropdownMenuItem(
                    modifier = Modifier.testTag(THREAD_BLOCK_ACTION_TEST_TAG),
                    enabled = !spamSafety.saving,
                    text = {
                        Text(
                            stringResource(
                                if (spamSafety.blocked) R.string.unblock_sender
                                else R.string.block_sender,
                            ),
                            color = visualTokens.onIncoming,
                        )
                    },
                    onClick = {
                        expanded = false
                        if (spamSafety.blocked) onUnblockSender() else onBlockSender()
                    },
                )
            }
            if (conversationAppearanceAvailable) {
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
            if (conversationSignatureAvailable) {
                DropdownMenuItem(
                    modifier = Modifier.testTag(THREAD_SIGNATURE_ACTION_TEST_TAG),
                    text = {
                        Text(
                            stringResource(R.string.conversation_signature),
                            color = visualTokens.onIncoming,
                        )
                    },
                    onClick = {
                        expanded = false
                        onOpenConversationSignature()
                    },
                )
            }
            DropdownMenuItem(
                modifier = Modifier.testTag(THREAD_SEND_DELAY_ACTION_TEST_TAG),
                text = {
                    Text(
                        if (sendDelaySeconds == 0) {
                            stringResource(R.string.send_delay_off)
                        } else {
                            pluralStringResource(
                                R.plurals.send_delay_current,
                                sendDelaySeconds,
                                sendDelaySeconds,
                            )
                        },
                        color = visualTokens.onIncoming,
                    )
                },
                onClick = {
                    expanded = false
                    showSendDelay = true
                },
            )
            DropdownMenuItem(
                modifier = Modifier.testTag(THREAD_DELETE_ACTION_TEST_TAG),
                enabled = deleteConversationAvailable,
                text = {
                    Text(
                        stringResource(R.string.delete_conversation),
                        color = if (deleteConversationAvailable) {
                            MaterialTheme.colorScheme.error
                        } else {
                            visualTokens.lilacSecondary
                        },
                    )
                },
                onClick = {
                    expanded = false
                    onRequestDeleteConversation()
                },
            )
        }
    }
}

@Composable
private fun SpamSafetyBanner(state: ThreadSpamSafetyUiState) {
    val reason = state.warningReason ?: return
    val warning = reason == SpamSafetyReason.USER_MARKED_SPAM ||
        reason == SpamSafetyReason.USER_BLOCKED ||
        reason == SpamSafetyReason.SUSPICIOUS_LINK_AND_REQUEST
    if (!warning) return
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(THREAD_SPAM_WARNING_TEST_TAG),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            text = when (reason) {
                SpamSafetyReason.USER_MARKED_SPAM ->
                    stringResource(R.string.user_marked_spam_reason)
                SpamSafetyReason.USER_BLOCKED -> stringResource(R.string.user_blocked_reason)
                SpamSafetyReason.SUSPICIOUS_LINK_AND_REQUEST ->
                    stringResource(R.string.suspicious_link_request_reason)
                SpamSafetyReason.USER_MARKED_NOT_SPAM ->
                    stringResource(R.string.user_marked_not_spam_reason)
                SpamSafetyReason.SAVED_CONTACT -> stringResource(R.string.saved_contact_reason)
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PermanentDeletionBanner(
    state: PermanentDeletionUiState,
    onUndo: () -> Unit,
    onRetryStatus: () -> Unit,
) {
    if (state is PermanentDeletionUiState.None || state is PermanentDeletionUiState.Loading) return
    val visualTokens = LocalAuroraVisualTokens.current
    val text = when (state) {
        is PermanentDeletionUiState.Pending -> stringResource(
            if (state.targetKind == PermanentDeletionTargetUiKind.MESSAGE) {
                R.string.deleting_message_pending
            } else {
                R.string.deleting_conversation_pending
            },
        )
        is PermanentDeletionUiState.Committing -> stringResource(R.string.deleting_permanently)
        is PermanentDeletionUiState.ReviewRequired -> stringResource(
            if (state.commitMayHaveStarted) {
                R.string.deletion_needs_review
            } else {
                R.string.deletion_paused_safe
            },
        )
        PermanentDeletionUiState.Loading,
        PermanentDeletionUiState.None,
        -> return
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PERMANENT_DELETION_BANNER_TEST_TAG),
        color = visualTokens.elevatedSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.72f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                color = visualTokens.onIncoming,
                style = MaterialTheme.typography.bodySmall,
            )
            when (state) {
                is PermanentDeletionUiState.Pending -> TextButton(
                    modifier = Modifier.testTag(UNDO_DELETION_TEST_TAG),
                    onClick = onUndo,
                ) { Text(stringResource(R.string.undo)) }
                is PermanentDeletionUiState.ReviewRequired -> TextButton(
                    modifier = Modifier.testTag(REVIEW_DELETION_TEST_TAG),
                    onClick = if (state.commitMayHaveStarted) onRetryStatus else onUndo,
                ) {
                    Text(
                        stringResource(
                            if (state.commitMayHaveStarted) R.string.check_status
                            else R.string.keep_item,
                        ),
                    )
                }
                is PermanentDeletionUiState.Committing,
                PermanentDeletionUiState.Loading,
                PermanentDeletionUiState.None,
                -> Unit
            }
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
    deletionActive: Boolean,
    onOpenMessageActions: (TimelineMessage, String?, Boolean) -> Unit,
    onRequestDeleteMessage: (TimelineMessage) -> Unit,
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
            val checkedMessages = state.coverage.generationCommittedCount
            val pluralCount = checkedMessages.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(INDEX_INCOMPLETE_NOTICE_TEST_TAG),
                color = visualTokens.nearBlack.copy(alpha = 0.94f),
                border = BorderStroke(1.dp, visualTokens.violet.copy(alpha = 0.32f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = visualTokens.violet,
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.index_incomplete_progress,
                            pluralCount,
                            checkedMessages,
                        ),
                        modifier = Modifier.weight(1f),
                        color = visualTokens.lilacSecondary,
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
                        deleteAvailable = message.syncFingerprint != null && !deletionActive,
                        onOpenActions = { body, truncated ->
                            onOpenMessageActions(message, body, truncated)
                        },
                        onRequestDelete = { onRequestDeleteMessage(message) },
                    )
                }
                item(key = "thread-bottom-space", contentType = "spacing") { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
    deleteAvailable: Boolean,
    onOpenActions: (String?, Boolean) -> Unit,
    onRequestDelete: () -> Unit,
) {
    val tokens = LocalAuroraMaterialTokens.current
    val visualTokens = LocalAuroraVisualTokens.current
    val incoming = message.direction == MessageDirection.INCOMING
    val directionDescription = stringResource(
        if (incoming) R.string.incoming_message else R.string.outgoing_message,
    )
    val messageActionsDescription = stringResource(R.string.message_actions)
    val deleteMessageDescription = stringResource(R.string.delete_message)
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
            .combinedClickable(
                onClick = {},
                onLongClick = { onOpenActions(displayedBody, displayedBodyTruncated) },
            )
            .semantics {
                stateDescription = directionDescription
                customActions = buildList {
                    add(
                        CustomAccessibilityAction(
                            label = messageActionsDescription,
                            action = {
                                onOpenActions(displayedBody, displayedBodyTruncated)
                                true
                            },
                        ),
                    )
                    if (deleteAvailable) {
                        add(
                            CustomAccessibilityAction(
                                label = deleteMessageDescription,
                                action = {
                                    onRequestDelete()
                                    true
                                },
                            ),
                        )
                    }
                }
            }
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
                    displayedBody?.takeIf(String::isNotEmpty)?.let { body ->
                        val reaction = body
                            .takeIf { !displayedBodyTruncated }
                            ?.let(::parseReactionFallback)
                        if (reaction == null) {
                            Text(body)
                        } else {
                            ReactionFallbackCard(reaction, bubbleMetadataColor)
                        }
                    }
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
private fun ReactionFallbackCard(
    reaction: ReactionFallback,
    supportingColor: androidx.compose.ui.graphics.Color,
) {
    val label = stringResource(reaction.kind.labelResource())
    val symbol = reaction.kind.symbol()
    val accessibilityLabel = stringResource(
        R.string.reaction_accessibility,
        label,
        reaction.targetText,
    )
    Surface(
        modifier = Modifier
            .semantics(mergeDescendants = true) {
                contentDescription = accessibilityLabel
            }
            .testTag(REACTION_FALLBACK_TEST_TAG),
        shape = RoundedCornerShape(12.dp),
        color = supportingColor.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, supportingColor.copy(alpha = 0.52f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(symbol, style = MaterialTheme.typography.titleMedium)
            Column {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "“${reaction.targetText}”",
                    color = supportingColor,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun ReactionFallbackKind.labelResource(): Int = when (this) {
    ReactionFallbackKind.LIKE_ADDED -> R.string.reaction_liked
    ReactionFallbackKind.LOVE_ADDED -> R.string.reaction_loved
    ReactionFallbackKind.LAUGH_ADDED -> R.string.reaction_laughed
    ReactionFallbackKind.EMPHASIS_ADDED -> R.string.reaction_emphasized
    ReactionFallbackKind.QUESTION_ADDED -> R.string.reaction_questioned
    ReactionFallbackKind.DISLIKE_ADDED -> R.string.reaction_disliked
    ReactionFallbackKind.LIKE_REMOVED -> R.string.reaction_removed_like
    ReactionFallbackKind.LOVE_REMOVED -> R.string.reaction_removed_love
    ReactionFallbackKind.LAUGH_REMOVED -> R.string.reaction_removed_laugh
    ReactionFallbackKind.EMPHASIS_REMOVED -> R.string.reaction_removed_emphasis
    ReactionFallbackKind.QUESTION_REMOVED -> R.string.reaction_removed_question
    ReactionFallbackKind.DISLIKE_REMOVED -> R.string.reaction_removed_dislike
}

private fun ReactionFallbackKind.symbol(): String = when (this) {
    ReactionFallbackKind.LIKE_ADDED -> "👍"
    ReactionFallbackKind.LOVE_ADDED -> "♥"
    ReactionFallbackKind.LAUGH_ADDED -> "😂"
    ReactionFallbackKind.EMPHASIS_ADDED -> "‼"
    ReactionFallbackKind.QUESTION_ADDED -> "?"
    ReactionFallbackKind.DISLIKE_ADDED -> "👎"
    ReactionFallbackKind.LIKE_REMOVED,
    ReactionFallbackKind.LOVE_REMOVED,
    ReactionFallbackKind.LAUGH_REMOVED,
    ReactionFallbackKind.EMPHASIS_REMOVED,
    ReactionFallbackKind.QUESTION_REMOVED,
    ReactionFallbackKind.DISLIKE_REMOVED,
    -> "−"
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
    Text(
        text = deliveryStatusText(message),
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun deliveryStatusText(message: TimelineMessage): String = when {
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

@Composable
private fun messageDetailsStatus(message: TimelineMessage): String =
    if (message.direction == MessageDirection.INCOMING) {
        stringResource(if (message.read) R.string.received_read else R.string.received_unread)
    } else {
        deliveryStatusText(message)
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
    voiceMemo: VoiceMemoUiState,
    onBodyChanged: (String) -> Unit,
    onSubjectChanged: (String) -> Unit,
    onAddAttachment: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onRecordVoiceMemo: () -> Unit,
    onStopVoiceMemo: () -> Unit,
    onCancelVoiceMemo: () -> Unit,
    onSendVoiceMemo: () -> Unit,
    onSend: () -> Unit,
    onUndoSend: () -> Unit,
    onSchedule: () -> Unit,
    onCancelSchedule: () -> Unit,
    onRequestExactAlarmAccess: () -> Unit,
    onAcknowledgeSubmissionUnknown: () -> Unit,
) {
    val visualTokens = LocalAuroraVisualTokens.current
    var showUnknownConfirmation by remember { mutableStateOf(false) }
    var showScheduleDetails by remember { mutableStateOf(false) }
    var showExtrasMenu by remember { mutableStateOf(false) }
    var showSubject by rememberSaveable { mutableStateOf(state.subject.isNotBlank()) }
    LaunchedEffect(state.subject) {
        if (state.subject.isNotBlank()) showSubject = true
    }
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
        state.attachmentImporting -> stringResource(R.string.preparing_image_attachment)
        state.attachmentFailure == ComposerAttachmentFailure.UNREADABLE ->
            stringResource(R.string.image_attachment_unreadable)
        state.attachmentFailure == ComposerAttachmentFailure.UNSUPPORTED ->
            stringResource(R.string.image_attachment_unsupported)
        state.attachmentFailure == ComposerAttachmentFailure.TOO_LARGE ->
            stringResource(R.string.image_attachment_too_large)
        state.attachmentFailure == ComposerAttachmentFailure.LIMIT_REACHED ->
            stringResource(R.string.image_attachment_limit_reached)
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
        state.sendState == ComposerSendState.DELAY_PENDING ->
            stringResource(R.string.undo_send_available)
        state.sendState == ComposerSendState.DELAY_REVIEW ->
            stringResource(R.string.delayed_send_not_sent)
        state.sendState == ComposerSendState.KNOWN_UNSENT ->
            stringResource(R.string.message_not_sent_draft_preserved)
        state.sendState == ComposerSendState.SUBMISSION_UNKNOWN ->
            stringResource(R.string.send_status_unknown_supporting)
        state.unavailableReason == ComposerUnavailableReason.CONVERSATION_UNVERIFIED ->
            stringResource(R.string.verifying_conversation_for_send)
        state.unavailableReason == ComposerUnavailableReason.GROUP_REQUIRES_MMS ->
            stringResource(
                if (state.signatureApplied) {
                    R.string.group_send_requires_mms_with_signature
                } else {
                    R.string.group_send_requires_mms
                },
            )
        state.unavailableReason == ComposerUnavailableReason.SUBSCRIPTION_UNAVAILABLE ->
            stringResource(R.string.conversation_sim_unavailable)
        state.unavailableReason == ComposerUnavailableReason.MULTIPART_UNAVAILABLE ->
            if (state.signatureApplied) {
                stringResource(
                    R.string.multipart_signature_impact,
                    state.unsignedSegmentCount ?: state.segmentCount ?: 2,
                    state.segmentCount ?: 2,
                )
            } else {
                stringResource(
                    R.string.multipart_send_unavailable,
                    state.segmentCount ?: 2,
                )
            }
        state.unavailableReason == ComposerUnavailableReason.RECOVERY_PENDING ->
            stringResource(R.string.finishing_send_recovery)
        state.unavailableReason == ComposerUnavailableReason.PERMANENT_DELETION_ACTIVE ->
            stringResource(R.string.deletion_in_progress)
        state.unavailableReason == ComposerUnavailableReason.MESSAGING_UNAVAILABLE ->
            stringResource(R.string.messaging_send_unavailable)
        state.unavailableReason == ComposerUnavailableReason.SIGNATURE_STATE_UNAVAILABLE ->
            stringResource(R.string.signature_state_unavailable)
        state.unavailableReason == ComposerUnavailableReason.EMPTY_MESSAGE ->
            stringResource(R.string.type_message_to_send)
        state.unavailableReason == ComposerUnavailableReason.DRAFT_NOT_DURABLE ->
            stringResource(R.string.saving_draft)
        state.sendState == ComposerSendState.READY && state.mmsRequired ->
            stringResource(R.string.draft_saved_mms)
        state.sendState == ComposerSendState.READY -> if (state.signatureApplied) {
            stringResource(
                R.string.signature_included_sms_segments,
                state.segmentCount ?: 1,
            )
        } else {
            stringResource(R.string.draft_saved_one_sms)
        }
        else -> stringResource(R.string.draft_saved)
    }
    val actionLabel = stringResource(
        when (state.sendState) {
            ComposerSendState.READY -> R.string.send
            ComposerSendState.DELAY_PENDING -> R.string.undo_send
            ComposerSendState.DELAY_REVIEW -> R.string.keep_as_draft
            ComposerSendState.SENDING -> R.string.sending
            ComposerSendState.KNOWN_UNSENT -> R.string.retry_send
            ComposerSendState.SUBMISSION_UNKNOWN -> R.string.review_send
            ComposerSendState.UNAVAILABLE -> R.string.send_unavailable
        },
    )
    val actionGlyph = when (state.sendState) {
        ComposerSendState.KNOWN_UNSENT -> AuroraGlyph.RETRY
        ComposerSendState.SUBMISSION_UNKNOWN -> AuroraGlyph.REVIEW
        ComposerSendState.DELAY_PENDING -> AuroraGlyph.BACK
        ComposerSendState.DELAY_REVIEW -> AuroraGlyph.REVIEW
        else -> AuroraGlyph.SEND
    }
    val scheduleActive = state.scheduleState is ComposerScheduleState.Pending ||
        state.scheduleState is ComposerScheduleState.Dispatching ||
        state.scheduleState is ComposerScheduleState.ReviewRequired
    val scheduleLoading = state.scheduleState is ComposerScheduleState.Loading
    val actionEnabled = !state.attachmentImporting && !scheduleActive &&
        (state.sendState == ComposerSendState.READY ||
        state.sendState == ComposerSendState.KNOWN_UNSENT ||
        state.sendState == ComposerSendState.DELAY_PENDING ||
        state.sendState == ComposerSendState.DELAY_REVIEW ||
        state.sendState == ComposerSendState.SUBMISSION_UNKNOWN)
    val composerEditingEnabled = !state.failed &&
        !scheduleActive &&
        !scheduleLoading &&
        state.sendState != ComposerSendState.SENDING &&
        state.sendState != ComposerSendState.DELAY_PENDING &&
        state.sendState != ComposerSendState.DELAY_REVIEW &&
        state.sendState != ComposerSendState.SUBMISSION_UNKNOWN &&
        !state.attachmentImporting &&
        state.unavailableReason != ComposerUnavailableReason.RECOVERY_PENDING &&
        state.unavailableReason != ComposerUnavailableReason.PERMANENT_DELETION_ACTIVE
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(visualTokens.deepNight.copy(alpha = 0.98f)),
    ) {
        HorizontalDivider(color = visualTokens.violet.copy(alpha = 0.5f))
        VoiceMemoPanel(
            state = voiceMemo,
            onStop = onStopVoiceMemo,
            onCancel = onCancelVoiceMemo,
            onSend = onSendVoiceMemo,
        )
        if (state.attachments.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 56.dp, end = 16.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.attachments.forEach { attachment ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("$COMPOSER_ATTACHMENT_TEST_TAG_PREFIX-${attachment.index}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                R.string.image_attachment_size,
                                (attachment.sizeBytes + 1_023) / 1_024,
                            ),
                            modifier = Modifier.weight(1f),
                            color = visualTokens.lilacSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            enabled = composerEditingEnabled,
                            onClick = { onRemoveAttachment(attachment.index) },
                        ) {
                            Text(stringResource(R.string.remove_attachment))
                        }
                    }
                }
            }
        }
        if (showSubject) {
            OutlinedTextField(
                value = state.subject,
                onValueChange = { value ->
                    if (value.length <= MAXIMUM_COMPOSER_SUBJECT_CHARACTERS) {
                        onSubjectChanged(value)
                    }
                },
                enabled = composerEditingEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 56.dp, end = 16.dp, top = 8.dp)
                    .testTag(COMPOSER_SUBJECT_TEST_TAG),
                singleLine = true,
                label = { Text(stringResource(R.string.message_subject)) },
                shape = RoundedCornerShape(20.dp),
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
                ),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val extrasActionLabel = stringResource(R.string.add_to_message)
            val subjectActionLabel = stringResource(
                if (showSubject) R.string.remove_message_subject else R.string.add_message_subject,
            )
            Box {
                AuroraIconAction(
                    glyph = AuroraGlyph.ADD,
                    contentDescription = extrasActionLabel,
                    onClick = { showExtrasMenu = true },
                    enabled = composerEditingEnabled,
                    modifier = Modifier
                        .testTag(COMPOSER_EXTRAS_ACTION_TEST_TAG)
                        .semantics { text = AnnotatedString(extrasActionLabel) },
                    tint = if (showSubject || state.attachments.isNotEmpty()) {
                        visualTokens.cyan
                    } else {
                        visualTokens.violet
                    },
                )
                DropdownMenu(
                    expanded = showExtrasMenu,
                    onDismissRequest = { showExtrasMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.add_image_attachment)) },
                        onClick = {
                            showExtrasMenu = false
                            onAddAttachment()
                        },
                        enabled = composerEditingEnabled &&
                            state.attachments.size < MAXIMUM_COMPOSER_ATTACHMENTS,
                        modifier = Modifier.testTag(COMPOSER_ADD_IMAGE_ACTION_TEST_TAG),
                    )
                    DropdownMenuItem(
                        text = { Text(subjectActionLabel) },
                        onClick = {
                            showExtrasMenu = false
                            if (showSubject) onSubjectChanged("")
                            showSubject = !showSubject
                        },
                        enabled = composerEditingEnabled,
                        modifier = Modifier.testTag(COMPOSER_SUBJECT_ACTION_TEST_TAG),
                    )
                }
            }
            OutlinedTextField(
                value = state.body,
                onValueChange = { value ->
                    if (value.length <= MAXIMUM_COMPOSER_CHARACTERS) onBodyChanged(value)
                },
                enabled = composerEditingEnabled,
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
            val recording = voiceMemo.phase == VoiceMemoUiPhase.RECORDING
            val voiceActionLabel = stringResource(
                if (recording) R.string.stop_voice_memo else R.string.record_voice_memo,
            )
            AuroraIconAction(
                glyph = if (recording) AuroraGlyph.STOP else AuroraGlyph.MICROPHONE,
                contentDescription = voiceActionLabel,
                onClick = if (recording) onStopVoiceMemo else onRecordVoiceMemo,
                enabled = recording || voiceMemo.recordEnabled,
                modifier = Modifier
                    .testTag(COMPOSER_VOICE_MEMO_TEST_TAG)
                    .semantics { text = AnnotatedString(voiceActionLabel) },
                tint = if (recording) MaterialTheme.colorScheme.error else visualTokens.violet,
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
                    (!state.mmsRequired && !scheduleLoading &&
                        state.sendState == ComposerSendState.READY),
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
                    } else if (
                        state.sendState == ComposerSendState.DELAY_PENDING ||
                        state.sendState == ComposerSendState.DELAY_REVIEW
                    ) {
                        onUndoSend()
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
                state.attachmentFailure != null ||
                state.sendState == ComposerSendState.KNOWN_UNSENT ||
                state.sendState == ComposerSendState.DELAY_REVIEW ||
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

@Composable
private fun VoiceMemoPanel(
    state: VoiceMemoUiState,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    if (state.phase == VoiceMemoUiPhase.IDLE) return
    val visualTokens = LocalAuroraVisualTokens.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag(VOICE_MEMO_PANEL_TEST_TAG),
        shape = RoundedCornerShape(18.dp),
        color = visualTokens.nearBlack.copy(alpha = 0.96f),
        border = BorderStroke(
            1.dp,
            if (state.phase == VoiceMemoUiPhase.RECORDING) {
                MaterialTheme.colorScheme.error
            } else {
                visualTokens.violet.copy(alpha = 0.72f)
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when (state.phase) {
                    VoiceMemoUiPhase.IDLE -> ""
                    VoiceMemoUiPhase.PREPARING -> stringResource(R.string.preparing_voice_memo)
                    VoiceMemoUiPhase.RECORDING -> stringResource(
                        R.string.recording_voice_memo,
                        formatVoiceMemoDuration(state.elapsedMillis),
                    )
                    VoiceMemoUiPhase.READY -> stringResource(
                        if (state.retryAfterKnownFailure) {
                            R.string.voice_memo_ready_after_failure
                        } else {
                            R.string.voice_memo_ready
                        },
                        formatVoiceMemoDuration(checkNotNull(state.durationMillis)),
                        ((checkNotNull(state.sizeBytes) + 1_023) / 1_024).coerceAtLeast(1),
                    )
                    VoiceMemoUiPhase.SENDING -> stringResource(R.string.submitting_voice_memo)
                    VoiceMemoUiPhase.AWAITING_RESULT -> stringResource(R.string.voice_memo_awaiting_result)
                    VoiceMemoUiPhase.SENT -> stringResource(R.string.voice_memo_sent)
                    VoiceMemoUiPhase.FAILED -> stringResource(
                        when (state.failure) {
                            VoiceMemoUiFailure.PERMISSION_DENIED -> R.string.microphone_permission_denied
                            VoiceMemoUiFailure.CAPTURE_FAILED -> R.string.voice_memo_capture_failed
                            VoiceMemoUiFailure.SEND_REJECTED -> R.string.voice_memo_send_rejected
                            VoiceMemoUiFailure.SUBMISSION_UNKNOWN -> R.string.voice_memo_submission_unknown
                            VoiceMemoUiFailure.SEND_FAILED -> R.string.voice_memo_send_failed
                            null -> R.string.voice_memo_capture_failed
                        },
                    )
                },
                modifier = Modifier.weight(1f),
                color = if (
                    state.phase == VoiceMemoUiPhase.RECORDING ||
                    state.phase == VoiceMemoUiPhase.FAILED
                ) {
                    MaterialTheme.colorScheme.error
                } else {
                    visualTokens.onIncoming
                },
                style = MaterialTheme.typography.bodySmall,
            )
            when (state.phase) {
                VoiceMemoUiPhase.PREPARING,
                VoiceMemoUiPhase.RECORDING,
                -> {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                    if (state.phase == VoiceMemoUiPhase.RECORDING) {
                        TextButton(
                            modifier = Modifier.testTag(STOP_VOICE_MEMO_TEST_TAG),
                            onClick = onStop,
                        ) { Text(stringResource(R.string.stop)) }
                    }
                }
                VoiceMemoUiPhase.READY -> {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.discard)) }
                    Button(
                        modifier = Modifier.testTag(SEND_VOICE_MEMO_TEST_TAG),
                        onClick = onSend,
                    ) { Text(stringResource(R.string.send_voice_memo)) }
                }
                VoiceMemoUiPhase.SENT,
                VoiceMemoUiPhase.FAILED,
                -> TextButton(onClick = onCancel) { Text(stringResource(R.string.dismiss)) }
                VoiceMemoUiPhase.IDLE,
                VoiceMemoUiPhase.SENDING,
                VoiceMemoUiPhase.AWAITING_RESULT,
                -> Unit
            }
        }
    }
}

private fun formatVoiceMemoDuration(durationMillis: Long): String {
    val totalSeconds = (durationMillis / 1_000L).coerceIn(0L, 60L)
    return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
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

private data class MessageActionTarget(
    val message: TimelineMessage,
    val displayedBody: String?,
    val displayedBodyTruncated: Boolean,
) {
    override fun toString(): String =
        "MessageActionTarget(message=${message.providerMessageId}, " +
            "hasBody=${displayedBody != null}, displayedBodyTruncated=$displayedBodyTruncated, REDACTED)"
}

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

private fun formatMessageDetailsTimestamp(timestampMillis: Long): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()))

private const val MAXIMUM_HEADER_NAMES: Int = 3
private const val THREAD_VIEWPORT_PREFETCH_ROWS: Int = 10
private const val MAXIMUM_VIEWPORT_THREAD_ROWS: Int = 100
private const val MAXIMUM_COMPOSER_CHARACTERS: Int = 100_000
private const val MAXIMUM_COMPOSER_SUBJECT_CHARACTERS: Int = 1_000
private const val MAXIMUM_COMPOSER_ATTACHMENTS: Int = 10
const val THREAD_SCREEN_TEST_TAG: String = "aurora-thread-screen"
const val THREAD_LIST_TEST_TAG: String = "aurora-thread-list"
const val MESSAGE_BUBBLE_TEST_TAG: String = "aurora-message-bubble"
const val MESSAGE_ACTIONS_DIALOG_TEST_TAG: String = "aurora-message-actions"
const val SELECT_MESSAGE_TEXT_ACTION_TEST_TAG: String = "aurora-select-message-text"
const val MESSAGE_DETAILS_ACTION_TEST_TAG: String = "aurora-message-details-action"
const val DELETE_MESSAGE_ACTION_TEST_TAG: String = "aurora-delete-message-action"
const val MESSAGE_TEXT_SELECTION_DIALOG_TEST_TAG: String = "aurora-message-text-selection"
const val MESSAGE_TEXT_SELECTION_FIELD_TEST_TAG: String = "aurora-message-text-selection-field"
const val COPY_SELECTED_TEXT_TEST_TAG: String = "aurora-copy-selected-text"
const val MESSAGE_DETAILS_DIALOG_TEST_TAG: String = "aurora-message-details"
const val REACTION_FALLBACK_TEST_TAG: String = "aurora-reaction-fallback"
const val COMPOSER_TEST_TAG: String = "aurora-composer"
const val COMPOSER_SUBJECT_TEST_TAG: String = "aurora-composer-subject"
const val COMPOSER_SUBJECT_ACTION_TEST_TAG: String = "aurora-composer-subject-action"
const val COMPOSER_EXTRAS_ACTION_TEST_TAG: String = "aurora-composer-extras-action"
const val COMPOSER_ADD_IMAGE_ACTION_TEST_TAG: String = "aurora-composer-add-image-action"
const val COMPOSER_ATTACHMENT_TEST_TAG_PREFIX: String = "aurora-composer-attachment"
const val COMPOSER_SEND_TEST_TAG: String = "aurora-composer-send"
const val COMPOSER_SCHEDULE_TEST_TAG: String = "aurora-composer-schedule"
const val COMPOSER_VOICE_MEMO_TEST_TAG: String = "aurora-composer-voice-memo"
const val VOICE_MEMO_PANEL_TEST_TAG: String = "aurora-voice-memo-panel"
const val STOP_VOICE_MEMO_TEST_TAG: String = "aurora-stop-voice-memo"
const val SEND_VOICE_MEMO_TEST_TAG: String = "aurora-send-voice-memo"
const val THREAD_SEND_DELAY_ACTION_TEST_TAG: String = "aurora-thread-send-delay"
const val THREAD_DELETE_ACTION_TEST_TAG: String = "aurora-thread-delete"
const val THREAD_SPAM_ACTION_TEST_TAG: String = "aurora-thread-spam-action"
const val THREAD_BLOCK_ACTION_TEST_TAG: String = "aurora-thread-block-action"
const val THREAD_SPAM_WARNING_TEST_TAG: String = "aurora-thread-spam-warning"
const val CONFIRM_BLOCK_SENDER_TEST_TAG: String = "aurora-confirm-block-sender"
const val CONFIRM_DELETE_MESSAGE_TEST_TAG: String = "aurora-confirm-delete-message"
const val CONTINUE_DELETE_THREAD_TEST_TAG: String = "aurora-continue-delete-thread"
const val CONFIRM_DELETE_THREAD_TEST_TAG: String = "aurora-confirm-delete-thread"
const val PERMANENT_DELETION_BANNER_TEST_TAG: String = "aurora-deletion-banner"
const val UNDO_DELETION_TEST_TAG: String = "aurora-undo-deletion"
const val REVIEW_DELETION_TEST_TAG: String = "aurora-review-deletion"

private val SEND_DELAY_SECOND_OPTIONS = listOf(0, 1, 3, 5, 10)
const val THREAD_MORE_ACTION_TEST_TAG: String = "aurora-thread-more-action"
const val THREAD_SIGNATURE_ACTION_TEST_TAG: String = "aurora-thread-signature-action"
const val THREAD_APPEARANCE_ACTION_TEST_TAG: String = "aurora-thread-appearance-action"
const val THREAD_SIM_SELECTOR_TEST_TAG: String = "aurora-thread-sim-selector"
