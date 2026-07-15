// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.aurorasms.app.R
import org.aurorasms.core.designsystem.AuroraMaterialProfile
import org.aurorasms.core.designsystem.AuroraMaterialTheme

internal enum class ScopedAppearanceKind {
    INBOX,
    GLOBAL_THREADS,
    CONVERSATION,
}

@androidx.compose.runtime.Immutable
internal data class ScopedAppearanceDraftState(
    val privateRestorationKey: String,
    val baselineProfileId: Long?,
    val expectedRevision: Long?,
    val selectedProfileId: Long?,
) {
    init {
        require(privateRestorationKey.isNotBlank()) { "A scoped appearance target key cannot be blank" }
        require(privateRestorationKey.length <= MAXIMUM_PRIVATE_RESTORATION_KEY_CHARACTERS) {
            "A scoped appearance target key is too large"
        }
        require(baselineProfileId == null || baselineProfileId > 0L)
        require(expectedRevision == null || expectedRevision > 0L)
        require(selectedProfileId == null || selectedProfileId > 0L)
    }

    fun forTargetOr(durable: ScopedAppearanceDraftState): ScopedAppearanceDraftState =
        if (privateRestorationKey == durable.privateRestorationKey) this else durable

    fun matchesTarget(privateKey: String): Boolean = privateRestorationKey == privateKey

    override fun toString(): String =
        "ScopedAppearanceDraftState(hasBaseline=${baselineProfileId != null}, " +
            "hasExpectedRevision=${expectedRevision != null}, " +
            "hasSelection=${selectedProfileId != null}, target=REDACTED)"
}

internal val ScopedAppearanceDraftStateSaver: Saver<ScopedAppearanceDraftState, Bundle> = Saver(
    save = { state ->
        Bundle().apply {
            putInt(SCOPED_DRAFT_SCHEMA_KEY, SCOPED_DRAFT_SCHEMA_VERSION)
            putString(SCOPED_DRAFT_TARGET_KEY, state.privateRestorationKey)
            state.baselineProfileId?.let { putLong(SCOPED_DRAFT_BASELINE_KEY, it) }
            state.expectedRevision?.let { putLong(SCOPED_DRAFT_REVISION_KEY, it) }
            state.selectedProfileId?.let { putLong(SCOPED_DRAFT_SELECTED_KEY, it) }
        }
    },
    restore = { bundle -> bundle.toScopedAppearanceDraftOrNull() },
)

private fun Bundle.toScopedAppearanceDraftOrNull(): ScopedAppearanceDraftState? = try {
    if (getInt(SCOPED_DRAFT_SCHEMA_KEY, -1) != SCOPED_DRAFT_SCHEMA_VERSION) return null
    val privateKey = getString(SCOPED_DRAFT_TARGET_KEY) ?: return null
    ScopedAppearanceDraftState(
        privateRestorationKey = privateKey,
        baselineProfileId = positiveLongOrNull(SCOPED_DRAFT_BASELINE_KEY),
        expectedRevision = positiveLongOrNull(SCOPED_DRAFT_REVISION_KEY),
        selectedProfileId = positiveLongOrNull(SCOPED_DRAFT_SELECTED_KEY),
    )
} catch (_: IllegalArgumentException) {
    null
} catch (_: ClassCastException) {
    null
}

private fun Bundle.positiveLongOrNull(key: String): Long? = if (containsKey(key)) {
    getLong(key).takeIf { it > 0L } ?: throw IllegalArgumentException("Invalid scoped draft value")
} else {
    null
}

/**
 * A route-local, persistence-agnostic editor for one profile reference. It never receives
 * conversation labels, participants, message text, or provider identifiers. The private restoration
 * key is app-private pseudonymous SavedState only and must never be logged, displayed, or exported.
 */
@Composable
internal fun ScopedAppearanceDialog(
    kind: ScopedAppearanceKind,
    privateRestorationKey: String,
    profiles: List<AppAppearanceProfile>,
    profileSnapshotReady: Boolean,
    overrideSnapshotReady: Boolean,
    inheritedProfile: AuroraMaterialProfile,
    inheritedName: String,
    currentOverride: AppAppearanceOverride?,
    onApply: suspend (profileId: Long?, expectedRevision: Long?) -> ScopedAppearanceControllerResult,
    onOpenWallpaper: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val durableProfileId = currentOverride?.profileId
    val durableDraft = ScopedAppearanceDraftState(
        privateRestorationKey = privateRestorationKey,
        baselineProfileId = durableProfileId,
        expectedRevision = currentOverride?.revision,
        selectedProfileId = durableProfileId,
    )
    var savedDraft by rememberSaveable(stateSaver = ScopedAppearanceDraftStateSaver) {
        mutableStateOf(durableDraft)
    }
    val draft = savedDraft.forTargetOr(durableDraft)
    var applying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<ScopedAppearanceError?>(null) }
    var selectorExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val profileIds = profiles.map(AppAppearanceProfile::id)
    val dirty = draft.selectedProfileId != draft.baselineProfileId
    val editorReady = profileSnapshotReady && overrideSnapshotReady

    LaunchedEffect(
        privateRestorationKey,
        currentOverride?.profileId,
        currentOverride?.revision,
        profileSnapshotReady,
        overrideSnapshotReady,
        profileIds,
    ) {
        when {
            !savedDraft.matchesTarget(privateRestorationKey) -> {
                savedDraft = durableDraft
                selectorExpanded = false
                error = null
            }
            !editorReady -> Unit
            !dirty && !applying -> {
                savedDraft = durableDraft
                error = null
            }
            draft.selectedProfileId != null && draft.selectedProfileId !in profileIds -> {
                error = ScopedAppearanceError.PROFILE_MISSING
            }
            error == ScopedAppearanceError.PROFILE_MISSING -> error = null
        }
    }

    val selectedProfile = profiles.firstOrNull { it.id == draft.selectedProfileId }
    val selectedProfileUnavailable = editorReady &&
        draft.selectedProfileId != null &&
        selectedProfile == null
    val previewProfile = selectedProfile?.profile ?: inheritedProfile
    val selectedName = when {
        selectedProfile != null -> selectedProfile.name
        !editorReady && draft.selectedProfileId != null ->
            stringResource(R.string.appearance_scope_profiles_loading)
        selectedProfileUnavailable -> stringResource(R.string.appearance_scope_profile_unavailable)
        else -> stringResource(R.string.appearance_scope_use_inherited)
    }
    val selectorState = if (draft.selectedProfileId == null) {
        stringResource(R.string.appearance_scope_inherited_state, inheritedName)
    } else {
        stringResource(R.string.appearance_scope_override_state, selectedName)
    }

    fun dismissIfIdle() {
        if (!applying) onDismiss()
    }

    AlertDialog(
        modifier = Modifier
            .semantics { testTagsAsResourceId = true }
            .testTag(SCOPED_APPEARANCE_DIALOG_TEST_TAG),
        onDismissRequest = ::dismissIfIdle,
        title = { Text(stringResource(kind.titleResource())) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.appearance_scope_explanation))
                Text(
                    text = stringResource(R.string.appearance_scope_profile),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = MINIMUM_SCOPE_CONTROL_SIZE)
                            .testTag(SCOPED_APPEARANCE_SELECTOR_TEST_TAG)
                            .semantics { stateDescription = selectorState },
                        enabled = !applying && editorReady,
                        onClick = { selectorExpanded = true },
                    ) {
                        Text(
                            text = selectedName,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(
                        expanded = selectorExpanded,
                        onDismissRequest = { selectorExpanded = false },
                    ) {
                        DropdownMenuItem(
                            modifier = Modifier
                                .heightIn(min = MINIMUM_SCOPE_CONTROL_SIZE)
                                .testTag(SCOPED_APPEARANCE_INHERITED_OPTION_TEST_TAG),
                            text = { Text(stringResource(R.string.appearance_scope_use_inherited)) },
                            onClick = {
                                selectorExpanded = false
                                savedDraft = draft.copy(selectedProfileId = null)
                                error = null
                            },
                        )
                        profiles.forEach { profile ->
                            DropdownMenuItem(
                                modifier = Modifier
                                    .heightIn(min = MINIMUM_SCOPE_CONTROL_SIZE)
                                    .testTag("$SCOPED_APPEARANCE_PROFILE_OPTION_PREFIX${profile.id}"),
                                text = {
                                    Text(
                                        text = profile.name,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                onClick = {
                                    selectorExpanded = false
                                    savedDraft = draft.copy(selectedProfileId = profile.id)
                                    error = null
                                },
                            )
                        }
                    }
                }
                if (!editorReady) {
                    Row(
                        modifier = Modifier
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag(SCOPED_APPEARANCE_LOADING_TEST_TAG),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.appearance_scope_profiles_loading))
                    }
                }
                ScopedAppearancePreview(previewProfile)
                if (kind != ScopedAppearanceKind.INBOX && onOpenWallpaper != null) {
                    OutlinedButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = MINIMUM_SCOPE_CONTROL_SIZE)
                            .testTag(SCOPED_APPEARANCE_WALLPAPER_TEST_TAG),
                        enabled = !applying && editorReady && !dirty,
                        onClick = onOpenWallpaper,
                    ) {
                        Text(stringResource(R.string.appearance_scope_wallpaper))
                    }
                    if (dirty) {
                        Text(
                            text = stringResource(R.string.appearance_scope_wallpaper_profile_dirty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MINIMUM_SCOPE_CONTROL_SIZE)
                        .testTag(SCOPED_APPEARANCE_RESET_TEST_TAG),
                    enabled = !applying && editorReady && draft.selectedProfileId != null,
                    onClick = {
                        savedDraft = draft.copy(selectedProfileId = null)
                        error = null
                    },
                ) {
                    Text(stringResource(R.string.appearance_scope_reset))
                }
                if (applying) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.appearance_scope_applying))
                    }
                }
                error?.let { currentError ->
                    Text(
                        text = stringResource(currentError.messageResource()),
                        modifier = Modifier
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag(SCOPED_APPEARANCE_ERROR_TEST_TAG),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier
                    .heightIn(min = MINIMUM_SCOPE_CONTROL_SIZE)
                    .testTag(SCOPED_APPEARANCE_APPLY_TEST_TAG),
                enabled = !applying &&
                    editorReady &&
                    dirty &&
                    !selectedProfileUnavailable,
                onClick = {
                    applying = true
                    error = null
                    scope.launch {
                        when (val result = onApply(draft.selectedProfileId, draft.expectedRevision)) {
                            ScopedAppearanceControllerResult.Success -> onDismiss()
                            is ScopedAppearanceControllerResult.Failed -> {
                                applying = false
                                error = result.error
                            }
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.appearance_apply))
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier
                    .heightIn(min = MINIMUM_SCOPE_CONTROL_SIZE)
                    .testTag(SCOPED_APPEARANCE_CANCEL_TEST_TAG),
                enabled = !applying,
                onClick = ::dismissIfIdle,
            ) {
                Text(stringResource(R.string.appearance_cancel))
            }
        },
    )
}

@Composable
private fun ScopedAppearancePreview(profile: AuroraMaterialProfile) {
    val previewDescription = stringResource(R.string.appearance_scope_preview_description)
    AuroraMaterialTheme(profile = profile) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SCOPED_APPEARANCE_PREVIEW_TEST_TAG)
                .semantics { contentDescription = previewDescription },
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.appearance_scope_preview_title),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(0.78f),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Text(
                        text = stringResource(R.string.appearance_scope_preview_incoming),
                        modifier = Modifier.padding(10.dp),
                    )
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.78f)
                        .align(Alignment.End),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = stringResource(R.string.appearance_scope_preview_outgoing),
                        modifier = Modifier.padding(10.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

private fun ScopedAppearanceKind.titleResource(): Int = when (this) {
    ScopedAppearanceKind.INBOX -> R.string.appearance_scope_inbox_title
    ScopedAppearanceKind.GLOBAL_THREADS -> R.string.appearance_scope_global_threads_title
    ScopedAppearanceKind.CONVERSATION -> R.string.appearance_scope_conversation_title
}

private fun ScopedAppearanceError.messageResource(): Int = when (this) {
    ScopedAppearanceError.SAVE_FAILED -> R.string.appearance_scope_save_failed
    ScopedAppearanceError.STALE_ASSIGNMENT -> R.string.appearance_scope_stale
    ScopedAppearanceError.PROFILE_MISSING -> R.string.appearance_scope_profile_missing
}

const val SCOPED_APPEARANCE_DIALOG_TEST_TAG: String = "aurora-scoped-appearance-dialog"
const val SCOPED_APPEARANCE_SELECTOR_TEST_TAG: String = "aurora-scoped-appearance-selector"
const val SCOPED_APPEARANCE_INHERITED_OPTION_TEST_TAG: String =
    "aurora-scoped-appearance-inherited-option"
const val SCOPED_APPEARANCE_PROFILE_OPTION_PREFIX: String = "aurora-scoped-appearance-profile-"
const val SCOPED_APPEARANCE_PREVIEW_TEST_TAG: String = "aurora-scoped-appearance-preview"
const val SCOPED_APPEARANCE_WALLPAPER_TEST_TAG: String = "aurora-scoped-appearance-wallpaper"
const val SCOPED_APPEARANCE_RESET_TEST_TAG: String = "aurora-scoped-appearance-reset"
const val SCOPED_APPEARANCE_APPLY_TEST_TAG: String = "aurora-scoped-appearance-apply"
const val SCOPED_APPEARANCE_CANCEL_TEST_TAG: String = "aurora-scoped-appearance-cancel"
const val SCOPED_APPEARANCE_ERROR_TEST_TAG: String = "aurora-scoped-appearance-error"
const val SCOPED_APPEARANCE_LOADING_TEST_TAG: String = "aurora-scoped-appearance-loading"
private const val MAXIMUM_PRIVATE_RESTORATION_KEY_CHARACTERS: Int = 256
private const val SCOPED_DRAFT_SCHEMA_VERSION: Int = 1
private const val SCOPED_DRAFT_SCHEMA_KEY: String = "schema"
private const val SCOPED_DRAFT_TARGET_KEY: String = "target"
private const val SCOPED_DRAFT_BASELINE_KEY: String = "baseline"
private const val SCOPED_DRAFT_REVISION_KEY: String = "revision"
private const val SCOPED_DRAFT_SELECTED_KEY: String = "selected"
private val MINIMUM_SCOPE_CONTROL_SIZE = 48.dp
