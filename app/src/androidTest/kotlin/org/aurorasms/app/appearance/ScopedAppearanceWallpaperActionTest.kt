// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScopedAppearanceWallpaperActionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ScopedAppearanceTestActivity>()

    @Test
    fun wallpaperActionIsOptionalAndRestrictedToThreadScopes() {
        compose.onNodeWithTag(SCOPED_APPEARANCE_WALLPAPER_TEST_TAG).assertDoesNotExist()

        compose.runOnIdle {
            compose.activity.updateWallpaperAction(ScopedAppearanceKind.GLOBAL_THREADS, available = true)
        }
        compose.onNodeWithTag(SCOPED_APPEARANCE_WALLPAPER_TEST_TAG).assertExists().performClick()
        compose.runOnIdle { assertEquals(1, compose.activity.wallpaperOpenRequestCount) }

        compose.runOnIdle {
            compose.activity.updateWallpaperAction(ScopedAppearanceKind.CONVERSATION, available = true)
        }
        compose.onNodeWithTag(SCOPED_APPEARANCE_WALLPAPER_TEST_TAG).assertExists()

        compose.runOnIdle {
            compose.activity.updateWallpaperAction(ScopedAppearanceKind.INBOX, available = true)
        }
        compose.onNodeWithTag(SCOPED_APPEARANCE_WALLPAPER_TEST_TAG).assertDoesNotExist()

        compose.runOnIdle {
            compose.activity.updateWallpaperAction(ScopedAppearanceKind.GLOBAL_THREADS, available = false)
        }
        compose.onNodeWithTag(SCOPED_APPEARANCE_WALLPAPER_TEST_TAG).assertDoesNotExist()
    }
}
