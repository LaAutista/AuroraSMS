// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import org.aurorasms.core.designsystem.AuroraMaterialProfile
import org.aurorasms.core.designsystem.AuroraMaterialTheme
import org.aurorasms.core.designsystem.AuroraPalette

/** Debug-only host that lets instrumentation exercise Theme Studio without SMS-role setup. */
class ThemeStudioTestActivity : ComponentActivity() {
    @Volatile
    var latestState: ThemeStudioEditorState? = null
        private set

    @Volatile
    var latestPreview: AuroraMaterialProfile? = null
        private set

    @Volatile
    var latestApplyRequest: ThemeStudioApplyRequest? = null
        private set

    @Volatile
    var deleteRequestCount: Int = 0
        private set

    @Volatile
    var cancelRequestCount: Int = 0
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var state by rememberSaveable(stateSaver = ThemeStudioEditorStateSaver) {
                mutableStateOf(testInitialState())
            }
            latestState = state
            AuroraMaterialTheme {
                ThemeStudioScreen(
                    state = state,
                    onStateChange = { updated ->
                        state = updated
                        latestState = updated
                    },
                    onPreviewProfileChange = { latestPreview = it },
                    onApply = { latestApplyRequest = it },
                    onCancel = { cancelRequestCount += 1 },
                    onDeleteConfirmed = { deleteRequestCount += 1 },
                )
            }
        }
    }

    private fun testInitialState(): ThemeStudioEditorState = ThemeStudioEditorState.create(
        savedProfiles = listOf(
            ThemeStudioSavedProfile(
                id = 0L,
                revision = 0L,
                name = "Aurora default",
                profile = AuroraMaterialProfile.Default,
                deletable = false,
            ),
            ThemeStudioSavedProfile(
                id = 7L,
                revision = 3L,
                name = "Evening",
                profile = AuroraMaterialProfile(palette = AuroraPalette.AMOLED_BLACK),
                deletable = true,
            ),
        ),
        activeProfileId = 0L,
    )
}
