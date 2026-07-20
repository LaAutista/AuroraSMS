// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.absoluteValue
import kotlinx.coroutines.delay
import org.aurorasms.core.index.IndexCoverage
import org.aurorasms.core.index.IndexRunState
import org.aurorasms.core.index.timeline.TimelineMessage
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.MessageSyncFingerprint
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.telephony.MmsAttachmentContentReader
import org.aurorasms.core.telephony.ActiveSubscription
import org.aurorasms.core.telephony.MmsAttachmentId
import org.aurorasms.core.telephony.MmsAttachmentListResult
import org.aurorasms.core.telephony.MmsAttachmentReadResult
import org.aurorasms.core.telephony.MmsAttachmentRepository
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationUiStateTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun threadExposesStableListAndDisabledSendWithoutLoadingProviderMedia() {
        val repository = RejectingAttachmentRepository()
        compose.setContent {
            MaterialTheme {
                ThreadScreen(
                    state = readyThreadState(),
                    composer = ComposerUiState(body = "Synthetic draft", saving = false, failed = false),
                    attachmentRepository = repository,
                    previewLoader = RejectingPreviewLoader,
                    onBack = {},
                    onOpenSearch = {},
                    conversationAppearanceAvailable = false,
                    onOpenConversationAppearance = {},
                    isDialable = { false },
                    onDial = {},
                    onRetry = {},
                    onLoadOlder = {},
                    onLoadNewer = {},
                    onAtNewestChanged = {},
                    onAcceptPending = {},
                    onViewportChanged = {},
                    onAnchorRestored = {},
                    onToggleMessageExpansion = {},
                    onDraftChanged = {},
                )
            }
        }

        compose.onNodeWithTag(THREAD_LIST_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithText("Send unavailable").assertIsNotEnabled()
        compose.onNodeWithTag(THREAD_MORE_ACTION_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithTag(THREAD_APPEARANCE_ACTION_TEST_TAG).assertDoesNotExist()
        compose.runOnIdle { check(repository.readAttempts == 0) }
    }

    @Test
    fun exactReactionFallbackUsesStructuredPresentationWithoutChangingTheModelBody() {
        val source = "Liked “Synthetic original”"
        val state = readyThreadState(body = source)
        compose.setContent {
            MaterialTheme {
                ThreadScreen(
                    state = state,
                    composer = ComposerUiState(body = "", saving = false, failed = false),
                    attachmentRepository = RejectingAttachmentRepository(),
                    previewLoader = RejectingPreviewLoader,
                    onBack = {},
                    onOpenSearch = {},
                    conversationAppearanceAvailable = false,
                    onOpenConversationAppearance = {},
                    isDialable = { false },
                    onDial = {},
                    onRetry = {},
                    onLoadOlder = {},
                    onLoadNewer = {},
                    onAtNewestChanged = {},
                    onAcceptPending = {},
                    onViewportChanged = {},
                    onAnchorRestored = {},
                    onToggleMessageExpansion = {},
                    onDraftChanged = {},
                )
            }
        }

        compose.onNodeWithTag(REACTION_FALLBACK_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithText("Liked").assertIsDisplayed()
        compose.onNodeWithText("“Synthetic original”").assertIsDisplayed()
        compose.runOnIdle { check(state.window.items.single().bodyPreview == source) }
    }

    @Test
    fun ambiguousOrTruncatedReactionLikeTextRemainsRaw() {
        val ambiguous = "Liked “Synthetic original” trailing"
        compose.setContent {
            MaterialTheme {
                ThreadScreen(
                    state = readyThreadState(body = ambiguous, bodyTruncated = true),
                    composer = ComposerUiState(body = "", saving = false, failed = false),
                    attachmentRepository = RejectingAttachmentRepository(),
                    previewLoader = RejectingPreviewLoader,
                    onBack = {},
                    onOpenSearch = {},
                    conversationAppearanceAvailable = false,
                    onOpenConversationAppearance = {},
                    isDialable = { false },
                    onDial = {},
                    onRetry = {},
                    onLoadOlder = {},
                    onLoadNewer = {},
                    onAtNewestChanged = {},
                    onAcceptPending = {},
                    onViewportChanged = {},
                    onAnchorRestored = {},
                    onToggleMessageExpansion = {},
                    onDraftChanged = {},
                )
            }
        }

        compose.onNodeWithTag(REACTION_FALLBACK_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithText(ambiguous).assertIsDisplayed()
        compose.onNodeWithTag(MESSAGE_BUBBLE_TEST_TAG).performTouchInput {
            longClick(topCenter + Offset(0f, 12f))
        }
        compose.onNodeWithTag(SELECT_MESSAGE_TEXT_ACTION_TEST_TAG).performClick()
        compose.onNodeWithText(
            "Only the loaded preview is available. Show the full message first to select more.",
        ).assertIsDisplayed()
    }

    @Test
    fun readySendInvokesOnceThenSendingLocksTheActionAndEditor() {
        val composerState = mutableStateOf(
            ComposerUiState(
                body = "Synthetic ready draft",
                saving = false,
                failed = false,
                sendState = ComposerSendState.READY,
                segmentCount = 1,
            ),
        )
        var sendCount = 0
        compose.setContent {
            SyntheticThreadScreen(
                composer = composerState.value,
                onSend = {
                    sendCount += 1
                    composerState.value = composerState.value.copy(
                        sendState = ComposerSendState.SENDING,
                    )
                },
            )
        }

        compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG)
            .assertIsEnabled()
            .performClick()

        compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(COMPOSER_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithText("Sending…").assertIsDisplayed()
        compose.onNodeWithText("Submitting safely…").assertIsDisplayed()
        compose.runOnIdle { check(sendCount == 1) }
    }

    @Test
    fun pendingSendDelayOffersUndoAndLocksDraftUntilDecision() {
        var undoCount = 0
        compose.setContent {
            SyntheticThreadScreen(
                composer = ComposerUiState(
                    body = "Synthetic delayed draft",
                    saving = false,
                    failed = false,
                    sendState = ComposerSendState.DELAY_PENDING,
                    segmentCount = 1,
                    sendDelayDueTimestampMillis = 4_000_000_000_000L,
                ),
                onUndoSend = { undoCount += 1 },
            )
        }

        compose.onNodeWithTag(COMPOSER_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithText("Waiting briefly before send · Undo is available").assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsEnabled().performClick()
        compose.runOnIdle { check(undoCount == 1) }
    }

    @Test
    fun sendDelaySettingOffersOnlyApprovedChoicesAndReportsSelection() {
        var selected: Int? = null
        compose.setContent {
            SyntheticThreadScreen(
                composer = ComposerUiState(
                    body = "Synthetic ready draft",
                    saving = false,
                    failed = false,
                    sendState = ComposerSendState.READY,
                    segmentCount = 1,
                ),
                sendDelaySeconds = 3,
                onSetSendDelaySeconds = { selected = it },
            )
        }

        compose.onNodeWithTag(THREAD_MORE_ACTION_TEST_TAG).performClick()
        compose.onNodeWithText("Send delay · 3 seconds").performClick()
        compose.onNodeWithText("Send immediately").assertIsDisplayed()
        compose.onNodeWithText("Wait 1 second").assertIsDisplayed()
        compose.onNodeWithText("Wait 3 seconds").assertIsDisplayed()
        compose.onNodeWithText("Wait 5 seconds").assertIsDisplayed()
        compose.onNodeWithText("Wait 10 seconds").assertIsDisplayed().performClick()
        compose.runOnIdle { check(selected == 10) }
    }

    @Test
    fun scheduleActionAndConfirmedCancellationKeepSendLocked() {
        val composerState = mutableStateOf(
            ComposerUiState(
                body = "Synthetic scheduled draft",
                saving = false,
                failed = false,
                sendState = ComposerSendState.READY,
                segmentCount = 1,
            ),
        )
        var scheduleCount = 0
        var cancelCount = 0
        compose.setContent {
            SyntheticThreadScreen(
                composer = composerState.value,
                onSchedule = {
                    scheduleCount += 1
                    composerState.value = composerState.value.copy(
                        sendState = ComposerSendState.UNAVAILABLE,
                        scheduleState = ComposerScheduleState.Pending(
                            dueTimestampMillis = 4_000_000_000_000L,
                            exact = false,
                        ),
                    )
                },
                onCancelSchedule = { cancelCount += 1 },
            )
        }

        compose.onNodeWithTag(COMPOSER_SCHEDULE_TEST_TAG).assertIsEnabled().performClick()
        compose.runOnIdle { check(scheduleCount == 1) }
        compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithText("may send late", substring = true).assertIsDisplayed()

        compose.onNodeWithTag(COMPOSER_SCHEDULE_TEST_TAG).performClick()
        compose.onNodeWithText("Cancel schedule").performClick()
        compose.runOnIdle { check(cancelCount == 1) }
    }

    @Test
    fun dispatchingScheduleDoesNotOfferCancellationAfterHandoff() {
        var cancelCount = 0
        compose.setContent {
            SyntheticThreadScreen(
                composer = ComposerUiState(
                    body = "Synthetic dispatching draft",
                    saving = false,
                    failed = false,
                    sendState = ComposerSendState.UNAVAILABLE,
                    segmentCount = 1,
                    scheduleState = ComposerScheduleState.Dispatching(4_000_000_000_000L),
                ),
                onCancelSchedule = { cancelCount += 1 },
            )
        }

        compose.onNodeWithTag(COMPOSER_SCHEDULE_TEST_TAG).performClick()
        compose.onNodeWithText("Scheduled message").assertIsDisplayed()
        compose.onNodeWithText("Cancel schedule").assertDoesNotExist()
        compose.runOnIdle { check(cancelCount == 0) }
    }

    @Test
    fun knownUnsentOffersAnEnabledRetryThatUsesTheSendCallback() {
        var sendCount = 0
        compose.setContent {
            SyntheticThreadScreen(
                composer = ComposerUiState(
                    body = "Synthetic preserved draft",
                    saving = false,
                    failed = false,
                    sendState = ComposerSendState.KNOWN_UNSENT,
                    segmentCount = 1,
                ),
                onSend = { sendCount += 1 },
            )
        }

        compose.onNodeWithText("Not sent. Your draft was preserved.").assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG)
            .assertIsEnabled()
            .performClick()

        compose.runOnIdle { check(sendCount == 1) }
    }

    @Test
    fun submissionUnknownRequiresExplicitKeepAsDraftAcknowledgement() {
        var acknowledgementCount = 0
        compose.setContent {
            SyntheticThreadScreen(
                composer = ComposerUiState(
                    body = "Synthetic uncertain draft",
                    saving = false,
                    failed = false,
                    sendState = ComposerSendState.SUBMISSION_UNKNOWN,
                    segmentCount = 1,
                ),
                onAcknowledgeSubmissionUnknown = { acknowledgementCount += 1 },
            )
        }

        compose.onNodeWithTag(COMPOSER_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG)
            .assertIsEnabled()
            .performClick()
        compose.onNodeWithText("Send status unknown").assertIsDisplayed()
        compose.onNodeWithText("Keep as draft").assertIsDisplayed()
        compose.runOnIdle { check(acknowledgementCount == 0) }

        compose.onNodeWithText("Wait").performClick()
        compose.onNodeWithText("Send status unknown").assertDoesNotExist()
        compose.runOnIdle { check(acknowledgementCount == 0) }

        compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).performClick()
        compose.onNodeWithText("Keep as draft").performClick()
        compose.onNodeWithText("Send status unknown").assertDoesNotExist()
        compose.runOnIdle { check(acknowledgementCount == 1) }
    }

    @Test
    fun multipartUnavailableKeepsSendDisabledAndExplainsThePartCount() {
        compose.setContent {
            SyntheticThreadScreen(
                composer = ComposerUiState(
                    body = "Synthetic multipart draft",
                    saving = false,
                    failed = false,
                    sendState = ComposerSendState.UNAVAILABLE,
                    unavailableReason = ComposerUnavailableReason.MULTIPART_UNAVAILABLE,
                    segmentCount = 3,
                ),
            )
        }

        compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithText(
            "This text needs 3 SMS parts. Phase 5A sends one part at a time.",
        ).assertIsDisplayed()
    }

    @Test
    fun recoveryPendingLocksTheEditorUntilDurableRecoveryFinishes() {
        compose.setContent {
            SyntheticThreadScreen(
                composer = ComposerUiState(
                    body = "Synthetic recovering draft",
                    saving = false,
                    failed = false,
                    sendState = ComposerSendState.UNAVAILABLE,
                    unavailableReason = ComposerUnavailableReason.RECOVERY_PENDING,
                    segmentCount = 1,
                ),
            )
        }

        compose.onNodeWithTag(COMPOSER_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithText("Finishing safe send recovery…").assertIsDisplayed()
    }

    @Test
    fun rememberedUnavailableSimRequiresExplicitReplacementSelection() {
        val first = ActiveSubscription(
            id = AuroraSubscriptionId(4),
            slotIndex = 0,
            displayLabel = "Work",
            smsCapable = true,
        )
        val second = ActiveSubscription(
            id = AuroraSubscriptionId(7),
            slotIndex = 1,
            displayLabel = "Personal",
            smsCapable = true,
        )
        var selected: AuroraSubscriptionId? = null
        compose.setContent {
            MaterialTheme {
                ThreadScreen(
                    state = readyThreadState(),
                    composer = ComposerUiState(
                        body = "Synthetic preserved draft",
                        saving = false,
                        failed = false,
                        sendState = ComposerSendState.UNAVAILABLE,
                        unavailableReason = ComposerUnavailableReason.SUBSCRIPTION_UNAVAILABLE,
                        segmentCount = 1,
                    ),
                    subscriptionSelection = ConversationSubscriptionUiState(
                        options = listOf(first, second),
                        rememberedSelectionUnavailable = true,
                    ),
                    attachmentRepository = RejectingAttachmentRepository(),
                    previewLoader = RejectingPreviewLoader,
                    onBack = {},
                    onOpenSearch = {},
                    conversationAppearanceAvailable = false,
                    onOpenConversationAppearance = {},
                    isDialable = { false },
                    onDial = {},
                    onRetry = {},
                    onLoadOlder = {},
                    onLoadNewer = {},
                    onAtNewestChanged = {},
                    onAcceptPending = {},
                    onViewportChanged = {},
                    onAnchorRestored = {},
                    onToggleMessageExpansion = {},
                    onDraftChanged = {},
                    onSelectSubscription = { selected = it },
                )
            }
        }

        compose.onNodeWithText("Remembered SIM unavailable · choose another").assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(THREAD_SIM_SELECTOR_TEST_TAG).performClick()
        compose.onNodeWithText("SIM 2 · Personal").performClick()
        compose.runOnIdle { check(selected == second.id) }
    }

    @Test
    fun sentMessageWithFailedDeliveryReportIsNotLabeledAsSendFailure() {
        val ready = readyThreadState()
        val message = ready.window.items.single().copy(
            direction = MessageDirection.OUTGOING,
            box = MessageBox.SENT,
            status = MessageStatus.FAILED,
        )
        compose.setContent {
            MaterialTheme {
                ThreadScreen(
                    state = ready.copy(window = ready.window.copy(items = listOf(message))),
                    composer = ComposerUiState(body = "Synthetic draft", saving = false, failed = false),
                    attachmentRepository = RejectingAttachmentRepository(),
                    previewLoader = RejectingPreviewLoader,
                    onBack = {},
                    onOpenSearch = {},
                    conversationAppearanceAvailable = false,
                    onOpenConversationAppearance = {},
                    isDialable = { false },
                    onDial = {},
                    onRetry = {},
                    onLoadOlder = {},
                    onLoadNewer = {},
                    onAtNewestChanged = {},
                    onAcceptPending = {},
                    onViewportChanged = {},
                    onAnchorRestored = {},
                    onToggleMessageExpansion = {},
                    onDraftChanged = {},
                )
            }
        }

        compose.onNodeWithText("Sent; delivery failed").assertIsDisplayed()
        compose.onNodeWithText("Failed to send").assertDoesNotExist()
    }

    @Test
    fun trustedConversationAppearanceActionUsesOnlyTheRouteCallback() {
        var openCount = 0
        compose.setContent {
            MaterialTheme {
                ThreadScreen(
                    state = readyThreadState(),
                    composer = ComposerUiState(body = "Synthetic draft", saving = false, failed = false),
                    attachmentRepository = RejectingAttachmentRepository(),
                    previewLoader = RejectingPreviewLoader,
                    onBack = {},
                    onOpenSearch = {},
                    conversationAppearanceAvailable = true,
                    onOpenConversationAppearance = { openCount += 1 },
                    isDialable = { false },
                    onDial = {},
                    onRetry = {},
                    onLoadOlder = {},
                    onLoadNewer = {},
                    onAtNewestChanged = {},
                    onAcceptPending = {},
                    onViewportChanged = {},
                    onAnchorRestored = {},
                    onToggleMessageExpansion = {},
                    onDraftChanged = {},
                )
            }
        }

        compose.onNodeWithTag(THREAD_MORE_ACTION_TEST_TAG).performClick()
        compose.onNodeWithTag(THREAD_APPEARANCE_ACTION_TEST_TAG).performClick()
        compose.runOnIdle { check(openCount == 1) }
    }

    @Test
    fun selectedTextActionCopiesOnlyTheExplicitRange() {
        var clipboard: ClipboardManager? = null
        try {
            compose.setContent {
                SyntheticThreadScreen(
                    composer = ComposerUiState(body = "", saving = false, failed = false),
                )
            }
            compose.runOnIdle {
                clipboard = InstrumentationRegistry.getInstrumentation()
                    .targetContext
                    .getSystemService(ClipboardManager::class.java)
                checkNotNull(clipboard).setPrimaryClip(ClipData.newPlainText("AuroraSMS test reset", ""))
            }

            compose.onNodeWithTag(MESSAGE_BUBBLE_TEST_TAG).performTouchInput { longClick() }
            compose.onNodeWithTag(MESSAGE_ACTIONS_DIALOG_TEST_TAG).assertIsDisplayed()
            compose.onNodeWithTag(SELECT_MESSAGE_TEXT_ACTION_TEST_TAG).performClick()
            compose.onNodeWithTag(COPY_SELECTED_TEXT_TEST_TAG).assertIsNotEnabled()
            compose.onNodeWithTag(MESSAGE_TEXT_SELECTION_FIELD_TEST_TAG)
                .performTextInputSelection(TextRange(0, 9))
            compose.onNodeWithTag(COPY_SELECTED_TEXT_TEST_TAG).assertIsEnabled().performClick()
            compose.waitForIdle()

            var copiedText: String? = null
            compose.runOnIdle {
                copiedText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
            }
            assertEquals("Synthetic", copiedText)
        } finally {
            clipboard?.let { initializedClipboard ->
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    initializedClipboard.setPrimaryClip(
                        ClipData.newPlainText("AuroraSMS test reset", ""),
                    )
                }
            }
        }
    }

    @Test
    fun messageDetailsShowBoundedMetadataWithoutProviderIdentifiers() {
        compose.setContent {
            SyntheticThreadScreen(
                composer = ComposerUiState(body = "", saving = false, failed = false),
            )
        }

        compose.onNodeWithTag(MESSAGE_BUBBLE_TEST_TAG).performTouchInput { longClick() }
        compose.onNodeWithTag(MESSAGE_DETAILS_ACTION_TEST_TAG).performClick()
        compose.onNodeWithTag(MESSAGE_DETAILS_DIALOG_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithText("SMS").assertIsDisplayed()
        compose.onNodeWithText("Incoming message").assertIsDisplayed()
        compose.onNodeWithText("Received · read").assertIsDisplayed()
        compose.onNodeWithText("Not available").assertIsDisplayed()
        compose.onNodeWithText("No attachments").assertIsDisplayed()
        compose.onNodeWithText("ProviderMessageId", substring = true).assertDoesNotExist()
    }

    @Test
    fun exactMessageDeletionRequiresActionChoiceAndConfirmationAfterLongPress() {
        var requested: TimelineMessage? = null
        compose.setContent {
            SyntheticThreadScreen(
                composer = ComposerUiState(body = "", saving = false, failed = false),
                onRequestDeleteMessage = { requested = it },
            )
        }

        compose.onNodeWithTag(MESSAGE_BUBBLE_TEST_TAG).performTouchInput { longClick() }
        compose.onNodeWithTag(MESSAGE_ACTIONS_DIALOG_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithTag(DELETE_MESSAGE_ACTION_TEST_TAG).performClick()
        compose.onNodeWithText("Permanently delete this message?").assertIsDisplayed()
        compose.onNodeWithTag(CONFIRM_DELETE_MESSAGE_TEST_TAG).performClick()
        compose.runOnIdle { check(requested?.providerMessageId == ProviderMessageId(ProviderKind.SMS, 1L)) }
    }

    @Test
    fun wholeConversationDeletionUsesTwoDistinctConfirmationSteps() {
        var requests = 0
        compose.setContent {
            SyntheticThreadScreen(
                composer = ComposerUiState(body = "", saving = false, failed = false),
                onRequestDeleteThread = { requests += 1 },
            )
        }

        compose.onNodeWithTag(THREAD_MORE_ACTION_TEST_TAG).performClick()
        compose.onNodeWithTag(THREAD_DELETE_ACTION_TEST_TAG).assertIsEnabled().performClick()
        compose.onNodeWithText("Delete this conversation?").assertIsDisplayed()
        compose.onNodeWithTag(CONTINUE_DELETE_THREAD_TEST_TAG).performClick()
        compose.onNodeWithText("Last chance").assertIsDisplayed()
        compose.onNodeWithTag(CONFIRM_DELETE_THREAD_TEST_TAG).performClick()
        compose.runOnIdle { check(requests == 1) }
    }

    @Test
    fun pendingDeletionShowsUndoWhileCommittingDoesNot() {
        val deletion = mutableStateOf<PermanentDeletionUiState>(
            PermanentDeletionUiState.Pending(
                targetKind = PermanentDeletionTargetUiKind.MESSAGE,
                providerMessageId = ProviderMessageId(ProviderKind.SMS, 1L),
                dueTimestampMillis = 5_000L,
            ),
        )
        var undoCount = 0
        compose.setContent {
            SyntheticThreadScreen(
                composer = ComposerUiState(body = "", saving = false, failed = false),
                deletion = deletion.value,
                onUndoDeletion = { undoCount += 1 },
            )
        }

        compose.onNodeWithTag(PERMANENT_DELETION_BANNER_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithTag(UNDO_DELETION_TEST_TAG).performClick()
        compose.runOnIdle {
            check(undoCount == 1)
            deletion.value = PermanentDeletionUiState.Committing(PermanentDeletionTargetUiKind.MESSAGE)
        }
        compose.onNodeWithText("Deleting permanently…").assertIsDisplayed()
        compose.onNodeWithTag(UNDO_DELETION_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun timelineBackgroundIsReadyOnlyAndDrawnBehindTimelineOutsideHeaderAndComposer() {
        val threadState = mutableStateOf<ThreadUiState>(ThreadUiState.Loading)
        compose.setContent {
            MaterialTheme {
                ThreadScreen(
                    state = threadState.value,
                    composer = ComposerUiState(body = "Synthetic draft", saving = false, failed = false),
                    attachmentRepository = RejectingAttachmentRepository(),
                    previewLoader = RejectingPreviewLoader,
                    onBack = {},
                    onOpenSearch = {},
                    conversationAppearanceAvailable = false,
                    onOpenConversationAppearance = {},
                    isDialable = { false },
                    onDial = {},
                    onRetry = {},
                    onLoadOlder = {},
                    onLoadNewer = {},
                    onAtNewestChanged = {},
                    onAcceptPending = {},
                    onViewportChanged = {},
                    onAnchorRestored = {},
                    onToggleMessageExpansion = {},
                    onDraftChanged = {},
                    timelineBackground = {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(SYNTHETIC_TIMELINE_BACKGROUND_COLOR)
                                .testTag(SYNTHETIC_TIMELINE_BACKGROUND_TAG),
                        )
                    },
                )
            }
        }

        compose.onNodeWithTag(SYNTHETIC_TIMELINE_BACKGROUND_TAG).assertDoesNotExist()
        compose.runOnIdle {
            threadState.value = ThreadUiState.Failed(
                failure = ConversationLoadFailure.STORAGE,
                coverage = readyThreadState().coverage,
            )
        }
        compose.onNodeWithTag(SYNTHETIC_TIMELINE_BACKGROUND_TAG).assertDoesNotExist()

        compose.runOnIdle { threadState.value = readyThreadState() }
        compose.onNodeWithTag(SYNTHETIC_TIMELINE_BACKGROUND_TAG).assertIsDisplayed()
        compose.onNodeWithTag(THREAD_LIST_TEST_TAG).assertIsDisplayed()

        val screenNode = compose.onNodeWithTag(THREAD_SCREEN_TEST_TAG).fetchSemanticsNode()
        val backgroundNode = compose.onNodeWithTag(SYNTHETIC_TIMELINE_BACKGROUND_TAG).fetchSemanticsNode()
        val listNode = compose.onNodeWithTag(THREAD_LIST_TEST_TAG).fetchSemanticsNode()
        val bubbleNode = compose.onNodeWithTag(MESSAGE_BUBBLE_TEST_TAG).fetchSemanticsNode()
        val composerNode = compose.onNodeWithTag(COMPOSER_TEST_TAG).fetchSemanticsNode()
        val headerTitleNode = compose.onNodeWithText("Conversation").fetchSemanticsNode()
        val backgroundBounds = backgroundNode.boundsInRoot
        val listBounds = listNode.boundsInRoot

        assertTrue(backgroundBounds.top >= headerTitleNode.boundsInRoot.bottom)
        assertTrue(backgroundBounds.bottom <= composerNode.boundsInRoot.top)
        assertTrue((backgroundBounds.left - listBounds.left).absoluteValue < 1f)
        assertTrue((backgroundBounds.top - listBounds.top).absoluteValue < 1f)
        assertTrue((backgroundBounds.right - listBounds.right).absoluteValue < 1f)
        assertTrue((backgroundBounds.bottom - listBounds.bottom).absoluteValue < 1f)

        val capture = compose.onNodeWithTag(THREAD_SCREEN_TEST_TAG).captureToImage().toPixelMap()
        val rootBounds = screenNode.boundsInRoot
        fun capturedColorAt(rootX: Float, rootY: Float): Color = capture[
            (rootX - rootBounds.left).toInt().coerceIn(0, capture.width - 1),
            (rootY - rootBounds.top).toInt().coerceIn(0, capture.height - 1),
        ]

        val exposedBackground = capturedColorAt(
            rootX = backgroundBounds.right - 2f,
            rootY = backgroundBounds.center.y,
        )
        assertTrue(exposedBackground.isSyntheticTimelineBackground())

        val bubbleCenter = bubbleNode.boundsInRoot.center
        val bubbleColor = capturedColorAt(bubbleCenter.x, bubbleCenter.y)
        assertTrue(!bubbleColor.isSyntheticTimelineBackground())
    }

    @Test
    fun inboxAppearanceEntriesUseSeparateRouteCallbacks() {
        var inboxCount = 0
        var defaultsCount = 0
        compose.setContent {
            MaterialTheme {
                InboxScreen(
                    state = InboxUiState.Loading,
                    diagnosticsAvailable = false,
                    contactsPermissionGranted = true,
                    onOpenConversation = {},
                    onOpenSearch = {},
                    onOpenAppearance = {},
                    onOpenInboxAppearance = { inboxCount += 1 },
                    onOpenConversationDefaults = { defaultsCount += 1 },
                    onOpenDiagnostics = {},
                    onRequestContactsPermission = {},
                    onRetry = {},
                    onLoadOlder = {},
                    onAtNewestChanged = {},
                    onAcceptPending = {},
                    onViewportChanged = {},
                    onAnchorRestored = {},
                )
            }
        }

        compose.onNodeWithTag(INBOX_MORE_ACTION_TEST_TAG).performClick()
        compose.onNodeWithTag(INBOX_SCOPE_APPEARANCE_ACTION_TEST_TAG).performClick()
        compose.onNodeWithTag(INBOX_MORE_ACTION_TEST_TAG).performClick()
        compose.onNodeWithTag(CONVERSATION_DEFAULTS_APPEARANCE_ACTION_TEST_TAG).performClick()
        compose.runOnIdle {
            check(inboxCount == 1)
            check(defaultsCount == 1)
        }
    }

    @Test
    fun inboxReminderDialogShowsCurrentChoiceAndRequiresExplicitSelection() {
        var selectedDelay: Int? = null
        compose.setContent {
            MaterialTheme {
                InboxScreen(
                    state = InboxUiState.Loading,
                    diagnosticsAvailable = false,
                    contactsPermissionGranted = true,
                    onOpenConversation = {},
                    onOpenSearch = {},
                    onOpenAppearance = {},
                    onOpenInboxAppearance = {},
                    onOpenConversationDefaults = {},
                    onOpenDiagnostics = {},
                    onRequestContactsPermission = {},
                    onRetry = {},
                    onLoadOlder = {},
                    onAtNewestChanged = {},
                    onAcceptPending = {},
                    onViewportChanged = {},
                    onAnchorRestored = {},
                    notificationReminderDelayMinutes = 15,
                    onSetNotificationReminderDelayMinutes = { selectedDelay = it },
                )
            }
        }

        compose.onNodeWithTag(INBOX_MORE_ACTION_TEST_TAG).performClick()
        compose.onNodeWithText("Message reminders · 15 minutes").assertIsDisplayed()
        compose.onNodeWithTag(INBOX_NOTIFICATION_REMINDER_ACTION_TEST_TAG).performClick()
        compose.onNodeWithText("Remind after 1 hour").assertIsDisplayed().performClick()

        compose.runOnIdle { assertEquals(60, selectedDelay) }
    }
}

@Composable
private fun SyntheticThreadScreen(
    composer: ComposerUiState,
    onSend: () -> Unit = {},
    onUndoSend: () -> Unit = {},
    sendDelaySeconds: Int = 0,
    onSetSendDelaySeconds: (Int) -> Unit = {},
    onAcknowledgeSubmissionUnknown: () -> Unit = {},
    onSchedule: () -> Unit = {},
    onCancelSchedule: () -> Unit = {},
    deletion: PermanentDeletionUiState = PermanentDeletionUiState.None,
    onRequestDeleteMessage: (TimelineMessage) -> Unit = {},
    onRequestDeleteThread: () -> Unit = {},
    onUndoDeletion: () -> Unit = {},
) {
    MaterialTheme {
        ThreadScreen(
            state = readyThreadState(),
            composer = composer,
            attachmentRepository = RejectingAttachmentRepository(),
            previewLoader = RejectingPreviewLoader,
            onBack = {},
            onOpenSearch = {},
            conversationAppearanceAvailable = false,
            onOpenConversationAppearance = {},
            isDialable = { false },
            onDial = {},
            onRetry = {},
            onLoadOlder = {},
            onLoadNewer = {},
            onAtNewestChanged = {},
            onAcceptPending = {},
            onViewportChanged = {},
            onAnchorRestored = {},
            onToggleMessageExpansion = {},
            onDraftChanged = {},
            onSend = onSend,
            onUndoSend = onUndoSend,
            sendDelaySeconds = sendDelaySeconds,
            onSetSendDelaySeconds = onSetSendDelaySeconds,
            onSchedule = onSchedule,
            onCancelSchedule = onCancelSchedule,
            onAcknowledgeSubmissionUnknown = onAcknowledgeSubmissionUnknown,
            deletion = deletion,
            onRequestDeleteMessage = onRequestDeleteMessage,
            onRequestDeleteThread = onRequestDeleteThread,
            onUndoDeletion = onUndoDeletion,
        )
    }
}

private fun readyThreadState(
    body: String = "Synthetic message",
    bodyTruncated: Boolean = false,
): ThreadUiState.Ready = ThreadUiState.Ready(
    window = BoundedThreadWindow(
        items = listOf(
            TimelineMessage(
                localRowId = 1L,
                providerMessageId = ProviderMessageId(ProviderKind.SMS, 1L),
                providerThreadId = ProviderThreadId(1L),
                timestampMillis = 1L,
                sentTimestampMillis = null,
                direction = MessageDirection.INCOMING,
                box = MessageBox.INBOX,
                status = MessageStatus.NONE,
                subscriptionId = null,
                senderAddress = null,
                bodyPreview = body,
                bodyTruncated = bodyTruncated,
                subject = null,
                attachmentCount = 0,
                attachmentTypeSummary = "",
                read = true,
                seen = true,
                locked = false,
                syncFingerprint = MessageSyncFingerprint.fromSha256(ByteArray(32) { 1 }),
            ),
        ),
        olderCursor = null,
        newerCursor = null,
    ),
    coverage = IndexCoverage(
        generationId = 1L,
        state = IndexRunState.COMPLETE,
        indexedMessageCount = 1L,
        smsExhausted = true,
        mmsExhausted = true,
        pendingChanges = false,
        generationCommittedCount = 1L,
        smsCheckpointCommittedCount = 1L,
    ),
    conversation = null,
    activeSubscription = null,
    contacts = emptyMap(),
    loadingOlder = false,
    loadingNewer = false,
)

private class RejectingAttachmentRepository : MmsAttachmentRepository {
    var readAttempts: Int = 0

    override suspend fun listStaticImages(providerMessageId: ProviderMessageId): MmsAttachmentListResult {
        readAttempts += 1
        return MmsAttachmentListResult.Unavailable
    }

    override suspend fun <T> read(
        id: MmsAttachmentId,
        reader: MmsAttachmentContentReader<T>,
    ): MmsAttachmentReadResult<T> {
        readAttempts += 1
        return MmsAttachmentReadResult.Unavailable
    }
}

private object RejectingPreviewLoader : BoundedPreviewLoader {
    override suspend fun load(
        descriptor: org.aurorasms.core.telephony.MmsAttachmentDescriptor,
    ): AttachmentPreviewResult {
        delay(1L)
        return AttachmentPreviewResult.Unavailable
    }

    override suspend fun clear() = Unit
}

private fun Color.isSyntheticTimelineBackground(): Boolean =
    red > 0.95f && green < 0.05f && blue > 0.95f && alpha > 0.95f

private const val SYNTHETIC_TIMELINE_BACKGROUND_TAG = "synthetic-timeline-background"
private val SYNTHETIC_TIMELINE_BACKGROUND_COLOR = Color.Magenta
