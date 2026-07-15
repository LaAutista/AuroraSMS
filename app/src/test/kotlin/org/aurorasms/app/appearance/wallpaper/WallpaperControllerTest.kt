// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import android.net.InertTestUri
import android.net.Uri
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.AppearanceLimit
import org.aurorasms.core.state.AppearanceParticipantSetKey
import org.aurorasms.core.state.AppearanceRepositoryResult
import org.aurorasms.core.state.AppearanceScope
import org.aurorasms.core.state.AppearanceScreenScope
import org.aurorasms.core.state.AppearanceStorageOperation
import org.aurorasms.core.state.AppearanceWallpaperAssignment
import org.aurorasms.core.state.AppearanceWallpaperMediaId
import org.aurorasms.core.state.AppearanceWallpaperMutation
import org.aurorasms.core.state.AppearanceWallpaperRepository
import org.aurorasms.core.state.AppearanceWallpaperRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperControllerTest {
    private val globalScope = AppearanceScope.Screen(AppearanceScreenScope.GLOBAL_THREAD)
    private val conversationScope = AppearanceScope.Conversation(
        participantSetKey = AppearanceParticipantSetKey.fromParticipants(
            listOf(ParticipantAddress("+15555550100")),
        ),
        providerThreadId = ProviderThreadId(7L),
    )

    @Test
    fun resolutionUsesConversationThenGlobalThenSolid() {
        val global = assignment(globalScope, media = "b")
        val conversation = assignment(conversationScope, media = "a")

        assertEquals(
            listOf(conversation, global),
            resolveWallpaperCandidates(
                conversationScope = conversationScope,
                conversationObservation = ready(conversationScope, conversation),
                globalScope = globalScope,
                globalObservation = ready(globalScope, global),
                highContrast = false,
            ),
        )
        assertEquals(
            listOf(global),
            resolveWallpaperCandidates(
                conversationScope = null,
                conversationObservation = AppWallpaperObservation.unavailable(),
                globalScope = globalScope,
                globalObservation = ready(globalScope, global),
                highContrast = false,
            ),
        )
        assertTrue(
            resolveWallpaperCandidates(
                conversationScope = conversationScope,
                conversationObservation = ready(conversationScope, conversation),
                globalScope = globalScope,
                globalObservation = ready(globalScope, global),
                highContrast = true,
            ).isEmpty(),
        )
    }

    @Test
    fun staleTargetObservationIsRejected() {
        val assignment = assignment(conversationScope, media = "a")
        assertTrue(
            resolveWallpaperCandidates(
                conversationScope = conversationScope,
                conversationObservation = ready(globalScope, assignment.copy(scope = globalScope)),
                globalScope = globalScope,
                globalObservation = AppWallpaperObservation.loading(globalScope),
                highContrast = false,
            ).isEmpty(),
        )
    }

    @Test
    fun resolutionWaitsForEveryApplicableWallpaperScope() {
        val global = assignment(globalScope, media = "b")
        val conversation = assignment(conversationScope, media = "a")

        assertTrue(
            resolveWallpaperCandidates(
                conversationScope = conversationScope,
                conversationObservation = AppWallpaperObservation.loading(conversationScope),
                globalScope = globalScope,
                globalObservation = ready(globalScope, global),
                highContrast = false,
            ).isEmpty(),
        )
        assertTrue(
            resolveWallpaperCandidates(
                conversationScope = conversationScope,
                conversationObservation = ready(conversationScope, conversation),
                globalScope = globalScope,
                globalObservation = AppWallpaperObservation.loading(globalScope),
                highContrast = false,
            ).isEmpty(),
        )
    }

    @Test
    fun focalAlignmentUsesPhysicalCoordinatesInBothDirections() {
        val left = AbsoluteFocalAlignment(0, 0)
        val right = AbsoluteFocalAlignment(1_000, 1_000)
        val image = IntSize(200, 300)
        val viewport = IntSize(100, 100)

        assertEquals(left.align(image, viewport, LayoutDirection.Ltr), left.align(image, viewport, LayoutDirection.Rtl))
        assertEquals(0, left.align(image, viewport, LayoutDirection.Ltr).x)
        assertEquals(-100, right.align(image, viewport, LayoutDirection.Ltr).x)
        assertEquals(-200, right.align(image, viewport, LayoutDirection.Ltr).y)
    }

    @Test
    fun applyValidatesExactProspectiveQuotaBeforeSettingWallpaper() = runTest {
        val calls = mutableListOf<String>()
        val importedId = mediaId('a')
        val retainedId = mediaId('b')
        val repository = FakeWallpaperRepository(calls).apply {
            projectionResult = AppearanceRepositoryResult.Success(setOf(importedId, retainedId))
        }
        val store = FakeWallpaperMediaStore(calls).apply {
            importResult = WallpaperImportResult.Ready(
                mediaId = importedId.toPrivateStorageToken(),
                created = true,
            )
        }
        val controller = WallpaperController(repository, store)

        val result = controller.apply(
            scope = conversationScope,
            source = InertTestUri(),
            dimPermill = 700,
            focalXPermill = 125,
            focalYPermill = 875,
            expectedRevision = 4L,
        )

        assertEquals(WallpaperApplyControllerResult.Success, result)
        assertEquals(listOf("import", "projection", "quota", "set"), calls)
        assertEquals(
            setOf(importedId.toPrivateStorageToken(), retainedId.toPrivateStorageToken()),
            store.lastProspectiveMediaIds,
        )
        assertEquals(conversationScope, repository.lastProjectionScope)
        assertEquals(importedId, repository.lastProjectionMediaId)
        assertEquals(AppearanceWallpaperRevision(4L), repository.lastProjectionExpectedRevision)
        assertEquals(1, repository.setCalls)
        assertTrue(store.deletedMediaIds.isEmpty())
    }

    @Test
    fun applyRejectsTargetThatChangesAfterProspectiveQuotaValidation() = runTest {
        val calls = mutableListOf<String>()
        val importedId = mediaId('c')
        val repository = FakeWallpaperRepository(calls).apply {
            projectionResult = AppearanceRepositoryResult.Success(setOf(importedId))
            referencedResult = AppearanceRepositoryResult.Success(emptySet())
        }
        val store = FakeWallpaperMediaStore(calls).apply {
            importResult = WallpaperImportResult.Ready(
                mediaId = importedId.toPrivateStorageToken(),
                created = true,
            )
        }
        val controller = WallpaperController(repository, store)
        var targetChecks = 0

        val result = controller.apply(
            scope = conversationScope,
            source = InertTestUri(),
            dimPermill = 700,
            focalXPermill = 125,
            focalYPermill = 875,
            expectedRevision = 4L,
            targetStillCurrent = { ++targetChecks == 1 },
        )

        assertEquals(
            WallpaperApplyControllerResult.Failed(WallpaperControllerError.STALE_ASSIGNMENT),
            result,
        )
        assertEquals(2, targetChecks)
        assertEquals(listOf("import", "projection", "quota", "references", "delete"), calls)
        assertEquals(0, repository.setCalls)
        assertEquals(1, store.quotaCalls)
        assertEquals(listOf(importedId.toPrivateStorageToken()), store.deletedMediaIds)
    }

    @Test
    fun applyExistingRejectsTargetThatChangesAfterProspectiveQuotaValidation() = runTest {
        val calls = mutableListOf<String>()
        val existingId = mediaId('d')
        val repository = FakeWallpaperRepository(calls).apply {
            projectionResult = AppearanceRepositoryResult.Success(setOf(existingId))
        }
        val store = FakeWallpaperMediaStore(calls)
        val controller = WallpaperController(repository, store)
        var targetChecks = 0

        val result = controller.applyExisting(
            scope = globalScope,
            mediaIdToken = existingId.toPrivateStorageToken(),
            dimPermill = 650,
            focalXPermill = 500,
            focalYPermill = 500,
            expectedRevision = 9L,
            targetStillCurrent = { ++targetChecks == 1 },
        )

        assertEquals(
            WallpaperApplyControllerResult.Failed(WallpaperControllerError.STALE_ASSIGNMENT),
            result,
        )
        assertEquals(2, targetChecks)
        assertEquals(listOf("projection", "quota"), calls)
        assertEquals(0, repository.setCalls)
        assertEquals(1, store.quotaCalls)
        assertTrue(store.deletedMediaIds.isEmpty())
    }

    @Test
    fun prospectiveAndDurableQuotaFailuresCleanNewImportWithoutSettingWallpaper() = runTest {
        val failures = listOf(
            ProjectionFailure(
                name = "stale projection",
                projection = AppearanceRepositoryResult.StaleWrite,
                quota = DurableWallpaperQuotaResult.WITHIN_LIMIT,
                expectedError = WallpaperControllerError.STALE_ASSIGNMENT,
                expectedQuotaCalls = 0,
            ),
            ProjectionFailure(
                name = "media-count projection limit",
                projection = AppearanceRepositoryResult.LimitExceeded(
                    AppearanceLimit.WALLPAPER_MEDIA_COUNT,
                ),
                quota = DurableWallpaperQuotaResult.WITHIN_LIMIT,
                expectedError = WallpaperControllerError.QUOTA_EXCEEDED,
                expectedQuotaCalls = 0,
            ),
            ProjectionFailure(
                name = "corrupt projection",
                projection = AppearanceRepositoryResult.CorruptData,
                quota = DurableWallpaperQuotaResult.WITHIN_LIMIT,
                expectedError = WallpaperControllerError.SAVE_FAILED,
                expectedQuotaCalls = 0,
            ),
            ProjectionFailure(
                name = "projection storage failure",
                projection = AppearanceRepositoryResult.StorageFailure(
                    AppearanceStorageOperation.WALLPAPER_MEDIA_REFERENCES,
                ),
                quota = DurableWallpaperQuotaResult.WITHIN_LIMIT,
                expectedError = WallpaperControllerError.SAVE_FAILED,
                expectedQuotaCalls = 0,
            ),
            ProjectionFailure(
                name = "durable byte limit",
                projection = null,
                quota = DurableWallpaperQuotaResult.LIMIT_EXCEEDED,
                expectedError = WallpaperControllerError.QUOTA_EXCEEDED,
                expectedQuotaCalls = 1,
            ),
            ProjectionFailure(
                name = "invalid durable files",
                projection = null,
                quota = DurableWallpaperQuotaResult.INVALID_STATE,
                expectedError = WallpaperControllerError.SAVE_FAILED,
                expectedQuotaCalls = 1,
            ),
        )

        failures.forEachIndexed { index, failure ->
            val importedId = mediaId("cdef01"[index])
            val repository = FakeWallpaperRepository().apply {
                projectionResult = failure.projection ?: AppearanceRepositoryResult.Success(
                    setOf(importedId),
                )
                referencedResult = AppearanceRepositoryResult.Success(emptySet())
            }
            val store = FakeWallpaperMediaStore().apply {
                importResult = WallpaperImportResult.Ready(
                    mediaId = importedId.toPrivateStorageToken(),
                    created = true,
                )
                quotaResult = failure.quota
            }
            val controller = WallpaperController(repository, store)

            val result = controller.apply(
                scope = globalScope,
                source = InertTestUri(),
                dimPermill = 650,
                focalXPermill = 500,
                focalYPermill = 500,
                expectedRevision = null,
            )

            assertEquals(
                failure.name,
                WallpaperApplyControllerResult.Failed(failure.expectedError),
                result,
            )
            assertEquals(failure.name, 0, repository.setCalls)
            assertEquals(failure.name, failure.expectedQuotaCalls, store.quotaCalls)
            assertEquals(
                failure.name,
                listOf(importedId.toPrivateStorageToken()),
                store.deletedMediaIds,
            )
        }
    }

    private fun ready(
        scope: AppearanceScope,
        assignment: AppWallpaperAssignment,
    ): AppWallpaperObservation = AppWallpaperObservation(scope, assignment, ready = true)

    private fun assignment(scope: AppearanceScope, media: String): AppWallpaperAssignment =
        AppWallpaperAssignment(
            scope = scope,
            mediaId = "sha256-v1:${media.repeat(64)}",
            dimPermill = 650,
            focalXPermill = 500,
            focalYPermill = 500,
            revision = 1L,
        )

    private fun mediaId(character: Char): AppearanceWallpaperMediaId =
        AppearanceWallpaperMediaId.fromPrivateStorageToken("sha256-v1:${character.toString().repeat(64)}")
}

private data class ProjectionFailure(
    val name: String,
    val projection: AppearanceRepositoryResult<Set<AppearanceWallpaperMediaId>>?,
    val quota: DurableWallpaperQuotaResult,
    val expectedError: WallpaperControllerError,
    val expectedQuotaCalls: Int,
)

private class FakeWallpaperRepository(
    private val calls: MutableList<String>? = null,
) : AppearanceWallpaperRepository {
    var projectionResult: AppearanceRepositoryResult<Set<AppearanceWallpaperMediaId>> =
        AppearanceRepositoryResult.Success(emptySet())
    var referencedResult: AppearanceRepositoryResult<Set<AppearanceWallpaperMediaId>> =
        AppearanceRepositoryResult.Success(emptySet())
    var setResult: AppearanceRepositoryResult<AppearanceWallpaperMutation> =
        AppearanceRepositoryResult.Success(
            AppearanceWallpaperMutation(
                assignment = null,
                mediaIdNowUnreferenced = null,
            ),
        )
    var lastProjectionScope: AppearanceScope? = null
    var lastProjectionMediaId: AppearanceWallpaperMediaId? = null
    var lastProjectionExpectedRevision: AppearanceWallpaperRevision? = null
    var setCalls: Int = 0

    override fun observeWallpaper(scope: AppearanceScope): Flow<AppearanceWallpaperAssignment?> =
        flowOf(null)

    override suspend fun setWallpaper(
        scope: AppearanceScope,
        mediaId: AppearanceWallpaperMediaId,
        dimPermill: Int,
        focalXPermill: Int,
        focalYPermill: Int,
        expectedRevision: AppearanceWallpaperRevision?,
    ): AppearanceRepositoryResult<AppearanceWallpaperMutation> {
        calls?.add("set")
        setCalls += 1
        return setResult
    }

    override suspend fun resetWallpaper(
        scope: AppearanceScope,
        expectedRevision: AppearanceWallpaperRevision?,
    ): AppearanceRepositoryResult<AppearanceWallpaperMutation> = setResult

    override suspend fun prospectiveMediaIdsForSet(
        scope: AppearanceScope,
        mediaId: AppearanceWallpaperMediaId,
        expectedRevision: AppearanceWallpaperRevision?,
    ): AppearanceRepositoryResult<Set<AppearanceWallpaperMediaId>> {
        calls?.add("projection")
        lastProjectionScope = scope
        lastProjectionMediaId = mediaId
        lastProjectionExpectedRevision = expectedRevision
        return projectionResult
    }

    override suspend fun referencedMediaIds(): AppearanceRepositoryResult<Set<AppearanceWallpaperMediaId>> {
        calls?.add("references")
        return referencedResult
    }
}

private class FakeWallpaperMediaStore(
    private val calls: MutableList<String>? = null,
) : WallpaperMediaStore {
    var importResult: WallpaperImportResult = WallpaperImportResult.Failed(
        WallpaperMediaFailure.UNAVAILABLE,
    )
    var quotaResult: DurableWallpaperQuotaResult = DurableWallpaperQuotaResult.WITHIN_LIMIT
    var quotaCalls: Int = 0
    var lastProspectiveMediaIds: Set<String>? = null
    val deletedMediaIds = mutableListOf<String>()

    override suspend fun inspect(source: Uri): WallpaperInspectionResult =
        WallpaperInspectionResult.Failed(WallpaperMediaFailure.UNAVAILABLE)

    override suspend fun import(source: Uri): WallpaperImportResult {
        calls?.add("import")
        return importResult
    }

    override suspend fun load(mediaId: String, preview: Boolean): WallpaperLoadResult =
        WallpaperLoadResult.Unavailable

    override suspend fun delete(mediaId: String): Boolean {
        calls?.add("delete")
        deletedMediaIds += mediaId
        return true
    }

    override suspend fun reconcile(referencedMediaIds: Set<String>): Boolean = true

    override suspend fun validateDurableQuota(
        prospectiveMediaIds: Set<String>,
    ): DurableWallpaperQuotaResult {
        calls?.add("quota")
        quotaCalls += 1
        lastProspectiveMediaIds = prospectiveMediaIds
        return quotaResult
    }
}
