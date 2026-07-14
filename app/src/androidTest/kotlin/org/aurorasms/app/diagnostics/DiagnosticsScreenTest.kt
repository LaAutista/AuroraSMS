// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.diagnostics

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aurorasms.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun controlsRemainReachableBelowTheDiagnosticRows() {
        compose.onNodeWithText("diagnostics", substring = true, ignoreCase = true).performClick()

        compose.onNodeWithTag(DIAGNOSTICS_SCREEN_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithTag(DIAGNOSTICS_CLOSE_TEST_TAG)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.onAllNodesWithTag(DIAGNOSTICS_SCREEN_TEST_TAG).assertCountEquals(0)
    }
}
