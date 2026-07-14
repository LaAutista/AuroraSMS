// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
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
import org.aurorasms.core.state.AppearanceOverride
import org.aurorasms.core.state.AppearanceOverrideRevision
import org.aurorasms.core.state.AppearanceRepositoryResult
import org.aurorasms.core.state.AppearanceRevision
import org.aurorasms.core.state.AppearanceScope
import org.aurorasms.core.state.AppearanceScreenScope
import org.aurorasms.core.state.AppearanceSnapshot
import org.aurorasms.core.state.NewAppearanceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(controller.state.value.profileSnapshotReady)
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

    @Test
    fun scopedAssignmentAndResetForwardExactOptimisticState() = runTest {
        val repository = FakeAppearanceRepository()
        val controller = AppearanceController(repository, backgroundScope)
        val target = AppearanceScope.Screen(AppearanceScreenScope.INBOX)

        assertEquals(
            ScopedAppearanceControllerResult.Success,
            controller.applyOverride(target, profileId = 7L, expectedRevision = null),
        )
        assertEquals(target, repository.lastOverrideScope)
        assertEquals(AppearanceProfileId(7L), repository.lastOverrideProfileId)
        assertNull(repository.lastOverrideExpectedRevision)

        assertEquals(
            ScopedAppearanceControllerResult.Success,
            controller.applyOverride(target, profileId = null, expectedRevision = 3L),
        )
        assertEquals(target, repository.lastResetOverrideScope)
        assertEquals(AppearanceOverrideRevision(3L), repository.lastResetOverrideExpectedRevision)
    }

    @Test
    fun resetFromObservedInheritanceStillChecksForAConcurrentInsert() = runTest {
        val repository = FakeAppearanceRepository().apply {
            resetOverrideResult = AppearanceRepositoryResult.StaleWrite
        }
        val controller = AppearanceController(repository, backgroundScope)
        val target = AppearanceScope.Screen(AppearanceScreenScope.GLOBAL_THREAD)

        assertEquals(
            ScopedAppearanceControllerResult.Failed(ScopedAppearanceError.STALE_ASSIGNMENT),
            controller.applyOverride(target, profileId = null, expectedRevision = null),
        )
        assertEquals(target, repository.lastResetOverrideScope)
        assertNull(repository.lastResetOverrideExpectedRevision)
    }

    @Test
    fun missingScopedProfileFallsForwardToInheritedMaterialProfile() = runTest {
        val repository = FakeAppearanceRepository()
        repository.emitProfile(focalX = 500, focalY = 500)
        val controller = AppearanceController(repository, backgroundScope)
        runCurrent()
        val inherited = AuroraMaterialProfile.Default.copy(hueDegrees = 12)

        assertEquals(
            controller.state.value.profiles.single().profile,
            controller.state.value.profileFor(AppAppearanceOverride(profileId = 7L, revision = 1L), inherited),
        )
        assertEquals(
            inherited,
            controller.state.value.profileFor(AppAppearanceOverride(profileId = 99L, revision = 1L), inherited),
        )
    }

    @Test
    fun revisionZeroKeepsAssignmentLoadingUntilDurableEmptySnapshotArrives() = runTest {
        val repository = FakeAppearanceRepository(initialSnapshot = AppearanceSnapshot.Empty)
        val controller = AppearanceController(repository, backgroundScope)
        val target = AppearanceScope.Screen(AppearanceScreenScope.INBOX)
        val readyObservation = async {
            controller.observeOverride(target).first { observation -> observation.ready }
        }

        runCurrent()

        assertFalse(controller.state.value.profileSnapshotReady)
        assertFalse(readyObservation.isCompleted)

        repository.emitAuthoritativeEmpty()
        runCurrent()

        val observation = readyObservation.await()
        assertTrue(controller.state.value.profileSnapshotReady)
        assertTrue(observation.isReadyFor(target))
        assertNull(observation.overrideFor(target))
    }

    @Test
    fun staleTargetObservationIsSynchronouslyRejected() {
        val first = AppearanceScope.Screen(AppearanceScreenScope.INBOX)
        val second = AppearanceScope.Screen(AppearanceScreenScope.GLOBAL_THREAD)
        val firstObservation = AppAppearanceOverrideObservation(
            scope = first,
            override = AppAppearanceOverride(profileId = 7L, revision = 11L),
            ready = true,
        )

        assertTrue(firstObservation.isReadyFor(first))
        assertFalse(firstObservation.isReadyFor(second))
        assertNull(firstObservation.overrideFor(second))
        assertFalse(firstObservation.toString().contains(first.screen.storageCode))
    }

    @Test
    fun restoredDraftFromAnotherPrivateTargetUsesCurrentDurableBaseline() {
        val restored = ScopedAppearanceDraftState(
            privateRestorationKey = "synthetic-target-a",
            baselineProfileId = 7L,
            expectedRevision = 3L,
            selectedProfileId = 8L,
        )
        val currentDurable = ScopedAppearanceDraftState(
            privateRestorationKey = "synthetic-target-b",
            baselineProfileId = 9L,
            expectedRevision = 5L,
            selectedProfileId = 9L,
        )

        assertEquals(currentDurable, restored.forTargetOr(currentDurable))
        assertFalse(restored.toString().contains("synthetic-target-a"))
    }
}

private class FakeAppearanceRepository(
    initialSnapshot: AppearanceSnapshot = authoritativeEmptySnapshot(),
) : AppearanceProfileRepository {
    private val mutableSnapshots = MutableStateFlow(initialSnapshot)
    override val snapshots: Flow<AppearanceSnapshot> = mutableSnapshots

    var lastCreated: NewAppearanceProfile? = null
    var lastCreateActivated: Boolean = false
    var lastEdit: AppearanceProfileEdit? = null
    var lastExpectedRevision: AppearanceRevision? = null
    var lastUpdateActivated: Boolean = false
    var resetCalled: Boolean = false
    var createResult: AppearanceRepositoryResult<AppearanceProfile>? = null
    private val mutableOverride = MutableStateFlow<AppearanceOverride?>(null)
    var lastOverrideScope: AppearanceScope? = null
    var lastOverrideProfileId: AppearanceProfileId? = null
    var lastOverrideExpectedRevision: AppearanceOverrideRevision? = null
    var lastResetOverrideScope: AppearanceScope? = null
    var lastResetOverrideExpectedRevision: AppearanceOverrideRevision? = null
    var resetOverrideResult: AppearanceRepositoryResult<Unit> = AppearanceRepositoryResult.Success(Unit)

    fun emitAuthoritativeEmpty() {
        mutableSnapshots.value = authoritativeEmptySnapshot()
    }

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

    override fun observeOverride(scope: AppearanceScope): Flow<AppearanceOverride?> = mutableOverride

    override suspend fun setOverride(
        scope: AppearanceScope,
        profileId: AppearanceProfileId,
        expectedRevision: AppearanceOverrideRevision?,
    ): AppearanceRepositoryResult<AppearanceOverride> {
        lastOverrideScope = scope
        lastOverrideProfileId = profileId
        lastOverrideExpectedRevision = expectedRevision
        return AppearanceRepositoryResult.Success(
            AppearanceOverride(
                scope = scope,
                profileId = profileId,
                revision = AppearanceOverrideRevision((expectedRevision?.value ?: 0L) + 1L),
            ),
        )
    }

    override suspend fun resetOverride(
        scope: AppearanceScope,
        expectedRevision: AppearanceOverrideRevision?,
    ): AppearanceRepositoryResult<Unit> {
        lastResetOverrideScope = scope
        lastResetOverrideExpectedRevision = expectedRevision
        return resetOverrideResult
    }
}

private fun authoritativeEmptySnapshot(): AppearanceSnapshot = AppearanceSnapshot(
    profiles = emptyList(),
    activeProfileId = null,
    revision = 1L,
)

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
