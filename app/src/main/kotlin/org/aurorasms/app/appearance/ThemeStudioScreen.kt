// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.aurorasms.app.R
import org.aurorasms.core.designsystem.AuroraAvatarMask
import org.aurorasms.core.designsystem.AuroraBubbleGeometry
import org.aurorasms.core.designsystem.AuroraMaterialProfile
import org.aurorasms.core.designsystem.AuroraMaterialTheme
import org.aurorasms.core.designsystem.AuroraPalette
import org.aurorasms.core.designsystem.AuroraRowDensity
import org.aurorasms.core.designsystem.LocalAuroraMaterialTokens
import org.aurorasms.core.designsystem.toShape

/**
 * Persistence-agnostic Theme Studio. All edits are reduced locally and emitted
 * through callbacks; this composable never reads or writes durable state.
 */
@Composable
fun ThemeStudioScreen(
    state: ThemeStudioEditorState,
    onStateChange: (ThemeStudioEditorState) -> Unit,
    onPreviewProfileChange: (AuroraMaterialProfile) -> Unit,
    onApply: (ThemeStudioApplyRequest) -> Unit,
    onCancel: () -> Unit,
    onDeleteConfirmed: (ThemeStudioSavedProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var nameFocused by remember { mutableStateOf(false) }

    fun dispatch(action: ThemeStudioAction) {
        val next = reduceThemeStudio(state, action)
        if (next == state) return
        onStateChange(next)
    }

    // Re-emits a restored editor draft after Activity/process state recreation.
    LaunchedEffect(state.draftProfile) {
        onPreviewProfileChange(state.draftProfile)
    }

    fun cancel() {
        if (!state.interactive) return
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        onPreviewProfileChange(state.activeProfile.profile)
        onCancel()
    }

    BackHandler {
        when {
            !state.interactive -> Unit
            nameFocused -> {
                focusManager.clearFocus(force = true)
                keyboard?.hide()
            }
            else -> cancel()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .semantics { testTagsAsResourceId = true }
            .testTag(THEME_STUDIO_SCREEN_TEST_TAG),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            ThemeStudioHeader(
                enabled = state.interactive,
                onBack = ::cancel,
            )
            HorizontalDivider()
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag(THEME_STUDIO_LIST_TEST_TAG),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item(key = "saved-profile") {
                    SavedProfileChooser(
                        state = state,
                        onSelect = { dispatch(ThemeStudioAction.SelectProfile(it)) },
                        onNewCopy = { dispatch(ThemeStudioAction.NewCopy) },
                    )
                }
                item(key = "profile-name") {
                    ProfileNameEditor(
                        state = state,
                        onNameChanged = { dispatch(ThemeStudioAction.ChangeName(it)) },
                        onFocusChanged = { nameFocused = it },
                    )
                }
                item(key = "live-preview") {
                    ThemeStudioPreview(state.draftProfile)
                }
                item(key = "palette") {
                    ChoiceSection(
                        title = stringResource(R.string.appearance_palette),
                        values = AuroraPalette.entries,
                        selected = state.draftProfile.palette,
                        enabled = state.interactive,
                        label = { paletteLabel(it) },
                        testTag = { "$THEME_STUDIO_PALETTE_OPTION_PREFIX${it.toStableCode()}" },
                        onSelected = { dispatch(ThemeStudioAction.ChangePalette(it)) },
                    )
                }
                item(key = "hue") {
                    HueControl(
                        profile = state.draftProfile,
                        enabled = state.interactive,
                        onHueChanged = { dispatch(ThemeStudioAction.ChangeHue(it)) },
                    )
                }
                item(key = "density") {
                    ChoiceSection(
                        title = stringResource(R.string.appearance_density),
                        values = AuroraRowDensity.entries,
                        selected = state.draftProfile.rowDensity,
                        enabled = state.interactive,
                        label = { densityLabel(it) },
                        testTag = { "$THEME_STUDIO_DENSITY_OPTION_PREFIX${it.toStableCode()}" },
                        onSelected = { dispatch(ThemeStudioAction.ChangeDensity(it)) },
                    )
                }
                item(key = "avatar-mask") {
                    ChoiceSection(
                        title = stringResource(R.string.appearance_avatar_mask),
                        values = AuroraAvatarMask.entries,
                        selected = state.draftProfile.avatarMask,
                        enabled = state.interactive,
                        label = { avatarMaskLabel(it) },
                        testTag = { "$THEME_STUDIO_AVATAR_OPTION_PREFIX${it.toStableCode()}" },
                        onSelected = { dispatch(ThemeStudioAction.ChangeAvatarMask(it)) },
                    )
                }
                item(key = "bubble-geometry") {
                    ChoiceSection(
                        title = stringResource(R.string.appearance_bubble_geometry),
                        values = AuroraBubbleGeometry.entries,
                        selected = state.draftProfile.bubbleGeometry,
                        enabled = state.interactive,
                        label = { bubbleGeometryLabel(it) },
                        testTag = { "$THEME_STUDIO_BUBBLE_OPTION_PREFIX${it.toStableCode()}" },
                        onSelected = { dispatch(ThemeStudioAction.ChangeBubbleGeometry(it)) },
                    )
                }
                item(key = "contrast") {
                    ToggleRow(
                        title = stringResource(R.string.appearance_high_contrast),
                        explanation = stringResource(R.string.appearance_high_contrast_explanation),
                        checked = state.draftProfile.highContrast,
                        enabled = state.interactive,
                        testTag = THEME_STUDIO_HIGH_CONTRAST_TEST_TAG,
                        onCheckedChange = {
                            dispatch(ThemeStudioAction.ChangeHighContrast(it))
                        },
                    )
                }
                item(key = "navigation") {
                    ReadOnlyNavigation()
                }
                item(key = "actions") {
                    ThemeStudioActions(
                        state = state,
                        onReset = { dispatch(ThemeStudioAction.Reset) },
                        onApply = {
                            focusManager.clearFocus(force = true)
                            keyboard?.hide()
                            state.applyRequest()?.let(onApply)
                        },
                        onCancel = ::cancel,
                        onDelete = { dispatch(ThemeStudioAction.RequestDelete) },
                    )
                }
            }
        }
    }

    state.deleteConfirmationProfileId?.let { profileId ->
        val profile = state.savedProfiles.firstOrNull { it.id == profileId && it.deletable }
        if (profile != null) {
            DeleteProfileDialog(
                profile = profile,
                onDismiss = { dispatch(ThemeStudioAction.DismissDelete) },
                onConfirm = {
                    onStateChange(reduceThemeStudio(state, ThemeStudioAction.DismissDelete))
                    onDeleteConfirmed(profile)
                },
            )
        }
    }
}

@Composable
private fun ThemeStudioHeader(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            modifier = Modifier
                .heightIn(min = MINIMUM_CONTROL_SIZE)
                .testTag(THEME_STUDIO_BACK_TEST_TAG),
            enabled = enabled,
            onClick = onBack,
        ) {
            Text(stringResource(R.string.appearance_back))
        }
        Text(
            text = stringResource(R.string.appearance_title),
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun SavedProfileChooser(
    state: ThemeStudioEditorState,
    onSelect: (Long) -> Unit,
    onNewCopy: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.appearance_saved_profile),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MINIMUM_CONTROL_SIZE)
                    .testTag(THEME_STUDIO_PROFILE_SELECTOR_TEST_TAG)
                    .semantics { stateDescription = state.selectedProfile.name },
                enabled = state.interactive,
                onClick = { expanded = true },
            ) {
                Text(
                    text = stringResource(
                        R.string.appearance_selected_profile,
                        state.selectedProfile.name,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                state.savedProfiles.forEach { profile ->
                    DropdownMenuItem(
                        modifier = Modifier
                            .heightIn(min = MINIMUM_CONTROL_SIZE)
                            .testTag("$THEME_STUDIO_PROFILE_OPTION_PREFIX${profile.id}"),
                        text = {
                            Text(
                                text = profile.name,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(profile.id)
                        },
                    )
                }
            }
        }
        OutlinedButton(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MINIMUM_CONTROL_SIZE)
                .testTag(THEME_STUDIO_NEW_COPY_TEST_TAG),
            enabled = state.canCreateCopy,
            onClick = onNewCopy,
        ) {
            Text(stringResource(R.string.appearance_new_copy))
        }
        Text(
            text = stringResource(
                if (state.savedProfiles.size >= MAXIMUM_THEME_STUDIO_SAVED_PROFILES) {
                    R.string.appearance_profile_limit
                } else {
                    R.string.appearance_new_copy_explanation
                },
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ProfileNameEditor(
    state: ThemeStudioEditorState,
    onNameChanged: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    OutlinedTextField(
        value = state.name,
        onValueChange = onNameChanged,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .testTag(THEME_STUDIO_NAME_FIELD_TEST_TAG),
        enabled = state.interactive,
        singleLine = true,
        isError = !state.nameValid,
        label = { Text(stringResource(R.string.appearance_profile_name)) },
        supportingText = if (!state.nameValid) {
            { Text(stringResource(R.string.appearance_profile_name_required)) }
        } else {
            null
        },
    )
}

@Composable
private fun ThemeStudioPreview(profile: AuroraMaterialProfile) {
    val previewDescription = stringResource(R.string.appearance_preview_description)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.appearance_live_preview),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        AuroraMaterialTheme(profile = profile) {
            val tokens = LocalAuroraMaterialTokens.current
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(THEME_STUDIO_PREVIEW_TEST_TAG)
                    .semantics { contentDescription = previewDescription },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier.padding(tokens.contentSpacing),
                    verticalArrangement = Arrangement.spacedBy(tokens.contentSpacing),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = tokens.rowMinimumHeight),
                        horizontalArrangement = Arrangement.spacedBy(tokens.contentSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            modifier = Modifier.size(tokens.avatarSize),
                            shape = profile.avatarMask.toShape(),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    stringResource(R.string.appearance_preview_avatar_initial),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.appearance_preview_title),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    PreviewBubble(
                        text = stringResource(R.string.appearance_preview_incoming),
                        incoming = true,
                    )
                    PreviewBubble(
                        text = stringResource(R.string.appearance_preview_outgoing),
                        incoming = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.PreviewBubble(text: String, incoming: Boolean) {
    val tokens = LocalAuroraMaterialTokens.current
    Surface(
        modifier = Modifier.align(if (incoming) Alignment.Start else Alignment.End),
        shape = RoundedCornerShape(tokens.bubbleCornerRadius),
        color = if (incoming) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            color = if (incoming) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
        )
    }
}

@Composable
private fun <T> ChoiceSection(
    title: String,
    values: List<T>,
    selected: T,
    enabled: Boolean,
    label: @Composable (T) -> String,
    testTag: (T) -> String,
    onSelected: (T) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(bottom = 4.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        values.forEach { value ->
            val selectedValue = value == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MINIMUM_CONTROL_SIZE)
                    .selectable(
                        selected = selectedValue,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelected(value) },
                    )
                    .testTag(testTag(value))
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selectedValue,
                    enabled = enabled,
                    onClick = null,
                )
                Text(
                    text = label(value),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun HueControl(
    profile: AuroraMaterialProfile,
    enabled: Boolean,
    onHueChanged: (Int) -> Unit,
) {
    val dynamic = profile.palette == AuroraPalette.SYSTEM_DYNAMIC
    val hueDescription = if (dynamic) {
        stringResource(R.string.appearance_dynamic_hue_explanation)
    } else {
        stringResource(R.string.appearance_hue, profile.hueDegrees)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.appearance_hue, profile.hueDegrees),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Slider(
            value = profile.hueDegrees.toFloat(),
            onValueChange = { onHueChanged(it.roundToInt()) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MINIMUM_CONTROL_SIZE)
                .testTag(THEME_STUDIO_HUE_TEST_TAG)
                .semantics { stateDescription = hueDescription },
            enabled = enabled && !dynamic,
            valueRange = 0f..359f,
            steps = 358,
        )
        if (dynamic) {
            Text(
                text = stringResource(R.string.appearance_dynamic_hue_explanation),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    explanation: String,
    checked: Boolean,
    enabled: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MINIMUM_CONTROL_SIZE)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .testTag(testTag)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            Text(
                text = explanation,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null,
        )
    }
}

@Composable
private fun ReadOnlyNavigation() {
    val classic = stringResource(R.string.appearance_navigation_classic)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.appearance_navigation),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MINIMUM_CONTROL_SIZE)
                .testTag(THEME_STUDIO_NAVIGATION_TEST_TAG)
                .semantics { stateDescription = classic },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = classic, fontWeight = FontWeight.SemiBold)
                Text(
                    text = stringResource(R.string.appearance_navigation_explanation),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ThemeStudioActions(
    state: ThemeStudioEditorState,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.error?.let { error ->
            Text(
                text = errorMessage(error),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(THEME_STUDIO_ERROR_TEST_TAG)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        when (state.operation) {
            ThemeStudioOperation.IDLE -> Unit
            ThemeStudioOperation.APPLYING,
            ThemeStudioOperation.DELETING,
            -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text(
                    stringResource(
                        if (state.operation == ThemeStudioOperation.APPLYING) {
                            R.string.appearance_applying
                        } else {
                            R.string.appearance_deleting
                        },
                    ),
                )
            }
        }
        if (state.resetStaged) {
            Text(
                text = stringResource(R.string.appearance_reset_staged),
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MINIMUM_CONTROL_SIZE)
                .testTag(THEME_STUDIO_RESET_TEST_TAG),
            enabled = state.interactive,
            onClick = onReset,
        ) {
            Text(stringResource(R.string.appearance_reset))
        }
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MINIMUM_CONTROL_SIZE)
                .testTag(THEME_STUDIO_APPLY_TEST_TAG),
            enabled = state.canApply,
            onClick = onApply,
        ) {
            Text(stringResource(R.string.appearance_apply))
        }
        OutlinedButton(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MINIMUM_CONTROL_SIZE)
                .testTag(THEME_STUDIO_CANCEL_TEST_TAG),
            enabled = state.interactive,
            onClick = onCancel,
        ) {
            Text(stringResource(R.string.appearance_cancel))
        }
        if (!state.newCopy && state.selectedProfile.deletable) {
            TextButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MINIMUM_CONTROL_SIZE)
                    .testTag(THEME_STUDIO_DELETE_TEST_TAG),
                enabled = state.interactive,
                onClick = onDelete,
            ) {
                Text(
                    text = stringResource(R.string.appearance_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DeleteProfileDialog(
    profile: ThemeStudioSavedProfile,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.appearance_delete_title, profile.name))
        },
        text = { Text(stringResource(R.string.appearance_delete_message)) },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag(THEME_STUDIO_DELETE_CONFIRM_TEST_TAG),
                onClick = onConfirm,
            ) {
                Text(
                    text = stringResource(R.string.appearance_delete_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.appearance_delete_dismiss))
            }
        },
    )
}

@Composable
private fun paletteLabel(value: AuroraPalette): String = stringResource(
    when (value) {
        AuroraPalette.AURORA_DARK -> R.string.appearance_palette_dark
        AuroraPalette.AMOLED_BLACK -> R.string.appearance_palette_amoled
        AuroraPalette.LIGHT -> R.string.appearance_palette_light
        AuroraPalette.SYSTEM_DYNAMIC -> R.string.appearance_palette_dynamic
    },
)

@Composable
private fun densityLabel(value: AuroraRowDensity): String = stringResource(
    when (value) {
        AuroraRowDensity.COMPACT -> R.string.appearance_density_compact
        AuroraRowDensity.COMFORTABLE -> R.string.appearance_density_comfortable
        AuroraRowDensity.SPACIOUS -> R.string.appearance_density_spacious
    },
)

@Composable
private fun avatarMaskLabel(value: AuroraAvatarMask): String = stringResource(
    when (value) {
        AuroraAvatarMask.CIRCLE -> R.string.appearance_avatar_circle
        AuroraAvatarMask.ROUNDED_SQUARE -> R.string.appearance_avatar_rounded_square
        AuroraAvatarMask.SQUIRCLE -> R.string.appearance_avatar_squircle
        AuroraAvatarMask.HEXAGON -> R.string.appearance_avatar_hexagon
    },
)

@Composable
private fun bubbleGeometryLabel(value: AuroraBubbleGeometry): String = stringResource(
    when (value) {
        AuroraBubbleGeometry.COMPACT -> R.string.appearance_bubble_compact
        AuroraBubbleGeometry.ROUNDED -> R.string.appearance_bubble_rounded
        AuroraBubbleGeometry.EXPRESSIVE -> R.string.appearance_bubble_expressive
    },
)

@Composable
private fun errorMessage(error: ThemeStudioError): String = stringResource(
    when (error) {
        ThemeStudioError.SAVE_FAILED -> R.string.appearance_save_failed
        ThemeStudioError.DELETE_FAILED -> R.string.appearance_delete_failed
        ThemeStudioError.STALE_PROFILE -> R.string.appearance_stale_profile
        ThemeStudioError.DUPLICATE_NAME -> R.string.appearance_duplicate_name
        ThemeStudioError.PROFILE_LIMIT -> R.string.appearance_profile_limit
    },
)

const val THEME_STUDIO_SCREEN_TEST_TAG: String = "aurora-theme-studio-screen"
const val THEME_STUDIO_LIST_TEST_TAG: String = "aurora-theme-studio-list"
const val THEME_STUDIO_BACK_TEST_TAG: String = "aurora-theme-studio-back"
const val THEME_STUDIO_PROFILE_SELECTOR_TEST_TAG: String = "aurora-theme-profile-selector"
const val THEME_STUDIO_PROFILE_OPTION_PREFIX: String = "aurora-theme-profile-option-"
const val THEME_STUDIO_NEW_COPY_TEST_TAG: String = "aurora-theme-new-copy"
const val THEME_STUDIO_NAME_FIELD_TEST_TAG: String = "aurora-theme-profile-name"
const val THEME_STUDIO_PREVIEW_TEST_TAG: String = "aurora-theme-preview"
const val THEME_STUDIO_PALETTE_OPTION_PREFIX: String = "aurora-theme-palette-"
const val THEME_STUDIO_HUE_TEST_TAG: String = "aurora-theme-hue"
const val THEME_STUDIO_DENSITY_OPTION_PREFIX: String = "aurora-theme-density-"
const val THEME_STUDIO_AVATAR_OPTION_PREFIX: String = "aurora-theme-avatar-"
const val THEME_STUDIO_BUBBLE_OPTION_PREFIX: String = "aurora-theme-bubble-"
const val THEME_STUDIO_HIGH_CONTRAST_TEST_TAG: String = "aurora-theme-high-contrast"
const val THEME_STUDIO_NAVIGATION_TEST_TAG: String = "aurora-theme-navigation-classic"
const val THEME_STUDIO_RESET_TEST_TAG: String = "aurora-theme-reset"
const val THEME_STUDIO_APPLY_TEST_TAG: String = "aurora-theme-apply"
const val THEME_STUDIO_CANCEL_TEST_TAG: String = "aurora-theme-cancel"
const val THEME_STUDIO_DELETE_TEST_TAG: String = "aurora-theme-delete"
const val THEME_STUDIO_DELETE_CONFIRM_TEST_TAG: String = "aurora-theme-delete-confirm"
const val THEME_STUDIO_ERROR_TEST_TAG: String = "aurora-theme-error"
private val MINIMUM_CONTROL_SIZE = 48.dp
