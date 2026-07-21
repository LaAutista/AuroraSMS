// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aurorasms.core.model.ParticipantAddress
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NewMessageScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun inboxNewChatFabUsesOnlyTheExplicitRouteCallback() {
        var openCount = 0
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
                    onOpenNewChat = { openCount += 1 },
                )
            }
        }

        compose.onNodeWithTag(INBOX_NEW_CHAT_ACTION_TEST_TAG).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, openCount) }
    }

    @Test
    fun newMessageIsControlledAndKeepsEverySendPathDisabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recipientInput = mutableStateOf("+12025550101")
        val body = mutableStateOf("Synthetic first-contact draft")
        val recipient = ParticipantAddress("+12025550102")
        var committedRaw: String? = null
        var removed: ParticipantAddress? = null
        compose.setContent {
            MaterialTheme {
                NewMessageScreen(
                    recipientInput = recipientInput.value,
                    committedRecipients = listOf(
                        NewMessageRecipientUiItem(recipient, "Synthetic Recipient"),
                    ),
                    body = body.value,
                    onBack = {},
                    onRecipientInputChanged = { recipientInput.value = it },
                    onCommitRecipients = { committedRaw = it },
                    onRemoveRecipient = { removed = it },
                    onBodyChanged = { body.value = it },
                    recipientError = NewMessageRecipientError.INVALID,
                    draftStatus = NewMessageDraftStatus.REVIEW_ONLY,
                    externalPrefillConflict = true,
                    explicitMmsRequested = true,
                )
            }
        }

        compose.onNodeWithTag(NEW_MESSAGE_SCREEN_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithText(
            context.getString(R.string.new_message_recipient_invalid),
        ).assertIsDisplayed()
        compose.onNodeWithTag(NEW_MESSAGE_EXTERNAL_CONFLICT_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithTag(NEW_MESSAGE_EXPLICIT_MMS_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithText(
            context.getString(R.string.new_message_external_review_only),
        ).assertIsDisplayed()
        compose.onNodeWithTag(NEW_MESSAGE_COMMIT_RECIPIENT_TEST_TAG).performClick()
        compose.onNodeWithTag("$NEW_MESSAGE_RECIPIENT_CHIP_TEST_TAG_PREFIX-0").performClick()
        compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(COMPOSER_EXTRAS_ACTION_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(COMPOSER_VOICE_MEMO_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(COMPOSER_SCHEDULE_TEST_TAG).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("+12025550101", committedRaw)
            assertEquals(recipient, removed)
            assertEquals("Synthetic first-contact draft", body.value)
        }
    }

    @Test
    fun loadingExactDraftBaseLocksRecipientsAndBodyUntilReadCompletes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MaterialTheme {
                NewMessageScreen(
                    recipientInput = "+12025550103",
                    committedRecipients = emptyList(),
                    body = "External prefill awaiting exact-base read",
                    onBack = {},
                    onRecipientInputChanged = {},
                    onCommitRecipients = {},
                    onRemoveRecipient = {},
                    onBodyChanged = {},
                    draftStatus = NewMessageDraftStatus.LOADING,
                )
            }
        }

        compose.onNodeWithTag(NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(NEW_MESSAGE_COMMIT_RECIPIENT_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(COMPOSER_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.new_message_draft_loading))
            .assertIsDisplayed()
    }

    @Test
    fun readyRecipientRouteDoesNotClaimAnUnsavedDraftIsSaved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MaterialTheme {
                NewMessageScreen(
                    recipientInput = "",
                    committedRecipients = listOf(
                        NewMessageRecipientUiItem(ParticipantAddress("+12025550107")),
                    ),
                    body = "",
                    onBack = {},
                    onRecipientInputChanged = {},
                    onCommitRecipients = {},
                    onRemoveRecipient = {},
                    onBodyChanged = {},
                    draftStatus = NewMessageDraftStatus.READY,
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.new_message_draft_ready))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.draft_saved)).assertDoesNotExist()
    }

    @Test
    fun bodyRequiresARecipientAndNonemptyDraftLocksRecipientIdentity() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val recipient = ParticipantAddress("+12025550104")
        val hasRecipient = mutableStateOf(false)
        val body = mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                NewMessageScreen(
                    recipientInput = "+12025550105",
                    committedRecipients = if (hasRecipient.value) {
                        listOf(NewMessageRecipientUiItem(recipient))
                    } else {
                        emptyList()
                    },
                    body = body.value,
                    onBack = {},
                    onRecipientInputChanged = {},
                    onCommitRecipients = { hasRecipient.value = true },
                    onRemoveRecipient = { hasRecipient.value = false },
                    onBodyChanged = { body.value = it },
                    recipientEditingEnabled = body.value.isEmpty(),
                )
            }
        }

        compose.onNodeWithTag(COMPOSER_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(NEW_MESSAGE_COMMIT_RECIPIENT_TEST_TAG).performClick()
        compose.onNodeWithTag(COMPOSER_TEST_TAG).assertIsEnabled()

        compose.runOnIdle { body.value = "Identity-locked draft" }
        compose.onNodeWithTag(NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(NEW_MESSAGE_COMMIT_RECIPIENT_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithTag("$NEW_MESSAGE_RECIPIENT_CHIP_TEST_TAG_PREFIX-0")
            .assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.new_message_recipient_locked))
            .assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_TEST_TAG).assertIsEnabled()
    }

    @Test
    fun unresolvedExternalPrefillCannotBeEditedWhileItsDurableBaseIsDecided() {
        compose.setContent {
            MaterialTheme {
                NewMessageScreen(
                    recipientInput = "",
                    committedRecipients = listOf(
                        NewMessageRecipientUiItem(ParticipantAddress("+12025550106")),
                    ),
                    body = "Unresolved external prefill",
                    onBack = {},
                    onRecipientInputChanged = {},
                    onCommitRecipients = {},
                    onRemoveRecipient = {},
                    onBodyChanged = {},
                    draftStatus = NewMessageDraftStatus.SAVING,
                    recipientEditingEnabled = false,
                    bodyEditingEnabled = false,
                )
            }
        }

        compose.onNodeWithTag(COMPOSER_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG).assertIsNotEnabled()
    }
}
