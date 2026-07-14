// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressBack
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScopedAppearanceDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<ScopedAppearanceTestActivity>()

    @Test
    fun namedSelectionAndInheritedResetCommitOnlyOnApply() {
        compose.onNodeWithTag(SCOPED_APPEARANCE_DIALOG_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SCOPED_APPEARANCE_APPLY_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(SCOPED_APPEARANCE_SELECTOR_TEST_TAG).performClick()
        compose.onNodeWithTag("${SCOPED_APPEARANCE_PROFILE_OPTION_PREFIX}8").performClick()
        compose.onNodeWithTag(SCOPED_APPEARANCE_SELECTOR_TEST_TAG).assertTextContains("Daylight")
        compose.onNodeWithTag(SCOPED_APPEARANCE_APPLY_TEST_TAG).assertIsEnabled()

        compose.onNodeWithTag(SCOPED_APPEARANCE_RESET_TEST_TAG).performClick()
        compose.onNodeWithTag(SCOPED_APPEARANCE_SELECTOR_TEST_TAG)
            .assertTextContains("Use inherited appearance")
        compose.onNodeWithTag(SCOPED_APPEARANCE_APPLY_TEST_TAG).performClick()

        compose.runOnIdle {
            assertEquals(1, compose.activity.applyRequestCount)
            assertNull(compose.activity.latestAppliedProfileId)
            assertEquals(3L, compose.activity.latestExpectedRevision)
        }
        compose.onNodeWithTag(SCOPED_APPEARANCE_DIALOG_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun cancelIsADurableNoOpAcrossRecreation() {
        selectDaylight()
        compose.onNodeWithTag(SCOPED_APPEARANCE_CANCEL_TEST_TAG).performClick()
        compose.runOnIdle {
            assertEquals(0, compose.activity.applyRequestCount)
            assertEquals(1, compose.activity.dismissRequestCount)
        }

        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag(SCOPED_APPEARANCE_DIALOG_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun systemBackDismissesWithoutApplying() {
        selectDaylight()
        onView(isRoot()).inRoot(isDialog()).perform(pressBack())
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(SCOPED_APPEARANCE_DIALOG_TEST_TAG)
                .fetchSemanticsNodes()
                .isEmpty()
        }

        compose.onNodeWithTag(SCOPED_APPEARANCE_DIALOG_TEST_TAG).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, compose.activity.applyRequestCount)
            assertEquals(1, compose.activity.dismissRequestCount)
        }
    }

    @Test
    fun recreationRestoresBoundedDraftButNotErrorOrInFlightOperation() {
        selectDaylight()
        compose.runOnIdle {
            compose.activity.nextResult =
                ScopedAppearanceControllerResult.Failed(ScopedAppearanceError.SAVE_FAILED)
        }
        compose.onNodeWithTag(SCOPED_APPEARANCE_APPLY_TEST_TAG).performClick()
        compose.onNodeWithTag(SCOPED_APPEARANCE_ERROR_TEST_TAG).assertIsDisplayed()

        compose.activityRule.scenario.recreate()

        compose.onNodeWithTag(SCOPED_APPEARANCE_SELECTOR_TEST_TAG).assertTextContains("Daylight")
        compose.onNodeWithTag(SCOPED_APPEARANCE_ERROR_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SCOPED_APPEARANCE_APPLY_TEST_TAG).assertIsEnabled()

        compose.runOnIdle { compose.activity.suspendApply = true }
        compose.onNodeWithTag(SCOPED_APPEARANCE_APPLY_TEST_TAG).performClick()
        compose.onNodeWithTag(SCOPED_APPEARANCE_APPLY_TEST_TAG).assertIsNotEnabled()

        compose.activityRule.scenario.recreate()

        compose.onNodeWithTag(SCOPED_APPEARANCE_SELECTOR_TEST_TAG).assertTextContains("Daylight")
        compose.onNodeWithTag(SCOPED_APPEARANCE_ERROR_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SCOPED_APPEARANCE_APPLY_TEST_TAG).assertIsEnabled()
        compose.onNodeWithText(SYNTHETIC_PRIVATE_TARGET).assertDoesNotExist()
    }

    @Test
    fun delayedProfileSnapshotDoesNotReconcileAwayRestoredDraft() {
        selectDaylight()
        compose.runOnIdle { compose.activity.delayProfilesOnNextRecreation() }

        compose.activityRule.scenario.recreate()

        compose.onNodeWithTag(SCOPED_APPEARANCE_LOADING_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SCOPED_APPEARANCE_SELECTOR_TEST_TAG)
            .assertIsNotEnabled()
        compose.onNodeWithTag(SCOPED_APPEARANCE_APPLY_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(SCOPED_APPEARANCE_ERROR_TEST_TAG).assertDoesNotExist()

        compose.runOnIdle { compose.activity.completeDelayedProfileSnapshot() }

        compose.onNodeWithTag(SCOPED_APPEARANCE_LOADING_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SCOPED_APPEARANCE_SELECTOR_TEST_TAG).assertTextContains("Daylight")
        compose.onNodeWithTag(SCOPED_APPEARANCE_APPLY_TEST_TAG).assertIsEnabled()
        compose.onNodeWithTag(SCOPED_APPEARANCE_ERROR_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun targetMismatchNeverRendersOrAppliesPriorTargetsDraft() {
        selectDaylight()

        compose.runOnIdle { compose.activity.switchToSecondPrivateTarget() }

        compose.onNodeWithTag(SCOPED_APPEARANCE_SELECTOR_TEST_TAG).assertTextContains("Evening")
        compose.onNodeWithTag(SCOPED_APPEARANCE_APPLY_TEST_TAG).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, compose.activity.applyRequestCount) }
    }

    @Test
    fun assignmentQueryLoadingDisablesEditingUntilExactTargetReturns() {
        compose.runOnIdle { compose.activity.updateOverrideSnapshotReadiness(false) }

        compose.onNodeWithTag(SCOPED_APPEARANCE_LOADING_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SCOPED_APPEARANCE_SELECTOR_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(SCOPED_APPEARANCE_RESET_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(SCOPED_APPEARANCE_APPLY_TEST_TAG).assertIsNotEnabled()

        compose.runOnIdle { compose.activity.updateOverrideSnapshotReadiness(true) }

        compose.onNodeWithTag(SCOPED_APPEARANCE_LOADING_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SCOPED_APPEARANCE_SELECTOR_TEST_TAG).assertTextContains("Evening")
    }

    @Test
    fun missingDurableProfileRemainsRevisionCheckedUntilResetIsApplied() {
        compose.runOnIdle { compose.activity.showMissingDurableProfile() }

        compose.onNodeWithTag(SCOPED_APPEARANCE_SELECTOR_TEST_TAG)
            .assertTextContains("Saved profile unavailable")
        compose.onNodeWithTag(SCOPED_APPEARANCE_APPLY_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(SCOPED_APPEARANCE_RESET_TEST_TAG).assertIsEnabled().performClick()
        compose.onNodeWithTag(SCOPED_APPEARANCE_APPLY_TEST_TAG).assertIsEnabled().performClick()

        compose.runOnIdle {
            assertEquals(1, compose.activity.applyRequestCount)
            assertNull(compose.activity.latestAppliedProfileId)
            assertEquals(5L, compose.activity.latestExpectedRevision)
        }
    }

    private fun selectDaylight() {
        compose.onNodeWithTag(SCOPED_APPEARANCE_SELECTOR_TEST_TAG).performClick()
        compose.onNodeWithTag("${SCOPED_APPEARANCE_PROFILE_OPTION_PREFIX}8").performClick()
    }

    private companion object {
        const val SYNTHETIC_PRIVATE_TARGET = "synthetic-conversation-target-a"
    }
}
