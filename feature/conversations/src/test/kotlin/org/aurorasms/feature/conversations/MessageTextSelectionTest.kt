// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageTextSelectionTest {
    @Test
    fun copiesOnlyTheExactForwardOrReversedRange() {
        val displayed = "alpha private omega"

        assertEquals("private", selectedMessageTextOrNull(displayed, 6, 13))
        assertEquals("private", selectedMessageTextOrNull(displayed, 13, 6))
    }

    @Test
    fun preservesSelectedWhitespaceWithoutAddingSurroundingContent() {
        assertEquals("  ", selectedMessageTextOrNull("a  b", 1, 3))
    }

    @Test
    fun collapsedOrOutOfBoundsRangesFailClosed() {
        assertNull(selectedMessageTextOrNull("message", 3, 3))
        assertNull(selectedMessageTextOrNull("message", -1, 3))
        assertNull(selectedMessageTextOrNull("message", 2, 8))
    }
}
