// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import android.net.Uri
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.aurorasms.core.state.AppearanceLimit
import org.aurorasms.core.state.AppearanceRepositoryResult
import org.aurorasms.core.state.AppearanceScope
import org.aurorasms.core.state.AppearanceWallpaperAssignment
import org.aurorasms.core.state.AppearanceWallpaperMediaId
import org.aurorasms.core.state.AppearanceWallpaperRepository
import org.aurorasms.core.state.AppearanceWallpaperRevision

@Immutable
internal data class AppWallpaperAssignment(
    val scope: AppearanceScope,
    val mediaId: String,
    val dimPermill: Int,
    val focalXPermill: Int,
    val focalYPermill: Int,
    val revision: Long,
) {
    override fun toString(): String = "AppWallpaperAssignment(REDACTED)"
}

@Immutable
internal data class AppWallpaperObservation(
    val scope: AppearanceScope?,
    val assignment: AppWallpaperAssignment?,
    val ready: Boolean,
) {
    init {
        require(scope != null || !ready)
        require(scope != null || assignment == null)
        require(ready || assignment == null)
    }

    fun assignmentFor(expectedScope: AppearanceScope): AppWallpaperAssignment? =
        takeIf { ready && scope == expectedScope }?.assignment

    fun isReadyFor(expectedScope: AppearanceScope): Boolean = ready && scope == expectedScope

    override fun toString(): String =
        "AppWallpaperObservation(ready=$ready, assigned=${assignment != null}, scope=REDACTED)"

    companion object {
        fun loading(scope: AppearanceScope): AppWallpaperObservation =
            AppWallpaperObservation(scope = scope, assignment = null, ready = false)

        fun unavailable(): AppWallpaperObservation =
            AppWallpaperObservation(scope = null, assignment = null, ready = false)
    }
}

internal enum class WallpaperControllerError {
    SOURCE_UNAVAILABLE,
    UNSUPPORTED_SOURCE,
    SOURCE_TOO_LARGE,
    QUOTA_EXCEEDED,
    STALE_ASSIGNMENT,
    SAVE_FAILED,
}

internal sealed interface WallpaperInspectionControllerResult {
    data class Ready(val selection: WallpaperInspectionResult.Ready) : WallpaperInspectionControllerResult {
        override fun toString(): String = "WallpaperInspectionControllerResult.Ready(REDACTED)"
    }

    data class Failed(val error: WallpaperControllerError) : WallpaperInspectionControllerResult
}

internal sealed interface WallpaperApplyControllerResult {
    data object Success : WallpaperApplyControllerResult
    data class Failed(val error: WallpaperControllerError) : WallpaperApplyControllerResult
}

internal data class LoadedWallpaper(
    val assignment: AppWallpaperAssignment,
    val image: androidx.compose.ui.graphics.ImageBitmap,
) {
    fun release() {
        image.recycleWallpaperBitmap()
    }

    override fun toString(): String = "LoadedWallpaper(REDACTED)"
}

/** Coordinates transient selection, managed-private media, and revision-checked durable state. */
internal class WallpaperController(
    private val repository: AppearanceWallpaperRepository,
    private val store: WallpaperMediaStore,
) {
    private val mutationMutex = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(scope: AppearanceScope): Flow<AppWallpaperObservation> = repository
        .observeWallpaper(scope)
        .map { assignment ->
            AppWallpaperObservation(
                scope = scope,
                assignment = assignment?.takeIf { it.scope == scope }?.toAppAssignment(),
                ready = true,
            )
        }
        .onStart { emit(AppWallpaperObservation.loading(scope)) }
        .distinctUntilChanged()
        .catch { failure ->
            if (failure is CancellationException) throw failure
            emit(AppWallpaperObservation.loading(scope))
        }

    suspend fun inspect(source: Uri): WallpaperInspectionControllerResult =
        when (val result = store.inspect(source)) {
            is WallpaperInspectionResult.Ready -> WallpaperInspectionControllerResult.Ready(result)
            is WallpaperInspectionResult.Failed -> WallpaperInspectionControllerResult.Failed(
                result.reason.toControllerError(),
            )
        }

    suspend fun apply(
        scope: AppearanceScope,
        source: Uri,
        dimPermill: Int,
        focalXPermill: Int,
        focalYPermill: Int,
        expectedRevision: Long?,
        targetStillCurrent: () -> Boolean = { true },
    ): WallpaperApplyControllerResult = mutationMutex.withLock {
        val imported = when (val result = store.import(source)) {
            is WallpaperImportResult.Ready -> result
            is WallpaperImportResult.Failed -> {
                return WallpaperApplyControllerResult.Failed(result.reason.toControllerError())
            }
        }
        var committed = false
        try {
            val mediaId = try {
                AppearanceWallpaperMediaId.fromPrivateStorageToken(imported.mediaId)
            } catch (_: IllegalArgumentException) {
                cleanupFailedImport(imported)
                return WallpaperApplyControllerResult.Failed(WallpaperControllerError.SAVE_FAILED)
            }
            if (!targetStillCurrent()) {
                cleanupFailedImport(imported)
                return WallpaperApplyControllerResult.Failed(WallpaperControllerError.STALE_ASSIGNMENT)
            }
            val expected = try {
                expectedRevision?.let(::AppearanceWallpaperRevision)
            } catch (_: IllegalArgumentException) {
                cleanupFailedImport(imported)
                return WallpaperApplyControllerResult.Failed(WallpaperControllerError.SAVE_FAILED)
            }
            validateProspectiveAssignment(scope, mediaId, expected)?.let { error ->
                cleanupFailedImport(imported)
                return WallpaperApplyControllerResult.Failed(error)
            }
            if (!targetStillCurrent()) {
                cleanupFailedImport(imported)
                return WallpaperApplyControllerResult.Failed(WallpaperControllerError.STALE_ASSIGNMENT)
            }
            return when (
                val result = repository.setWallpaper(
                    scope = scope,
                    mediaId = mediaId,
                    dimPermill = dimPermill,
                    focalXPermill = focalXPermill,
                    focalYPermill = focalYPermill,
                    expectedRevision = expected,
                )
            ) {
                is AppearanceRepositoryResult.Success -> {
                    committed = true
                    result.value.mediaIdNowUnreferenced?.let { stale ->
                        store.delete(stale.toPrivateStorageToken())
                    }
                    WallpaperApplyControllerResult.Success
                }
                AppearanceRepositoryResult.StaleWrite -> {
                    cleanupFailedImport(imported)
                    WallpaperApplyControllerResult.Failed(WallpaperControllerError.STALE_ASSIGNMENT)
                }
                is AppearanceRepositoryResult.LimitExceeded -> {
                    cleanupFailedImport(imported)
                    WallpaperApplyControllerResult.Failed(
                        if (result.limit == AppearanceLimit.WALLPAPER_MEDIA_COUNT) {
                            WallpaperControllerError.QUOTA_EXCEEDED
                        } else {
                            WallpaperControllerError.SAVE_FAILED
                        },
                    )
                }
                else -> {
                    cleanupFailedImport(imported)
                    WallpaperApplyControllerResult.Failed(WallpaperControllerError.SAVE_FAILED)
                }
            }
        } catch (cancelled: CancellationException) {
            if (!committed) {
                withContext(NonCancellable) { cleanupFailedImport(imported) }
            }
            throw cancelled
        }
    }

    suspend fun reset(
        scope: AppearanceScope,
        expectedRevision: Long?,
        targetStillCurrent: () -> Boolean = { true },
    ): WallpaperApplyControllerResult = mutationMutex.withLock {
        val expected = try {
            expectedRevision?.let(::AppearanceWallpaperRevision)
        } catch (_: IllegalArgumentException) {
            return WallpaperApplyControllerResult.Failed(WallpaperControllerError.SAVE_FAILED)
        }
        if (!targetStillCurrent()) {
            return WallpaperApplyControllerResult.Failed(WallpaperControllerError.STALE_ASSIGNMENT)
        }
        return when (val result = repository.resetWallpaper(scope, expected)) {
            is AppearanceRepositoryResult.Success -> {
                result.value.mediaIdNowUnreferenced?.let { stale ->
                    store.delete(stale.toPrivateStorageToken())
                }
                WallpaperApplyControllerResult.Success
            }
            AppearanceRepositoryResult.StaleWrite -> WallpaperApplyControllerResult.Failed(
                WallpaperControllerError.STALE_ASSIGNMENT,
            )
            else -> WallpaperApplyControllerResult.Failed(WallpaperControllerError.SAVE_FAILED)
        }
    }

    suspend fun applyExisting(
        scope: AppearanceScope,
        mediaIdToken: String,
        dimPermill: Int,
        focalXPermill: Int,
        focalYPermill: Int,
        expectedRevision: Long?,
        targetStillCurrent: () -> Boolean = { true },
    ): WallpaperApplyControllerResult = mutationMutex.withLock {
        val mediaId = try {
            AppearanceWallpaperMediaId.fromPrivateStorageToken(mediaIdToken)
        } catch (_: IllegalArgumentException) {
            return WallpaperApplyControllerResult.Failed(WallpaperControllerError.SAVE_FAILED)
        }
        val expected = try {
            expectedRevision?.let(::AppearanceWallpaperRevision)
        } catch (_: IllegalArgumentException) {
            return WallpaperApplyControllerResult.Failed(WallpaperControllerError.SAVE_FAILED)
        }
        if (!targetStillCurrent()) {
            return WallpaperApplyControllerResult.Failed(WallpaperControllerError.STALE_ASSIGNMENT)
        }
        validateProspectiveAssignment(scope, mediaId, expected)?.let { error ->
            return WallpaperApplyControllerResult.Failed(error)
        }
        if (!targetStillCurrent()) {
            return WallpaperApplyControllerResult.Failed(WallpaperControllerError.STALE_ASSIGNMENT)
        }
        return when (
            val result = repository.setWallpaper(
                scope = scope,
                mediaId = mediaId,
                dimPermill = dimPermill,
                focalXPermill = focalXPermill,
                focalYPermill = focalYPermill,
                expectedRevision = expected,
            )
        ) {
            is AppearanceRepositoryResult.Success -> {
                result.value.mediaIdNowUnreferenced?.let { stale ->
                    store.delete(stale.toPrivateStorageToken())
                }
                WallpaperApplyControllerResult.Success
            }
            AppearanceRepositoryResult.StaleWrite -> WallpaperApplyControllerResult.Failed(
                WallpaperControllerError.STALE_ASSIGNMENT,
            )
            else -> WallpaperApplyControllerResult.Failed(WallpaperControllerError.SAVE_FAILED)
        }
    }

    suspend fun loadFirstAvailable(
        candidates: List<AppWallpaperAssignment>,
        preview: Boolean = false,
    ): LoadedWallpaper? {
        candidates.forEach { assignment ->
            when (val result = store.load(assignment.mediaId, preview = preview)) {
                is WallpaperLoadResult.Ready -> if (result.mediaId == assignment.mediaId) {
                    return LoadedWallpaper(assignment = assignment, image = result.image)
                } else {
                    result.release()
                }
                WallpaperLoadResult.Unavailable -> Unit
            }
        }
        return null
    }

    /** Must be called only after durable state opened successfully. */
    suspend fun reconcileManagedFiles(): Boolean = mutationMutex.withLock {
        when (val result = repository.referencedMediaIds()) {
            is AppearanceRepositoryResult.Success -> store.reconcile(
                result.value.mapTo(HashSet()) { it.toPrivateStorageToken() },
            )
            else -> false
        }
    }

    private suspend fun cleanupFailedImport(imported: WallpaperImportResult.Ready) {
        if (!imported.created) return
        val references = repository.referencedMediaIds()
        if (
            references is AppearanceRepositoryResult.Success &&
            references.value.none { it.toPrivateStorageToken() == imported.mediaId }
        ) {
            store.delete(imported.mediaId)
        }
    }

    private suspend fun validateProspectiveAssignment(
        scope: AppearanceScope,
        mediaId: AppearanceWallpaperMediaId,
        expectedRevision: AppearanceWallpaperRevision?,
    ): WallpaperControllerError? = when (
        val projection = repository.prospectiveMediaIdsForSet(
            scope = scope,
            mediaId = mediaId,
            expectedRevision = expectedRevision,
        )
    ) {
        is AppearanceRepositoryResult.Success -> when (
            store.validateDurableQuota(
                projection.value.mapTo(HashSet()) { it.toPrivateStorageToken() },
            )
        ) {
            DurableWallpaperQuotaResult.WITHIN_LIMIT -> null
            DurableWallpaperQuotaResult.LIMIT_EXCEEDED -> WallpaperControllerError.QUOTA_EXCEEDED
            DurableWallpaperQuotaResult.INVALID_STATE -> WallpaperControllerError.SAVE_FAILED
        }
        AppearanceRepositoryResult.StaleWrite -> WallpaperControllerError.STALE_ASSIGNMENT
        is AppearanceRepositoryResult.LimitExceeded -> {
            if (projection.limit == AppearanceLimit.WALLPAPER_MEDIA_COUNT) {
                WallpaperControllerError.QUOTA_EXCEEDED
            } else {
                WallpaperControllerError.SAVE_FAILED
            }
        }
        else -> WallpaperControllerError.SAVE_FAILED
    }
}

internal fun resolveWallpaperCandidates(
    conversationScope: AppearanceScope.Conversation?,
    conversationObservation: AppWallpaperObservation,
    globalScope: AppearanceScope.Screen,
    globalObservation: AppWallpaperObservation,
    highContrast: Boolean,
): List<AppWallpaperAssignment> {
    if (
        highContrast ||
        !globalObservation.isReadyFor(globalScope) ||
        (conversationScope != null && !conversationObservation.isReadyFor(conversationScope))
    ) {
        return emptyList()
    }
    return buildList(2) {
        conversationScope
            ?.let(conversationObservation::assignmentFor)
            ?.let(::add)
        globalObservation.assignmentFor(globalScope)?.let { global ->
            if (none { it.mediaId == global.mediaId }) add(global)
        }
    }
}

private fun AppearanceWallpaperAssignment.toAppAssignment(): AppWallpaperAssignment =
    AppWallpaperAssignment(
        scope = scope,
        mediaId = mediaId.toPrivateStorageToken(),
        dimPermill = dimPermill,
        focalXPermill = focalXPermill,
        focalYPermill = focalYPermill,
        revision = revision.value,
    )

private fun WallpaperMediaFailure.toControllerError(): WallpaperControllerError = when (this) {
    WallpaperMediaFailure.INVALID_SOURCE,
    WallpaperMediaFailure.UNAVAILABLE,
    WallpaperMediaFailure.MIME_MISMATCH,
    WallpaperMediaFailure.MALFORMED,
    -> WallpaperControllerError.SOURCE_UNAVAILABLE
    WallpaperMediaFailure.UNSUPPORTED_TYPE -> WallpaperControllerError.UNSUPPORTED_SOURCE
    WallpaperMediaFailure.INPUT_TOO_LARGE,
    WallpaperMediaFailure.SOURCE_DIMENSIONS_TOO_LARGE,
    WallpaperMediaFailure.OUTPUT_TOO_LARGE,
    -> WallpaperControllerError.SOURCE_TOO_LARGE
    WallpaperMediaFailure.QUOTA_EXCEEDED -> WallpaperControllerError.QUOTA_EXCEEDED
    WallpaperMediaFailure.STORAGE_FAILURE -> WallpaperControllerError.SAVE_FAILED
}
