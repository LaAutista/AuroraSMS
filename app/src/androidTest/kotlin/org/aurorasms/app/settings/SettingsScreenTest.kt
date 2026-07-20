// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.aurorasms.app.AuroraComposeTestActivity
import org.aurorasms.app.AuroraSmsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<AuroraComposeTestActivity>()

    @Test
    fun searchableSettingsExposeBackupRestoreDestination() {
        var backupOpens = 0
        compose.setContent {
            AuroraSmsTheme {
                SettingsScreen(
                    onOpenAppearance = {},
                    onOpenSpamBlocked = {},
                    onOpenBackupRestore = { backupOpens += 1 },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag(SETTINGS_SCREEN_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SETTINGS_SEARCH_TEST_TAG).performTextInput("backup")
        compose.onNodeWithTag(SETTINGS_APPEARANCE_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SETTINGS_SPAM_BLOCKED_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SETTINGS_BACKUP_RESTORE_TEST_TAG)
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertEquals(1, backupOpens) }
    }
}
