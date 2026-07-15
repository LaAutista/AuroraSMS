// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.appearance.wallpaper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperResourceOwnerTest {
    @Test
    fun replacementDisposalAndLateHandoffReleaseEveryResourceExactlyOnce() {
        val released = mutableListOf<String>()
        val owner = WallpaperResourceOwner<String>(released::add)

        assertTrue(owner.replace("first"))
        assertTrue(owner.replace("second"))
        owner.dispose()
        assertFalse(owner.replace("late"))
        owner.dispose()

        assertTrue(released == listOf("first", "second", "late"))
    }
}
