// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Small, original symbols that avoid a heavyweight icon or ImageVector dependency. */
enum class AuroraGlyph {
    BACK,
    SEARCH,
    MORE,
    CALL,
    ADD,
    SEND,
    RETRY,
    REVIEW,
}

/**
 * A localized, accessible icon action rendered with original Canvas paths and no bundled artwork.
 */
@Composable
fun AuroraIconAction(
    glyph: AuroraGlyph,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
) {
    require(contentDescription.isNotBlank()) { "An Aurora icon action needs a description" }
    val visualTokens = LocalAuroraVisualTokens.current
    val resolvedTint = if (tint == Color.Unspecified) {
        glyph.defaultTint(visualTokens)
    } else {
        tint
    }
    IconButton(
        onClick = onClick,
        modifier = modifier.semantics(mergeDescendants = true) {
            this.contentDescription = contentDescription
        },
        enabled = enabled,
    ) {
        Canvas(
            modifier = Modifier
                .clearAndSetSemantics { }
                .size(ICON_SIZE),
        ) {
            drawAuroraGlyph(
                glyph = glyph,
                color = if (enabled) {
                    resolvedTint
                } else {
                    resolvedTint.copy(alpha = DISABLED_ICON_ALPHA)
                },
            )
        }
    }
}

/**
 * A static, code-only Aurora field suitable beneath a screen scrim or empty state.
 * It deliberately carries no semantics and contains no private or generated image asset.
 */
@Composable
fun AuroraBackdrop(modifier: Modifier = Modifier) {
    val visualTokens = LocalAuroraVisualTokens.current
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(
                listOf(
                    visualTokens.nearBlack,
                    visualTokens.deepNight,
                    visualTokens.nearBlack,
                ),
            ),
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color.Transparent,
                            visualTokens.violet.copy(alpha = AURORA_VIOLET_ALPHA),
                            visualTokens.magenta.copy(alpha = AURORA_MAGENTA_ALPHA),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(
                            visualTokens.cyan.copy(alpha = AURORA_CYAN_ALPHA),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(visualTokens.wallpaperScrim),
        )
    }
}

private fun AuroraGlyph.defaultTint(tokens: AuroraVisualTokens): Color = when (this) {
    AuroraGlyph.BACK, AuroraGlyph.MORE, AuroraGlyph.CALL, AuroraGlyph.SEND -> tokens.cyan
    AuroraGlyph.SEARCH, AuroraGlyph.ADD, AuroraGlyph.RETRY, AuroraGlyph.REVIEW -> tokens.violet
}

private fun DrawScope.drawAuroraGlyph(glyph: AuroraGlyph, color: Color) {
    val strokeWidth = ICON_STROKE_WIDTH.toPx()
    val stroke = Stroke(
        width = strokeWidth,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )
    val width = size.width
    val height = size.height
    when (glyph) {
        AuroraGlyph.BACK -> {
            val tipX = auroraBackGlyphXFraction(0.18f, layoutDirection) * width
            val shaftEndX = auroraBackGlyphXFraction(0.82f, layoutDirection) * width
            val arrowheadEndX = auroraBackGlyphXFraction(0.46f, layoutDirection) * width
            drawLine(
                color,
                Offset(tipX, height * 0.50f),
                Offset(shaftEndX, height * 0.50f),
                strokeWidth,
                StrokeCap.Round,
            )
            drawLine(
                color,
                Offset(tipX, height * 0.50f),
                Offset(arrowheadEndX, height * 0.22f),
                strokeWidth,
                StrokeCap.Round,
            )
            drawLine(
                color,
                Offset(tipX, height * 0.50f),
                Offset(arrowheadEndX, height * 0.78f),
                strokeWidth,
                StrokeCap.Round,
            )
        }
        AuroraGlyph.SEARCH -> {
            drawCircle(
                color = color,
                radius = size.minDimension * 0.27f,
                center = Offset(width * 0.42f, height * 0.42f),
                style = stroke,
            )
            drawLine(color, Offset(width * 0.61f, height * 0.61f), Offset(width * 0.83f, height * 0.83f), strokeWidth, StrokeCap.Round)
        }
        AuroraGlyph.MORE -> listOf(0.25f, 0.50f, 0.75f).forEach { fraction ->
            drawCircle(
                color = color,
                radius = strokeWidth * 0.72f,
                center = Offset(width * 0.50f, height * fraction),
            )
        }
        AuroraGlyph.CALL -> {
            val path = Path().apply {
                moveTo(width * 0.25f, height * 0.16f)
                lineTo(width * 0.40f, height * 0.32f)
                cubicTo(width * 0.43f, height * 0.36f, width * 0.43f, height * 0.42f, width * 0.39f, height * 0.47f)
                lineTo(width * 0.34f, height * 0.53f)
                cubicTo(width * 0.43f, height * 0.68f, width * 0.54f, height * 0.77f, width * 0.69f, height * 0.82f)
                lineTo(width * 0.75f, height * 0.72f)
                cubicTo(width * 0.78f, height * 0.67f, width * 0.84f, height * 0.66f, width * 0.89f, height * 0.69f)
                lineTo(width * 0.96f, height * 0.75f)
                cubicTo(width * 0.88f, height * 0.90f, width * 0.75f, height * 0.94f, width * 0.60f, height * 0.88f)
                cubicTo(width * 0.37f, height * 0.79f, width * 0.20f, height * 0.62f, width * 0.12f, height * 0.39f)
                cubicTo(width * 0.08f, height * 0.27f, width * 0.13f, height * 0.19f, width * 0.25f, height * 0.16f)
                close()
            }
            drawPath(path = path, color = color, style = stroke)
        }
        AuroraGlyph.ADD -> {
            drawLine(color, Offset(width * 0.20f, height * 0.50f), Offset(width * 0.80f, height * 0.50f), strokeWidth, StrokeCap.Round)
            drawLine(color, Offset(width * 0.50f, height * 0.20f), Offset(width * 0.50f, height * 0.80f), strokeWidth, StrokeCap.Round)
        }
        AuroraGlyph.SEND -> {
            val path = Path().apply {
                moveTo(width * 0.14f, height * 0.17f)
                lineTo(width * 0.88f, height * 0.50f)
                lineTo(width * 0.14f, height * 0.83f)
                lineTo(width * 0.30f, height * 0.53f)
                lineTo(width * 0.88f, height * 0.50f)
                lineTo(width * 0.30f, height * 0.47f)
                close()
            }
            drawPath(path = path, color = color, style = stroke)
        }
        AuroraGlyph.RETRY -> {
            drawArc(
                color = color,
                startAngle = -55f,
                sweepAngle = 285f,
                useCenter = false,
                topLeft = Offset(width * 0.18f, height * 0.18f),
                size = Size(width * 0.64f, height * 0.64f),
                style = stroke,
            )
            drawLine(color, Offset(width * 0.17f, height * 0.26f), Offset(width * 0.18f, height * 0.48f), strokeWidth, StrokeCap.Round)
            drawLine(color, Offset(width * 0.17f, height * 0.26f), Offset(width * 0.38f, height * 0.28f), strokeWidth, StrokeCap.Round)
        }
        AuroraGlyph.REVIEW -> {
            drawOval(
                color = color,
                topLeft = Offset(width * 0.13f, height * 0.13f),
                size = Size(width * 0.74f, height * 0.74f),
                style = stroke,
            )
            drawLine(color, Offset(width * 0.50f, height * 0.31f), Offset(width * 0.50f, height * 0.58f), strokeWidth, StrokeCap.Round)
            drawCircle(color, strokeWidth * 0.66f, Offset(width * 0.50f, height * 0.72f))
        }
    }
}

internal fun auroraBackGlyphXFraction(
    leftToRightFraction: Float,
    layoutDirection: LayoutDirection,
): Float = if (layoutDirection == LayoutDirection.Rtl) {
    1f - leftToRightFraction
} else {
    leftToRightFraction
}

private val ICON_SIZE = 24.dp
private val ICON_STROKE_WIDTH = 2.25.dp
private const val DISABLED_ICON_ALPHA: Float = 0.38f
private const val AURORA_VIOLET_ALPHA: Float = 0.40f
private const val AURORA_MAGENTA_ALPHA: Float = 0.24f
private const val AURORA_CYAN_ALPHA: Float = 0.18f
