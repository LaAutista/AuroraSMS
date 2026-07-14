// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance

import java.nio.charset.StandardCharsets
import org.aurorasms.core.designsystem.AuroraAvatarMask
import org.aurorasms.core.designsystem.AuroraBubbleGeometry
import org.aurorasms.core.designsystem.AuroraMaterialProfile
import org.aurorasms.core.designsystem.AuroraNavigationStyle
import org.aurorasms.core.designsystem.AuroraPalette
import org.aurorasms.core.designsystem.AuroraRowDensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeStudioEditorStateTest {
    @Test
    fun selectingSavedProfileLoadsOneImmutableDraftWithoutChangingActiveProfile() {
        val initial = initialState()

        val selected = reduceThemeStudio(initial, ThemeStudioAction.SelectProfile(CUSTOM_ID))

        assertEquals(CANONICAL_ID, selected.activeProfileId)
        assertEquals(CUSTOM_ID, selected.selectedProfileId)
        assertEquals("Evening", selected.name)
        assertEquals(AuroraPalette.AMOLED_BLACK, selected.draftProfile.palette)
        assertTrue(selected.canApply)
    }

    @Test
    fun everyExposedAppearanceFieldIsReducedIntoThePreviewProfile() {
        var state = initialState()
        state = reduceThemeStudio(state, ThemeStudioAction.ChangePalette(AuroraPalette.LIGHT))
        state = reduceThemeStudio(state, ThemeStudioAction.ChangeHue(42))
        state = reduceThemeStudio(state, ThemeStudioAction.ChangeDensity(AuroraRowDensity.SPACIOUS))
        state = reduceThemeStudio(state, ThemeStudioAction.ChangeAvatarMask(AuroraAvatarMask.HEXAGON))
        state = reduceThemeStudio(
            state,
            ThemeStudioAction.ChangeBubbleGeometry(AuroraBubbleGeometry.EXPRESSIVE),
        )
        state = reduceThemeStudio(state, ThemeStudioAction.ChangeHighContrast(true))

        assertEquals(AuroraPalette.LIGHT, state.draftProfile.palette)
        assertEquals(42, state.draftProfile.hueDegrees)
        assertEquals(AuroraRowDensity.SPACIOUS, state.draftProfile.rowDensity)
        assertEquals(AuroraAvatarMask.HEXAGON, state.draftProfile.avatarMask)
        assertEquals(AuroraBubbleGeometry.EXPRESSIVE, state.draftProfile.bubbleGeometry)
        assertFalse(state.draftProfile.reducedMotion)
        assertTrue(state.draftProfile.highContrast)
        assertTrue(state.dirty)
    }

    @Test
    fun dynamicPaletteHonestyPreventsAnUnavailableHueEdit() {
        val dynamic = reduceThemeStudio(
            initialState(),
            ThemeStudioAction.ChangePalette(AuroraPalette.SYSTEM_DYNAMIC),
        )

        val ignored = reduceThemeStudio(dynamic, ThemeStudioAction.ChangeHue(11))

        assertEquals(dynamic, ignored)
        assertEquals(AuroraMaterialProfile.Default.hueDegrees, ignored.draftProfile.hueDegrees)
    }

    @Test
    fun newCopyHasNoUpdateTargetAndExistingProfileUsesItsExactRevision() {
        val selected = reduceThemeStudio(initialState(), ThemeStudioAction.SelectProfile(CUSTOM_ID))
        val existingRequest = selected.applyRequest()
        assertEquals(CUSTOM_ID, existingRequest?.profileId)
        assertEquals(CUSTOM_REVISION, existingRequest?.expectedRevision)

        var copy = reduceThemeStudio(selected, ThemeStudioAction.NewCopy)
        assertFalse(copy.nameValid)
        assertNull(copy.applyRequest())
        copy = reduceThemeStudio(copy, ThemeStudioAction.ChangeName("Evening copy"))

        val copyRequest = copy.applyRequest()
        assertNull(copyRequest?.profileId)
        assertNull(copyRequest?.expectedRevision)
        assertEquals("Evening copy", copyRequest?.name)
        assertEquals(selected.draftProfile, copyRequest?.profile)
    }

    @Test
    fun selectingCanonicalWhileNamedProfileIsActiveStagesAResetNotADuplicate() {
        val activeNamed = ThemeStudioEditorState.create(
            savedProfiles = initialState().savedProfiles,
            activeProfileId = CUSTOM_ID,
        )
        val selectedCanonical = reduceThemeStudio(
            activeNamed,
            ThemeStudioAction.SelectProfile(CANONICAL_ID),
        )

        val request = checkNotNull(selectedCanonical.applyRequest())
        assertTrue(request.resetToDefault)
        assertNull(request.profileId)
        assertNull(request.expectedRevision)
    }

    @Test
    fun editingCanonicalTransitionsToANamedCopyAndCannotSilentlyReset() {
        var edited = reduceThemeStudio(
            initialState(),
            ThemeStudioAction.ChangeHue(42),
        )
        assertTrue(edited.newCopy)
        assertFalse(edited.nameValid)
        assertNull(edited.applyRequest())

        edited = reduceThemeStudio(edited, ThemeStudioAction.ChangeName("Custom default"))
        val request = checkNotNull(edited.applyRequest())
        assertFalse(request.resetToDefault)
        assertNull(request.profileId)
        assertEquals(42, request.profile.hueDegrees)
    }

    @Test
    fun resetIsOnlyStagedAndCancelProfileRemainsTheOriginalActiveProfile() {
        val activeNamed = ThemeStudioEditorState.create(
            savedProfiles = initialState().savedProfiles,
            activeProfileId = CUSTOM_ID,
        )
        val reset = reduceThemeStudio(activeNamed, ThemeStudioAction.Reset)

        assertTrue(reset.resetStaged)
        assertEquals(AuroraMaterialProfile.Default, reset.draftProfile)
        assertEquals(AuroraPalette.AMOLED_BLACK, reset.activeProfile.profile.palette)
        assertTrue(reset.applyRequest()?.resetToDefault == true)

        val renamed = reduceThemeStudio(reset, ThemeStudioAction.ChangeName("Default copy"))
        assertFalse(renamed.resetStaged)
        assertFalse(checkNotNull(renamed.applyRequest()).resetToDefault)
    }

    @Test
    fun deleteRequiresConfirmationAndCannotTargetCanonicalOrUnsavedCopy() {
        val canonical = reduceThemeStudio(initialState(), ThemeStudioAction.RequestDelete)
        assertNull(canonical.deleteConfirmationProfileId)

        val selected = reduceThemeStudio(initialState(), ThemeStudioAction.SelectProfile(CUSTOM_ID))
        val requested = reduceThemeStudio(selected, ThemeStudioAction.RequestDelete)
        assertEquals(CUSTOM_ID, requested.deleteConfirmationProfileId)
        assertNull(
            reduceThemeStudio(requested, ThemeStudioAction.DismissDelete)
                .deleteConfirmationProfileId,
        )

        val copy = reduceThemeStudio(selected, ThemeStudioAction.NewCopy)
        assertNull(reduceThemeStudio(copy, ThemeStudioAction.RequestDelete).deleteConfirmationProfileId)
    }

    @Test
    fun busyOperationRejectsEditsUntilTheOwnerPublishesIdle() {
        val busy = reduceThemeStudio(
            initialState(),
            ThemeStudioAction.SetOperation(ThemeStudioOperation.APPLYING),
        )

        assertEquals(
            busy,
            reduceThemeStudio(busy, ThemeStudioAction.ChangePalette(AuroraPalette.LIGHT)),
        )
        assertFalse(busy.canApply)
        assertEquals(
            ThemeStudioOperation.IDLE,
            reduceThemeStudio(
                busy,
                ThemeStudioAction.SetOperation(ThemeStudioOperation.IDLE),
            ).operation,
        )
    }

    @Test
    fun nameInputIsNfcBoundedAndRejectsControlsAndBidiOverrides() {
        var state = reduceThemeStudio(initialState(), ThemeStudioAction.NewCopy)
        val hostile = "  Cafe\u0301\u0000\u061C\u200E\u200F\u202E" + "🙂".repeat(100)
        state = reduceThemeStudio(state, ThemeStudioAction.ChangeName(hostile))

        assertFalse(state.name.contains('\u0000'))
        assertFalse(state.name.contains('\u061C'))
        assertFalse(state.name.contains('\u200E'))
        assertFalse(state.name.contains('\u200F'))
        assertFalse(state.name.contains('\u202E'))
        assertTrue(
            state.name.codePointCount(0, state.name.length) <=
                MAXIMUM_THEME_STUDIO_PROFILE_NAME_CHARACTERS,
        )
        assertTrue(
            state.name.toByteArray(StandardCharsets.UTF_8).size <=
                MAXIMUM_THEME_STUDIO_PROFILE_NAME_UTF8_BYTES,
        )

        state = reduceThemeStudio(state, ThemeStudioAction.ChangeName("  Cafe\u0301  "))
        assertEquals("Café", state.applyRequest()?.name)
    }

    @Test
    fun pureRestorationRoundTripsStableCodesAndDropsTransientOperations() {
        val profile = AuroraMaterialProfile(
            palette = AuroraPalette.LIGHT,
            hueDegrees = 271,
            rowDensity = AuroraRowDensity.COMPACT,
            avatarMask = AuroraAvatarMask.ROUNDED_SQUARE,
            navigationStyle = AuroraNavigationStyle.CLASSIC,
            bubbleGeometry = AuroraBubbleGeometry.EXPRESSIVE,
            reducedMotion = true,
            highContrast = true,
            wallpaperDim = 0.61f,
        )
        val edited = initialState().copy(
            selectedProfileId = CUSTOM_ID,
            name = "Restored",
            draftProfile = profile,
            newCopy = true,
            resetStaged = false,
            operation = ThemeStudioOperation.APPLYING,
            error = ThemeStudioError.SAVE_FAILED,
            deleteConfirmationProfileId = null,
        )

        val saved = edited.toRestorableState()
        assertEquals("light", saved.draftProfile.paletteCode)
        assertEquals("compact", saved.draftProfile.densityCode)
        assertEquals("rounded_square", saved.draftProfile.avatarMaskCode)
        assertEquals("classic", saved.draftProfile.navigationCode)
        assertEquals("expressive", saved.draftProfile.bubbleGeometryCode)

        val restored = saved.toEditorStateOrNull()
        assertEquals(profile, restored?.draftProfile)
        assertEquals("Restored", restored?.name)
        assertTrue(restored?.newCopy == true)
        assertFalse(restored?.resetStaged == true)
        assertEquals(ThemeStudioOperation.IDLE, restored?.operation)
        assertNull(restored?.error)
        assertNull(restored?.deleteConfirmationProfileId)
    }

    @Test
    fun restorationRejectsContradictoryOrUnstagedCanonicalState() {
        val saved = initialState().toRestorableState()
        val canonical = saved.profiles.single { !it.deletable }

        assertNull(
            saved.copy(
                activeProfileId = CUSTOM_ID,
                selectedProfileId = CANONICAL_ID,
                newCopy = true,
                resetStaged = true,
            ).toEditorStateOrNull(),
        )
        assertNull(
            saved.copy(
                activeProfileId = CUSTOM_ID,
                selectedProfileId = CANONICAL_ID,
                editedName = canonical.name,
                draftProfile = canonical.profile,
                resetStaged = false,
            ).toEditorStateOrNull(),
        )
        assertNull(
            saved.copy(
                activeProfileId = CUSTOM_ID,
                selectedProfileId = CANONICAL_ID,
                editedName = canonical.name,
                draftProfile = saved.draftProfile.copy(hueDegrees = 42),
                resetStaged = true,
            ).toEditorStateOrNull(),
        )
        assertNull(
            saved.copy(
                profiles = saved.profiles.map { profile ->
                    if (profile.deletable) {
                        profile
                    } else {
                        profile.copy(
                            profile = profile.profile.copy(hueDegrees = 42),
                        )
                    }
                },
            ).toEditorStateOrNull(),
        )
    }

    @Test
    fun editorConstructorRejectsContradictoryResetAndCopyState() {
        val state = initialState()

        assertThrows(IllegalArgumentException::class.java) {
            state.copy(newCopy = true, resetStaged = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThemeStudioApplyRequest(
                profileId = null,
                expectedRevision = null,
                name = "Mismatched reset",
                profile = AuroraMaterialProfile.Default.copy(hueDegrees = 42),
                resetToDefault = true,
            )
        }
    }

    @Test
    fun restorationRejectsUnknownCodesAndUnsupportedSchemas() {
        val saved = initialState().toRestorableState()
        assertNull(saved.copy(schemaVersion = 2).toEditorStateOrNull())
        assertNull(
            saved.copy(
                draftProfile = saved.draftProfile.copy(paletteCode = "future_palette"),
            ).toEditorStateOrNull(),
        )
        assertNull(
            saved.copy(
                draftProfile = saved.draftProfile.copy(hueDegrees = 360),
            ).toEditorStateOrNull(),
        )
    }

    @Test
    fun uiBoundAllowsCanonicalPlusThirtyTwoDurableProfiles() {
        val profiles = buildList {
            add(canonicalProfile())
            repeat(32) { index ->
                add(
                    ThemeStudioSavedProfile(
                        id = index + 1L,
                        revision = 1L,
                        name = "Profile ${index + 1}",
                        profile = AuroraMaterialProfile.Default,
                        deletable = true,
                    ),
                )
            }
        }

        assertEquals(33, ThemeStudioEditorState.create(profiles, CANONICAL_ID).savedProfiles.size)
    }

    @Test
    fun diagnosticStringsRedactProfileNames() {
        val state = initialState()
        assertFalse(state.toString().contains("Aurora default"))
        assertFalse(state.savedProfiles.last().toString().contains("Evening"))
        assertFalse(state.toRestorableState().toString().contains("Evening"))
        assertFalse(state.toRestorableState().profiles.last().toString().contains("Evening"))
        assertFalse(ThemeStudioAction.ChangeName("Private name").toString().contains("Private name"))
    }

    private fun initialState(): ThemeStudioEditorState = ThemeStudioEditorState.create(
        savedProfiles = listOf(
            canonicalProfile(),
            ThemeStudioSavedProfile(
                id = CUSTOM_ID,
                revision = CUSTOM_REVISION,
                name = "Evening",
                profile = AuroraMaterialProfile(palette = AuroraPalette.AMOLED_BLACK),
                deletable = true,
            ),
        ),
        activeProfileId = CANONICAL_ID,
    )

    private fun canonicalProfile() = ThemeStudioSavedProfile(
        id = CANONICAL_ID,
        revision = 0L,
        name = "Aurora default",
        profile = AuroraMaterialProfile.Default,
        deletable = false,
    )

    private companion object {
        const val CANONICAL_ID: Long = 0L
        const val CUSTOM_ID: Long = 7L
        const val CUSTOM_REVISION: Long = 3L
    }
}
