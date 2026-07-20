// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aurorasms.app.AuroraComposeTestActivity
import org.aurorasms.core.state.MessageSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageSignatureDialogTest {
    @get:Rule val compose = createAndroidComposeRule<AuroraComposeTestActivity>()

    @Test
    fun globalEditorNormalizesOnlyAfterExplicitSave() {
        var saved: MessageSignature? = null
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                GlobalMessageSignatureDialog(
                    current = null,
                    onSave = { signature ->
                        saved = signature
                        true
                    },
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithTag(SIGNATURE_TEXT_FIELD_TEST_TAG).performTextInput("  Aurora  ")
        compose.runOnIdle {
            assertEquals(null, saved)
            assertTrue(!dismissed)
        }
        compose.onNodeWithTag(SAVE_GLOBAL_SIGNATURE_TEST_TAG).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("Aurora", saved?.value)
            assertTrue(dismissed)
        }
    }

    @Test
    fun globalEditorRejectsMoreThanFourLinesWithoutTruncation() {
        var saveCalls = 0
        compose.setContent {
            MaterialTheme {
                GlobalMessageSignatureDialog(
                    current = null,
                    onSave = {
                        saveCalls += 1
                        true
                    },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithTag(SIGNATURE_TEXT_FIELD_TEST_TAG).performTextInput("a\nb\nc\nd\ne")
        compose.onNodeWithTag(SAVE_GLOBAL_SIGNATURE_TEST_TAG).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, saveCalls) }
    }

    @Test
    fun conversationEditorRequiresAnExplicitCustomMode() {
        var saved: ConversationSignatureOverride? = null
        compose.setContent {
            MaterialTheme {
                ConversationMessageSignatureDialog(
                    inherited = checkNotNull(MessageSignature.fromUserInput("Global")),
                    current = ConversationSignatureOverride.Disabled,
                    onSave = {
                        saved = it
                        true
                    },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithTag(SIGNATURE_TEXT_FIELD_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithText("Use a custom signature").performClick()
        compose.onNodeWithTag(SIGNATURE_TEXT_FIELD_TEST_TAG)
            .assertIsEnabled()
            .performTextInput("Personal")
        compose.onNodeWithTag(SAVE_CONVERSATION_SIGNATURE_TEST_TAG).performClick()
        compose.runOnIdle {
            assertEquals(
                "Personal",
                (saved as? ConversationSignatureOverride.Custom)?.signature?.value,
            )
        }
    }
}
