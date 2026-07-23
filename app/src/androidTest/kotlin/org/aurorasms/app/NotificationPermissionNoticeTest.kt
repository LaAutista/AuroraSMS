// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aurorasms.app.appearance.ScopedAppearanceTestActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationPermissionNoticeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ScopedAppearanceTestActivity>()

    @Test
    fun deniedNoticeExplainsContinuityAndInvokesRecovery() {
        var recoveryCalls = 0
        compose.runOnIdle {
            compose.activity.setContent {
                AuroraSmsTheme {
                    NotificationPermissionNoticeHost(
                        notificationPermissionRequired = true,
                        notificationPermissionGranted = false,
                        onRecoverNotificationPermission = { recoveryCalls += 1 },
                    )
                }
            }
        }

        compose.onNodeWithTag(NOTIFICATION_PERMISSION_NOTICE_TEST_TAG)
            .assertIsDisplayed()
        compose.onNodeWithText("Messages remain usable and readable in AuroraSMS", substring = true)
            .assertIsDisplayed()
            .assertTextContains("background and new-message alerts are off", substring = true)
        compose.onNodeWithTag(NOTIFICATION_PERMISSION_CTA_TEST_TAG)
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertEquals(1, recoveryCalls) }
    }

    @Test
    fun noticeIsHiddenWhenPermissionIsGrantedOrNotRequired() {
        compose.runOnIdle {
            compose.activity.setContent {
                AuroraSmsTheme {
                    NotificationPermissionNoticeHost(
                        notificationPermissionRequired = true,
                        notificationPermissionGranted = true,
                        onRecoverNotificationPermission = {},
                    )
                }
            }
        }
        compose.onNodeWithTag(NOTIFICATION_PERMISSION_NOTICE_TEST_TAG).assertDoesNotExist()

        compose.runOnIdle {
            compose.activity.setContent {
                AuroraSmsTheme {
                    NotificationPermissionNoticeHost(
                        notificationPermissionRequired = false,
                        notificationPermissionGranted = false,
                        onRecoverNotificationPermission = {},
                    )
                }
            }
        }
        compose.onNodeWithTag(NOTIFICATION_PERMISSION_NOTICE_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun settingsRecoveryIntentTargetsOnlyAuroraSms() {
        val intent = appNotificationSettingsIntent("org.aurorasms.app")

        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals("org.aurorasms.app", intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

}
