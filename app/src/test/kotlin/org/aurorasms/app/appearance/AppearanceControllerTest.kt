// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.aurorasms.core.designsystem.AuroraAvatarMask
import org.aurorasms.core.designsystem.AuroraBubbleGeometry
import org.aurorasms.core.designsystem.AuroraMaterialProfile
import org.aurorasms.core.designsystem.AuroraPalette
import org.aurorasms.core.designsystem.AuroraRowDensity
import org.aurorasms.core.state.AppearanceProfile
import org.aurorasms.core.state.AppearanceProfileEdit
import org.aurorasms.core.state.AppearanceProfileId
import org.aurorasms.core.state.AppearanceProfileName
import org.aurorasms.core.state.AppearanceProfileRepository
import org.aurorasms.core.state.AppearanceProfileValues
import org.aurorasms.core.state.AppearanceNavigationStyle
import org.aurorasms.core.state.AppearanceRepositoryResult
import org.aurorasms.core.state.AppearanceRevision
import org.aurorasms.core.state.AppearanceSnapshot
import org.aurorasms.core.state.NewAppearanceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppearanceControllerTest {
    @Test
    fun emptyStorageImmediatelyUsesCanonicalMaterialProfile() = runTest {
        val repository = FakeAppearanceRepository()
        val controller = AppearanceController(repository, backgroundScope)
        runCurrent()

        assertEquals(AuroraMaterialProfile.Default, controller.state.value.activeProfile)
        assertTrue(controller.state.value.profiles.isEmpty())
        assertNull(controller.state.value.activeProfileId)
    }

    @Test
    fun createAndActivateMapsEveryRenderedValueToStableStorageVocabulary() = runTest {
        val repository = FakeAppearanceRepository()
        val controller = AppearanceController(repository, backgroundScope) { 10_000L }
        val material = AuroraMaterialProfile(
            palette = AuroraPalette.AMOLED_BLACK,
            hueDegrees = 24,
            rowDensity = AuroraRowDensity.SPACIOUS,
            avatarMask = AuroraAvatarMask.HEXAGON,
            bubbleGeometry = AuroraBubbleGeometry.EXPRESSIVE,
            reducedMotion = true,
            highContrast = true,
            wallpaperDim = 0.61f,
        )

        assertEquals(
            AppearanceControllerResult.Success,
            controller.apply(
                ThemeStudioApplyRequest(
                    profileId = null,
                    expectedRevision = null,
                    name = "Night shift",
                    profile = material,
                    resetToDefault = false,
                ),
            ),
        )
        val created = checkNotNull(repository.lastCreated)
        assertTrue(repository.lastCreateActivated)
        assertEquals("Night shift", created.name.value)
        assertEquals("amoled_black", created.values.palette.storageCode)
        assertEquals("spacious", created.values.rowDensity.storageCode)
        assertEquals("hexagon", created.values.avatarMask.storageCode)
        assertEquals("expressive", created.values.bubbleGeometry.storageCode)
        assertEquals(610, created.values.wallpaperDimPermill)
    }

    @Test
    fun updateUsesOptimisticRevisionAndPreservesUneditedFocalPoint() = runTest {
        val repository = FakeAppearanceRepository()
        repository.emitProfile(focalX = 123, focalY = 876)
        val controller = AppearanceController(repository, backgroundScope) { 20_000L }
        runCurrent()

        val current = controller.state.value.profiles.single()
        val edited = current.profile.copy(hueDegrees = 210)
        assertEquals(
            AppearanceControllerResult.Success,
            controller.apply(
                ThemeStudioApplyRequest(
                    profileId = current.id,
                    expectedRevision = current.revision,
                    name = current.name,
                    profile = edited,
                    resetToDefault = false,
                ),
            ),
        )

        assertEquals(AppearanceRevision(1L), repository.lastExpectedRevision)
        assertEquals(123, repository.lastEdit?.values?.focalXPermill)
        assertEquals(876, repository.lastEdit?.values?.focalYPermill)
        assertTrue(repository.lastUpdateActivated)
    }

    @Test
    fun resetAndDuplicateConflictRemainTyped() = runTest {
        val repository = FakeAppearanceRepository()
        val controller = AppearanceController(repository, backgroundScope)

        assertEquals(
            AppearanceControllerResult.Success,
            controller.apply(
                ThemeStudioApplyRequest(
                    profileId = null,
                    expectedRevision = null,
                    name = "Ignored for reset",
                    profile = AuroraMaterialProfile.Default,
                    resetToDefault = true,
                ),
            ),
        )
        assertTrue(repository.resetCalled)

        repository.createResult = AppearanceRepositoryResult.Conflict
        assertEquals(
            AppearanceControllerResult.Failed(ThemeStudioError.DUPLICATE_NAME),
            controller.apply(
                ThemeStudioApplyRequest(
                    profileId = null,
                    expectedRevision = null,
                    name = "Duplicate",
                    profile = AuroraMaterialProfile.Default.copy(hueDegrees = 10),
                    resetToDefault = false,
                ),
            ),
        )
    }

    @Test
    fun unsupportedStoredNavigationProfileFallsBackWithoutCrashingThemeStudio() = runTest {
        val repository = FakeAppearanceRepository()
        repository.emitProfile(
            focalX = 500,
            focalY = 500,
            values = AppearanceProfileValues(
                navigationStyle = AppearanceNavigationStyle.BOTTOM_BAR,
            ),
        )
        val controller = AppearanceController(repository, backgroundScope)
        runCurrent()

        assertTrue(controller.state.value.profiles.isEmpty())
        assertNull(controller.state.value.activeProfileId)
        assertEquals(AuroraMaterialProfile.Default, controller.state.value.activeProfile)
    }

    @Test
    fun updateFailsStaleInsteadOfOverwritingHiddenValuesBeforeSnapshotArrives() = runTest {
        val repository = FakeAppearanceRepository()
        val controller = AppearanceController(repository, backgroundScope)

        val result = controller.apply(
            ThemeStudioApplyRequest(
                profileId = 7L,
                expectedRevision = 1L,
                name = "Restored edit",
                profile = AuroraMaterialProfile.Default.copy(hueDegrees = 210),
                resetToDefault = false,
            ),
        )

        assertEquals(
            AppearanceControllerResult.Failed(ThemeStudioError.STALE_PROFILE),
            result,
        )
        assertNull(repository.lastEdit)
    }
}

private class FakeAppearanceRepository : AppearanceProfileRepository {
    private val mutableSnapshots = MutableStateFlow(AppearanceSnapshot.Empty)
    override val snapshots: Flow<AppearanceSnapshot> = mutableSnapshots

    var lastCreated: NewAppearanceProfile? = null
    var lastCreateActivated: Boolean = false
    var lastEdit: AppearanceProfileEdit? = null
    var lastExpectedRevision: AppearanceRevision? = null
    var lastUpdateActivated: Boolean = false
    var resetCalled: Boolean = false
    var createResult: AppearanceRepositoryResult<AppearanceProfile>? = null

    fun emitProfile(
        focalX: Int,
        focalY: Int,
        values: AppearanceProfileValues = AppearanceProfileValues(
            focalXPermill = focalX,
            focalYPermill = focalY,
        ),
    ) {
        val profile = storedProfile(focalX, focalY, values = values)
        mutableSnapshots.value = AppearanceSnapshot(
            profiles = listOf(profile),
            activeProfileId = profile.id,
            revision = 1L,
        )
    }

    override suspend fun create(
        profile: NewAppearanceProfile,
        activate: Boolean,
    ): AppearanceRepositoryResult<AppearanceProfile> {
        lastCreated = profile
        lastCreateActivated = activate
        return createResult ?: AppearanceRepositoryResult.Success(
            storedProfile(
                focalX = profile.values.focalXPermill,
                focalY = profile.values.focalYPermill,
                name = profile.name,
                values = profile.values,
            ),
        )
    }

    override suspend fun update(
        edit: AppearanceProfileEdit,
        expectedRevision: AppearanceRevision,
        activate: Boolean,
    ): AppearanceRepositoryResult<AppearanceProfile> {
        lastEdit = edit
        lastExpectedRevision = expectedRevision
        lastUpdateActivated = activate
        return AppearanceRepositoryResult.Success(
            AppearanceProfile(
                id = edit.id,
                name = edit.name,
                values = edit.values,
                revision = AppearanceRevision(expectedRevision.value + 1L),
                createdTimestampMillis = 1L,
                updatedTimestampMillis = edit.updatedTimestampMillis,
            ),
        )
    }

    override suspend fun activate(id: AppearanceProfileId): AppearanceRepositoryResult<Unit> =
        AppearanceRepositoryResult.Success(Unit)

    override suspend fun resetActive(): AppearanceRepositoryResult<Unit> {
        resetCalled = true
        return AppearanceRepositoryResult.Success(Unit)
    }

    override suspend fun delete(
        id: AppearanceProfileId,
        expectedRevision: AppearanceRevision,
    ): AppearanceRepositoryResult<Unit> =
        AppearanceRepositoryResult.Success(Unit)
}

private fun storedProfile(
    focalX: Int,
    focalY: Int,
    name: AppearanceProfileName = AppearanceProfileName.from("Stored profile"),
    values: AppearanceProfileValues = AppearanceProfileValues(
        focalXPermill = focalX,
        focalYPermill = focalY,
    ),
): AppearanceProfile = AppearanceProfile(
    id = AppearanceProfileId(7L),
    name = name,
    values = values,
    revision = AppearanceRevision(1L),
    createdTimestampMillis = 1L,
    updatedTimestampMillis = 1L,
)
