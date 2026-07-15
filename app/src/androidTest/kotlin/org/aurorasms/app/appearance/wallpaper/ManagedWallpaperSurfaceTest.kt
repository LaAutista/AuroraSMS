// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import org.aurorasms.app.appearance.ScopedAppearanceTestActivity
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.AppearanceParticipantSetKey
import org.aurorasms.core.state.AppearanceRepositoryResult
import org.aurorasms.core.state.AppearanceScope
import org.aurorasms.core.state.AppearanceScreenScope
import org.aurorasms.core.state.AppearanceWallpaperAssignment
import org.aurorasms.core.state.AppearanceWallpaperMediaId
import org.aurorasms.core.state.AppearanceWallpaperMutation
import org.aurorasms.core.state.AppearanceWallpaperRepository
import org.aurorasms.core.state.AppearanceWallpaperRevision
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedWallpaperSurfaceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ScopedAppearanceTestActivity>()

    @Test
    fun targetChangeClearsPublishedAWhileBLoadsAndFailedBLeavesSolid() {
        val store = ControlledWallpaperMediaStore()
        val aLoad = store.enqueue(ASSIGNMENT_A.mediaId)
        val bLoad = store.enqueue(ASSIGNMENT_B.mediaId)
        val harness = showSurface(store, listOf(ASSIGNMENT_A))
        val aBitmap = TestBitmap(Color.Red)

        aLoad.awaitStarted()
        aLoad.completeReady(aBitmap)
        waitForDominant(red = true)
        assertFalse(aBitmap.native.isRecycled)

        compose.runOnIdle { harness.candidates.value = listOf(ASSIGNMENT_B) }
        bLoad.awaitStarted()

        assertSolid()
        assertTrue(aBitmap.native.isRecycled)

        bLoad.completeUnavailable()
        bLoad.awaitReturned()
        assertSolid()

        disposeSurface()
    }

    @Test
    fun lateAResultCannotPublishOverBAndEveryHandedOffBitmapIsReleased() {
        val store = ControlledWallpaperMediaStore()
        val aLoad = store.enqueue(ASSIGNMENT_A.mediaId, ignoreCancellation = true)
        val bLoad = store.enqueue(ASSIGNMENT_B.mediaId)
        val harness = showSurface(store, listOf(ASSIGNMENT_A))
        val lateABitmap = TestBitmap(Color.Red)
        val bBitmap = TestBitmap(Color.Blue)

        aLoad.awaitStarted()
        assertSolid()

        compose.runOnIdle { harness.candidates.value = listOf(ASSIGNMENT_B) }
        bLoad.awaitStarted()
        assertSolid()

        aLoad.completeReady(lateABitmap)
        aLoad.awaitReturned()
        waitUntil { lateABitmap.native.isRecycled }
        assertSolid()

        bLoad.completeReady(bBitmap)
        bLoad.awaitReturned()
        waitForDominant(red = false)
        assertFalse(bBitmap.native.isRecycled)

        disposeSurface()
        waitUntil { bBitmap.native.isRecycled }
        assertTrue(lateABitmap.native.isRecycled)
    }

    @Test
    fun targetEpochChangeClearsAndReloadsAnEqualInheritedGlobalCandidate() {
        val store = ControlledWallpaperMediaStore()
        val initialLoad = store.enqueue(GLOBAL_ASSIGNMENT.mediaId)
        val replacementLoad = store.enqueue(GLOBAL_ASSIGNMENT.mediaId)
        val harness = showSurface(store, listOf(GLOBAL_ASSIGNMENT))
        val initialBitmap = TestBitmap(Color.Red)
        val replacementBitmap = TestBitmap(Color.Blue)

        initialLoad.awaitStarted()
        initialLoad.completeReady(initialBitmap)
        waitForDominant(red = true)

        compose.runOnIdle {
            harness.targetKey.value = SYNTHETIC_TARGET_B
        }
        replacementLoad.awaitStarted()
        assertSolid()
        assertTrue(initialBitmap.native.isRecycled)

        replacementLoad.completeReady(replacementBitmap)
        replacementLoad.awaitReturned()
        waitForDominant(red = false)

        disposeSurface()
        waitUntil { replacementBitmap.native.isRecycled }
    }

    @Test
    fun revisionAndPresentationChangeClearBeforeReloadingTheSameMedia() {
        val store = ControlledWallpaperMediaStore()
        val firstLoad = store.enqueue(ASSIGNMENT_A.mediaId)
        val secondLoad = store.enqueue(ASSIGNMENT_A.mediaId)
        val harness = showSurface(store, listOf(ASSIGNMENT_A))
        val firstBitmap = TestBitmap(Color.Red)
        val secondBitmap = TestBitmap(Color.Blue)

        firstLoad.awaitStarted()
        firstLoad.completeReady(firstBitmap)
        waitForDominant(red = true)

        compose.runOnIdle {
            harness.candidates.value = listOf(
                ASSIGNMENT_A.copy(
                    revision = ASSIGNMENT_A.revision + 1L,
                    dimPermill = 500,
                    focalXPermill = 125,
                    focalYPermill = 875,
                ),
            )
        }
        secondLoad.awaitStarted()
        assertSolid()
        assertTrue(firstBitmap.native.isRecycled)

        secondLoad.completeReady(secondBitmap)
        secondLoad.awaitReturned()
        waitForDominant(red = false)

        disposeSurface()
        waitUntil { secondBitmap.native.isRecycled }
    }

    private fun showSurface(
        store: ControlledWallpaperMediaStore,
        initialCandidates: List<AppWallpaperAssignment>,
    ): SurfaceHarness {
        val candidates = mutableStateOf(initialCandidates)
        val targetKey = mutableStateOf(SYNTHETIC_TARGET_A)
        val controller = WallpaperController(InertWallpaperRepository, store)
        compose.runOnIdle {
            compose.activity.setContent {
                val requestEpoch = remember(targetKey.value) { WallpaperRenderRequestEpoch() }
                MaterialTheme(colorScheme = darkColorScheme(surface = SAFE_SOLID)) {
                    Box(
                        modifier = Modifier
                            .size(SURFACE_SIZE)
                            .testTag(SURFACE_TAG),
                    ) {
                        ManagedWallpaperSurface(
                            controller = controller,
                            requestEpoch = requestEpoch,
                            candidates = candidates.value,
                        )
                    }
                }
            }
        }
        return SurfaceHarness(candidates = candidates, targetKey = targetKey)
    }

    private fun disposeSurface() {
        compose.runOnIdle { compose.activity.setContent {} }
    }

    private fun PendingLoad.awaitStarted() = waitUntil { started.isCompleted }

    private fun PendingLoad.awaitReturned() = waitUntil(returned::get)

    private fun waitUntil(predicate: () -> Boolean) {
        compose.waitUntil(timeoutMillis = TIMEOUT_MILLIS, condition = predicate)
    }

    private fun assertSolid() {
        val actual = centerPixel()
        assertColorNear(SAFE_SOLID, actual)
    }

    private fun waitForDominant(red: Boolean) {
        waitUntil {
            val pixel = centerPixel()
            if (red) {
                pixel.red > 0.45f && pixel.red > pixel.blue * 3f
            } else {
                pixel.blue > 0.45f && pixel.blue > pixel.red * 3f
            }
        }
    }

    private fun centerPixel(): Color {
        val pixels = compose.onNodeWithTag(SURFACE_TAG).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    private fun assertColorNear(expected: Color, actual: Color) {
        val tolerance = 1f / 255f
        assertTrue(
            "red expected=$expected actual=$actual",
            kotlin.math.abs(expected.red - actual.red) <= tolerance,
        )
        assertTrue(
            "green expected=$expected actual=$actual",
            kotlin.math.abs(expected.green - actual.green) <= tolerance,
        )
        assertTrue(
            "blue expected=$expected actual=$actual",
            kotlin.math.abs(expected.blue - actual.blue) <= tolerance,
        )
        assertTrue(
            "alpha expected=$expected actual=$actual",
            kotlin.math.abs(expected.alpha - actual.alpha) <= tolerance,
        )
    }

    private companion object {
        const val SURFACE_TAG = "managed-wallpaper-surface"
        const val TIMEOUT_MILLIS = 5_000L
        val SURFACE_SIZE = 64.dp
        val SAFE_SOLID = Color(0xff17324d)

        val CONVERSATION_A = AppearanceScope.Conversation(
            participantSetKey = AppearanceParticipantSetKey.fromParticipants(
                listOf(ParticipantAddress("synthetic-a@example.invalid")),
            ),
            providerThreadId = ProviderThreadId(7_001L),
        )
        val CONVERSATION_B = AppearanceScope.Conversation(
            participantSetKey = AppearanceParticipantSetKey.fromParticipants(
                listOf(ParticipantAddress("synthetic-b@example.invalid")),
            ),
            providerThreadId = ProviderThreadId(7_002L),
        )
        val GLOBAL_SCOPE = AppearanceScope.Screen(AppearanceScreenScope.GLOBAL_THREAD)
        val ASSIGNMENT_A = assignment(CONVERSATION_A, 'a', revision = 1L)
        val ASSIGNMENT_B = assignment(CONVERSATION_B, 'b', revision = 2L)
        val GLOBAL_ASSIGNMENT = assignment(GLOBAL_SCOPE, 'g', revision = 3L)
        const val SYNTHETIC_TARGET_A = "synthetic-render-target-a"
        const val SYNTHETIC_TARGET_B = "synthetic-render-target-b"

        fun assignment(
            scope: AppearanceScope,
            media: Char,
            revision: Long,
        ): AppWallpaperAssignment = AppWallpaperAssignment(
            scope = scope,
            mediaId = "sha256-v1:${media.toString().repeat(64)}",
            dimPermill = 350,
            focalXPermill = 500,
            focalYPermill = 500,
            revision = revision,
        )
    }
}

private data class SurfaceHarness(
    val candidates: MutableState<List<AppWallpaperAssignment>>,
    val targetKey: MutableState<String>,
)

private class TestBitmap(color: Color) {
    val native: Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
        eraseColor(color.toArgb())
    }
    val image = native.asImageBitmap()
}

private class PendingLoad(
    val mediaId: String,
    val ignoreCancellation: Boolean,
) {
    val started = CompletableDeferred<Unit>()
    val result = CompletableDeferred<WallpaperLoadResult>()
    val returned = AtomicBoolean(false)

    fun completeReady(bitmap: TestBitmap) {
        check(
            result.complete(
                WallpaperLoadResult.Ready(
                    mediaId = mediaId,
                    image = bitmap.image,
                    width = bitmap.native.width,
                    height = bitmap.native.height,
                ),
            ),
        )
    }

    fun completeUnavailable() {
        check(result.complete(WallpaperLoadResult.Unavailable))
    }
}

private class ControlledWallpaperMediaStore : WallpaperMediaStore {
    private val pending = mutableMapOf<String, ArrayDeque<PendingLoad>>()

    fun enqueue(mediaId: String, ignoreCancellation: Boolean = false): PendingLoad =
        PendingLoad(mediaId, ignoreCancellation).also { load ->
            synchronized(pending) {
                pending.getOrPut(mediaId, ::ArrayDeque).addLast(load)
            }
        }

    override suspend fun inspect(source: Uri): WallpaperInspectionResult =
        WallpaperInspectionResult.Failed(WallpaperMediaFailure.UNAVAILABLE)

    override suspend fun import(
        source: Uri,
        referencedMediaIds: Set<String>,
        onCandidateCreated: (WallpaperImportResult.Ready) -> Unit,
    ): WallpaperImportResult =
        WallpaperImportResult.Failed(WallpaperMediaFailure.UNAVAILABLE)

    override suspend fun load(mediaId: String, preview: Boolean): WallpaperLoadResult {
        val next = synchronized(pending) {
            pending[mediaId]?.pollFirst()
                ?: error("No controlled load queued for redacted media ID")
        }
        next.started.complete(Unit)
        return try {
            if (next.ignoreCancellation) {
                withContext(NonCancellable) { next.result.await() }
            } else {
                next.result.await()
            }
        } finally {
            next.returned.set(true)
        }
    }

    override suspend fun deleteIfUnreferenced(
        mediaId: String,
        referencedMediaIds: Set<String>,
    ): Boolean = true

    override suspend fun reconcile(
        referencedMediaIds: Set<String>,
    ): ManagedWallpaperReconcileResult = ManagedWallpaperReconcileResult.COMPLETE

    override suspend fun validateDurableQuota(
        prospectiveMediaIds: Set<String>,
    ): DurableWallpaperQuotaResult = DurableWallpaperQuotaResult.WITHIN_LIMIT
}

private object InertWallpaperRepository : AppearanceWallpaperRepository {
    override fun observeWallpaper(scope: AppearanceScope): Flow<AppearanceWallpaperAssignment?> =
        flowOf(null)

    override suspend fun setWallpaper(
        scope: AppearanceScope,
        mediaId: AppearanceWallpaperMediaId,
        dimPermill: Int,
        focalXPermill: Int,
        focalYPermill: Int,
        expectedRevision: AppearanceWallpaperRevision?,
    ): AppearanceRepositoryResult<AppearanceWallpaperMutation> =
        error("Mutation is outside the renderer harness")

    override suspend fun resetWallpaper(
        scope: AppearanceScope,
        expectedRevision: AppearanceWallpaperRevision?,
    ): AppearanceRepositoryResult<AppearanceWallpaperMutation> =
        error("Mutation is outside the renderer harness")

    override suspend fun prospectiveMediaIdsForSet(
        scope: AppearanceScope,
        mediaId: AppearanceWallpaperMediaId,
        expectedRevision: AppearanceWallpaperRevision?,
    ): AppearanceRepositoryResult<Set<AppearanceWallpaperMediaId>> =
        error("Mutation is outside the renderer harness")

    override suspend fun referencedMediaIds(): AppearanceRepositoryResult<Set<AppearanceWallpaperMediaId>> =
        error("Reconciliation is outside the renderer harness")
}
