// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aurorasms.core.designsystem.AuroraPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeStudioScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ThemeStudioTestActivity>()

    @Test
    fun verticalControlsPreviewApplyCancelAndDeleteThroughCallbacksOnly() {
        compose.onNodeWithTag(THEME_STUDIO_SCREEN_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithTag("${THEME_STUDIO_PALETTE_OPTION_PREFIX}light")
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        compose.runOnIdle {
            assertEquals(AuroraPalette.LIGHT, compose.activity.latestPreview?.palette)
        }

        compose.onNodeWithTag(THEME_STUDIO_NEW_COPY_TEST_TAG)
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag(THEME_STUDIO_NAME_FIELD_TEST_TAG)
            .performScrollTo()
            .performTextReplacement("Daylight")
        scrollTo(THEME_STUDIO_APPLY_TEST_TAG)
        compose.onNodeWithTag(THEME_STUDIO_APPLY_TEST_TAG)
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle {
            assertEquals("Daylight", compose.activity.latestApplyRequest?.name)
            assertEquals(AuroraPalette.LIGHT, compose.activity.latestApplyRequest?.profile?.palette)
        }

        scrollTo(THEME_STUDIO_CANCEL_TEST_TAG)
        compose.onNodeWithTag(THEME_STUDIO_CANCEL_TEST_TAG)
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle {
            assertEquals(1, compose.activity.cancelRequestCount)
            assertEquals(AuroraPalette.AURORA_DARK, compose.activity.latestPreview?.palette)
        }

        scrollTo(THEME_STUDIO_PROFILE_SELECTOR_TEST_TAG)
        compose.onNodeWithTag(THEME_STUDIO_PROFILE_SELECTOR_TEST_TAG)
            .performClick()
        compose.onNodeWithTag("${THEME_STUDIO_PROFILE_OPTION_PREFIX}7")
            .performClick()
        scrollTo(THEME_STUDIO_DELETE_TEST_TAG)
        compose.onNodeWithTag(THEME_STUDIO_DELETE_TEST_TAG)
            .performClick()
        compose.onNodeWithTag(THEME_STUDIO_DELETE_CONFIRM_TEST_TAG)
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle {
            assertEquals(1, compose.activity.deleteRequestCount)
        }
    }

    @Test
    fun systemDynamicDisablesHueWithAnHonestExplanation() {
        compose.onNodeWithTag("${THEME_STUDIO_PALETTE_OPTION_PREFIX}system_dynamic")
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag(THEME_STUDIO_HUE_TEST_TAG)
            .performScrollTo()
            .assertIsNotEnabled()
        compose.onNodeWithTag(THEME_STUDIO_PREVIEW_TEST_TAG).assertExists()
        compose.runOnIdle {
            assertEquals(AuroraPalette.SYSTEM_DYNAMIC, compose.activity.latestPreview?.palette)
        }
    }

    @Test
    fun activityRecreationRestoresBoundedEditorDraftAndPreviewSelection() {
        compose.onNodeWithTag(THEME_STUDIO_NEW_COPY_TEST_TAG)
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag(THEME_STUDIO_NAME_FIELD_TEST_TAG)
            .performScrollTo()
            .performTextReplacement("Restored profile")
        scrollTo("${THEME_STUDIO_DENSITY_OPTION_PREFIX}spacious")
        compose.onNodeWithTag("${THEME_STUDIO_DENSITY_OPTION_PREFIX}spacious")
            .performClick()

        compose.activityRule.scenario.recreate()

        scrollTo(THEME_STUDIO_NAME_FIELD_TEST_TAG)
        compose.onNodeWithTag(THEME_STUDIO_NAME_FIELD_TEST_TAG)
            .assertTextContains("Restored profile")
        scrollTo("${THEME_STUDIO_DENSITY_OPTION_PREFIX}spacious")
        compose.onNodeWithTag("${THEME_STUDIO_DENSITY_OPTION_PREFIX}spacious")
            .assertIsSelected()
        scrollTo(THEME_STUDIO_APPLY_TEST_TAG)
        compose.onNodeWithTag(THEME_STUDIO_APPLY_TEST_TAG)
            .assertIsEnabled()
        compose.runOnIdle {
            assertNotNull(compose.activity.latestState)
            assertEquals("Restored profile", compose.activity.latestState?.name)
        }
    }

    private fun scrollTo(testTag: String) {
        compose.onNodeWithTag(THEME_STUDIO_LIST_TEST_TAG)
            .performScrollToNode(hasTestTag(testTag))
    }
}
