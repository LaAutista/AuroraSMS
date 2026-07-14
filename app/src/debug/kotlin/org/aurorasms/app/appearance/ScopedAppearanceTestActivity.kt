// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.awaitCancellation
import org.aurorasms.core.designsystem.AuroraMaterialProfile
import org.aurorasms.core.designsystem.AuroraMaterialTheme
import org.aurorasms.core.designsystem.AuroraPalette

/** Debug-only host for scoped appearance behavior without SMS role or provider data. */
class ScopedAppearanceTestActivity : ComponentActivity() {
    private var currentOverride by mutableStateOf(AppAppearanceOverride(profileId = 7L, revision = 3L))
    private var privateRestorationKey by mutableStateOf(SYNTHETIC_TARGET_A)
    private var profiles by mutableStateOf(testProfiles())
    private var profileSnapshotReady by mutableStateOf(true)
    private var overrideSnapshotReady by mutableStateOf(true)

    @Volatile
    var latestAppliedProfileId: Long? = null
        private set

    @Volatile
    var latestExpectedRevision: Long? = null
        private set

    @Volatile
    var applyRequestCount: Int = 0
        private set

    @Volatile
    var dismissRequestCount: Int = 0
        private set

    @Volatile
    var nextResult: ScopedAppearanceControllerResult = ScopedAppearanceControllerResult.Success

    @Volatile
    var suspendApply: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null && intent.getBooleanExtra(DELAY_PROFILES_EXTRA, false)) {
            profiles = emptyList()
            profileSnapshotReady = false
        }
        setContent {
            var visible by rememberSaveable { mutableStateOf(true) }
            AuroraMaterialTheme {
                if (visible) {
                    ScopedAppearanceDialog(
                        kind = ScopedAppearanceKind.CONVERSATION,
                        privateRestorationKey = privateRestorationKey,
                        profiles = profiles,
                        profileSnapshotReady = profileSnapshotReady,
                        overrideSnapshotReady = overrideSnapshotReady,
                        inheritedProfile = AuroraMaterialProfile.Default,
                        inheritedName = "Aurora default",
                        currentOverride = currentOverride,
                        onApply = { profileId, expectedRevision ->
                            applyRequestCount += 1
                            latestAppliedProfileId = profileId
                            latestExpectedRevision = expectedRevision
                            if (suspendApply) awaitCancellation()
                            nextResult
                        },
                        onDismiss = {
                            dismissRequestCount += 1
                            visible = false
                        },
                    )
                }
            }
        }
    }

    fun showMissingDurableProfile() {
        currentOverride = AppAppearanceOverride(profileId = 99L, revision = 5L)
    }

    fun switchToSecondPrivateTarget() {
        privateRestorationKey = SYNTHETIC_TARGET_B
    }

    fun delayProfilesOnNextRecreation() {
        intent.putExtra(DELAY_PROFILES_EXTRA, true)
    }

    fun completeDelayedProfileSnapshot() {
        profiles = testProfiles()
        profileSnapshotReady = true
    }

    fun updateOverrideSnapshotReadiness(ready: Boolean) {
        overrideSnapshotReady = ready
    }

    private fun testProfiles(): List<AppAppearanceProfile> = listOf(
        AppAppearanceProfile(
            id = 7L,
            revision = 3L,
            name = "Evening",
            profile = AuroraMaterialProfile(palette = AuroraPalette.AMOLED_BLACK),
            focalXPermill = 500,
            focalYPermill = 500,
        ),
        AppAppearanceProfile(
            id = 8L,
            revision = 1L,
            name = "Daylight",
            profile = AuroraMaterialProfile(palette = AuroraPalette.LIGHT),
            focalXPermill = 500,
            focalYPermill = 500,
        ),
    )

    private companion object {
        const val DELAY_PROFILES_EXTRA = "org.aurorasms.app.debug.DELAY_SCOPED_PROFILES"
        const val SYNTHETIC_TARGET_A = "synthetic-conversation-target-a"
        const val SYNTHETIC_TARGET_B = "synthetic-conversation-target-b"
    }
}
