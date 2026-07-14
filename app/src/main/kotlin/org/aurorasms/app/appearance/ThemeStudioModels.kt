// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance

import androidx.compose.runtime.Immutable
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import org.aurorasms.core.designsystem.AuroraAvatarMask
import org.aurorasms.core.designsystem.AuroraBubbleGeometry
import org.aurorasms.core.designsystem.AuroraMaterialProfile
import org.aurorasms.core.designsystem.AuroraNavigationStyle
import org.aurorasms.core.designsystem.AuroraPalette
import org.aurorasms.core.designsystem.AuroraRowDensity

/** A bounded presentation snapshot. Persistence models are mapped at the app boundary. */
@Immutable
data class ThemeStudioSavedProfile(
    val id: Long,
    val revision: Long,
    val name: String,
    val profile: AuroraMaterialProfile,
    val deletable: Boolean,
) {
    init {
        if (deletable) {
            require(id > 0L) { "A durable Theme Studio profile ID must be positive" }
            require(revision > 0L) { "A durable Theme Studio profile revision must be positive" }
        } else {
            require(id == 0L) { "The canonical Theme Studio profile ID must be zero" }
            require(revision == 0L) { "The canonical Theme Studio revision must be zero" }
            require(profile == AuroraMaterialProfile.Default) {
                "The canonical Theme Studio profile must use the code-owned default"
            }
        }
        require(isValidThemeStudioProfileName(name)) { "A Theme Studio profile name is invalid" }
        require(profile.navigationStyle == AuroraNavigationStyle.CLASSIC) {
            "Theme Studio cannot expose an unverified navigation style"
        }
    }

    override fun toString(): String =
        "ThemeStudioSavedProfile(id=$id, revision=$revision, deletable=$deletable, name=REDACTED)"
}

@Immutable
data class ThemeStudioEditorState(
    val savedProfiles: List<ThemeStudioSavedProfile>,
    val activeProfileId: Long,
    val selectedProfileId: Long,
    val name: String,
    val draftProfile: AuroraMaterialProfile,
    val newCopy: Boolean = false,
    val resetStaged: Boolean = false,
    val operation: ThemeStudioOperation = ThemeStudioOperation.IDLE,
    val error: ThemeStudioError? = null,
    val deleteConfirmationProfileId: Long? = null,
) {
    init {
        require(savedProfiles.isNotEmpty()) { "Theme Studio requires a canonical profile" }
        require(savedProfiles.size <= MAXIMUM_THEME_STUDIO_SAVED_PROFILES) {
            "Theme Studio received too many profiles"
        }
        require(savedProfiles.map(ThemeStudioSavedProfile::id).distinct().size == savedProfiles.size) {
            "Theme Studio profile IDs must be unique"
        }
        require(savedProfiles.count { !it.deletable } == 1) {
            "Theme Studio requires exactly one canonical profile"
        }
        val canonical = savedProfiles.single { !it.deletable }
        require(savedProfiles.any { it.id == activeProfileId }) {
            "The active Theme Studio profile is missing"
        }
        require(savedProfiles.any { it.id == selectedProfileId }) {
            "The selected Theme Studio profile is missing"
        }
        require(isBoundedThemeStudioProfileNameInput(name)) {
            "The edited Theme Studio profile name is too long"
        }
        require(draftProfile.navigationStyle == AuroraNavigationStyle.CLASSIC) {
            "Theme Studio cannot preview an unverified navigation style"
        }
        require(!newCopy || !resetStaged) {
            "A new Theme Studio copy cannot also stage a reset"
        }
        if (!newCopy && selectedProfileId == canonical.id) {
            require(name == canonical.name && draftProfile == canonical.profile) {
                "The canonical Theme Studio profile cannot contain unstaged edits"
            }
            require(resetStaged == (activeProfileId != canonical.id)) {
                "Canonical selection must match the active-profile reset state"
            }
        }
        if (resetStaged) {
            require(selectedProfileId == canonical.id && activeProfileId != canonical.id) {
                "A Theme Studio reset requires a noncanonical active profile"
            }
            require(name == canonical.name && draftProfile == canonical.profile) {
                "A Theme Studio reset must stage the canonical profile"
            }
        }
        deleteConfirmationProfileId?.let { profileId ->
            require(savedProfiles.any { it.id == profileId && it.deletable }) {
                "Only a deletable Theme Studio profile may request confirmation"
            }
        }
    }

    val activeProfile: ThemeStudioSavedProfile
        get() = checkNotNull(savedProfiles.firstOrNull { it.id == activeProfileId })

    val selectedProfile: ThemeStudioSavedProfile
        get() = checkNotNull(savedProfiles.firstOrNull { it.id == selectedProfileId })

    val normalizedName: String
        get() = normalizeThemeStudioProfileName(name)

    val nameValid: Boolean
        get() = isValidThemeStudioProfileName(normalizedName)

    val interactive: Boolean
        get() = operation == ThemeStudioOperation.IDLE

    val canCreateCopy: Boolean
        get() = interactive && savedProfiles.size < MAXIMUM_THEME_STUDIO_SAVED_PROFILES

    val dirty: Boolean
        get() = newCopy ||
            resetStaged ||
            selectedProfileId != activeProfileId ||
            normalizedName != selectedProfile.name ||
            draftProfile != selectedProfile.profile

    val canApply: Boolean
        get() = interactive && nameValid && dirty

    fun applyRequest(): ThemeStudioApplyRequest? {
        if (!canApply) return null
        val updateTarget = selectedProfile.takeIf { it.deletable && !newCopy }
        return ThemeStudioApplyRequest(
            profileId = updateTarget?.id,
            expectedRevision = updateTarget?.revision,
            name = normalizedName,
            profile = draftProfile,
            resetToDefault = resetStaged,
        )
    }

    override fun toString(): String =
        "ThemeStudioEditorState(savedProfiles=${savedProfiles.size}, activeProfileId=$activeProfileId, " +
            "selectedProfileId=$selectedProfileId, newCopy=$newCopy, resetStaged=$resetStaged, " +
            "operation=$operation, error=$error, name=REDACTED)"

    companion object {
        fun create(
            savedProfiles: List<ThemeStudioSavedProfile>,
            activeProfileId: Long,
        ): ThemeStudioEditorState {
            require(savedProfiles.size <= MAXIMUM_THEME_STUDIO_SAVED_PROFILES)
            val active = checkNotNull(savedProfiles.firstOrNull { it.id == activeProfileId }) {
                "The active Theme Studio profile is missing"
            }
            return ThemeStudioEditorState(
                savedProfiles = savedProfiles.toList(),
                activeProfileId = activeProfileId,
                selectedProfileId = activeProfileId,
                name = active.name,
                draftProfile = active.profile,
            )
        }
    }
}

@Immutable
data class ThemeStudioApplyRequest(
    val profileId: Long?,
    val expectedRevision: Long?,
    val name: String,
    val profile: AuroraMaterialProfile,
    val resetToDefault: Boolean,
) {
    init {
        require((profileId == null) == (expectedRevision == null)) {
            "A Theme Studio update target and revision must be present together"
        }
        require(profileId == null || profileId > 0L)
        require(expectedRevision == null || expectedRevision > 0L)
        require(isValidThemeStudioProfileName(name))
        require(profile.navigationStyle == AuroraNavigationStyle.CLASSIC) {
            "Theme Studio cannot apply an unverified navigation style"
        }
        require(!resetToDefault || profileId == null && profile == AuroraMaterialProfile.Default) {
            "A Theme Studio reset must target the code-owned default"
        }
    }

    override fun toString(): String =
        "ThemeStudioApplyRequest(profileId=$profileId, expectedRevision=$expectedRevision, " +
            "resetToDefault=$resetToDefault, name=REDACTED)"
}

enum class ThemeStudioOperation {
    IDLE,
    APPLYING,
    DELETING,
}

enum class ThemeStudioError {
    SAVE_FAILED,
    DELETE_FAILED,
    STALE_PROFILE,
    DUPLICATE_NAME,
    PROFILE_LIMIT,
}

sealed interface ThemeStudioAction {
    data class SelectProfile(val profileId: Long) : ThemeStudioAction
    data object NewCopy : ThemeStudioAction
    data class ChangeName(val name: String) : ThemeStudioAction {
        override fun toString(): String = "ThemeStudioAction.ChangeName(REDACTED)"
    }
    data class ChangePalette(val palette: AuroraPalette) : ThemeStudioAction
    data class ChangeHue(val hueDegrees: Int) : ThemeStudioAction
    data class ChangeDensity(val density: AuroraRowDensity) : ThemeStudioAction
    data class ChangeAvatarMask(val mask: AuroraAvatarMask) : ThemeStudioAction
    data class ChangeBubbleGeometry(val geometry: AuroraBubbleGeometry) : ThemeStudioAction
    data class ChangeHighContrast(val enabled: Boolean) : ThemeStudioAction
    data object Reset : ThemeStudioAction
    data class SetOperation(val operation: ThemeStudioOperation) : ThemeStudioAction
    data class SetError(val error: ThemeStudioError?) : ThemeStudioAction
    data object RequestDelete : ThemeStudioAction
    data object DismissDelete : ThemeStudioAction
}

fun reduceThemeStudio(
    state: ThemeStudioEditorState,
    action: ThemeStudioAction,
): ThemeStudioEditorState {
    if (!state.interactive && action !is ThemeStudioAction.SetOperation && action !is ThemeStudioAction.SetError) {
        return state
    }
    return when (action) {
        is ThemeStudioAction.SelectProfile -> {
            val selected = state.savedProfiles.firstOrNull { it.id == action.profileId } ?: return state
            state.copy(
                selectedProfileId = selected.id,
                name = selected.name,
                draftProfile = selected.profile,
                newCopy = false,
                resetStaged = !selected.deletable && selected.id != state.activeProfileId,
                error = null,
                deleteConfirmationProfileId = null,
            )
        }
        ThemeStudioAction.NewCopy -> if (!state.canCreateCopy) {
            state
        } else {
            state.copy(
                name = "",
                newCopy = true,
                resetStaged = false,
                error = null,
                deleteConfirmationProfileId = null,
            )
        }
        is ThemeStudioAction.ChangeName -> state.copy(
            name = sanitizeThemeStudioProfileNameInput(action.name),
            newCopy = state.newCopy || !state.selectedProfile.deletable,
            resetStaged = false,
            error = null,
        )
        is ThemeStudioAction.ChangePalette -> state.editProfile { copy(palette = action.palette) }
        is ThemeStudioAction.ChangeHue -> {
            if (state.draftProfile.palette == AuroraPalette.SYSTEM_DYNAMIC) {
                state
            } else {
                state.editProfile { copy(hueDegrees = action.hueDegrees.coerceIn(0, 359)) }
            }
        }
        is ThemeStudioAction.ChangeDensity -> state.editProfile { copy(rowDensity = action.density) }
        is ThemeStudioAction.ChangeAvatarMask -> state.editProfile { copy(avatarMask = action.mask) }
        is ThemeStudioAction.ChangeBubbleGeometry -> state.editProfile {
            copy(bubbleGeometry = action.geometry)
        }
        is ThemeStudioAction.ChangeHighContrast -> state.editProfile {
            copy(highContrast = action.enabled)
        }
        ThemeStudioAction.Reset -> {
            val canonical = state.savedProfiles.single { !it.deletable }
            state.copy(
                selectedProfileId = canonical.id,
                name = canonical.name,
                draftProfile = canonical.profile,
                newCopy = false,
                resetStaged = canonical.id != state.activeProfileId,
                error = null,
                deleteConfirmationProfileId = null,
            )
        }
        is ThemeStudioAction.SetOperation -> state.copy(
            operation = action.operation,
            deleteConfirmationProfileId = null,
        )
        is ThemeStudioAction.SetError -> state.copy(error = action.error)
        ThemeStudioAction.RequestDelete -> {
            val selected = state.selectedProfile
            if (state.newCopy || !selected.deletable) state else {
                state.copy(deleteConfirmationProfileId = selected.id)
            }
        }
        ThemeStudioAction.DismissDelete -> state.copy(deleteConfirmationProfileId = null)
    }
}

fun isValidThemeStudioProfileName(name: String): Boolean {
    val normalized = normalizeThemeStudioProfileName(name)
    return normalized.isNotBlank() &&
        normalized.codePointCount(0, normalized.length) <= MAXIMUM_THEME_STUDIO_PROFILE_NAME_CHARACTERS &&
        normalized.toByteArray(StandardCharsets.UTF_8).size <= MAXIMUM_THEME_STUDIO_PROFILE_NAME_UTF8_BYTES &&
        normalized.codePoints().noneMatch(::isForbiddenThemeStudioNameCodePoint)
}

fun normalizeThemeStudioProfileName(name: String): String =
    Normalizer.normalize(name.trim(), Normalizer.Form.NFC)

private fun sanitizeThemeStudioProfileNameInput(input: String): String {
    val boundedInput = StringBuilder()
    input.codePoints()
        .limit(MAXIMUM_THEME_STUDIO_RAW_NAME_CODE_POINTS.toLong())
        .forEachOrdered(boundedInput::appendCodePoint)
    val normalized = Normalizer.normalize(boundedInput, Normalizer.Form.NFC)
    val result = StringBuilder()
    var count = 0
    normalized.codePoints().forEachOrdered { codePoint ->
        if (count >= MAXIMUM_THEME_STUDIO_PROFILE_NAME_CHARACTERS) return@forEachOrdered
        if (isForbiddenThemeStudioNameCodePoint(codePoint)) return@forEachOrdered
        val candidate = result.toString() + String(Character.toChars(codePoint))
        if (candidate.toByteArray(StandardCharsets.UTF_8).size > MAXIMUM_THEME_STUDIO_PROFILE_NAME_UTF8_BYTES) {
            return@forEachOrdered
        }
        result.appendCodePoint(codePoint)
        count += 1
    }
    return result.toString()
}

internal fun isBoundedThemeStudioProfileNameInput(name: String): Boolean =
    name.codePointCount(0, name.length) <= MAXIMUM_THEME_STUDIO_PROFILE_NAME_CHARACTERS &&
        name.toByteArray(StandardCharsets.UTF_8).size <= MAXIMUM_THEME_STUDIO_PROFILE_NAME_UTF8_BYTES &&
        name.codePoints().noneMatch(::isForbiddenThemeStudioNameCodePoint)

private fun isForbiddenThemeStudioNameCodePoint(codePoint: Int): Boolean =
    Character.isISOControl(codePoint) ||
        codePoint in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code ||
        codePoint == ARABIC_LETTER_MARK ||
        codePoint == LEFT_TO_RIGHT_MARK ||
        codePoint == RIGHT_TO_LEFT_MARK ||
        codePoint in BIDI_EMBEDDING_FIRST..BIDI_EMBEDDING_LAST ||
        codePoint in BIDI_ISOLATE_FIRST..BIDI_ISOLATE_LAST

private inline fun ThemeStudioEditorState.editProfile(
    edit: AuroraMaterialProfile.() -> AuroraMaterialProfile,
): ThemeStudioEditorState {
    val editingCanonical = !selectedProfile.deletable && !newCopy
    return copy(
        name = if (editingCanonical) "" else name,
        draftProfile = draftProfile.edit(),
        newCopy = newCopy || editingCanonical,
        resetStaged = false,
        error = null,
        deleteConfirmationProfileId = null,
    )
}

const val MAXIMUM_THEME_STUDIO_PROFILE_NAME_CHARACTERS: Int = 64
const val MAXIMUM_THEME_STUDIO_PROFILE_NAME_UTF8_BYTES: Int = 256
const val MAXIMUM_THEME_STUDIO_SAVED_PROFILES: Int = 33
private const val BIDI_EMBEDDING_FIRST: Int = 0x202A
private const val BIDI_EMBEDDING_LAST: Int = 0x202E
private const val BIDI_ISOLATE_FIRST: Int = 0x2066
private const val BIDI_ISOLATE_LAST: Int = 0x2069
private const val ARABIC_LETTER_MARK: Int = 0x061C
private const val LEFT_TO_RIGHT_MARK: Int = 0x200E
private const val RIGHT_TO_LEFT_MARK: Int = 0x200F
private const val MAXIMUM_THEME_STUDIO_RAW_NAME_CODE_POINTS: Int = 128
