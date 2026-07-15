// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.aurorasms.app.R
import org.aurorasms.core.state.AppearanceProfileValues
import org.aurorasms.core.state.MAXIMUM_APPEARANCE_FOCAL_PERMILL
import org.aurorasms.core.state.MAXIMUM_APPEARANCE_WALLPAPER_DIM_PERMILL
import org.aurorasms.core.state.MINIMUM_APPEARANCE_FOCAL_PERMILL
import org.aurorasms.core.state.MINIMUM_APPEARANCE_WALLPAPER_DIM_PERMILL

/**
 * The only state restored for this editor is a private target tag and bounded numeric controls.
 * Picker capabilities, URI text, media tokens, paths, names, previews, and in-flight work are never
 * placed in SavedState.
 */
@Immutable
internal data class ScopedWallpaperDraftState(
    val privateRestorationKey: String,
    val expectedRevision: Long?,
    val dimPermill: Int,
    val focalXPermill: Int,
    val focalYPermill: Int,
) {
    init {
        require(privateRestorationKey.isNotBlank()) { "A wallpaper target key cannot be blank" }
        require(privateRestorationKey.length <= MAXIMUM_PRIVATE_WALLPAPER_KEY_CHARACTERS) {
            "A wallpaper target key is too large"
        }
        require(expectedRevision == null || expectedRevision > 0L)
        require(
            dimPermill in
                MINIMUM_APPEARANCE_WALLPAPER_DIM_PERMILL..
                MAXIMUM_APPEARANCE_WALLPAPER_DIM_PERMILL,
        )
        require(
            focalXPermill in
                MINIMUM_APPEARANCE_FOCAL_PERMILL..MAXIMUM_APPEARANCE_FOCAL_PERMILL,
        )
        require(
            focalYPermill in
                MINIMUM_APPEARANCE_FOCAL_PERMILL..MAXIMUM_APPEARANCE_FOCAL_PERMILL,
        )
    }

    fun forTargetOr(durable: ScopedWallpaperDraftState): ScopedWallpaperDraftState =
        if (privateRestorationKey == durable.privateRestorationKey) this else durable

    fun matchesTarget(privateKey: String): Boolean = privateRestorationKey == privateKey

    fun hasNumericChangesFrom(other: ScopedWallpaperDraftState): Boolean =
        dimPermill != other.dimPermill ||
            focalXPermill != other.focalXPermill ||
            focalYPermill != other.focalYPermill

    override fun toString(): String =
        "ScopedWallpaperDraftState(hasExpectedRevision=${expectedRevision != null}, " +
            "dimPermill=$dimPermill, focalXPermill=$focalXPermill, " +
            "focalYPermill=$focalYPermill, target=REDACTED)"
}

internal val ScopedWallpaperDraftStateSaver: Saver<ScopedWallpaperDraftState, Bundle> = Saver(
    save = { state ->
        Bundle().apply {
            putInt(WALLPAPER_DRAFT_SCHEMA_KEY, WALLPAPER_DRAFT_SCHEMA_VERSION)
            putString(WALLPAPER_DRAFT_TARGET_KEY, state.privateRestorationKey)
            state.expectedRevision?.let { putLong(WALLPAPER_DRAFT_REVISION_KEY, it) }
            putInt(WALLPAPER_DRAFT_DIM_KEY, state.dimPermill)
            putInt(WALLPAPER_DRAFT_FOCAL_X_KEY, state.focalXPermill)
            putInt(WALLPAPER_DRAFT_FOCAL_Y_KEY, state.focalYPermill)
        }
    },
    restore = { bundle -> bundle.toScopedWallpaperDraftOrNull() },
)

private fun Bundle.toScopedWallpaperDraftOrNull(): ScopedWallpaperDraftState? = try {
    if (getInt(WALLPAPER_DRAFT_SCHEMA_KEY, -1) != WALLPAPER_DRAFT_SCHEMA_VERSION) return null
    ScopedWallpaperDraftState(
        privateRestorationKey = getString(WALLPAPER_DRAFT_TARGET_KEY) ?: return null,
        expectedRevision = if (containsKey(WALLPAPER_DRAFT_REVISION_KEY)) {
            getLong(WALLPAPER_DRAFT_REVISION_KEY).takeIf { it > 0L }
                ?: throw IllegalArgumentException("Invalid wallpaper revision")
        } else {
            null
        },
        dimPermill = getInt(WALLPAPER_DRAFT_DIM_KEY, Int.MIN_VALUE),
        focalXPermill = getInt(WALLPAPER_DRAFT_FOCAL_X_KEY, Int.MIN_VALUE),
        focalYPermill = getInt(WALLPAPER_DRAFT_FOCAL_Y_KEY, Int.MIN_VALUE),
    )
} catch (_: IllegalArgumentException) {
    null
} catch (_: ClassCastException) {
    null
}

/**
 * Route-local editor for one already-authorized global-thread or verified-conversation target.
 * The caller owns target revalidation immediately before every mutation.
 */
@Composable
internal fun ScopedWallpaperDialog(
    privateRestorationKey: String,
    currentAssignment: AppWallpaperAssignment?,
    assignmentSnapshotReady: Boolean,
    controller: WallpaperController,
    onApply: suspend (
        source: Uri?,
        dimPermill: Int,
        focalXPermill: Int,
        focalYPermill: Int,
        expectedRevision: Long?,
    ) -> WallpaperApplyControllerResult,
    onReset: suspend (expectedRevision: Long?) -> WallpaperApplyControllerResult,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    val durableDraft = currentAssignment.toDraft(privateRestorationKey)
    var savedDraft by rememberSaveable(stateSaver = ScopedWallpaperDraftStateSaver) {
        mutableStateOf(durableDraft)
    }
    val draft = savedDraft.forTargetOr(durableDraft)
    var selectedInspection by remember(privateRestorationKey) {
        mutableStateOf<WallpaperInspectionResult.Ready?>(null)
    }
    var existingLoaded by remember(privateRestorationKey) { mutableStateOf<LoadedWallpaper?>(null) }
    val selectedOwner = remember(privateRestorationKey) {
        WallpaperResourceOwner<WallpaperInspectionResult.Ready>(WallpaperInspectionResult.Ready::release)
    }
    val existingOwner = remember(privateRestorationKey) {
        WallpaperResourceOwner<LoadedWallpaper>(LoadedWallpaper::release)
    }
    var existingPreviewLoading by remember(privateRestorationKey) { mutableStateOf(false) }
    var existingPreviewUnavailable by remember(privateRestorationKey) { mutableStateOf(false) }
    var inspecting by remember(privateRestorationKey) { mutableStateOf(false) }
    var applying by remember(privateRestorationKey) { mutableStateOf(false) }
    var resetting by remember(privateRestorationKey) { mutableStateOf(false) }
    var error by remember(privateRestorationKey) { mutableStateOf<WallpaperControllerError?>(null) }
    val latestTarget by rememberUpdatedState(privateRestorationKey)
    val scope = rememberCoroutineScope()
    val selectedSource = selectedInspection?.source
    val selectedPreview = selectedInspection?.preview
    val existingPreview = existingLoaded?.image
    val numericDirty = draft.hasNumericChangesFrom(durableDraft)
    val busy = inspecting || applying || resetting

    DisposableEffect(selectedOwner, existingOwner) {
        onDispose {
            selectedOwner.dispose()
            existingOwner.dispose()
        }
    }

    LaunchedEffect(
        privateRestorationKey,
        currentAssignment?.revision,
        currentAssignment?.dimPermill,
        currentAssignment?.focalXPermill,
        currentAssignment?.focalYPermill,
        assignmentSnapshotReady,
    ) {
        when {
            !savedDraft.matchesTarget(privateRestorationKey) -> savedDraft = durableDraft
            !assignmentSnapshotReady -> Unit
            !numericDirty && selectedSource == null && !applying && !resetting -> {
                savedDraft = durableDraft
                error = null
            }
        }
    }

    LaunchedEffect(
        privateRestorationKey,
        currentAssignment?.mediaId,
        currentAssignment?.revision,
        assignmentSnapshotReady,
        controller,
        inspecting,
        selectedSource,
    ) {
        existingOwner.clear()
        existingLoaded = null
        existingPreviewUnavailable = false
        if (inspecting || selectedSource != null) {
            existingPreviewLoading = false
            return@LaunchedEffect
        }
        val assignment = currentAssignment
        if (!assignmentSnapshotReady || assignment == null) {
            existingPreviewLoading = false
            return@LaunchedEffect
        }
        existingPreviewLoading = true
        var acquired: LoadedWallpaper? = null
        try {
            acquired = controller.loadFirstAvailable(listOf(assignment), preview = true)
            currentCoroutineContext().ensureActive()
            val accepted = acquired?.takeIf { it.assignment == assignment }
            if (accepted != null) {
                val retained = existingOwner.replace(accepted)
                acquired = null
                if (!retained) return@LaunchedEffect
                existingLoaded = accepted
            }
            existingPreviewUnavailable = accepted == null
            existingPreviewLoading = false
        } finally {
            acquired?.release()
        }
    }

    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { source ->
        if (source != null && !busy) {
            val requestedTarget = privateRestorationKey
            selectedOwner.clear()
            existingOwner.clear()
            selectedInspection = null
            existingLoaded = null
            existingPreviewLoading = false
            existingPreviewUnavailable = false
            inspecting = true
            error = null
            scope.launch {
                when (val result = controller.inspect(source)) {
                    is WallpaperInspectionControllerResult.Ready -> {
                        val selection = result.selection
                        var acquired: WallpaperInspectionResult.Ready? = selection
                        try {
                            currentCoroutineContext().ensureActive()
                            if (latestTarget != requestedTarget) return@launch
                            inspecting = false
                            if (selection.source == source) {
                                val retained = selectedOwner.replace(selection)
                                acquired = null
                                if (retained) selectedInspection = selection
                            } else {
                                error = WallpaperControllerError.SOURCE_UNAVAILABLE
                            }
                        } finally {
                            acquired?.release()
                        }
                    }
                    is WallpaperInspectionControllerResult.Failed -> {
                        currentCoroutineContext().ensureActive()
                        if (latestTarget != requestedTarget) return@launch
                        inspecting = false
                        error = result.error
                    }
                }
            }
        }
    }

    val preview = selectedPreview ?: existingPreview
    val editorReady = assignmentSnapshotReady && !existingPreviewLoading
    val previewState = when {
        inspecting -> stringResource(R.string.wallpaper_inspecting)
        selectedPreview != null -> stringResource(R.string.wallpaper_selected_image)
        existingPreviewLoading || !assignmentSnapshotReady ->
            stringResource(R.string.wallpaper_loading_assignment)
        existingPreviewUnavailable -> stringResource(R.string.wallpaper_saved_image_unavailable)
        existingPreview != null -> stringResource(R.string.wallpaper_current_image)
        else -> stringResource(R.string.wallpaper_no_image)
    }
    val controlsEnabled = editorReady && preview != null && !busy
    val canApply = controlsEnabled &&
        (selectedSource != null || currentAssignment != null) &&
        (selectedSource != null || numericDirty)
    val previewStateDescription = stringResource(
        R.string.wallpaper_preview_state,
        draft.focalXPermill.toPercent(),
        draft.focalYPermill.toPercent(),
        draft.dimPermill.toPercent(),
    )

    fun dismissIfIdle() {
        if (!busy) onDismiss()
    }

    fun backIfIdle() {
        if (!busy) onBack()
    }

    AlertDialog(
        modifier = Modifier
            .semantics { testTagsAsResourceId = true }
            .testTag(SCOPED_WALLPAPER_DIALOG_TEST_TAG),
        onDismissRequest = ::dismissIfIdle,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.wallpaper_title))
                TextButton(
                    modifier = Modifier
                        .heightIn(min = MINIMUM_WALLPAPER_CONTROL_SIZE)
                        .testTag(SCOPED_WALLPAPER_BACK_TEST_TAG),
                    enabled = !busy,
                    onClick = ::backIfIdle,
                ) {
                    Text(stringResource(R.string.wallpaper_back))
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = MAXIMUM_WALLPAPER_DIALOG_BODY_HEIGHT)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.wallpaper_explanation))
                OutlinedButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MINIMUM_WALLPAPER_CONTROL_SIZE)
                        .testTag(SCOPED_WALLPAPER_PICK_TEST_TAG)
                        .semantics { stateDescription = previewState },
                    enabled = assignmentSnapshotReady && !busy,
                    onClick = {
                        picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                    },
                ) {
                    Text(
                        stringResource(
                            if (preview == null) {
                                R.string.wallpaper_choose_image
                            } else {
                                R.string.wallpaper_change_image
                            },
                        ),
                    )
                }

                WallpaperPreview(
                    image = preview,
                    placeholder = previewState,
                    state = previewStateDescription,
                    dimPermill = draft.dimPermill,
                    focalXPermill = draft.focalXPermill,
                    focalYPermill = draft.focalYPermill,
                )

                WallpaperSlider(
                    label = stringResource(
                        R.string.wallpaper_focal_horizontal,
                        draft.focalXPermill.toPercent(),
                    ),
                    state = stringResource(
                        R.string.wallpaper_focal_horizontal_state,
                        draft.focalXPermill.toPercent(),
                    ),
                    value = draft.focalXPermill,
                    range = MINIMUM_APPEARANCE_FOCAL_PERMILL..
                        MAXIMUM_APPEARANCE_FOCAL_PERMILL,
                    enabled = controlsEnabled,
                    testTag = SCOPED_WALLPAPER_FOCAL_X_TEST_TAG,
                    onValueChange = {
                        savedDraft = draft.copy(focalXPermill = it)
                        error = null
                    },
                )
                WallpaperSlider(
                    label = stringResource(
                        R.string.wallpaper_focal_vertical,
                        draft.focalYPermill.toPercent(),
                    ),
                    state = stringResource(
                        R.string.wallpaper_focal_vertical_state,
                        draft.focalYPermill.toPercent(),
                    ),
                    value = draft.focalYPermill,
                    range = MINIMUM_APPEARANCE_FOCAL_PERMILL..
                        MAXIMUM_APPEARANCE_FOCAL_PERMILL,
                    enabled = controlsEnabled,
                    testTag = SCOPED_WALLPAPER_FOCAL_Y_TEST_TAG,
                    onValueChange = {
                        savedDraft = draft.copy(focalYPermill = it)
                        error = null
                    },
                )
                WallpaperSlider(
                    label = stringResource(
                        R.string.wallpaper_dim,
                        draft.dimPermill.toPercent(),
                    ),
                    state = stringResource(
                        R.string.wallpaper_dim_state,
                        draft.dimPermill.toPercent(),
                    ),
                    value = draft.dimPermill,
                    range = MINIMUM_APPEARANCE_WALLPAPER_DIM_PERMILL..
                        MAXIMUM_APPEARANCE_WALLPAPER_DIM_PERMILL,
                    enabled = controlsEnabled,
                    testTag = SCOPED_WALLPAPER_DIM_TEST_TAG,
                    onValueChange = {
                        savedDraft = draft.copy(dimPermill = it)
                        error = null
                    },
                )

                OutlinedButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MINIMUM_WALLPAPER_CONTROL_SIZE)
                        .testTag(SCOPED_WALLPAPER_RESET_TEST_TAG),
                    enabled = assignmentSnapshotReady && currentAssignment != null && !busy,
                    onClick = {
                        val requestedTarget = privateRestorationKey
                        resetting = true
                        error = null
                        scope.launch {
                            val result = onReset(draft.expectedRevision)
                            if (latestTarget != requestedTarget) return@launch
                            resetting = false
                            when (result) {
                                WallpaperApplyControllerResult.Success -> onBack()
                                is WallpaperApplyControllerResult.Failed -> error = result.error
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.wallpaper_reset))
                }

                if (!assignmentSnapshotReady || existingPreviewLoading || busy) {
                    Row(
                        modifier = Modifier
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag(SCOPED_WALLPAPER_LOADING_TEST_TAG),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(
                            stringResource(
                                when {
                                    applying -> R.string.wallpaper_applying
                                    resetting -> R.string.wallpaper_resetting
                                    inspecting -> R.string.wallpaper_inspecting
                                    else -> R.string.wallpaper_loading_assignment
                                },
                            ),
                        )
                    }
                }
                if (existingPreviewUnavailable && selectedPreview == null) {
                    Text(
                        text = stringResource(R.string.wallpaper_saved_image_unavailable),
                        modifier = Modifier
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag(SCOPED_WALLPAPER_UNAVAILABLE_TEST_TAG),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                error?.let { currentError ->
                    Text(
                        text = stringResource(currentError.messageResource()),
                        modifier = Modifier
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag(SCOPED_WALLPAPER_ERROR_TEST_TAG),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier
                    .heightIn(min = MINIMUM_WALLPAPER_CONTROL_SIZE)
                    .testTag(SCOPED_WALLPAPER_APPLY_TEST_TAG),
                enabled = canApply,
                onClick = {
                    val requestedTarget = privateRestorationKey
                    applying = true
                    error = null
                    scope.launch {
                        val result = onApply(
                            selectedSource,
                            draft.dimPermill,
                            draft.focalXPermill,
                            draft.focalYPermill,
                            draft.expectedRevision,
                        )
                        if (latestTarget != requestedTarget) return@launch
                        applying = false
                        when (result) {
                            WallpaperApplyControllerResult.Success -> onBack()
                            is WallpaperApplyControllerResult.Failed -> error = result.error
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.wallpaper_apply))
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier
                    .heightIn(min = MINIMUM_WALLPAPER_CONTROL_SIZE)
                    .testTag(SCOPED_WALLPAPER_CANCEL_TEST_TAG),
                enabled = !busy,
                onClick = ::dismissIfIdle,
            ) {
                Text(stringResource(R.string.wallpaper_cancel))
            }
        },
    )
}

@Composable
private fun WallpaperPreview(
    image: ImageBitmap?,
    placeholder: String,
    state: String,
    dimPermill: Int,
    focalXPermill: Int,
    focalYPermill: Int,
) {
    val description = stringResource(R.string.wallpaper_preview_description)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(WALLPAPER_PREVIEW_ASPECT_RATIO)
            .testTag(SCOPED_WALLPAPER_PREVIEW_TEST_TAG)
            .semantics {
                contentDescription = description
                stateDescription = state
            },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (image == null) {
                Text(
                    text = placeholder,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                WallpaperBitmapSurface(
                    image = image,
                    dimPermill = dimPermill,
                    focalXPermill = focalXPermill,
                    focalYPermill = focalYPermill,
                    modifier = Modifier.fillMaxSize(),
                )
                FocalPointMarker(
                    focalXPermill = focalXPermill,
                    focalYPermill = focalYPermill,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun FocalPointMarker(
    focalXPermill: Int,
    focalYPermill: Int,
    modifier: Modifier = Modifier,
) {
    val markerRadius = 7.dp
    Canvas(modifier = modifier) {
        val center = Offset(
            x = size.width * focalXPermill / MAXIMUM_APPEARANCE_FOCAL_PERMILL,
            y = size.height * focalYPermill / MAXIMUM_APPEARANCE_FOCAL_PERMILL,
        )
        drawCircle(
            color = Color.Black,
            radius = markerRadius.toPx(),
            center = center,
        )
        drawCircle(
            color = Color.White,
            radius = (markerRadius - 2.dp).toPx(),
            center = center,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

@Composable
private fun WallpaperSlider(
    label: String,
    state: String,
    value: Int,
    range: IntRange,
    enabled: Boolean,
    testTag: String,
    onValueChange: (Int) -> Unit,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { candidate ->
                onValueChange(candidate.roundToInt().coerceIn(range))
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MINIMUM_WALLPAPER_CONTROL_SIZE)
                .testTag(testTag)
                .semantics { stateDescription = state },
            enabled = enabled,
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

private fun AppWallpaperAssignment?.toDraft(privateRestorationKey: String): ScopedWallpaperDraftState =
    ScopedWallpaperDraftState(
        privateRestorationKey = privateRestorationKey,
        expectedRevision = this?.revision,
        dimPermill = this?.dimPermill ?: AppearanceProfileValues.CanonicalDefault.wallpaperDimPermill,
        focalXPermill = this?.focalXPermill ?: AppearanceProfileValues.CanonicalDefault.focalXPermill,
        focalYPermill = this?.focalYPermill ?: AppearanceProfileValues.CanonicalDefault.focalYPermill,
    )

private fun Int.toPercent(): Int = (this / 10f).roundToInt()

private fun WallpaperControllerError.messageResource(): Int = when (this) {
    WallpaperControllerError.SOURCE_UNAVAILABLE -> R.string.wallpaper_error_source_unavailable
    WallpaperControllerError.UNSUPPORTED_SOURCE -> R.string.wallpaper_error_unsupported_source
    WallpaperControllerError.SOURCE_TOO_LARGE -> R.string.wallpaper_error_source_too_large
    WallpaperControllerError.QUOTA_EXCEEDED -> R.string.wallpaper_error_quota_exceeded
    WallpaperControllerError.STALE_ASSIGNMENT -> R.string.wallpaper_error_stale_assignment
    WallpaperControllerError.SAVE_FAILED -> R.string.wallpaper_error_save_failed
}

const val SCOPED_WALLPAPER_DIALOG_TEST_TAG: String = "aurora-scoped-wallpaper-dialog"
const val SCOPED_WALLPAPER_BACK_TEST_TAG: String = "aurora-scoped-wallpaper-back"
const val SCOPED_WALLPAPER_PICK_TEST_TAG: String = "aurora-scoped-wallpaper-pick"
const val SCOPED_WALLPAPER_PREVIEW_TEST_TAG: String = "aurora-scoped-wallpaper-preview"
const val SCOPED_WALLPAPER_FOCAL_X_TEST_TAG: String = "aurora-scoped-wallpaper-focal-x"
const val SCOPED_WALLPAPER_FOCAL_Y_TEST_TAG: String = "aurora-scoped-wallpaper-focal-y"
const val SCOPED_WALLPAPER_DIM_TEST_TAG: String = "aurora-scoped-wallpaper-dim"
const val SCOPED_WALLPAPER_RESET_TEST_TAG: String = "aurora-scoped-wallpaper-reset"
const val SCOPED_WALLPAPER_APPLY_TEST_TAG: String = "aurora-scoped-wallpaper-apply"
const val SCOPED_WALLPAPER_CANCEL_TEST_TAG: String = "aurora-scoped-wallpaper-cancel"
const val SCOPED_WALLPAPER_LOADING_TEST_TAG: String = "aurora-scoped-wallpaper-loading"
const val SCOPED_WALLPAPER_UNAVAILABLE_TEST_TAG: String = "aurora-scoped-wallpaper-unavailable"
const val SCOPED_WALLPAPER_ERROR_TEST_TAG: String = "aurora-scoped-wallpaper-error"

private const val MAXIMUM_PRIVATE_WALLPAPER_KEY_CHARACTERS: Int = 256
private const val WALLPAPER_DRAFT_SCHEMA_VERSION: Int = 1
private const val WALLPAPER_DRAFT_SCHEMA_KEY: String = "schema"
private const val WALLPAPER_DRAFT_TARGET_KEY: String = "target"
private const val WALLPAPER_DRAFT_REVISION_KEY: String = "revision"
private const val WALLPAPER_DRAFT_DIM_KEY: String = "dim"
private const val WALLPAPER_DRAFT_FOCAL_X_KEY: String = "focal_x"
private const val WALLPAPER_DRAFT_FOCAL_Y_KEY: String = "focal_y"
private const val WALLPAPER_PREVIEW_ASPECT_RATIO: Float = 4f / 3f
private val MINIMUM_WALLPAPER_CONTROL_SIZE = 48.dp
private val MAXIMUM_WALLPAPER_DIALOG_BODY_HEIGHT = 560.dp
