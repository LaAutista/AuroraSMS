// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Opaque identity for one Thread-target render request.
 *
 * A fresh instance invalidates an otherwise equal inherited candidate list without retaining the
 * route, participant identity, media token, or any other private target data.
 */
@Immutable
internal class WallpaperRenderRequestEpoch {
    override fun toString(): String = "WallpaperRenderRequestEpoch(REDACTED)"
}

/**
 * Target-keyed renderer that synchronously starts from the theme solid on every request/candidate
 * change. A previous conversation bitmap therefore cannot survive into a new target while loading.
 */
@Composable
internal fun BoxScope.ManagedWallpaperSurface(
    controller: WallpaperController,
    requestEpoch: WallpaperRenderRequestEpoch,
    candidates: List<AppWallpaperAssignment>,
) {
    val safeSolid = MaterialTheme.colorScheme.surface
    key(controller, requestEpoch, candidates) {
        var loaded by remember { mutableStateOf<LoadedWallpaper?>(null) }
        val owner = remember { WallpaperResourceOwner<LoadedWallpaper>(LoadedWallpaper::release) }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(safeSolid),
        )
        DisposableEffect(owner) {
            onDispose(owner::dispose)
        }
        LaunchedEffect(controller, requestEpoch, candidates) {
            var acquired: LoadedWallpaper? = null
            try {
                acquired = controller.loadFirstAvailable(candidates)
                currentCoroutineContext().ensureActive()
                val retained = acquired
                if (owner.replace(retained)) {
                    loaded = retained
                }
                acquired = null
            } finally {
                acquired?.release()
            }
        }
        loaded?.let { wallpaper ->
            WallpaperBitmapSurface(
                image = wallpaper.image,
                dimPermill = wallpaper.assignment.dimPermill,
                focalXPermill = wallpaper.assignment.focalXPermill,
                focalYPermill = wallpaper.assignment.focalYPermill,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** A decorative static crop used by both the real timeline and the staged editor preview. */
@Composable
internal fun WallpaperBitmapSurface(
    image: ImageBitmap,
    dimPermill: Int,
    focalXPermill: Int,
    focalYPermill: Int,
    modifier: Modifier = Modifier,
) {
    require(dimPermill in 350..900)
    require(focalXPermill in 0..1_000)
    require(focalYPermill in 0..1_000)
    Box(modifier = modifier) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = AbsoluteFocalAlignment(focalXPermill, focalYPermill),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = dimPermill / 1_000f)),
        )
    }
}

/** Ignores layout direction because stored focal coordinates describe physical image pixels. */
internal data class AbsoluteFocalAlignment(
    val focalXPermill: Int,
    val focalYPermill: Int,
) : Alignment {
    init {
        require(focalXPermill in 0..1_000)
        require(focalYPermill in 0..1_000)
    }

    override fun align(
        size: IntSize,
        space: IntSize,
        layoutDirection: LayoutDirection,
    ): IntOffset = IntOffset(
        x = ((space.width - size.width) * (focalXPermill / 1_000f)).roundToInt(),
        y = ((space.height - size.height) * (focalYPermill / 1_000f)).roundToInt(),
    )
}
