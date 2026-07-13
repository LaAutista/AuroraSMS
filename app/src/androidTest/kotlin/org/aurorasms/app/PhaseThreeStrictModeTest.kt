// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aurorasms.app.strictmode.SanitizedViolationType
import org.aurorasms.app.strictmode.StrictModeViolationLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhaseThreeStrictModeTest {
    @Before
    fun resetSanitizedLedger() {
        StrictModeViolationLedger.reset()
    }

    @Test
    fun ledgerRetainsOnlyFixedCategoriesAndCounts() {
        StrictModeViolationLedger.record("DiskReadViolation")
        StrictModeViolationLedger.record("private-path-or-content-cannot-become-a-key")
        StrictModeViolationLedger.record("private-path-or-content-cannot-become-a-key")

        val snapshot = StrictModeViolationLedger.snapshot()
        assertEquals(1L, snapshot[SanitizedViolationType.DISK_READ])
        assertEquals(2L, snapshot[SanitizedViolationType.OTHER])
        assertEquals(2, snapshot.size)
        assertFalse(snapshot.keys.any { it.name.contains("private", ignoreCase = true) })
    }
}
