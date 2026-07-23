// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import android.net.InertTestUri
import android.net.Uri
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CancellationException
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
        assertEquals(
            listOf("references", "reconcile", "quota", "import", "projection", "quota", "set"),
            calls,
        )
        assertEquals(emptySet<String>(), store.lastImportReferenceIds)
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
        assertEquals(
            listOf(
                "references",
                "reconcile",
                "quota",
                "import",
                "projection",
                "quota",
                "references",
                "delete",
            ),
            calls,
        )
        assertEquals(0, repository.setCalls)
        assertEquals(2, store.quotaCalls)
        assertEquals(listOf(importedId.toPrivateStorageToken()), store.deletedMediaIds)
    }

    @Test
    fun lateRepositoryStaleWriteDeletesCreatedUnreferencedCandidate() = runTest {
        val calls = mutableListOf<String>()
        val importedId = mediaId('7')
        val repository = FakeWallpaperRepository(calls).apply {
            projectionResult = AppearanceRepositoryResult.Success(setOf(importedId))
            referencedResult = AppearanceRepositoryResult.Success(emptySet())
            setResult = AppearanceRepositoryResult.StaleWrite
        }
        val store = FakeWallpaperMediaStore(calls).apply {
            importResult = WallpaperImportResult.Ready(
                mediaId = importedId.toPrivateStorageToken(),
                created = true,
            )
        }
        val controller = WallpaperController(repository, store)

        assertEquals(
            WallpaperApplyControllerResult.Failed(WallpaperControllerError.STALE_ASSIGNMENT),
            controller.apply(
                scope = globalScope,
                source = InertTestUri(),
                dimPermill = 650,
                focalXPermill = 500,
                focalYPermill = 500,
                expectedRevision = null,
            ),
        )

        assertEquals(
            listOf(
                "references",
                "reconcile",
                "quota",
                "import",
                "projection",
                "quota",
                "set",
                "references",
                "delete",
            ),
            calls,
        )
        assertEquals(1, repository.setCalls)
        assertEquals(2, repository.referencedCalls)
        assertEquals(listOf(importedId.toPrivateStorageToken()), store.deletedMediaIds)
        assertEquals(listOf(emptySet<String>()), store.deletionReferenceSnapshots)
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
                expectedQuotaCalls = 1,
            ),
            ProjectionFailure(
                name = "media-count projection limit",
                projection = AppearanceRepositoryResult.LimitExceeded(
                    AppearanceLimit.WALLPAPER_MEDIA_COUNT,
                ),
                quota = DurableWallpaperQuotaResult.WITHIN_LIMIT,
                expectedError = WallpaperControllerError.QUOTA_EXCEEDED,
                expectedQuotaCalls = 1,
            ),
            ProjectionFailure(
                name = "corrupt projection",
                projection = AppearanceRepositoryResult.CorruptData,
                quota = DurableWallpaperQuotaResult.WITHIN_LIMIT,
                expectedError = WallpaperControllerError.SAVE_FAILED,
                expectedQuotaCalls = 1,
            ),
            ProjectionFailure(
                name = "projection storage failure",
                projection = AppearanceRepositoryResult.StorageFailure(
                    AppearanceStorageOperation.WALLPAPER_MEDIA_REFERENCES,
                ),
                quota = DurableWallpaperQuotaResult.WITHIN_LIMIT,
                expectedError = WallpaperControllerError.SAVE_FAILED,
                expectedQuotaCalls = 1,
            ),
            ProjectionFailure(
                name = "durable byte limit",
                projection = null,
                quota = DurableWallpaperQuotaResult.LIMIT_EXCEEDED,
                expectedError = WallpaperControllerError.QUOTA_EXCEEDED,
                expectedQuotaCalls = 2,
            ),
            ProjectionFailure(
                name = "invalid durable files",
                projection = null,
                quota = DurableWallpaperQuotaResult.INVALID_STATE,
                expectedError = WallpaperControllerError.SAVE_FAILED,
                expectedQuotaCalls = 2,
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
                queuedQuotaResults += DurableWallpaperQuotaResult.WITHIN_LIMIT
                queuedQuotaResults += failure.quota
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

    @Test
    fun applyReferencePreflightFailuresHaveNoImportCasOrDeleteSideEffects() = runTest {
        val failures: List<AppearanceRepositoryResult<Set<AppearanceWallpaperMediaId>>> = listOf(
            AppearanceRepositoryResult.StaleWrite,
            AppearanceRepositoryResult.CorruptData,
            AppearanceRepositoryResult.StorageFailure(
                AppearanceStorageOperation.WALLPAPER_MEDIA_REFERENCES,
            ),
            AppearanceRepositoryResult.LimitExceeded(
                AppearanceLimit.WALLPAPER_MEDIA_COUNT,
            ),
        )

        failures.forEach { failure ->
            val calls = mutableListOf<String>()
            val repository = FakeWallpaperRepository(calls).apply {
                referencedResult = failure
            }
            val store = FakeWallpaperMediaStore(calls)
            val controller = WallpaperController(repository, store)

            assertEquals(
                WallpaperApplyControllerResult.Failed(WallpaperControllerError.SAVE_FAILED),
                controller.apply(
                    scope = globalScope,
                    source = InertTestUri(),
                    dimPermill = 650,
                    focalXPermill = 500,
                    focalYPermill = 500,
                    expectedRevision = null,
                ),
            )
            assertEquals(listOf("references"), calls)
            assertNoImportCasOrDelete(repository, store)
            assertEquals(0, store.reconcileCalls)
            assertEquals(0, store.quotaCalls)
        }
    }

    @Test
    fun applyReconcilePreflightFailuresHaveNoImportCasOrDeleteSideEffects() = runTest {
        ManagedWallpaperReconcileResult.entries
            .filterNot { it == ManagedWallpaperReconcileResult.COMPLETE }
            .forEach { failure ->
                val calls = mutableListOf<String>()
                val repository = FakeWallpaperRepository(calls)
                val store = FakeWallpaperMediaStore(calls).apply {
                    reconcileResult = failure
                }
                val controller = WallpaperController(repository, store)

                assertEquals(
                    failure.name,
                    WallpaperApplyControllerResult.Failed(WallpaperControllerError.SAVE_FAILED),
                    controller.apply(
                        scope = globalScope,
                        source = InertTestUri(),
                        dimPermill = 650,
                        focalXPermill = 500,
                        focalYPermill = 500,
                        expectedRevision = null,
                    ),
                )
                assertEquals(failure.name, listOf("references", "reconcile"), calls)
                assertNoImportCasOrDelete(repository, store)
                assertEquals(failure.name, 1, store.reconcileCalls)
                assertEquals(failure.name, 0, store.quotaCalls)
            }
    }

    @Test
    fun applyCurrentQuotaPreflightFailuresHaveNoImportCasOrDeleteSideEffects() = runTest {
        listOf(
            DurableWallpaperQuotaResult.LIMIT_EXCEEDED,
            DurableWallpaperQuotaResult.INVALID_STATE,
        ).forEach { failure ->
            val calls = mutableListOf<String>()
            val repository = FakeWallpaperRepository(calls)
            val store = FakeWallpaperMediaStore(calls).apply {
                quotaResult = failure
            }
            val controller = WallpaperController(repository, store)

            assertEquals(
                failure.name,
                WallpaperApplyControllerResult.Failed(WallpaperControllerError.SAVE_FAILED),
                controller.apply(
                    scope = globalScope,
                    source = InertTestUri(),
                    dimPermill = 650,
                    focalXPermill = 500,
                    focalYPermill = 500,
                    expectedRevision = null,
                ),
            )
            assertEquals(failure.name, listOf("references", "reconcile", "quota"), calls)
            assertNoImportCasOrDelete(repository, store)
            assertEquals(failure.name, 1, store.reconcileCalls)
            assertEquals(failure.name, 1, store.quotaCalls)
        }
    }

    @Test
    fun cancellationImmediatelyAfterCandidateCallbackUsesFreshAuthorityForCleanup() = runTest {
        val calls = mutableListOf<String>()
        val candidate = mediaId('7')
        val repository = FakeWallpaperRepository(calls).apply {
            queuedReferencedResults += AppearanceRepositoryResult.Success(emptySet())
            queuedReferencedResults += AppearanceRepositoryResult.Success(emptySet())
        }
        val store = FakeWallpaperMediaStore(calls).apply {
            importResult = WallpaperImportResult.Ready(
                mediaId = candidate.toPrivateStorageToken(),
                created = true,
            )
            cancelAfterCandidateCallback = true
        }
        val controller = WallpaperController(repository, store)

        var cancellationObserved = false
        try {
            controller.apply(
                scope = globalScope,
                source = InertTestUri(),
                dimPermill = 650,
                focalXPermill = 500,
                focalYPermill = 500,
                expectedRevision = null,
            )
        } catch (_: CancellationException) {
            cancellationObserved = true
        }

        assertTrue(cancellationObserved)
        assertEquals(
            listOf("references", "reconcile", "quota", "import", "references", "delete"),
            calls,
        )
        assertEquals(2, repository.referencedCalls)
        assertEquals(listOf(emptySet<String>()), store.deletionReferenceSnapshots)
        assertEquals(listOf(candidate.toPrivateStorageToken()), store.deletedMediaIds)
        assertEquals(0, repository.setCalls)
    }

    @Test
    fun cancellationAfterCommittedSetUsesFreshAuthorityAndPreservesCandidate() = runTest {
        val calls = mutableListOf<String>()
        val candidate = mediaId('6')
        val candidateToken = candidate.toPrivateStorageToken()
        val repository = FakeWallpaperRepository(calls).apply {
            projectionResult = AppearanceRepositoryResult.Success(setOf(candidate))
            queuedReferencedResults += AppearanceRepositoryResult.Success(emptySet())
            queuedReferencedResults += AppearanceRepositoryResult.Success(setOf(candidate))
            cancelAfterSetResult = true
        }
        val store = FakeWallpaperMediaStore(calls).apply {
            importResult = WallpaperImportResult.Ready(candidateToken, created = true)
        }
        val controller = WallpaperController(repository, store)

        var cancellationObserved = false
        try {
            controller.apply(
                scope = globalScope,
                source = InertTestUri(),
                dimPermill = 650,
                focalXPermill = 500,
                focalYPermill = 500,
                expectedRevision = null,
            )
        } catch (_: CancellationException) {
            cancellationObserved = true
        }

        assertTrue(cancellationObserved)
        assertEquals(
            listOf(
                "references",
                "reconcile",
                "quota",
                "import",
                "projection",
                "quota",
                "set",
                "references",
                "delete",
            ),
            calls,
        )
        assertEquals(1, repository.setCalls)
        assertEquals(2, repository.referencedCalls)
        assertEquals(listOf(setOf(candidateToken)), store.deletionReferenceSnapshots)
        assertTrue(store.deletedMediaIds.isEmpty())
    }

    @Test
    fun cleanupSnapshotContainingCandidatePreventsPhysicalDelete() = runTest {
        val calls = mutableListOf<String>()
        val candidate = mediaId('8')
        val candidateToken = candidate.toPrivateStorageToken()
        val repository = FakeWallpaperRepository(calls).apply {
            queuedReferencedResults += AppearanceRepositoryResult.Success(emptySet())
            queuedReferencedResults += AppearanceRepositoryResult.Success(setOf(candidate))
        }
        val store = FakeWallpaperMediaStore(calls).apply {
            importResult = WallpaperImportResult.Ready(candidateToken, created = true)
        }
        val controller = WallpaperController(repository, store)

        assertEquals(
            WallpaperApplyControllerResult.Failed(WallpaperControllerError.STALE_ASSIGNMENT),
            controller.apply(
                scope = globalScope,
                source = InertTestUri(),
                dimPermill = 650,
                focalXPermill = 500,
                focalYPermill = 500,
                expectedRevision = null,
                targetStillCurrent = { false },
            ),
        )

        assertEquals(
            listOf("references", "reconcile", "quota", "import", "references", "delete"),
            calls,
        )
        assertEquals(listOf(setOf(candidateToken)), store.deletionReferenceSnapshots)
        assertTrue(store.deletedMediaIds.isEmpty())
        assertEquals(0, repository.setCalls)
    }

    @Test
    fun deduplicatedImportIsNeverCleanupDeleted() = runTest {
        val calls = mutableListOf<String>()
        val existing = mediaId('9')
        val repository = FakeWallpaperRepository(calls).apply {
            referencedResult = AppearanceRepositoryResult.Success(setOf(existing))
        }
        val store = FakeWallpaperMediaStore(calls).apply {
            importResult = WallpaperImportResult.Ready(
                mediaId = existing.toPrivateStorageToken(),
                created = false,
            )
        }
        val controller = WallpaperController(repository, store)

        assertEquals(
            WallpaperApplyControllerResult.Failed(WallpaperControllerError.STALE_ASSIGNMENT),
            controller.apply(
                scope = globalScope,
                source = InertTestUri(),
                dimPermill = 650,
                focalXPermill = 500,
                focalYPermill = 500,
                expectedRevision = null,
                targetStillCurrent = { false },
            ),
        )

        assertEquals(listOf("references", "reconcile", "quota", "import"), calls)
        assertEquals(1, repository.referencedCalls)
        assertEquals(0, store.deleteCalls)
        assertTrue(store.deletedMediaIds.isEmpty())
    }

    @Test
    fun postCommitStaleMediaIsDeletedOnlyWithFreshExcludingAuthority() = runTest {
        val calls = mutableListOf<String>()
        val imported = mediaId('a')
        val stale = mediaId('b')
        val staleToken = stale.toPrivateStorageToken()
        val repository = FakeWallpaperRepository(calls).apply {
            projectionResult = AppearanceRepositoryResult.Success(setOf(imported))
            queuedReferencedResults += AppearanceRepositoryResult.Success(setOf(stale))
            queuedReferencedResults += AppearanceRepositoryResult.Success(setOf(imported))
            setResult = successfulMutation(stale)
        }
        val store = FakeWallpaperMediaStore(calls).apply {
            importResult = WallpaperImportResult.Ready(
                mediaId = imported.toPrivateStorageToken(),
                created = true,
            )
        }
        val controller = WallpaperController(repository, store)

        assertEquals(
            WallpaperApplyControllerResult.Success,
            controller.apply(
                scope = globalScope,
                source = InertTestUri(),
                dimPermill = 650,
                focalXPermill = 500,
                focalYPermill = 500,
                expectedRevision = null,
            ),
        )

        assertEquals(
            listOf(
                "references",
                "reconcile",
                "quota",
                "import",
                "projection",
                "quota",
                "set",
                "references",
                "delete",
            ),
            calls,
        )
        assertEquals(listOf(setOf(imported.toPrivateStorageToken())), store.deletionReferenceSnapshots)
        assertEquals(listOf(staleToken), store.deletedMediaIds)
    }

    @Test
    fun postCommitFreshSnapshotStillContainingStalePreventsPhysicalDelete() = runTest {
        val calls = mutableListOf<String>()
        val imported = mediaId('c')
        val stale = mediaId('d')
        val importedToken = imported.toPrivateStorageToken()
        val staleToken = stale.toPrivateStorageToken()
        val repository = FakeWallpaperRepository(calls).apply {
            projectionResult = AppearanceRepositoryResult.Success(setOf(imported, stale))
            queuedReferencedResults += AppearanceRepositoryResult.Success(setOf(stale))
            queuedReferencedResults += AppearanceRepositoryResult.Success(setOf(imported, stale))
            setResult = successfulMutation(stale)
        }
        val store = FakeWallpaperMediaStore(calls).apply {
            importResult = WallpaperImportResult.Ready(
                mediaId = importedToken,
                created = false,
            )
        }
        val controller = WallpaperController(repository, store)

        assertEquals(
            WallpaperApplyControllerResult.Success,
            controller.apply(
                scope = globalScope,
                source = InertTestUri(),
                dimPermill = 650,
                focalXPermill = 500,
                focalYPermill = 500,
                expectedRevision = null,
            ),
        )

        assertEquals(
            listOf(
                "references",
                "reconcile",
                "quota",
                "import",
                "projection",
                "quota",
                "set",
                "references",
                "delete",
            ),
            calls,
        )
        assertEquals(listOf(setOf(importedToken, staleToken)), store.deletionReferenceSnapshots)
        assertTrue(store.deletedMediaIds.isEmpty())
    }

    @Test
    fun postCommitAuthorityFailurePreservesSuccessAndStaleMedia() = runTest {
        val calls = mutableListOf<String>()
        val imported = mediaId('1')
        val stale = mediaId('2')
        val repository = FakeWallpaperRepository(calls).apply {
            projectionResult = AppearanceRepositoryResult.Success(setOf(imported))
            queuedReferencedResults += AppearanceRepositoryResult.Success(setOf(stale))
            queuedReferencedResults += AppearanceRepositoryResult.StorageFailure(
                AppearanceStorageOperation.WALLPAPER_MEDIA_REFERENCES,
            )
            setResult = successfulMutation(stale)
        }
        val store = FakeWallpaperMediaStore(calls).apply {
            importResult = WallpaperImportResult.Ready(
                mediaId = imported.toPrivateStorageToken(),
                created = true,
            )
        }
        val controller = WallpaperController(repository, store)

        assertEquals(
            WallpaperApplyControllerResult.Success,
            controller.apply(
                scope = globalScope,
                source = InertTestUri(),
                dimPermill = 650,
                focalXPermill = 500,
                focalYPermill = 500,
                expectedRevision = null,
            ),
        )

        assertEquals(
            listOf(
                "references",
                "reconcile",
                "quota",
                "import",
                "projection",
                "quota",
                "set",
                "references",
            ),
            calls,
        )
        assertEquals(2, repository.referencedCalls)
        assertEquals(0, store.deleteCalls)
        assertTrue(store.deletedMediaIds.isEmpty())
    }

    @Test
    fun resetUsesAuthoritativeStaleCleanupWithoutImportPreflight() = runTest {
        val calls = mutableListOf<String>()
        val stale = mediaId('e')
        val repository = FakeWallpaperRepository(calls).apply {
            setResult = successfulMutation(stale)
            referencedResult = AppearanceRepositoryResult.Success(emptySet())
        }
        val store = FakeWallpaperMediaStore(calls)
        val controller = WallpaperController(repository, store)

        assertEquals(
            WallpaperApplyControllerResult.Success,
            controller.reset(
                scope = globalScope,
                expectedRevision = 5L,
            ),
        )

        assertEquals(listOf("reset", "references", "delete"), calls)
        assertEquals(1, repository.resetCalls)
        assertEquals(0, store.importCalls)
        assertEquals(0, store.reconcileCalls)
        assertEquals(0, store.quotaCalls)
        assertEquals(listOf(stale.toPrivateStorageToken()), store.deletedMediaIds)
    }

    @Test
    fun applyExistingUsesAuthoritativeStaleCleanupWithoutImportPreflight() = runTest {
        val calls = mutableListOf<String>()
        val existing = mediaId('f')
        val stale = mediaId('0')
        val repository = FakeWallpaperRepository(calls).apply {
            projectionResult = AppearanceRepositoryResult.Success(setOf(existing))
            setResult = successfulMutation(stale)
            referencedResult = AppearanceRepositoryResult.Success(setOf(existing))
        }
        val store = FakeWallpaperMediaStore(calls)
        val controller = WallpaperController(repository, store)

        assertEquals(
            WallpaperApplyControllerResult.Success,
            controller.applyExisting(
                scope = globalScope,
                mediaIdToken = existing.toPrivateStorageToken(),
                dimPermill = 650,
                focalXPermill = 500,
                focalYPermill = 500,
                expectedRevision = 6L,
            ),
        )

        assertEquals(listOf("projection", "quota", "set", "references", "delete"), calls)
        assertEquals(0, store.importCalls)
        assertEquals(0, store.reconcileCalls)
        assertEquals(1, store.quotaCalls)
        assertEquals(listOf(stale.toPrivateStorageToken()), store.deletedMediaIds)
        assertEquals(
            listOf(setOf(existing.toPrivateStorageToken())),
            store.deletionReferenceSnapshots,
        )
    }

    private fun assertNoImportCasOrDelete(
        repository: FakeWallpaperRepository,
        store: FakeWallpaperMediaStore,
    ) {
        assertEquals(0, store.importCalls)
        assertEquals(0, repository.setCalls)
        assertEquals(0, store.deleteCalls)
        assertTrue(store.deletedMediaIds.isEmpty())
    }

    private fun successfulMutation(
        stale: AppearanceWallpaperMediaId,
    ): AppearanceRepositoryResult<AppearanceWallpaperMutation> = AppearanceRepositoryResult.Success(
        AppearanceWallpaperMutation(
            assignment = null,
            mediaIdNowUnreferenced = stale,
        ),
    )

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
    var resetCalls: Int = 0
    var referencedCalls: Int = 0
    var cancelAfterSetResult: Boolean = false
    val queuedReferencedResults =
        ArrayDeque<AppearanceRepositoryResult<Set<AppearanceWallpaperMediaId>>>()

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
        if (cancelAfterSetResult) {
            throw CancellationException("cancel after committed set result")
        }
        return setResult
    }

    override suspend fun resetWallpaper(
        scope: AppearanceScope,
        expectedRevision: AppearanceWallpaperRevision?,
    ): AppearanceRepositoryResult<AppearanceWallpaperMutation> {
        calls?.add("reset")
        resetCalls += 1
        return setResult
    }

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
        referencedCalls += 1
        return queuedReferencedResults.removeFirstOrNull() ?: referencedResult
    }
}

private class FakeWallpaperMediaStore(
    private val calls: MutableList<String>? = null,
) : WallpaperMediaStore {
    var importResult: WallpaperImportResult = WallpaperImportResult.Failed(
        WallpaperMediaFailure.UNAVAILABLE,
    )
    var quotaResult: DurableWallpaperQuotaResult = DurableWallpaperQuotaResult.WITHIN_LIMIT
    val queuedQuotaResults = ArrayDeque<DurableWallpaperQuotaResult>()
    var reconcileResult: ManagedWallpaperReconcileResult = ManagedWallpaperReconcileResult.COMPLETE
    var cancelAfterCandidateCallback: Boolean = false
    var importCalls: Int = 0
    var deleteCalls: Int = 0
    var reconcileCalls: Int = 0
    var quotaCalls: Int = 0
    var lastProspectiveMediaIds: Set<String>? = null
    var lastImportReferenceIds: Set<String>? = null
    val deletedMediaIds = mutableListOf<String>()
    val deletionReferenceSnapshots = mutableListOf<Set<String>>()

    override suspend fun inspect(source: Uri): WallpaperInspectionResult =
        WallpaperInspectionResult.Failed(WallpaperMediaFailure.UNAVAILABLE)

    override suspend fun import(
        source: Uri,
        referencedMediaIds: Set<String>,
        onCandidateCreated: (WallpaperImportResult.Ready) -> Unit,
    ): WallpaperImportResult {
        calls?.add("import")
        importCalls += 1
        lastImportReferenceIds = referencedMediaIds
        val candidate = (importResult as? WallpaperImportResult.Ready)
            ?.takeIf(WallpaperImportResult.Ready::created)
        candidate?.let(onCandidateCreated)
        if (candidate != null && cancelAfterCandidateCallback) {
            throw CancellationException("cancel after candidate callback")
        }
        return importResult
    }

    override suspend fun load(mediaId: String, preview: Boolean): WallpaperLoadResult =
        WallpaperLoadResult.Unavailable

    override suspend fun deleteIfUnreferenced(
        mediaId: String,
        referencedMediaIds: Set<String>,
    ): Boolean {
        calls?.add("delete")
        deleteCalls += 1
        deletionReferenceSnapshots += referencedMediaIds
        return (mediaId !in referencedMediaIds).also { deleted ->
            if (deleted) deletedMediaIds += mediaId
        }
    }

    override suspend fun reconcile(
        referencedMediaIds: Set<String>,
    ): ManagedWallpaperReconcileResult {
        calls?.add("reconcile")
        reconcileCalls += 1
        return reconcileResult
    }

    override suspend fun validateDurableQuota(
        prospectiveMediaIds: Set<String>,
    ): DurableWallpaperQuotaResult {
        calls?.add("quota")
        quotaCalls += 1
        lastProspectiveMediaIds = prospectiveMediaIds
        return queuedQuotaResults.removeFirstOrNull() ?: quotaResult
    }
}
