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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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

    @Test
    fun closedContactDiscoveryKeepsManualRecipientEntryAsTheFallback() {
        var opened = 0
        var committedRaw: String? = null
        compose.setContent {
            MaterialTheme {
                NewMessageScreen(
                    recipientInput = "+12025550108",
                    committedRecipients = emptyList(),
                    body = "",
                    onBack = {},
                    onRecipientInputChanged = {},
                    onCommitRecipients = { committedRaw = it },
                    onRemoveRecipient = {},
                    onBodyChanged = {},
                    contactDiscoveryState = NewMessageContactDiscoveryUiState.Closed,
                    onOpenContactDiscovery = { opened += 1 },
                )
            }
        }

        compose.onNodeWithTag(NEW_MESSAGE_CONTACT_DISCOVERY_PANEL_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG).assertIsEnabled()
        compose.onNodeWithTag(NEW_MESSAGE_COMMIT_RECIPIENT_TEST_TAG).performClick()
        compose.onNodeWithTag(NEW_MESSAGE_CONTACT_DISCOVERY_ACTION_TEST_TAG).performClick()
        compose.runOnIdle {
            assertEquals("+12025550108", committedRaw)
            assertEquals(1, opened)
        }
    }

    @Test
    fun openContactDiscoveryPresentsEveryOutcomeAndReturnsOnlyTheSelectedAddress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = mutableStateOf<NewMessageContactDiscoveryUiState>(
            NewMessageContactDiscoveryUiState.Empty(),
        )
        var changedQuery: String? = null
        var retries = 0
        var closedByHeader = 0
        var selected: ParticipantAddress? = null
        val resultAddress = ParticipantAddress("+12025550109")
        compose.setContent {
            MaterialTheme {
                NewMessageScreen(
                    recipientInput = "manual-entry-stays-visible",
                    committedRecipients = emptyList(),
                    body = "",
                    onBack = {},
                    onRecipientInputChanged = {},
                    onCommitRecipients = {},
                    onRemoveRecipient = {},
                    onBodyChanged = {},
                    contactDiscoveryState = state.value,
                    onCloseContactDiscovery = { closedByHeader += 1 },
                    onContactDiscoveryQueryChanged = { changedQuery = it },
                    onRetryContactDiscovery = { retries += 1 },
                    onSelectDiscoveredContact = { selected = it },
                )
            }
        }

        compose.onNodeWithTag(NEW_MESSAGE_CONTACT_DISCOVERY_PANEL_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.contact_discovery_empty_prompt))
            .assertIsDisplayed()
        compose.onNodeWithTag(NEW_MESSAGE_CONTACT_DISCOVERY_QUERY_TEST_TAG)
            .performTextInput("Synthetic")
        compose.runOnIdle { assertEquals("Synthetic", changedQuery) }
        compose.onNodeWithTag(NEW_MESSAGE_BACK_ACTION_TEST_TAG).performClick()
        compose.runOnIdle { assertEquals(1, closedByHeader) }

        compose.runOnIdle {
            state.value = NewMessageContactDiscoveryUiState.Empty("Nobody")
        }
        compose.onNodeWithText(context.getString(R.string.contact_discovery_no_results))
            .assertIsDisplayed()

        compose.runOnIdle {
            state.value = NewMessageContactDiscoveryUiState.Empty("Selected", truncated = true)
        }
        compose.onNodeWithText(context.getString(R.string.contact_discovery_truncated_empty))
            .assertIsDisplayed()

        compose.runOnIdle {
            state.value = NewMessageContactDiscoveryUiState.Loading("Synthetic")
        }
        compose.onNodeWithText(context.getString(R.string.contact_discovery_loading))
            .assertIsDisplayed()

        compose.runOnIdle {
            state.value = NewMessageContactDiscoveryUiState.Unavailable("Synthetic")
        }
        compose.onNodeWithText(context.getString(R.string.contact_discovery_unavailable))
            .assertIsDisplayed()
        compose.onNodeWithTag(NEW_MESSAGE_CONTACT_DISCOVERY_QUERY_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG).assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.manage_contact_access)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }

        compose.runOnIdle {
            state.value = NewMessageContactDiscoveryUiState.Error("Synthetic")
        }
        compose.onNodeWithText(context.getString(R.string.contact_discovery_error))
            .assertIsDisplayed()
        compose.onNodeWithTag(NEW_MESSAGE_CONTACT_DISCOVERY_RETRY_TEST_TAG).performClick()
        compose.runOnIdle { assertEquals(2, retries) }

        compose.runOnIdle {
            state.value = NewMessageContactDiscoveryUiState.Results(
                query = "Synthetic",
                items = listOf(
                    NewMessageContactResultUiItem(resultAddress, "Synthetic Contact"),
                ),
                truncated = true,
            )
        }
        compose.onNodeWithTag(NEW_MESSAGE_CONTACT_DISCOVERY_RESULTS_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithTag(NEW_MESSAGE_CONTACT_DISCOVERY_TRUNCATED_TEST_TAG)
            .assertIsDisplayed()
        compose.onNodeWithTag("$NEW_MESSAGE_CONTACT_DISCOVERY_RESULT_TEST_TAG_PREFIX-0")
            .performClick()
        compose.runOnIdle { assertEquals(resultAddress, selected) }
        compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
    }

    @Test
    fun compactDeviceCanReachAndSelectLastOfMaximumBoundedContactResults() {
        val results = List(MAXIMUM_NEW_MESSAGE_CONTACT_RESULTS) { index ->
            NewMessageContactResultUiItem(
                address = ParticipantAddress("+1202555${1000 + index}"),
                displayLabel = "Synthetic Contact ${index + 1}",
            )
        }
        var selected: ParticipantAddress? = null
        compose.setContent {
            MaterialTheme {
                NewMessageScreen(
                    recipientInput = "",
                    committedRecipients = emptyList(),
                    body = "",
                    onBack = {},
                    onRecipientInputChanged = {},
                    onCommitRecipients = {},
                    onRemoveRecipient = {},
                    onBodyChanged = {},
                    contactDiscoveryState = NewMessageContactDiscoveryUiState.Results(
                        query = "Synthetic",
                        items = results,
                    ),
                    onSelectDiscoveredContact = { selected = it },
                )
            }
        }

        results.indices.forEach { index ->
            compose.onNodeWithTag(
                "$NEW_MESSAGE_CONTACT_DISCOVERY_RESULT_TEST_TAG_PREFIX-$index",
            ).assertExists()
        }
        compose.onNodeWithTag(
            "$NEW_MESSAGE_CONTACT_DISCOVERY_RESULT_TEST_TAG_PREFIX-" +
                (MAXIMUM_NEW_MESSAGE_CONTACT_RESULTS - 1),
        )
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle {
            assertEquals(results.last().address, selected)
        }
        compose.onNodeWithTag(COMPOSER_SEND_TEST_TAG).assertIsNotEnabled()
    }

    @Test
    fun lockedRecipientIdentityDisablesContactQueriesAndSelectionButStillAllowsClosing() {
        val resultAddress = ParticipantAddress("+12025550110")
        var closed = 0
        var selected: ParticipantAddress? = null
        compose.setContent {
            MaterialTheme {
                NewMessageScreen(
                    recipientInput = "",
                    committedRecipients = listOf(
                        NewMessageRecipientUiItem(resultAddress, "Locked Recipient"),
                    ),
                    body = "Identity-locked draft",
                    onBack = {},
                    onRecipientInputChanged = {},
                    onCommitRecipients = {},
                    onRemoveRecipient = {},
                    onBodyChanged = {},
                    contactDiscoveryState = NewMessageContactDiscoveryUiState.Results(
                        query = "Locked",
                        items = listOf(
                            NewMessageContactResultUiItem(resultAddress, "Locked Recipient"),
                        ),
                    ),
                    onCloseContactDiscovery = { closed += 1 },
                    onSelectDiscoveredContact = { selected = it },
                    recipientEditingEnabled = false,
                )
            }
        }

        compose.onNodeWithTag(NEW_MESSAGE_RECIPIENT_INPUT_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(NEW_MESSAGE_CONTACT_DISCOVERY_QUERY_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithTag("$NEW_MESSAGE_CONTACT_DISCOVERY_RESULT_TEST_TAG_PREFIX-0")
            .assertIsNotEnabled()
        compose.onNodeWithTag(NEW_MESSAGE_CONTACT_DISCOVERY_ACTION_TEST_TAG)
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle {
            assertEquals(1, closed)
            assertEquals(null, selected)
        }
    }
}
