// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import org.aurorasms.core.index.IndexCoverage
import org.aurorasms.core.index.IndexRunState
import org.aurorasms.core.index.timeline.TimelineMessage
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.telephony.MmsAttachmentContentReader
import org.aurorasms.core.telephony.MmsAttachmentId
import org.aurorasms.core.telephony.MmsAttachmentListResult
import org.aurorasms.core.telephony.MmsAttachmentReadResult
import org.aurorasms.core.telephony.MmsAttachmentRepository
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
        compose.onNodeWithTag(THREAD_MORE_ACTION_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(THREAD_APPEARANCE_ACTION_TEST_TAG).assertDoesNotExist()
        compose.runOnIdle { check(repository.readAttempts == 0) }
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
}

private fun readyThreadState(): ThreadUiState.Ready = ThreadUiState.Ready(
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
                bodyPreview = "Synthetic message",
                bodyTruncated = false,
                subject = null,
                attachmentCount = 0,
                attachmentTypeSummary = "",
                read = true,
                seen = true,
                locked = false,
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
