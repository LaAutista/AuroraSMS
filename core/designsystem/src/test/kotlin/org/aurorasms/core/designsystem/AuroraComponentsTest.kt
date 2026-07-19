// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.designsystem

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class AuroraComponentsTest {
    @Test
    fun backGlyphMirrorsOnlyItsHorizontalGeometryInRightToLeftLayouts() {
        listOf(0.18f, 0.46f, 0.82f).forEach { leftToRightFraction ->
            assertEquals(
                leftToRightFraction,
                auroraBackGlyphXFraction(leftToRightFraction, LayoutDirection.Ltr),
                0f,
            )
            assertEquals(
                1f - leftToRightFraction,
                auroraBackGlyphXFraction(leftToRightFraction, LayoutDirection.Rtl),
                0f,
            )
        }
    }
}
