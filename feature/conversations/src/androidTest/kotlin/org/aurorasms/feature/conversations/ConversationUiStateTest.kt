// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.absoluteValue
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
import org.junit.Assert.assertTrue
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

private fun Color.isSyntheticTimelineBackground(): Boolean =
    red > 0.95f && green < 0.05f && blue > 0.95f && alpha > 0.95f

private const val SYNTHETIC_TIMELINE_BACKGROUND_TAG = "synthetic-timeline-background"
private val SYNTHETIC_TIMELINE_BACKGROUND_COLOR = Color.Magenta
