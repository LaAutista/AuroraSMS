// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import org.aurorasms.app.R
import org.aurorasms.core.designsystem.AuroraMaterialProfile

@Composable
fun ThemeStudioRoute(
    appearance: AppAppearanceState,
    controller: AppearanceController,
    onPreviewProfileChange: (AuroraMaterialProfile?) -> Unit,
    onClose: () -> Unit,
) {
    val defaultName = stringResource(R.string.appearance_default_profile)
    val durableProfiles = appearance.toThemeStudioProfiles(defaultName)
    val durableActiveId = appearance.activeProfileId ?: CANONICAL_PROFILE_ID
    var editorState by rememberSaveable(stateSaver = ThemeStudioEditorStateSaver) {
        mutableStateOf(
            ThemeStudioEditorState.create(
                savedProfiles = durableProfiles,
                activeProfileId = durableActiveId,
            ),
        )
    }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose { onPreviewProfileChange(null) }
    }
    LaunchedEffect(appearance.snapshotRevision) {
        if (!editorState.dirty && editorState.operation == ThemeStudioOperation.IDLE) {
            editorState = ThemeStudioEditorState.create(
                savedProfiles = durableProfiles,
                activeProfileId = durableActiveId,
            )
        }
    }

    ThemeStudioScreen(
        state = editorState,
        onStateChange = { editorState = it },
        onPreviewProfileChange = { profile -> onPreviewProfileChange(profile) },
        onApply = { request ->
            if (editorState.operation != ThemeStudioOperation.IDLE) return@ThemeStudioScreen
            editorState = reduceThemeStudio(
                editorState,
                ThemeStudioAction.SetOperation(ThemeStudioOperation.APPLYING),
            )
            scope.launch {
                when (val result = controller.apply(request)) {
                    AppearanceControllerResult.Success -> {
                        onPreviewProfileChange(null)
                        onClose()
                    }
                    is AppearanceControllerResult.Failed -> {
                        editorState = reduceThemeStudio(
                            reduceThemeStudio(
                                editorState,
                                ThemeStudioAction.SetOperation(ThemeStudioOperation.IDLE),
                            ),
                            ThemeStudioAction.SetError(result.error),
                        )
                    }
                }
            }
        },
        onCancel = {
            onPreviewProfileChange(null)
            onClose()
        },
        onDeleteConfirmed = { profile ->
            if (editorState.operation != ThemeStudioOperation.IDLE) return@ThemeStudioScreen
            editorState = reduceThemeStudio(
                editorState,
                ThemeStudioAction.SetOperation(ThemeStudioOperation.DELETING),
            )
            scope.launch {
                when (val result = controller.delete(profile.id, profile.revision)) {
                    AppearanceControllerResult.Success -> {
                        val remaining = editorState.savedProfiles.filterNot { it.id == profile.id }
                        val activeId = editorState.activeProfileId
                            .takeUnless { it == profile.id }
                            ?: CANONICAL_PROFILE_ID
                        editorState = ThemeStudioEditorState.create(
                            savedProfiles = remaining,
                            activeProfileId = activeId,
                        )
                        onPreviewProfileChange(editorState.draftProfile)
                    }
                    is AppearanceControllerResult.Failed -> {
                        editorState = reduceThemeStudio(
                            reduceThemeStudio(
                                editorState,
                                ThemeStudioAction.SetOperation(ThemeStudioOperation.IDLE),
                            ),
                            ThemeStudioAction.SetError(result.error),
                        )
                    }
                }
            }
        },
    )
}

private fun AppAppearanceState.toThemeStudioProfiles(
    defaultName: String,
): List<ThemeStudioSavedProfile> = buildList {
    add(
        ThemeStudioSavedProfile(
            id = CANONICAL_PROFILE_ID,
            revision = CANONICAL_PROFILE_REVISION,
            name = defaultName,
            profile = AuroraMaterialProfile.Default,
            deletable = false,
        ),
    )
    profiles.forEach { profile ->
        add(
            ThemeStudioSavedProfile(
                id = profile.id,
                revision = profile.revision,
                name = profile.name,
                profile = profile.profile,
                deletable = true,
            ),
        )
    }
}

private const val CANONICAL_PROFILE_ID: Long = 0L
private const val CANONICAL_PROFILE_REVISION: Long = 0L
