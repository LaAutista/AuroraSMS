// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlin.math.roundToInt
import org.aurorasms.core.designsystem.AuroraAvatarMask
import org.aurorasms.core.designsystem.AuroraBubbleGeometry
import org.aurorasms.core.designsystem.AuroraMaterialProfile
import org.aurorasms.core.designsystem.AuroraNavigationStyle
import org.aurorasms.core.designsystem.AuroraPalette
import org.aurorasms.core.designsystem.AuroraRowDensity
import org.aurorasms.core.state.AppearanceAvatarMask
import org.aurorasms.core.state.AppearanceBubbleGeometry
import org.aurorasms.core.state.AppearanceNavigationStyle
import org.aurorasms.core.state.AppearanceOverride
import org.aurorasms.core.state.AppearanceOverrideRevision
import org.aurorasms.core.state.AppearancePalette
import org.aurorasms.core.state.AppearanceProfile
import org.aurorasms.core.state.AppearanceProfileEdit
import org.aurorasms.core.state.AppearanceProfileId
import org.aurorasms.core.state.AppearanceProfileName
import org.aurorasms.core.state.AppearanceProfileRepository
import org.aurorasms.core.state.AppearanceProfileValues
import org.aurorasms.core.state.AppearanceRepositoryResult
import org.aurorasms.core.state.AppearanceRevision
import org.aurorasms.core.state.AppearanceScope
import org.aurorasms.core.state.AppearanceRowDensity
import org.aurorasms.core.state.AppearanceSnapshot
import org.aurorasms.core.state.NewAppearanceProfile

@Immutable
data class AppAppearanceProfile(
    val id: Long,
    val revision: Long,
    val name: String,
    val profile: AuroraMaterialProfile,
    val focalXPermill: Int,
    val focalYPermill: Int,
) {
    override fun toString(): String =
        "AppAppearanceProfile(id=$id, revision=$revision, name=REDACTED)"
}

@Immutable
data class AppAppearanceState(
    val profiles: List<AppAppearanceProfile>,
    val activeProfileId: Long?,
    val activeProfile: AuroraMaterialProfile,
    val snapshotRevision: Long,
    val profileSnapshotReady: Boolean,
) {
    override fun toString(): String =
        "AppAppearanceState(profileCount=${profiles.size}, hasActive=${activeProfileId != null}, " +
            "snapshotRevision=$snapshotRevision, profileSnapshotReady=$profileSnapshotReady, REDACTED)"

    companion object {
        val Default: AppAppearanceState = AppAppearanceState(
            profiles = emptyList(),
            activeProfileId = null,
            activeProfile = AuroraMaterialProfile.Default,
            snapshotRevision = 0L,
            profileSnapshotReady = false,
        )
    }
}

@Immutable
data class AppAppearanceOverride(
    val profileId: Long,
    val revision: Long,
) {
    init {
        require(profileId > 0L) { "An app appearance override profile ID must be positive" }
        require(revision > 0L) { "An app appearance override revision must be positive" }
    }

    override fun toString(): String = "AppAppearanceOverride(REDACTED)"
}

/**
 * One target-tagged assignment observation. The target is retained only so callers can reject a
 * stale emission synchronously while a switched Flow produces its first value; its string form is
 * deliberately redacted because conversation scopes contain app-private correlation material.
 */
@Immutable
internal data class AppAppearanceOverrideObservation(
    val scope: AppearanceScope?,
    val override: AppAppearanceOverride?,
    val ready: Boolean,
) {
    init {
        require(scope != null || !ready) { "A missing appearance scope cannot be ready" }
        require(scope != null || override == null) { "A missing appearance scope cannot be assigned" }
        require(ready || override == null) { "A loading appearance scope cannot be assigned" }
    }

    fun overrideFor(expectedScope: AppearanceScope): AppAppearanceOverride? =
        takeIf { ready && scope == expectedScope }?.override

    fun isReadyFor(expectedScope: AppearanceScope): Boolean = ready && scope == expectedScope

    override fun toString(): String =
        "AppAppearanceOverrideObservation(ready=$ready, hasOverride=${override != null}, " +
            "scope=REDACTED)"

    companion object {
        fun loading(scope: AppearanceScope): AppAppearanceOverrideObservation =
            AppAppearanceOverrideObservation(scope = scope, override = null, ready = false)

        fun unavailable(): AppAppearanceOverrideObservation =
            AppAppearanceOverrideObservation(scope = null, override = null, ready = false)
    }
}

sealed interface AppearanceControllerResult {
    data object Success : AppearanceControllerResult
    data class Failed(val error: ThemeStudioError) : AppearanceControllerResult
}

sealed interface ScopedAppearanceControllerResult {
    data object Success : ScopedAppearanceControllerResult
    data class Failed(val error: ScopedAppearanceError) : ScopedAppearanceControllerResult
}

enum class ScopedAppearanceError {
    SAVE_FAILED,
    STALE_ASSIGNMENT,
    PROFILE_MISSING,
}

/**
 * Application-owned bridge between durable Aurora state and immutable rendering
 * profiles. Neither Compose nor the design-system module receives a database.
 */
class AppearanceController(
    private val repository: AppearanceProfileRepository,
    scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    val state: StateFlow<AppAppearanceState> = repository.snapshots
        .map(AppearanceSnapshot::toAppState)
        .catch { emit(AppAppearanceState.Default) }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = AppAppearanceState.Default,
        )

    /** Observes exactly one bounded scope; callers own collection lifetime and no scope is cached. */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun observeOverride(scope: AppearanceScope): Flow<AppAppearanceOverrideObservation> = state
        .map { appearance -> appearance.profileSnapshotReady }
        .distinctUntilChanged()
        .flatMapLatest { profileSnapshotReady ->
            if (!profileSnapshotReady) {
                flowOf(AppAppearanceOverrideObservation.loading(scope))
            } else {
                repository.observeOverride(scope).map { override ->
                    AppAppearanceOverrideObservation(
                        scope = scope,
                        override = override?.takeIf { it.scope == scope }?.toAppOverride(),
                        ready = true,
                    )
                }
            }
        }
        .onStart { emit(AppAppearanceOverrideObservation.loading(scope)) }
        .distinctUntilChanged()
        .catch { failure ->
            if (failure is CancellationException) throw failure
            emit(AppAppearanceOverrideObservation.loading(scope))
        }

    suspend fun applyOverride(
        scope: AppearanceScope,
        profileId: Long?,
        expectedRevision: Long?,
    ): ScopedAppearanceControllerResult = try {
        val expected = expectedRevision?.let(::AppearanceOverrideRevision)
        val result = if (profileId == null) {
            repository.resetOverride(scope, expected)
        } else {
            repository.setOverride(
                scope = scope,
                profileId = AppearanceProfileId(profileId),
                expectedRevision = expected,
            )
        }
        result.toScopedControllerResult()
    } catch (_: IllegalArgumentException) {
        ScopedAppearanceControllerResult.Failed(ScopedAppearanceError.SAVE_FAILED)
    }

    suspend fun apply(request: ThemeStudioApplyRequest): AppearanceControllerResult {
        if (request.resetToDefault) {
            return repository.resetActive().toControllerResult(ThemeStudioError.SAVE_FAILED)
        }
        val name = try {
            AppearanceProfileName.from(request.name)
        } catch (_: IllegalArgumentException) {
            return AppearanceControllerResult.Failed(ThemeStudioError.SAVE_FAILED)
        }
        val currentValues = request.profileId?.let { profileId ->
            storedValuesFor(
                profileId = profileId,
                expectedRevision = request.expectedRevision
                    ?: return AppearanceControllerResult.Failed(ThemeStudioError.STALE_PROFILE),
            ) ?: return AppearanceControllerResult.Failed(ThemeStudioError.STALE_PROFILE)
        }
        val values = request.profile.toStoredValues(current = currentValues)
        val timestamp = nowMillis().coerceAtLeast(0L)
        val result = if (request.profileId == null) {
            repository.create(
                profile = NewAppearanceProfile(
                    name = name,
                    values = values,
                    createdTimestampMillis = timestamp,
                ),
                activate = true,
            )
        } else {
            val expectedRevision = request.expectedRevision
                ?: return AppearanceControllerResult.Failed(ThemeStudioError.STALE_PROFILE)
            val unchanged = state.value.profiles.firstOrNull { it.id == request.profileId }
                ?.takeIf { profile ->
                    profile.revision == expectedRevision &&
                        profile.name == name.value &&
                        profile.profile == request.profile
                }
            if (unchanged != null) {
                return repository.activate(AppearanceProfileId(request.profileId))
                    .toControllerResult(ThemeStudioError.SAVE_FAILED)
            }
            repository.update(
                edit = AppearanceProfileEdit(
                    id = AppearanceProfileId(request.profileId),
                    name = name,
                    values = values,
                    updatedTimestampMillis = timestamp,
                ),
                expectedRevision = AppearanceRevision(expectedRevision),
                activate = true,
            )
        }
        return result.toControllerResult(ThemeStudioError.SAVE_FAILED)
    }

    suspend fun delete(
        profileId: Long,
        expectedRevision: Long,
    ): AppearanceControllerResult =
        try {
            repository.delete(
                id = AppearanceProfileId(profileId),
                expectedRevision = AppearanceRevision(expectedRevision),
            )
                .toControllerResult(ThemeStudioError.DELETE_FAILED)
        } catch (_: IllegalArgumentException) {
            AppearanceControllerResult.Failed(ThemeStudioError.DELETE_FAILED)
        }

    private fun storedValuesFor(
        profileId: Long,
        expectedRevision: Long,
    ): AppearanceProfileValues? = state.value.profiles
        .firstOrNull { it.id == profileId && it.revision == expectedRevision }
        ?.let { profile ->
            profile.profile.toStoredValues(
                current = null,
                focalXPermill = profile.focalXPermill,
                focalYPermill = profile.focalYPermill,
            )
        }
}

internal fun AppAppearanceState.profileFor(
    override: AppAppearanceOverride?,
    inherited: AuroraMaterialProfile,
): AuroraMaterialProfile = override
    ?.let { assignment -> profiles.firstOrNull { it.id == assignment.profileId } }
    ?.profile
    ?: inherited

internal fun AppAppearanceState.profileNameFor(
    override: AppAppearanceOverride?,
    inheritedName: String,
): String = override
    ?.let { assignment -> profiles.firstOrNull { it.id == assignment.profileId } }
    ?.name
    ?: inheritedName

private fun AppearanceOverride.toAppOverride(): AppAppearanceOverride = AppAppearanceOverride(
    profileId = profileId.value,
    revision = revision.value,
)

private fun AppearanceSnapshot.toAppState(): AppAppearanceState {
    val mapped = profiles.mapNotNull { profile ->
        runCatching { profile.toAppProfile() }.getOrNull()
    }
    val activeId = activeProfileId?.value?.takeIf { id -> mapped.any { it.id == id } }
    val active = mapped.firstOrNull { it.id == activeId }?.profile ?: AuroraMaterialProfile.Default
    return AppAppearanceState(
        profiles = mapped,
        activeProfileId = activeId,
        activeProfile = active,
        snapshotRevision = revision,
        profileSnapshotReady = revision > 0L,
    )
}

private fun AppearanceProfile.toAppProfile(): AppAppearanceProfile = AppAppearanceProfile(
    id = id.value,
    revision = revision.value,
    name = name.value,
    profile = values.toMaterialProfile(),
    focalXPermill = values.focalXPermill,
    focalYPermill = values.focalYPermill,
)

private fun AppearanceProfileValues.toMaterialProfile(): AuroraMaterialProfile = AuroraMaterialProfile(
    schemaVersion = schemaVersion,
    palette = when (palette) {
        AppearancePalette.AURORA_DARK -> AuroraPalette.AURORA_DARK
        AppearancePalette.AMOLED_BLACK -> AuroraPalette.AMOLED_BLACK
        AppearancePalette.LIGHT -> AuroraPalette.LIGHT
        AppearancePalette.SYSTEM_DYNAMIC -> AuroraPalette.SYSTEM_DYNAMIC
    },
    hueDegrees = hueDegrees,
    rowDensity = when (rowDensity) {
        AppearanceRowDensity.COMPACT -> AuroraRowDensity.COMPACT
        AppearanceRowDensity.COMFORTABLE -> AuroraRowDensity.COMFORTABLE
        AppearanceRowDensity.SPACIOUS -> AuroraRowDensity.SPACIOUS
    },
    avatarMask = when (avatarMask) {
        AppearanceAvatarMask.CIRCLE -> AuroraAvatarMask.CIRCLE
        AppearanceAvatarMask.ROUNDED_SQUARE -> AuroraAvatarMask.ROUNDED_SQUARE
        AppearanceAvatarMask.SQUIRCLE -> AuroraAvatarMask.SQUIRCLE
        AppearanceAvatarMask.HEXAGON -> AuroraAvatarMask.HEXAGON
    },
    navigationStyle = navigationStyle.toSupportedMaterialNavigationStyle(),
    bubbleGeometry = when (bubbleGeometry) {
        AppearanceBubbleGeometry.COMPACT -> AuroraBubbleGeometry.COMPACT
        AppearanceBubbleGeometry.ROUNDED -> AuroraBubbleGeometry.ROUNDED
        AppearanceBubbleGeometry.EXPRESSIVE -> AuroraBubbleGeometry.EXPRESSIVE
    },
    reducedMotion = reducedMotion,
    highContrast = highContrast,
    wallpaperDim = wallpaperDimPermill / PERMILL_SCALE,
)

private fun AppearanceNavigationStyle.toSupportedMaterialNavigationStyle(): AuroraNavigationStyle {
    require(this == AppearanceNavigationStyle.CLASSIC) {
        "The stored navigation presentation is not implemented by this app version"
    }
    return AuroraNavigationStyle.CLASSIC
}

private fun AuroraMaterialProfile.toStoredValues(
    current: AppearanceProfileValues?,
    focalXPermill: Int = current?.focalXPermill ?: DEFAULT_FOCAL_PERMILL,
    focalYPermill: Int = current?.focalYPermill ?: DEFAULT_FOCAL_PERMILL,
): AppearanceProfileValues = AppearanceProfileValues(
    schemaVersion = schemaVersion,
    palette = when (palette) {
        AuroraPalette.AURORA_DARK -> AppearancePalette.AURORA_DARK
        AuroraPalette.AMOLED_BLACK -> AppearancePalette.AMOLED_BLACK
        AuroraPalette.LIGHT -> AppearancePalette.LIGHT
        AuroraPalette.SYSTEM_DYNAMIC -> AppearancePalette.SYSTEM_DYNAMIC
    },
    hueDegrees = hueDegrees,
    rowDensity = when (rowDensity) {
        AuroraRowDensity.COMPACT -> AppearanceRowDensity.COMPACT
        AuroraRowDensity.COMFORTABLE -> AppearanceRowDensity.COMFORTABLE
        AuroraRowDensity.SPACIOUS -> AppearanceRowDensity.SPACIOUS
    },
    avatarMask = when (avatarMask) {
        AuroraAvatarMask.CIRCLE -> AppearanceAvatarMask.CIRCLE
        AuroraAvatarMask.ROUNDED_SQUARE -> AppearanceAvatarMask.ROUNDED_SQUARE
        AuroraAvatarMask.SQUIRCLE -> AppearanceAvatarMask.SQUIRCLE
        AuroraAvatarMask.HEXAGON -> AppearanceAvatarMask.HEXAGON
    },
    navigationStyle = when (navigationStyle) {
        AuroraNavigationStyle.CLASSIC -> AppearanceNavigationStyle.CLASSIC
        AuroraNavigationStyle.BOTTOM_BAR -> AppearanceNavigationStyle.BOTTOM_BAR
        AuroraNavigationStyle.ADAPTIVE_RAIL -> AppearanceNavigationStyle.ADAPTIVE_RAIL
    },
    bubbleGeometry = when (bubbleGeometry) {
        AuroraBubbleGeometry.COMPACT -> AppearanceBubbleGeometry.COMPACT
        AuroraBubbleGeometry.ROUNDED -> AppearanceBubbleGeometry.ROUNDED
        AuroraBubbleGeometry.EXPRESSIVE -> AppearanceBubbleGeometry.EXPRESSIVE
    },
    reducedMotion = reducedMotion,
    highContrast = highContrast,
    wallpaperDimPermill = (wallpaperDim * PERMILL_SCALE).roundToInt(),
    focalXPermill = focalXPermill,
    focalYPermill = focalYPermill,
)

private fun AppearanceRepositoryResult<*>.toControllerResult(
    fallback: ThemeStudioError,
): AppearanceControllerResult = when (this) {
    is AppearanceRepositoryResult.Success -> AppearanceControllerResult.Success
    AppearanceRepositoryResult.Conflict -> AppearanceControllerResult.Failed(ThemeStudioError.DUPLICATE_NAME)
    AppearanceRepositoryResult.StaleWrite -> AppearanceControllerResult.Failed(ThemeStudioError.STALE_PROFILE)
    is AppearanceRepositoryResult.LimitExceeded ->
        AppearanceControllerResult.Failed(ThemeStudioError.PROFILE_LIMIT)
    AppearanceRepositoryResult.NotFound,
    AppearanceRepositoryResult.InvalidTimestamp,
    AppearanceRepositoryResult.CorruptData,
    is AppearanceRepositoryResult.StorageFailure,
    -> AppearanceControllerResult.Failed(fallback)
}

private fun AppearanceRepositoryResult<*>.toScopedControllerResult(): ScopedAppearanceControllerResult =
    when (this) {
        is AppearanceRepositoryResult.Success -> ScopedAppearanceControllerResult.Success
        AppearanceRepositoryResult.StaleWrite,
        AppearanceRepositoryResult.Conflict,
        -> ScopedAppearanceControllerResult.Failed(ScopedAppearanceError.STALE_ASSIGNMENT)
        AppearanceRepositoryResult.NotFound ->
            ScopedAppearanceControllerResult.Failed(ScopedAppearanceError.PROFILE_MISSING)
        AppearanceRepositoryResult.InvalidTimestamp,
        AppearanceRepositoryResult.CorruptData,
        is AppearanceRepositoryResult.LimitExceeded,
        is AppearanceRepositoryResult.StorageFailure,
        -> ScopedAppearanceControllerResult.Failed(ScopedAppearanceError.SAVE_FAILED)
    }

private const val PERMILL_SCALE: Float = 1_000f
private const val DEFAULT_FOCAL_PERMILL: Int = 500
